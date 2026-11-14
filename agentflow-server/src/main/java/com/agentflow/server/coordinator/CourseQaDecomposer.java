package com.agentflow.server.coordinator;

import com.agentflow.common.state.SubtaskStatus;
import com.agentflow.common.state.TaskStatus;
import com.agentflow.server.entity.LectureEntity;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.LectureMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * COURSE_QA:在一门课的范围里回答一个问题。两个阶段,各是一个普通的 Kafka 子任务:
 * QA_AGENT(ReAct agent 找答案,每步存档可续跑)→ QA_VERIFY(逐条核验结论的证据)。
 *
 * <p>核验是单独的一步,由代码执行:agent 给出什么都要过这一关,绕不过去。
 * 被拿掉的结论太多(不少于 1 条且超过三分之一)时,带着原因让 agent 再答一轮、再核验一次;只补一次,
 * 第二轮还通不过就如实给出"没有能通过核验的结论"。
 *
 * <p>payload:{@code {"courseId":3,"question":"…","history":[{"question","answer"}],"model":"…"}}。
 * 只用这门课里已经处理好的讲次(READY / PARTIAL)。
 */
@Component
@RequiredArgsConstructor
public class CourseQaDecomposer implements TaskDecomposer {

    public static final String TYPE = "COURSE_QA";
    public static final String AGENT = "QA_AGENT";
    public static final String VERIFY = "QA_VERIFY";
    static final int MAX_QUESTION_CHARS = 500;
    static final int MAX_HISTORY = 3;
    static final int MAX_HISTORY_ANSWER_CHARS = 2000;

