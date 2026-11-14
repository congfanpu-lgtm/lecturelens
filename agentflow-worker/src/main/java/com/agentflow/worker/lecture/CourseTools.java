package com.agentflow.worker.lecture;

import com.agentflow.worker.agent.tool.AgentTool;
import com.agentflow.worker.media.MediaClient;
import com.agentflow.worker.rag.RagClient;
import com.agentflow.worker.rag.RagClient.SegmentHit;
import com.agentflow.worker.retry.NonRetryableException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.agentflow.worker.lecture.EvidenceChecker.clock;

/**
 * 问答 agent 的三个工具:看课程概览、检索片段、展开上下文。思路来自"全局浏览 / 片段搜索 / 看细节",范围是一整门课。
 *
 * <p>工具每次提问新建一套,课程 id 和讲次表绑在里面:模型在参数里写什么都只能查到这一门课。
 * 它们故意不是 Spring bean——注册成 bean 会被收进研究 agent 的工具表。都是只读的。
 */
@Component
@RequiredArgsConstructor
public class CourseTools {

    static final int TOP_K = 5;
    static final int MAX_RESULT_CHARS = 6000;
    static final String CUT_OFF = "\n…(结果太长,只显示了前面一部分。缩小范围再查:限定讲次或时间段,或者换更具体的查询)";
    static final int LONG_CONTEXT_MAX_CHARS = 800_000;   // 评测课(12.7 小时)约 70 万字符;超过的课不适合这个基线
    static final int MAX_HIT_CHARS = 900;
    static final long MAX_CONTEXT_MS = 180_000;
    private static final int OVERVIEW_CONCEPTS = 8;

    private final RagClient rag;
    private final MediaClient media;

    private record Lecture(long id, int seq, String title, long durationMs, String mediaKey, JsonNode concepts) {
    }

    /** lectures:[{"lectureId","seq","title","durationMs","mediaKey","concepts":[{"name","startMs","summary"}]}]。 */
    public List<AgentTool> forCourse(long courseId, JsonNode lectures) {
        Map<Integer, Lecture> bySeq = new LinkedHashMap<>();
        Map<Long, Lecture> byId = new LinkedHashMap<>();
        for (JsonNode l : lectures) {
            Lecture lecture = new Lecture(l.path("lectureId").asLong(), l.path("seq").asInt(), l.path("title").asText(""),
                    l.path("durationMs").asLong(), l.path("mediaKey").asText(), l.path("concepts"));
            bySeq.put(lecture.seq(), lecture);
            byId.put(lecture.id(), lecture);
        }
        return List.of(
                tool("course_overview",
                        "看这门课有哪几讲、每讲讲了哪些知识点。不带参数:列出所有讲次和各自的几个知识点;"
                                + "带 lecture(讲次序号):列出那一讲完整的知识点目录和各自的时间。先用它决定去哪几讲里找。",
                        "{\"type\":\"object\",\"properties\":{\"lecture\":{\"type\":\"integer\",\"description\":\"讲次序号,可省\"}}}",
                        args -> overview(bySeq, args)),
                tool("search_segments",
                        "在这门课的逐字稿和幻灯片文字里检索,返回最相关的片段,每个片段标着 [L讲次 开始-结束 来源]。"
                                + "可以用 lectures 限定到几讲;限定到一讲时还可以用 from、to 限定时间范围。",
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"要找的内容\"},"
                                + "\"lectures\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"description\":\"讲次序号,可省\"},"
                                + "\"from\":{\"type\":\"string\",\"description\":\"分:秒,可省\"},"
                                + "\"to\":{\"type\":\"string\",\"description\":\"分:秒,可省\"}},\"required\":[\"query\"]}",
                        args -> search(courseId, bySeq, byId, args)),
                tool("read_context",
                        "读某一讲某段时间(最长 3 分钟)的逐字稿原文,以及那段时间显示的幻灯片文字。检索到的片段不够完整时用它看前后文。",
                        "{\"type\":\"object\",\"properties\":{\"lecture\":{\"type\":\"integer\",\"description\":\"讲次序号\"},"
                                + "\"from\":{\"type\":\"string\",\"description\":\"分:秒\"},\"to\":{\"type\":\"string\",\"description\":\"分:秒\"}},"
                                + "\"required\":[\"lecture\",\"from\",\"to\"]}",
                        args -> context(bySeq, args)));
    }

