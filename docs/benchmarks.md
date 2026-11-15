# Benchmarks & drills (2026-10-17)

Environment: Apple M5 (10 cores) · Docker Desktop · `docker compose` stack (MySQL, Redis, MongoDB, Kafka,
pgvector, RAG, server, **2 workers × 4 consumer threads**, 8 SUBTASK partitions). Numbers are machine-specific;
the ratios and pass/fail results are the point.

## 1. Scheduler throughput (no LLM)

`ECHO_BATCH` subtasks sleep 300 ms, so the ceiling is 8 threads ÷ 0.3 s = **26.7 subtasks/s**.

| Run | Tasks × subtasks | Concurrency | Submit P50 / P99 | End-to-end P50 / P99 | Throughput | Trace complete |
|---|---|---|---|---|---|---|
| `load_test.py --tasks 60 --items 5 --concurrency 10` | 60 × 5 | 10 | 44 / 579 ms | 1.9 / 2.9 s | 23.9 subtasks/s | 100% |
| `load_test.py --tasks 120 --items 5 --concurrency 20` | 120 × 5 | 20 | 36 / 357 ms | 3.8 / 4.3 s | **25.5 subtasks/s (96% of ceiling)** | 100% |

Reading it: Kafka dispatch, MySQL state CAS, idempotency checks, Mongo trace writes and polling
together add ~4% over pure work time at saturation; end-to-end latency grows with queue depth, not with
per-task overhead.

### Raising the ceiling (2026-11-12)

A worker thread handles one subtask at a time and commits its offset when it is done. So the number of subtasks
in flight equals the number of consumer threads, capped by the SUBTASK partition count. Both are now settings
(`SUBTASK_PARTITIONS`, `WORKER_CONCURRENCY`); the defaults stay at 8 and 2 × 4.

| Configuration | Ceiling | Measured | Limited by |
|---|---|---|---|
| 8 partitions, 2 × 4 threads | 26.7 /s | 25.1 /s | worker threads |
| 64 partitions, 2 × 32 threads, 1 result thread | 213 /s | 117–159 /s | the server: one thread consuming results, 785 results queued mid-run |
| 64 partitions, 2 × 32 threads, 3 result threads | 213 /s | **195–201 /s (92–94%)** | worker threads again |

Last row: 1,200 tasks × 5 subtasks from 300 concurrent clients. Submit P50 89–118 ms / P99 1.3–1.5 s,
end-to-end P50 7.1 s / P99 8.0–8.4 s, trace 100% complete.

**A race the higher concurrency exposed.** With three result threads, 3 of about 1,200 tasks hung with one
subtask left `DISPATCHED`. The dispatcher sends the message first and records `DISPATCHED` second. Under load a
result arrived in between, did not match the expected state, and was dropped as a duplicate. The result handler
now records `DISPATCHED` itself in that case. The three tasks recovered on their own ten minutes later through
the timeout redispatch and the worker's re-send (finding 2 in the fault matrix).

**Why in-flight work is still tied to partitions.** Untying it needs out-of-order offset commits. Spring
Kafka's `asyncAcks` pauses the consumer until a whole poll is acknowledged, redelivers more after a crash, and
takes listener failures away from the container's error handler. More partitions reaches the same in-flight
count and leaves the failure behaviour measured below unchanged. The cost: partitions cannot be removed, and a
rebalance moves more of them.

One operational note from upgrading a live deployment (3 → 8 partitions): a worker that is already running
picks up the added partitions only after its next metadata refresh, up to 5 minutes. Subtasks sent to the
new partitions waited about 4 minutes and then completed. Restart the workers to take over at once.

## 2. Fault matrix (2026-11-12)

`scripts/fault_matrix.py` submits 2 tasks/s (5 subtasks each) for 60 s, `docker kill`s one component 10 s in
(SIGKILL) and starts it again after the outage. It runs in its own compose project (`docker/chaos.yml`: separate
containers and volume), same shape as above: 2 workers × 4 threads. Each run answers three questions:

- **Lost?** Every *accepted* task (the submit call returned a `taskUuid`) must end `COMPLETED`. Submits refused
  during the outage are counted separately.
- **Duplicated?** A subtask with more than one `PROCESSED/OK` event in its trace was executed twice.
- **How long?** Seconds from the restart to the first new result, and to the backlog being cleared.

**30 s outage**

| Killed | Accepted / refused | Completed | Run twice | First result | Backlog cleared | What happens |
|---|---|---|---|---|---|---|
| 1 of 2 workers | 120 / 0 | 120 | 0 | 14.9 s | 26.6 s | Its partitions move after Kafka's 45 s session timeout, restart or not |
| Server | 52 / 68 | 52 | 0 | 14.4 s | 14.5 s | Results wait in Kafka. One server, so submits are refused while it is down |
| Kafka | 120 / 0 | 120 | 0 | 14.0 s | 31.4 s | Producers buffer and retry. Submit calls block until the broker is back (P99 60 s) |
| MySQL | 99 / 21 | 99 | 0 | 10.7 s | 22.2 s | The result consumer retries with backoff. Submits return 500 meanwhile |
| Redis | 120 / 0 | 120 | 0 | 4.4 s | 20.7 s | The subtask consumer retries its idempotency lookup with backoff |
| MongoDB | 120 / 0 | 120 | 0 | — | — | Tasks do not depend on it. Trace events wait in Kafka and are written after the restart |

Re-run at 64 partitions, 2 × 32 worker threads and 3 result threads: the same outcome for all six (accepted
tasks all completed, none run twice, nothing dropped), and the backlog clears in 11–17 s instead of 15–31 s.

**Minutes-long outage** (a failover, not a blip), before and after the fixes below

| Outage | Build | Completed | Stuck at the deadline | Run twice | Messages dropped |
|---|---|---|---|---|---|
| Redis, 180 s | before | 115 / 120 | 5 | 5 | 24 subtask messages |
| | after | **120 / 120** | 0 | 0 | 0 |
| MySQL, 330 s | before | 20 / 21 | 1, later `PARTIAL_FAILED` | 0 | 1 result |
| | after | **21 / 21** | 0 | 0 | 0 |

### What the drills found

1. **Messages dropped after 10 instant retries.** Spring Kafka's default error handler retries a failed record
   10 times with no delay and then skips it. With Redis down the workers skipped subtask messages; with MySQL
   down the server skipped a result. Now: exponential backoff, 1 s doubling to 30 s, 10 minutes in total.
2. **A lost result became a false failure.** The worker had done the work and stored its idempotency key, so
   every timeout redispatch was skipped, and nothing re-sent the result. After 3 redispatches (40 minutes at the
   default 600 s) the subtask was marked `FAILED` and the task ended `PARTIAL_FAILED`, although all the work
   had succeeded. Now the key stores the output and a redelivery re-sends it; the server's compare-and-swap
   drops extras. Checked live: one redispatch settles the task and the work is not redone.
3. **Redis failing just after a result was sent ran the work twice.** The failed idempotency write was treated
   as a processing failure and went to the retry ladder (5 of 5 in-flight subtasks). Now it is logged: the
   result is already out, and a missing key can only cost a duplicate on a later redelivery.
4. **A task could be stranded forever.** If the server died between committing a task and dispatching it,
   task and subtasks stayed `PENDING`; the sweep only looked at `RUNNING` tasks. The random drills did not hit
   this window (a few milliseconds), so it was reproduced by planting such a row: untouched after two sweeps on
   the old build, `COMPLETED` after one on the new build.

### Limits

- One server. While it is down nothing can be submitted; accepted tasks are unaffected.
- Submit waits for the broker ack of each subtask, so it blocks while Kafka is down.
- A dead consumer is replaced only after the 45 s session timeout. Lowering `session.timeout.ms` trades faster
  failover for more false rebalances.
- An outage longer than the 10-minute retry budget still drops the message. The timeout sweep (600 s) then
  recovers it through redispatch and re-send.
- Redis is not persisted here. A kill loses the idempotency keys and agent checkpoints written since the last
  snapshot, so a later redelivery of those subtasks redoes the work. These runs did not trigger one.
- MongoDB acknowledges a trace write before it is journaled (the default write concern), so a kill can lose the
  last ~100 ms of events. On the Linux run, 1 task of 120 lost its first two trace events (`SUBMITTED`,
  `DECOMPOSED`) in each MongoDB drill; the laptop runs lost none. Task state lives in MySQL and was not
  affected. `?journal=true` on the Mongo URI closes the gap and makes every trace write slower.
