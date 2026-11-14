package com.agentflow.server.coordinator;

import com.agentflow.common.mq.SubtaskMessage;
import com.agentflow.common.mq.Topics;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.SubtaskMapper;
import com.agentflow.server.service.TaskStateMachine;
import com.agentflow.server.trace.TraceEmitter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 哪个子任务发到哪个 topic:转写单独一个,别的共用。 */
class SubtaskDispatcherRoutingTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final SubtaskMapper subtasks = mock(SubtaskMapper.class);

    private static SubtaskEntity sub(long id, String kind) {
        SubtaskEntity s = new SubtaskEntity();
        s.setId(id);
        s.setKind(kind);
        s.setSeq((int) id);
        s.setSubtaskUuid("u" + id);
        s.setInput("{}");
        return s;
    }

    private static List<PartitionInfo> partitions(String topic, int n) {
        List<PartitionInfo> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            parts.add(new PartitionInfo(topic, i, null, null, null));
        }
        return parts;
    }

    @Test
    void transcriptionGoesToItsOwnTopicWithItsOwnPartitionCount() {
        when(subtasks.selectList(any())).thenReturn(List.of(sub(21, "LEC_TRANSCRIBE"), sub(22, "LEC_FRAMES")));
        when(kafka.partitionsFor(Topics.SUBTASK)).thenReturn(partitions(Topics.SUBTASK, 8));
        when(kafka.partitionsFor(Topics.SUBTASK_SLOW)).thenReturn(partitions(Topics.SUBTASK_SLOW, 4));
        var sent = CompletableFuture.completedFuture(new SendResult<String, Object>(
                new ProducerRecord<>("t", "k", "v"), new RecordMetadata(new TopicPartition("t", 0), 0L, 0, 0L, 0, 0)));
        when(kafka.send(anyString(), anyInt(), anyString(), any())).thenReturn(sent);
        TaskEntity task = new TaskEntity();
        task.setId(5L);
        task.setType("LECTURE_INGEST");

        new SubtaskDispatcher(kafka, subtasks, mock(TaskStateMachine.class), mock(TraceEmitter.class)).dispatchPending(task);

        ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(Topics.SUBTASK_SLOW), eq(21 % 4), eq("21"), msg.capture());
        assertEquals("LEC_TRANSCRIBE", ((SubtaskMessage) msg.getValue()).getType());
        verify(kafka).send(eq(Topics.SUBTASK), eq(22 % 8), eq("22"), any());
    }
}