    /**
     * 评测的长上下文基线用:这门课全部讲次的逐字稿,切成不超过一分钟的段,每段的标记和检索结果一样
     * ([L讲次 开始-结束 speech]),模型引用时照抄。放不下就直接失败:悄悄截断的话,基线就成了"只看了前几讲"。
     */
    public String wholeCourse(JsonNode lectures) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode l : lectures) {
            long from = -1;
            long to = 0;
            StringBuilder block = new StringBuilder();
            for (JsonNode u : media.transcript(l.path("mediaKey").asText()).path("utterances")) {
                if (from >= 0 && u.path("endMs").asLong() - from > 60_000) {
                    append(sb, l.path("seq").asInt(), from, to, block);
                    from = -1;
                }
                if (from < 0) {
                    from = u.path("startMs").asLong();
                    block.setLength(0);
                }
                to = u.path("endMs").asLong();
                block.append(block.isEmpty() ? "" : " ").append(u.path("text").asText(""));
            }
            if (from >= 0) {
                append(sb, l.path("seq").asInt(), from, to, block);
            }
            if (sb.length() > LONG_CONTEXT_MAX_CHARS) {
                throw new NonRetryableException("这门课的逐字稿超过了长上下文基线的上限 " + LONG_CONTEXT_MAX_CHARS
                        + " 个字符(读到第 " + l.path("seq").asInt() + " 讲时已有 " + sb.length() + " 个)");
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, int seq, long fromMs, long toMs, StringBuilder text) {
        sb.append(sb.isEmpty() ? "" : "\n\n").append("[L").append(seq).append(' ').append(clock(fromMs)).append('-')
                .append(clock(toMs)).append(" speech]\n").append(text);
    }

    private static String overview(Map<Integer, Lecture> bySeq, JsonNode args) {
        StringBuilder sb = new StringBuilder();
        if (args.hasNonNull("lecture")) {
            Lecture l = lecture(bySeq, args.get("lecture"));
            sb.append(header(l));
            for (JsonNode c : l.concepts()) {
                sb.append("\n  ").append(clock(c.path("startMs").asLong())).append(' ')
                        .append(c.path("name").asText()).append(':').append(c.path("summary").asText());
            }
            if (l.concepts().isEmpty()) {
                sb.append("\n  (这一讲没有知识点目录,用 search_segments 直接检索)");
            }
            return sb.toString();
        }
        for (Lecture l : bySeq.values()) {
            sb.append(header(l));
            List<String> names = new ArrayList<>();
            for (JsonNode c : l.concepts()) {
                if (names.size() < OVERVIEW_CONCEPTS) {
                    names.add(c.path("name").asText());
                }
            }
            if (!names.isEmpty()) {
                sb.append("\n  ").append(String.join("、", names))
                        .append(l.concepts().size() > names.size() ? " 等 " + l.concepts().size() + " 个知识点" : "");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String search(long courseId, Map<Integer, Lecture> bySeq, Map<Long, Lecture> byId, JsonNode args) {
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        List<Long> lectureIds = null;
        // 模型常把 lectures 写成单数,或者只给一个数而不是数组:都按"限定到这几讲"理解,看不懂就报错,不能悄悄查全课
        JsonNode wanted = args.hasNonNull("lectures") ? args.get("lectures") : args.path("lecture");
        if (!wanted.isMissingNode() && !wanted.isNull() && !(wanted.isArray() && wanted.isEmpty())) {
            lectureIds = new ArrayList<>();
            for (JsonNode n : wanted.isArray() ? wanted : List.of(wanted)) {
                lectureIds.add(lecture(bySeq, n).id());
            }
        }
        Long fromMs = args.hasNonNull("from") ? parseClock(args.get("from").asText()) : null;
        Long toMs = args.hasNonNull("to") ? parseClock(args.get("to").asText()) : null;
        if ((fromMs != null || toMs != null) && (lectureIds == null || lectureIds.size() != 1)) {
            throw new IllegalArgumentException("from、to 要和单独一讲一起用:lectures 里只写一个讲次序号");
        }
        if (fromMs != null && toMs != null && toMs < fromMs) {
            throw new IllegalArgumentException("to 不能早于 from");
        }
        List<SegmentHit> hits = rag.searchSegments(courseId, query, TOP_K, lectureIds, fromMs, toMs);
        StringBuilder sb = new StringBuilder();
        for (SegmentHit h : hits) {
            Lecture l = byId.get(h.lectureId());
            if (l == null) {
                continue;   // 不在这次提问的讲次表里(比如还在处理中的讲次),不给模型看
            }
            String text = h.text().length() > MAX_HIT_CHARS ? h.text().substring(0, MAX_HIT_CHARS) : h.text();
            // 幻灯片片段从这一张出现一直到下一张出现,可能挂十几分钟;模型会照抄这个时间段当证据,
            // 而一条证据最多指 5 分钟(EvidenceChecker.MAX_WINDOW_MS),所以这里只写到出现后的 5 分钟
            long endMs = "slide".equals(h.kind()) ? Math.min(h.endMs(), h.startMs() + EvidenceChecker.MAX_WINDOW_MS) : h.endMs();
            sb.append(sb.isEmpty() ? "" : "\n\n")
                    .append("[L").append(l.seq()).append(' ').append(clock(h.startMs())).append('-').append(clock(endMs))
                    .append(' ').append(h.kind()).append("]\n").append(text);
        }
        return sb.isEmpty() ? "这门课里没有找到相关内容:" + query : sb.toString();
    }

    private String context(Map<Integer, Lecture> bySeq, JsonNode args) {
        Lecture l = lecture(bySeq, args.path("lecture"));
        long fromMs = parseClock(args.path("from").asText(""));
        long toMs = parseClock(args.path("to").asText(""));
        if (toMs < fromMs) {
            throw new IllegalArgumentException("to 不能早于 from");
        }
        toMs = Math.min(toMs, fromMs + MAX_CONTEXT_MS);
        StringBuilder sb = new StringBuilder("[L").append(l.seq()).append(' ').append(clock(fromMs)).append('-')
                .append(clock(toMs)).append(" speech]");
        int lines = 0;
        for (JsonNode u : media.transcript(l.mediaKey()).path("utterances")) {
            if (u.path("endMs").asLong() > fromMs && u.path("startMs").asLong() < toMs) {
                sb.append('\n').append(clock(u.path("startMs").asLong())).append(' ').append(u.path("text").asText());
                lines++;
            }
        }
        if (lines == 0) {
            sb.append("\n(这段时间没有人说话)");
        }
        JsonNode frames;
        try {
            frames = media.frameList(l.mediaKey()).path("frames");
        } catch (NonRetryableException e) {
            return sb.toString();   // 这一讲没有截到幻灯片
        }
        for (int i = 0; i < frames.size(); i++) {
            long shownFrom = frames.get(i).path("tsMs").asLong();
            long shownTo = i + 1 < frames.size() ? frames.get(i + 1).path("tsMs").asLong() : Long.MAX_VALUE;
            if (shownTo > fromMs && shownFrom < toMs) {
                // 和检索结果同一种标记,来源写明是 slide、时间段最长 5 分钟:模型引用幻灯片时照抄就对
                long until = Math.min(Math.min(shownTo, l.durationMs()), shownFrom + EvidenceChecker.MAX_WINDOW_MS);
                sb.append("\n\n[L").append(l.seq()).append(' ').append(clock(shownFrom)).append('-').append(clock(until))
                        .append(" slide]\n").append(frames.get(i).path("text").asText());
            }
        }
        return sb.toString();
    }

    private static String header(Lecture l) {
        return "L" + l.seq() + " " + l.title() + " (" + clock(l.durationMs()) + ")";
    }

    /** 序号不在这门课里就报错给模型看,不能悄悄退化成查全课。 */
    private static Lecture lecture(Map<Integer, Lecture> bySeq, JsonNode seq) {
        Lecture l = bySeq.get(seqOf(seq));
        if (l == null) {
            throw new IllegalArgumentException("这门课没有第 " + (seq == null ? "?" : seq.asText()) + " 讲,可用的讲次序号:" + bySeq.keySet());
        }
        return l;
    }

    /** 模型写的讲次序号:3、"3"、"L3"、3.0 都认;别的(1.5、"first")返回 null。 */
    static Integer seqOf(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble() == Math.rint(n.asDouble()) ? Integer.valueOf((int) n.asDouble()) : null;
        }
        String text = n.asText("").trim();
        return text.matches("[Ll]?\\s*\\d{1,4}") ? Integer.valueOf(text.replaceAll("\\D", "")) : null;
    }

    /** "分:秒" 或 "时:分:秒";分钟可以超过 59。 */
    static long parseClock(String s) {
        String[] parts = s == null ? new String[0] : s.trim().split(":");
        try {
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
            }
            if (parts.length == 3) {
                return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2])) * 1000;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面统一报错
        }
        throw new IllegalArgumentException("时间要写成 分:秒,比如 12:30;收到的是:" + s);
    }

    private interface Body {
        String run(JsonNode args) throws Exception;
    }

    private static AgentTool tool(String name, String description, String schema, Body body) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String parametersSchema() {
                return schema;
            }

            @Override
            public String run(JsonNode args) throws Exception {
                String out = body.run(args);
                // 太长的结果在这里截,并且明说后面还有:只留一个省略号的话,模型会以为这就是全部
                return out.length() <= MAX_RESULT_CHARS ? out
                        : out.substring(0, MAX_RESULT_CHARS - CUT_OFF.length()) + CUT_OFF;
            }
        };
    }
}