- Duplicates are counted from the trace, so the MongoDB row is a lower bound.

    C() { docker compose -p agentflow-chaos -f docker-compose.yml -f docker/chaos.yml "$@"; }
    C up -d --wait mysql redis mongo kafka && C up -d --build --no-deps server worker-1 worker-2
    python3 scripts/fault_matrix.py --victim all        # prefix the `C up` line with SUBTASK_PARTITIONS=64 WORKER_CONCURRENCY=32 for the scaled run
    python3 scripts/fault_matrix.py --victim redis --down 180 --deadline 300
    python3 scripts/fault_matrix.py --victim mysql --down 330 --deadline 480

## 3. Overload (2026-11-12)

60 `LLM_BATCH` tasks × 5 calls submitted at once to 64 worker threads, mock model at 1.5 s per call. That is
about 43 calls/s attempted against a token bucket of 20 + 10/s, roughly four times the quota.

| No token available | Tasks completed | Subtasks dead-lettered | Wall time | Rate-limit failures |
|---|---|---|---|---|
| Before: fail, then the retry ladder (5 s / 30 s / 5 min), then DLQ | 22 / 60, the other 38 `PARTIAL_FAILED` | 52 / 300 | 344 s | 557 |
| After: wait for one, up to `llm.max-wait-ms` (60 s) | **60 / 60** | 0 | 31 s | 0 |

Before, every rate-limited call used up one of a subtask's three retries. Overload turned into failures, and
the tasks that did finish had waited for the 5-minute retry step. Now the call waits for a token. Its consumer
thread takes no new message meanwhile, so the excess stays queued in Kafka, and 300 calls at 10/s take the
expected 30 s. Waiting calls are not served in order, and one large task can still fill the queue ahead of a
small one.

    python3 scripts/load_test.py --base http://127.0.0.1:18080/api/v1 --type LLM_BATCH --tasks 60 --items 5 --concurrency 60 --deadline 480

### The same runs on a Linux server (2026-11-12)

Sections 1 (ceiling), 2 and 3 repeated on a 24-core Linux machine (WSL2, Docker 29.1, Compose 2.40), from a
fresh clone of the fixed build.

| | Laptop (M5) | Linux server |
|---|---|---|
| Throughput, 8 partitions | 25.1 /s | 25.0 /s |
| Throughput, 64 partitions | 195–201 /s | 193–203 /s |
| Six faults, 30 s outage, both configurations | all accepted tasks completed, none run twice | same |
| Redis down 180 s | 120 / 120 | 120 / 120 |
| MySQL down 330 s | 21 / 21 | 21 / 21, 5 redeliveries answered from the stored result |
| Overload, 4× the quota | 60 / 60 in 31 s | 60 / 60 in 30.5 s |
| Messages dropped / results ignored / dead-lettered | 0 / 0 / 0 | 0 / 0 / 0 |
| Trace complete after the MongoDB kill | 100% | 99.2% (see Limits) |

Throughput is the same on both because the ceiling is set by the 300 ms simulated work per thread, not by the
hardware.

## 4. Agent crash recovery (real model: Claude Haiku + Tavily)

`scripts/chaos-agent.sh "<question>"` waits for the first agent step, kills the worker running it, and
checks that another worker resumes from the Redis checkpoint.

| Question | Killed | Resumed | Result | Wall time |
|---|---|---|---|---|
| How does Kafka consumer group rebalancing work? | worker-2 | 2 agents on worker-1, from step 2 | COMPLETED | 71 s |
| What is the difference between Raft and Paxos? | worker-1 | 2 agents on worker-2, from step 2 | COMPLETED | 56 s |
| How does Redis Cluster shard keys? | worker-2 | 2 agents on worker-1, from step 2 | COMPLETED | 59 s |

3/3 recovered; completed steps (LLM decisions + tool results) were replayed from the checkpoint, not re-run.
Most of the wall time is the same 45 s session timeout as in the fault matrix.

## 5. Research quality

See the evaluation table in the README and `eval/results/`.

## 6. Retrieval quality (BEIR)

`agentflow-rag/retrieval_eval.py`, test splits, the service's own BM25 / embedding / RRF code; dense search is
exact cosine (what pgvector `<=>` does without an ANN index). Raw output: `eval/results/retrieval-v1.{md,json}`.

| Method | SciFact nDCG@10 | SciFact Recall@3 | NFCorpus nDCG@10 | NFCorpus Recall@10 |
|---|---|---|---|---|
| Hash embedding (before) | 0.211 | 0.204 | 0.074 | 0.037 |
| BM25 (k1 0.9, b 0.4) | 0.681 | 0.696 | 0.322 | 0.153 |
| Dense `BAAI/bge-small-en-v1.5` | 0.720 | 0.710 | 0.338 | 0.158 |
| **Hybrid, RRF k=60 over top 100 each** | **0.733** | **0.720** | **0.365** | **0.174** |
| *Published: BM25 (BEIR paper, Anserini)* | *0.665* | | *0.325* | |
| *Published: bge-small model card (MTEB)* | *0.713* | | *0.343* | |

Paired bootstrap 95% CI of the nDCG@10 gain: hybrid − BM25 = [+0.028, +0.076] (SciFact), [+0.030, +0.057]
(NFCorpus); hybrid − dense = [−0.012, +0.036] (SciFact, not significant), [+0.014, +0.041] (NFCorpus).
Encoding time on the M5 CPU: 539 s (SciFact), 360 s (NFCorpus).

## 7. Lecture video: transcription and slide frames (2026-11-13)

Environment: the 24-core Linux machine (WSL2) with one RTX 5080 Laptop GPU (16 GB). faster-whisper 1.2.1
(ctranslate2 4.9.0), `large-v3`, fp16. The service is `agentflow-media`; design in
`docs/superpowers/specs/2026-11-13-course-video-qa-design.md`.

### How to run transcription on one GPU (spike)

One 47.7-minute lecture (MIT 18.06, lecture 2), same audio for every row. Model load time is excluded.
The scripts were throwaway and are not in the repo.

| Plan | ASR workers | Wall time | Speed (× realtime) | Peak VRAM |
|---|---|---|---|---|
| 60 s chunks, one at a time | 1 | 315 s | 9.1 | 5.8 GB |
| same | 2 | 310 s | 9.2 | 11.2 GB |
| same | 3 | 327 s | 8.8 | 15.8 GB |
| same (control, rerun at the end) | 1 | 334 s | 8.6 | 5.8 GB |
| 5 min chunks, batched inside the worker (batch 8) | 1 | 39 s | 72.6 | 7.3 GB |
| same | 2 | 43 s | 67.0 | 13.7 GB |
| same | 3 | 74 s | 38.9 | 15.9 GB |
| **whole file, batched (batch 16)** | **1** | **30 s** | **95.1** | **10.0 GB** |

1. **More ASR workers on one GPU buy nothing.** Throughput with 1, 2 and 3 workers is the same (one worker
   already saturates the GPU) while VRAM grows linearly; near the 16 GB limit it gets slower (5-minute plan,
   3 workers: almost half the speed).
2. **The speed-up comes from batching inside one worker**: 8 to 10 times faster than chunk-by-chunk.
3. **So a lecture is not split:** one subtask per lecture, the whole file batched, one transcription at a time
   on the GPU. The earlier idea (60 s chunks fanned out to several workers, retried per chunk) was dropped.
4. After half an hour of continuous load the same configuration was about 6% slower (315 s → 334 s).

The two ways do not produce the same text. In the first 10 minutes the chunked run had about 10% more words
(1400 vs 1265): partly repetitions and hallucinations at the hard 60 s cuts ("Thank you very much. Thank you."
where nobody said it), partly short phrases that the batched pipeline's silence detection dropped. There is no
reference transcript, so neither is shown to be more accurate; batching was chosen because invented text would
later be quoted as evidence.

### End to end on a real lecture (smoke)

`agentflow-media/smoke.py` against the running service, one lecture of the evaluation course (Berkeley Agentic
AI MOOC, "LLM Agents Overview", 1 h 58 min 21 s, 1280×720). Everything is done twice; the second pass must be
all cache hits.

