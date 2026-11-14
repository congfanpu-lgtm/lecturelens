package com.agentflow.server.service;

import com.agentflow.common.mq.ResultMessage;
import com.agentflow.server.course.QuestionService;
import com.agentflow.server.entity.CourseEntity;
import com.agentflow.server.entity.LectureEntity;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.LectureMapper;
import com.agentflow.server.mapper.SubtaskMapper;
import com.agentflow.server.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** COURSE_QA:提问 → agent → 核验 →(必要时补一轮)→ 答案,经 ResultHandleService 真实入口和真实的库。 */
@SpringBootTest
@Transactional
class CourseQaFlowTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String USAGE = "\"usage\":{\"calls\":2,\"promptTokens\":200,\"completionTokens\":20,\"costMicro\":5}";

    @Autowired private QuestionService questions;
    @Autowired private ResultHandleService results;
    @Autowired private TaskMapper taskMapper;
    @Autowired private SubtaskMapper subtaskMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private LectureMapper lectureMapper;
    @MockBean private KafkaTemplate<String, Object> kafkaTemplate;

    private long lectureId;
    private long taskId;

    /** 一门课、一讲已完成的课,提一个问题;返回任务 uuid。 */
    private String ask() throws Exception {
        CourseEntity c = new CourseEntity();
        c.setName("qa-flow-test");
        courseMapper.insert(c);
        LectureEntity l = new LectureEntity();
        l.setCourseId(c.getId());
        l.setSeq(1);
        l.setTitle("LLM Agents Overview");
        l.setSourceUrl("https://youtu.be/abc");
        l.setStatus("READY");
        l.setMediaKey(KEY);
        l.setDurationMs(7101000L);
        lectureMapper.insert(l);
        lectureId = l.getId();
        String uuid = questions.ask(c.getId(), OM.readTree("{\"question\":\"what is rlhf\"}"));
        taskId = taskMapper.selectOne(new QueryWrapper<TaskEntity>().eq("task_uuid", uuid)).getId();
        return uuid;
    }

    private List<SubtaskEntity> subs() {
        return subtaskMapper.selectList(new QueryWrapper<SubtaskEntity>().eq("task_id", taskId).orderByAsc("seq"));
    }

    /** 最新的那个子任务(应该是这个 kind,还没落定)成功,输出是 output。 */
    private void finish(String kind, String output) {
        List<SubtaskEntity> all = subs();
        SubtaskEntity last = all.get(all.size() - 1);
        assertEquals(kind, last.getKind());
        results.handle(new ResultMessage(taskId, last.getId(), true, output, null));
    }

    private String claims(String... texts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < texts.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"text\":\"").append(texts[i]).append("\",\"evidence\":[{\"lectureId\":")
                    .append(lectureId).append(",\"seq\":1,\"startMs\":750000,\"endMs\":805000,\"source\":\"speech\",")
                    .append("\"quote\":\"a real quote from the lecture\"}]}");
        }
        return sb.append("]").toString();
    }

    private String agentOutput(String... texts) {
        return "{\"question\":\"what is rlhf\",\"notFound\":false,\"claims\":" + claims(texts)
                + ",\"steps\":2,\"toolCalls\":[{\"tool\":\"search_segments\",\"args\":\"{}\"}]," + USAGE + "}";
    }

    @Test
    void agentThenVerifyThenTheAnswerIsReadable() throws Exception {
        String uuid = ask();
        assertEquals("QA_AGENT", subs().get(0).getKind());
        // 库里的 JSON 列会被 MySQL 重新排版,所以解析了再比,不按字符串比
        assertEquals(KEY, OM.readTree(subs().get(0).getInput()).get("lectures").get(0).get("mediaKey").asText());

        finish("QA_AGENT", agentOutput("RLHF maximizes human preference."));
        JsonNode running = questions.answer(uuid);
        assertEquals("RUNNING", running.get("status").asText());
        assertEquals(2, running.get("stages").size());
        assertEquals(0, running.get("claims").size(), "还没核验的结论不给出去");

        finish("QA_VERIFY", "{\"claims\":" + claims("RLHF maximizes human preference.") + ",\"dropped\":[]," + USAGE + "}");
        JsonNode a = questions.answer(uuid);
        assertEquals("COMPLETED", a.get("status").asText());
        assertEquals("what is rlhf", a.get("question").asText());
        assertFalse(a.get("notFound").asBoolean());
        assertEquals("RLHF maximizes human preference.", a.get("claims").get(0).get("text").asText());
        assertEquals(750000, a.get("claims").get(0).get("evidence").get(0).get("startMs").asLong());
        assertEquals(0, a.get("dropped").size());
        assertEquals(1, a.get("rounds").asInt());
        assertEquals("search_segments", a.get("toolCalls").get(0).get("tool").asText());
        assertEquals(4, a.get("usage").get("calls").asInt());
        assertTrue(a.get("error").isNull());
    }

    @Test
    void allClaimsDroppedTwiceEndsAsNotAnswered() throws Exception {
        String uuid = ask();
        String allDropped = "{\"claims\":[],\"dropped\":[{\"text\":\"Claim A.\",\"layer\":2,\"reason\":\"引用的原文在 L1 12:30-13:25 里找不到\"},"
                + "{\"text\":\"Claim B.\",\"layer\":3,\"reason\":\"原文没有说到这一点\"}]," + USAGE + "}";
        finish("QA_AGENT", agentOutput("Claim A.", "Claim B."));
        finish("QA_VERIFY", allDropped);
        assertEquals("RUNNING", taskMapper.selectById(taskId).getStatus(), "被拿掉的太多:带着原因再答一轮");
        SubtaskEntity second = subs().get(2);
        assertEquals("QA_AGENT", second.getKind());
        assertTrue(second.getInput().contains("Claim A.") && second.getInput().contains("原文没有说到这一点"));

        finish("QA_AGENT", agentOutput("Claim A.", "Claim B."));
        finish("QA_VERIFY", allDropped);
        assertEquals("COMPLETED", taskMapper.selectById(taskId).getStatus());
        assertEquals(4, subs().size(), "只补一次");
        JsonNode a = questions.answer(uuid);
        assertEquals("COMPLETED", a.get("status").asText());
        assertEquals(0, a.get("claims").size());
        assertEquals(2, a.get("dropped").size());
        assertEquals(3, a.get("dropped").get(1).get("layer").asInt());
        assertEquals(2, a.get("rounds").asInt());
        assertFalse(a.get("notFound").asBoolean(), "不是课里没讲,是没有能通过核验的结论");
    }

    private static final String ROUND_ONE = ",\"dropped\":[{\"text\":\"Claim B.\",\"layer\":2,\"reason\":\"引用的原文找不到\"},"
            + "{\"text\":\"Claim C.\",\"layer\":3,\"reason\":\"原文没有说到这一点\"}],";

    /** 第一轮:三条结论里 A 通过、B 和 C 被拿掉,于是进入补的一轮。返回任务 uuid。 */
    private String firstRoundKeepsOnlyA() throws Exception {
        String uuid = ask();
        finish("QA_AGENT", agentOutput("Claim A.", "Claim B.", "Claim C."));
        finish("QA_VERIFY", "{\"claims\":" + claims("Claim A.") + ROUND_ONE + USAGE + "}");
        JsonNode second = OM.readTree(subs().get(2).getInput());
        assertEquals("QA_AGENT", subs().get(2).getKind());
        assertEquals("Claim A.", second.get("kept").get(0).get("text").asText(), "补的一轮要知道哪些已经通过了");
        return uuid;
    }

    @Test
    void theRepairRoundAddsToTheFirstRoundsAnswerInsteadOfReplacingIt() throws Exception {
        String uuid = firstRoundKeepsOnlyA();
        finish("QA_AGENT", agentOutput("Claim D."));
        finish("QA_VERIFY", "{\"claims\":" + claims("Claim D.") + ",\"dropped\":[]," + USAGE + "}");
        JsonNode a = questions.answer(uuid);
        assertEquals("COMPLETED", a.get("status").asText());
        assertEquals(2, a.get("claims").size(), "第一轮通过核验的 A 不能因为补了一轮就没了");
        assertEquals("Claim A.", a.get("claims").get(0).get("text").asText());
        assertEquals("Claim D.", a.get("claims").get(1).get("text").asText());
        assertEquals(0, a.get("dropped").size());
        assertEquals(2, a.get("rounds").asInt());
        assertTrue(a.get("error").isNull());
    }

    @Test
    void aRepairRoundThatAddsNothingKeepsTheFirstRoundsAnswer() throws Exception {
        String uuid = firstRoundKeepsOnlyA();
        finish("QA_AGENT", "{\"question\":\"what is rlhf\",\"notFound\":true,\"claims\":[],\"steps\":2,\"toolCalls\":[]," + USAGE + "}");
        assertEquals("COMPLETED", taskMapper.selectById(taskId).getStatus());
        JsonNode a = questions.answer(uuid);
        assertEquals("COMPLETED", a.get("status").asText());
        assertFalse(a.get("notFound").asBoolean(), "补的一轮没补上,不等于课里没讲");
        assertEquals("Claim A.", a.get("claims").get(0).get("text").asText());
        assertEquals(2, a.get("dropped").size(), "被拿掉的那两条照样列出来");
    }

    @Test
    void aRepairRoundThatFailsKeepsTheFirstRoundsAnswer() throws Exception {
        String uuid = firstRoundKeepsOnlyA();
        results.handle(new ResultMessage(taskId, subs().get(2).getId(), false, null, "模型服务不可用"));
        assertEquals("PARTIAL_FAILED", taskMapper.selectById(taskId).getStatus());
        JsonNode a = questions.answer(uuid);
        assertEquals("COMPLETED", a.get("status").asText());
        assertEquals("Claim A.", a.get("claims").get(0).get("text").asText());
        assertEquals(2, a.get("dropped").size());
        assertTrue(a.get("error").asText().contains("QA_AGENT: 模型服务不可用"), a.get("error").asText());
    }

    @Test
    void anAgentThatFindsNothingEndsWithoutVerification() throws Exception {
        String uuid = ask();
        finish("QA_AGENT", "{\"question\":\"what is rlhf\",\"notFound\":true,\"claims\":[],\"steps\":2,\"toolCalls\":[]," + USAGE + "}");
        JsonNode a = questions.answer(uuid);
        assertEquals("COMPLETED", a.get("status").asText());
        assertTrue(a.get("notFound").asBoolean());
        assertEquals(1, subs().size());
    }

    @Test
    void aFailedVerificationFailsTheQuestion() throws Exception {
        String uuid = ask();
        finish("QA_AGENT", agentOutput("Claim A."));
        SubtaskEntity verify = subs().get(1);
        results.handle(new ResultMessage(taskId, verify.getId(), false, null, "判官的输出里没有 verdicts 数组"));
        JsonNode a = questions.answer(uuid);
        assertEquals("FAILED", a.get("status").asText());
        assertEquals(0, a.get("claims").size(), "没核验过的结论不能当答案");
        assertEquals("QA_VERIFY: 判官的输出里没有 verdicts 数组", a.get("error").asText());
    }
}
