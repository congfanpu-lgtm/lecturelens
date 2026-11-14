package com.agentflow.worker.lecture;

import com.agentflow.common.llm.ChatMessage;
import com.agentflow.common.llm.ChatRequest;
import com.agentflow.common.llm.ChatResponse;
import com.agentflow.worker.agent.Prompts;
import com.agentflow.worker.agent.TextUtil;
import com.agentflow.worker.agent.Usage;
import com.agentflow.worker.lecture.EvidenceChecker.Evidence;
import com.agentflow.worker.lecture.EvidenceChecker.Verdict;
import com.agentflow.worker.llm.LlmGateway;
import com.agentflow.worker.llm.LlmModelProperties;
import com.agentflow.worker.media.MediaClient;
import com.agentflow.worker.processor.SubtaskProcessor;
import com.agentflow.worker.retry.NonRetryableException;
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

import static com.agentflow.worker.lecture.EvidenceChecker.MARGIN_MS;
import static com.agentflow.worker.lecture.EvidenceChecker.clock;

/**
 * COURSE_QA 的第二步:逐条核验 agent 给出的结论。这是单独的一个子任务、由代码执行,agent 绕不过去。
 *
 * <ol>
 *   <li>时间合法:证据指的那段时间在这一讲里真实存在,而且确实有这种来源的内容。</li>
 *   <li>引文忠实:引用的原话真的出现在那段时间的逐字稿或幻灯片文字里。</li>
 *   <li>结论被支持:通过前两层的结论连同各自的原文,一次交给判官模型,判断原文是否支持结论。</li>
 * </ol>
 *
 * 前两层是纯文字核对({@link EvidenceChecker}),能拦住编造的引文和随手写的时间;
 * 第三层拦"原话是真的、但并不支持这条结论"。判官只看通过了文字核对的原文,而且漏判的结论不算通过。
 */
@Component
@RequiredArgsConstructor
public class CourseVerifyProcessor implements SubtaskProcessor {

    static final int MAX_TOKENS = 1500;
    static final int MAX_CONTEXT_CHARS = 600;
    private static final ObjectMapper OM = new ObjectMapper();

    private final MediaClient media;
    private final LlmGateway gateway;
    private final LlmModelProperties props;

    private record Lecture(int seq, long durationMs, String mediaKey) {
    }

    /** 通过了前两层、等判官判的一条结论。 */
    private record Candidate(String text, ArrayNode evidence) {
    }

    @Override
    public String type() {
        return "QA_VERIFY";
    }

    @Override
    public String process(String inputJson) throws Exception {
        JsonNode in = OM.readTree(inputJson);
        Map<Long, Lecture> lectures = new HashMap<>();
        for (JsonNode l : in.path("lectures")) {
            lectures.put(l.path("lectureId").asLong(),
                    new Lecture(l.path("seq").asInt(), l.path("durationMs").asLong(), l.path("mediaKey").asText()));
        }
        Map<String, JsonNode> transcripts = new HashMap<>();   // 一次核验里同一讲只取一次
        Map<String, JsonNode> frames = new HashMap<>();

        ArrayNode dropped = OM.createArrayNode();
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode claim : in.path("claims")) {
            String text = claim.path("text").asText("");
            if (claim.path("evidence").isEmpty()) {
                drop(dropped, text, 0, "没有给出证据");
                continue;
            }
            ArrayNode passed = OM.createArrayNode();
            Verdict firstFailure = null;
            for (JsonNode e : claim.path("evidence")) {
                Lecture lecture = lectures.get(e.path("lectureId").asLong());
                Verdict v;
                if (lecture == null) {
                    v = new Verdict(false, 1, "证据指向的讲次不在这门课里");
                } else {
                    v = EvidenceChecker.check(
                            new Evidence(e.path("lectureId").asLong(), lecture.seq(), e.path("startMs").asLong(),
                                    e.path("endMs").asLong(), e.path("source").asText(null), e.path("quote").asText(null)),
                            lecture.durationMs(),
                            transcripts.computeIfAbsent(lecture.mediaKey(), k -> media.transcript(k).path("utterances")),
                            frames.computeIfAbsent(lecture.mediaKey(), this::framesOf));
                }
                if (v.ok()) {
                    passed.add(e);
                } else if (firstFailure == null) {
                    firstFailure = v;
                }
            }
            if (passed.isEmpty()) {
                drop(dropped, text, firstFailure.layer(), firstFailure.reason());
            } else {
                candidates.add(new Candidate(text, passed));
            }
        }

