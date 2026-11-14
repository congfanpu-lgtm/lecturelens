# 课程录播 · 计划 3：问答 agent、三层核验、问答页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在一门课的范围里提问，agent 自己决定查哪几讲、搜什么、读哪一段，给出一条条结论；每条结论带"第几讲、哪一段时间、原话是什么"的证据，由代码逐条核验，通不过的结论从答案里拿掉并写明原因。

**Architecture:** 一次提问是一个 `COURSE_QA` 任务，两个阶段各是一个 Kafka 子任务：`QA_AGENT`（ReAct 循环，每步存档，worker 死了从断点续跑）→ `QA_VERIFY`（代码做第 1、2 层核验，一次判官模型调用做第 3 层）。被拿掉的结论太多时，带着原因再跑一轮 `QA_AGENT` → `QA_VERIFY`，只补一次。agent 的三个工具（课程概览、检索片段、展开上下文）都绑定在这一门课上，模型没有办法查别的课。核验是单独的一步、由代码执行，agent 绕不过去。

**Tech Stack:** 同计划 2（Java 17 / Spring Boot 的 server 和 worker，Python / FastAPI 的检索服务和媒体服务，原生 HTML + JS 的页面）。

**Spec:** `docs/superpowers/specs/2026-11-13-course-video-qa-design.md` 第 8 节（问答 agent、核验闸门）、第 5 节（问答页）。前两份计划：`2026-11-13-course-video-1-media-service.md`、`2026-11-13-course-video-2-ingest.md`。

**这份计划比前两份写得短**：实现的人就是写计划的人，而且在同一个会话里，所以测试只列出名字和要断言的东西，测试代码在执行时先写。接口、取值和行为仍然在这里定死。

## Global Constraints

- 所有改动在 `lecturelens` 仓库里做；`agentflow` 仓库不再改。提交信息用 Conventional Commits、中文描述，带仓库现行的两行 trailer，每个任务完成后提交并推送到 `main`。
- 所有测试和课程环境都用模拟模型。真实模型是计划 4 里用户的决定。
- 研究 agent（`RESEARCH_AGENT`）的行为不能变：`AgentLoopTest` 等现有测试原样通过。
- 子任务的 `kind` 不超过 16 个字符。JSON 字段 camelCase；对内时间是整数毫秒（`Ms`），给模型看和让模型写的时间用 `分:秒`（模型照着工具结果抄，比让它算毫秒可靠）。
- agent 的工具只读。逐字稿和幻灯片文字是不可信内容：里面即使混进指令，最坏结果是答错，由核验兜住一部分。
- 课程功能仍然由 `agentflow.course.enabled` 控制，默认关闭。
- 在哪里跑什么（和计划 2 一样，目录换成新的）：不需要 Spring 容器的 Java 单元测试在 Mac 上跑；需要 MySQL / Kafka / pgvector 的测试在 Upton 上跑：
  先 `rsync` 到 `~/project/lecturelens-dev`，再 `./scripts/course-env.sh test-up`、`test-java …`、`test-rag …`。整套环境 `./scripts/course-env.sh up`（`127.0.0.1:18090`），里面已经有评测课的 11 讲。
- 开发中遇到需要解决但当时不做的问题，追加到 `docs/open-problems.md`。

## 接口约定（计划 4 依赖这一节）

**提问和取答案（server）**

| 方法和路径 | 请求 | 响应 |
|---|---|---|
| `POST /api/v1/courses/{id}/questions` | `{"question", "history":[{"question","answer"}]（可省，最多 3 轮）, "model"（可省）}` | `{"taskUuid"}` |
| `GET /api/v1/questions/{taskUuid}` | | 见下 |

答案视图：

```json
{"status": "RUNNING | COMPLETED | FAILED",
 "question": "…",
 "notFound": false,
 "claims": [{"text": "…", "evidence": [{"lectureId": 3, "seq": 3, "startMs": 750000, "endMs": 805000, "source": "speech", "quote": "…"}]}],
 "dropped": [{"text": "…", "layer": 2, "reason": "引用的原文在 L3 12:30-13:25 里找不到"}],
 "rounds": 1,
 "toolCalls": [{"tool": "search_segments", "args": "{…}"}],
 "stages": [{"kind": "QA_AGENT", "status": "COMPLETED", "seconds": 12}],
 "usage": {…},
 "error": null}
```

