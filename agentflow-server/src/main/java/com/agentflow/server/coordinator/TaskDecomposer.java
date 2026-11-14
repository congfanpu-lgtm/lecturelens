package com.agentflow.server.coordinator;

import com.agentflow.common.state.TaskStatus;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 协调器拆解策略:每种任务类型一个实现,Spring 自动注册(策略模式)。 */
public interface TaskDecomposer {
    String type();

    List<SubtaskDef> decompose(JsonNode payload);

    /**
     * 当前所有子任务都已落定时调用(声明了 {@link #advancesEarly} 的类型:每落定一个子任务就调用一次):
     * 返回下一阶段要创建的子任务;空 = 现在没有要建的。全部落定时返回空,任务进入终态。
     * 单阶段任务类型不必覆盖。
     */
    default List<SubtaskDef> nextStage(TaskEntity task, List<SubtaskEntity> subs) {
        return List.of();
    }

    /**
     * true:不等当前所有子任务落定,每落定一个子任务就问一次 {@link #nextStage}。
     * 用于一个任务里有互不相干的几条线(讲次摄入:转写占显卡,挑帧和识别占 CPU),其中一条线的下一步不该等另一条线。
     * 这样的 nextStage 会被问很多次,必须自己保证同一批子任务只建一次。同一个任务的落定是串行处理的(见 StageAdvancer)。
     */
    default boolean advancesEarly() {
        return false;
    }

    /** 全部落定、即将进终态时调用:可按业务语义改判计数得出的终态。默认不改。 */
    default TaskStatus terminalStatus(TaskEntity task, List<SubtaskEntity> subs, TaskStatus computed) {
        return computed;
    }

    /** 任务刚进入终态时调用一次(和终态落定在同一个事务里):把结果写进业务表之类的收尾。默认什么都不做。 */
    default void onFinished(TaskEntity task, List<SubtaskEntity> subs) {
    }
}