| Step | First pass | Result |
|---|---|---|
| Import (audio + 720p video, two downloads, no ffmpeg) | 38.6 s | 242 MB on disk including frames |
| Transcribe, as shipped (timestamps inside each chunk) | 151.5 s = **46.9 × realtime**, including the one-time model load | 1,298 utterances, 18,240 words, English |
| Transcribe, first version (pipeline default: no timestamps) | 96.1 s = 73.9 × realtime, same | 264 utterances, 18,546 words |
| Frames (key frames only, OCR on change) | 806.4 s | 1,288 key frames, 974 OCRed, 896 kept |
| Second pass | all three steps `cached`, `/stats` unchanged (1 download, 1 ASR run, 1 OCR run) | |

The lecture was transcribed twice because the first version turned out to give one utterance per ~30 s chunk
(next section). Import and frames were not repeated for the second run; they were cache hits.

The model stays loaded after the first transcription: about 4.4 GB of VRAM while idle.

### What the spot check found

- **Transcript:** reads correctly at the start, middle and end; start times never go backwards. "DeepSeek"
  is heard as "EAP-seq" in both runs.
- **Utterance length was wrong in the first version and is fixed.** The batched pipeline does not ask Whisper
  for timestamps by default, so one silence-detection chunk came back as one utterance: median 27.8 s, starting
  and ending mid-sentence, useless for "jump to this sentence". With `without_timestamps=False` each chunk is
  split at Whisper's own timestamps: 1,298 utterances, median 4.9 s, 90% under 7.8 s, longest 24.4 s. It costs
  58% more time (96 s → 152 s). These are phrases rather than sentences (497 of 1,298 end with ". ? !"), 5 of
  them start up to 1 s before the previous one ends (chunk boundaries), and the largest gap is 11.8 s;
  segmentation has to deal with all three.
- The two runs agree on 90–94% of the words, in order, in three 10-minute windows; the timestamped run has
  1.6% fewer words overall. There is no reference transcript, so neither is shown to be more accurate.
- **Frames: the same slide is captured many times.** The recording has the speaker's webcam in a corner
  (about 2% of the picture), so the picture "changes" on almost every key frame: the median change between
  consecutive key frames is 0.48% of pixels, above the 0.3% threshold that was tuned on a synthetic video
  without a webcam. (The service compares each key frame with the last one it OCRed, not with its neighbour;
  the outcome is the same.) 76% of key frames were OCRed (974 of 1,288), which is where the 13 minutes go. OCR output
  for an unchanged slide differs in spacing ("LLM AgentsMOOC" / "LLM Agents MOOC"), so the exact-text check
  does not remove the repeats; slides that build up line by line add more. Comparing texts with whitespace
  removed, 896 frames collapse to 359 at similarity 0.9 and to 268 at 0.8. (These two were measured with
  difflib's default heuristics. With the exact comparison the service uses, the merge at 0.8 gives 199; that is
  the number used in the next section.)
- Fixed afterwards, see the next section.

### Frame dedup (fixed)

Same lecture, same machine. Two changes: (1) a first pass over the key frames finds the blocks of the picture
that keep moving (changed in more than half of all key-frame pairs) and the change test ignores them plus one
block of margin; (2) a frame whose OCR text is at least 80% similar to the last kept one (whitespace removed)
is dropped.

| | Key frames OCRed | Frames kept | Frames step | Distinct slides of the first run found again |
|---|---|---|---|---|
| First version | 974 of 1,288 | 896 | 806 s | |
| Moving blocks ignored, no margin | 585 | 200 | 542 s | 199 of 199 |
| **Moving blocks + one block of margin (shipped)** | **263** | **191** | **238 s** | **192 of 199** |

- The middle row is what the first attempt gave. Counting per block showed why: the upper half of the webcam
  changed in 85-89% of key-frame pairs, the lower half (shoulders) in 38-47%, the slide area in at most 11%.
  The lower half stayed under the 50% line, was not ignored, and caused 85% of the 385 wasted OCR runs.
  Lowering the line would also start to swallow the slide area of a fast-paced deck, so the line stayed and a
  margin was added instead.
- "Distinct slides" = the 896 frames of the first run with neighbours of 80% text similarity merged (199).
  The 7 not matched were checked by hand: all 7 are slides that are in the new list in another build-up state
  or with a different OCR reading (for example "Questions?" without the webcam caption). No slide was lost.
- 191 frames for a 2-hour lecture is still more than the number of slides: a slide that builds up line by line
  is read several times, and at this point the first of several similar readings was kept, so the last bullets
  of such a slide could be missing. Changed afterwards (`frames.v3.json`): of similar readings the fullest is
  kept, at the time the slide first appeared. The same lecture then keeps 170 frames from the same 263 OCR runs.
- OCR costs about 0.9 s per frame on this CPU, so the step is now about 4 minutes for a 2-hour lecture.

## 8. Lecture ingest on the engine (2026-11-13)

A lecture is one `LECTURE_INGEST` task with five subtasks: import → transcribe ∥ frames → index → concepts
(design: `docs/superpowers/specs/2026-11-13-course-video-qa-design.md`). Same machine as section 7; the full
stack runs from `scripts/course-env.sh up` (2 workers, mock LLM), the media service on the host with the GPU.
Three lectures of the evaluation course (Berkeley Agentic AI MOOC, Fall 2025) were ingested through the course
library page's API.

### Time per step

Seconds from the moment the step's subtask was created until it settled, as the engine recorded them.

| Lecture | Length | Import | Transcribe | Frames | Index | Concepts | Wall clock |
|---|---|---|---|---|---|---|---|
| 3. Post-Training Verifiable Agents (nothing cached) | 77.5 min | 28 s | 196 s | 153 s | 4 s | <1 s | 240 s |
| 2. Evolution of System Designs (nothing cached) | 79.5 min | | | | | | 240 s for lectures 1 and 2 together |
| 1. LLM Agents Overview (import, transcript and frames already in the media cache) | 118.3 min | | | | | | see above |
| The link of lecture 1 added a second time | 118.3 min | <1 s | <1 s | <1 s | 11 s | <1 s | 12 s |

- Lectures 1 and 2 have no per-step numbers: when they ran, the engine overwrote a settled subtask's
  `updated_at` with the time it was dispatched, so every step read 0 s. That was fixed before lecture 3.
- A 77-minute lecture is searchable four minutes after its link is pasted. Transcription and frames run side
  by side, so the wall clock is import + the slower of the two + index.
- Transcription ran at 24× realtime here against 47× when it ran alone (section 7). The likely cause, not
  measured, is that the OCR of the frames step uses the same CPU cores that transcription needs for decoding
  and feature extraction. Whether running the two one after the other would be faster was not measured.
- Every number in this table is from a single run.
- Adding a link that is already in the media cache does no download, no transcription and no OCR
  (`/stats` of the media service unchanged); only the index is rebuilt.
- Concepts are produced by the mock model in this environment, so that column only shows the step runs; its
  real cost is one model call per lecture.

### What ended up in the index

| Lecture | Utterances | Speech segments (avg length) | Key frames → OCRed → kept | Slide segments |
|---|---|---|---|---|
| 1 | 1,298 | 129 (62 s) | 1,288 → 263 → 191 | 191 |
| 2 | 1,173 | 98 (56 s) | 1,008 → 77 → 47 | 47 |
| 3 | 1,541 | 113 | 1,075 → 84 → 53 | 53 |

The longest speech segment is 1,496 characters, about 250 words, inside the embedding model's 512-token window.

Search inside the course (`POST /segments/search`, hybrid, top 3), checked by eye:

| Query | Top hits |
|---|---|
| "what is pre-training" | L1 speech 53:02 "Okay, so pre-training summary. The idea is about predicting the next [word] on the internet…", L1 speech 11:57, L1 speech 12:36 |
| "reinforcement learning from human feedback" | L1 slide 78:18 "RL from Human Feedback (RLHF) / Idea: maximize human preference rather than clone their behavior", L1 speech 2:55, L1 speech 77:33 |
| "what hardware do you need to serve large models" | L2 speech 51:58 "It hosts eight GPUs, two CPUs, and it's a very beefy machine…", L2 speech 51:22, L2 speech 53:32 |

The third query is answered from lecture 2 without naming it: retrieval is scoped to the course, not to a
lecture. For "how are agents evaluated" one of the three hits was an unrelated slide (an SFT pipeline); this is
plain retrieval with no agent and no verification yet.