- `claims` 只有通过三层核验的结论；`dropped` 是被拿掉的，`layer` 是在第几层被拿掉的（1 时间不合法、2 引文找不到、3 判官认为原文不支持、0 结论没有带证据）。
- `notFound`：agent 认为课里没讲。此时 `claims` 为空。
- 任务还没结束时 `claims`、`dropped` 为空数组，`stages` 反映进度。

**子任务**

| kind | 输入 | 输出 |
|---|---|---|
| `QA_AGENT` | `{"courseId","question","history","lectures":[{"lectureId","seq","title","durationMs","mediaKey","concepts"}],"feedback":[{"text","reason"}]（补一轮时才有）,"model"}` | `{"question","notFound","note","claims":[{"text","evidence":[{"lectureId","seq","startMs","endMs","source","quote"}]}],"steps","toolCalls","usage"}` |
| `QA_VERIFY` | `{"courseId","question","lectures":[…同上，不带 concepts],"claims":[…QA_AGENT 的输出],"model"}` | `{"claims":[…通过的],"dropped":[{"text","layer","reason"}],"usage"}` |

**agent 的最终输出（模型写的）**

```json
{"notFound": false,
 "claims": [{"text": "一句结论", "evidence": [{"lecture": 3, "at": "12:30-13:25", "source": "speech", "quote": "从工具结果里逐字复制的一句原文"}]}]}
```

`lecture` 是课内序号；`at` 照工具结果里的时间段抄；`source` 是 `speech` 或 `slide`。

**检索服务**：`POST /segments/search` 增加可选的 `fromMs`、`toMs`（只返回和这个时间范围有重叠的片段）。

## Review Focus

1. **引文是模型编的**：结论看起来对，引用的"原话"在那一段里并不存在，或者时间是随手写的。→ 任务 2 的 `EvidenceChecker` 测试；任务 6 `dropsAClaimWhoseQuoteIsNotInTheLecture`。
2. **原话是真的，但不支持结论**（张冠李戴）：→ 任务 6 `dropsAClaimTheJudgeDoesNotAccept`、`judgeSeesOnlyQuotesThatPassedTheTextCheck`。
3. **判官的输出坏了或者漏判**：不是 JSON、少了某条结论的判定。漏判的结论不能算通过。→ 任务 6 `aClaimTheJudgeSkippedIsNotAccepted`、`failsWhenTheJudgeAnswerIsUnusable`。
4. **课里没讲的问题**：应该回答"找不到"，不硬答；全部结论都被拿掉时不能给出一个空白的"成功"。→ 任务 5 `saysNotFoundInsteadOfAnswering`、任务 7 `allClaimsDroppedTwiceEndsAsNotAnswered`。
5. **查到别的课里去**：工具绑定课程，模型传什么参数都只能查到这一门课；序号对不上的讲次要报错给模型，不能悄悄查全课。→ 任务 4 `toolsOnlyReachTheirOwnCourse`、`anUnknownLectureNumberIsAnErrorTheModelCanSee`。
6. **worker 在回答到一半时死掉**：换一个 worker 从存档的那一步接着跑，已经调用过的工具不重跑。→ 任务 3 `resumesACourseRunFromItsCheckpoint`；任务 10 里实际杀一次。

---

### Task 1: 检索可以限定时间范围（agentflow-rag）

**Files:** Modify `agentflow-rag/app/segments.py`、`agentflow-rag/app/main.py`、`agentflow-rag/tests/test_segments.py`

**Interfaces:** `segments.search(course_id, query, k, lecture_ids=None, mode="hybrid", from_ms=None, to_ms=None)`；HTTP 请求体加可选的 `fromMs`、`toMs`（整数，≥ 0）。片段的 `[start_ms, end_ms]` 和 `[from_ms, to_ms]` 有重叠才返回；只给一头时另一头不限。

- [ ] 测试（先失败）：
  - `test_search_can_be_limited_to_a_time_range`：一讲三个片段（0-60、60-120、120-180 秒），`from_ms=70000, to_ms=100000` 三种 mode 都只返回 60-120 那个；只给 `to_ms=30000` 只返回第一个。
  - `test_http_search_accepts_a_time_range`：`fromMs` 传负数 422；正常范围返回的命中都落在范围里。
