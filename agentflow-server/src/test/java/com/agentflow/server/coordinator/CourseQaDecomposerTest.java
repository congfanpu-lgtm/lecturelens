package com.agentflow.server.coordinator;

import com.agentflow.common.state.TaskStatus;
import com.agentflow.server.entity.CourseEntity;
import com.agentflow.server.entity.LectureEntity;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.LectureMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseQaDecomposerTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String EV = "{\"lectureId\":11,\"seq\":1,\"startMs\":750000,\"endMs\":805000,\"source\":\"speech\",\"quote\":\"a real quote from the lecture\"}";

    private final ObjectMapper om = new ObjectMapper();
    private final CourseMapper courses = mock(CourseMapper.class);
    private final LectureMapper lectures = mock(LectureMapper.class);
    private final CourseQaDecomposer d = new CourseQaDecomposer(om, courses, lectures);

    private static LectureEntity lecture(long id, int seq, String status) {
        LectureEntity l = new LectureEntity();
        l.setId(id);
        l.setCourseId(3L);
        l.setSeq(seq);
        l.setTitle("Lecture " + seq);
        l.setStatus(status);
        l.setMediaKey(KEY);
        l.setDurationMs(4648000L);
        l.setConcepts("[{\"name\":\"Pre-training\",\"startMs\":0,\"summary\":\"s\"}]");
        return l;
    }

    @BeforeEach
    void course() {
        when(courses.selectById(3L)).thenReturn(new CourseEntity());
        when(lectures.selectList(any())).thenReturn(List.of(lecture(11, 1, "READY"), lecture(12, 2, "INGESTING"),
                lecture(13, 3, "PARTIAL"), lecture(14, 4, "FAILED")));
    }

    private JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    private static SubtaskEntity sub(int seq, String kind, String status, String input, String output) {
        SubtaskEntity s = new SubtaskEntity();
        s.setSeq(seq);
        s.setKind(kind);
        s.setStatus(status);
        s.setInput(input);
        s.setOutput(output);
        return s;
    }

    private static TaskEntity task() {
        TaskEntity t = new TaskEntity();
        t.setType("COURSE_QA");
        t.setPayload("{\"courseId\":3,\"question\":\"what is rlhf\"}");
        return t;
    }

    /** 第一个 QA_AGENT(已成功),输出里有 n 条带证据的结论。 */
    private List<SubtaskEntity> afterAgent(int claims) throws Exception {
        String input = d.decompose(json("{\"courseId\":3,\"question\":\"what is rlhf\",\"model\":\"m1\"}")).get(0).inputJson();
        StringBuilder out = new StringBuilder("{\"question\":\"what is rlhf\",\"notFound\":false,\"claims\":[");
        for (int i = 0; i < claims; i++) {
            out.append(i == 0 ? "" : ",").append("{\"text\":\"claim ").append(i).append("\",\"evidence\":[").append(EV).append("]}");
        }
        out.append("],\"steps\":2,\"toolCalls\":[],\"usage\":{}}");
        return new ArrayList<>(List.of(sub(0, "QA_AGENT", "COMPLETED", input, out.toString())));
    }

    private static String verified(int kept, int dropped) {
        StringBuilder sb = new StringBuilder("{\"claims\":[");
        for (int i = 0; i < kept; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"text\":\"kept ").append(i).append("\",\"evidence\":[").append(EV).append("]}");
        }
        sb.append("],\"dropped\":[");
        for (int i = 0; i < dropped; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"text\":\"bad ").append(i).append("\",\"layer\":2,\"reason\":\"引用的原文找不到 ").append(i).append("\"}");
        }
        return sb.append("],\"usage\":{}}").toString();
    }

    @Test
    void decomposeBuildsTheAgentInputFromReadyLectures() throws Exception {
        List<SubtaskDef> defs = d.decompose(json("{\"courseId\":3,\"question\":\"  what is rlhf \",\"model\":\"m1\","
                + "\"history\":[{\"question\":\"q1\",\"answer\":\"a1\"},{\"question\":\"q2\",\"answer\":\"a2\"},"
                + "{\"question\":\"q3\",\"answer\":\"a3\"},{\"question\":\"q4\",\"answer\":\"a4\"}]}"));
        assertEquals(1, defs.size());
        assertEquals("QA_AGENT", defs.get(0).kind());
        assertEquals(0, defs.get(0).seq());
        JsonNode in = json(defs.get(0).inputJson());
        assertEquals(3, in.get("courseId").asLong());
        assertEquals("what is rlhf", in.get("question").asText());
        assertEquals("m1", in.get("model").asText());
        assertEquals(2, in.get("lectures").size(), "处理中和失败的讲次不能拿来回答问题");
        JsonNode first = in.get("lectures").get(0);
        assertEquals(11, first.get("lectureId").asLong());
        assertEquals(1, first.get("seq").asInt());
        assertEquals(KEY, first.get("mediaKey").asText());
        assertEquals(4648000, first.get("durationMs").asLong());
        assertEquals("Pre-training", first.get("concepts").get(0).get("name").asText());
        assertEquals(3, in.get("lectures").get(1).get("seq").asInt());
        assertEquals(3, in.get("history").size(), "只带最近 3 轮");
        assertEquals("q2", in.get("history").get(0).get("question").asText());
    }

    @Test
    void decomposeRejectsBadQuestionsAndEmptyCourses() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> d.decompose(null));
        assertThrows(IllegalArgumentException.class, () -> d.decompose(json("{\"courseId\":3,\"question\":\"  \"}")));
        assertThrows(IllegalArgumentException.class, () -> d.decompose(json("{\"courseId\":3,\"question\":\"" + "x".repeat(501) + "\"}")));
        assertThrows(IllegalArgumentException.class, () -> d.decompose(json("{\"courseId\":9,\"question\":\"q\"}")));   // 课程不存在
        when(lectures.selectList(any())).thenReturn(List.of(lecture(12, 2, "INGESTING")));
        assertThrows(IllegalArgumentException.class, () -> d.decompose(json("{\"courseId\":3,\"question\":\"q\"}")));   // 没有可用的讲次
    }

    @Test
    void verifyFollowsTheAgent() throws Exception {
        List<SubtaskDef> next = d.nextStage(task(), afterAgent(2));
        assertEquals(1, next.size());
        assertEquals("QA_VERIFY", next.get(0).kind());
        assertEquals(1, next.get(0).seq());
        JsonNode in = json(next.get(0).inputJson());
        assertEquals(3, in.get("courseId").asLong());
        assertEquals("what is rlhf", in.get("question").asText());
        assertEquals("m1", in.get("model").asText());
        assertEquals(2, in.get("claims").size());
        assertEquals("claim 0", in.get("claims").get(0).get("text").asText());
        assertEquals(2, in.get("lectures").size());
        assertEquals(KEY, in.get("lectures").get(0).get("mediaKey").asText());
        assertFalse(in.get("lectures").get(0).has("concepts"), "核验用不到知识点目录");
    }

    @Test
    void notFoundEndsWithoutVerification() throws Exception {
        List<SubtaskEntity> subs = afterAgent(0);
        assertTrue(d.nextStage(task(), subs).isEmpty(), "一条结论都没有,没有东西可核验");
        subs.get(0).setOutput("{\"notFound\":true,\"claims\":[]}");
        assertTrue(d.nextStage(task(), subs).isEmpty());
        assertEquals(TaskStatus.COMPLETED, d.terminalStatus(task(), subs, TaskStatus.COMPLETED));
    }

    @Test
    void tooManyDroppedClaimsTriggerOneMoreRound() throws Exception {
        List<SubtaskEntity> subs = afterAgent(3);
        subs.add(sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(1, 2)));
        List<SubtaskDef> next = d.nextStage(task(), subs);
        assertEquals(1, next.size());
        assertEquals("QA_AGENT", next.get(0).kind());
        assertEquals(2, next.get(0).seq());
        JsonNode in = json(next.get(0).inputJson());
        assertEquals("what is rlhf", in.get("question").asText());
        assertEquals(2, in.get("lectures").size());
        assertEquals(2, in.get("feedback").size());
        assertEquals("bad 0", in.get("feedback").get(0).get("text").asText());
        assertEquals("引用的原文找不到 0", in.get("feedback").get(0).get("reason").asText());
    }

    @Test
    void aFewDroppedClaimsDoNotTriggerAnotherRound() throws Exception {
        List<SubtaskEntity> subs = afterAgent(4);
        subs.add(sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(3, 1)));
        assertTrue(d.nextStage(task(), subs).isEmpty(), "4 条里拿掉 1 条,不到三分之一");
        subs.set(1, sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(4, 0)));
        assertTrue(d.nextStage(task(), subs).isEmpty());
        assertEquals(TaskStatus.COMPLETED, d.terminalStatus(task(), subs, TaskStatus.COMPLETED));
    }

    @Test
    void thereIsNoThirdRound() throws Exception {
        List<SubtaskEntity> subs = afterAgent(3);
        subs.add(sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(0, 3)));
        subs.add(sub(2, "QA_AGENT", "COMPLETED", subs.get(0).getInput(), subs.get(0).getOutput()));
        List<SubtaskDef> next = d.nextStage(task(), subs);
        assertEquals(List.of("QA_VERIFY"), next.stream().map(SubtaskDef::kind).toList());
        assertEquals(3, next.get(0).seq());
        subs.add(sub(3, "QA_VERIFY", "COMPLETED", "{}", verified(0, 3)));
        assertTrue(d.nextStage(task(), subs).isEmpty(), "第二轮还是全被拿掉,也不再补");
        assertEquals(TaskStatus.COMPLETED, d.terminalStatus(task(), subs, TaskStatus.COMPLETED));
    }

    @Test
    void theSecondRoundIsToldWhatWasKept() throws Exception {
        List<SubtaskEntity> subs = afterAgent(3);
        subs.add(sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(1, 2)));
        JsonNode in = json(d.nextStage(task(), subs).get(0).inputJson());
        assertEquals(1, in.get("kept").size());
        assertEquals("kept 0", in.get("kept").get(0).get("text").asText());
    }

    @Test
    void aSecondRoundThatFailsOrAddsNothingKeepsTheFirstRoundsAnswer() throws Exception {
        List<SubtaskEntity> subs = afterAgent(3);
        subs.add(sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(1, 2)));
        subs.add(sub(2, "QA_AGENT", "FAILED", subs.get(0).getInput(), null));
        assertTrue(d.nextStage(task(), subs).isEmpty());
        assertEquals(TaskStatus.PARTIAL_FAILED, d.terminalStatus(task(), subs, TaskStatus.PARTIAL_FAILED),
                "第一轮通过核验的结论还在,不能因为补的一轮没跑成就整题失败");

        subs.set(2, sub(2, "QA_AGENT", "COMPLETED", subs.get(0).getInput(), subs.get(0).getOutput()));
        subs.add(sub(3, "QA_VERIFY", "FAILED", "{}", null));
        assertTrue(d.nextStage(task(), subs).isEmpty());
        assertEquals(TaskStatus.PARTIAL_FAILED, d.terminalStatus(task(), subs, TaskStatus.PARTIAL_FAILED));

        subs.remove(3);
        subs.set(2, sub(2, "QA_AGENT", "COMPLETED", subs.get(0).getInput(),
                "{\"question\":\"what is rlhf\",\"notFound\":true,\"claims\":[],\"steps\":2,\"toolCalls\":[],\"usage\":{}}"));
        assertTrue(d.nextStage(task(), subs).isEmpty(), "补的一轮什么都没补上:结束");
        assertEquals(TaskStatus.COMPLETED, d.terminalStatus(task(), subs, TaskStatus.COMPLETED));
    }

    @Test
    void historyIsCappedAndMalformedHistoryIsIgnored() throws Exception {
        String huge = "x".repeat(200_000);
        JsonNode in = json(d.decompose(json("{\"courseId\":3,\"question\":\"what is rlhf\",\"history\":["
                + "{\"question\":\"" + huge + "\",\"answer\":\"" + huge + "\"},"
                + "{\"question\":\"what is sft\",\"answer\":\"\"},"
                + "{\"question\":\"\",\"answer\":\"an answer without a question\"},"
                + "\"not a turn\"]}")).get(0).inputJson());
        assertEquals(1, in.get("history").size(), "没有问题或没有回答的那几轮不带");
        assertEquals(CourseQaDecomposer.MAX_QUESTION_CHARS, in.get("history").get(0).get("question").asText().length());
        assertEquals(CourseQaDecomposer.MAX_HISTORY_ANSWER_CHARS, in.get("history").get(0).get("answer").asText().length());
        // 通用的任务提交接口可以送任何形状的 payload:history 不是数组时当作没有,不能抛空指针
        assertEquals(0, json(d.decompose(json("{\"courseId\":3,\"question\":\"q\",\"history\":{\"question\":\"a\"}}"))
                .get(0).inputJson()).get("history").size());
    }

    // ---- 评测选项(计划 4):固定检索流水线、长上下文、不调判官、不补一轮 ----

    private JsonNode agentInput(String eval) throws Exception {
        return json(d.decompose(json("{\"courseId\":3,\"question\":\"what is rlhf\",\"eval\":" + eval + "}")).get(0).inputJson());
    }

    @Test
    void evalOptionsArePassedOnAndValidated() throws Exception {
        assertEquals("rag", agentInput("{\"mode\":\"rag\"}").get("mode").asText());
        assertEquals("long", agentInput("{\"mode\":\"long\",\"judge\":false}").get("mode").asText());
        JsonNode plain = json(d.decompose(json("{\"courseId\":3,\"question\":\"what is rlhf\"}")).get(0).inputJson());
        assertFalse(plain.has("mode") || plain.has("judge") || plain.has("repair"), "不带选项时和以前一样");
        assertFalse(agentInput("{\"mode\":\"agent\"}").has("mode"));
        for (String bad : new String[]{"{\"mode\":\"everything\"}", "{\"judge\":\"no\"}", "{\"repair\":0}", "\"rag\"", "{\"mode\":5}"}) {
            assertThrows(IllegalArgumentException.class, () -> agentInput(bad), bad);
        }
    }

    @Test
    void withoutTheJudgeVerifyIsToldSo() throws Exception {
        String input = d.decompose(json("{\"courseId\":3,\"question\":\"what is rlhf\",\"eval\":{\"judge\":false}}")).get(0).inputJson();
        List<SubtaskEntity> subs = new ArrayList<>(List.of(sub(0, "QA_AGENT", "COMPLETED", input, afterAgent(2).get(0).getOutput())));
        JsonNode verify = json(d.nextStage(task(), subs).get(0).inputJson());
        assertFalse(verify.get("judge").asBoolean(true));
        assertFalse(json(d.nextStage(task(), afterAgent(2)).get(0).inputJson()).has("judge"), "默认不写这一项,核验照常调判官");
    }

    @Test
    void ragAndLongNeverGetARepairRoundAndItCanBeSwitchedOff() throws Exception {
        for (String eval : new String[]{"{\"mode\":\"rag\"}", "{\"mode\":\"long\"}", "{\"repair\":false}"}) {
            String input = d.decompose(json("{\"courseId\":3,\"question\":\"what is rlhf\",\"eval\":" + eval + "}")).get(0).inputJson();
            List<SubtaskEntity> subs = new ArrayList<>(List.of(sub(0, "QA_AGENT", "COMPLETED", input, afterAgent(3).get(0).getOutput())));
            subs.add(sub(1, "QA_VERIFY", "COMPLETED", "{}", verified(0, 3)));
            assertTrue(d.nextStage(task(), subs).isEmpty(), eval + ":全被拿掉也不补一轮");
        }
    }

    @Test
    void aFailedStepFailsTheTask() throws Exception {
        List<SubtaskEntity> agentFailed = List.of(sub(0, "QA_AGENT", "FAILED", "{}", null));
        assertTrue(d.nextStage(task(), agentFailed).isEmpty());
        assertEquals(TaskStatus.FAILED, d.terminalStatus(task(), agentFailed, TaskStatus.FAILED));
        List<SubtaskEntity> verifyFailed = afterAgent(2);
        verifyFailed.add(sub(1, "QA_VERIFY", "FAILED", "{}", null));
        assertTrue(d.nextStage(task(), verifyFailed).isEmpty());
        assertEquals(TaskStatus.FAILED, d.terminalStatus(task(), verifyFailed, TaskStatus.PARTIAL_FAILED),
                "核验没跑成,没核验过的结论不能当答案");
    }
}