### Failure cases

| Case | Result |
|---|---|
| A playlist link | `FAILED` at once (import settled in under 1 s) with `LEC_IMPORT: media 400: the link must point to one video…`; no retry was scheduled (0 retry log lines on both workers). Before the non-retryable path, any failure waited through the 5 s / 30 s / 5 min retry ladder first. |
| Retry on a lecture that is `READY` | 400, "only failed or partly finished lectures can be retried" |
| Retry on the failed playlist lecture | accepted, runs again, fails again with the same reason |
| The same link added twice | second lecture `READY` in 12 s, all from cache |

Both pages (`/courses.html`, `/lecture.html`) were checked in a browser against this data: the five steps and
their times, the failure reason and the retry button; on the lecture page the player, 1,298 transcript lines,
26 concepts and 191 slide thumbnails, with a click on a transcript line, a concept and a slide each moving the
player to that second, the search box narrowing the transcript, and no console errors.

### A whole course at once (11 lectures, 12.7 hours of video)

All eleven recorded lectures of the course were added in one go, to see what happens when work queues up
behind one GPU and 24 CPU cores. Lectures 1-3 already had their download and transcript in the media cache;
every lecture's frames were recomputed. For this run the course environment allows a step to wait: stuck
threshold 3,600 s (default 600), media-call timeout 1 h, Kafka `max.poll.interval.ms` 65 min.

**All 11 ended `READY`; none failed.** The last one settled after 114 minutes. Seconds per step, from the
moment its subtask was created (so queueing is included):

| # | Length | Import | Transcribe | Frames | Index | Concepts | Key frames → OCRed → kept |
|---|---|---|---|---|---|---|---|
| 1 | 118.3 min | cached | cached | 920 | 1,102 | <1 | 1,288 → 263 → 170 |
| 2 | 79.5 min | cached | cached | 440 | 965 | 1,275 | 1,008 → 77 → 47 |
| 3 | 77.5 min | cached | cached | 396 | 853 | 155 | 1,075 → 84 → 53 |
| 4 | 94.4 min | 34 | 1,204 | 1,354 | 225 | <1 | 1,175 → 124 → 106 |
| 5 | 64.6 min | 23 | 505 | 284 | 427 | 293 | 811 → 42 → 42 |
| 6 | 54.8 min | 19 | 242 | 1,628 | 383 | 649 | 704 → 400 → 52 |
| 7 | 44.1 min | 108 | 1,271 | **6,701** | 6 | <1 | **79,320 → 3,396 → 94** |
| 8 | 61.2 min | 456 | 1,137 | 2,223 | 14 | <1 | 768 → 406 → 77 |
| 9 | 46.9 min | 933 | 853 | 3,114 | 7 | 2,754 | 616 → 96 → 70 |
| 10 | 59.0 min | 59 | 883 | 1,934 | 12 | <1 | 761 → 173 → 80 |
| 11 | 61.7 min | 51 | 694 | 1,704 | 56 | 2,235 | 1,124 → 592 → 276 |

What the run showed:

- **A bug: lecture 7's video is VP9, and its decoder ignores "decode key frames only".** Every one of the
  79,320 frames was treated as a key frame; the frames step ran for 112 minutes. Fixed by also filtering on
  the key-frame flag. The same lecture alone afterwards: 529 key frames, 64 OCR runs, 50 frames kept, 172 s.
- **A step that ran for 112 minutes still ended correctly.** At 60 minutes the engine re-dispatched it once and
  the worker's call timed out and was retried once. Both duplicates waited on the media service's per-lecture
  lock and returned the finished result (one idempotency hit on the worker); the lecture ended `READY`. With
  the default 600 s threshold and 3 re-dispatches it would have been marked failed after 40 minutes.
- **OCR is the bottleneck when lectures are processed together.** The frames step took 284 to 3,114 s here
  (lecture 7 aside) against 153 to 238 s for a lecture processed alone: up to eleven OCR runs shared the same
  cores. Transcription times (242 to 1,271 s) are mostly the wait for the single GPU, as designed.
- **Short steps starve behind long ones.** Building the index is seconds of work but waited up to 1,102 s;
  the concepts step (under a second with the mock model) waited up to 2,754 s. A consumer thread handles its
  Kafka partition one message at a time, so a short step queued behind a transcription or a frames step waits
  for it. This is the "ingest against interactive work" problem the design leaves to version two; a question
  asked during an import would wait the same way.
- For three lectures most OCR runs were wasted (lecture 6: 400 runs, 52 frames kept; 8: 406 → 77; 11: 592 →
  276): something on screen moves that the moving-block mask does not cover. Not investigated yet.

After the fix lecture 7 was ingested again (10 s, everything else cached). The course now holds 929 speech
segments and 1,023 slide segments.

### OCR concurrency limit

The whole-course run above showed lectures fighting for the CPU while their slides were read. Two limits were
added to the media service: at most `MEDIA_OCR_SLOTS` OCR calls at once across all lectures, and
`MEDIA_OCR_THREADS` as the size of the OCR engine's thread pools. Measurement: the frames step of eight
lectures started at the same moment, 1,259 OCR calls in total, single runs.

| Calls at once × thread-pool size | Wall time | Load average |
|---|---|---|
| no limit on calls, pools of one thread per core (the old behaviour) | 1,243 s | 59 |
| 12 × 2 | 541 s | 5 |
| 6 × 4 | 455 s | 12 |
| **3 × 8 (default: pools of 8, calls at once = cores ÷ 8)** | **405 s** | 26 |

**Correction (2026-11-14, from the code review).** An earlier version of this section called the second number
"threads per call" and gave the rule "calls × threads ≈ cores". That is not what the code does: there is one OCR
engine, shared by all calls, so the thread pools are shared too and 12 × 2 does not mean 24 busy threads (its
load average of 5 says as much). The wall times above stand; the explanation of why 3 × 8 is the fastest did
not. The measured explanation is in "What actually limits the OCR" below.

- Limiting is three times faster than not limiting (1,243 s → 405 s) for the same work.
- The 3 × 8 run shared the machine with a test run for about two minutes, so it is, if anything, understated.
- One OCR call takes about 1.1 s with 4 threads and under 1.0 s with 8; with 2 threads the cores stay idle.
- This only removes the thrashing on one machine. More throughput needs more cores: splitting the OCR of a
  lecture into batches fanned out to several machines is listed in `docs/open-problems.md` (P1, second layer).

### What actually limits the OCR (2026-11-14)

Two measurements on the same server (24 cores, 25 GB, WSL2 on a laptop CPU) with slide images of one lecture,
single runs. They replace the guesswork above. The first table uses the first 60 images, the second all 240;
the 240 are slower per slide on average (the same layout reads 3.86 slides per second on the 60 and 2.93 on
the 240), so numbers from the two tables are not to be compared with each other.

**1. Most of the CPU was spent waiting, not working.** ONNX Runtime's worker threads busy-wait ("spin") between
two pieces of work. Reading one slide is dozens of small inferences, so the threads never stop spinning.
One engine, one caller, 60 images:

| Thread pool | Spinning | Slides per second | Cores busy | CPU seconds per slide |
|---|---|---|---|---|
| 8 | on (the library default, what ran until now) | 4.35 | 20.8 | 4.77 |
| 8 | off | 3.86 | 4.4 | 1.15 |
| 4 | off | 2.97 | 2.9 | 0.98 |
| 1 | — | 1.19 | 1.0 | 0.87 |

The real work is about one CPU second per slide; the default configuration burned almost five. That explains
the CPU load, and it is measured. That it is also what made lectures read at the same time starve each other is
an inference: the threefold gain in wall time came from the limit on concurrent calls above, and switching the
spinning off changed the wall time by nothing (section 10).
Spinning is now switched off (`frames._stop_spinning`; the OCR library has no setting for it): with three callers
on the shared engine, throughput went from 4.50 to 4.11 slides per second and the machine from 22 busy cores to 12.

**2. More engines do not help on this machine; the most likely reason is memory, not cores.** 240 images, spinning off,
each layout in a fresh process:

