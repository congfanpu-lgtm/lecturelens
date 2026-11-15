package com.agentflow.worker.agent;

import com.agentflow.common.llm.ChatMessage;
import com.agentflow.common.llm.ChatRequest;
import com.agentflow.common.llm.ChatResponse;
import com.agentflow.common.llm.ToolCall;
import com.agentflow.common.llm.ToolSpec;
import com.agentflow.common.trace.TraceStage;
import com.agentflow.worker.agent.tool.AgentTool;
import com.agentflow.worker.llm.LlmGateway;
import com.agentflow.worker.llm.LlmModelProperties;
import com.agentflow.worker.trace.TraceEmitter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ReAct 循环:思考 → 调工具 → 看结果 → … → 给出最后的回答。用哪些工具、什么提示词由调用方的 {@link Profile} 决定。
 *
 * <ul>
 *   <li>每一步完整结束(LLM 决策 + 全部工具结果)后写 {@link AgentCheckpoint};重新投递时从下一步续跑,
 *       已调用过的工具直接复用存档结果,不重复调用。</li>
 *   <li>工具出错不抛:以 "ERROR: ..." 回给模型让它自己纠正;只有 LLM 调用失败才让子任务进入重试。</li>
 *   <li>同一次运行内相同 name+参数的调用走缓存,防止模型原地打转。</li>
 *   <li>最多 {@link #MAX_STEPS} 步,超出则去掉工具强制作答。</li>
 * </ul>
 * ponytail: 上下文靠"单个工具结果截断 2000 字 × 最多 10 步"封顶,没有按 token 预算裁剪历史
 * (tool_call 与结果必须成对,不能像 AgentContext 那样逐条丢);步数调大时再加摘要压缩。
 */
@Slf4j
@Component
public class AgentLoop {

    static final int MAX_STEPS = 10;   // 6 → 10:不拆的链式问题要一个 agent 连查几环(FRAMES 试跑里最多用到 9 步)
    static final int PREVIEW_CHARS = 300;
    static final String ERROR = "ERROR: ";
    static final String WORKER_ID = hostname();
    private static final ObjectMapper OM = new ObjectMapper();

    private final LlmGateway gateway;
    private final AgentCheckpoint checkpoint;
    private final TraceEmitter trace;
    private final LlmModelProperties props;

    public AgentLoop(LlmGateway gateway, AgentCheckpoint checkpoint, TraceEmitter trace, LlmModelProperties props) {
        this.gateway = gateway;
        this.checkpoint = checkpoint;
        this.trace = trace;
        this.props = props;
    }

    /**
     * 一次运行用什么提示词、哪些工具、模型输出上限(token)、单个工具结果给模型看多长(字符)。
     * 循环本身对所有 agent 都一样。
     *
     * <p>failOnToolError:工具自己出故障(不是模型把参数写错)时让整个运行失败、交给重试,而不是把错误当结果给模型看。
     * 课程问答用它:检索服务挂了的时候,模型看到的"检索失败"会被它当成"课里没讲"。
     * 工具是外部网站这类偶尔失败很正常的场景可以关掉,让模型换个工具再查。
     */
    public record Profile(String systemPrompt, List<AgentTool> tools, int maxTokens, int maxToolChars,
                          boolean failOnToolError) {
    }

    /** 循环的结果:模型最后的回答原文、用了几步、token 用量、全部已完成的步骤(含续跑前存档的)。 */
    public record Outcome(String content, int stepCount, Usage usage, List<AgentStep> history) {
    }

    /**
     * ReAct 循环本身,和具体是哪种 agent 无关。seed 是 system 之后、循环之前的消息(问题、历史对话等)。
     * 只能调用 profile 里的工具:模型要别的工具会得到 "ERROR: 未知工具"。
     */
    public Outcome converse(AgentRun run, Profile profile, List<ChatMessage> seed) {
        Map<String, AgentTool> available = profile.tools().stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));
        List<ToolSpec> specs = profile.tools().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.parametersSchema())).toList();
        List<ToolSpec> offered = specs.isEmpty() ? null : specs;   // 没有工具的运行(一次调用就结束):不发空的工具表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", profile.systemPrompt()));
        messages.addAll(seed);
        Map<String, String> cache = new HashMap<>();
        Usage usage = new Usage();

        // 1) 续跑:把已完成的步骤原样放回对话,工具结果进缓存,不重新调用
        List<AgentStep> history = new ArrayList<>(checkpoint.load(run.subtaskUuid()));
        int resumedFrom = history.size();
        for (AgentStep s : history) {
            messages.add(s.assistant());
            messages.addAll(s.toolResults());
            for (int i = 0; i < s.toolResults().size(); i++) {
                String out = s.toolResults().get(i).getContent();
                if (!out.startsWith(ERROR)) {
                    cache.put(cacheKey(s.assistant().getToolCalls().get(i)), out);
                }
            }
            usage.add(s.usage());
        }
        if (resumedFrom > 0) {
            log.info("agent 从存档续跑 subtaskUuid={} fromStep={}", run.subtaskUuid(), resumedFrom);
            emit(run, TraceStage.RESUMED, OM.createObjectNode()
                    .put("fromStep", resumedFrom).put("workerId", WORKER_ID));
        }

        // 2) ReAct 主循环
        for (int step = resumedFrom + 1; step <= MAX_STEPS; step++) {
            ChatResponse resp = gateway.chat(request(run.model(), messages, offered, profile.maxTokens()));
            Usage stepUsage = Usage.of(resp, props);
            usage.add(stepUsage);
            if (resp.getToolCalls().isEmpty()) {
                return new Outcome(resp.getContent(), step, usage, history);
            }
            ChatMessage assistant = ChatMessage.assistant(resp.getContent(), resp.getToolCalls());
            List<ChatMessage> results = new ArrayList<>();
            ArrayNode calls = OM.createArrayNode();
            for (ToolCall c : resp.getToolCalls()) {
                String out = execute(c, cache, available, profile);
                results.add(ChatMessage.tool(c.getId(), out));
                calls.addObject().put("tool", c.getName()).put("args", c.getArgumentsJson())
                        .put("resultPreview", TextUtil.truncate(out, PREVIEW_CHARS));
            }
            messages.add(assistant);
            messages.addAll(results);
            // 一步完整结束才存档:崩溃在此之前 → 该步重做(工具只读,重做无副作用)
            AgentStep done = new AgentStep(step, assistant, results, stepUsage);
            checkpoint.append(run.subtaskUuid(), done);
            history.add(done);
            ObjectNode detail = OM.createObjectNode().put("step", step)
                    .put("thought", resp.getContent()).put("workerId", WORKER_ID);
            detail.set("calls", calls);
            emit(run, TraceStage.AGENT_STEP, detail);
        }

        // 3) 步数耗尽:去掉工具,强制作答(防死循环)
        messages.add(new ChatMessage("user", Prompts.FORCE_ANSWER));
        ChatResponse resp = gateway.chat(request(run.model(), messages, null, profile.maxTokens()));
        usage.add(Usage.of(resp, props));
        return new Outcome(resp.getContent(), MAX_STEPS + 1, usage, history);
    }

    private String execute(ToolCall c, Map<String, String> cache, Map<String, AgentTool> available, Profile profile) {
        String key = cacheKey(c);
        String hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        AgentTool tool = available.get(c.getName());
        if (tool == null) {
            return ERROR + "未知工具 " + c.getName() + ",可用工具:" + available.keySet();
        }
        String out;
        try {
            String raw = c.getArgumentsJson();
            JsonNode args = OM.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            out = TextUtil.truncate(tool.run(args), profile.maxToolChars());
        } catch (Exception e) {
            // 参数不是 JSON、参数写错(IllegalArgumentException)是模型的错,给它看;其余是工具背后的服务出了故障
            boolean modelsMistake = e instanceof IllegalArgumentException || e instanceof JsonProcessingException;
            if (profile.failOnToolError() && !modelsMistake) {
                throw e instanceof RuntimeException r ? r : new IllegalStateException(c.getName() + " 调用失败:" + e, e);
            }
            return ERROR + c.getName() + " 调用失败:" + e.getMessage();   // 不缓存:瞬时错误允许模型重试
        }
        cache.put(key, out);
        return out;
    }

    /** 拷贝 messages:后续追加不影响已发出的请求。 */
    private static ChatRequest request(String model, List<ChatMessage> messages, List<ToolSpec> tools, int maxTokens) {
        ChatRequest r = new ChatRequest(model, new ArrayList<>(messages), maxTokens);
        r.setTools(tools);
        return r;
    }

    private static String cacheKey(ToolCall c) {
        return c.getName() + " " + c.getArgumentsJson();
    }

    private void emit(AgentRun run, TraceStage stage, ObjectNode detail) {
        trace.emit(String.valueOf(run.taskId()), run.taskId(), run.subtaskId(), stage, null, detail.toString());
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
