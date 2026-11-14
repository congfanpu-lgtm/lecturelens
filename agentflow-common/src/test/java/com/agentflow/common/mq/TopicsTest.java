package com.agentflow.common.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopicsTest {

    @Test
    void transcriptionHasItsOwnTopicAndEverythingElseSharesTheMainOne() {
        // 转写要等显卡,一等就是几分钟:和别的子任务共用 topic 的话,排在它后面的识别、建索引、提问都得跟着等
        assertEquals(Topics.SUBTASK_SLOW, Topics.forSubtask("LEC_TRANSCRIBE"));
        for (String kind : new String[]{"LEC_IMPORT", "LEC_FRAMES", "LEC_OCR", "LEC_INDEX", "QA_AGENT", "QA_VERIFY", "ECHO_BATCH"}) {
            assertEquals(Topics.SUBTASK, Topics.forSubtask(kind), kind);
        }
        assertEquals(Topics.SUBTASK, Topics.forSubtask(null));
    }
}
