package com.agentflow.server.coordinator;

import com.agentflow.server.mapper.CourseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * COURSE_VERIFY:只核验给定的"结论 + 证据",不经过 agent。一个 QA_VERIFY 子任务,和课程问答用的是同一个核验步骤。
 * 给评测里校准判官用(eval/course/calibrate.py):比如把已经通过核验的结论换上随机的原文当证据,判官应该全部拒绝。
 *
 * <p>payload:{@code {"courseId":3,"question":"…","claims":[{"text":"…","evidence":[{"lecture":3,"startMs":…,
 * "endMs":…,"source":"speech","quote":"…"}]}],"model":"…","eval":{"judge":false}}}。lecture 是讲次序号。
 * 讲次的 mediaKey 等只从库里取:通用的任务提交接口对外开放,调用方不能自带。
 */
@Component
@RequiredArgsConstructor
public class CourseVerifyDecomposer implements TaskDecomposer {

    public static final String TYPE = "COURSE_VERIFY";
    private static final int MAX_CLAIMS = 12;   // 和课程问答一次最多的结论数一样:判官一次输出的上限装不下更多
    private static final int MAX_EVIDENCE = 4;
    private static final int MAX_TEXT = 2000;

    private final ObjectMapper objectMapper;
    private final CourseMapper courseMapper;
    private final CourseQaDecomposer courseQa;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<SubtaskDef> decompose(JsonNode payload) {
        long courseId = payload == null ? 0 : payload.path("courseId").asLong(0);
        if (courseId <= 0 || courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("课程不存在: courseId=" + courseId);
        }
        String question = payload.path("question").asText("").trim();
        if (question.isEmpty() || question.length() > CourseQaDecomposer.MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("payload.question 不能为空,也不能超过 " + CourseQaDecomposer.MAX_QUESTION_CHARS + " 字");
        }
        JsonNode given = payload.path("claims");
        if (!given.isArray() || given.isEmpty() || given.size() > MAX_CLAIMS) {
            throw new IllegalArgumentException("payload.claims 要是 1 到 " + MAX_CLAIMS + " 条结论");
        }
        ObjectNode in = objectMapper.createObjectNode().put("courseId", courseId).put("question", question);
        Map<Integer, Long> idBySeq = new HashMap<>();
        ArrayNode lectures = in.putArray("lectures");
        for (JsonNode l : courseQa.lectures(courseId)) {   // 核验用不到知识点目录
            idBySeq.put(l.path("seq").asInt(), l.path("lectureId").asLong());
            lectures.addObject().put("lectureId", l.path("lectureId").asLong()).put("seq", l.path("seq").asInt())
                    .put("durationMs", l.path("durationMs").asLong()).put("mediaKey", l.path("mediaKey").asText());
        }
        ArrayNode claims = in.putArray("claims");
        for (JsonNode c : given) {
            String text = c.path("text").asText("").trim();
            if (text.isEmpty() || text.length() > MAX_TEXT || c.path("evidence").size() > MAX_EVIDENCE) {
                throw new IllegalArgumentException("每条结论要有 text(不超过 " + MAX_TEXT + " 字)和最多 " + MAX_EVIDENCE + " 条证据");
            }
            ArrayNode evidence = claims.addObject().put("text", text).putArray("evidence");
            for (JsonNode e : c.path("evidence")) {
                Long lectureId = e.path("lecture").isIntegralNumber() ? idBySeq.get(e.path("lecture").asInt()) : null;
                if (lectureId == null) {
                    throw new IllegalArgumentException("证据里的 lecture 要是这门课里已经处理好的讲次序号:" + e.path("lecture"));
                }
                String quote = e.path("quote").asText("");
                evidence.addObject().put("lectureId", lectureId).put("seq", e.path("lecture").asInt())
                        .put("startMs", e.path("startMs").asLong()).put("endMs", e.path("endMs").asLong())
                        .put("source", e.path("source").asText(""))
                        .put("quote", quote.length() > MAX_TEXT ? quote.substring(0, MAX_TEXT) : quote);
            }
        }
        JsonNode eval = payload.path("eval");
        if (!eval.isMissingNode() && !eval.isNull()) {   // 这里只认 judge 一个选项,写错的拒绝
            if (!eval.isObject() || eval.size() != 1 || !eval.path("judge").isBoolean()) {
                throw new IllegalArgumentException("payload.eval 只能是 {\"judge\": true 或 false}");
            }
            if (!eval.get("judge").asBoolean()) {
                in.put("judge", false);
            }
        }
        if (payload.hasNonNull("model")) {
            in.put("model", payload.get("model").asText());
        }
        return List.of(new SubtaskDef(0, in.toString(), CourseQaDecomposer.VERIFY));
    }
}
