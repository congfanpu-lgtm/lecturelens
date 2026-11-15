CREATE TABLE IF NOT EXISTS task (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_uuid     VARCHAR(36)  NOT NULL,
  type          VARCHAR(32)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  payload       JSON         NULL,
  result        JSON         NULL,
  subtask_total INT          NOT NULL DEFAULT 0,
  subtask_done  INT          NOT NULL DEFAULT 0,
  subtask_failed INT         NOT NULL DEFAULT 0,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_uuid (task_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS subtask (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  subtask_uuid  VARCHAR(36)  NOT NULL,
  task_id       BIGINT       NOT NULL,
  seq           INT          NOT NULL,
  kind          VARCHAR(16)  NULL,
  status        VARCHAR(16)  NOT NULL,
  input         JSON         NULL,
  output        JSON         NULL,
  error_msg     VARCHAR(512) NULL,
  redispatch_count INT NOT NULL DEFAULT 0,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_subtask_uuid (subtask_uuid),
  KEY idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== 产品层:课程录播(与 migrations/2026-11-13-course-video.sql 保持一致) =====
CREATE TABLE IF NOT EXISTS course (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(200) NOT NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lecture (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id   BIGINT        NOT NULL,
  seq         INT           NOT NULL,          -- 课内序号,从 1 起,按添加顺序
  title       VARCHAR(300)  NULL,              -- 用户没填时,摄入完成后用视频自己的标题
  source_url  VARCHAR(1024) NULL,
  source_path VARCHAR(1024) NULL,
  status      VARCHAR(16)   NOT NULL,          -- INGESTING / READY / PARTIAL / FAILED
  media_key   CHAR(32)      NULL,
  duration_ms BIGINT        NULL,
  task_uuid   VARCHAR(36)   NULL,              -- 最近一次摄入任务
  concepts    JSON          NULL,              -- [{"name","startMs","summary"}]
  error       VARCHAR(512)  NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_course_seq (course_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
