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
        int evidenceProposed = 0;   // 按条数记:一条结论带一句编的和一句真的,结论会留下,但那句编的要算进"引文忠实率"
        int evidencePassed = 0;
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
                if (e.hasNonNull("invalid")) {   // 上一步没看懂这条证据的讲次或时间:原因照实写,不说成"没有给出证据"
                    v = new Verdict(false, 1, e.get("invalid").asText());
                } else if (lecture == null) {
                    v = new Verdict(false, 1, "证据指向的讲次不在这门课里");
                } else {
                    v = EvidenceChecker.check(
                            new Evidence(e.path("lectureId").asLong(), lecture.seq(), e.path("startMs").asLong(),
                                    e.path("endMs").asLong(), e.path("source").asText(null), e.path("quote").asText(null)),
                            lecture.durationMs(),
                            transcripts.computeIfAbsent(lecture.mediaKey(), k -> media.transcript(k).path("utterances")),
                            frames.computeIfAbsent(lecture.mediaKey(), this::framesOf));
                }
                evidenceProposed++;
                if (v.ok()) {
                    evidencePassed++;
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
        if (!in.path("judge").asBoolean(true)) {
            // 评测里"去掉第 3 层"的那个系统:前两层照做,通过的直接保留
            for (Candidate c : candidates) {
                verified.addObject().put("text", c.text()).set("evidence", c.evidence());
            }
        } else if (!candidates.isEmpty()) {   // 没有结论需要判就不调用判官
            // 给判官的是一个 JSON:结论、问题、原文都是外来的文字,拼成纯文本的话,里面的换行或者"结论 0:"这样的字样
            // 就能伪造出结构。每条结论有一个 id(c1、c2…),判定按 id 对应,不靠位置和编号。
            ObjectNode ask = OM.createObjectNode().put("question", in.path("question").asText(""));
            ArrayNode list = ask.putArray("claims");
            for (int i = 0; i < candidates.size(); i++) {
                ObjectNode item = list.addObject().put("id", "c" + (i + 1)).put("text", candidates.get(i).text());
                ArrayNode evidence = item.putArray("evidence");
                for (JsonNode e : candidates.get(i).evidence()) {
                    Lecture lecture = lectures.get(e.path("lectureId").asLong());
                    String context = EvidenceChecker.windowText(e.path("source").asText(),
                            e.path("startMs").asLong() - MARGIN_MS, e.path("endMs").asLong() + MARGIN_MS,
                            transcripts.get(lecture.mediaKey()), frames.get(lecture.mediaKey()));
                    String shown = around(context, e.path("quote").asText(), MAX_CONTEXT_CHARS);
                    // 判官看到的那段原文跟着证据一起存下来:校准判官时给人看的必须是同一段,不能在别处按"同样的规则"再截一遍
                    ((ObjectNode) e).put("context", shown);
                    evidence.addObject()
                            .put("where", "L" + lecture.seq() + " " + clock(e.path("startMs").asLong()) + "-" + clock(e.path("endMs").asLong()))
                            .put("context", shown)
                            .put("quote", e.path("quote").asText());
                }
            }
            String user = ask.toString();
            String model = in.hasNonNull("model") ? in.get("model").asText() : null;
            ChatResponse resp = gateway.chat(new ChatRequest(model, List.of(
                    new ChatMessage("system", Prompts.system(Prompts.JUDGE, in.path("question").asText(""))),
                    new ChatMessage("user", user)), MAX_TOKENS));
            usage = Usage.of(resp, props);
            JsonNode verdicts = TextUtil.parseObject(resp.getContent()).path("verdicts");
            if (!verdicts.isArray()) {
                throw new IllegalStateException("判官的输出里没有 verdicts 数组");
            }
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                index.put("c" + (i + 1), i);
            }
            Map<Integer, JsonNode> byClaim = new HashMap<>();
            for (JsonNode v : verdicts) {
                Integer i = v.path("id").isTextual() ? index.get(v.path("id").asText().trim()) : null;
                if (i == null) {
                    // 判定指向一条不存在的结论:判官把结论对错了号,别的判定也不可信。整份作废,这一步重来
                    throw new IllegalStateException("判官的判定指向不存在的结论:" + v.path("id"));
                }
                // 同一条结论判了两次:有一次说不支持就按不支持算
                if (!byClaim.containsKey(i) || !v.path("supported").asBoolean(false)) {
                    byClaim.put(i, v);
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                JsonNode v = byClaim.get(i);
                // 判官否掉(或漏判)的结论带着它看过的证据:评测里校准判官时,要拿"结论 + 证据"这一对给人标注
                if (v == null) {
                    drop(dropped, c.text(), 3, "判官没有给出判定").set("evidence", c.evidence());   // 漏判的不算通过
                } else if (!v.path("supported").asBoolean(false)) {
                    drop(dropped, c.text(), 3, v.path("reason").asText("判官认为原文不支持这条结论")).set("evidence", c.evidence());
                } else {
                    verified.addObject().put("text", c.text()).set("evidence", c.evidence());
                }
            }
        }

        ObjectNode out = OM.createObjectNode();
        out.set("claims", verified);
        out.set("dropped", dropped);
        out.set("evidence", OM.createObjectNode().put("proposed", evidenceProposed).put("passed", evidencePassed));
        out.set("usage", OM.valueToTree(usage));
        return out.toString();
    }

    /**
     * 给判官看的那段原文:以引文为中心截取。前两层核对的是整个时间窗的文字,一个 90 秒的片段就有一两千字;
     * 只给开头的话,引文和它前后那几句(比如紧跟着的"但这是错的")常常不在里面,幻灯片则会给成前一张的开头。
     */
    static String around(String context, String quote, int max) {
        String flat = context.replaceAll("\\s+", " ").trim();
        if (flat.length() <= max) {
            return flat;
        }
        int at = Math.max(0, TextUtil.normalize(flat).indexOf(TextUtil.normalize(quote)));
        int from = Math.max(0, Math.min(at - Math.max(0, max - quote.length()) / 2, flat.length() - max));
        return (from > 0 ? "…" : "") + flat.substring(from, from + max) + (from + max < flat.length() ? "…" : "");
    }

    /** 这一讲没有截到幻灯片(媒体服务返回 404):当作没有幻灯片,语音证据照常核验。 */
    private JsonNode framesOf(String mediaKey) {
        try {
            return media.frameList(mediaKey).path("frames");
        } catch (NonRetryableException e) {
            return OM.createArrayNode();
        }
    }

    private static ObjectNode drop(ArrayNode dropped, String text, int layer, String reason) {
        return dropped.addObject().put("text", text).put("layer", layer).put("reason", reason);
    }
}
