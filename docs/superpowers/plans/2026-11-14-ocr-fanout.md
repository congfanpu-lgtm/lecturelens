# 幻灯片识别分批并行(OCR fan-out)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"识别幻灯片"从一讲一个子任务、一帧接一帧,拆成"挑帧 → 按批识别(Kafka 分给 worker 池)→ 合并去重",让识别能用满一台机器,并且能靠加机器扩容。

**Architecture:** 挑帧(解码、比较画面、存下候选帧)留在持有文件的媒体服务里。每 20 张候选帧是一批,一批是一个普通的 `LEC_OCR` 子任务;引擎按子任务编号轮流分到 8 个分区,所以一讲的各批自然散到各个 worker。每个 worker 调自己配置的识别地址(`media.ocr-base-url`,默认就是媒体服务):同一份媒体服务代码以"识别副本"方式启动(`MEDIA_SOURCE=<媒体服务地址>`)时没有自己的文件,图片和结果都经 HTTP 找媒体服务。合并去重并进"建索引"这一步。

**Tech Stack:** 现有的:FastAPI + RapidOCR(onnxruntime)、Spring Boot worker / server、Kafka。不加新依赖(副本访问媒体服务用标准库 `urllib`)。

**Spec:** 没有单独的设计文档。依据是 `docs/open-problems.md` 的 P1(第二层)和 2026-11-13 对话里确认的方案;设计文档 `docs/superpowers/specs/2026-11-13-course-video-qa-design.md` 第 7 节把"摄入和问答隔离"留在第二版,这份计划不做隔离。

## Global Constraints

- 结果不变:对同一段视频,分批识别再合并得到的 `frames.v3.json` 的 `frames`(时间、文字)必须和原来一帧接一帧的做法一样。文件版本号不变,已经处理好的 11 讲不重做。(执行时发现:候选帧存成 JPEG 会让识别少认空格,所以存成无损的 PNG,见下面"执行中改掉的"。)
- 逐字稿、图片、识别出的文字都不经过 Kafka 和 MySQL:子任务只带 `mediaKey`、批号和计数。
- 失败的含义不变:挑帧或任何一批识别最终失败 → 这一讲只索引语音,状态是"部分完成";重试时只重做没做完的批。
- 默认部署不变:不起识别副本、不配 `media.ocr-base-url` 时,一个媒体服务进程照样完成全部工作。
- 环境约束照旧:Java 和检索服务的测试、所有实测都在 Upton 服务器上做;固定模拟模型;不动服务器上别的服务。

## Review Focus

1. **一批识别了两遍**(超时重投、重试):结果文件是整份原子写,两遍内容相同;合并时每批只读一次。→ 任务 2 `aBatchReadTwiceGivesTheSameResult`。
2. **某一批的结果缺了就合并**:不能悄悄少几张幻灯片。合并发现缺批要报错。→ 任务 2 `mergeRefusesWhenABatchIsMissing`。
3. **一讲没有画面 / 没有候选帧**:批数是 0,不建识别子任务,索引照常,幻灯片为空。→ 任务 2 `mediaWithoutVideoHasNoBatches`、任务 5 `noBatchesGoesStraightToTheIndex`。
4. **识别副本写回结果时文件名不对**:副本只能写它那一批的文件名,不能借这个接口往别处写。→ 任务 3 `storingTextsForFilesOutsideTheBatchIsRejected`。
5. **重新摄入已经处理好的讲次**(`frames.v3.json` 已存在):不重新挑帧、不建识别子任务。→ 任务 2 `aFinishedLectureHasNothingLeftToRead`。
6. **一批最终失败**:这一讲部分完成,只索引语音;页面上能看出是识别失败。→ 任务 5 `aFailedBatchMeansNoSlidesButTheLectureIsStillIndexed`。

---

## 接口约定

**媒体服务**(`K` = mediaKey,`n` = 批号,从 0 起)

| 接口 | 作用 | 返回 |
|---|---|---|
| `POST /media/K/frames/pick` | 挑帧:候选帧存进 `frames/`(PNG),清单写 `candidates.v1.json`。已有 `frames.v3.json` 时什么都不做 | `{"mediaKey","cached","candidates","batches"}` |
| `GET /media/K/frames/batches/n` | 这一批有哪些文件、识别过没有(给识别副本) | `{"files":["000012345.png",…],"done":false}` |
| `PUT /media/K/frames/batches/n` | 存这一批的识别结果(给识别副本),body `{"texts":{"000012345.png":"…"}}` | `{"stored":20}` |
| `POST /media/K/frames/batches/n/ocr` | 识别这一批(本机有文件就读本机,识别副本经 HTTP 取图、写回) | `{"mediaKey","batch","frames","cached"}` |
| `POST /media/K/frames/merge` | 按时间顺序合并各批、去重,写 `frames.v3.json`,删掉没留下的候选图 | `{"mediaKey","cached","frames"}` |
| `POST /media/K/frames` | 原来的一步到位,现在 = 挑帧 + 逐批识别 + 合并 | 不变 |

