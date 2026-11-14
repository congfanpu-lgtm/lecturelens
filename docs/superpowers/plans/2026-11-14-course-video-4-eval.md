# 课程问答评测(计划 4)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把设计文档第 11 节的评测做成可以一条命令重跑的东西:题集、四个被比较的系统、打分、统计、判官校准、故障演练,结果进 `docs/benchmarks.md`。

**Architecture:** 被比较的系统都是同一个 `COURSE_QA` 任务的不同跑法(payload 里的 `eval` 选项),所以它们共用工具、核验、记账和 trace。评测脚本是仓库 `eval/course/` 下的标准库 Python:提交任务、存下原始结果、离线打分。打分分两类:不调模型的(证据定位、引文忠实、拒答、步数、token、延迟)和要调模型的(答对率,由打分模型对照要点判)。

**Tech Stack:** 现有的引擎和服务;Python 3 标准库(和 `eval/run_eval.py`、`scripts/fault_matrix.py` 一样不加依赖)。

**Spec:** `docs/superpowers/specs/2026-11-13-course-video-qa-design.md` 第 11 节(评测)、第 9 节(成本)。

## 这份计划分两段

- **第一段(任务 1 到 7):不需要真实模型,也不需要用户动手。** 用模拟模型把整条评测链路跑通,把接真实模型之前必须修的几处修掉,把不依赖模型的故障演练做完。
- **第二段(任务 8 到 10):需要用户。** 真实模型用哪个、key 怎么放到服务器上、预算多少,是用户的决定;跨讲次题要用户自己写,单讲题要用户改写核对,判官校准要用户标 100 对。第一段做完后,这三件事的材料和命令都准备好,用户的工作量就是设计文档里估的 4 到 6 小时。

## Global Constraints

- 课程环境固定用模拟模型;真实模型只在用户明确给出型号和 key 之后用,key 不进仓库、不写进任何提交。
- 题集在看任何系统的结果之前冻结:冻结时记下题集文件的 SHA-256,每次运行的结果里带着它,打分时核对。
- 评测课没有查到明确的 CC 授权:报告和仓库里只放数字、简短引用和原视频链接,不放大段转写。题集里的"标准答案要点"是用户自己写的话,不是转写原文。
- 只写置信区间分得开的结论;分不开的写"没有显著差别"。事先写下的三条预期(设计文档第 11 节)跑之前不改。
- 四个系统的输出格式相同(结论 + 证据),走同一个核验步骤,这样指标才可比。
- 环境约束照旧:所有运行在 Upton 服务器上;不动服务器上别的服务;临时数据用完清掉。

## Review Focus

1. **评测选项被普通用户用上**:`eval` 选项会让一次提问读整门课的转写(每问几十万 token)。页面上的提问接口不能带它;通用任务接口能带,但选项必须校验,不认识的值拒绝。→ 任务 2。
2. **长上下文基线撑爆上限**:整门课的转写超过模型上下文时,要明确失败并说明原因,不能悄悄截断成"只看了前几讲"。→ 任务 2 `longContextRefusesWhenTheCourseDoesNotFit`。
3. **判官的编号对不上**:结论用字符串 id,判定按 id 对应;缺的、重复的、不认识的 id 都不能让结论蒙混过去。→ 任务 1。
4. **运行中断后重跑**:跑了一半的评测再跑一次,已经有结果的题不重跑、不重复计费;同一题同一系统同一轮只有一条结果。→ 任务 4 `aRunCanBeResumed`。
5. **题集被改过**:结果是用另一版题集跑的,打分时要拒绝,不能把两版混在一起算。→ 任务 5 `scoringRefusesAnotherVersionOfTheQuestions`。
6. **标准区间和引用区间的重叠判定**:只沾边 1 秒算不算命中、一条结论多条证据、一题多个标准区间、证据在别的讲次。→ 任务 5(判定规则写在接口约定里)。

---

## 接口约定

**`COURSE_QA` 的评测选项**(payload 里的 `eval` 对象,省略时就是现在的行为)