    private final ObjectMapper objectMapper;
    private final CourseMapper courseMapper;
    private final LectureMapper lectureMapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<SubtaskDef> decompose(JsonNode payload) {
        String question = payload == null ? "" : payload.path("question").asText("").trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("payload.question 不能为空");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("payload.question 不能超过 " + MAX_QUESTION_CHARS + " 字");
        }
        long courseId = payload.path("courseId").asLong(0);
        if (courseId <= 0 || courseMapper.selectById(courseId) == null) {
            throw new IllegalArgumentException("课程不存在: courseId=" + courseId);
        }
        ObjectNode in = objectMapper.createObjectNode().put("courseId", courseId).put("question", question);
        ArrayNode lectures = in.putArray("lectures");
        for (LectureEntity l : lectureMapper.selectList(
                new QueryWrapper<LectureEntity>().eq("course_id", courseId).orderByAsc("seq"))) {
            // 处理中和失败的讲次没有(完整的)索引,不能拿来回答问题
            if (("READY".equals(l.getStatus()) || "PARTIAL".equals(l.getStatus())) && l.getMediaKey() != null) {
                ObjectNode item = lectures.addObject()
                        .put("lectureId", l.getId())
                        .put("seq", l.getSeq())
                        .put("title", l.getTitle() == null ? "" : l.getTitle())
                        .put("durationMs", l.getDurationMs() == null ? 0 : l.getDurationMs())
                        .put("mediaKey", l.getMediaKey());
                item.set("concepts", json(l.getConcepts() == null ? "[]" : l.getConcepts()));
            }
        }
        if (lectures.isEmpty()) {
            throw new IllegalArgumentException("这门课还没有处理好的讲次");
        }
        // 历史对话是调用方自己送来的(页面,或者通用的任务提交接口),每一轮都要限长:不然一段几十万字的"历史"
        // 会原样进到每一次模型调用里。形状不对、缺问题或缺回答的那几轮不带。
        List<ObjectNode> turns = new ArrayList<>();
        if (payload.path("history").isArray()) {
            for (JsonNode turn : payload.get("history")) {
                String q = turn.path("question").asText("").trim();
                String a = turn.path("answer").asText("").trim();
                if (!q.isEmpty() && !a.isEmpty()) {
                    turns.add(objectMapper.createObjectNode().put("question", cut(q, MAX_QUESTION_CHARS))
                            .put("answer", cut(a, MAX_HISTORY_ANSWER_CHARS)));
                }
            }
        }
        in.putArray("history").addAll(turns.subList(Math.max(0, turns.size() - MAX_HISTORY), turns.size()));   // 只带最近几轮
        if (payload.hasNonNull("model")) {
            in.put("model", payload.get("model").asText());
        }
        return List.of(new SubtaskDef(0, in.toString(), AGENT));
    }

    @Override
    public List<SubtaskDef> nextStage(TaskEntity task, List<SubtaskEntity> subs) {
        SubtaskEntity last = subs.get(subs.size() - 1);
        if (!completed(last)) {
            return List.of();
        }
        JsonNode agentInput = json(subs.get(0).getInput());
        JsonNode out = json(last.getOutput());
        if (AGENT.equals(last.getKind())) {
            if (out.path("notFound").asBoolean(false) || out.path("claims").isEmpty()) {
                return List.of();   // 课里没讲,或者一条结论都没有:没有东西可核验
            }
            ObjectNode in = objectMapper.createObjectNode()
                    .put("courseId", agentInput.path("courseId").asLong())
                    .put("question", agentInput.path("question").asText());
            ArrayNode lectures = in.putArray("lectures");
            for (JsonNode l : agentInput.path("lectures")) {   // 核验用不到知识点目录
                lectures.addObject().put("lectureId", l.path("lectureId").asLong()).put("seq", l.path("seq").asInt())
                        .put("durationMs", l.path("durationMs").asLong()).put("mediaKey", l.path("mediaKey").asText());
            }
            in.set("claims", out.path("claims"));
            if (agentInput.hasNonNull("model")) {
                in.put("model", agentInput.get("model").asText());
            }
            return List.of(new SubtaskDef(last.getSeq() + 1, in.toString(), VERIFY));
        }
        // 刚核验完:第一轮里被拿掉的结论太多,就带着原因让 agent 再答一轮;只补一次
        long rounds = subs.stream().filter(s -> AGENT.equals(s.getKind())).count();
        int dropped = out.path("dropped").size();
        int total = dropped + out.path("claims").size();
        if (rounds > 1 || dropped < 1 || dropped * 3 <= total) {
            return List.of();
        }
        ObjectNode in = agentInput.deepCopy();
        ArrayNode feedback = in.putArray("feedback");
        for (JsonNode d : out.path("dropped")) {
            feedback.addObject().put("text", d.path("text").asText()).put("reason", d.path("reason").asText());
        }
        // 通过核验的那些也告诉它:这一轮只补被拿掉的部分,最后的答案是两轮通过核验的结论合在一起(QuestionService)
        ArrayNode kept = in.putArray("kept");
        for (JsonNode c : out.path("claims")) {
            kept.addObject().put("text", c.path("text").asText());
        }
        return List.of(new SubtaskDef(last.getSeq() + 1, in.toString(), AGENT));
    }

    /**
     * 最后一步成功才算回答完了。第一轮的核验没跑成 → FAILED:没核验过的结论不能当答案。
     * 第一轮核验过了、补的那一轮(agent 或核验)没跑成 → PARTIAL_FAILED:第一轮通过核验的结论照样是答案。
     */
    @Override
    public TaskStatus terminalStatus(TaskEntity task, List<SubtaskEntity> subs, TaskStatus computed) {
        if (completed(subs.get(subs.size() - 1))) {
            return TaskStatus.COMPLETED;
        }
        boolean verifiedOnce = subs.stream().anyMatch(s -> VERIFY.equals(s.getKind()) && completed(s));
        return verifiedOnce ? TaskStatus.PARTIAL_FAILED : TaskStatus.FAILED;
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean completed(SubtaskEntity s) {
        return SubtaskStatus.COMPLETED.name().equals(s.getStatus());
    }

    private JsonNode json(String s) {
        try {
            return objectMapper.readTree(s == null ? "{}" : s);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败:" + s, e);
        }
    }
}