- 一批 20 张(`frames.OCR_BATCH`)。`candidates.v1.json`:`{"keyframes":n,"files":[…]}`;各批结果:`ocr.<n>.json`,`{"texts":{…}}`。合并之后清单、各批结果和候选图都删掉。
- `frames.v3.json` 里每张幻灯片的 `file` 是留下的那张图(合并时从 PNG 转成 JPEG;同一张幻灯片读了几次时是文字最全的那次),`tsMs` 是它第一次出现的时间,所以文件名里的数字不一定等于 `tsMs`。
- 识别副本:环境变量 `MEDIA_SOURCE=http://<媒体服务>`。副本上只有 `/health`、`/stats` 和 `…/ocr` 有意义。

**子任务**

| kind | 输入 | 输出 |
|---|---|---|
| `LEC_FRAMES`(现在是挑帧) | `{"lectureId","mediaKey"}` | `{"candidates","batches","cached"}` |
| `LEC_OCR`(新) | `{"lectureId","mediaKey","batch"}` | `{"batch","frames","cached"}` |
| `LEC_INDEX` | 不变;`withSlides` = 挑帧成功且每一批都成功 | 不变 |

阶段:导入 → 转写 ∥(挑帧 → 各批识别)→ 建索引(先合并)→ 知识点目录。

**执行中改掉的**(原因和代价记在账本的 Ruling 里,实测在 `docs/benchmarks.md` 第 8、10 节):
- 原计划"识别排在转写之后才开始"。实测 77 分钟的那一讲转写 196 秒、截帧识别 153 秒,原来识别是藏在转写的时间里的;等转写落定再识别,单讲会多出整个识别的时间。所以给引擎加了一个开关(`TaskDecomposer.advancesEarly()`):讲次摄入每落定一个子任务就问一次下一步,挑帧一完成就建各批识别。
- 原计划候选帧存 JPEG。实测识别 JPEG 会少认 16% 的空格,改成无损的 PNG,合并时把留下的转成 JPEG。
- 整体审查之后:同一段视频被两个任务同时摄入时后一个不再丢幻灯片;识别副本连不上时退回媒体服务;每批多少张可以用 `MEDIA_OCR_BATCH` 设;转写改走自己的 topic(`Topics.forSubtask`),因为几讲同时摄入时识别批次会被转写堵住(实测在第 10 节)。
- 任务 1 的实测结论是"不改布局":这台机器上识别受内存带宽限制,一个引擎、每个槽位一个引擎、每个进程一个引擎,吞吐都在每秒 4.5 张上下。改掉的是线程空转。

**worker 配置**:`media.ocr-base-url`(环境变量 `MEDIA_OCRBASEURL`),不配就用 `media.base-url`。

---

### Task 1: 一台机器上识别引擎怎么摆(先实测,再改)

**Files:** `agentflow-media/app/frames.py`、`app/config.py`、`app/main.py`、`tests/test_frames.py`、`docs/benchmarks.md`

- [ ] **Step 1: 实测。** 同样 240 张幻灯片图,比较:一个引擎几路共用(现在的做法)、同一进程里每路一个引擎、每个进程一个引擎,线程数 8 / 4 / 2 / 1。记录耗时、每秒张数、平均占用的核数。脚本不进仓库,方法和结果写进 `docs/benchmarks.md` 第 8 节。
- [ ] **Step 2: 按实测结果定布局**,写成 Ruling 记进账本(用哪种、线程数和路数的默认值)。
- [ ] **Step 3: 写失败的测试** `everyOcrSlotHasItsOwnEngine`(或者实测选中的那种布局对应的断言):同时进行的两次识别用的不是同一个引擎对象;`MEDIA_OCR_SLOTS=0` 时当作 1。
- [ ] **Step 4: 实现**,跑 `agentflow-media` 全部测试。
- [ ] **Step 5: 提交。**

### Task 2: 媒体服务把"截帧识别"拆成挑帧、按批识别、合并

**Files:** `agentflow-media/app/frames.py`、`app/main.py`、`tests/test_frames.py`、`tests/test_api.py`(或现有的接口测试文件)

**Interfaces — Produces:**
- `frames.pick(store, key) -> dict`(`{"mediaKey","cached","candidates","batches"}`)
- `frames.batch_files(store, key, n) -> list[str]`(批号越界 → `NotFound`)
- `frames.store_texts(store, key, n, texts: dict[str, str]) -> int`(文件名不是这一批的 → `BadInput`)
- `frames.ocr_batch(source, key, n, ocr, stats) -> dict`,`source` 有三个方法:`files(key, n)`、`image(key, name) -> PIL.Image`、`save(key, n, texts)`;`frames.Local(store)` 是本机的实现
- `frames.merge(store, key, stats) -> dict`
- `frames.frames_media(store, key, ocr, stats)`:签名和返回不变,内部是上面三步

