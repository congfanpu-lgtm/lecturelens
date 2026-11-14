package com.agentflow.worker.lecture;

import com.agentflow.common.llm.ChatRequest;
import com.agentflow.common.llm.ChatResponse;
import com.agentflow.common.llm.TokenUsage;
import com.agentflow.worker.agent.Prompts;
import com.agentflow.worker.llm.LlmGateway;
import com.agentflow.worker.llm.LlmModelProperties;
import com.agentflow.worker.media.MediaClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LecConceptsProcessorTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String INPUT = "{\"lectureId\":7,\"mediaKey\":\"" + KEY + "\",\"title\":\"LLM Agents Overview\"}";
    private final MediaClient media = mock(MediaClient.class);
    private final LlmGateway gateway = mock(LlmGateway.class);

    /** 30 条 utterance,每条 20 秒、以句号结尾 → 每 3 条(60 秒)一个片段,片段 i 从 i*60 秒开始(20 秒的一条放不进 10 秒的重叠)。 */
    @BeforeEach
    void transcript() throws Exception {
        ArrayNode arr = OM.createArrayNode();
        for (int i = 0; i < 30; i++) {
            arr.addObject().put("startMs", i * 20_000L).put("endMs", (i + 1) * 20_000L).put("text", "Point " + i + ".");
        }
        when(media.transcript(KEY)).thenReturn(OM.createObjectNode().put("language", "en").set("utterances", arr));
    }

    private LecConceptsProcessor processor() {
        LlmModelProperties props = new LlmModelProperties();
        LlmModelProperties.Model m = new LlmModelProperties.Model();
        m.setName("m");
        props.setModels(List.of(m));
        return new LecConceptsProcessor(media, gateway, props);
    }

    private JsonNode run(String llmOutput) throws Exception {
        when(gateway.chat(any())).thenReturn(new ChatResponse(llmOutput, new TokenUsage(5, 5), "m"));
        return OM.readTree(processor().process(INPUT));
    }

    @Test
    void theModelSeesNumberedSegmentsWithTheirTimes() throws Exception {
        run("{\"concepts\":[{\"name\":\"Pre-training\",\"segment\":0,\"summary\":\"s\",\"quote\":\"Point 0. Point 1.\"}]}");
        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(gateway).chat(req.capture());
        assertTrue(req.getValue().getMessages().get(0).getContent().startsWith(Prompts.CONCEPTS_TAG));
        String user = req.getValue().getMessages().get(req.getValue().getMessages().size() - 1).getContent();
        assertTrue(user.startsWith("标题:LLM Agents Overview\n[0] 00:00 Point 0. Point 1. Point 2."), user.substring(0, 80));
        assertTrue(user.contains("\n[1] 01:00 Point 3. Point 4. Point 5.\n[2] 02:00 "), "片段按编号和起始时间逐行列出");
        assertEquals("LEC_CONCEPTS", processor().type());
    }

    @Test
    void segmentNumbersBecomeStartTimesInOrder() throws Exception {
        JsonNode out = run("{\"concepts\":[{\"name\":\"RLHF\",\"segment\":3,\"summary\":\"reward from people\",\"quote\":\"Point 9. Point 10.\"},"
                + "{\"name\":\"Pre-training\",\"segment\":0,\"summary\":\"next token prediction\",\"quote\":\"point 0.  POINT 1.\"}]}");
        JsonNode c = out.get("concepts");
        assertEquals(2, c.size());
        assertEquals("Pre-training", c.get(0).get("name").asText());
        assertEquals(0, c.get(0).get("startMs").asLong());
        assertEquals("RLHF", c.get(1).get("name").asText());
        assertEquals(180_000, c.get(1).get("startMs").asLong());
        assertEquals("reward from people", c.get(1).get("summary").asText());
        assertEquals("Point 9. Point 10.", c.get(1).get("quote").asText());
        assertEquals("point 0.  POINT 1.", c.get(0).get("quote").asText(), "大小写和空白不同不算编造,原样保留模型写的");
        assertTrue(out.has("usage"));
    }

    @Test
    void dropsConceptsThatPointOutsideTheLecture() throws Exception {
        JsonNode c = run("{\"concepts\":[{\"name\":\"Good\",\"segment\":1,\"summary\":\"s\",\"quote\":\"Point 3. Point 4.\"},"
                + "{\"name\":\"Too far\",\"segment\":999,\"summary\":\"s\"},{\"name\":\"Negative\",\"segment\":-1,\"summary\":\"s\"},"
                + "{\"name\":\"  \",\"segment\":2,\"summary\":\"s\"},{\"name\":\"No number\",\"summary\":\"s\"},"
                + "{\"name\":\"Good\",\"segment\":5,\"summary\":\"again, later\",\"quote\":\"Point 15. Point 16.\"},"
                + "{\"name\":\"Text number\",\"segment\":\"2\",\"summary\":\"s\",\"quote\":\"Point 6. Point 7.\"}]}")
                .get("concepts");
        assertEquals(1, c.size());
        assertEquals("Good", c.get(0).get("name").asText());
        assertEquals(60_000, c.get(0).get("startMs").asLong());
    }

    @Test
    void failsWhenNothingUsableComesBack() {
        assertThrows(IllegalStateException.class, () -> run("{\"concepts\":[]}"));
        assertThrows(IllegalStateException.class, () -> run("{\"concepts\":[{\"name\":\"x\",\"segment\":999}]}"));
        assertThrows(IllegalStateException.class, () -> run("I could not find any concepts, sorry."));
    }

    @Test
    void asksForEnoughTokensForThirtyConceptsWithQuotes() throws Exception {
        // 30 个知识点,每个带名字、摘要和一句原文,大约 100 到 150 token;上限给 2000 的话真实模型的输出会被截断,这一步就失败了
        run("{\"concepts\":[{\"name\":\"A\",\"segment\":0,\"summary\":\"s\",\"quote\":\"Point 0. Point 1.\"}]}");
        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(gateway).chat(req.capture());
        assertTrue(req.getValue().getMaxTokens() >= 6000, "输出上限 " + req.getValue().getMaxTokens());
    }

    @Test
    void keepsAtMostThirty() throws Exception {
        StringBuilder sb = new StringBuilder("{\"concepts\":[");
        for (int i = 0; i < 40; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"name\":\"c").append(i)
                    .append("\",\"segment\":0,\"summary\":\"s\",\"quote\":\"Point 0. Point 1.\"}");
        }
        assertEquals(30, run(sb.append("]}").toString()).get("concepts").size());
    }

    @Test
    void dropsAConceptWhoseQuoteIsNotInItsSegment() throws Exception {
        // 时间点是真的(片段 0 存在),但引的原话其实在片段 3 里:这个知识点不是在这里讲的
        JsonNode c = run("{\"concepts\":[{\"name\":\"Misplaced\",\"segment\":0,\"summary\":\"s\",\"quote\":\"Point 9. Point 10.\"},"
                + "{\"name\":\"No quote\",\"segment\":1,\"summary\":\"s\"},"
                + "{\"name\":\"Too short\",\"segment\":1,\"summary\":\"s\",\"quote\":\"Point 3.\"},"
                + "{\"name\":\"Made up\",\"segment\":2,\"summary\":\"s\",\"quote\":\"something nobody said in this lecture\"},"
                + "{\"name\":\"Real\",\"segment\":2,\"summary\":\"s\",\"quote\":\"Point 6. Point 7.\"}]}").get("concepts");
        assertEquals(1, c.size());
        assertEquals("Real", c.get(0).get("name").asText());
        assertThrows(IllegalStateException.class, () -> run("{\"concepts\":[{\"name\":\"Misplaced\",\"segment\":0,"
                + "\"summary\":\"s\",\"quote\":\"Point 9. Point 10.\"}]}"));
    }
}
