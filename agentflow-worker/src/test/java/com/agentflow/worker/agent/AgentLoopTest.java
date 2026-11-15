package com.agentflow.worker.agent;

import com.agentflow.common.llm.*;
import com.agentflow.common.trace.TraceStage;
import com.agentflow.worker.agent.tool.AgentTool;
import com.agentflow.worker.llm.LlmGateway;
import com.agentflow.worker.llm.LlmModelProperties;
import com.agentflow.worker.trace.TraceEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopTest {

    /** 可计数的假工具:返回固定文本或抛出指定异常。 */
    static class FakeTool implements AgentTool {
        int calls;
        String output = "标题: Kafka\nURL: https://en.wikipedia.org/wiki/Apache_Kafka\n摘要: log";
        RuntimeException fail;

        public String name() { return "search"; }
        public String description() { return "d"; }
        public String parametersSchema() { return "{\"type\":\"object\"}"; }
        public String run(JsonNode args) {
            calls++;
            if (fail != null) throw fail;
            return output;
        }
    }

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
    private final TraceEmitter trace = mock(TraceEmitter.class);
    private final FakeTool tool = new FakeTool();
    private final AgentRun run = new AgentRun(1L, 2L, "uuid-1", "what is kafka", "m");

    private AgentLoop loop() {
        LlmModelProperties props = new LlmModelProperties();
        LlmModelProperties.Model m = new LlmModelProperties.Model();
        m.setName("m");
        m.setPricePer1kTokens(1.0);
        props.setModels(List.of(m));
        when(checkpoint.load(anyString())).thenReturn(List.of());
        return new AgentLoop(gateway, checkpoint, trace, props);
    }

    /** 循环只认调用方给的 Profile:这里是一个工具、宽松模式(工具出错给模型看)。 */
    private void go(AgentLoop loop) {
        loop.converse(run, new AgentLoop.Profile("p", List.of(tool), 1600, 2000, false),
                List.of(new ChatMessage("user", run.question())));
    }

    private static ChatResponse call(String id, String args) {
        ChatResponse r = new ChatResponse("思路 " + id, new TokenUsage(100, 10), "m");
        r.setToolCalls(List.of(new ToolCall(id, "search", args)));
        return r;
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(content, new TokenUsage(100, 10), "m");
    }

    private List<ChatRequest> requests(int n) {
        ArgumentCaptor<ChatRequest> c = ArgumentCaptor.forClass(ChatRequest.class);
        verify(gateway, times(n)).chat(c.capture());
        return c.getAllValues();
    }

    private List<String> detailsOf(TraceStage stage) {
        ArgumentCaptor<String> d = ArgumentCaptor.forClass(String.class);
        verify(trace, atLeast(0)).emit(anyString(), anyLong(), anyLong(), eq(stage), any(), d.capture());
        return d.getAllValues();
    }

    @Test
    void toolErrorIsReturnedToModelInsteadOfThrowing() {
        AgentLoop loop = loop();
        tool.fail = new IllegalStateException("boom");
        when(gateway.chat(any())).thenReturn(call("c1", "{}"), answer("{\"answer\":\"a\",\"sources\":[]}"));

        go(loop);

        String toolMsg = requests(2).get(1).getMessages().get(3).getContent();
        assertTrue(toolMsg.startsWith("ERROR: "));
        assertTrue(toolMsg.contains("boom"));
    }

    @Test
    void malformedArgumentsReturnedAsError() {
        AgentLoop loop = loop();
        when(gateway.chat(any())).thenReturn(call("c1", "{not json"), answer("{\"answer\":\"a\",\"sources\":[]}"));

        go(loop);

        assertEquals(0, tool.calls);
        assertTrue(requests(2).get(1).getMessages().get(3).getContent().startsWith("ERROR: "));
    }

    @Test
    void unknownToolReturnedAsError() {
        AgentLoop loop = loop();
        ChatResponse bad = new ChatResponse("t", new TokenUsage(1, 1), "m");
        bad.setToolCalls(List.of(new ToolCall("c1", "rm_rf", "{}")));
        when(gateway.chat(any())).thenReturn(bad, answer("{\"answer\":\"a\",\"sources\":[]}"));

        go(loop);

        assertTrue(requests(2).get(1).getMessages().get(3).getContent().contains("未知工具 rm_rf"));
    }

    @Test
    void repeatedIdenticalCallIsServedFromCache() {
        AgentLoop loop = loop();
        when(gateway.chat(any())).thenReturn(call("c1", "{\"q\":\"k\"}"), call("c2", "{\"q\":\"k\"}"),
                answer("{\"answer\":\"a\",\"sources\":[]}"));

        go(loop);

        assertEquals(1, tool.calls);
    }

}
