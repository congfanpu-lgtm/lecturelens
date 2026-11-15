package com.agentflow.server.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;


import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公开演示保护:访问码、每 IP 每日次数、每日花费上限、只允许研究任务。 */
@SpringBootTest(properties = {"demo.access-code=s3cret"})
@AutoConfigureMockMvc
@Transactional
class DemoGuardTest {

    private static final String ECHO = "{\"type\":\"ECHO_BATCH\",\"payload\":{\"items\":[\"a\"]}}";

    @Autowired
    private MockMvc mvc;
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 每个用例用不同的客户端 IP,输错次数互不影响。 */
    private final String ip = "10.77." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

    private ResultActions submit(String body, String code) throws Exception {
        var req = post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(r -> { r.setRemoteAddr(ip); return r; });
        return mvc.perform(code == null ? req : req.header("X-Access-Code", code));
    }

    @Test
    void everythingButTheHomePageNeedsTheAccessCode() throws Exception {
        mvc.perform(get("/api/v1/llm/usage")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", containsString("access code")));
        mvc.perform(get("/api/v1/llm/usage").header("X-Access-Code", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/llm/usage").header("X-Access-Code", "s3cret")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/courses")).andExpect(status().isUnauthorized());
        submit(ECHO, null).andExpect(status().isUnauthorized());
    }

    @Test
    void theGenericTaskApiIsDisabledInDemoMode() throws Exception {
        // 通用接口能按任意类型、任意模型跑任务(批量调模型、把整门课塞进一次调用),演示模式下没有配额和预算拦它
        submit(ECHO, "s3cret").andExpect(status().isForbidden());
        submit("{\"type\":\"LLM_BATCH\",\"payload\":{\"items\":[\"a\"]}}", "s3cret").andExpect(status().isForbidden());
        submit("{\"type\":\"COURSE_QA\",\"payload\":{\"courseId\":1,\"question\":\"q\",\"eval\":{\"mode\":\"long\"}}}", "s3cret")
                .andExpect(status().isForbidden());
    }

    @Test
    void encodedOrMatrixPathsCannotSkipTheCode() throws Exception {
        // 审查发现:过滤器看原始 URI,Spring 按解码后的路径路由 → /%61pi/... 和 /api;x/... 能绕过访问码
        mvc.perform(post("/%61pi/v1/tasks").contentType(MediaType.APPLICATION_JSON).content(ECHO))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api;x=1/v1/tasks").contentType(MediaType.APPLICATION_JSON).content(ECHO))
                .andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/v1/courses/../llm/usage", "/api/v1/courses;x=1", "/api/v1/%63ourses"}) {
            mvc.perform(get(java.net.URI.create(path))).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error", containsString("access code")));
        }
    }

    @Test
    void dlqReplayIsDisabledInDemoMode() throws Exception {
        // 重放能把任意失败子任务(任意类型、原 model)再跑一遍
        mvc.perform(post("/api/v1/dlq/replay/1").header("X-Access-Code", "s3cret")).andExpect(status().isForbidden());
    }

    @Test
    void repeatedWrongCodesAreThrottled() throws Exception {
        for (int i = 0; i < DemoGuard.MAX_BAD_CODES; i++) {
            mvc.perform(get("/api/v1/llm/usage").header("X-Access-Code", "guess" + i)
                    .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/llm/usage").header("X-Access-Code", "s3cret")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isTooManyRequests());
    }

    @Test
    void ipv6ClientsAreCountedPerSlash64() {
        assertEquals(DemoGuard.quotaKey("2001:db8:1:2:aaaa::1"), DemoGuard.quotaKey("2001:0db8:0001:0002:ffff:1:2:3"));
        assertEquals("203.0.113.9", DemoGuard.quotaKey("203.0.113.9"));
    }

    @Test
    void clientIpTrustsProxyHeaderOnlyWhenConfigured() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("127.0.0.1");
        r.addHeader("CF-Connecting-IP", "203.0.113.9");
        assertEquals("127.0.0.1", DemoGuard.clientIp(r, false), "不信任时客户端可伪造这个头");
        assertEquals("203.0.113.9", DemoGuard.clientIp(r, true));
    }
}