- [ ] 实现；跑检索服务的全部测试；提交（`feat(rag): 片段检索可以限定时间范围`）。

---

### Task 2: 证据核对（纯函数，核验的第 1、2 层）

**Files:** Create `agentflow-worker/src/main/java/com/agentflow/worker/lecture/EvidenceChecker.java`、`…/test/…/lecture/EvidenceCheckerTest.java`

**Interfaces:**
- `record Evidence(long lectureId, int seq, long startMs, long endMs, String source, String quote)`
- `record Verdict(boolean ok, int layer, String reason)`（`ok` 时 `layer` 为 0、`reason` 为 null）
- `static Verdict check(Evidence e, long durationMs, JsonNode utterances, JsonNode frames)`
- `static String windowText(String source, long fromMs, long toMs, JsonNode utterances, JsonNode frames)`：`speech` 取和时间窗有重叠的 utterance，用空格连接；`slide` 取显示时间（从这一帧到下一帧）和时间窗有重叠的帧的文字，用换行连接。
- 常量：`MARGIN_MS = 15_000`（时间窗前后各放宽这么多）、`MIN_QUOTE_CHARS = 12`、`MAX_WINDOW_MS = 300_000`。

规则：
- 第 1 层：`source` 是 `speech` 或 `slide`；`0 <= startMs <= endMs <= durationMs + MARGIN_MS`；`endMs - startMs <= MAX_WINDOW_MS`；放宽后的时间窗里确实有这种来源的内容（`windowText` 非空）。
- 第 2 层：引文规范化后（`TextUtil.normalize`：小写、统一引号、空白折叠）不少于 `MIN_QUOTE_CHARS` 个字符，并且出现在放宽后时间窗的 `windowText` 的规范化文本里。跨两条 utterance 的引文算通过（文本是连起来的）。
- `reason` 用中文，写明哪一讲、哪个时间段、哪一层的什么问题，例如 `引用的原文在 L3 12:30-13:25 里找不到`。

- [ ] 测试（先失败）：
  - `aQuoteCopiedFromTheTranscriptPasses`（含大小写、多余空白、弯引号不同的情况）。
  - `aQuoteSpanningTwoUtterancesPasses`。
  - `aQuoteThatIsNotInTheWindowFailsAtLayerTwo`：同一讲里别处有这句话，但不在所引的时间段（放宽 15 秒之外）→ 第 2 层。
  - `aQuoteJustOutsideTheWindowButInsideTheMarginPasses`。
  - `aTooShortQuoteFailsAtLayerTwo`（"yes"、空串）。
  - `timesOutsideTheLectureFailAtLayerOne`：`startMs > endMs`、超过时长、负数、窗口超过 5 分钟。
  - `aWindowWithNothingInItFailsAtLayerOne`：那段时间没人说话；`slide` 证据指向没有任何帧的时间。
  - `aSlideQuoteIsCheckedAgainstTheSlideShownAtThatTime`：帧在 20 秒出现、下一帧 40 秒，证据 25-30 秒引用这一帧的文字通过；引用下一帧的文字不通过。
  - `anUnknownSourceFailsAtLayerOne`（`"frame"`、null）。
- [ ] 实现；跑测试；提交（`feat(worker): 证据核对:时间是否合法、引文是否真的在那一段里`）。

---

### Task 3: 把 ReAct 循环从研究 agent 里拆出来

现在 `AgentLoop` 把循环（续跑、调工具、存档、步数上限）和研究 agent 的东西（提示词、全部工具、按 URL 核对引用）写在一起。

**Files:** Modify `agentflow-worker/src/main/java/com/agentflow/worker/agent/AgentLoop.java`；Create `…/test/…/agent/AgentLoopCoreTest.java`

