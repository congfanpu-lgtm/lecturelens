package com.agentflow.worker.media;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 媒体服务(agentflow-media,Python/FastAPI,跑在 GPU 宿主机上)的连接配置。 */
@Getter
@Setter
@ConfigurationProperties("media")
public class MediaProperties {
    private String baseUrl = "http://localhost:8010";
    /** 转写和截帧是同步的长请求(一讲几分钟),超时要比它们长。 */
    private int timeoutMs = 1_200_000;
    /**
     * 这个 worker 识别幻灯片用的地址。不配就用 baseUrl(媒体服务自己识别)。
     * 配成一个识别副本(同一份媒体服务代码,MEDIA_SOURCE 指向媒体服务)时,加一台机器 = 加一对"worker + 副本"。
     */
    private String ocrBaseUrl;
}
