package com.agentflow.server.course;

import com.agentflow.server.entity.CourseEntity;
import com.agentflow.server.entity.LectureEntity;
import com.agentflow.server.entity.SubtaskEntity;
import com.agentflow.server.entity.TaskEntity;
import com.agentflow.server.mapper.CourseMapper;
import com.agentflow.server.mapper.LectureMapper;
import com.agentflow.server.mapper.SubtaskMapper;
import com.agentflow.server.mapper.TaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 课程录播的接口:课程库页和单讲页用。默认不注册(agentflow.course.enabled=false):
 * 公开 demo 用的也是这份代码,不能让路人触发下载和转写。错误体由 ProductErrors 统一成 {"error": 原因}。
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "agentflow.course.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CourseController {

    private static final Pattern FRAME_NAME = Pattern.compile("\\d{9}\\.jpg");

    private final LectureService service;
    private final QuestionService questions;
    private final CourseMapper courseMapper;
    private final LectureMapper lectureMapper;
    private final TaskMapper taskMapper;
    private final SubtaskMapper subtaskMapper;
    private final MediaProxy mediaProxy;
    private final ObjectMapper objectMapper;

    public record CourseBody(String name) {
    }

    public record LectureBody(String url, String path, String title) {
    }

    @PostMapping("/courses")
    public Map<String, Object> createCourse(@RequestBody CourseBody body) {
        CourseEntity c = service.createCourse(body.name());
        return Map.of("id", c.getId(), "name", c.getName());
    }

    @GetMapping("/courses")
    public List<Map<String, Object>> courses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CourseEntity c : courseMapper.selectList(new QueryWrapper<CourseEntity>().orderByAsc("id"))) {
            List<LectureEntity> lectures = lectures(c.getId());
            out.add(Map.of("id", c.getId(), "name", c.getName(), "lectures", lectures.size(),
                    "ready", lectures.stream().filter(l -> "READY".equals(l.getStatus())).count()));
        }
        return out;
    }

    @GetMapping("/courses/{id}")
    public Map<String, Object> course(@PathVariable("id") long id) {
        CourseEntity c = courseMapper.selectById(id);
        if (c == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在");
        }
        // ponytail: 每讲各查一次任务和子任务。一门课十几讲、页面 3 秒刷一次没问题;讲次上百时改成一条 JOIN
        return Map.of("id", c.getId(), "name", c.getName(),
                "lectures", lectures(id).stream().map(this::view).toList());
    }

    @PostMapping("/courses/{id}/lectures")
    public Map<String, Object> addLecture(@PathVariable("id") long id, @RequestBody LectureBody body) {
        return view(service.addLecture(id, body.url(), body.path(), body.title()));
    }

    @GetMapping("/lectures/{id}")
    public Map<String, Object> lectureDetail(@PathVariable("id") long id) throws Exception {
        LectureEntity l = lecture(id);
        Map<String, Object> v = view(l);
        v.put("concepts", objectMapper.readTree(l.getConcepts() == null ? "[]" : l.getConcepts()));
        return v;
    }

    @PostMapping("/lectures/{id}/retry")
    public Map<String, Object> retry(@PathVariable("id") long id) {
        return view(service.retry(id));
    }

    /** 在一门课里提问:起一个 COURSE_QA 任务(agent 找答案 → 逐条核验),返回任务的 uuid,页面拿它轮询答案。 */
    @PostMapping("/courses/{id}/questions")
    public Map<String, String> ask(@PathVariable("id") long id, @RequestBody JsonNode body) {
        return Map.of("taskUuid", questions.ask(id, body));
    }

    @GetMapping("/questions/{taskUuid}")
    public JsonNode answer(@PathVariable("taskUuid") String taskUuid) {
        return questions.answer(taskUuid);
    }

    @GetMapping("/lectures/{id}/transcript")
    public ResponseEntity<byte[]> transcript(@PathVariable("id") long id) {
        return fromMedia(id, "/transcript");
    }

    @GetMapping("/lectures/{id}/frames")
    public ResponseEntity<byte[]> frames(@PathVariable("id") long id) {
        return fromMedia(id, "/frames");
    }

    @GetMapping("/lectures/{id}/frames/{name}")
    public ResponseEntity<byte[]> frame(@PathVariable("id") long id, @PathVariable("name") String name) {
        if (!FRAME_NAME.matcher(name).matches()) {   // 名字会拼进媒体服务的路径:只放行截帧产出的那种文件名
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "帧文件名不对");
        }
        return fromMedia(id, "/frames/" + name);
    }

    private ResponseEntity<byte[]> fromMedia(long lectureId, String suffix) {
        LectureEntity l = lecture(lectureId);
        if (l.getMediaKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这一讲还没有导入成功");
        }
        MediaProxy.Body b = mediaProxy.get("/media/" + l.getMediaKey() + suffix);
        return ResponseEntity.status(b.status()).contentType(MediaType.parseMediaType(b.contentType())).body(b.bytes());
    }

    private LectureEntity lecture(long id) {
        LectureEntity l = lectureMapper.selectById(id);
        if (l == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "讲次不存在");
        }
        return l;
    }

    private List<LectureEntity> lectures(long courseId) {
        return lectureMapper.selectList(new QueryWrapper<LectureEntity>().eq("course_id", courseId).orderByAsc("seq"));
    }

    private Map<String, Object> view(LectureEntity l) {
        Map<String, Object> v = new LinkedHashMap<>();   // 有的值是 null,Map.of 放不了
        v.put("id", l.getId());
        v.put("courseId", l.getCourseId());
        v.put("seq", l.getSeq());
        v.put("title", l.getTitle());
        v.put("status", l.getStatus());
        v.put("durationMs", l.getDurationMs());
        v.put("sourceUrl", l.getSourceUrl());
        v.put("sourcePath", l.getSourcePath());
        v.put("error", l.getError());
        v.put("stages", stages(l.getTaskUuid()));
        return v;
    }

    /** 最近一次摄入任务的各个步骤。seconds 是这一步从建出来到落定的时间(含排队),落定后才有。 */
    private List<Map<String, Object>> stages(String taskUuid) {
        TaskEntity task = taskUuid == null ? null
                : taskMapper.selectOne(new QueryWrapper<TaskEntity>().eq("task_uuid", taskUuid));
        if (task == null) {
            return new ArrayList<>();
        }
        return stages(subtaskMapper.selectList(
                new QueryWrapper<SubtaskEntity>().eq("task_id", task.getId()).orderByAsc("seq")));
    }

    /**
     * 一讲有十几个识别子任务(LEC_OCR,一批 20 张),页面上合成"识别"一项:done / total 是进度;
     * 全部落定后,有失败的算 FAILED,否则 COMPLETED;用时从第一批建出来算到最后一批落定。别的步骤一个子任务一项。
     */
    static List<Map<String, Object>> stages(List<SubtaskEntity> subs) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> ocr = null;
        for (SubtaskEntity s : subs) {
            boolean completed = "COMPLETED".equals(s.getStatus());
            boolean settled = completed || "FAILED".equals(s.getStatus());
            if (!"LEC_OCR".equals(s.getKind())) {
                Map<String, Object> stage = new LinkedHashMap<>();
                stage.put("kind", s.getKind());
                stage.put("status", s.getStatus());
                stage.put("error", s.getErrorMsg());
                stage.put("seconds", settled && s.getCreatedAt() != null && s.getUpdatedAt() != null
                        ? Duration.between(s.getCreatedAt(), s.getUpdatedAt()).getSeconds() : null);
                out.add(stage);
                continue;
            }
            if (ocr == null) {
                ocr = new LinkedHashMap<>();
                ocr.put("kind", s.getKind());
                ocr.put("status", "COMPLETED");
                ocr.put("error", null);
                ocr.put("seconds", null);
                ocr.put("done", 0);
                ocr.put("total", 0);
                ocr.put("from", s.getCreatedAt());
                ocr.put("to", s.getUpdatedAt());
                out.add(ocr);
            }
            ocr.put("total", (int) ocr.get("total") + 1);
            if (completed) {
                ocr.put("done", (int) ocr.get("done") + 1);
            }
            if (!settled) {
                ocr.put("status", "RUNNING");
            } else if (!completed && ocr.get("error") == null) {
                ocr.put("error", s.getErrorMsg());
            }
            if (s.getUpdatedAt() != null && (ocr.get("to") == null || s.getUpdatedAt().isAfter((LocalDateTime) ocr.get("to")))) {
                ocr.put("to", s.getUpdatedAt());
            }
        }
        if (ocr != null) {
            LocalDateTime from = (LocalDateTime) ocr.remove("from");
            LocalDateTime to = (LocalDateTime) ocr.remove("to");
            if (!"RUNNING".equals(ocr.get("status"))) {
                ocr.put("status", (int) ocr.get("done") == (int) ocr.get("total") ? "COMPLETED" : "FAILED");
                ocr.put("seconds", from != null && to != null ? Duration.between(from, to).getSeconds() : null);
            }
        }
        return out;
    }
}
