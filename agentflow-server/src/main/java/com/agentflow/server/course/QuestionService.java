package com.agentflow.server.course;

import com.agentflow.server.coordinator.CourseQaDecomposer;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.SubtaskMapper;
import com.agentflow.server.mapper.TaskMapper;
import com.agentflow.server.service.TaskSubmitService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

/**
 * 提问和取答案。一次提问就是一个 COURSE_QA 任务;答案不另外存表,由这个任务的子任务输出拼出来:
 * 通过核验的结论、被拿掉的结论和原因、agent 调用过哪些工具、每一步的状态和用量。
 */
@Service
@RequiredArgsConstructor
public class QuestionService {

    private final CourseMapper courseMapper;
    private final TaskMapper taskMapper;
    private final SubtaskMapper subtaskMapper;
    private final TaskSubmitService submitService;
    private final ObjectMapper objectMapper;

    /** body:{"question", "history":[{"question","answer"}](可省), "model"(可省)}。返回任务的 uuid。 */
    public String ask(long courseId, JsonNode body) {
        if (courseMapper.selectById(courseId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在");
        }
        ObjectNode payload = objectMapper.createObjectNode().put("courseId", courseId)
                .put("question", body == null ? "" : body.path("question").asText(""));
        if (body != null && body.path("history").isArray()) {
            payload.set("history", body.get("history"));
        }
        if (body != null && body.hasNonNull("model")) {
            payload.put("model", body.get("model").asText());
        }
        try {
            return submitService.submit(CourseQaDecomposer.TYPE, payload).getTaskUuid();
        } catch (IllegalArgumentException e) {   // 问题为空或太长、这门课还没有处理好的讲次
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    public ObjectNode answer(String taskUuid) {
        TaskEntity task = taskMapper.selectOne(new QueryWrapper<TaskEntity>().eq("task_uuid", taskUuid));
        if (task == null || !CourseQaDecomposer.TYPE.equals(task.getType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个提问");
        }
        List<SubtaskEntity> subs = subtaskMapper.selectList(
                new QueryWrapper<SubtaskEntity>().eq("task_id", task.getId()).orderByAsc("seq"));
        boolean running = "PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus());
        boolean answered = "COMPLETED".equals(task.getStatus());

        ObjectNode view = objectMapper.createObjectNode();
        view.put("status", running ? "RUNNING" : answered ? "COMPLETED" : "FAILED");
        view.put("question", json(task.getPayload()).path("question").asText(""));

        // 答案只在任务落定后给:补一轮期间不把第一轮(已经被判定不够好)的结果先亮出来
        boolean notFound = false;
        JsonNode claims = objectMapper.createArrayNode();
        JsonNode dropped = objectMapper.createArrayNode();
        if (answered && !subs.isEmpty()) {
            SubtaskEntity last = subs.get(subs.size() - 1);
            JsonNode out = json(last.getOutput());
            if (CourseQaDecomposer.VERIFY.equals(last.getKind())) {
                claims = out.path("claims");
                dropped = out.path("dropped");
            } else {
                notFound = true;   // agent 说课里没讲,或者一条结论都没给:没有经过核验这一步
            }
        }
        view.put("notFound", notFound);
        view.set("claims", claims);
        view.set("dropped", dropped);

        int rounds = 0;
        ArrayNode toolCalls = view.putArray("toolCalls");
        ArrayNode stages = objectMapper.createArrayNode();
        long calls = 0, promptTokens = 0, completionTokens = 0, costMicro = 0;
        String error = null;
        for (SubtaskEntity s : subs) {
            boolean completed = "COMPLETED".equals(s.getStatus());
            boolean failed = "FAILED".equals(s.getStatus());
            ObjectNode stage = stages.addObject().put("kind", s.getKind()).put("status", s.getStatus());
            stage.put("error", s.getErrorMsg());
            if ((completed || failed) && s.getCreatedAt() != null && s.getUpdatedAt() != null) {
                stage.put("seconds", Duration.between(s.getCreatedAt(), s.getUpdatedAt()).getSeconds());
            } else {
                stage.putNull("seconds");
            }
            if (failed && error == null) {
                error = s.getKind() + ": " + s.getErrorMsg();
            }
            if (completed) {
                JsonNode out = json(s.getOutput());
                if (CourseQaDecomposer.AGENT.equals(s.getKind())) {
                    rounds++;
                    out.path("toolCalls").forEach(toolCalls::add);
                }
                JsonNode usage = out.path("usage");
                calls += usage.path("calls").asLong();
                promptTokens += usage.path("promptTokens").asLong();
                completionTokens += usage.path("completionTokens").asLong();
                costMicro += usage.path("costMicro").asLong();
            }
        }
        view.put("rounds", rounds);
        view.set("stages", stages);
        view.set("usage", objectMapper.createObjectNode().put("calls", calls).put("promptTokens", promptTokens)
                .put("completionTokens", completionTokens).put("costMicro", costMicro));
        view.put("error", running || answered ? null : error == null ? "任务失败" : error);
        return view;
    }

    private JsonNode json(String s) {
        try {
            return objectMapper.readTree(s == null ? "{}" : s);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
