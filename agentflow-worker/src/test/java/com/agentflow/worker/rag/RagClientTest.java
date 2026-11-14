package com.agentflow.worker.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RagClient 单测:用 JDK HttpServer 起桩返回固定 JSON,验证解析(离线可跑,不依赖真服务)。 */
class RagClientTest {

    private HttpServer server;
    private int port;
    private String lastBody;

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/rag/search", ex -> {
            String body = """
                {"hits":[
                  {"id":"kafka-2","text":"kafka rebalance","score":0.91},
                  {"id":"redis-1","text":"redis lua","score":0.42}
                ]}""";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/segments/index", ex -> {
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b = "{\"indexed\":2}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/segments/search", ex -> {
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b = ("{\"hits\":[{\"id\":\"103:speech:750000\",\"lectureId\":103,\"startMs\":750000,\"endMs\":805000,"
                    + "\"kind\":\"speech\",\"text\":\"RL from human feedback\",\"score\":0.031}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private RagClient client() {
        RagProperties props = new RagProperties();
        props.setBaseUrl("http://localhost:" + port);
        return new RagClient(props);
    }

    @Test
    void parsesHitsInOrder() {
        List<Hit> hits = client().search("kafka partition", 2);
        assertEquals(2, hits.size());
        assertEquals("kafka-2", hits.get(0).id());
        assertEquals("kafka rebalance", hits.get(0).text());
        assertEquals(0.91, hits.get(0).score(), 1e-9);
    }

    @Test
    void indexSegmentsPostsTheLectureAndReturnsTheCount() throws Exception {
        int n = client().indexSegments(3, 7, List.of(
                new com.agentflow.worker.lecture.Segmenter.Segment(0, 60000, "speech", "Welcome."),
                new com.agentflow.worker.lecture.Segmenter.Segment(0, 20000, "slide", "Title")));
        assertEquals(2, n);
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastBody);
        assertEquals(3, body.get("courseId").asLong());
        assertEquals(7, body.get("lectureId").asLong());
        assertEquals("slide", body.get("segments").get(1).get("kind").asText());
        assertEquals(20000, body.get("segments").get(1).get("endMs").asLong());
        assertEquals("Welcome.", body.get("segments").get(0).get("text").asText());
    }

    @Test
    void searchSegmentsSendsTheFiltersAndParsesHits() throws Exception {
        List<RagClient.SegmentHit> hits = client().searchSegments(7, "rlhf", 5, List.of(103L), 600000L, null);
        assertEquals(1, hits.size());
        assertEquals(new RagClient.SegmentHit("103:speech:750000", 103, 750000, 805000, "speech", "RL from human feedback", 0.031),
                hits.get(0));
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastBody);
        assertEquals(7, body.get("courseId").asLong());
        assertEquals("rlhf", body.get("query").asText());
        assertEquals(5, body.get("topK").asInt());
        assertEquals(103, body.get("lectureIds").get(0).asLong());
        assertEquals(600000, body.get("fromMs").asLong());
        org.junit.jupiter.api.Assertions.assertFalse(body.has("toMs"), "没给的过滤条件不发");

        client().searchSegments(7, "rlhf", 5, null, null, null);
        body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lastBody);
        org.junit.jupiter.api.Assertions.assertFalse(body.has("lectureIds") || body.has("fromMs"));
    }
}
