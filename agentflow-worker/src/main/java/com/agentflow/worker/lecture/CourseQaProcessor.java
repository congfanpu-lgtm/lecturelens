package com.agentflow.worker.lecture;

import com.agentflow.common.llm.ChatMessage;
import com.agentflow.common.llm.ToolCall;
import com.agentflow.common.mq.SubtaskMessage;
import com.agentflow.worker.agent.AgentLoop;
import com.agentflow.worker.agent.AgentLoop.Outcome;
import com.agentflow.worker.agent.AgentLoop.Profile;
import com.agentflow.worker.agent.AgentRun;
import com.agentflow.worker.agent.AgentStep;
import com.agentflow.worker.agent.Prompts;
import com.agentflow.worker.agent.TextUtil;
import com.agentflow.worker.processor.SubtaskProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COURSE_QA 的第一步:在一门课里找答案。ReAct agent,自己决定看哪几讲、搜什么、读哪一段;
 * 每步存档(按 subtaskUuid),worker 死了由别的 worker 从断点续跑,已经调用过的工具不重跑。
 *
 * <p>这里只把模型的回答整理成"结论 + 证据(讲次、时间段、原话)"。结论对不对不在这里判断:
 * 下一步 QA_VERIFY 由代码逐条核验,agent 绕不过去。所以这里对模型的输出很宽松——
 * 没带证据的结论、不是 JSON 的回答都原样交下去,由核验拿掉。
 */
@Component
@RequiredArgsConstructor
public class CourseQaProcessor implements SubtaskProcessor {

    static final int MAX_TOKENS = 4000;        // 跨讲次的回答带多条引文,研究 agent 的 1600 会把 JSON 截断
    static final int MAX_TOOL_CHARS = 6000;    // 一次检索 5 个片段、每个最多 900 字
    static final int MAX_CLAIMS = 12;
    static final int MAX_EVIDENCE = 4;
    private static final int MAX_HISTORY_ANSWER = 500;
    private static final int MAX_FREE_TEXT = 2000;
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Pattern CLOCK = Pattern.compile("\\d+:\\d{1,2}(?::\\d{1,2})?");   // 12:30、1:02:30

    private final AgentLoop loop;
    private final CourseTools tools;

    @Override
    public String type() {
        return "QA_AGENT";
    }

    @Override
    public String process(String inputJson) {
        throw new UnsupportedOperationException("QA_AGENT 需要子任务身份(按 subtaskUuid 存档续跑),请走 process(SubtaskMessage)");
    }

    @Override
    public String process(SubtaskMessage msg) throws Exception {
        JsonNode in = OM.readTree(msg.getInputJson());
        String question = in.path("question").asText("").trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("input.question 缺失");
        }
        String model = in.hasNonNull("model") ? in.get("model").asText() : null;
        JsonNode lectures = in.path("lectures");

        List<ChatMessage> seed = new ArrayList<>();
        for (JsonNode turn : in.path("history")) {   // 追问:最近几轮对话放在问题前面
            seed.add(new ChatMessage("user", turn.path("question").asText("")));
            seed.add(new ChatMessage("assistant", TextUtil.truncate(turn.path("answer").asText(""), MAX_HISTORY_ANSWER)));
        }
        seed.add(new ChatMessage("user", question));
        if (in.path("feedback").isArray() && !in.get("feedback").isEmpty()) {   // 补一轮:告诉模型上次哪些结论被拿掉、为什么
            StringBuilder sb = new StringBuilder(Prompts.COURSE_QA_FEEDBACK);
            for (JsonNode f : in.get("feedback")) {
                sb.append("\n- ").append(f.path("text").asText()).append("(原因:").append(f.path("reason").asText()).append(")");
            }
            if (in.path("kept").isArray() && !in.get("kept").isEmpty()) {
                sb.append("\n下面这些结论已经通过核验,会保留在答案里,不要重复:");
                for (JsonNode k : in.get("kept")) {
                    sb.append("\n- ").append(k.path("text").asText());
                }
            }
            // 这一轮只补被拿掉的那部分;通过核验的结论会和这一轮通过的合在一起成为最后的答案
            sb.append("\n请只针对被拿掉的那些重新查找:能在工具结果里找到原文支持的,写成新的结论(带证据);"
                    + "找不到原文支持的就不要写。一条都补不上就输出 {\"notFound\":false,\"claims\":[]}。");
            seed.add(new ChatMessage("user", sb.toString()));
        }

