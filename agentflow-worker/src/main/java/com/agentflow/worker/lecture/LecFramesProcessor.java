package com.agentflow.worker.lecture;

import com.agentflow.worker.media.MediaClient;
import com.agentflow.worker.processor.SubtaskProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 讲次摄入里和转写并行的那一步:挑出画面变了的帧(只解码和比较画面,不识别文字)。
 * 输出里的 batches 告诉 server 接下来建几个 LEC_OCR 子任务。失败不拖垮整讲,只是索引里没有幻灯片。
 */
@Component
@RequiredArgsConstructor
public class LecFramesProcessor implements SubtaskProcessor {

    private static final ObjectMapper OM = new ObjectMapper();
    private final MediaClient media;

    @Override
    public String type() {
        return "LEC_FRAMES";
    }

    @Override
    public String process(String inputJson) throws Exception {
        JsonNode r = media.pickFrames(OM.readTree(inputJson).path("mediaKey").asText());
        return OM.createObjectNode()
                .put("candidates", r.path("candidates").asInt())
                .put("batches", r.path("batches").asInt())
                .put("cached", r.path("cached").asBoolean())
                .toString();
    }
}
