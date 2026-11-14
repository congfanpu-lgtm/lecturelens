package com.agentflow.worker.lecture;

import com.agentflow.worker.agent.tool.AgentTool;
import com.agentflow.worker.media.MediaClient;
import com.agentflow.worker.rag.RagClient;
import com.agentflow.worker.rag.RagClient.SegmentHit;
import com.agentflow.worker.retry.NonRetryableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseToolsTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String KEY1 = "a".repeat(32);
    private static final String KEY3 = "c".repeat(32);
    private static final String LECTURES = "["
            + "{\"lectureId\":101,\"seq\":1,\"title\":\"LLM Agents Overview\",\"durationMs\":7101000,\"mediaKey\":\"" + KEY1 + "\","
            + "\"concepts\":[{\"name\":\"Pre-training\",\"startMs\":120000,\"summary\":\"next token prediction\"},"
            + "{\"name\":\"RLHF\",\"startMs\":4698000,\"summary\":\"reward from human preference\"}]},"
            + "{\"lectureId\":103,\"seq\":3,\"title\":\"Post-Training Verifiable Agents\",\"durationMs\":4648000,"
            + "\"mediaKey\":\"" + KEY3 + "\",\"concepts\":[]}]";

    private final RagClient rag = mock(RagClient.class);
    private final MediaClient media = mock(MediaClient.class);
    private final Map<String, AgentTool> tools = tools();

    private Map<String, AgentTool> tools() {
        return new CourseTools(rag, media).forCourse(7, json(LECTURES)).stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String call(String tool, String args) throws Exception {
        return tools.get(tool).run(json(args));
    }

    @Test
    void thereAreExactlyThreeTools() {
        assertEquals(java.util.Set.of("course_overview", "search_segments", "read_context"), tools.keySet());
    }

    @Test
    void overviewListsLecturesAndTheirConcepts() throws Exception {
        String all = call("course_overview", "{}");
        assertTrue(all.contains("L1 LLM Agents Overview (118:21)"), all);
        assertTrue(all.contains("Pre-training") && all.contains("RLHF"), all);
        assertTrue(all.contains("L3 Post-Training Verifiable Agents (77:28)"), all);
        String one = call("course_overview", "{\"lecture\":1}");
        assertTrue(one.contains("02:00 Pre-training:next token prediction"), one);
        assertTrue(one.contains("78:18 RLHF:reward from human preference"), one);
        assertFalse(one.contains("Post-Training"), one);
        assertThrows(IllegalArgumentException.class, () -> call("course_overview", "{\"lecture\":2}"));
    }

    @Test
    void searchFormatsHitsWithLectureNumberAndTimeRange() throws Exception {
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of(
                new SegmentHit("103:speech:750000", 103, 750000, 805000, "speech", "RL from human feedback aligns the model.", 0.9),
                new SegmentHit("101:slide:4698000", 101, 4698000, 4720000, "slide", "RLHF: maximize human preference", 0.5)));
        String out = call("search_segments", "{\"query\":\"rlhf\"}");
        assertTrue(out.contains("[L3 12:30-13:25 speech]\nRL from human feedback aligns the model."), out);
        assertTrue(out.contains("\n\n[L1 78:18-78:40 slide]\nRLHF: maximize human preference"), out);
        verify(rag).searchSegments(eq(7L), eq("rlhf"), anyInt(), isNull(), isNull(), isNull());
    }

    @Test
    void searchSaysSoWhenNothingIsFound() throws Exception {
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of());
        assertEquals("这门课里没有找到相关内容:quantum computing", call("search_segments", "{\"query\":\"quantum computing\"}"));
        assertThrows(IllegalArgumentException.class, () -> call("search_segments", "{\"query\":\"  \"}"));
    }

    @Test
    void toolsOnlyReachTheirOwnCourse() throws Exception {
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of());
        call("search_segments", "{\"query\":\"x\",\"courseId\":999,\"lectureId\":555,\"lectureIds\":[555],\"lectures\":[3]}");
        verify(rag).searchSegments(eq(7L), eq("x"), anyInt(), eq(List.of(103L)), isNull(), isNull());
    }

    @Test
    void anUnknownLectureNumberIsAnErrorTheModelCanSee() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> call("search_segments", "{\"query\":\"x\",\"lectures\":[99]}"));
        assertTrue(e.getMessage().contains("99") && e.getMessage().contains("1") && e.getMessage().contains("3"), e.getMessage());
        verify(rag, never()).searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> call("read_context", "{\"lecture\":99,\"from\":\"0:00\",\"to\":\"1:00\"}"));
    }

    @Test
    void searchWithATimeRangePassesItOn() throws Exception {
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of());
        call("search_segments", "{\"query\":\"x\",\"lectures\":[3],\"from\":\"10:00\",\"to\":\"12:30\"}");
        verify(rag).searchSegments(eq(7L), eq("x"), anyInt(), eq(List.of(103L)), eq(600000L), eq(750000L));
        call("search_segments", "{\"query\":\"y\",\"lectures\":[1],\"from\":\"1:02:30\"}");
        verify(rag).searchSegments(eq(7L), eq("y"), anyInt(), eq(List.of(101L)), eq(3750000L), isNull());
        assertThrows(IllegalArgumentException.class, () -> call("search_segments", "{\"query\":\"x\",\"from\":\"10:00\"}"));          // 没说哪一讲
        assertThrows(IllegalArgumentException.class, () -> call("search_segments", "{\"query\":\"x\",\"lectures\":[1,3],\"from\":\"10:00\"}"));
        assertThrows(IllegalArgumentException.class, () -> call("search_segments", "{\"query\":\"x\",\"lectures\":[3],\"from\":\"soon\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> call("search_segments", "{\"query\":\"x\",\"lectures\":[3],\"from\":\"12:30\",\"to\":\"10:00\"}"));
    }

    @Test
    void aSlideShownForLongerThanFiveMinutesIsPrintedAsARangeThatCanBeCited() throws Exception {
        // 幻灯片片段从这一张出现一直到下一张出现。一张挂了 12 分钟的幻灯片,模型照抄时间段的话第 1 层会嫌太长
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of(
                new SegmentHit("103:slide:2400000", 103, 2400000, 3120000, "slide", "Reward modeling from human preferences", 0.9)));
        String out = call("search_segments", "{\"query\":\"reward\"}");
        assertTrue(out.contains("[L3 40:00-45:00 slide]"), out);
        JsonNode frames = json("[{\"tsMs\":2400000,\"text\":\"Reward modeling from human preferences\"},{\"tsMs\":3120000,\"text\":\"Next\"}]");
        assertTrue(EvidenceChecker.check(new EvidenceChecker.Evidence(103, 3, 2400000, 2700000, "slide",
                "Reward modeling from human preferences"), 4648000, json("[]"), frames).ok(), "照抄这个时间段的证据要能过第 1 层");
    }

    @Test
    void aLectureNumberWrittenAsTextOrADecimalIsUnderstood() throws Exception {
        assertTrue(call("course_overview", "{\"lecture\":\"1\"}").contains("Pre-training"));
        assertTrue(call("course_overview", "{\"lecture\":1.0}").contains("Pre-training"));
        assertTrue(call("course_overview", "{\"lecture\":\"L1\"}").contains("Pre-training"));
        assertThrows(IllegalArgumentException.class, () -> call("course_overview", "{\"lecture\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> call("course_overview", "{\"lecture\":\"first\"}"));
    }

    @Test
    void aLectureFilterInTheWrongShapeDoesNotSilentlySearchTheWholeCourse() throws Exception {
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of());
        call("search_segments", "{\"query\":\"rlhf\",\"lectures\":3}");          // 一个数,不是数组
        call("search_segments", "{\"query\":\"rlhf\",\"lecture\":\"3\"}");      // 参数名写成了单数
        verify(rag, org.mockito.Mockito.times(2)).searchSegments(eq(7L), eq("rlhf"), anyInt(), eq(List.of(103L)), isNull(), isNull());
        assertThrows(IllegalArgumentException.class, () -> call("search_segments", "{\"query\":\"rlhf\",\"lectures\":\"all of them\"}"));
        verify(rag, never()).searchSegments(anyLong(), anyString(), anyInt(), isNull(), any(), any());
    }

    private void lecture3() {
        when(media.transcript(KEY3)).thenReturn(json("{\"utterances\":["
                + "{\"startMs\":745000,\"endMs\":750000,\"text\":\"before the window\"},"
                + "{\"startMs\":750000,\"endMs\":760000,\"text\":\"So what is reward modeling?\"},"
                + "{\"startMs\":760000,\"endMs\":770000,\"text\":\"You collect human preferences.\"},"
                + "{\"startMs\":960000,\"endMs\":970000,\"text\":\"much later\"}]}"));
        when(media.frameList(KEY3)).thenReturn(json("{\"frames\":["
                + "{\"tsMs\":740000,\"file\":\"frames/000740000.jpg\",\"text\":\"Reward modeling\\nfrom preferences\"},"
                + "{\"tsMs\":800000,\"file\":\"frames/000800000.jpg\",\"text\":\"Next slide\"}]}"));
    }

    @Test
    void readContextReturnsTheTranscriptOfTheWindowAndItsSlides() throws Exception {
        lecture3();
        String out = call("read_context", "{\"lecture\":3,\"from\":\"12:30\",\"to\":\"13:00\"}");
        assertTrue(out.contains("12:30 So what is reward modeling?\n12:40 You collect human preferences."), out);
        assertFalse(out.contains("before the window") || out.contains("much later"), out);
        // 幻灯片和检索结果一样标成 [L讲次 时间段 slide]:模型照抄来源和时间段时不会把幻灯片上的字当成语音
        assertTrue(out.contains("\n\n[L3 12:20-13:20 slide]\nReward modeling\nfrom preferences"), out);
        assertTrue(out.startsWith("[L3 12:30-13:00 speech]"), out);
        assertFalse(out.contains("Next slide"), out);
    }

    @Test
    void aCutOffResultSaysSo() throws Exception {
        // 结果超过上限时,末尾要明说"后面还有":只有一个省略号的话,模型会以为这就是全部
        StringBuilder concepts = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            concepts.append(i == 0 ? "" : ",").append("{\"name\":\"Concept ").append(i).append("\",\"startMs\":").append(i * 60000)
                    .append(",\"summary\":\"").append("a long summary of this concept ".repeat(10)).append("\"}");
        }
        String lectures = "[{\"lectureId\":101,\"seq\":1,\"title\":\"T\",\"durationMs\":7101000,\"mediaKey\":\"" + KEY1
                + "\",\"concepts\":[" + concepts + "]}]";
        String out = new CourseTools(rag, media).forCourse(7, json(lectures)).stream()
                .filter(t -> t.name().equals("course_overview")).findFirst().orElseThrow().run(json("{\"lecture\":1}"));
        assertTrue(out.length() <= CourseTools.MAX_RESULT_CHARS, "长度 " + out.length());
        assertTrue(out.endsWith(CourseTools.CUT_OFF), out.substring(out.length() - 80));
        assertTrue(out.contains("Concept 0") && !out.contains("Concept 29"), "前面的还在,后面的被截掉了");
        assertFalse(call("course_overview", "{\"lecture\":1}").contains(CourseTools.CUT_OFF), "没超过上限的不加这句话");
    }

    @Test
    void readContextIsCappedAtThreeMinutes() throws Exception {
        lecture3();
        String out = call("read_context", "{\"lecture\":3,\"from\":\"12:30\",\"to\":\"30:00\"}");
        assertTrue(out.contains("You collect human preferences."), out);
        assertFalse(out.contains("much later"), "窗口最长 3 分钟:12:30 到 15:30,16:00 的那句在外面");
    }

    @Test
    void readContextWorksForALectureWithoutSlides() throws Exception {
        lecture3();
        when(media.frameList(KEY3)).thenThrow(new NonRetryableException("media 404: not found: frames"));
        String out = call("read_context", "{\"lecture\":3,\"from\":\"12:30\",\"to\":\"13:00\"}");
        assertTrue(out.contains("So what is reward modeling?") && !out.contains("slide]"), out);
    }
}