**Interfaces:**
- `record Profile(String systemPrompt, List<AgentTool> tools, int maxTokens)`（嵌套在 `AgentLoop` 里）。
- `record Outcome(String content, int stepCount, Usage usage, List<AgentStep> history)`：`content` 是模型最后的回答原文；`history` 是全部已完成的步骤，含续跑前存档的。
- `public Outcome converse(AgentRun run, Profile profile, List<ChatMessage> seed)`：`seed` 是 system 之后、循环之前的消息（问题、历史对话、补一轮时的反馈）。行为和现在的循环一样：先放回存档的步骤，每步完整结束后存档并发 `AGENT_STEP` trace，同名同参数的工具调用走缓存，工具出错以 `ERROR: …` 回给模型，超过 `MAX_STEPS` 去掉工具强制作答。
- `run(AgentRun)`（研究 agent）改成调 `converse`，再做它自己的按 URL 核对；输出不变。

- [ ] 测试（先失败）：
  - `usesTheToolsAndPromptOfTheProfile`：给一个只有假工具 `echo` 的 profile，模型（mock 的 `LlmGateway`）调用 `echo` 后作答；断言发给模型的 system 是 profile 的、工具列表只有 `echo`、`maxTokens` 是 profile 的；研究 agent 的工具（`web_search` 等）不在里面。
  - `resumesACourseRunFromItsCheckpoint`：存档里已有第 1 步（含一次工具调用和结果），`converse` 不再调用那个工具，直接从第 2 步继续，`history` 里有两步。
  - `forcesAnAnswerAfterTheStepLimit`：模型一直调工具，第 `MAX_STEPS + 1` 次请求不带工具。
- [ ] 实现；跑 `AgentLoopCoreTest` 和原有的 `AgentLoopTest`、`AgentProcessorTest`（必须原样通过）；提交（`refactor(worker): ReAct 循环和研究 agent 的提示词、工具、引用核对分开`）。

---

### Task 4: 课程的三个工具

**Files:** Create `agentflow-worker/src/main/java/com/agentflow/worker/lecture/CourseTools.java`、`…/test/…/lecture/CourseToolsTest.java`；Modify `…/rag/RagClient.java`（加 `searchSegments`）、`…/test/…/rag/RagClientTest.java`

**Interfaces:**
- `RagClient.searchSegments(long courseId, String query, int topK, List<Long> lectureIds, Long fromMs, Long toMs) -> List<SegmentHit>`；`record SegmentHit(String id, long lectureId, long startMs, long endMs, String kind, String text, double score)`。
- `CourseTools`（`@Component`，构造参数 `(RagClient, MediaClient)`）：`List<AgentTool> forCourse(long courseId, JsonNode lectures)`。返回的三个工具**不是** Spring bean（否则会被收进研究 agent 的工具表），每次提问新建，课程 id 和讲次表在闭包里：

| 工具 | 参数 | 返回给模型的文本 |
|---|---|---|
| `course_overview` | 无 | 每讲一行 `L<序号> <标题> (<时长>)`，下面缩进列出知识点 `  <分:秒> <名字>:<一句话>`。没有知识点目录的讲次只有标题。 |
| `search_segments` | `query`（必填）、`lectures`（序号数组，可省）、`from`、`to`（`分:秒`，可省，要和单个讲次一起用） | 最多 6 条，每条一块、块之间空行：第一行 `[L<序号> <分:秒>-<分:秒> <speech\|slide>]`，下面是片段文字（每条最多 700 字）。没有命中返回 `这门课里没有找到相关内容:<query>`。 |
| `read_context` | `lecture`（序号）、`from`、`to`（`分:秒`） | 这个时间窗（最长 3 分钟，超出截到 3 分钟）里的逐字稿，每条一行 `<分:秒> <文字>`；如果这段时间有幻灯片，后面加 `--- 幻灯片 <分:秒> ---` 和文字。 |

- 序号不在这门课里、时间格式不对、`to` 早于 `from`：抛 `IllegalArgumentException`（循环会把它变成 `ERROR: …` 回给模型）。错误信息里列出这门课有哪些序号。
- 时间格式 `分:秒`，分钟可以超过 59，也接受 `时:分:秒`。