| Layout | Slides per second | Cores busy | CPU seconds per slide |
|---|---|---|---|
| 1 engine, pool of 8, 1 caller (one lecture) | 2.93 | 5.9 | 2.0 |
| **1 engine, pool of 8, 3 callers (the default)** | **4.11** | 11.8 | 2.9 |
| 1 engine, pool of 8, 6 callers | 3.50 | 16.8 | 4.8 |
| 3 engines × 8 threads | 4.32 | 15.2 | 3.5 |
| 6 engines × 4 threads (one process / six processes) | 3.69 / 3.65 | 18.7 | 5.1 |
| 12 engines × 2 threads (one process / twelve processes) | 2.88 / 2.80 | 21.5 | 7.6 |
| 24 engines × 1 thread (one process / 24 processes) | 2.37 / 2.36 | 23 | 9.7 |

Twenty-four single-threaded engines together read 2.36 slides per second, fewer than three callers on one
engine, and a slide then costs 9.7 CPU seconds against 2.0 with one caller. Threads or processes make no
difference, so it is neither Python's lock nor the OCR library. A control experiment points at the machine's
memory: the same job run 1, 6, 12 and 24 times at once takes

| At once | Pure CPU loop, seconds per job | Memory-heavy numpy loop (72 MB array), seconds per job |
|---|---|---|
| 1 | 0.40 | 0.64 |
| 6 | 0.42 | 1.09 |
| 12 | 0.54 | 2.26 |
| 24 | 0.69 | 5.72 |

The cores are real for pure computation (24 jobs at once are 1.7 times slower each, so about 14 times the
throughput of one). For memory-heavy work the machine tops out at about 3.5 times one job, and convolutional
inference over a 1280 × 720 image is memory-heavy work. No layout on this machine reads more than 4.5 slides per
second, and the layouts with an engine per process stay below 3.7. This is a consistency argument, not a
measurement of the OCR's memory traffic: memory bandwidth is the most likely cause, not a proven one.

What follows from it:
- The defaults stay as they were (one engine, pools of 8, three calls at once); only the spinning goes.
- An engine per slot, which the code review suggested measuring, is not faster and uses more CPU.
- Splitting the OCR of a lecture into batches cannot make this one machine faster. What it buys is that a failed
  batch is retried alone and that the OCR can be given to other machines. Whether other machines add throughput
  has not been measured: there is one machine.
- `MEDIA_OCR_SLOTS=0` used to block every OCR call forever; it now counts as 1.

The scripts are in `eval/ocr-bench/` (`bench_one.py`, `bench_layouts.py`, `cpu_scaling.py`); CPU is read from
`/proc/stat`.

## 9. Course Q&A (2026-11-13, mock model)

A question is one `COURSE_QA` task: `QA_AGENT` (a ReAct agent with three tools bound to the course, checkpointed
after every step) → `QA_VERIFY` (code checks time and quote, one judge-model call checks support). If at least
one claim and more than a third of the claims are dropped, the agent answers once more with the reasons, and
the answer is verified again. **Everything below ran with the mock model**: it shows that the flow and the
verification behave as designed, not how good the answers are. The mock answers from the first search hit; a
marker in the question makes it invent the quote (`FABRICATE`) or overclaim (`UNSUPPORTED`).

| Question | Result |
|---|---|
| "reinforcement learning from human feedback" | 1 claim kept, evidence L1 68:21-68:42 (slide) quoting "Reinforcement learning"; the media service's frame list shows that slide appearing at 68:20. 1 round, about 1 s. |
| "FABRICATE what is pre-training" | the claim is dropped at layer 2 ("the quoted text is not in L5 48:30-52:52"); a second round runs and is dropped again; final answer: no claim, 1 dropped, 2 rounds. |
| "UNSUPPORTED how are agents evaluated" | the quote is real and passes layers 1 and 2; the judge rejects the claim (layer 3); second round, same outcome. |
| a nonsense question | the mock still answers from the nearest segment and the mock judge accepts it. Search always returns the closest segments, so "this course does not cover that" depends entirely on the model; it has to be measured with a real model. |

**Killing the worker mid-answer** (`scripts/chaos-course-qa.sh`, mock model slowed to 4 s per call): the worker
running the agent was killed after its first step. The other worker resumed from step 2 from the checkpoint;
the search of step 1 ran once; the answer was verified and completed 51 s after the kill (Kafka's session
timeout is 45 s).

The Q&A page (`/qa.html`) was checked in a browser: the two steps and their times, a kept claim with its
evidence chip, a click on the chip positioning the player of that lecture at 68:21 and showing the quote, a
dropped claim struck through with the layer and reason, the second round's steps, the per-lecture comparison
table (with a synthetic two-lecture answer), the conversation record, and no console errors.

After the change to the concept directory (each concept must quote its segment), one lecture's directory was
regenerated: 12 concepts, all 12 with a quote found in their segment.

## 10. Slide OCR in batches (2026-11-14)

Reading the slides used to be one subtask per lecture, one frame after the other. It is now three steps
(plan: `docs/superpowers/plans/2026-11-14-ocr-fanout.md`): **pick** the key frames whose picture changed (media
service), **read** them in batches of 20 (one `LEC_OCR` subtask per batch, spread over the workers by Kafka; each
worker reads at its own OCR address, which can be an OCR replica on another machine), **merge** the batches in
time order (inside the index step). The batches start as soon as the pick is done and do not wait for the
transcription. Same server as before, mock model, single runs.

**The result is the same, slide for slide.** Eight lectures were read again and compared with what the old
code had produced: 620 slides before, 620 after, same times, 620 of 620 texts identical. That needed one change
on the way. The OCR now reads a stored picture instead of the decoded frame, and from a JPEG it reads worse
(60 frames of one lecture, against the text read from the decoded frame):

| Stored as | Texts identical | Spaces between words | Size per frame |
|---|---|---|---|
| decoded frame (before) | — | 1,256 | — |
| JPEG quality 80 | 11 of 60 | 1,055 (−16%) | 56 KB |
| JPEG quality 92 | 11 of 60 | 1,140 (−9%) | 78 KB |
| JPEG quality 95, 4:4:4 | 14 of 60 | 1,195 (−5%) | 117 KB |
| **PNG (what is used)** | **60 of 60** | 1,256 | 232 KB |

Words that run together cannot be found by a search, so the picked frames are stored as PNG; the merge turns
the kept ones into JPEGs for the page and deletes the rest.

