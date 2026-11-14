package com.agentflow.worker.agent;

import com.agentflow.common.llm.*;
import com.agentflow.worker.agent.AgentLoop.Outcome;
import com.agentflow.worker.agent.AgentLoop.Profile;
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

/** ReAct 循环本身(续跑、调工具、步数上限),和研究 agent 的提示词、工具、引用核对无关。 */
class AgentLoopCoreTest {

    static class FakeTool implements AgentTool {
        final String name;
        int calls;

        FakeTool(String name) {
            this.name = name;
        }

        public String name() { return name; }
        public String description() { return "d"; }
        public String parametersSchema() { return "{\"type\":\"object\"}"; }
        public String run(JsonNode args) {
            calls++;
            return name + " says " + args;
        }
    }

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
    private final FakeTool research = new FakeTool("web_search");   // 研究 agent 的工具,循环里注册着
    private final FakeTool echo = new FakeTool("echo");             // 这次运行自己的工具
    private final AgentRun run = new AgentRun(1L, 2L, "uuid-qa", "what is rlhf", "m");
    private final Profile profile = new Profile("[course-qa] 你在回答一门课的问题", List.of(echo), 4000, 12, false);
    private final List<ChatMessage> seed = List.of(new ChatMessage("user", "what is rlhf"));

    private AgentLoop loop(List<AgentStep> archived) {
        LlmModelProperties props = new LlmModelProperties();
        LlmModelProperties.Model m = new LlmModelProperties.Model();
        m.setName("m");
        m.setPricePer1kTokens(1.0);
        props.setModels(List.of(m));
        when(checkpoint.load(anyString())).thenReturn(archived);
        return new AgentLoop(gateway, List.of(research), checkpoint, mock(TraceEmitter.class), props);
    }