- [ ] **Step 1: 写失败的测试**(`tests/test_frames.py`,沿用现有的 `video` 等夹具和假 OCR):
  - `pickSavesCandidatesWithoutReadingThem`:挑帧之后 `candidates.v1.json` 里的文件都在 `frames/` 下,假 OCR 一次都没被调用,`batches == ceil(candidates / 20)`。
  - `batchesThenMergeGiveTheSameFramesAsTheOneCallVersion`:同一段视频,一步到位和"挑帧 + 逐批 + 合并"得到的 `frames`(`tsMs`、`text`)相同。批的大小用 monkeypatch 调成 2,保证真的分了几批。
  - `aBatchReadTwiceGivesTheSameResult`:同一批识别两次,第二次 `cached` 为真、OCR 没有再被调用。
  - `mergeRefusesWhenABatchIsMissing`:少一批时合并抛 `BadInput`,`frames.v3.json` 没有写出来。
  - `mergeDeletesTheImagesItDidNotKeep`:合并后 `frames/` 下只剩 `frames.v3.json` 里引用的图。
  - `mediaWithoutVideoHasNoBatches`:只有声音的媒体,挑帧返回 `candidates == 0, batches == 0`;合并写出空的 `frames`。
  - `aFinishedLectureHasNothingLeftToRead`:已有 `frames.v3.json` 时挑帧返回 `cached` 为真、`batches == 0`,不解码视频。
  - `storingTextsForFilesOutsideTheBatchIsRejected`:`store_texts` 给了不属于这一批的文件名 → `BadInput`。
  - 现有的 `frames_media` 测试全部保留,必须照样通过(这就是"结果不变"的回归)。
- [ ] **Step 2: 跑测试,确认新测试失败。** `cd agentflow-media && .venv/bin/python -m pytest -q tests/test_frames.py`
- [ ] **Step 3: 实现。** 挑帧 = 现在循环里 OCR 之前的部分(`seen` 只和画面有关,和识别结果无关,所以候选帧不依赖识别);合并 = 现在循环里 OCR 之后的部分(丢掉文字太少的、`same_slide` 去重、留文字最全的那次),按候选帧的顺序做。
- [ ] **Step 4: 接口**(`app/main.py`)加上表里的五个,接口测试各一条(正常、404、400)。
- [ ] **Step 5: 跑全部媒体服务测试,提交。**

### Task 3: 识别副本(没有文件的媒体服务进程)

**Files:** `agentflow-media/app/frames.py`(`Remote`)、`app/config.py`(`MEDIA_SOURCE`)、`app/main.py`、`run.sh` 不用改、`tests/test_replica.py`

**Interfaces — Produces:** `frames.Remote(base_url, fetch=urllib 的实现)`,和 `Local` 一样有 `files / image / save`;`fetch(method, url, body: bytes | None) -> bytes`。

- [ ] **Step 1: 写失败的测试**(`tests/test_replica.py`):用 FastAPI 的 `TestClient` 当"媒体服务",把 `Remote` 的 `fetch` 接到它上面。
  - `aReplicaReadsABatchThroughTheMediaService`:媒体服务上挑帧;副本识别第 0 批;媒体服务那边出现 `ocr/0.json`,内容是假 OCR 的结果;副本本地没有写任何文件。
  - `aReplicaLeavesAFinishedBatchAlone`:第二次识别同一批,`cached` 为真(媒体服务返回的这一批已经有结果时不再识别)。
  - `aReplicaPassesOnTheMediaServicesErrors`:媒体不存在 → `NotFound`(副本返回 404,worker 不重试);媒体服务 500 → 普通异常(副本返回 500,worker 重试)。
- [ ] **Step 2: 跑,确认失败。**
- [ ] **Step 3: 实现。** `GET …/batches/n` 的返回里加 `"done": true/false`,副本据此判断要不要识别。`main.py`:`MEDIA_SOURCE` 有值时 `…/ocr` 用 `Remote`,否则用 `Local`。
- [ ] **Step 4: 跑全部媒体服务测试,提交。**

### Task 4: worker:挑帧、识别一批、建索引前合并

**Files:** `agentflow-worker/…/media/MediaClient.java`、`MediaProperties.java`、`lecture/LecFramesProcessor.java`、`lecture/LecOcrProcessor.java`(新)、`lecture/LecIndexProcessor.java`、对应测试