- [ ] 测试（先失败，`RagClient`、`MediaClient` 用 Mockito）：
  - `overviewListsLecturesAndTheirConcepts`。
  - `searchFormatsHitsWithLectureNumberAndTimeRange`：断言文本里有 `[L3 12:30-13:25 speech]`，并且传给 `searchSegments` 的 `courseId` 是绑定的那个。
  - `toolsOnlyReachTheirOwnCourse`：模型在参数里塞 `courseId`、`lectureId` 等字段都被忽略；`lectures:[3]` 被翻译成这门课里序号 3 对应的 `lectureId`。
  - `anUnknownLectureNumberIsAnErrorTheModelCanSee`：`lectures:[99]` 抛异常，消息里有可用的序号；不会退化成查全课。
  - `searchWithATimeRangePassesItOn`：`from:"10:00", to:"12:30"` → `fromMs=600000, toMs=750000`。
  - `readContextReturnsTheTranscriptOfTheWindowAndItsSlides`、`readContextIsCappedAtThreeMinutes`。
  - `RagClientTest.searchSegmentsSendsTheFiltersAndParsesHits`。
- [ ] 实现；跑测试；提交（`feat(worker): 问答 agent 的三个工具,绑定在一门课上`）。

---

### Task 5: `QA_AGENT`：在一门课里找答案

**Files:** Create `…/lecture/CourseQaProcessor.java`、`…/test/…/lecture/CourseQaProcessorTest.java`；Modify `…/agent/Prompts.java`（`COURSE_QA_TAG = "[course-qa]"`、`COURSE_QA`）、`…/llm/MockLlmClient.java`、`…/test/…/llm/MockLlmClientTest.java`

**Interfaces:**
- `CourseQaProcessor`（`@Component`，`type() = "QA_AGENT"`，走 `process(SubtaskMessage)`，因为要用 `subtaskUuid` 存档）。`MAX_TOKENS = 4000`（跨讲次的答案带多条引文，1600 会被截断）。
- 发给模型：system = `Prompts.system(Prompts.COURSE_QA, question)`；seed = 最近的历史对话（每轮一条 user 一条 assistant，答案截到 500 字）+ 这次的问题；补一轮时再加一条 user：列出上一轮被拿掉的结论和原因，要求只保留能找到原文的结论。
- 提示词要点：先看课程概览决定查哪几讲，再检索，必要时展开上下文；每条结论必须带证据，`quote` 从工具结果里逐字复制、不要改写或翻译，`at` 照工具结果里的时间段抄；课里没讲就输出 `{"notFound":true,"claims":[]}`，不要用自己的知识回答；用和问题相同的语言。
- 解析最终输出：`lecture` 序号翻译成 `lectureId`（不认识的序号 → 这条证据丢掉）；`at` 解析成 `startMs`/`endMs`（解析不了 → 这条证据丢掉）；没有证据的结论保留、`evidence` 为空（核验时在第 0 层拿掉）。输出不是 JSON：整段当作一条没有证据的结论。`claims` 最多 12 条、每条最多 4 条证据。
- 输出见"接口约定"；`toolCalls` 从 `Outcome.history` 里按顺序取（工具名和参数）。
- `MockLlmClient`：system 以 `COURSE_QA_TAG` 开头时——还没有工具结果：调用 `search_segments`，`query` 是问题里的关键词；已有工具结果：从第一块命中（`[L<n> <at> <source>]` 加文字）里取前 80 个字符当引文，输出一条结论 `MOCK 结论:第 n 讲在 <at> 提到 …`；工具结果是"没有找到"：输出 `{"notFound":true,"claims":[]}`。问题里含 `UNSUPPORTED` 时结论文本带上这个词（给判官的测试用）；含 `FABRICATE` 时引文换成一句工具结果里没有的话（给第 2 层的测试用）。

- [ ] 测试（先失败；`AgentLoop` 用真的，`LlmGateway`、`AgentCheckpoint`、`RagClient`、`MediaClient` 用 mock）：
  - `turnsTheModelsAnswerIntoClaimsWithEvidenceInMilliseconds`：模型输出 `lecture:3, at:"12:30-13:25"` → `lectureId` 是输入里序号 3 的那个、`startMs=750000`、`endMs=805000`。
  - `saysNotFoundInsteadOfAnswering`：模型输出 `notFound:true` → 输出 `notFound=true`、`claims` 为空。
  - `evidenceWithAnUnknownLectureOrBadTimeIsDropped`，结论本身保留。
  - `anAnswerThatIsNotJsonBecomesOneClaimWithoutEvidence`。
  - `theSecondRoundShowsTheModelWhatWasRejectedAndWhy`：输入带 `feedback` 时，发给模型的消息里有被拿掉的结论和原因。
  - `historyIsSentBeforeTheQuestion`。
  - `reportsWhichToolsWereCalled`。
  - `MockLlmClientTest`：三种情况（先搜、有结果后作答且引文确实在工具结果里、没找到）。
