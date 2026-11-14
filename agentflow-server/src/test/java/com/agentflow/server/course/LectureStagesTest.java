package com.agentflow.server.course;

import com.agentflow.server.entity.SubtaskEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 页面上的步骤条:一讲有十几个识别子任务,合成"识别"一项,带进度。 */
class LectureStagesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 14, 10, 0, 0);

    private static SubtaskEntity sub(String kind, String status, int createdAt, int updatedAt, String error) {
        SubtaskEntity s = new SubtaskEntity();
        s.setKind(kind);
        s.setStatus(status);
        s.setCreatedAt(T0.plusSeconds(createdAt));
        s.setUpdatedAt(T0.plusSeconds(updatedAt));
        s.setErrorMsg(error);
        return s;
    }

    private static List<SubtaskEntity> upToThePick() {
        return new ArrayList<>(List.of(sub("LEC_IMPORT", "COMPLETED", 0, 12, null),
                sub("LEC_TRANSCRIBE", "COMPLETED", 12, 110, null), sub("LEC_FRAMES", "COMPLETED", 12, 30, null)));
    }

    @Test
    void ocrBatchesAreOneStepWithProgress() {
        List<SubtaskEntity> subs = upToThePick();
        subs.add(sub("LEC_OCR", "COMPLETED", 110, 125, null));
        subs.add(sub("LEC_OCR", "COMPLETED", 110, 131, null));
        subs.add(sub("LEC_OCR", "DISPATCHED", 110, 111, null));
        List<Map<String, Object>> stages = CourseController.stages(subs);
        assertEquals(List.of("LEC_IMPORT", "LEC_TRANSCRIBE", "LEC_FRAMES", "LEC_OCR"), stages.stream().map(s -> s.get("kind")).toList());
        assertEquals(12L, stages.get(0).get("seconds"));
        Map<String, Object> ocr = stages.get(3);
        assertEquals("RUNNING", ocr.get("status"));
        assertEquals(2, ocr.get("done"));
        assertEquals(3, ocr.get("total"));
        assertNull(ocr.get("seconds"));
    }

    @Test
    void finishedBatchesShowTheTimeFromTheFirstToTheLast() {
        List<SubtaskEntity> subs = upToThePick();
        subs.add(sub("LEC_OCR", "COMPLETED", 110, 125, null));
        subs.add(sub("LEC_OCR", "COMPLETED", 110, 152, null));
        subs.add(sub("LEC_INDEX", "COMPLETED", 152, 156, null));
        List<Map<String, Object>> stages = CourseController.stages(subs);
        assertEquals(List.of("LEC_IMPORT", "LEC_TRANSCRIBE", "LEC_FRAMES", "LEC_OCR", "LEC_INDEX"), stages.stream().map(s -> s.get("kind")).toList());
        assertEquals("COMPLETED", stages.get(3).get("status"));
        assertEquals(42L, stages.get(3).get("seconds"));
        assertEquals(2, stages.get(3).get("done"));
    }

    @Test
    void aFailedBatchMakesTheStepFailedOnceAllHaveSettled() {
        List<SubtaskEntity> subs = upToThePick();
        subs.add(sub("LEC_OCR", "FAILED", 110, 140, "media 500: OSError"));
        subs.add(sub("LEC_OCR", "DISPATCHED", 110, 111, null));
        assertEquals("RUNNING", CourseController.stages(subs).get(3).get("status"));
        subs.set(4, sub("LEC_OCR", "COMPLETED", 110, 150, null));
        Map<String, Object> ocr = CourseController.stages(subs).get(3);
        assertEquals("FAILED", ocr.get("status"));
        assertEquals("media 500: OSError", ocr.get("error"));
        assertEquals(1, ocr.get("done"));
        assertEquals(2, ocr.get("total"));
    }
}
