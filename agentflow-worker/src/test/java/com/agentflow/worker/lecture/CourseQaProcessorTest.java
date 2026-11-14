package com.agentflow.worker.lecture;

import com.agentflow.common.llm.ChatMessage;
import com.agentflow.common.llm.ChatRequest;
import com.agentflow.common.llm.ChatResponse;
import com.agentflow.common.llm.TokenUsage;
import com.agentflow.common.llm.ToolCall;
import com.agentflow.common.llm.ToolSpec;
import com.agentflow.common.mq.SubtaskMessage;
import com.agentflow.worker.agent.AgentCheckpoint;
import com.agentflow.worker.agent.AgentLoop;
import com.agentflow.worker.agent.Prompts;
import com.agentflow.worker.llm.LlmGateway;
import com.agentflow.worker.llm.LlmModelProperties;
import com.agentflow.worker.media.MediaClient;
import com.agentflow.worker.rag.RagClient;
import com.agentflow.worker.rag.RagClient.SegmentHit;
import com.agentflow.worker.trace.TraceEmitter;
import com.agentflow.worker.retry.NonRetryableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseQaProcessorTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String LECTURES = "\"lectures\":["
            + "{\"lectureId\":101,\"seq\":1,\"title\":\"LLM Agents Overview\",\"durationMs\":7101000,\"mediaKey\":\"" + "a".repeat(32) + "\",\"concepts\":[]},"
            + "{\"lectureId\":103,\"seq\":3,\"title\":\"Post-Training\",\"durationMs\":4648000,\"mediaKey\":\"" + "c".repeat(32) + "\",\"concepts\":[]}]";
    private static final String QUESTION = "what is reward modeling";

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
    private final RagClient rag = mock(RagClient.class);
    private final MediaClient media = mock(MediaClient.class);
    private CourseQaProcessor processor;

    @BeforeEach
    void setUp() {
        LlmModelProperties props = new LlmModelProperties();
        LlmModelProperties.Model m = new LlmModelProperties.Model();
        m.setName("m");
        props.setModels(List.of(m));
        when(checkpoint.load(anyString())).thenReturn(List.of());
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of(
                new SegmentHit("103:speech:750000", 103, 750000, 805000, "speech", "You collect human preferences to train a reward model.", 0.9)));
        AgentLoop loop = new AgentLoop(gateway, List.of(), checkpoint, mock(TraceEmitter.class), props);
        processor = new CourseQaProcessor(loop, new CourseTools(rag, media));
    }

    private static ChatResponse search(String query) {
        ChatResponse r = new ChatResponse("先检索", new TokenUsage(100, 10), "m");
        r.setToolCalls(List.of(new ToolCall("c1", "search_segments", "{\"query\":\"" + query + "\"}")));
        return r;
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(content, new TokenUsage(100, 10), "m");
    }

    private JsonNode run(String extraInput, ChatResponse... script) throws Exception {
        if (script.length == 1) {
            when(gateway.chat(any())).thenReturn(script[0]);
        } else {
            when(gateway.chat(any())).thenReturn(script[0], java.util.Arrays.copyOfRange(script, 1, script.length));
        }
        String input = "{\"courseId\":7,\"question\":\"" + QUESTION + "\"," + LECTURES + extraInput + "}";
        return OM.readTree(processor.process(new SubtaskMessage(1L, 2L, "uuid-qa", "QA_AGENT", 0, input)));
    }

    private List<ChatRequest> requests(int n) {
        ArgumentCaptor<ChatRequest> c = ArgumentCaptor.forClass(ChatRequest.class);
        verify(gateway, times(n)).chat(c.capture());
        return c.getAllValues();
    }

    private static final String GOOD = "{\"notFound\":false,\"claims\":[{\"text\":\"Reward models are trained on human preferences.\","
            + "\"evidence\":[{\"lecture\":3,\"at\":\"12:30-13:25\",\"source\":\"speech\","
            + "\"quote\":\"You collect human preferences to train a reward model.\"}]}]}";

    @Test
    void turnsTheModelsAnswerIntoClaimsWithEvidenceInMilliseconds() throws Exception {
        JsonNode out = run("", search("reward modeling"), answer(GOOD));
        assertEquals("QA_AGENT", processor.type());
        assertEquals(QUESTION, out.get("question").asText());
        assertFalse(out.get("notFound").asBoolean());
        JsonNode claim = out.get("claims").get(0);
        assertEquals("Reward models are trained on human preferences.", claim.get("text").asText());
        JsonNode e = claim.get("evidence").get(0);
        assertEquals(103, e.get("lectureId").asLong());
        assertEquals(3, e.get("seq").asInt());
        assertEquals(750000, e.get("startMs").asLong());
        assertEquals(805000, e.get("endMs").asLong());
        assertEquals("speech", e.get("source").asText());
        assertEquals("You collect human preferences to train a reward model.", e.get("quote").asText());
        assertEquals(2, out.get("steps").asInt());
        assertTrue(out.has("usage"));
    }

    @Test
    void theAgentGetsTheCourseToolsAndItsOwnPrompt() throws Exception {
        run("", search("reward modeling"), answer(GOOD));
        ChatRequest first = requests(2).get(0);
        assertTrue(first.getMessages().get(0).getContent().startsWith(Prompts.COURSE_QA_TAG));
        assertEquals(List.of("course_overview", "search_segments", "read_context"),
                first.getTools().stream().map(ToolSpec::name).toList());
        assertEquals(CourseQaProcessor.MAX_TOKENS, first.getMaxTokens());
        assertEquals(QUESTION, first.getMessages().get(first.getMessages().size() - 1).getContent());
    }

    @Test
    void reportsWhichToolsWereCalled() throws Exception {
        JsonNode calls = run("", search("reward modeling"), answer(GOOD)).get("toolCalls");
        assertEquals(1, calls.size());
        assertEquals("search_segments", calls.get(0).get("tool").asText());
        assertEquals("{\"query\":\"reward modeling\"}", calls.get(0).get("args").asText());
    }

    @Test
    void saysNotFoundInsteadOfAnswering() throws Exception {
        JsonNode out = run("", search("quantum"), answer("{\"notFound\":true,\"claims\":[{\"text\":\"from my own knowledge…\",\"evidence\":[]}]}"));
        assertTrue(out.get("notFound").asBoolean());
        assertEquals(0, out.get("claims").size(), "说了找不到,就不能再夹带结论");
    }

    @Test
    void evidenceWithAnUnknownLectureOrBadTimeIsPassedOnWithTheReason() throws Exception {
        JsonNode out = run("", answer("{\"claims\":[{\"text\":\"A claim.\",\"evidence\":["
                + "{\"lecture\":99,\"at\":\"12:30-13:25\",\"source\":\"speech\",\"quote\":\"q one is long enough\"},"
                + "{\"lecture\":3,\"at\":\"around noon\",\"source\":\"speech\",\"quote\":\"q two is long enough\"},"
                + "{\"lecture\":3,\"at\":\"12:30\",\"source\":\"slide\",\"quote\":\"q three is long enough\"}]}]}"));
        JsonNode ev = out.get("claims").get(0).get("evidence");
        assertEquals("A claim.", out.get("claims").get(0).get("text").asText());   // 结论留着,由核验决定去留
        // 看不懂的证据不悄悄丢掉:否则核验只能说"没有给出证据",补一轮时模型得到的是错的原因
        assertEquals(3, ev.size());
        assertTrue(ev.get(0).get("invalid").asText().contains("99"), ev.get(0).toString());
        assertTrue(ev.get(1).get("invalid").asText().contains("around noon"), ev.get(1).toString());
        assertFalse(ev.get(2).has("invalid"));
        assertEquals(750000, ev.get(2).get("startMs").asLong());   // 只写了一个时间点:当作那一刻
        assertEquals(750000, ev.get(2).get("endMs").asLong());
    }

    @Test
    void smallFormatSlipsInEvidenceAreUnderstood() throws Exception {
        String rest = ",\"source\":\"speech\",\"quote\":\"You collect human preferences to train a reward model.\"}";
        JsonNode out = run("", answer("{\"claims\":["
                + "{\"text\":\"lecture as text\",\"evidence\":[{\"lecture\":\"3\",\"at\":\"12:30-13:25\"" + rest + "]},"
                + "{\"text\":\"lecture with its letter\",\"evidence\":[{\"lecture\":\"L3\",\"at\":\"12:30 \u2013 13:25\"" + rest + "]},"
                + "{\"text\":\"the whole label copied\",\"evidence\":[{\"lecture\":3.0,\"at\":\"[L3 12:30-13:25 speech]\"" + rest + "]},"
                + "{\"text\":\"one piece of evidence, not a list\",\"evidence\":{\"lecture\":3,\"at\":\"[12:30-13:25]\"" + rest + "}]}"));
        assertEquals(4, out.get("claims").size());
        for (JsonNode claim : out.get("claims")) {
            JsonNode e = claim.get("evidence").get(0);
            assertFalse(e.has("invalid"), claim.toString());
            assertEquals(103, e.get("lectureId").asLong(), claim.toString());
            assertEquals(750000, e.get("startMs").asLong(), claim.toString());
            assertEquals(805000, e.get("endMs").asLong(), claim.toString());
        }
    }

    @Test
    void claimsWrittenAsBareTextAreKeptWithoutEvidence() throws Exception {
        JsonNode out = run("", answer("{\"claims\":[\"Reward models are trained on preferences.\"]}"));
        assertFalse(out.get("notFound").asBoolean());
        assertEquals("Reward models are trained on preferences.", out.get("claims").get(0).get("text").asText());
        assertEquals(0, out.get("claims").get(0).get("evidence").size());
    }

    @Test
    void jsonInTheWrongShapeIsTreatedLikeFreeText() throws Exception {
        // 是 JSON、但没有 claims:不能变成"课里没讲"。和不是 JSON 的回答一样,整段当一条没有证据的结论交给核验
        JsonNode out = run("", answer("{\"answer\":\"Reward modeling is about preferences.\"}"));
        assertFalse(out.get("notFound").asBoolean());
        assertEquals(1, out.get("claims").size());
        assertTrue(out.get("claims").get(0).get("text").asText().contains("Reward modeling is about preferences."));
        assertEquals(0, out.get("claims").get(0).get("evidence").size());
    }

    @Test
    void anExplicitlyEmptyListOfClaimsMeansNotFound() throws Exception {
        assertTrue(run("", answer("{\"claims\":[]}")).get("notFound").asBoolean());
    }

    @Test
    void aSearchOutageFailsTheStepInsteadOfLookingLikeNotFound() {
        when(rag.searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("rag 503"));
        assertThrows(IllegalStateException.class,
                () -> run("", search("reward modeling"), answer("{\"notFound\":true,\"claims\":[]}")));
    }

    @Test
    void aBadToolArgumentGoesBackToTheModelNotToTheRetryLadder() throws Exception {
        JsonNode out = run("", search("  "), answer(GOOD));   // query 是空的:模型自己写错了,让它看到错误再来
        assertEquals(1, out.get("claims").size());
        String toolResult = requests(2).get(1).getMessages().get(3).getContent();
        assertTrue(toolResult.startsWith("ERROR"), toolResult);
    }

    @Test
    void anAnswerThatIsNotJsonBecomesOneClaimWithoutEvidence() throws Exception {
        JsonNode out = run("", answer("Reward modeling is about preferences, I think."));
        assertFalse(out.get("notFound").asBoolean());
        assertEquals(1, out.get("claims").size());
        assertEquals("Reward modeling is about preferences, I think.", out.get("claims").get(0).get("text").asText());
        assertEquals(0, out.get("claims").get(0).get("evidence").size());
    }

    @Test
    void anEmptyAnswerIsAFailureToRetry() {
        assertThrows(IllegalStateException.class, () -> run("", answer("  ")));
    }

    @Test
    void keepsAtMostTwelveClaimsAndFourPiecesOfEvidenceEach() throws Exception {
        StringBuilder sb = new StringBuilder("{\"claims\":[");
        String ev = "{\"lecture\":3,\"at\":\"12:30-13:25\",\"source\":\"speech\",\"quote\":\"a quote long enough\"}";
        for (int i = 0; i < 15; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"text\":\"c").append(i).append("\",\"evidence\":[")
                    .append(String.join(",", ev, ev, ev, ev, ev, ev)).append("]}");
        }
        JsonNode claims = run("", answer(sb.append("]}").toString())).get("claims");
        assertEquals(12, claims.size());
        assertEquals(4, claims.get(0).get("evidence").size());
    }

    @Test
    void historyIsSentBeforeTheQuestion() throws Exception {
        run(",\"history\":[{\"question\":\"what is SFT\",\"answer\":\"Supervised finetuning on desired answers.\"}]", answer(GOOD));
        List<ChatMessage> sent = requests(1).get(0).getMessages();
        assertEquals(List.of("system", "user", "assistant", "user"), sent.stream().map(ChatMessage::getRole).toList());
        assertEquals("what is SFT", sent.get(1).getContent());
        assertEquals("Supervised finetuning on desired answers.", sent.get(2).getContent());
        assertEquals(QUESTION, sent.get(3).getContent());
    }

    @Test
    void theSecondRoundShowsTheModelWhatWasRejectedAndWhy() throws Exception {
        run(",\"feedback\":[{\"text\":\"Reward models never fail.\",\"reason\":\"引用的原文在 L3 12:30-13:25 里找不到\"}]", answer(GOOD));
        List<ChatMessage> sent = requests(1).get(0).getMessages();
        String last = sent.get(sent.size() - 1).getContent();
        assertEquals("user", sent.get(sent.size() - 1).getRole());
        assertEquals(QUESTION, sent.get(sent.size() - 2).getContent());
        assertTrue(last.contains("Reward models never fail.") && last.contains("引用的原文在 L3 12:30-13:25 里找不到"), last);
    }

    @Test
    void theSecondRoundIsToldWhatWasKeptSoItOnlyRedoesTheRest() throws Exception {
        run(",\"feedback\":[{\"text\":\"Reward models never fail.\",\"reason\":\"找不到\"}],"
                + "\"kept\":[{\"text\":\"Reward models are trained on human preferences.\"}]", answer(GOOD));
        List<ChatMessage> sent = requests(1).get(0).getMessages();
        String last = sent.get(sent.size() - 1).getContent();
        assertTrue(last.contains("Reward models are trained on human preferences."), last);
        assertTrue(last.indexOf("Reward models never fail.") < last.indexOf("Reward models are trained on human preferences."), last);
        assertTrue(last.contains("不要重复"), last);
    }

    // ---- 评测用的另外两种跑法(计划 4):固定检索流水线、长上下文 ----

    @Test
    void ragModeSearchesOnceAndAnswersInOneCall() throws Exception {
        JsonNode out = run(",\"mode\":\"rag\"", answer(GOOD));
        assertEquals(1, out.get("claims").size());
        assertEquals(750000, out.get("claims").get(0).get("evidence").get(0).get("startMs").asLong());
        // 取 8 个片段(agent 自己检索时一次是 5 个):这条基线只有一次检索的机会,不能再在片段数上吃亏
        verify(rag, times(1)).searchSegments(eq(7L), eq(QUESTION), eq(8), isNull(), isNull(), isNull());
        ChatRequest sent = requests(1).get(0);                         // 只有一次模型调用,不给工具
        assertTrue(sent.getTools() == null || sent.getTools().isEmpty());
        assertEquals(List.of("system", "user", "user"), sent.getMessages().stream().map(ChatMessage::getRole).toList());
        assertTrue(sent.getMessages().get(0).getContent().startsWith(Prompts.COURSE_QA_TAG));
        assertTrue(sent.getMessages().get(1).getContent().contains("[L3 12:30-13:25 speech]\nYou collect human preferences to train a reward model."),
                sent.getMessages().get(1).getContent());
        // 问题后面再说一遍输出格式:课程内容很长时,模型会忘了系统提示里的格式要求,答成一段话(实测:长上下文那一路)
        String last = sent.getMessages().get(2).getContent();
        assertTrue(last.startsWith(QUESTION) && last.contains("JSON") && last.contains("\"claims\""), last);
        assertEquals(0, out.get("toolCalls").size());
    }

    private void transcripts() throws Exception {
        when(media.transcript("a".repeat(32))).thenReturn(OM.readTree("{\"utterances\":["
                + "{\"startMs\":0,\"endMs\":20000,\"text\":\"Welcome to the first lecture.\"},"
                + "{\"startMs\":20000,\"endMs\":50000,\"text\":\"Today we talk about agents.\"},"
                + "{\"startMs\":50000,\"endMs\":80000,\"text\":\"An agent uses tools.\"}]}"));
        when(media.transcript("c".repeat(32))).thenReturn(OM.readTree("{\"utterances\":["
                + "{\"startMs\":750000,\"endMs\":760000,\"text\":\"You collect human preferences to train a reward model.\"}]}"));
    }

    @Test
    void longModePutsEveryTranscriptInOneCall() throws Exception {
        transcripts();
        JsonNode out = run(",\"mode\":\"long\"", answer(GOOD));
        assertEquals(1, out.get("claims").size());
        verify(rag, never()).searchSegments(anyLong(), anyString(), anyInt(), any(), any(), any());
        ChatRequest sent = requests(1).get(0);
        assertTrue(sent.getTools() == null || sent.getTools().isEmpty());
        String course = sent.getMessages().get(1).getContent();
        // 和检索结果同一种标记,一段不超过一分钟:模型引用时照抄讲次和时间段
        assertTrue(course.contains("[L1 00:00-00:50 speech]\nWelcome to the first lecture. Today we talk about agents."), course);
        assertTrue(course.contains("[L1 00:50-01:20 speech]\nAn agent uses tools."), course);
        assertTrue(course.contains("[L3 12:30-12:40 speech]\nYou collect human preferences to train a reward model."), course);
        assertTrue(sent.getMessages().get(2).getContent().startsWith(QUESTION + "\n"));
        assertTrue(sent.getMessages().get(2).getContent().contains("\"claims\""));
    }

    @Test
    void longContextRefusesWhenTheCourseDoesNotFit() throws Exception {
        transcripts();
        when(media.transcript("c".repeat(32))).thenReturn(OM.readTree("{\"utterances\":[{\"startMs\":0,\"endMs\":9000,\"text\":\""
                + "x".repeat(CourseTools.LONG_CONTEXT_MAX_CHARS) + "\"}]}"));
        NonRetryableException e = assertThrows(NonRetryableException.class, () -> run(",\"mode\":\"long\"", answer(GOOD)));
        assertTrue(e.getMessage().contains(String.valueOf(CourseTools.LONG_CONTEXT_MAX_CHARS)), e.getMessage());
        verify(gateway, never()).chat(any());                       // 不能悄悄截断成"只看了前几讲"
    }
}