        ArrayNode verified = OM.createArrayNode();
        Usage usage = new Usage();
        if (!candidates.isEmpty()) {   // 没有结论需要判就不调用判官
            StringBuilder user = new StringBuilder("问题:").append(in.path("question").asText(""));
            for (int i = 0; i < candidates.size(); i++) {
                user.append("\n\n结论 ").append(i).append(':').append(candidates.get(i).text().replace('\n', ' '));
                for (JsonNode e : candidates.get(i).evidence()) {
                    Lecture lecture = lectures.get(e.path("lectureId").asLong());
                    String context = EvidenceChecker.windowText(e.path("source").asText(),
                            e.path("startMs").asLong() - MARGIN_MS, e.path("endMs").asLong() + MARGIN_MS,
                            transcripts.get(lecture.mediaKey()), frames.get(lecture.mediaKey()));
                    user.append("\n  原文(L").append(lecture.seq()).append(' ').append(clock(e.path("startMs").asLong()))
                            .append('-').append(clock(e.path("endMs").asLong())).append("):")
                            .append(TextUtil.truncate(context.replace('\n', ' '), MAX_CONTEXT_CHARS))
                            .append("\n  其中引用的一句:").append(e.path("quote").asText().replace('\n', ' '));
                }
            }
            String model = in.hasNonNull("model") ? in.get("model").asText() : null;
            ChatResponse resp = gateway.chat(new ChatRequest(model, List.of(
                    new ChatMessage("system", Prompts.system(Prompts.JUDGE, in.path("question").asText(""))),
                    new ChatMessage("user", user.toString())), MAX_TOKENS));
            usage = Usage.of(resp, props);
            JsonNode verdicts = TextUtil.parseObject(resp.getContent()).path("verdicts");
            if (!verdicts.isArray()) {
                throw new IllegalStateException("判官的输出里没有 verdicts 数组");
            }
            Map<Integer, JsonNode> byClaim = new HashMap<>();
            for (JsonNode v : verdicts) {
                if (v.path("claim").isIntegralNumber()) {
                    byClaim.putIfAbsent(v.path("claim").asInt(), v);
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                JsonNode v = byClaim.get(i);
                if (v == null) {
                    drop(dropped, c.text(), 3, "判官没有给出判定");   // 漏判的不算通过
                } else if (!v.path("supported").asBoolean(false)) {
                    drop(dropped, c.text(), 3, v.path("reason").asText("判官认为原文不支持这条结论"));
                } else {
                    verified.addObject().put("text", c.text()).set("evidence", c.evidence());
                }
            }
        }

        ObjectNode out = OM.createObjectNode();
        out.set("claims", verified);
        out.set("dropped", dropped);
        out.set("usage", OM.valueToTree(usage));
        return out.toString();
    }

    /** 这一讲没有截到幻灯片(媒体服务返回 404):当作没有幻灯片,语音证据照常核验。 */
    private JsonNode framesOf(String mediaKey) {
        try {
            return media.frameList(mediaKey).path("frames");
        } catch (NonRetryableException e) {
            return OM.createArrayNode();
        }
    }

    private static void drop(ArrayNode dropped, String text, int layer, String reason) {
        dropped.addObject().put("text", text).put("layer", layer).put("reason", reason);
    }
}