- [ ] 实现；跑测试；提交（`feat(worker): 课程问答 agent:结论带讲次、时间段和原文`）。

---

### Task 6: `QA_VERIFY`：三层核验

**Files:** Create `…/lecture/CourseVerifyProcessor.java`、`…/test/…/lecture/CourseVerifyProcessorTest.java`；Modify `…/agent/Prompts.java`（`JUDGE_TAG = "[judge]"`、`JUDGE`）、`…/llm/MockLlmClient.java`、`MockLlmClientTest.java`

**Interfaces:**
- `CourseVerifyProcessor`（`@Component`，`type() = "QA_VERIFY"`，构造参数 `(MediaClient, LlmGateway, LlmModelProperties)`）。
- 流程：
  1. 每条证据过 `EvidenceChecker.check`（逐字稿和帧列表按 `mediaKey` 取，一次调用里同一讲只取一次；帧列表取不到当作没有幻灯片）。不通过的证据拿掉，记下原因。
  2. 没带证据的结论 → `dropped`，`layer=0`，原因"没有给出证据"。证据全被拿掉的结论 → `dropped`，`layer` 和 `reason` 取它第一条证据失败的那一层。
  3. 剩下的结论一起交给判官（一次模型调用）：每条结论列出通过了文字核对的引文，引文前后各带 `MARGIN_MS` 的上下文（`EvidenceChecker.windowText`，最多 600 字）。判官只回答原文是否支持结论。
  4. 判官输出 `{"verdicts":[{"claim":<序号>,"supported":true|false,"reason":"一句话"}]}`。`supported=false` → `dropped`，`layer=3`。**判官没有判到的结论不算通过**：`dropped`，`layer=3`，原因"判官没有给出判定"。输出不是 JSON 或没有 `verdicts` → `IllegalStateException`（重试）。
  5. 没有结论需要判时（全被前两层拿掉，或者 `claims` 本来就空）不调用判官。
- 判官的提示词要点：只根据给出的原文判断，不用自己的知识；原文只是提到相关话题但没有说出结论里的内容，算不支持；结论比原文说得更强（原文说"可能"，结论说"一定"）算不支持。
- `MockLlmClient`：system 以 `JUDGE_TAG` 开头时，对 user 消息里出现的每个结论序号给出判定：结论文本含 `UNSUPPORTED` → `supported=false`，原因 `MOCK 判官:原文没有说到这一点`；其余 `true`。
- 输出见"接口约定"；`usage` 是判官那次调用的用量（没调用时为零）。

- [ ] 测试（先失败；`MediaClient`、`LlmGateway` 用 mock）：
  - `keepsAClaimWhoseQuoteIsThereAndTheJudgeAccepts`。
  - `dropsAClaimWhoseQuoteIsNotInTheLecture`（`layer=2`）；`dropsAClaimWithATimeOutsideTheLecture`（`layer=1`）；`dropsAClaimWithoutEvidence`（`layer=0`）。
  - `aClaimKeepsOnlyItsEvidenceThatPassed`：两条证据一真一假 → 结论保留，只带真的那条。
  - `dropsAClaimTheJudgeDoesNotAccept`（`layer=3`，原因是判官给的）。
  - `judgeSeesOnlyQuotesThatPassedTheTextCheck`：编的引文不出现在发给判官的消息里。
  - `aClaimTheJudgeSkippedIsNotAccepted`。
  - `failsWhenTheJudgeAnswerIsUnusable`（不是 JSON；`verdicts` 不是数组）。
  - `theJudgeIsNotCalledWhenNothingIsLeftToJudge`。
  - `eachLectureIsFetchedOnce`：三条证据指向同一讲，`media.transcript` 只调用一次。
  - `MockLlmClientTest`：判官分支。
- [ ] 实现；跑测试；提交（`feat(worker): 问答的三层核验:时间、引文、判官`）。

---

### Task 7: `COURSE_QA` 的阶段推进和提问接口

