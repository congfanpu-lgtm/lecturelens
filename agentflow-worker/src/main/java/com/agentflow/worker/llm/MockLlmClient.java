package com.agentflow.worker.llm;

import com.agentflow.common.llm.ChatMessage;
import com.agentflow.common.llm.ChatRequest;
import com.agentflow.common.llm.ChatResponse;
import com.agentflow.common.llm.TokenUsage;
import com.agentflow.common.llm.ToolCall;
import com.agentflow.worker.agent.Prompts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认 LLM 后端:确定性输出、不发网络、无需 API key——压测测调度不烧钱(master 设计文档第 149 行)。
 * 网关的限流/路由/记账对 mock 与真实模型完全一致,mock 只是把"发 HTTP"换成"本地确定性返回"。
 *
 * <p>按系统提示的角色标签({@link Prompts})分流:知识点目录、课程问答、判官各有一个剧本;
 * 其余请求返回"摘要"(LLM_BATCH 用)。
 * {@code llm.mock.delay-ms} 模拟模型耗时,便于在页面上看清每一步、演示中途杀 worker。
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Set<String> STOP = Set.of("what", "which", "does", "with", "from", "that", "this",
            "between", "about", "versus", "when", "where", "there", "their", "have");

    private static final Pattern HIT_BLOCK = Pattern.compile("(?m)^\\[L(\\d+) (\\S+) (speech|slide)]\\n(.+)$");
    private static final Pattern SEGMENT_LINE = Pattern.compile("(?m)^\\[(\\d+)] \\S+ (.*)$");

    @Value("${llm.mock.delay-ms:0}")
    private long delayMs;

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        sleep();
        List<ChatMessage> msgs = request.getMessages() == null ? List.of() : request.getMessages();
        String system = msgs.stream().filter(m -> "system".equals(m.getRole()) && m.getContent() != null)
                .map(ChatMessage::getContent).findFirst().orElse("");
        ChatResponse resp;
        if (system.startsWith(Prompts.CONCEPTS_TAG)) {
            resp = concepts(lastUser(msgs));
        } else if (system.startsWith(Prompts.COURSE_QA_TAG)) {
            resp = courseQa(request, msgs);
        } else if (system.startsWith(Prompts.JUDGE_TAG)) {
            resp = judge(lastUser(msgs));
        } else {
            resp = summarize(lastUser(msgs));
        }
        // token 估算:chars/4(见 TokenEstimator 同款启发式);mock 无真实 usage 故本地估。
        int promptChars = msgs.stream().mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length()).sum();
        int completionChars = resp.getContent() == null ? 0 : resp.getContent().length();
        resp.setUsage(new TokenUsage(Math.max(1, promptChars / 4), Math.max(1, completionChars / 4)));
        resp.setModel(request.getModel());
        return resp;
    }

    // 模拟"守规矩的模型":确定性输出符合 summarize schema 的 JSON——processor 会真的解析+校验它。
    private ChatResponse summarize(String lastUser) {
        String trimmed = lastUser.length() > 120 ? lastUser.substring(0, 120) : lastUser;
        return new ChatResponse(OM.createObjectNode().put("summary", "SUMMARY: " + trimmed.trim()).toString(),
                null, null);
    }

    /** 判官:每条结论都给判定;结论里带 UNSUPPORTED 的判为不支持(演练核验的第 3 层),其余支持。 */
    private ChatResponse judge(String user) {
        ObjectNode out = OM.createObjectNode();
        ArrayNode verdicts = out.putArray("verdicts");
        JsonNode claims;
        try {
            claims = OM.readTree(user).path("claims");
        } catch (Exception e) {
            claims = OM.createArrayNode();
        }
        for (JsonNode c : claims) {
            boolean supported = !c.path("text").asText("").contains("UNSUPPORTED");
            verdicts.addObject().put("id", c.path("id").asText()).put("supported", supported)
                    .put("reason", supported ? "MOCK 判官:原文支持这条结论" : "MOCK 判官:原文没有说到这一点");
        }
        return new ChatResponse(out.toString(), null, null);
    }

    /**
     * 课程问答:先检索一次;有命中就用第一条命中里的原话作答(引文真的在工具结果里,能通过文字核对);没有命中就说找不到。
     * 问题里带 FABRICATE:引文换成一句没人说过的话(演练核验的第 2 层);带 UNSUPPORTED:结论里带上这个词(演练判官)。
     */
    private ChatResponse courseQa(ChatRequest req, List<ChatMessage> msgs) {
        String question = "";
        for (ChatMessage m : msgs) {
            if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().startsWith(Prompts.COURSE_QA_FEEDBACK)) {
                question = m.getContent();
            }
        }
        List<String> toolOutputs = msgs.stream().filter(m -> "tool".equals(m.getRole()))
                .map(ChatMessage::getContent).toList();
        boolean toolsOffered = req.getTools() != null && !req.getTools().isEmpty();
        if (toolsOffered && toolOutputs.isEmpty()) {
            return withCall("MOCK 思路:先在这门课里检索。",
                    new ToolCall("call_1", "search_segments", OM.createObjectNode().put("query", keywords(question)).toString()));
        }
        ObjectNode out = OM.createObjectNode();
        // 没有工具的两种跑法(固定检索流水线、长上下文):课程内容在用户消息里
        String material = !toolOutputs.isEmpty() ? toolOutputs.get(0) : toolsOffered ? ""
                : msgs.stream().filter(m -> "user".equals(m.getRole()) && m.getContent() != null)
                        .map(ChatMessage::getContent).collect(java.util.stream.Collectors.joining("\n\n"));
        Matcher hit = HIT_BLOCK.matcher(material);
        if (!hit.find()) {
            out.put("notFound", true).putArray("claims");
            return new ChatResponse(out.toString(), null, null);
        }
        String text = hit.group(4).trim();
        String quote = question.contains("FABRICATE") ? "this sentence was never said anywhere in the lecture"
                : text.substring(0, Math.min(80, text.length())).trim();
        ObjectNode claim = out.put("notFound", false).putArray("claims").addObject();
        claim.put("text", "MOCK 结论:第 " + hit.group(1) + " 讲在 " + hit.group(2) + " 提到:"
                + text.substring(0, Math.min(40, text.length())) + (question.contains("UNSUPPORTED") ? " UNSUPPORTED" : ""));
        claim.putArray("evidence").addObject().put("lecture", Integer.parseInt(hit.group(1))).put("at", hit.group(2))
                .put("source", hit.group(3)).put("quote", quote);
        return new ChatResponse(out.toString(), null, null);
    }

    /** 课程知识点目录:每隔 5 个片段出一个知识点,编号都是输入里真实存在的片段。 */
    private ChatResponse concepts(String user) {
        ObjectNode out = OM.createObjectNode();
        ArrayNode arr = out.putArray("concepts");
        Matcher line = SEGMENT_LINE.matcher(user);
        while (line.find() && arr.size() < 30) {
            int segment = Integer.parseInt(line.group(1));
            if (segment % 5 == 0) {
                String text = line.group(2);
                String name = "知识点 " + (arr.size() + 1);   // 先算名字:addObject 之后 size 已经加一了
                arr.addObject().put("name", name).put("segment", segment)
                        .put("summary", text.length() > 60 ? text.substring(0, 60) : text)
                        .put("quote", text.length() > 40 ? text.substring(0, 40).trim() : text);
            }
        }
        return new ChatResponse(out.toString(), null, null);
    }

    /**
     * 剧本里的"模型"会提炼搜索关键词:整句问题直接丢给 Wikipedia 全文搜索几乎搜不到结果。
     * 优先取句中首字母大写的专有名词(Kafka、RabbitMQ),否则取前 3 个非停用词。
     */
    static String keywords(String question) {
        String q = question.replaceAll("\\s+—.*$", "");
        String[] words = q.split("[^\\p{L}\\p{N}-]+");
        List<String> caps = Arrays.stream(words).skip(1)
                .filter(w -> !w.isEmpty() && Character.isUpperCase(w.charAt(0))).toList();
        if (!caps.isEmpty()) {
            return String.join(" ", caps);
        }
        List<String> plain = Arrays.stream(words)
                .filter(w -> w.length() > 3 && !STOP.contains(w.toLowerCase(Locale.ROOT))).limit(3).toList();
        return plain.isEmpty() ? q : String.join(" ", plain);
    }

    private static ChatResponse withCall(String thought, ToolCall call) {
        ChatResponse r = new ChatResponse(thought, null, null);
        r.setToolCalls(List.of(call));
        return r;
    }

    private static String lastUser(List<ChatMessage> msgs) {
        String last = "";
        for (ChatMessage m : msgs) {
            if ("user".equals(m.getRole()) && m.getContent() != null) {
                last = m.getContent();
            }
        }
        return last;
    }

    private void sleep() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
