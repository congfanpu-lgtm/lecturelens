package com.agentflow.server.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

/**
 * 公开演示保护。配置了 DEMO_ACCESS_CODE 才生效,否则什么都不做(本地开发 / CI 不受影响)。
 * <ul>
 *   <li>白名单:只有首页(GET / 和 /index.html)不需要访问码,其余一切请求都要带 X-Access-Code
 *       (常数时间比较)。按白名单放行而不是按 "/api/" 前缀拦截:Spring 按解码后的路径路由,
 *       /%61pi/... 或 /api;x/... 这类写法能绕过前缀匹配。同一地址连续输错 {@link #MAX_BAD_CODES} 次后 1 小时内拒绝。</li>
 *   <li>通用的任务提交接口和 DLQ 重放在演示模式下禁用:它们能按任意类型、任意模型跑任务,没有配额和预算可言。</li>
 * </ul>
 * 课程接口(加讲次、提问)在演示模式下只有访问码这一道保护,没有按地址的配额和每日预算;真要公开演示得先补上。
 * 客户端 IP 默认取 TCP 对端;经 Cloudflare Tunnel 时对端是本机,需 DEMO_TRUST_PROXY_HEADER=true 改读 CF-Connecting-IP
 * (只有 8080 不直接对外暴露时才能信任这个头,否则客户端可以伪造它绕过输错次数的限制)。
 */
@Component
public class DemoGuard extends OncePerRequestFilter {

    static final String HEADER = "X-Access-Code";
    static final int MAX_BAD_CODES = 20;
    static final String BAD_CODE_PREFIX = "agentflow:demo:badcode:";
    private static final Set<String> PUBLIC_PAGES = Set.of("/", "/index.html", "/favicon.ico");
    private final RedissonClient redisson;
    private final byte[] code;
    private final boolean trustProxyHeader;

    public DemoGuard(RedissonClient redisson,
                     @Value("${demo.access-code:${DEMO_ACCESS_CODE:}}") String code,
                     @Value("${demo.trust-proxy-header:${DEMO_TRUST_PROXY_HEADER:false}}") boolean trustProxyHeader) {
        this.redisson = redisson;
        this.code = code.trim().getBytes(StandardCharsets.UTF_8);
        this.trustProxyHeader = trustProxyHeader;
    }

    public boolean enabled() {
        return code.length > 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled() || ("GET".equals(req.getMethod()) && PUBLIC_PAGES.contains(req.getRequestURI()))) {
            chain.doFilter(req, resp);
            return;
        }
        RAtomicLong bad = redisson.getAtomicLong(BAD_CODE_PREFIX + quotaKey(clientIp(req, trustProxyHeader)));
        if (bad.get() >= MAX_BAD_CODES) {
            reject(resp, HttpStatus.TOO_MANY_REQUESTS, "Too many wrong access codes — try again in an hour.");
            return;
        }
        String given = req.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(code, given.trim().getBytes(StandardCharsets.UTF_8))) {
            if (given != null && !given.isBlank() && bad.incrementAndGet() == 1) {
                bad.expire(Duration.ofHours(1));
            }
            reject(resp, HttpStatus.UNAUTHORIZED, "This demo needs an access code.");
            return;
        }
        chain.doFilter(req, resp);
    }

    private static void reject(HttpServletResponse resp, HttpStatus status, String message) throws IOException {
        resp.setStatus(status.value());
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /** 演示模式下禁用的操作:通用的任务提交、DLQ 重放。 */
    public void forbidInDemo() {
        if (enabled()) {
            throw new Rejected(HttpStatus.FORBIDDEN, "Not available in the public demo.");
        }
    }

    static String clientIp(HttpServletRequest req, boolean trustProxyHeader) {
        String cf = trustProxyHeader ? req.getHeader("CF-Connecting-IP") : null;
        return cf != null && !cf.isBlank() ? cf.trim() : req.getRemoteAddr();
    }

    /** 输错次数按什么计:IPv4 整个地址;IPv6 取 /64 前缀(一个用户通常拥有整段 /64,否则换个地址就能绕开)。 */
    static String quotaKey(String ip) {
        if (!ip.contains(":")) {
            return ip;
        }
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();   // IP 字面量,不触发 DNS
            return b.length == 16 ? HexFormat.of().formatHex(b, 0, 8) + "::/64" : ip;
        } catch (IOException e) {
            return ip;
        }
    }

    public static class Rejected extends RuntimeException {
        private final HttpStatus status;

        Rejected(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
