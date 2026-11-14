package com.agentflow.worker.media;

import com.agentflow.worker.retry.NonRetryableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MediaClient 单测:JDK HttpServer 起桩,记录收到的请求(离线可跑)。 */
class MediaClientTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private HttpServer server;
    private final List<String> seen = new ArrayList<>();   // "METHOD path body"
    private int status = 200;
    private String reply = "{}";

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body);
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private MediaClient client() {
        MediaProperties props = new MediaProperties();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        return new MediaClient(props);
    }

    @Test
    void importSendsOnlyTheSourceThatWasGiven() {
        reply = "{\"mediaKey\":\"" + KEY + "\",\"title\":\"L1\",\"durationMs\":7101000,\"video\":\"video.mp4\",\"cached\":false}";
        JsonNode meta = client().importMedia("https://youtu.be/abc", null);
        assertEquals(KEY, meta.get("mediaKey").asText());
        assertEquals("POST /media/import {\"url\":\"https://youtu.be/abc\"}", seen.get(0));
        client().importMedia(null, "lec1.mp4");
        assertEquals("POST /media/import {\"path\":\"lec1.mp4\"}", seen.get(1));
    }

    @Test
    void stepsAndReadsUseTheMediaKeyInThePath() {
        reply = "{\"utterances\":[{\"startMs\":0,\"endMs\":4200,\"text\":\"Hi.\"}],\"frames\":[]}";
        MediaClient c = client();
        c.transcribe(KEY);
        c.pickFrames(KEY);
        c.ocrBatch(KEY, 3);       // 没有单独配识别地址:识别也打到媒体服务
        c.mergeFrames(KEY);
        assertEquals("Hi.", c.transcript(KEY).get("utterances").get(0).get("text").asText());
        assertTrue(c.frameList(KEY).get("frames").isEmpty());
        assertEquals(List.of("POST /media/" + KEY + "/transcribe ", "POST /media/" + KEY + "/frames/pick ",
                "POST /media/" + KEY + "/frames/batches/3/ocr ", "POST /media/" + KEY + "/frames/merge ",
                "GET /media/" + KEY + "/transcript ", "GET /media/" + KEY + "/frames "), seen);
    }

    @Test
    void ocrGoesToTheWorkersOwnOcrAddressWhenOneIsConfigured() throws IOException {
        List<String> atReplica = new ArrayList<>();
        HttpServer replica = HttpServer.create(new InetSocketAddress(0), 0);
        replica.createContext("/", ex -> {
            atReplica.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            byte[] b = "{\"batch\":3,\"frames\":20,\"cached\":false}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        replica.start();
        try {
            MediaProperties props = new MediaProperties();
            props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            props.setOcrBaseUrl("http://localhost:" + replica.getAddress().getPort());
            MediaClient c = new MediaClient(props);
            assertEquals(20, c.ocrBatch(KEY, 3).get("frames").asInt());
            c.pickFrames(KEY);                                    // 别的调用照旧打到媒体服务
            assertEquals(List.of("POST /media/" + KEY + "/frames/batches/3/ocr"), atReplica);
            assertEquals(List.of("POST /media/" + KEY + "/frames/pick "), seen);
        } finally {
            replica.stop(0);
        }
    }

    @Test
    void ocrFallsBackToTheMediaServiceWhenTheReplicaCannotBeReached() throws IOException {
        // 识别副本没起来(机器关了、进程死了):退回媒体服务自己识别。否则这个 worker 分到的每一批都会失败到底,
        // 而有一批失败,这一讲就一张幻灯片都没有
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        reply = "{\"batch\":3,\"frames\":20,\"cached\":false}";
        MediaProperties props = new MediaProperties();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setOcrBaseUrl("http://localhost:" + closed);
        assertEquals(20, new MediaClient(props).ocrBatch(KEY, 3).get("frames").asInt());
        assertEquals(List.of("POST /media/" + KEY + "/frames/batches/3/ocr "), seen);
    }

    @Test
    void aReplicaThatAnswersWithAnErrorIsNotBypassed() throws IOException {
        HttpServer replica = HttpServer.create(new InetSocketAddress(0), 0);
        replica.createContext("/", ex -> {
            byte[] b = "{\"detail\":\"RuntimeError: media service answered 500\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        replica.start();
        try {
            MediaProperties props = new MediaProperties();
            props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
            props.setOcrBaseUrl("http://localhost:" + replica.getAddress().getPort());
            assertThrows(IllegalStateException.class, () -> new MediaClient(props).ocrBatch(KEY, 3));
            assertTrue(seen.isEmpty(), "副本在线、只是这次出错:走正常的重试,不绕过它");
        } finally {
            replica.stop(0);
        }
    }

    @Test
    void aBadRequestIsNotWorthRetrying() {
        status = 400;
        reply = "{\"detail\":\"only YouTube links are accepted, got: 'https://evil.example/x'\"}";
        NonRetryableException e = assertThrows(NonRetryableException.class,
                () -> client().importMedia("https://evil.example/x", null));
        assertTrue(e.getMessage().startsWith("media 400: only YouTube links are accepted"), e.getMessage());
    }

    @Test
    void aServerSideFailureIsRetried() {
        status = 502;
        reply = "{\"detail\":\"download failed: HTTP Error 403\"}";
        RuntimeException e = assertThrows(RuntimeException.class, () -> client().importMedia("https://youtu.be/abc", null));
        assertFalse(e instanceof NonRetryableException);
        assertTrue(e.getMessage().contains("502"), e.getMessage());
    }
}