**Speed on this one machine: unchanged; the load on the machine fell.** The harness of section 8 (eight
lectures' slides read at once, 1,259 OCR calls, straight against the media service, no Kafka): 405 s at a load
average of 26 before, 409 s at a load average of 7 after (10 busy cores on average; busy cores were not recorded
before, and the earlier run shared the machine with a test run for about two minutes). In the controlled
measurement of section 8 the default layout went from 22 busy cores to 12 when the spinning was switched off.
Batching itself buys no time here: the machine reads about 4 slides per second whatever the layout.

**Single lectures, measured again.** Through the engine; these are not effects of this change.

| | 2026-11-13 | Now |
|---|---|---|
| 77-minute lecture, transcription and slides both done, download not counted | 212 s (transcription 196, slides 153) | 135 s (transcription 128; pick 10, OCR 31 in 5 batches; index 5); 146 s with a cold transcription model |
| The same with thread spinning left on (control) | | 137 s (transcription 131; pick 9, OCR 32) |
| 118-minute lecture, slides only (263 frames, 14 batches) | 238 s | 95 s (pick 13, OCR 82) |

The 2026-11-13 numbers were taken before the limit on concurrent OCR calls existed, and the limit was never
measured on a single lecture, so how much of the difference it accounts for is not known. The control row varies
only the spinning (137 s against 135 s: no effect); batching was not varied. What moved the wall clock of the
77-minute lecture is its transcription, 196 s then and 128 s now.

**OCR replicas on the same machine do not add throughput, as section 8 predicts.** 400 picked frames of one
lecture; each replica pinned to 6 cores of its own, one batch at a time, pool of 6 threads:

| | Slides per second | Cores busy |
|---|---|---|
| 1 replica | 2.59 | 4.9 |
| 2 replicas | 3.92 | 8.7 |
| 4 replicas | 4.10 | 15.4 |
| no replica: the media service itself, three batches at once | 3.96 | 11.4 |

Two replicas are 1.5 times one, four are 1.6 times one: the memory limit again. This is **not** evidence that
replicas on separate machines would or would not scale; that needs a second machine and has not been measured.

**What the batches do buy: a failure costs one batch.** Through the engine, two workers, each with its own
replica, the 118-minute lecture (transcription cached):

| | Until searchable | OCR step |
|---|---|---|
| nothing killed | 120 s | 93 s; the replicas read 6 and 8 batches, the media service none |
| one replica killed with SIGKILL 32 s in, started again 10 s later | 130 s | 103 s |

In the second run the four batches in flight were cut and four more failed while the replica was down: eight
failed attempts, all sent to the 5-second retry topic, all succeeded on retry. A retried batch is sent again by
its key and can land in another partition, so most of them went to the other worker, whose replica read 12 of
the 14 batches; the restarted one read 2. No batch that had finished was read again, the lecture ended READY, and
its 170 slides are identical to the original ones.

A retried batch lands in the same partition on every retry, though. With a replica that stays down, a batch could
fail all its retries, and one failed batch means a lecture without slides. Since the review the worker falls back
to the media service when its replica cannot be reached (`MediaClient.ocrBatch`); that is covered by a unit test,
not by a drill.

**Several lectures at once through the engine, and what the batches were waiting for.** The review of this work
pointed out that the rows above are single lectures or bypass Kafka. Three lectures (46, 54 and 61 minutes; 96,
400 and about 420 picked frames) added at the same moment, transcription and slides both redone, two workers:

| | All three searchable | Slides of each lecture read by | Transcriptions done at |
|---|---|---|---|
| batches of 20, one topic for everything | 353 s | 223 / 320 / 342 s | 101 / 213 / 305 s |
| one batch per lecture, one topic for everything | 332 s | 152 / 288 / 324 s | 89 / 203 / 312 s |
| **batches of 20, transcription on its own topic** | 339 s | **53 / 191 / 299 s** | 121 / 220 / 333 s |
| one batch per lecture, transcription on its own topic | 337 s | 75 / 287 / 328 s | 96 / 207 / 312 s |

- In the first row the shortest lecture's five batches took 214 s although they are 25 s of work, and its index
  waited another 126 s: a consumer thread sits in the transcription call for as long as the lecture waits for
  the GPU, and everything queued behind it in that partition waits too. With the OCR in batches a lecture is
  exposed to every partition instead of one, so the batches made this worse (353 s against 332 s).
- The fix is the first step of the isolation the design leaves to version two: transcription has its own topic
  and its own consumer threads (`Topics.forSubtask`). The slides are then read as fast as the machine reads them
  (53 / 191 / 299 s), and small batches are no longer the slower choice, so the default stays at 20.
- The time until all three are searchable does not move (332 to 353 s): it is the queue for the one GPU.
  The order in which the lectures get the GPU is arbitrary and differed between the runs, which is why the table
  gives the time the slides were read, not the time each lecture became searchable.
- Single runs. The transcripts and slides of all three lectures were identical after every run.

**Killing a worker during ingestion** (`scripts/chaos-course-ingest.sh`, on the final code): the same three
lectures, one of the two workers killed with `docker kill` 25 s in, while transcriptions and OCR batches were
running, and started again 21 s later. All three lectures ended READY after 409 s (339 s without the kill).
The media service counted 0 downloads, 3 transcriptions and 3 slide merges for the three lectures: nothing that
had finished was done again, and the transcripts and slides are identical to the original ones.

Tests after this work: Java 393 (common 6, server 163, worker 224), retrieval service 44, media service 110.

## 11. Evaluation tooling and drills that need no real model (2026-11-14)

The evaluation of the design's section 11 is prepared (plan: `docs/superpowers/plans/2026-11-14-course-video-4-eval.md`,
tools: `eval/course/`). Nothing here says anything about answer quality: the course environment runs the mock model.

**The four systems run through the engine.** They are the same `COURSE_QA` task with different options
(`payload.eval`): S1 the full system; S2 a fixed pipeline (one search, one model call, verification); S3 the agent
without the judge; B1 the whole course's transcripts in one model call. Three example questions, one round each,
twelve runs, all completed; scoring and the report (`eval/course/score.py`) ran on the result.

| System | Model calls for one question | Tool calls | Prompt size of the answering call (mock estimate) |
|---|---|---|---|
| S1 | 3 (agent 2, judge 1) | 1 | about 1,700 tokens in total |
| S2 | 2 (answer 1, judge 1) | 0 | about 1,300 tokens in total |
| S3 | 2 (agent 2, no judge) | 1 | about 1,400 tokens in total |
| B1 | 1 (no judge) | 0 | about 168,000 tokens: the 11 lectures are about 673,000 characters of transcript |

The last row is the one real number here: the 11 lectures are about 673,000 characters, about 168,000 tokens,
and the long-context baseline sends all of it with every question. How much the other systems read per question
is not known yet: the mock model answers after one search, a real one will read more (the design estimates
about 30,000 tokens). The course fits the limit set for that baseline (800,000 characters); a longer course
fails that run with a clear error rather than being cut short.

**The whole course added again** (11 lectures, 12.7 hours, into a second course): all searchable after 57 s; the
media service counted 0 downloads, 0 transcriptions and 0 slide merges. Killing a worker during ingestion is in
section 10.

**Transcript spot check** (`eval/course/transcript_check.py`): three lectures, three 10-minute stretches each
(90 minutes), our transcript aligned word by word with the caption track the uploader put on YouTube. That track
has markers such as "inaudible" and "non English speech", so it was probably made by people, but that is not
known; this is a spot check, not a word error rate.

| | Caption words | Our words | Matched | Stretches of 5+ words only in the captions | Only in ours |
|---|---|---|---|---|---|
| 9 stretches, 90 minutes | 15,175 | 14,941 (98.5%) | 92.4% | 36, the longest 20 words | 7, the longest 9 words |

No passage is missing and none is invented: the unmatched stretches are a sentence or less, and several are the
same words written differently (a phone number spelled out against digits, the caption's "inaudible"). The
voice-activity settings were left as they are.

**Not measured:** how long the download stage of a whole course takes with one worker against three (the design
asks for it). It would mean downloading 12.7 hours of video from YouTube twice. The other two stages are known:
transcription does not get faster with more workers (section 7), and the OCR does not on this machine (section 10).

The question set, the real runs and the grading are in section 12. Still not done, because they need a person:
the judge's calibration against 100 hand-labelled pairs, and the comparison with a notebook product.

---

## 12. Course Q&A with a real model (2026-11-14)

The evaluation of the design's section 11, run once with a real model. Sections 9 and 11 used the mock model;
this is the first time the answers, the judge and the grades came from a real one. Full tables:
[`eval/results/course-haiku-v1.md`](../eval/results/course-haiku-v1.md).

**Setup**

- **Course**: the 11 lectures of section 8 (12.7 hours).
- **Model**: `claude-haiku-5-5` with thinking disabled, for answering, for the judge and for grading.
- **Questions**: 89, each with required points and gold time ranges — 60 about one lecture, 22 that need two or
  three lectures, 7 that the course does not answer. **A model wrote them** (Claude Fable 5.2, from the
  time-marked transcripts); nobody rewrote them. They were frozen before any system ran on them. Lecture 8 has
  no questions: drafting for that lecture was stopped by the model provider's content safeguards (its subject
  is biology) and was not redone. How the set was made and what the author was unsure about:
  [`eval/course/questions.md`](../eval/course/questions.md).
- **Systems**, all the same `COURSE_QA` task with different options:
  S1 the full system (agent with tools, three verification layers, one repair round);
  S2 a fixed pipeline (one search with 8 hits, one model call, the same verification, no repair round);
  S3 the agent without the judge (the code checks only);
  B1 every transcript of the course (speech only, about 200,000 tokens) in one model call, the code checks
  only, no repair round.
- **Runs**: S1, S2 and S3 three rounds each on all 89 questions; B1 one round on every third question (30).
  831 runs, none failed; 17.5 million input tokens, $4.75 at list price ($3.07 of it for the 30 B1 runs).
- **Scoring**: per question the mean over the rounds, then the mean over questions. Differences are paired per
  question with a bootstrap 95% interval; the intervals resample questions, so they do not include the
  variation between rounds or in the grader. "Correct" is the share of a question's required points that the
  answer states, judged point by point by the grader model — and the answer is only the claims that passed
  verification. For a not-in-course question it is 1 if the system said it found nothing.

**Results**