| 字段 | 取值 | 含义 |
|---|---|---|
| `eval.mode` | `agent`(默认)/ `rag` / `long` | `rag`:不用 agent,用问题检索一次(8 个片段)→ 一次模型调用作答(S2)。`long`:不检索,把这门课全部讲次的逐字稿(带讲次和时间标记)放进一次模型调用(B1) |
| `eval.judge` | `true`(默认)/ `false` | `false`:核验只做前两层,不调判官(S3) |
| `eval.repair` | `true`(默认)/ `false` | `false`:不补一轮(`rag`、`long` 固定为 false) |

- 三种 mode 的最终输出是同一个格式(`claims` + `evidence`),都进同一个 `QA_VERIFY`。
- `long` 的逐字稿由 worker 从媒体服务取,不经过 Kafka 和 MySQL。总长度超过 `course.long-context-max-chars`(默认 800 000 字符)时这一步直接失败(不重试),错误里写明实际长度。
- 页面的提问接口(`POST /courses/{id}/questions`)不接受 `eval`;评测脚本走通用的任务提交接口。

**判官的输入输出**(任务 1 之后)

- 输入是一个 JSON:`{"question":"…","claims":[{"id":"c1","text":"…","evidence":[{"where":"L3 12:30-13:25","context":"…","quote":"…"}]}]}`。
- 输出:`{"verdicts":[{"id":"c1","supported":true,"reason":"一句话"}]}`。按 `id` 对应;输出里有不认识的 `id` → 整份作废(这一步重试);同一个 `id` 判了两次,有一次不支持就算不支持;缺了的 `id` 算没通过。

**题集** `eval/course/questions.jsonl`,一行一题:

```json
{"id":"x07","kind":"cross","question":"…","points":["要点一","要点二"],
 "gold":[{"lecture":3,"from":"12:30","to":"14:10"},{"lecture":5,"from":"40:00","to":"41:30"}],
 "author":"user","note":"可省"}
```

- `kind`:`single`(单讲)/ `cross`(跨讲次)/ `absent`(课里没讲,`points` 和 `gold` 为空)。`lecture` 是讲次序号。
- 冻结:`python3 eval/course/questions.py freeze` 校验后写 `eval/course/questions.lock`(题数、各类题数、文件的 SHA-256)。

**一次运行的结果** `eval/course/runs/<name>/results.jsonl`,一行是一题一系统一轮:

```json
{"qid":"x07","system":"S1","round":1,"questionsSha":"…","taskUuid":"…","status":"COMPLETED",
 "seconds":41.2,"answer":{…GET /questions/{uuid} 的整个视图…}}
```

- 四个系统:`S1` = 默认;`S2` = `mode: rag`;`S3` = `judge: false`;`B1` = `mode: long, judge: false`。
- 同目录的 `meta.json` 记:课程 id、模型名、开始时间、题集 SHA、各系统的 payload 选项。

**不调模型的指标**(`eval/course/score.py`,每题每系统每轮算一次,再汇总)

| 指标 | 算法 |
|---|---|
| 证据定位命中 | 这一题通过核验的证据里,至少有一条和某个标准区间在同一讲、时间重叠不少于 5 秒(或不少于较短那一段的一半)→ 命中。`absent` 题不算 |
| 引文忠实率 | (这一题模型给出的证据条数 − 在第 1、2 层被拿掉的结论数)/ 给出的结论数;汇总时按结论数加权 |
| 无证据结论占比 | 被第 0 到 3 层拿掉的结论数 / 模型给出的结论数(S3、B1 没有第 3 层,单独标注) |
| 拒答 | `absent` 题:`notFound` 为真或者没有通过核验的结论 → 正确拒答。其余题:`notFound` 为真 → 错误拒答 |
| 步数、token、成本、延迟 | 取自视图的 `toolCalls`、`usage`、`stages`;报告中位数和 p90,步数另给分布 |

**要调模型的指标**:答对率。打分模型拿到问题、要点、系统的答案(通过核验的结论连成的文字),逐个要点判 0 / 1,这一题的分数是命中的要点占比;`absent` 题按"正确拒答"算 1 或 0。打分的输入输出、打分模型的名字都存进 `grades.jsonl`,人工抽查用。