    private static ChatResponse call(String id, String tool, String args) {
        ChatResponse r = new ChatResponse("思路 " + id, new TokenUsage(100, 10), "m");
        r.setToolCalls(List.of(new ToolCall(id, tool, args)));
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

    @Test
    void usesTheToolsAndPromptOfTheProfile() {
        AgentLoop loop = loop(List.of());
        when(gateway.chat(any())).thenReturn(call("c1", "echo", "{\"x\":1}"),
                call("c2", "web_search", "{\"q\":\"rlhf\"}"), answer("done"));
        Outcome out = loop.converse(run, profile, seed);

        assertEquals("done", out.content());
        assertEquals(3, out.stepCount());
        assertEquals(2, out.history().size());
        assertEquals(3, out.usage().getCalls());
        assertEquals(1, echo.calls);
        assertEquals("echo says {\"…", out.history().get(0).toolResults().get(0).getContent(),
                "工具结果按这次运行的上限(12 个字符)截断");
        assertEquals(0, research.calls, "研究 agent 的工具不在这次运行的工具表里,不能被调用");
        assertTrue(out.history().get(1).toolResults().get(0).getContent().startsWith("ERROR: 未知工具 web_search"));

        ChatRequest first = requests(3).get(0);
        assertEquals("[course-qa] 你在回答一门课的问题", first.getMessages().get(0).getContent());
        assertEquals("what is rlhf", first.getMessages().get(1).getContent());
        assertEquals(List.of("echo"), first.getTools().stream().map(ToolSpec::name).toList());
        assertEquals(4000, first.getMaxTokens());
        verify(checkpoint, times(2)).append(eq("uuid-qa"), any());
    }

    @Test
    void resumesACourseRunFromItsCheckpoint() {
        ChatMessage assistant = ChatMessage.assistant("思路 c1", List.of(new ToolCall("c1", "echo", "{\"x\":1}")));
        AgentStep archived = new AgentStep(1, assistant, List.of(ChatMessage.tool("c1", "echo says {\"x\":1}")),
                new Usage());
        AgentLoop loop = loop(List.of(archived));
        // 续跑后模型又要了一次同样的调用:结果走存档里的,工具不重跑
        when(gateway.chat(any())).thenReturn(call("c2", "echo", "{\"x\":1}"), answer("final"));
        Outcome out = loop.converse(run, profile, seed);

        assertEquals("final", out.content());
        assertEquals(0, echo.calls);
        assertEquals(2, out.history().size());
        assertEquals(3, out.stepCount());
        List<ChatMessage> sent = requests(2).get(0).getMessages();
        assertEquals("tool", sent.get(sent.size() - 1).getRole());                    // 存档的那一步原样放回了对话
        assertEquals("echo says {\"x\":1}", sent.get(sent.size() - 1).getContent());
        verify(checkpoint, times(1)).append(eq("uuid-qa"), any());                    // 只存新的那一步
    }

    @Test
    void forcesAnAnswerAfterTheStepLimit() {
        AgentLoop loop = loop(List.of());
        ChatResponse[] more = new ChatResponse[AgentLoop.MAX_STEPS];
        for (int i = 0; i < AgentLoop.MAX_STEPS; i++) {
            more[i] = i < AgentLoop.MAX_STEPS - 1 ? call("c" + (i + 2), "echo", "{\"x\":" + (i + 2) + "}") : answer("forced");
        }
        when(gateway.chat(any())).thenReturn(call("c1", "echo", "{\"x\":1}"), more);
        Outcome out = loop.converse(run, profile, seed);

        assertEquals("forced", out.content());
        assertEquals(AgentLoop.MAX_STEPS + 1, out.stepCount());
        List<ChatRequest> all = requests(AgentLoop.MAX_STEPS + 1);
        assertNull(all.get(AgentLoop.MAX_STEPS).getTools(), "步数用完后的那次请求不带工具");
        assertEquals(AgentLoop.MAX_STEPS, out.history().size());
    }

    @Test
    void researchToolsAreOfferedInTheOrderTheyWereRegistered() {
        LlmModelProperties props = new LlmModelProperties();
        LlmModelProperties.Model m = new LlmModelProperties.Model();
        m.setName("m");
        props.setModels(List.of(m));
        when(checkpoint.load(anyString())).thenReturn(List.of());
        List<AgentTool> registered = List.of(new FakeTool("kb_search"), new FakeTool("web_fetch"),
                new FakeTool("web_search"), new FakeTool("wiki_search"));
        when(gateway.chat(any())).thenReturn(answer("{\"answer\":\"ok\",\"sources\":[]}"));
        new AgentLoop(gateway, registered, checkpoint, mock(TraceEmitter.class), props).run(run);
        assertEquals(List.of("kb_search", "web_fetch", "web_search", "wiki_search"),
                requests(1).get(0).getTools().stream().map(t -> t.name()).toList());
    }

    @Test
    void aBrokenToolFailsARunThatAsksForItAndIsShownToTheModelOtherwise() {
        AgentTool broken = new FakeTool("echo") {
            @Override
            public String run(JsonNode args) {
                throw new IllegalStateException("search service is down");
            }
        };
        when(gateway.chat(any())).thenReturn(call("c1", "echo", "{}"), answer("done"));
        Profile strict = new Profile("p", List.of(broken), 4000, 100, true);
        assertThrows(IllegalStateException.class, () -> loop(List.of()).converse(run, strict, seed));

        when(gateway.chat(any())).thenReturn(call("c1", "echo", "{}"), answer("done"));
        Outcome out = loop(List.of()).converse(run, new Profile("p", List.of(broken), 4000, 100, false), seed);
        assertEquals("done", out.content());
        assertTrue(out.history().get(0).toolResults().get(0).getContent().startsWith("ERROR"));
    }

    @Test
    void aWrongArgumentIsShownToTheModelEvenInAStrictRun() {
        AgentTool picky = new FakeTool("echo") {
            @Override
            public String run(JsonNode args) {
                throw new IllegalArgumentException("query 不能为空");
            }
        };
        when(gateway.chat(any())).thenReturn(call("c1", "echo", "{}"), call("c2", "echo", "not json"), answer("done"));
        Outcome out = loop(List.of()).converse(run, new Profile("p", List.of(picky), 4000, 100, true), seed);
        assertEquals("done", out.content());
        assertTrue(out.history().get(0).toolResults().get(0).getContent().contains("query 不能为空"));
        assertTrue(out.history().get(1).toolResults().get(0).getContent().startsWith("ERROR"));
    }
}
