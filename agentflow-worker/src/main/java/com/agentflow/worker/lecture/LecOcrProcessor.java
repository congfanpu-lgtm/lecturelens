package com.agentflow.worker.lecture;

import com.agentflow.worker.media.MediaClient;
import com.agentflow.worker.processor.SubtaskProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 识别一批幻灯片(20 张候选帧)上的文字。一讲拆成十几个这样的子任务,经 Kafka 分到各个 worker 并行做;
 * 各批互不依赖,某一批失败只重试那一批。结果留在媒体服务那边,这里只回报数量。
 */
@Component
@RequiredArgsConstructor
public class LecOcrProcessor implements SubtaskProcessor {

    private static final ObjectMapper OM = new ObjectMapper();
    private final MediaClient media;

    @Override
    public String type() {
        return "LEC_OCR";
    }

    @Override
    public String process(String inputJson) throws Exception {
        JsonNode in = OM.readTree(inputJson);
        JsonNode r = media.ocrBatch(in.path("mediaKey").asText(), in.path("batch").asInt());
        return OM.createObjectNode()
                .put("batch", r.path("batch").asInt())
                .put("frames", r.path("frames").asInt())
                .put("cached", r.path("cached").asBoolean())
                .toString();
    }
}