**整体审查之后改过的口径**(2026-11-14,原因见 `docs/benchmarks.md` 第 11 节之后的说明和账本):
- 答案视图多了 `verifications`:每一轮核验的明细(留下几条、被拿掉的清单、证据提出几条通过几条)。"被拿掉的占比"和"引文忠实率"
  把各轮加起来算——只看最后一轮的话,补过一轮的系统会显得更干净。引文忠实率按证据条数算。
- "被拿掉的占比"拆成两项:证据站不住(第 0 到 2 层,各系统可比)和被判官拿掉(第 3 层,只有带判官的系统有)。
  没有判官的系统另外用 `calibrate.py audit` 让判官事后看它答案里的结论,得到"答案里被判官否掉的占比"。
- 没跑成的运行在命中类指标上按 0 算;答对率的分数跟着那一次运行(taskUuid)记。
- 跨讲次题多一项"每一讲都命中";标准区间一段最长 6 分钟。
- 配对比较在跨讲次题上单独做一遍;两边都有的题不到 10 道不下结论;事先写下的预期对应的比较标出来,其余是探索性的。
- 判官校准的三个数按实际运行里判官放行和否掉的比例加权;判官看到的原文存在证据里,给标注的人看的就是它。
- 固定检索流水线取 8 个片段;题目用课程的语言(英文)。
- 题集必须冻结才能跑,运行开始时抄进运行目录;报告里有输入输出 token、美元(按给的单价)的中位数和 p90。

**统计**:同一套题上两两配对,对每题的差值做 10 000 次 bootstrap,给 95% 置信区间;跨三轮的波动单独报(每轮的均值和它们的极差)。

---

### Task 1: 接真实模型之前必须修的四处

**Files:** `agentflow-worker/…/lecture/CourseVerifyProcessor.java`、`LecConceptsProcessor.java`、`CourseTools.java`、`agent/Prompts.java`、`agent/AgentLoop.java`(或 `TextUtil`)、`llm/MockLlmClient.java`、对应测试;`docs/open-problems.md`(Q2、Q9、Q10、Q12 的状态)

- [ ] **Step 1: 写失败的测试**
  - `CourseVerifyProcessorTest`:`theJudgeGetsJsonSoThatContentCannotFakeStructure`(结论或问题里有换行、`\r`、U+2028、"结论 0:"、引号,判官收到的仍是能解析的 JSON,每条结论的 `text` 原样在里面);`verdictsAreMatchedById`(判官按任意顺序返回、id 是字符串);`anUnknownVerdictIdVoidsTheAnswer`;现有的编号相关测试改成 id 的写法后仍然成立(重复判定取不支持、缺的不算通过)。
  - `LecConceptsProcessorTest.asksForEnoughTokensForThirtyConceptsWithQuotes`:请求里的输出上限不低于 6000。
  - `CourseToolsTest.aCutOffResultSaysSo`:结果超过上限时,末尾有一句"后面还有,缩小范围再查"的提示(不只是省略号);`readContextLabelsSlidesAsSlides`:幻灯片文字前的标记里有 `slide`,模型照抄来源时不会把它当成语音。
  - `MockLlmClientTest`:判官分支读新的 JSON 输入。
