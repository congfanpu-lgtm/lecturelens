package com.agentflow.worker.media;

import com.agentflow.worker.retry.NonRetryableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 媒体服务的唯一入口(worker):下载、转写、挑帧、识别都在那边做,这里只发 HTTP 请求。
 * 那边按内容哈希缓存,同一讲同时来两个请求会排队、第二个拿到缓存,所以这些调用可以放心重试。
 * 和 {@code RagClient} 一样用 JDK HttpClient、固定 HTTP/1.1。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaClient {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final int MAX_DETAIL = 300;

    private final MediaProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** url 和 path 给一个:YouTube 链接,或者媒体服务 inbox 目录里的文件名。 */
    public JsonNode importMedia(String url, String path) {
        ObjectNode body = OM.createObjectNode();
        if (url != null) {
            body.put("url", url);
        }
        if (path != null) {
            body.put("path", path);
        }
        return call("POST", "/media/import", body.toString());
    }

    public JsonNode transcribe(String mediaKey) {
        return call("POST", "/media/" + mediaKey + "/transcribe", null);
    }

    /** 截帧识别的第 1 步:挑出画面变了的帧。返回里的 batches 是接下来要识别几批。 */
    public JsonNode pickFrames(String mediaKey) {
        return call("POST", "/media/" + mediaKey + "/frames/pick", null);
    }

    /**
     * 第 2 步:识别一批。打到这个 worker 自己的识别地址(media.ocr-base-url,不配就是媒体服务):
     * 各批经 Kafka 分到各个 worker,每个 worker 用自己那台机器上的识别副本,加机器就是加一对"worker + 副本"。
     */
    public JsonNode ocrBatch(String mediaKey, int batch) {
        String path = "/media/" + mediaKey + "/frames/batches/" + batch + "/ocr";
        String ocr = props.getOcrBaseUrl();
        if (ocr == null || ocr.isBlank()) {
            return call("POST", path, null);
        }
        try {
            return call(ocr, "POST", path, null);
        } catch (IllegalStateException e) {
            // 副本连不上(机器关了、进程死了):退回媒体服务自己识别。重试的消息总是回到同一个分区、同一个 worker,
            // 副本一直不在的话这一批会失败到底,而有一批失败这一讲就没有幻灯片。副本在线但这次出错的,照常走重试。
            if (e.getCause() instanceof ConnectException || e.getCause() instanceof HttpConnectTimeoutException) {
                log.warn("识别副本连不上,这一批改由媒体服务识别 ocrBaseUrl={} mediaKey={} batch={}", ocr, mediaKey, batch);
                return call("POST", path, null);
            }
            throw e;
        }
    }

    /** 第 3 步:各批的结果按时间合并、去重。有哪一批还没识别,那边会拒绝(400)。 */
    public JsonNode mergeFrames(String mediaKey) {
        return call("POST", "/media/" + mediaKey + "/frames/merge", null);
    }

    public JsonNode transcript(String mediaKey) {
        return call("GET", "/media/" + mediaKey + "/transcript", null);
    }

    public JsonNode frameList(String mediaKey) {
        return call("GET", "/media/" + mediaKey + "/frames", null);
    }

    private JsonNode call(String method, String path, String body) {
        return call(props.getBaseUrl(), method, path, body);
    }

    private JsonNode call(String baseUrl, String method, String path, String body) {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(props.getTimeoutMs()));
        if ("GET".equals(method)) {
            req.GET();
        } else {
            req.header("Content-Type", "application/json").POST(body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("媒体服务调用被中断 " + method + " " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("媒体服务调用失败 " + method + " " + path + ": " + e, e);
        }
        int code = resp.statusCode();
        if (code / 100 == 2) {
            try {
                return OM.readTree(resp.body());
            } catch (IOException e) {
                throw new IllegalStateException("媒体服务返回的不是 JSON " + method + " " + path, e);
            }
        }
        String message = "media " + code + ": " + detail(resp.body());
        // 4xx 是"给的东西不对"(链接不合规、媒体不存在),重试也是同样的结果;其余是那边自己的故障
        if (code / 100 == 4) {
            throw new NonRetryableException(message);
        }
        throw new IllegalStateException(message);
    }

    /** 错误体是 {"detail": …};422 时 detail 是个列表,原样转成文本。 */
    private static String detail(String body) {
        String text = body == null ? "" : body;
        try {
            JsonNode d = OM.readTree(text).path("detail");
            if (!d.isMissingNode()) {
                text = d.isTextual() ? d.asText() : d.toString();
            }
        } catch (IOException ignored) {
            // 不是 JSON:就用原文
        }
        return text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) : text;
    }
}
