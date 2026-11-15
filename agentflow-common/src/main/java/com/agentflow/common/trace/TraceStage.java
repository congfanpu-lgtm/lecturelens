package com.agentflow.common.trace;

public enum TraceStage {
    SUBMITTED, DECOMPOSED, DISPATCHED, WORKER_RECEIVED, PROCESSED, RESULT_SENT,
    SUBTASK_SETTLED, RETRY, DLQ, RECOVERED, TIMEOUT_REDISPATCH, TASK_FINALIZED,
    // 多阶段任务和 agent:阶段推进 / agent 每一步 / 从存档续跑 / 删除编造的引用(最后一项现在没有人发)
    STAGE_ADVANCED, AGENT_STEP, RESUMED, CITATION_DROPPED
}
