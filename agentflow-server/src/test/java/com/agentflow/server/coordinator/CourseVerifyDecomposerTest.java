package com.agentflow.server.coordinator;

import com.agentflow.server.entity.CourseEntity;
import com.agentflow.server.entity.LectureEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.LectureMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** COURSE_VERIFY:只核验给定的"结论 + 证据"(评测里校准判官用),不经过 agent。 */
class CourseVerifyDecomposerTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String CLAIM = "{\"text\":\"Reward models are trained on preferences.\",\"evidence\":["
            + "{\"lecture\":3,\"startMs\":750000,\"endMs\":805000,\"source\":\"speech\",\"quote\":\"You collect human preferences.\"}]}";

    private final ObjectMapper om = new ObjectMapper();
    private final CourseMapper courses = mock(CourseMapper.class);
    private final LectureMapper lectures = mock(LectureMapper.class);
    private final CourseVerifyDecomposer d =
            new CourseVerifyDecomposer(om, courses, new CourseQaDecomposer(om, courses, lectures));

    private static LectureEntity lecture(long id, int seq, String status) {
        LectureEntity l = new LectureEntity();
        l.setId(id);
        l.setCourseId(3L);
        l.setSeq(seq);
        l.setStatus(status);
        l.setMediaKey(KEY);
        l.setDurationMs(4648000L);
        return l;
    }

    @BeforeEach
    void course() {
        when(courses.selectById(3L)).thenReturn(new CourseEntity());
        when(lectures.selectList(any())).thenReturn(List.of(lecture(11, 1, "READY"), lecture(13, 3, "PARTIAL"), lecture(14, 4, "FAILED")));
    }

    private List<SubtaskDef> decompose(String payload) throws Exception {
        return d.decompose(om.readTree(payload));
    }

    @Test
    void oneVerifySubtaskWithLecturesTakenFromTheDatabase() throws Exception {
        List<SubtaskDef> defs = decompose("{\"courseId\":3,\"question\":\"what is rlhf\",\"claims\":[" + CLAIM + "],"
                + "\"lectures\":[{\"lectureId\":13,\"seq\":3,\"mediaKey\":\"ffffffffffffffffffffffffffffffff\"}]}");
        assertEquals("COURSE_VERIFY", d.type());
        assertEquals(1, defs.size());
        assertEquals("QA_VERIFY", defs.get(0).kind());
        JsonNode in = om.readTree(defs.get(0).inputJson());
        assertEquals("what is rlhf", in.get("question").asText());
        assertEquals(2, in.get("lectures").size(), "只有处理好的讲次");
        assertEquals(KEY, in.get("lectures").get(1).get("mediaKey").asText(), "调用方自带的 mediaKey 不算数");
        assertFalse(in.get("lectures").get(0).has("concepts"));
        JsonNode e = in.get("claims").get(0).get("evidence").get(0);
        assertEquals(13, e.get("lectureId").asLong());          // 讲次序号换成库里的 id
        assertEquals(3, e.get("seq").asInt());
        assertEquals(750000, e.get("startMs").asLong());
        assertEquals("You collect human preferences.", e.get("quote").asText());
        assertFalse(in.has("judge"));
    }

    @Test
    void theJudgeCanBeSwitchedOff() throws Exception {
        JsonNode in = om.readTree(decompose("{\"courseId\":3,\"question\":\"q\",\"claims\":[" + CLAIM + "],\"eval\":{\"judge\":false}}")
                .get(0).inputJson());
        assertFalse(in.get("judge").asBoolean(true));
    }

    @Test
    void rejectsWhatItCannotVerify() {
        String[] bad = {
                "{\"courseId\":99,\"question\":\"q\",\"claims\":[" + CLAIM + "]}",                 // 没有这门课
                "{\"courseId\":3,\"question\":\"\",\"claims\":[" + CLAIM + "]}",                   // 没有问题
                "{\"courseId\":3,\"question\":\"q\",\"claims\":[]}",                                // 没有结论
                "{\"courseId\":3,\"question\":\"q\"}",
                "{\"courseId\":3,\"question\":\"q\",\"claims\":[{\"text\":\"\",\"evidence\":[]}]}",  // 结论是空的
                "{\"courseId\":3,\"question\":\"q\",\"claims\":[" + CLAIM.replace("\"lecture\":3", "\"lecture\":4") + "]}",   // 这一讲没处理好
                "{\"courseId\":3,\"question\":\"q\",\"claims\":[" + CLAIM.replace("\"lecture\":3", "\"lecture\":\"3\"") + "]}",
        };
        for (String payload : bad) {
            assertThrows(IllegalArgumentException.class, () -> decompose(payload), payload);
        }
    }
}