        Profile profile = new Profile(Prompts.system(Prompts.COURSE_QA, question),
                tools.forCourse(in.path("courseId").asLong(), lectures), MAX_TOKENS, MAX_TOOL_CHARS, true);
        Outcome outcome = loop.converse(
                new AgentRun(msg.getTaskId(), msg.getSubtaskId(), msg.getSubtaskUuid(), question, model), profile, seed);
        String content = outcome.content() == null ? "" : outcome.content().trim();
        if (content.isEmpty()) {
            throw new IllegalStateException("agent 最终答案为空");
        }

        ObjectNode out = OM.createObjectNode().put("question", question);
        ArrayNode claims = OM.createArrayNode();
        boolean notFound = false;
        try {
            JsonNode parsed = TextUtil.parseObject(content);
            JsonNode list = parsed.path("claims");
            // 明确给了一个空的 claims:就是"没有可说的",和 notFound 一个意思
            notFound = parsed.path("notFound").asBoolean(false) || (list.isArray() && list.isEmpty());
            if (!notFound) {   // 说了"课里没讲"就不能再夹带结论
                Map<Integer, JsonNode> bySeq = new HashMap<>();
                lectures.forEach(l -> bySeq.put(l.path("seq").asInt(), l));
                for (JsonNode c : list) {
                    String text = (c.isTextual() ? c.asText() : c.path("text").asText("")).trim();
                    if (!text.isEmpty() && claims.size() < MAX_CLAIMS) {
                        ObjectNode claim = claims.addObject().put("text", text);
                        ArrayNode evidence = claim.putArray("evidence");
                        JsonNode given = c.path("evidence");
                        for (JsonNode e : given.isObject() ? List.of(given) : given) {   // 只有一条证据时模型常不写成数组
                            if (evidence.size() < MAX_EVIDENCE) {
                                addEvidence(evidence, e, bySeq);
                            }
                        }
                    }
                }
                if (claims.isEmpty()) {
                    // 是 JSON,但里面没有一条能用的结论(比如写成了 {"answer": …}):不能当成"课里没讲",按没按格式作答处理
                    throw new IllegalStateException("回答里没有 claims");
                }
            }
        } catch (IllegalStateException e) {
            // 模型没按格式作答:整段当作一条没有证据的结论交下去,核验会把它拿掉并说明原因
            claims.removeAll();
            claims.addObject().put("text", TextUtil.truncate(content, MAX_FREE_TEXT)).putArray("evidence");
        }
        out.put("notFound", notFound);
        out.set("claims", claims);
        out.put("steps", outcome.stepCount());
        ArrayNode calls = out.putArray("toolCalls");
        for (AgentStep step : outcome.history()) {
            for (ToolCall c : step.assistant().getToolCalls()) {
                calls.addObject().put("tool", c.getName()).put("args", c.getArgumentsJson());
            }
        }
        out.set("usage", OM.valueToTree(outcome.usage()));
        return out.toString();
    }

    /**
     * 模型写的证据(讲次序号 + "分:秒-分:秒")翻译成内部格式。写法上的小出入都认:序号写成 "3"、"L3"、3.0,
     * 时间段用了长横线、带着方括号、把整个 [L3 12:30-13:25 speech] 抄了过来。
     * 讲次不认识或时间看不懂的不悄悄丢掉,带着原因(invalid)交给核验:否则核验只能说"没有给出证据",
     * 补一轮时模型拿到的是错的原因。
     */
    private static void addEvidence(ArrayNode evidence, JsonNode e, Map<Integer, JsonNode> bySeq) {
        JsonNode lecture = bySeq.get(CourseTools.seqOf(e.path("lecture")));
        Matcher at = CLOCK.matcher(e.path("at").asText(""));
        if (lecture == null) {
            evidence.addObject().put("invalid", "证据指的讲次不在这门课里:" + shown(e.path("lecture")));
            return;
        }
        long startMs;
        long endMs;
        try {
            if (!at.find()) {
                throw new IllegalArgumentException("没有时间");
            }
            startMs = CourseTools.parseClock(at.group());
            endMs = at.find() ? CourseTools.parseClock(at.group()) : startMs;   // 只写了一个时间点:当作那一刻
        } catch (IllegalArgumentException bad) {
            evidence.addObject().put("invalid", "证据的时间看不懂:" + shown(e.path("at")));
            return;
        }
        evidence.addObject()
                .put("lectureId", lecture.path("lectureId").asLong())
                .put("seq", lecture.path("seq").asInt())
                .put("startMs", startMs)
                .put("endMs", endMs)
                .put("source", e.path("source").asText(""))
                .put("quote", e.path("quote").asText(""));
    }

    private static String shown(JsonNode n) {
        return TextUtil.truncate(n.isMissingNode() ? "(没写)" : n.asText(n.toString()), 40);
    }
}
