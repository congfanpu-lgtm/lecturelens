package com.agentflow.server.service;

import com.agentflow.common.mq.ResultMessage;
import com.agentflow.server.entity.CourseEntity;
import com.agentflow.server.entity.LectureEntity;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.LectureMapper;
import com.agentflow.server.mapper.SubtaskMapper;
import com.agentflow.server.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LECTURE_INGEST 的五步推进,经 ResultHandleService 真实入口,最后讲次那一行被写对。 */
@SpringBootTest
@Transactional
class LectureIngestFlowTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    @Autowired private ResultHandleService service;
    @Autowired private TaskMapper taskMapper;
    @Autowired private SubtaskMapper subtaskMapper;
    @Autowired private CourseMapper courseMapper;
    @Autowired private LectureMapper lectureMapper;
    @MockBean private KafkaTemplate<String, Object> kafkaTemplate;

    private long lectureId;
    private long taskId;

    /** 一门课、一讲(INGESTING)、一个 RUNNING 的任务和已发出的 LEC_IMPORT;返回 LEC_IMPORT 的子任务 id。 */
    private long fixture() {
        CourseEntity c = new CourseEntity();
        c.setName("flow-test");
        courseMapper.insert(c);
        LectureEntity l = new LectureEntity();
        l.setCourseId(c.getId());
        l.setSeq(1);
        l.setSourceUrl("https://youtu.be/abc");
        l.setStatus("INGESTING");
        lectureMapper.insert(l);
        lectureId = l.getId();
        TaskEntity t = new TaskEntity();
        t.setTaskUuid(UUID.randomUUID().toString());
        t.setType("LECTURE_INGEST");
        t.setStatus("RUNNING");
        t.setPayload("{\"lectureId\":" + lectureId + ",\"courseId\":" + c.getId() + "}");
        t.setSubtaskTotal(1);
        t.setSubtaskDone(0);
        t.setSubtaskFailed(0);
        taskMapper.insert(t);
        taskId = t.getId();
        SubtaskEntity s = new SubtaskEntity();
        s.setSubtaskUuid(UUID.randomUUID().toString());
        s.setTaskId(taskId);
        s.setSeq(0);
        s.setKind("LEC_IMPORT");
        s.setStatus("DISPATCHED");
        s.setInput("{\"lectureId\":" + lectureId + ",\"url\":\"https://youtu.be/abc\"}");
        subtaskMapper.insert(s);
        return s.getId();
    }

    /** 这个 kind 的子任务(刚由阶段推进建出来,PENDING),标成已发出后返回它的 id。 */
    private long dispatched(String kind) {
        SubtaskEntity s = subtaskMapper.selectOne(new QueryWrapper<SubtaskEntity>().eq("task_id", taskId).eq("kind", kind));
        assertEquals("PENDING", s.getStatus(), kind);
        subtaskMapper.casStatus(s.getId(), "PENDING", "DISPATCHED");
        return s.getId();
    }

    private void ok(long subtaskId, String output) {
        service.handle(new ResultMessage(taskId, subtaskId, true, output, null));
    }

    /** 这个 kind 的全部子任务(刚建出来,PENDING),标成已发出后返回它们的 id。 */
    private List<Long> dispatchedAll(String kind) {
        List<SubtaskEntity> all = subtaskMapper.selectList(
                new QueryWrapper<SubtaskEntity>().eq("task_id", taskId).eq("kind", kind).orderByAsc("seq"));
        all.forEach(s -> subtaskMapper.casStatus(s.getId(), "PENDING", "DISPATCHED"));
        return all.stream().map(SubtaskEntity::getId).toList();
    }

    private boolean none(String kind) {
        return subtaskMapper.selectList(new QueryWrapper<SubtaskEntity>().eq("task_id", taskId).eq("kind", kind)).isEmpty();
    }

    @Test
    void sixStepsThenTheLectureIsReady() {
        ok(fixture(), "{\"mediaKey\":\"" + KEY + "\",\"title\":\"LLM Agents Overview\",\"durationMs\":7101000,\"hasVideo\":true}");
        long transcribe = dispatched("LEC_TRANSCRIBE");
        long frames = dispatched("LEC_FRAMES");
        ok(frames, "{\"candidates\":25,\"batches\":2,\"cached\":false}");
        assertTrue(none("LEC_OCR"), "转写还没落定,不进下一阶段");
        ok(transcribe, "{\"utterances\":1298,\"cached\":false}");
        List<Long> batches = dispatchedAll("LEC_OCR");
        assertEquals(2, batches.size(), "挑帧说有 2 批");
        ok(batches.get(1), "{\"batch\":1,\"frames\":5,\"cached\":false}");
        assertTrue(none("LEC_INDEX"), "还有一批没识别完,不能开始建索引");
        ok(batches.get(0), "{\"batch\":0,\"frames\":20,\"cached\":false}");
        long index = dispatched("LEC_INDEX");
        assertTrue(subtaskMapper.selectById(index).getInput().contains("\"withSlides\": true")
                || subtaskMapper.selectById(index).getInput().contains("\"withSlides\":true"));
        ok(index, "{\"speech\":150,\"slides\":120}");
        assertEquals("INGESTING", lectureMapper.selectById(lectureId).getStatus());
        ok(dispatched("LEC_CONCEPTS"), "{\"concepts\":[{\"name\":\"Pre-training\",\"startMs\":0,\"summary\":\"s\"}],\"usage\":{}}");

        assertEquals("COMPLETED", taskMapper.selectById(taskId).getStatus());
        LectureEntity l = lectureMapper.selectById(lectureId);
        assertEquals("READY", l.getStatus());
        assertEquals(KEY, l.getMediaKey());
        assertEquals("LLM Agents Overview", l.getTitle());
        assertTrue(l.getConcepts().contains("Pre-training"));
    }

    @Test
    void aBatchThatFailsForGoodLeavesTheLecturePartlyDone() {
        ok(fixture(), "{\"mediaKey\":\"" + KEY + "\",\"title\":\"T\",\"durationMs\":1000,\"hasVideo\":true}");
        ok(dispatched("LEC_TRANSCRIBE"), "{\"utterances\":10,\"cached\":false}");
        ok(dispatched("LEC_FRAMES"), "{\"candidates\":25,\"batches\":2,\"cached\":false}");
        List<Long> batches = dispatchedAll("LEC_OCR");
        ok(batches.get(0), "{\"batch\":0,\"frames\":20,\"cached\":false}");
        service.handle(new ResultMessage(taskId, batches.get(1), false, null, "media 500: OSError"));
        long index = dispatched("LEC_INDEX");
        assertTrue(subtaskMapper.selectById(index).getInput().replace(" ", "").contains("\"withSlides\":false"));
        ok(index, "{\"speech\":10,\"slides\":0}");
        ok(dispatched("LEC_CONCEPTS"), "{\"concepts\":[],\"usage\":{}}");

        assertEquals("PARTIAL_FAILED", taskMapper.selectById(taskId).getStatus());
        LectureEntity l = lectureMapper.selectById(lectureId);
        assertEquals("PARTIAL", l.getStatus());
        assertEquals("LEC_OCR: media 500: OSError", l.getError());
    }

    @Test
    void aFailedTranscriptionFailsTheLectureAndSaysWhy() {
        ok(fixture(), "{\"mediaKey\":\"" + KEY + "\",\"title\":\"T\",\"durationMs\":1000,\"hasVideo\":true}");
        long transcribe = dispatched("LEC_TRANSCRIBE");
        long frames = dispatched("LEC_FRAMES");
        service.handle(new ResultMessage(taskId, transcribe, false, null, "CUDA out of memory"));
        ok(frames, "{\"candidates\":25,\"batches\":2,\"cached\":false}");

        assertTrue(none("LEC_OCR"), "整讲已经失败了,不用再识别");
        assertEquals("FAILED", taskMapper.selectById(taskId).getStatus());
        LectureEntity l = lectureMapper.selectById(lectureId);
        assertEquals("FAILED", l.getStatus());
        assertEquals("LEC_TRANSCRIBE: CUDA out of memory", l.getError());
    }

    @Test
    void aSettledStepRecordsWhenItSettled() {
        long importId = fixture();
        // 用库里的时间做基准,不用 JVM 的时钟:两边时区可能不一样
        java.time.LocalDateTime dispatchedAt = subtaskMapper.selectById(importId).getUpdatedAt().minusMinutes(5);
        subtaskMapper.setUpdatedAt(importId, dispatchedAt);          // 这一步 5 分钟前发出去
        ok(importId, "{\"mediaKey\":\"" + KEY + "\",\"title\":\"T\",\"durationMs\":1000,\"hasVideo\":true}");
        java.time.LocalDateTime settledAt = subtaskMapper.selectById(importId).getUpdatedAt();
        assertTrue(settledAt.isAfter(dispatchedAt.plusMinutes(4)),
                "落定后 updated_at 应该是落定的时间(页面靠它算每一步的用时),实际还是发出时的 " + settledAt);
    }
}
