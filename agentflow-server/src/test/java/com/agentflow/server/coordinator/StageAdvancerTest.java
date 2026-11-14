package com.agentflow.server.coordinator;

import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.SubtaskMapper;
import com.agentflow.server.mapper.TaskMapper;
import com.agentflow.server.service.TaskFinalizer;
import com.agentflow.server.trace.TraceEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 什么时候问任务类型"下一步建什么":一般是当前子任务全部落定;声明了 advancesEarly 的类型,每落定一个就问。 */
class StageAdvancerTest {

    /** 记下被问了几次;按给定的回答返回下一阶段。 */
    private static final class Recording implements TaskDecomposer {
        private final boolean early;
        private final List<SubtaskDef> answer;
        private int asked;

        private Recording(boolean early, List<SubtaskDef> answer) {
            this.early = early;
            this.answer = answer;
        }

        @Override
        public String type() {
            return "T";
        }

        @Override
        public List<SubtaskDef> decompose(JsonNode payload) {
            return List.of();
        }

        @Override
        public boolean advancesEarly() {
            return early;
        }

        @Override
        public List<SubtaskDef> nextStage(TaskEntity task, List<SubtaskEntity> subs) {
            asked++;
            return answer;
        }
    }

    private final TaskMapper tasks = mock(TaskMapper.class);
    private final SubtaskMapper subtasks = mock(SubtaskMapper.class);
    private final SubtaskDispatcher dispatcher = mock(SubtaskDispatcher.class);
    private final TaskFinalizer finalizer = mock(TaskFinalizer.class);

    private StageAdvancer advancer(TaskDecomposer d, int total, int done, int failed) {
        TaskEntity t = new TaskEntity();
        t.setId(9L);
        t.setType("T");
        t.setSubtaskTotal(total);
        t.setSubtaskDone(done);
        t.setSubtaskFailed(failed);
        when(tasks.selectById(9L)).thenReturn(t);
        when(subtasks.selectList(any())).thenReturn(new ArrayList<>());
        return new StageAdvancer(List.of(d), tasks, subtasks, dispatcher, finalizer, mock(TraceEmitter.class), new ObjectMapper());
    }

    @Test
    void aNormalTaskTypeIsOnlyAskedWhenEverythingHasSettled() {
        Recording d = new Recording(false, List.of(new SubtaskDef(2, "{}", "NEXT")));
        advancer(d, 2, 1, 0).onSettled(9L);                       // 两个子任务里才落定一个
        assertEquals(0, d.asked, "还有子任务在跑:不问下一阶段");
        verify(subtasks, never()).insert(any(SubtaskEntity.class));
        verify(finalizer).finalizeIfAllSettled(9L);

        advancer(d, 2, 1, 1).onSettled(9L);                       // 都落定了(一个成功一个失败)
        assertEquals(1, d.asked);
        verify(subtasks).insert(any(SubtaskEntity.class));
        verify(tasks).addTotal(9L, 1);
        verify(dispatcher).dispatchPending(any());
    }

    @Test
    void aTaskTypeThatAdvancesEarlyIsAskedOnEverySettle() {
        Recording d = new Recording(true, List.of(new SubtaskDef(10, "{}", "A"), new SubtaskDef(11, "{}", "A")));
        advancer(d, 3, 1, 0).onSettled(9L);                       // 三个里才落定一个,也问
        assertEquals(1, d.asked);
        verify(tasks).addTotal(9L, 2);
        verify(dispatcher).dispatchPending(any());
        verify(finalizer, never()).finalizeIfAllSettled(any());   // 建了新的子任务:任务还没完
    }

    @Test
    void whenAnEarlyTypeHasNothingToAddTheFinalizerStillDecides() {
        Recording d = new Recording(true, List.of());
        advancer(d, 3, 1, 0).onSettled(9L);
        assertEquals(1, d.asked);
        verify(subtasks, never()).insert(any(SubtaskEntity.class));
        verify(finalizer).finalizeIfAllSettled(9L);               // 它自己判断是不是全部落定了
    }
}