**Files:** Create `agentflow-server/src/main/java/com/agentflow/server/coordinator/CourseQaDecomposer.java`、`…/test/…/coordinator/CourseQaDecomposerTest.java`、`…/test/…/service/CourseQaFlowTest.java`；Modify `…/course/CourseController.java`、`…/course/LectureService.java`（或新建 `QuestionService`）、`…/test/…/course/CourseApiTest.java`

**Interfaces:**
- `CourseQaDecomposer`（`TYPE = "COURSE_QA"`，kind：`QA_AGENT`、`QA_VERIFY`；构造参数 `(ObjectMapper, CourseMapper, LectureMapper)`）：
  - `decompose(payload {courseId, question, history?, model?})`：课程不存在、问题为空或超过 500 字、这门课没有 `READY`/`PARTIAL` 的讲次 → `IllegalArgumentException`。`history` 最多留最后 3 轮。建一个 `QA_AGENT`（seq 0），输入里的 `lectures` 是这门课 `READY`/`PARTIAL` 的讲次（按序号排，带 `concepts`）。
  - `nextStage`：`QA_AGENT` 成功 → `QA_VERIFY`（带 agent 的 `claims`；agent 说 `notFound` 或者没有结论时不建核验，任务结束）。`QA_VERIFY` 成功后，如果这是第一轮、被拿掉的结论不少于 1 条且超过总数的三分之一 → 再建一个 `QA_AGENT`（输入加 `feedback`：被拿掉的结论和原因）→ 它成功后再建 `QA_VERIFY`。第二轮之后不再补。任何一步失败 → 结束。
  - `terminalStatus`：最后一个落定的子任务成功 → `COMPLETED`；否则 `FAILED`。
- 提问：`POST /api/v1/courses/{id}/questions` → `TaskSubmitService.submit("COURSE_QA", …)` → `{"taskUuid"}`；请求不对 400，课程不存在 404。
- 取答案：`GET /api/v1/questions/{taskUuid}`：任务不存在或者不是 `COURSE_QA` → 404。视图见"接口约定"，由子任务的输出拼出来：`claims`/`dropped` 取最后一个成功的 `QA_VERIFY`；没有核验（`notFound`）时取最后一个 `QA_AGENT` 的 `notFound`；`rounds` 是成功的 `QA_AGENT` 个数；`toolCalls` 是各轮 agent 的合在一起；`usage` 是各步之和；`error` 是失败步骤的 `kind: 原因`。
- **全部结论两轮都被拿掉**时：`status=COMPLETED`、`claims` 为空、`dropped` 有内容、`notFound=false`。页面据此显示"没有能通过核验的结论"，不是一片空白。

- [ ] 测试（先失败）：
  - `CourseQaDecomposerTest`（单元，mapper 用 Mockito）：`decomposeBuildsTheAgentInputFromReadyLectures`（`INGESTING`、`FAILED` 的讲次不在里面）、`decomposeRejectsBadQuestionsAndEmptyCourses`、`verifyFollowsTheAgent`、`notFoundEndsWithoutVerification`、`tooManyDroppedClaimsTriggerOneMoreRound`（3 条里拿掉 2 条 → 第二轮，`feedback` 里是那 2 条）、`aFewDroppedClaimsDoNotTriggerAnotherRound`（4 条里拿掉 1 条）、`thereIsNoThirdRound`、`aFailedStepFailsTheTask`。
  - `CourseQaFlowTest`（Spring + 真实的库，经 `ResultHandleService`）：`agentThenVerifyThenTheAnswerIsReadable`、`allClaimsDroppedTwiceEndsAsNotAnswered`（走完两轮，答案视图 `claims` 空、`dropped` 非空、`rounds=2`）。
  - `CourseApiTest`：`askingAQuestionStartsACourseQaTask`、`anAnswerInProgressShowsItsStages`、`questionsNeedAnExistingCourseWithLectures`（404 / 400）。
- [ ] 实现；Mac 上跑单元测试，Upton 上跑 server 的全部测试；提交（`feat(server): 课程问答任务:agent、核验、必要时补一轮;提问和取答案的接口`）。

---

### Task 8: 问答页

**Files:** Create `agentflow-server/src/main/resources/static/qa.html`；Modify `courses.html`、`lecture.html`（顶部加到问答页的链接）