**Interfaces — Consumes:** 任务 2、3 的接口。**Produces:** 子任务表里的输入输出。

- [ ] **Step 1: 写失败的测试**
  - `MediaClientTest`:`pickFrames` / `mergeFrames` 打到 `media.base-url`;`ocrBatch` 打到 `media.ocr-base-url`,没配时打到 `media.base-url`;4xx 仍是 `NonRetryableException`。
  - `LecFramesProcessorTest.picksFramesAndReportsHowManyBatches`:输出里有 `candidates`、`batches`、`cached`。
  - `LecOcrProcessorTest.readsOneBatch`:`type()` 是 `LEC_OCR`;调用 `ocrBatch(mediaKey, batch)`;输出 `{"batch","frames","cached"}`。
  - `LecIndexProcessorTest.mergesTheSlidesBeforeReadingThem`:`withSlides` 为真时先 `mergeFrames` 再 `frameList`(用 Mockito 的 `InOrder`);为假时两个都不调。
- [ ] **Step 2: 跑,确认失败。** `mvn -q -pl agentflow-worker -am test -Dtest='MediaClientTest,LecFramesProcessorTest,LecOcrProcessorTest,LecIndexProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: 实现,跑 worker 的全部单元测试,提交。**

### Task 5: server:多一个"各批识别"的阶段;页面上显示识别进度

**Files:** `agentflow-server/…/coordinator/LectureIngestDecomposer.java`、`course/CourseController.java`(`stages`)、`static/courses.html`、`LectureIngestDecomposerTest`、`LectureIngestFlowTest`(或现有的摄入流程测试)

- [ ] **Step 1: 写失败的测试**
  - `ocrBatchesFollowTranscribeAndPick`:转写和挑帧都成功、挑帧说有 3 批 → 下一阶段是 3 个 `LEC_OCR`,输入里是 `batch` 0、1、2 和 `mediaKey`。
  - `theIndexWaitsForEveryBatch`:3 批都成功 → `LEC_INDEX`,`withSlides` 为真。
  - `noBatchesGoesStraightToTheIndex`:挑帧说 0 批 → 直接 `LEC_INDEX`,`withSlides` 为真(合并会给出空的幻灯片,或者已有的结果)。
  - `aFailedBatchMeansNoSlidesButTheLectureIsStillIndexed`:有一批失败 → `LEC_INDEX` 的 `withSlides` 为假;全部落定后任务是 `PARTIAL_FAILED`,讲次的 `error` 以 `LEC_OCR:` 开头。
  - `aFailedPickMeansNoBatches`:挑帧失败 → 不建 `LEC_OCR`,`withSlides` 为假。
  - 转写失败时不建 `LEC_OCR`(整讲失败,现有行为)。
  - 页面用的 `stages`:多个 `LEC_OCR` 合成一项 `{"kind":"LEC_OCR","status","seconds","done","total"}`——有失败的是 `FAILED`,全成功是 `COMPLETED`,否则 `RUNNING`;`seconds` 从第一批建出来算到最后一批落定。
- [ ] **Step 2: 跑,确认失败**(单元测试在 Mac 上,流程测试在服务器上)。
- [ ] **Step 3: 实现。** `courses.html` 的步骤条:导入、转写、挑帧、识别(进行中显示 `识别 9/14`)、索引、知识点。
- [ ] **Step 4: 服务器上跑全套 Java 测试,提交。**

### Task 6: 部署、实测、文档

- [ ] **Step 1:** 代码同步到服务器,重启媒体服务,重建课程环境;重新摄入一讲(清掉那一讲的 `frames.v3.json`、`candidates.v1.json`、`ocr/`),页面上确认六个步骤和识别进度;和原来的 `frames.v3.json` 对比,`frames` 的时间和文字相同。
- [ ] **Step 2: 单讲耗时**:77 分钟那一讲从加入到可检索,改之前 240 秒,改之后多少。
- [ ] **Step 3: 加副本的效果**:每个识别副本用 `taskset` 限定在固定的几个核上,每个 worker 配自己的副本,分别测 1、2、4 对"worker + 副本"识别同一讲(或同样几讲)的耗时。需要的话在 `docker/course.yml` 里加 `worker-3`、`worker-4`(compose profile `scale`)。
- [ ] **Step 4: 八讲同时识别**,和第 8 节的 405 秒比。
- [ ] **Step 5: 杀掉一个识别副本**(或它的 worker),确认那几批被重试、别的批不重做、这一讲最后是完整的。
- [ ] **Step 6: 文档**:`docs/benchmarks.md`(第 8 节的布局实测 + 新的一节)、README 的摄入流程图和实测表、`docs/open-problems.md`(P1 的状态;过程中遇到的新问题)、`docs/superpowers/specs/…-design.md` 不改。提交并推送。
