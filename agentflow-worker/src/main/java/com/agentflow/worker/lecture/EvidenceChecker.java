package com.agentflow.worker.lecture;

import com.agentflow.worker.agent.TextUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 核验的第 1、2 层,纯函数:一条证据说"第几讲、哪段时间、原话是什么",这里检查
 * (1) 这段时间在这一讲里真实存在、而且确实有这种来源的内容;
 * (2) 引用的原话真的出现在那段时间的逐字稿(或那时显示的幻灯片文字)里。
 *
 * <p>只做文字上的核对。原话是真的、但并不支持结论,由第 3 层(判官模型)判断。
 */
public final class EvidenceChecker {

    /** 时间窗前后各放宽这么多:模型抄时间段时差一两句话很常见,片段之间本来也有 10 秒重叠。 */
    public static final long MARGIN_MS = 15_000;
    /** 引文太短("yes"、"the loss")到处都能找到,等于没有引用。 */
    public static final int MIN_QUOTE_CHARS = 12;
    /** 一条证据最多指这么长的一段:再长就不是"指给你看在哪",而是"在这一讲里"。 */
    public static final long MAX_WINDOW_MS = 300_000;

    public record Evidence(long lectureId, int seq, long startMs, long endMs, String source, String quote) {
    }

    /** ok 时 layer 为 0、reason 为 null;否则 layer 是在第几层没通过。 */
    public record Verdict(boolean ok, int layer, String reason) {
    }

    private EvidenceChecker() {
    }

    public static Verdict check(Evidence e, long durationMs, JsonNode utterances, JsonNode frames) {
        String where = "L" + e.seq() + " " + clock(e.startMs()) + "-" + clock(e.endMs());
        if (!"speech".equals(e.source()) && !"slide".equals(e.source())) {
            return new Verdict(false, 1, "证据的来源只能是语音或幻灯片:" + e.source());
        }
        if (e.startMs() < 0 || e.startMs() > e.endMs() || e.endMs() > durationMs + MARGIN_MS) {
            return new Verdict(false, 1, where + " 不是这一讲里的一段时间(这一讲长 " + clock(durationMs) + ")");
        }
        if (e.endMs() - e.startMs() > MAX_WINDOW_MS) {
            return new Verdict(false, 1, where + " 太长了,一条证据最多指 " + MAX_WINDOW_MS / 60_000 + " 分钟的一段");
        }
        String text = windowText(e.source(), e.startMs() - MARGIN_MS, e.endMs() + MARGIN_MS, utterances, frames);
        if (text.isEmpty()) {
            return new Verdict(false, 1, where + " 这段时间里没有" + ("slide".equals(e.source()) ? "幻灯片" : "人说话"));
        }
        String quote = TextUtil.normalize(e.quote());
        if (quote.length() < MIN_QUOTE_CHARS) {
            return new Verdict(false, 2, "引用的原文太短(不到 " + MIN_QUOTE_CHARS + " 个字符),不能当证据");
        }
        if (!TextUtil.normalize(text).contains(quote)) {
            return new Verdict(false, 2, "引用的原文在 " + where + " 里找不到");
        }
        return new Verdict(true, 0, null);
    }

    /**
     * 这段时间里的原文。speech:和时间窗有重叠的 utterance,用空格连起来(所以跨两条的引文也能对上);
     * slide:那段时间显示着的幻灯片(一张从它出现显示到下一张出现)的文字,用换行连起来。
     */
    public static String windowText(String source, long fromMs, long toMs, JsonNode utterances, JsonNode frames) {
        List<String> parts = new ArrayList<>();
        if ("speech".equals(source) && utterances != null) {
            for (JsonNode u : utterances) {
                if (u.path("endMs").asLong() > fromMs && u.path("startMs").asLong() < toMs) {
                    parts.add(u.path("text").asText(""));
                }
            }
            return String.join(" ", parts);
        }
        if ("slide".equals(source) && frames != null) {
            for (int i = 0; i < frames.size(); i++) {
                long shownFrom = frames.get(i).path("tsMs").asLong();
                long shownTo = i + 1 < frames.size() ? frames.get(i + 1).path("tsMs").asLong() : Long.MAX_VALUE;
                if (shownTo > fromMs && shownFrom < toMs) {
                    parts.add(frames.get(i).path("text").asText(""));
                }
            }
            return String.join("\n", parts);
        }
        return "";
    }

    /** 分:秒,分钟可以超过 59。 */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