| | S1 full | S2 one search | S3 no judge | B1 long context (30 questions) |
|---|---|---|---|---|
| Correct | 70% | 48% | 72% | 56% |
| Correct, one-lecture questions | 73% | 50% | 76% | 54% (20 questions) |
| Correct, cross-lecture questions | 57% | 30% | 62% | 52% (8 questions) |
| Evidence falls in a gold time range | 97% | 79% | 96% | 86% |
| The same for every lecture of a cross-lecture question | 41% | 14% | 47% | 25% (8 questions) |
| Share of proposed claims removed by the code checks (no evidence, impossible time, quote not found) | 11% | 3% | 12% | 42% |
| Share of proposed claims removed by the judge | 8% | 18% | no judge | no judge |
| Share of the claims left in the answer that the judge rejects when asked afterwards | 5% [3, 7] (asked a second time) | — | 11% [8, 14] | 23% [12, 36] |
| Not-in-course questions refused (7 questions) | 81% | 86% | 67% | 2 of 2 |
| Answerable questions refused | 0% | 9% | 0% | 0% |
| Input tokens per question, median | 20,171 | 3,855 | 16,314 | 200,662 |
| Cost per question, median | $0.0029 | $0.0008 | $0.0023 | $0.102 |
| Seconds per question, median / p90 | 10 / 16 | 5 / 6 | 8 / 11 | 5 / 8 |

The three "share of claims" rows are means over questions. Pooled over all claims: the code checks removed
14.7% of S1's 1,318 proposed claims and 46.2% of B1's 104; the judge removed 9.6% of S1's.
On the 30 questions B1 ran, S1 is 68% correct, S3 72% and S2 47%, and S1's median cost is $0.0026. A prompt of
more than 100,000 tokens is billed on Haiku's higher rate card ($0.50 / $2.50 per million tokens instead of
$0.10 / $0.50), which is why B1 costs 35 to 39 times as much as S1 while reading 10 times as many tokens.

**The three expectations written down before the run** (design, section 11)

1. *"On cross-lecture questions S1 is correct more often than S2."* **Holds**: +27.3 points, interval
   [+12.9, +40.5], 22 questions; on all questions +22.2 [+15.4, +29.0]. One search does not reach two lectures:
   S2's evidence covers every lecture of a cross-lecture question 14% of the time against 41%, and it refused
   9% of the questions the course does answer. S1 also has a repair round, which S2 does not (used in 62 of
   S1's 267 runs).
2. *"S1 has a smaller share of unsupported claims than S3, without a clear drop in correctness."* **Holds by
   the judge's own measure, but not against blind labels** (see "The judge against blind labels" below). The judge was asked about the claims
   in the final answers of both systems: it rejects 10.9% of S3's (never judged before) and 5.2% of S1's (which
   it had already passed once), difference 5.3 points [+2.8, +7.9] over 85 questions. So the judge roughly
   halves the claims it objects to, and it contradicts itself on about one claim in twenty. Correctness is 2.1
   points lower with the judge, interval [−6.1, +2.1]: no measurable loss, though a loss of up to 6 points is
   not excluded. Against a referee other than the judge, most of what it removes turns out to be supported.
3. *"The course is small enough that B1 will probably be as correct as S1; the case for S1 is cost, evidence
   location and unsupported claims."* **Holds for what the model says, not for what it can prove.**
   - Counting only verified claims — the metric fixed in advance — B1 is lower: −11.8 points [−23.8, −0.6] on
     30 questions. This is borderline: B1 ran once, and against any single round of S1 the interval includes
     zero (for example −12.5 [−26.7, +1.7]).
   - Counting every claim the model proposed, verified or not (an analysis added after the run, 28
     questions), B1 is at least level: 80% [71, 88] against 71% [64, 79] for S1, difference +8.8
     [−0.0, +17.2], not separable from zero.
   - The difference is verification: it takes 27 points from B1 (interval [15, 39]) and 4 from S1 [1, 8].
     Quotes found word for word at the cited time: −35 points [−46, −24] for B1.
   - Evidence location (−13.1 [−27.4, −1.2]) is the same effect, not a second one: 3 of B1's 28 answers kept
     no claim at all and count as misses; of the 25 that kept one, 24 cite a gold range.
   - Cost: $0.102 against $0.0029 per question.

   So S1 is not "more accurate than a long context". It costs about 35 times less and its claims can be
   checked; the long context reads everything and cannot reliably show where it read it.

**Where quotes fail.** Every quote the models proposed was compared with the transcript and the slide text
(an analysis added after the run):

| Speech quotes | S1 (1,185) | B1 (127) |
|---|---|---|
| Found word for word at the cited time | 93.1% | 48.8% |
| Word for word, but somewhere else in the lecture | 0.5% | 13.4% |
| Differs only in punctuation | 1.0% | 4.7% |
| Small edits (a filler word dropped, "…" for a skipped stretch) | 5.1% | 22.0% |
| Reworded further | 0.3% | 11.0% |

| Slide quotes | S1 (374) | S3 (340) |
|---|---|---|
| Found word for word on the slide shown at the cited time | 48% | 50% |
| Same letters, different spacing or punctuation | 29% | 30% |
| Not on any slide of the lecture | 22% | 19% |

- With 200,000 tokens in the prompt, Haiku cites the wrong minute or tidies the sentence in half of its quotes;
  with a few search results in front of it, it copies. About four in ten of B1's rejected quotes are small
  edits a reader would accept.
- **The agent's own quote failures are mostly slide quotes** (about 70% of S1's failed quotes). The OCR text
  often has words run together ("usemoregpus"); the model writes them apart, and the check compares
  character by character. Three in ten slide quotes fail for spacing alone. This is a defect of the check, found
  by this run and not fixed in it (open problem E7).

**The judge.** Fifty verified claims were each given a real but unrelated sentence from another lecture as
evidence: the judge rejected 50 of 50. That is the easy direction. Asked twice about the same claim, it changes
its mind on about 5%.

**The judge against blind labels: it blocks more good claims than bad ones.** 100 claim-and-evidence pairs
from S1 and S2 (50 the judge passed, 50 it rejected, each with the excerpt the judge saw) were labelled
"supported or not" by the same rule as the judge's prompt. **The labellers were a model, not people**: two
separate Claude Fable 5.2 sessions that saw only the pairs — not the judge's verdicts, not each other, nothing
about the run. They agree on 96 of 100 (kappa 0.91), and on all 60 where both were confident.

| | Labellers say supported | Labellers say not supported |
|---|---|---|
| Judge passed (50) | 39 | 11 |
| Judge rejected (50) | 27 to 29 | 21 to 23 |

- **What the judge rejects is supported more often than not**: the labellers agree with only 42% to 46% of its
  rejections (interval for 21 of 50: about 29% to 56%). Where the first labeller was confident, 19 of the
  judge's 29 rejections were of supported claims.
- **What the judge passes is mostly fine**: 78% by the strict rule. The other 22% are almost all borderline —
  a supported claim carrying one extra detail that is not in the excerpt; only 2 of the 35 confident cases
  are clear misses.
- Weighted by how often the judge passes and rejects in the run (1,553 against 232): accuracy 73% to 75%; of
  the supported claims it blocks about 10%; of the unsupported ones (strict rule) it lets about three
  quarters through. With the judge the answer contains about 22% of claims that fail the strict rule, without
  it about 25%.
- So expectation 2 does not survive this check: the judge layer as built (Haiku, thinking off, all claims of
  an answer in one call, a 600-character excerpt per quote) removes a tenth of the good claims to catch a
  quarter of the weak ones. On this evidence S3 — the agent with the code checks only — is the better default
  until the judge is improved; the 100 labelled pairs are now a test set for that.
- Limits: 100 pairs, model labellers, one course. The labels are in the run directory, not in the repository
  (they quote the course).

**The grader moves by several points.** The first grading had a defect: in 11% of the replies the grader gave
one mark per claim of the answer instead of one per required point, and the script cut the list to length. The
grader now gets the number of points, a reply of the wrong length is asked again, and everything was regraded.
On the 650 answers whose first reply was well formed, the second grading scored 4 to 6 points higher on every
system — same answers, one sentence added to the prompt; one row in five changed. So the levels (70% correct)
are soft by about five points. The differences between systems moved less (S1 − S2 from +19.9 to +22.2, S1 − S3
from −2.5 to −2.1, B1 − S1 from −12.5 to −11.8) and no verdict changed.

