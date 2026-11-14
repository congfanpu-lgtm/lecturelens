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
import java.util.HashSet;
import java.util.Set;
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
        // 每一轮核验的输出(只算跑成了的)。补一轮是"补",不是"重来":答案是各轮通过核验的结论合在一起
        List<JsonNode> verified = subs.stream()
                .filter(s -> CourseQaDecomposer.VERIFY.equals(s.getKind()) && "COMPLETED".equals(s.getStatus()))
                .map(s -> json(s.getOutput())).toList();
        // PARTIAL_FAILED:第一轮核验过了,补的那一轮没跑成——第一轮通过核验的结论照样给出去
        boolean answered = "COMPLETED".equals(task.getStatus())
                || ("PARTIAL_FAILED".equals(task.getStatus()) && !verified.isEmpty());

        ObjectNode view = objectMapper.createObjectNode();
        view.put("status", running ? "RUNNING" : answered ? "COMPLETED" : "FAILED");
        view.put("question", json(task.getPayload()).path("question").asText(""));

        // 答案只在任务落定后给:补一轮期间不把第一轮(已经被判定不够好)的结果先亮出来
        boolean notFound = false;
        ArrayNode claims = objectMapper.createArrayNode();
        JsonNode dropped = objectMapper.createArrayNode();
        if (answered && verified.isEmpty()) {
            // 没有经过核验就结束了:agent 自己说课里没讲。只认它自己的这句话,别的情况(比如补的一轮没补上)不算"没讲"
            notFound = !subs.isEmpty() && json(subs.get(0).getOutput()).path("notFound").asBoolean(false);
        } else if (answered) {
            Set<String> seen = new HashSet<>();
            for (JsonNode round : verified) {
                for (JsonNode claim : round.path("claims")) {
                    if (seen.add(claim.path("text").asText())) {
                        claims.add(claim);
                    }
                }
            }
            // 被拿掉的只列最后一次核验的:第一轮被拿掉的那些,第二轮要么换了证据通过了,要么又被拿掉、已经在这里面
            if (verified.get(verified.size() - 1).path("dropped").isArray()) {
                dropped = verified.get(verified.size() - 1).path("dropped");
            }
        }
        view.put("notFound", notFound);
        view.set("claims", claims);
        view.set("dropped", dropped);
        // 每一轮核验各自的明细(落定之后才给):评测统计"被拿掉的占比""引文忠实率"时要把各轮加起来——
        // 上面的 dropped 只是最后一轮的,补过一轮的话第一轮被拿掉的那些不在里面
        ArrayNode verifications = view.putArray("verifications");
        if (answered) {
            for (JsonNode round : verified) {
                ObjectNode item = verifications.addObject().put("kept", round.path("claims").size());
                item.set("dropped", round.path("dropped").isArray() ? round.path("dropped") : objectMapper.createArrayNode());
                item.set("evidence", round.path("evidence"));
            }
        }

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
        boolean repairFailed = answered && !"COMPLETED".equals(task.getStatus());
        view.put("error", running ? null
                : repairFailed ? "补的一轮没有跑成,下面是第一轮通过核验的结果(" + (error == null ? "任务失败" : error) + ")"
                : answered ? null : error == null ? "任务失败" : error);
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
