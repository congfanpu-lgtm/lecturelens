package com.agentflow.worker.lecture;

import com.agentflow.worker.lecture.EvidenceChecker.Evidence;
import com.agentflow.worker.lecture.EvidenceChecker.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核验的第 1、2 层:时间是否合法、引文是否真的在那一段里。 */
class EvidenceCheckerTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final long DURATION = 310_000;
    private static final JsonNode UTTERANCES = json("["
            + "{\"startMs\":0,\"endMs\":5000,\"text\":\"Welcome, it's the lecture on optimization.\"},"
            + "{\"startMs\":5000,\"endMs\":12000,\"text\":\"Gradient descent updates the parameters\"},"
            + "{\"startMs\":12000,\"endMs\":18000,\"text\":\"using the gradient of the loss function.\"},"
            + "{\"startMs\":60000,\"endMs\":66000,\"text\":\"Stochastic gradient descent uses one sample per step.\"},"
            + "{\"startMs\":300000,\"endMs\":306000,\"text\":\"That is all for today.\"}]");
    private static final JsonNode FRAMES = json("["
            + "{\"tsMs\":20000,\"file\":\"frames/000020000.jpg\",\"text\":\"SGD: one sample per step\\nNoisy but cheap\"},"
            + "{\"tsMs\":60000,\"file\":\"frames/000060000.jpg\",\"text\":\"Learning rate decay\"}]");

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Verdict check(long startMs, long endMs, String source, String quote) {
        return EvidenceChecker.check(new Evidence(7, 3, startMs, endMs, source, quote), DURATION, UTTERANCES, FRAMES);
    }

    private static void passes(Verdict v) {
        assertTrue(v.ok(), String.valueOf(v.reason()));
        assertEquals(0, v.layer());
        assertNull(v.reason());
    }

    private static void fails(int layer, Verdict v) {
        assertFalse(v.ok());
        assertEquals(layer, v.layer(), String.valueOf(v.reason()));
        assertTrue(v.reason() != null && !v.reason().isBlank());
    }

    @Test
    void aQuoteCopiedFromTheTranscriptPasses() {
        passes(check(5000, 18000, "speech", "Gradient descent updates the parameters"));
        passes(check(5000, 18000, "speech", "gradient   DESCENT updates\nthe parameters"));     // 大小写、空白
        passes(check(0, 5000, "speech", "it’s the lecture on optimization"));                 // 弯引号
    }

    @Test
    void aQuoteSpanningTwoUtterancesPasses() {
        passes(check(5000, 18000, "speech", "updates the parameters using the gradient of the loss"));
    }

    @Test
    void aQuoteThatIsNotInTheWindowFailsAtLayerTwo() {
        Verdict v = check(5000, 18000, "speech", "Stochastic gradient descent uses one sample per step.");
        fails(2, v);                                          // 这句话在 60 秒,不在所引的 5-18 秒(放宽 15 秒也够不着)
        assertTrue(v.reason().contains("L3") && v.reason().contains("00:05-00:18"), v.reason());
    }

    @Test
    void aQuoteJustOutsideTheWindowButInsideTheMarginPasses() {
        passes(check(20000, 30000, "speech", "using the gradient of the loss function"));   // 12-18 秒,在 20 秒之前 15 秒以内
    }

    @Test
    void aTooShortQuoteFailsAtLayerTwo() {
        fails(2, check(5000, 18000, "speech", "the loss"));
        fails(2, check(5000, 18000, "speech", ""));
        fails(2, check(5000, 18000, "speech", null));
    }

    @Test
    void timesOutsideTheLectureFailAtLayerOne() {
        String q = "Gradient descent updates the parameters";
        fails(1, check(18000, 5000, "speech", q));                // 开始晚于结束
        fails(1, check(-1000, 5000, "speech", q));
        fails(1, check(300000, 400000, "speech", "That is all for today."));   // 超过这一讲的时长
        fails(1, check(0, 301000, "speech", q));                  // 一条证据横跨 5 分钟以上
    }

    @Test
    void aWindowWithNothingInItFailsAtLayerOne() {
        fails(1, check(100000, 110000, "speech", "Gradient descent updates the parameters"));   // 那段时间没人说话
        fails(1, check(0, 3000, "slide", "SGD: one sample per step"));                          // 第一张幻灯片 20 秒才出现
        assertEquals("", EvidenceChecker.windowText("speech", 100000, 110000, UTTERANCES, FRAMES));
    }

    @Test
    void aSlideQuoteIsCheckedAgainstTheSlideShownAtThatTime() {
        passes(check(25000, 30000, "slide", "SGD: one sample per step"));
        passes(check(25000, 30000, "slide", "one sample per step noisy but cheap"));     // 幻灯片上的换行不影响
        fails(2, check(25000, 30000, "slide", "Learning rate decay"));                  // 那是 60 秒才出现的下一张
        passes(check(70000, 80000, "slide", "Learning rate decay"));                    // 最后一张一直显示到结束
    }

    @Test
    void anUnknownSourceFailsAtLayerOne() {
        fails(1, check(5000, 18000, "frame", "Gradient descent updates the parameters"));
        fails(1, check(5000, 18000, null, "Gradient descent updates the parameters"));
    }
}