- [ ] **Step 2: 跑,确认失败。** `mvn -q -pl agentflow-worker -am test -Dtest='CourseVerifyProcessorTest,LecConceptsProcessorTest,CourseToolsTest,MockLlmClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 实现**,跑 worker 的全部单元测试。
- [ ] **Step 4: 提交。**

### Task 2: 评测选项:固定检索流水线、长上下文、不调判官

**Files:** `agentflow-server/…/coordinator/CourseQaDecomposer.java`、`course/QuestionService.java`、`agentflow-worker/…/lecture/CourseQaProcessor.java`、`CourseVerifyProcessor.java`、`agent/Prompts.java`、`llm/MockLlmClient.java`、对应测试

**Interfaces — Produces:** 接口约定里的 `eval` 选项。`QA_AGENT` 的输入多 `mode`;`QA_VERIFY` 的输入多 `judge`。

- [ ] **Step 1: 写失败的测试**
  - `CourseQaDecomposerTest`:`evalOptionsArePassedOnAndValidated`(`mode: rag` 进 `QA_AGENT` 的输入;不认识的 mode、不是布尔的 judge → `IllegalArgumentException`);`withoutTheJudgeVerifyIsToldSo`;`ragAndLongNeverGetARepairRound`;`theQuestionEndpointIgnoresEvalOptions`(`QuestionService.ask` 的 body 里带 `eval` 也不会进 payload)。
  - `CourseQaProcessorTest`:`ragModeSearchesOnceAndAnswersInOneCall`(检索 1 次、模型调用 1 次、请求里没有工具,输出仍是结论 + 证据);`longModePutsEveryTranscriptInOneCall`(每一讲的逐字稿都在消息里,带 `[L3 12:30]` 这样的标记;不检索);`longContextRefusesWhenTheCourseDoesNotFit`(超过上限 → `NonRetryableException`,消息里有实际长度)。
  - `CourseVerifyProcessorTest.withoutTheJudgeLayerThreeIsSkipped`(`judge: false`:不调用模型,通过前两层的结论全部保留,`usage.calls` 为 0)。
- [ ] **Step 2: 跑,确认失败。**
- [ ] **Step 3: 实现。** `rag` 和 `long` 复用 `AgentLoop.converse`(不给工具,循环就是一次调用)和现有的输出解析。
- [ ] **Step 4: 服务器上跑全套 Java 测试;模拟模型下四种跑法各提一个问题,都拿到答案视图。提交。**

### Task 3: 题集的格式、校验、冻结

**Files:** `eval/course/questions.py`、`eval/course/questions.example.jsonl`(3 道示例题,明确标注是示例)、`eval/course/README.md`

- [ ] **Step 1: 写失败的自检**(`python3 eval/course/questions.py --self-check`,和仓库里别的脚本一样用断言):缺字段、`kind` 不认识、`gold` 的讲次不在课程里、时间超出这一讲的长度、`from` 晚于 `to`、`absent` 题带了 `gold`、id 重复 → 各报出是哪一题哪一项;`freeze` 写出的 SHA 在文件改一个字后会变;`check` 在 lock 和文件不一致时退出码非 0。
- [ ] **Step 2: 实现** `check <课程 id>`(对着课程环境核对讲次和时长)、`freeze`、`--self-check`。
- [ ] **Step 3: 提交。**

### Task 4: 运行器

**Files:** `eval/course/run.py`

- [ ] **Step 1: 自检用例**:`aRunCanBeResumed`(结果文件里已有的 (qid, system, round) 不再提交);`aFailedQuestionIsRecordedNotDropped`(任务失败或超时也写一行,`status` 说明);`theQuestionsShaIsOnEveryRow`;`eachSystemSendsItsOwnOptions`。
- [ ] **Step 2: 实现。** `python3 eval/course/run.py --name <名字> --course <id> --systems S1,S2,S3 --rounds 3 [--limit N] [--concurrency 2] [--model <名字>] [--questions <文件>]`:走通用任务接口提交 `COURSE_QA`,轮询 `GET /questions/{uuid}`,结果逐行追加(写一行 flush 一次)。
- [ ] **Step 3: 用模拟模型和示例题在课程环境里跑一遍**(S1、S2、S3、B1 各 1 轮),中途 Ctrl-C 再跑,确认不重跑。提交。

### Task 5: 打分和统计

**Files:** `eval/course/score.py`

- [ ] **Step 1: 自检用例**(纯函数,手写的小数据):重叠判定的几种边界(同讲重叠 5 秒命中、只沾边 1 秒不命中、短区间重叠过半命中、不同讲不命中、多个标准区间命中一个就算);引文忠实率和无证据占比的分母;拒答的四种情况;bootstrap 在两组完全相同的数据上区间包含 0、在差别明显的数据上不包含;`scoringRefusesAnotherVersionOfTheQuestions`。
- [ ] **Step 2: 实现**:`score.py <运行目录>` 输出 `summary.json` 和 `summary.md`(每个系统一行的指标表、系统两两的差值和置信区间、三轮的波动、步数分布)。答对率一列在有 `grades.jsonl` 时才出现。
- [ ] **Step 3: 对任务 4 的模拟运行结果出一份报告**(只说明链路通了,数字没有意义,报告里写明)。提交。

### Task 6: 答对率打分和判官校准的工具

**Files:** `eval/course/grade.py`、`eval/course/calibrate.py`、`agentflow-server/…/coordinator/CourseVerifyDecomposer.java`(只建一个 `QA_VERIFY` 的任务类型,给"换成随机证据"的测试用)和测试

- [ ] **Step 1: 自检和测试**
  - `grade.py --self-check`:打分提示的拼装、解析(逐要点 0/1)、`absent` 题不调模型。
  - `calibrate.py --self-check`:从运行结果里抽"结论 + 证据"对、导出成给人标注的 CSV(结论、原文片段、判官的判定,判定一列默认隐藏在另一个文件里,标注时看不到);读回标注后算判官的准确率、漏拦率、误拦率;"随机证据"的构造(同一门课里别的讲次的片段)。
  - `CourseVerifyDecomposerTest`:输入 `{"courseId","question","claims"}`,讲次信息从库里取(调用方不能自带 `mediaKey`);课程不存在、没有结论 → 拒绝。
- [ ] **Step 2: 实现。** 打分模型和判官一样经过引擎的网关(`LLM_BATCH` 任务),这样 token 记账是同一套。
- [ ] **Step 3: 模拟模型下跑通**(模拟打分只证明链路)。提交。

### Task 7: 不依赖模型的故障演练和转写抽查

**Files:** `scripts/chaos-course-ingest.sh`、`eval/course/transcript_check.py`、`docs/benchmarks.md`

- [ ] **Step 1: 摄入途中 kill -9 一个 worker**:三讲同时摄入(用临时的媒体目录,不动现有的 11 讲),挑帧和识别进行中强杀一个 worker。预期:三讲都完成;媒体服务的计数(下载、转写、合并次数)和不杀时一样,说明做完的步骤没有重做。
- [ ] **Step 2: 同一门课重新导入**:转写调用 0 次、下载 0 次(读媒体服务的 `/stats`)。
- [ ] **Step 3: 转写质量抽查**:取 2 到 3 讲各 10 分钟,和 YouTube 上的字幕逐词对齐,统计连续漏掉和多出 5 个词以上的段数。先看有没有人工字幕;只有自动字幕时如实写"对照的是另一个语音识别的结果"。
- [ ] **Step 4: 设计文档里的"1 个 worker 和 3 个 worker 导入一门课各多久"**:转写不随 worker 数变(第 7 节),识别受内存限制(第 10 节),这两段已有数据;只补测下载阶段(3 讲,临时媒体目录),或者说明为什么不测。
- [ ] **Step 5: 结果写进 `docs/benchmarks.md` 第 11 节。提交。**

---

## 第二段:需要用户

### Task 8: 题集(用户 4 小时左右)

- [ ] 用户写约 20 道跨讲次题(`kind: cross`),每题带要点和标准区间。`eval/course/README.md` 里有写法和例子。
- [ ] 单讲题:`python3 eval/course/draft.py --course 1 --per-lecture 4` 用真实模型按每讲的转写起草约 40 道;用户逐题改写措辞、核对答案和区间。
- [ ] 约 6 道课里没讲的题(`kind: absent`)。
- [ ] `questions.py check 1` 通过后 `freeze`。**冻结之后才开始任何一次真实运行。**

### Task 9: 真实模型的运行

- [ ] 用户决定:型号(先在 20 题上比 Haiku / Sonnet / Opus 的 S1,再定全量用哪个)、预算、key 放到服务器上的方式。
- [ ] 全量:S1、S2、S3 各 3 轮;B1 约 30 题 1 轮;打分;判官校准的 100 对由用户标注;"随机证据"测试。
- [ ] B2(Gemini Notebook)由用户手动跑约 30 题,把答案贴进 `eval/course/b2.jsonl`,打分脚本一并处理。

### Task 10: 报告

- [ ] `docs/benchmarks.md` 第 12 节:题集规模、各系统的指标表、差值和置信区间、三条事先写下的预期各自成立与否、判官的准确率、每问成本的实际数字、发现的问题。
- [ ] README 的实测表和简历用的数字只取这一节里置信区间分得开的结论。