- 单文件、原生 JS，外来文字只走 `textContent`。地址 `qa.html?course=<id>`。
- 左边：问题输入框和"提问"按钮；下面是这次的回答。回答里每条结论一行，后面跟证据标签（`L3 · 12:30`，通过核验的带 ✓）。被拿掉的结论排在后面，划掉，下面一行小字写原因和是第几层拿掉的。`notFound` 时显示"这门课里没有讲到这个问题"。全部被拿掉时显示"没有能通过核验的结论"，下面照样列出被拿掉的。
- 回答下面一行小字：检索路径（依次调用了哪些工具、参数是什么）、轮数、token 用量。
- 运行中：显示各步骤的状态（`找答案`、`核验`，第二轮时再出现一组），每 2 秒刷新。
- 右边：点一个证据标签，播放器（YouTube IFrame API）切到那一讲并跳到那一秒，下面显示这条证据的原文和它属于哪条结论。讲次的 `sourceUrl` 从 `GET /api/v1/courses/{id}` 取。
- 涉及两讲以上的回答，回答上方多一个"按讲次对比"的切换：一行一讲，列出这一讲的结论和证据。
- 追问：页面记住本次打开以来的问答（最多 3 轮），随下一次提问发给接口；有"清空对话"按钮。

- [ ] 写页面；`grep innerHTML` 无输出；`node --check` 通过；提交（`feat(ui): 问答页:结论带证据标签,点了跳到视频的那一秒`）。任务 10 里对着真实数据在浏览器里验证。

---

### Task 9: 知识点目录也要有原文（`docs/open-problems.md` Q1）

**Files:** Modify `…/lecture/LecConceptsProcessor.java`、`…/agent/Prompts.java`（`CONCEPTS`）、`…/llm/MockLlmClient.java`、对应的测试

- 提示词改成每个知识点多给一个 `quote`：从它所在的那个片段里逐字复制的一句话。
- `LecConceptsProcessor`：`quote` 规范化后不少于 `EvidenceChecker.MIN_QUOTE_CHARS` 个字符，并且出现在所引片段的文字里，才保留这个知识点。输出的每个知识点多一个 `quote` 字段。
- 模拟模型：`quote` 取那一行文字的前 40 个字符。

- [ ] 测试（先失败）：`dropsAConceptWhoseQuoteIsNotInItsSegment`、`keepsTheQuoteOfAConceptThatPassed`；原有的 `LecConceptsProcessorTest` 用例里的模型输出补上 `quote`。
- [ ] 实现；跑测试；提交（`feat(worker): 知识点目录的每一条都要有能在片段里找到的原文`）。

---

### Task 10: 在 Upton 上端到端跑通，记录

**Files:** Modify `docs/benchmarks.md`（第 9 节 "Course Q&A"）、`README.zh.md`、`README.md`（状态表）、`docs/open-problems.md`

- [ ] 同步、重建整套环境（数据保留：11 讲还在）。
- [ ] 用模拟模型问三个问题，逐个看答案视图：
  1. 课里有的内容（比如 `reinforcement learning from human feedback`）→ `COMPLETED`，有一条通过核验的结论，证据的时间段确实包含那句原文（到媒体服务的逐字稿里核对一次）。
  2. 问题里带 `FABRICATE` → 引文是编的，结论在第 2 层被拿掉；触发第二轮；最后 `claims` 为空、`dropped` 非空、`rounds=2`。
  3. 问题里带 `UNSUPPORTED` → 结论在第 3 层被拿掉，原因是判官给的。
- [ ] 杀 worker：提一个问题，agent 第一步存档之后 `docker kill` 正在跑它的那个 worker，确认另一个 worker 从存档续跑（trace 里有 `RESUMED`，工具没有重跑），任务照常完成。记下从杀掉到恢复用了多久。
- [ ] 在浏览器里看问答页：提问、看结论和证据标签、点证据跳视频、被拿掉的结论的显示、按讲次对比、追问；控制台没有报错。截图。
- [ ] 重新生成一讲的知识点目录（任务 9 之后），确认每条都带 `quote`。
- [ ] 把结果写进 `docs/benchmarks.md` 第 9 节（注明是模拟模型，只说明流程和核验的行为，不代表答案质量）；更新 README 的状态表；`docs/open-problems.md` 里 Q1 改成已解决，补上这次遇到的新问题。提交并推送。