**Not-in-course questions.** Seven questions, 21 runs per system: S1 refused 17, S2 18, S3 14. In 2 of S1's 4
non-refusals the answer itself says that the course does not give what was asked and then adds what it does
say; the rule (refused = "found nothing") counts them as failures. The other 2 answer a neighbouring question —
every claim supported by a real quote, none of them an answer to the question, which verification does not
check. Seven questions are too few to separate the systems.

**What this does not show**

- The questions come from a model of the same family as the systems, the grader is the same model as the
  systems, and nobody checked the grades by hand. The levels are not comparable with anything outside this run.
- Nobody reworded the questions. The design asked for that so that a question does not echo the lecture's
  wording and hand keyword search a free win; if these do, retrieval (S1 to S3) is favoured over the long context.
- One course, one model, one run. B1 ran once on 30 questions, so everything about B1 is wide or borderline.
- The comparison is not symmetric in two places: only S1 and S3 get a repair round, and only S2 and B1 get the
  reminder after the question (output format, copy the quote word for word).
- A gold range is one place where the answer is said, not every place: "every lecture of a cross-lecture
  question" punishes an answer that cites another valid passage.
- B1 saw speech only; S1 and S3 can also cite slides (14% of S1's kept evidence).
- The analysis without verification, the quote comparison and the judge's second look were added after seeing
  the results; they are exploratory.
- The second baseline of the design (a notebook product asked by hand) was not run.

**Found on the way**

- In the first smoke test the long-context call answered in prose instead of the JSON format, so every claim
  counted as "no evidence". The two modes without tools now repeat the format after the question; B1's numbers
  above are with that fix.
- The grader defects above: one reply in prose stopped the whole grading (a row that cannot be parsed is now
  skipped and retried), and one reply in nine had the wrong number of marks.
- One of the seven not-in-course questions (an algorithm name) is on a slide although it is never spoken; the
  questions were written from speech only.
- The largest S1 run read 69,393 input tokens (19 of 267 above 40,000): the agent's cost has a long tail.

---

## 13. Two changes after the first run, each measured by a rerun (2026-11-14)

The first run (section 12) pointed at two defects. Each was changed alone and the evaluation rerun on the same
frozen questions with the same model; the expectations were written into the plan before each rerun
(`docs/superpowers/plans/2026-11-14-course-video-4-eval.md`, parts three and four). All three runs are graded
by the corrected grader. Tables: [`course-haiku-v2.md`](../eval/results/course-haiku-v2.md),
[`course-haiku-v3.md`](../eval/results/course-haiku-v3.md).

| | Run 1 | Run 2: slide quotes compared by letters and digits | Run 3: concept directory written by the real model |
|---|---|---|---|
| Runs | 831, four systems | 831, four systems | 534, S1 and S3 only |
| Cost at list price | $4.75 | $4.69 | $1.45 |
| S1 correct / cross-lecture | 70% / 57% | 71% / 62% | 72% / 63% |
| S3 correct / cross-lecture | 72% / 62% | 74% / 64% | 73% / 65% |
| S2 correct / cross-lecture | 48% / 30% | 50% / 30% | not rerun |
| B1 correct (30 questions, one round) | 56% | 55% | not rerun |
| S1 slide quotes that pass the check | 48% | 72% | 73% |
| S1 claims removed by the code checks | 11% | 8% | 7% |
| S1 claims removed by the judge | 8% | 9% | 9% |
| S1 answers that needed the repair round | 62 of 267 | 50 | 40 |

**Run 2: slide quotes.** The OCR often runs words together, the model writes them apart, and the check
compared character by character. Slide quotes are now compared on letters and digits only; speech quotes are
unchanged. Differences are run 2 minus run 1, paired by question.

| Expectation written before the run | Result |
|---|---|
| The agent's slide quotes pass 70% of the time or more (was 48% / 50%) | **Holds**: S1 72%, S3 78% |
| S1's share of claims removed by the code checks falls below 9% (was 11%) | **Holds**: 8%; −3.9 points [−6.6, −1.2] |
| S1's correctness does not fall; a gain of 1 to 3 points, probably not separable | **Holds**: +1.1 [−2.8, +5.1] |
| The judge removes slightly more (more slide claims reach it) | Direction only: +1.3 [−0.7, +3.3] |
| Speech quotes, S2 and B1 do not move; the first run's verdicts keep their direction | **Holds**: S1 speech quotes 93.1% → 93.3%; S2 +2.0 [−0.9, +5.2], B1 −1.1 [−12.8, +10.6] |

- What still fails: 28% of S1's slide quotes are on no slide of the lecture — the model joins lines with "…"
  or rewords them.
- The fix shows in the verification numbers and not in correctness: the claims it rescues were mostly
  replaced by the repair round before (62 → 50 answers needed one).
- The first run's three verdicts in this run: S1 − S2 on cross-lecture questions +31.2 [+16.5, +45.1];
  S1 − S3 −3.2 [−6.5, +0.2]; B1 − S1 −18.2 [−32.6, −5.0].
- **The long context, two rounds together** (the change does not touch it): −17.7 points [−30.1, −6.3] against
  S1 when only verified claims count. In the first run this was borderline; with a second round it is not.
  Counting every proposed claim it is level: 82% against 77%, +4.9 [−4.5, +13.7].

**Run 3: the concept directory.** While run 2 was running it turned out that the course used in runs 1 and 2
had placeholder concept directories: the environment defaults to the mock model, which names concepts
"知识点 1, 知识点 2, …" and uses the first sentence of a segment as the summary. The agent's
`course_overview` tool reads that directory, and the agent calls it in every question. So in runs 1 and 2 the
agent's overview carried almost no information. For run 3 the same 11 lectures were added to a new course with
the real model switched on (downloads, transcripts and slide text came from the cache: all 11 searchable
after 60 s, 10 to 22 concepts per lecture, none failed), and the two systems that read the directory were rerun.

| Expectation written before the run | Result |
|---|---|
| "Evidence in every lecture of a cross-lecture question" rises by 5 points or more | **Not shown**: S1 +6.1 [−10.6, +24.2], S3 −6.1 [−22.7, +10.6] |
| Correctness on one-lecture questions does not move | **Holds**: S1 +0.6 [−3.6, +5.0], S3 −1.3 [−5.4, +2.5] |
| Input tokens per question rise by 10% to 30% | **Does not hold**: S1 −0.4%, S3 +5%, neither separable |
| If none of the three holds, the overview tool does not matter here | Correctness: S1 +1.1 [−2.6, +4.9], S3 −1.1 [−5.1, +2.7] |

- **A real concept directory changes nothing measurable.** On this course and these questions the agent finds
  what it needs by searching; the overview is called once per question and costs the same either way. Whether
  that holds for a much larger course, where a search cannot cover the ground, is not tested.
- It does mean the numbers of runs 1 and 2 were not held back by the placeholder directory, which was the worry.
- This was also the first time the concept directory ran on a real model (open problem Q2): the 6,000-token
  output limit was enough for every lecture.

**The judge across the three runs.** S1 minus S3 in correctness: −2.1, −3.2 and −1.0, none separable alone.
Pooled over the three runs (nine rounds per system) it is −2.1 points [−4.1, −0.0]: the judge costs about two
points. Together with the blind labels of section 12 (more than half of what it rejects is supported) the
picture is consistent: as built, the judge takes out good claims slightly more than it protects the answer.
Asked a second time, it rejects 5% to 7% of the claims it had passed, against 9% to 11% of S3's.

**Where this leaves the system**

- Kept: the agent over the fixed pipeline (+21 to +22 points, +27 to +31 on cross-lecture questions, in both
  full runs); the two code checks (time, quote); evidence in a gold range for 96% to 97% of answers.
- Changed and measured: the slide-quote check.
- Not earning its place yet: the judge layer. S3 is as correct as S1 or slightly better and 2 s
  faster per question, and about a fifth cheaper.
- Not better than the alternative, only cheaper and checkable: against the whole course in one call, the
  agent's answers are level in content and its evidence can be verified at a thirty-fifth of the cost.

**Limits added by this section**: the comparisons between runs carry run-to-run noise as well as the change;
the grader is still the same model as the systems; the three runs together cost $10.89 at list price for the
question-answering calls, not counting grading, the judge's second looks and the concept directories.
