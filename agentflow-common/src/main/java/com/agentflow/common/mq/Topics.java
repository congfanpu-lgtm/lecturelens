package com.agentflow.common.mq;

public final class Topics {
    public static final String SUBTASK = "AGENTFLOW_SUBTASK";
    public static final String SUBTASK_SLOW = "AGENTFLOW_SUBTASK_SLOW";
    public static final String RESULT = "AGENTFLOW_RESULT";
    public static final String RETRY_5S = "AGENTFLOW_RETRY_5S";
    public static final String RETRY_30S = "AGENTFLOW_RETRY_30S";
    public static final String RETRY_5M = "AGENTFLOW_RETRY_5M";
    public static final String DLQ = "AGENTFLOW_DLQ";
    public static final String TRACE = "AGENTFLOW_TRACE";

    public static final String RETRY_ATTEMPT_HEADER = "x-retry-attempt";
    public static final String NOT_BEFORE_HEADER = "x-not-before"; // epoch millis

    /**
     * 这种子任务发到哪个 topic。转写(LEC_TRANSCRIBE)单独一个:它要等显卡,一等就是几分钟,期间占着一个消费线程;
     * 和别的子任务共用 topic 的话,排在它后面的识别、建索引、提问都得跟着等(实测:三讲同时摄入,
     * 转写 100 秒就完成的那一讲要到 351 秒才可检索,docs/benchmarks.md 第 10 节)。别的子任务共用主 topic。
     * 分发、重试、超时重投、死信恢复都经过这里,保证一个子任务始终在同一个 topic 里。
     */
    public static String forSubtask(String kind) {
        return "LEC_TRANSCRIBE".equals(kind) ? SUBTASK_SLOW : SUBTASK;
    }

    private Topics() {}
}
