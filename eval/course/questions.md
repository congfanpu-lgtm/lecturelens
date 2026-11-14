# 题集是怎么来的

`questions.jsonl` 一共 89 道题:单讲 60 道(第 1 到 7、9 到 11 讲各 6 道)、跨讲次 22 道、课里没讲的 7 道。
冻结于 2026-11-14,`questions.lock` 里是文件的 SHA-256;冻结之前没有用这些题跑过任何系统。

- **出题的是模型,不是人。** 全部题目、要点和标准区间由 Claude Fable 5.2 对着带时间标记的逐字稿写出
  (`author` 一栏是 `fable-5.2`),没有经过人工改写。被评测的系统用的是另一个型号(Haiku),但两者同属一家,
  出题的模型觉得"问得清楚"的题,对同一家的模型可能偏容易。这一点在报告里要一并写明。
- **只依据讲话。** 出题时只看了逐字稿,没有看幻灯片,所以没有"答案只在幻灯片上"的题。
- **第 8 讲没有题。** 起草这一讲题目的那一步被模型服务的内容安全策略拦下(这一讲的内容涉及生物),
  没有换别的办法重做。第 8 讲仍然在课程里,系统检索时会碰到它;"课里没讲"的 7 道题只对第 8 讲做过关键词计数,
  没有读过内容(各题的关键术语在第 8 讲里出现 0 次)。
- **标准区间**是能答出要点的那段讲话的起止时间,每段不超过 6 分钟;跨讲次的题每一讲至少一段。
- 第 6、7 讲的单讲题另外对着逐字稿逐题核对过要点和区间。

下面是出题时逐题记下的"拿不准的地方",原样保留(英文)。打分或看个例时如果某道题的结果奇怪,先来这里查。


---

# Notes A — lectures 1 and 2

## Absent

- How does byte-pair encoding decide which sub-word units are merged into a tokenizer's vocabulary? — L01 only says text is cut into tokens / sub-words and illustrates it by splitting on spaces (14:47–15:53); no tokenizer algorithm is described. Keywords to search: `BPE`, `byte pair`, `byte-pair`, `merge`, `WordPiece`, `SentencePiece`, `tokenizer`.
- How does self-attention use queries, keys and values, and how do positional encodings tell a transformer the order of the tokens? — L01 says explicitly that architecture will not be covered (09:27–10:32) and describes the network only as embeddings, a generic aggregator, a linear layer and softmax (21:28–25:41); L02 names GPT/BERT without mechanics. Keywords: `self-attention`, `query`, `key`, `value`, `attention head`, `positional encoding`, `RoPE`, `rotary`.
- Which vector index, chunk size and embedding model are recommended for the retrieval step of a RAG system? — L02's Q&A only says coarse ranking by keywords / vector embeddings followed by fine ranking with an LLM (65:59–67:02); no index, chunking or embedding model is named. Keywords: `HNSW`, `FAISS`, `Pinecone`, `vector database`, `chunk`, `chunking`, `reranker`, `embedding model`.

## Cross-lecture hooks

- Reinforcement learning on verifiable rewards (likely also L03, L05) — L01 70:44–73:35 (overview at 04:59–06:36): rewards are rule-based checks (string match, test cases), a reward model trained on human preferences, or an LLM judge; DeepSeek R1 uses verifiers for math/code/logic and a preference model for general prompts, starting from an SFT checkpoint. L02 18:14–19:21: RL matters because it defines the loss on the end result of a rollout instead of on the next token.
- Reward hacking (likely also L03, L04, L07) — L01 06:03–07:43 and 74:12–75:14: the model optimises the reward exactly as written, e.g. deletes the test or makes it always return true; GRPO's KL term to a reference model is described as a hack to limit such drift.
- Test-time compute / reasoning models (likely also L06, L03, L05) — L01 55:36–57:21: since o1 (2024) one can spend more compute after training, like a person given more time to think. L02 16:35–18:14 (and 12:14–12:45): the model "mumbles longer" and reflects on intermediate results; DeepSeek's paper let open models catch up; some call it the last leg.
- Sutton's bitter lesson (likely also L06, L11) — L01 48:34–50:40: more compute gives better performance and compute keeps growing, so do the simple thing that scales. L02 33:39–34:10: general methods plus large computation on large data, cited as the reason AI infra became a pillar of its own.
- Evaluation, LLM-as-judge and human annotators (likely also L04, L07) — L01 83:09–89:18: closed-ended evals (MMLU) suffer from prompt sensitivity and train–test contamination; open-ended ones use blind human pairwise comparison (Chatbot Arena) or an LLM judge giving a win rate (AlpacaEval, high Spearman correlation, under $10). L01 80:22–83:09: human labels are slow, costly, biased to form and length; LLM annotators were 10–30x cheaper and agreed with humans more than humans did with each other (66%).
- Synthetic data with simulated users and tools for tool use (likely also L05, L09) — L01 64:27–66:42 (and 60:40–62:19): Kimi K2 trains tool use by SFT on rollouts of an agent LLM against an LLM-simulated user and LLM-simulated tools, filtered by rejection sampling; Alpaca used a stronger LLM to write answers.
- Hallucination and grounding / RAG (likely also L08, L09) — L01 68:25–70:44: cloning correct answers the model does not know (e.g. a citation) teaches it to make up plausible references. L02 64:25–68:11: the boundary between common knowledge and facts from the context is blurry, which is why retrieval and clean data pipelines matter, especially in enterprise search; retrieval is coarse ranking (keywords, embeddings) then LLM fine ranking.
- Count-based n-gram prediction as the root of next-token prediction (pair inside L01/L02) — L01 17:30–21:28: counting continuations of "the grass is" works but needs infeasible memory and gives zero probability to unseen sequences, hence neural networks. L02 05:33–08:14: n-gram counts were used to lay out Chinese typewriters; counting stops at about 5-grams, LLMs look at millions of tokens.
- Mixture of experts / sparsity (pair inside L01/L02) — L01 115:38–117:20 (and 09:27–09:59): each data point passes through only some parameters, which makes multi-GPU training easy; the standard open architecture. L02 16:04–17:33: sparsely activated model that is smaller and more efficient at similar quality, compared to ensembles and Inception.
- Enterprise deployment and where startups add value (likely also L09) — L02 29:13–31:23 and 74:13–75:16: enterprise applications need heavy integration work (Glean: connectors plus access control, $100M ARR in about three years); startups win in vertical enterprise areas by combining domain knowledge with LLMs rather than training another foundation model.

## Transcript problems

Lecture 1

- No Q&A: the lecture is a re-recording and ends at 118:01 with "no questions today".
- Parts that need the slides: scaling-law plots (42:35–43:38, 45:12–46:19), FineWeb accuracy plot with coloured lines (34:08–36:49), AlpacaFarm cost/agreement plot (81:35–82:38, "humans are in blue"), Kimi K2 tool-use pipeline diagram (65:00–66:42), GPU memory hierarchy (94:54–95:28), ZeRO table (111:38–112:45), and above all the tiling diagram (101:52–104:51), where the speaker himself says it is hard to explain without showing it. Avoid questions on the mechanics of tiling.
- Sentences cut at line boundaries, so the fact is lost: 03:56 "compute costs maybe around like a hundred" (amount missing); 02:47 start; 06:03 end; 51:44–52:22 (assumption behind the $52M estimate garbled); 57:21 (the human analogy for test-time compute); 84:14 (the definition of closed-ended evaluation starts mid-thought); 97:21 (start of the low-precision explanation).
- Stage numbering is confusing: RLHF is called the "second part" in order of presentation but "third in chronological order", reasoning RL the "last part" but "second in the pipeline" (02:47–05:31, 55:36–56:08). A question on "which stage comes second" would be unfair.
- Numbers to treat with care: 38:01–38:36 context length said as "100 120 tokens" and "4,000 ... to 128" (means 4K to 128K); 35:14 "360 billion tokens" (training subset) next to 36 trillion; 51:10 3.8e25 FLOPs called "around two times less" than 1e26; 107:35 "16 times p gigabytes" (means 16 bytes per parameter); 110:59 "120 12 gigabytes" (112); 112:10 the ZeRO example uses 120 GB to 31 GB, not the 112 GB of the previous slide; 96:41 "higher than 15 or 20%" for MFU is ambiguous.
- Recognition errors in names: Llama appears as "Lama", "LIMA2/LIMA3", "lemma 3 400b" (but "Lima paper in 2023" at 66:42 really is the LIMA paper); DeepSeek as "DeepSeq", "EAP-Seq", "DPC-3", "deepsig v3"; Kimi as "Kimmy", "QMI2", "KimiK"; RLHF as "LHF", "all hf"; RL as "oil" (68:25); bitter lesson as "beta lesson"; WARC as "walk files"; The Pile as "the PAL"; AlpacaFarm as "alpaca form"; AlpacaEval as "Alpaca Evil"; ZeRO as "zero"; o1 as "01"; an unidentified "XLIME paper" at 64:27; "log rollout" at 75:44.

Lecture 2

- Audience questions are not audible; only the speaker's restatement survives, usually in the middle of a line and sometimes not at all: 58:54/59:28, 61:42, 64:25, 66:30–67:02, 68:11–68:44, 74:13, 75:16, 77:39; the questions that lead to 70:55 (startup lessons) and 72:35–73:06 (researchers and engineers) are missing entirely.
- Inconsistent numbers: 13:19–13:53 daily tokens go from "50 billion to 60 billion" to "4.9 trillion", called "about 10x growth" (the figures give about 100x); 61:42 "the cost of serving those tokens is actually still higher than the cost" (means price). Do not ask for the growth factor.
- Parts that need slides or the demo: model timeline (10:30–11:40), OpenRouter chart (13:53–14:57), Gartner hype cycle (19:21–20:27), live browser-app demo (22:10–23:11), a16z top-50 list (23:45–25:24), GPU failure chart (44:20–45:28), platform architecture slide (45:28), hardware photos (50:05–53:25), scale slide (55:01).
- Garbled stretches: 09:23–09:59 (projector trouble), 16:35–17:33 (Inception / "tensile static composition"), 53:57–54:30 ("disaggregated like pre-filling" is cut off), 75:50–76:32 ("pop up entrance" = points of presence).
- Recognition errors in names and terms: Lepton as "Leapton", "leptin", "Leviton"; NVL72 as "MEL72", "MGL72"; NVLink as "NV switches"; Qwen as "Quan", "Qianwen", "Quinn"; DeepSeek as "DeepSeq"; Chinchilla as "Chantilla"; CoreWeave/Lambda as "core we've lambda", a European neo-cloud as "cotton abuse"; Plaud as "Ploud"; Glean also as "Lean"; RAG as "rack", "RAC", "WAG"; LLM as "ELMO", "other lemmings"; SGLang as "SGLM"; Sutton and Barto as "such an aborto"; Oriol Vinyals leading Gemini as "Oreo Venus ... leading Germany" (43:16); Demmel and Yelick as "Stemmel and Kat Jellick"; Bill Dally as "Bill Daddys"; "interoperable" at 60:36 means interruptible; "more slow equivalent" at 63:52 means a Moore's-law equivalent; "course ranking" = coarse ranking; "concept window" = context window; "4D keyboard" = QWERTY; "40 gpus" = faulty GPUs; "Odin" at 64:25 probably coding; "SciAero" (legal AI, 30:50) unidentified.

---

# Notes B — lectures 3, 4, 5

## Absent

- (near L03) How is a process reward model (a learned scorer for each intermediate reasoning step, as opposed to a verifier on the final answer) trained and used when post-training verifiable agents? — L03 only has verifiers on final outputs / whole trajectories; L05 only has unit-test, LLM and rubric graders. Search keywords: `process reward`, `PRM`, `step-level`, `intermediate reward`, `partial credit`.
- (near L04) Which chance-corrected agreement statistic (Cohen's kappa, Krippendorff's alpha) should be reported to quantify disagreement between human annotators, and what value counts as acceptable? — L04 describes inter- and intra-annotator disagreement only qualitatively and gives no statistic. Search keywords: `kappa`, `Cohen`, `Krippendorff`, `agreement coefficient`, `annotator agreement`.
- (near L05) What LoRA rank (and scaling factor alpha, and which layers get adapters) is recommended for reinforcement-learning fine-tuning? — L05 gives only the regularisation argument and names "LoRA Without Regret"; no rank, alpha or layer choice is ever stated. Search keywords: `rank`, `alpha`, `adapter`, `LoRA`, `low-rank`.

## Cross-lecture hooks

- Verifiable vs. non-verifiable tasks — L03 [04:14–05:19] (conversation is not verifiable; agent tasks need a clear verifier), L04 [11:17–14:35] (verifiable = an oracle such as a proof checker or code interpreter; non-verifiable needs humans or an LLM judge), L05 [06:56–08:32] (verifiable: math, code, multiple choice; non-verifiable: style, writing, safety). Likely also in L01.
- SFT vs. RL in post-training (and RLHF) — L03 [42:21–49:23]: SFT is light imitation to jump-start, RL reinforces diverse good attempts; L03 [02:09–04:14]: RLHF pipeline with a reward model. L05 [57:15–59:54]: RL trades compute for robustness and is steered by graders instead of a reward model. Likely also in L01 (LLM overview).
- Entropy collapse / keeping exploration alive in RL — L03 [56:50–65:47]: definition, on-policy training, decoupled clipping, entropy loss. L05 [19:11–20:19]: data synthesis raises entropy and pass@k; L05 [35:11–38:21]: exploration via instruction following, prompt and tool variation.
- SWE-bench and SWE-bench Verified — L03 [27:37–29:12]: must be solved under several harnesses. L04 [78:35–82:01]: why Verified was built, 93 developers, 68.3% filtered, 500 tasks, GPT-4o 33.2% vs 16%. L05 [29:12–29:44]: model copies the fix from the internet; L05 [42:09–42:45]: leaderboard reports pass rate without rollout length. Likely also in L01, L07.
- Benchmark contamination, saturation and overfitting — L03 [32:56–38:09]: hardness (GSM8K), separability, diversity, private vs. many public benchmarks. L04 [41:35–43:15] and [47:10–48:20]: hard-to-contaminate / hard-to-saturate principles, post-knowledge-cutoff test items; L04 [19:52–22:27]: dynamic benchmarks. Likely also in L07 (noise in benchmarks).
- LLM-as-a-judge and rubric grading — L04 [17:11–19:52]: cheap, consistent with humans, fix randomness and bias by repetition and voting, cross-check with humans. L05 [08:32–11:40] and [26:05–27:10]: expert-written scorable rubrics, LLM grader as one of three grader types. L03 [14:15–15:46]: verifier as a vector. Possibly also L08 (AI reviewers in science).
- Gaming the evaluator / reward hacking — L05 [28:10–30:49]: model deletes or edits tests, copies solutions online; hide tests, second model, penalty. L04 [33:46–35:23]: prompt-injecting an LLM judge, invisible text in papers; L04 [37:36–38:40]: evaluation must not be hackable. L03 [18:29–21:13]: verifier corner cases.
- Tool use, function calling and tool generalisation (MCP) — L03 [12:39–14:15]: tools as APIs that fetch information or change state; L03 [23:20–23:50]: must survive modified tools. L05 [38:21–39:55]: users plug in any tool via MCP, so train on many tools and penalise wrong formats. L04 [68:19] and [73:26]: MCP / A2A protocols between agents and platform. Likely also in L01, L02, L09.
- Customer-service agents and tau-bench; vague user requests — L04 [48:51–56:00]: tau-bench and tau²-bench design, pass^k reliability metric. L03 [28:40–29:12]: tau-bench named; L03 [23:50–24:53]: users give vague instructions, agent must ask proactively. L05 [05:50–06:22] and [24:31–25:03]: user interaction and product constraints. Likely also in L09 (deploying agents).
- Parallel sampling, voting and multi-agent set-ups — L03 [72:37–75:24]: GenSelect (pick one of 32 candidates) and DeepConf (confidence-weighted voting). L05 [53:59–55:04]: parallel thinking should be trained for; multiple agents under an orchestrator. L04 [84:09–85:15]: multi-agent benchmarks encouraged (synthesiser + analyser); L04 [18:15–19:19]: majority vote across judge runs/models. Likely also in L06, L10.
- Cost and efficiency as part of the score — L03 [14:45–15:16]: a correct answer with too many steps/tokens may be penalised. L04 [63:48–64:26]: CRMArena reports turns, tokens and dollar cost. L05 [40:00–42:45]: length penalty trade-off; results are not comparable without equal test-time compute. Possibly also L02 (system design), L09.

## Transcript problems

General: all three transcripts mis-recognise key terms, so keyword search on the correct spelling often fails; questions that name a method or benchmark should also describe it.

L03
- "IAM / ILM / IRM / arms / Alam" = LLM; "Superspeed / Superspun / super-span tuning", "SRT", "SFG" = supervised fine-tuning (SFT); "sweep bench / three-bench / 3Bench" = SWE-bench; "ME and math" = AIME; "RO stage" = RL stage; "falls positive" = false positive; "self-engineering / server engineering" = software engineering; "REPL / REPL has issues" = repo.
- [59:58] says "either on-policy or on-policy" (should be on-policy or off-policy); [61:29] "if you use some policy" = "on-policy".
- "DAPO" is transcribed correctly at [62:32] but as "Doppler math" at [68:26]; "Andrew B. McKay's paper" [65:47] = the entropy-mechanism paper; "GRPOD paper" [70:32] is an unidentifiable GRPO variant; "SkyWork or Open Reasoner" = Skywork Open Reasoner; "yours RL data / URS-IRL data" [68:26–69:30] = "ours RL data" (dataset names on a slide).
- Slides are needed for [57:21–59:27] (entropy/performance plot), [59:58–62:32] (on-/off-policy curves), [62:32–64:41] (clipping formula, "purple line"), [65:47–68:26] (covariance plot; the definition is cut mid-sentence at [66:19]→[66:54]) and [68:26–70:32] (which curve is which dataset). Questions on these parts should stick to the conclusions stated in words.
- Text is dropped mid-sentence at [21:46]→[22:19], [51:00]→[51:30], [63:03]→[63:35] and [66:19]→[66:54].
- The "Q&A" at [51:30–54:15] contains no audible question, only the speaker's answer about pattern matching; there is no Q&A at the end.

L04
- Two presenters; their names and the demo presenter's name are garbled ("Paifeng", "Xiuyu / Shuyi", "Xiao Yuan"). The announced live demo of the platform is not in the transcript (it ends at [94:17]).
- "green engine / green engines / re-agent / reagent / billing agents" = green agent; "AgentBees / Agent B / agent-based / agent bits" = AgentBeats; "8-way" = A2A; "problem-based toolkit" = prompt-based toolkit; "tax / tags / text / tag" very often = task(s); "involvement" = environment.
- Benchmarks: "cyber gene / cyberjacking / Cyber Dream / cyberjain" = CyberGym; "tall bench / whole bench" = tau-bench; "tall square bench / Tower Square Bench / TOSquareBench" = tau²-bench; "positive one / positive K / Parsec-A" = pass^1 / pass^k; "GDP eval / GDP-VAL" = GDPval; "MLOU" = MMLU; "local mode" = LoCoMo; "MemGBT" = MemGPT; "SweetBench / sweepbench" = SWE-bench; "Clocked 4.5 / Cloud 4.5" = Claude 4.5; "simple-demo-org" = Simple Demo Org; "VATI professionals" probably = vetted professionals.
- "stream matching" = string matching; "fast testing" [35:56] = fuzz testing, and that sentence is garbled; "hat performance" = hack performance; "subjective charging" = judging; "authorizing" = authoring; "minibash prompting" = mini-batch prompting; "power phrasing" = paraphrasing.
- Text is dropped or garbled at [13:15]→[13:31], [35:56]→[36:29], [41:03], [54:17]→[54:54] (tau² data pipeline, hardly intelligible), [71:44]→[72:20] (the list of green-agent components is cut) and [91:21].
- Slides are needed for the benchmark lists [24:36–28:21], the outcome-validity screenshots [30:28–37:36] and the checklists [85:47–90:16]; the SWE-bench Verified percentages [79:45–80:56] are spoken clearly, but "38.3% ... other specification issues" most likely means underspecification.
- [64:26–94:17] is the project overview (logistics mixed with substance); only s04-6 is taken from it.

L05
- The speaker's name is recognised as "richard" at [00:00]. "IELTS / IL / IO" = RL; "SAP / SMT / SFP" = SFT; "problem / poem" often = prompt; "inspiration" = exploration; "channel / chain / change the model" = trainer / train the model; "length model / linked model" = language model; "same box" = sandbox; "roller" = rollout worker; "all-policy / op policy" and "a policy learning" [32:58] = off-policy; "Parsec1 / ParsecK" = pass@1 / pass@k; "sweet bank / sweep and verify" = SWE-bench Verified; "Kimmy K2 / chemical paper" = Kimi K2; "the work from John, LoRa without regret" = "LoRA Without Regret"; "less ethics suggestion" = next-edit suggestion; "step improvement" = self-improvement; "Xiumei is very good at this" [55:35] = "human is very good at this"; "DECAMP data" [14:57] and "product host chain" [22:57] are unidentifiable.
- Slides are needed for [12:46–13:47] (one-example plot: "dotted line" vs "green line"), [14:21] ("pi one" vs "pi 17" are labels on a slide), [33:32–35:11] (asynchronous-training diagram) and [41:38] (length-control plot).
- Sentences are often fragmentary; e.g. [33:32] "we assign much more gpu" breaks off (presumably: to sampling), [42:45] "support up to" breaks off before the number (one million tokens, from the next sentence), and [00:34] "in microsoft for about 20" lacks the unit.
- There is no Q&A in the transcript; it ends with the speaker's remarks on daily work at [64:04].

---

# Notes for lectures 9, 10, 11

## Absent

- Which specific speech-to-text and text-to-speech models or vendors does the customer-service voice pipeline use? (L09 describes the pipeline, several STT models compared, a synthesis engine, but names no product other than the OpenAI real-time API as the thing it does not rely on.) Search: `Whisper`, `Deepgram`, `ElevenLabs`, `Cartesia`, `text-to-speech`, `TTS`.
- What limit on actions per minute or reaction time was imposed on AlphaStar to keep its play comparable to a human's? (L10 never mentions any speed or interface restriction.) Search: `APM`, `actions per minute`, `reaction time`, `camera interface`, `superhuman speed`.
- How does domain randomization help a policy trained in simulation transfer to a real robot? (L11 only says that direct sim-to-real transfer "is not always straightforward" and never names the technique.) Search: `domain randomization`, `randomiz`, `sim-to-real`, `sim2real`, `reality gap`.

## Cross-lecture hooks

- Agent benchmarks with simulated users, tool use and policies (tau-bench) and the pass^k reliability metric — L09 [31:24–36:25]: tau-bench (tool-agent-user) tests agents in telco/retail/airline scenarios against an LLM-played user, judges success by the final database state, and pass^k asks whether the task is solved in every one of k runs. Likely also in L04 (agent evaluations) and L07 (benchmark noise).
- LLM-as-judge versus verifiable outcomes, and LLM supervisors — L09 [34:21–35:22] and [39:54–41:11]: an LLM judge is said to matter less than a verifiable final action (mutated database), while supervisor micro-agents are used at run time to watch the main agent. Likely also in L03 (verifiable agents) and L04.
- Prompt injection, red teaming and guardrails — L09 [42:47–46:10]: outside and internal red teams, layered checks on input, inside the agent and on output, and deterministic access control for systems of record; L10 [56:52–57:30] adds that automatic red teaming uses multi-agent techniques but is in its infancy. Possibly also L01/L04.
- Non-determinism of agents and why ordinary software testing is not enough — L09 [12:17–13:55] and [31:24–32:32]: for a given input the output is not known in advance, so unit and integration tests must be replaced by many realistic simulated conversations. Relates to L07 (noise in LLM benchmarks) and L04.
- Memory across sessions / RAG — L09 [11:13–12:54]: agents "have amnesia" between sessions, RAG is a lightweight fix, and a memory-and-context foundation is needed for a "warm start". Probably also in L01/L02.
- Imitation first, then reinforcement learning (pre-training then post-training) — L10 [14:07–15:15], [19:47–22:22], [26:19–31:26]: AlphaStar needed human replays before RL, used a KL penalty to the supervised policy, and spent most compute in RL, unlike LLMs. Likely also in L01, L03, L05.
- The bitter lesson and the value of human priors — L10 [20:17–21:50] and [57:30–58:06]: pure RL gave a degenerate worker rush, and Vinyals holds that human priors plus RL is the shortest path to AGI; L11 [21:22–21:52]: human feedback makes TAMER learn faster but end lower than RL from scratch. (Hook between L10 and L11; maybe also L06.)
- Self-play, populations, rock-paper-scissors non-transitivity and Nash — L10 [37:38–42:00] and [44:44–48:47]: naive self-play cycles or collapses to one strategy, so AlphaStar trains a league with main agents, main exploiters and league exploiters. Likely also in L06 (Noam Brown, multi-agent AI).
- Tool use as API calls — L10 [15:15–19:47]: AlphaStar's actions were API calls emitted by a fixed autoregressive head, compared with LLM tool calling (MCP) through text or code. Likely also in L01/L02.
- Elo ratings and arena-style comparison of models — L10 [26:57–27:34] and [37:38–39:51]: Elo as the strength measure (a 40-point gap already meaningful), and models of equal Elo beating each other in cycles. Relates to L07/L04.
- What counts as an agent — L10 [01:49–05:06]: the RL loop (observations, actions, reward) versus today's user + harness + LLM + environment package; L11 [01:55–05:16]: sensors and effectors, a continuous loop that does not wait to be invoked, and perception/cognition/action. Likely also in L01.
- Humans learning from superhuman game agents — L10 [11:15–11:50]: Serral copied a build order AlphaStar had used against him; L11 [58:19–59:23]: Gran Turismo drivers said they learned new things from watching the agent. (Hook between L10 and L11; L06 may have a poker/Diplomacy analogue.)
- RLHF and learning from human feedback — L11 [19:09–23:06]: TAMER as the claimed first RLHF system, with explicit good/bad signals, later implicit cues (EMPATHIC). Likely also in L01/L03/L05 for LLM training.
- Teamwork with unknown partners — L11 [26:23–28:05]: ad hoc teamwork, an agent that cooperates with teammates it does not control. Possibly also in L06 (cooperation with humans).

## Transcript problems

Lecture 9
- [07:17] The name of the pricing model is missing (the sentence jumps from consumption-based pricing straight to "where a company only pays Sierra if..."); do not ask for the term "outcome-based pricing".
- [02:07] and [08:55–09:33] Sentences are cut ("They can pick up on They do everything"; "financial advice which is illegal ... invoice how do you mitigate latency"): the list of hidden problems is partly garbled ("invoice" = "in voice").
- [33:42] "TauBench 1" is probably a later version of the benchmark (tau-squared); do not ask which version introduced user-side actions.
- [35:22–35:53] The pass^k explanation is garbled ("95, 0.95 to asymptotically approaching zero", "Pass It K"); the exponent is never stated cleanly, and the leaderboard address is given hesitantly ("I think at taubench.com").
- [36:57–38:40] Played audio demo is only partly transcribed, and the speaker's recap (ID "S7, no, S6") does not match the transcribed tag (SD3782); unfair to ask about the demo's content.
- [27:35] The list of voice attributes depends on a slide ("Dips, verbal fry").
- Recognition errors: "Olakai" = OluKai, "Brett" = Bret (Taylor), "paralyze" = parallelize, "LLM is judge" = LLM-as-judge, "React loop" = ReAct, "chat GBT".
- No Q&A is in the transcript; [46:10–46:41] is a hiring pitch.

Lecture 10
- [08:27–09:04] and [10:42–11:15] Depend on video/slides; words are missing.
- [09:37] "Grandmaster ... top 100 players in any given region" (hedged with "I believe"); do not ask for this number.
- [13:33] Starts mid-sentence; "called tech leading together with jeff and noam" (= co-tech-leading Gemini).
- [18:08–18:41] A sentence is lost between "AlphaStar is like a language" and "differences that are notable".
- [20:17–20:47] Garbled ("pretty sharply in the next video so problem is random policy ..."; the bitter-lesson sentence does not parse).
- [27:34–28:04] Reads an Elo chart from a slide; the from-scratch and plain-RL values are not spoken (only 900, +400, +~150).
- [36:30–37:38] The definition of non-transitivity is cut ("this is called non-transitiveness. um opponent right"); [38:13–39:51] refers to a figure and a skipped slide.
- [40:55–42:00] "Here are the three figures ... the right most": needs the slide to know which population scheme is which.
- [49:25] "30,000 RPS cycles": the speaker himself is unsure what the number means.
- [51:05–51:43] Ablation strengths are on the slide only; [52:15–52:47] "variance was a little better" is vague.
- Recognition errors: "Sero/Serol" = Serral, "Void Raid/Boyd Raid" = void ray, "Zerbin" = Zergling, "Nurebs/Europe" = NeurIPS, "LMC" = LMArena, "soft play" = self-play, "leak exploiters" = league exploiters, "motivation learning" (32:39) = multi-agent learning, "outer red teaming" = auto red teaming, "HEI" = AGI, "Sackgraf" = StarCraft, "aggressive actions" (18:41) = autoregressive, "alpha style" = AlphaStar.
- No Q&A is in the transcript.

Lecture 11
- [08:05–11:37] and [13:56–14:34] Narration over videos (robot soccer, split-screen simulator versus real car); understandable but details are visual.
- [14:34–15:05] Garbled ("five of the four of the best human drivers", "this was in october of 20 we first raced them in july of 2021").
- [16:46–18:34] "Phoebe" = FHIBE, "Alice Shang" = Alice Xiang; the benchmark's feature list is on a slide and not read out.
- [22:30–23:06] The EMPATHIC acronym expansion is garbled; [25:18] the name of the evaluative-feedback variant of APPL is dropped; "adaptive plan and parameter learning" [31:33] = adaptive planner parameter learning.
- [28:38–30:24] Screen-sharing trouble: the paper title and motivation come in fragments.
- [35:53] "maximizing or minimizing the mutual information" is imprecise; [36:56] colour legend is wrong/garbled (red is said twice); the disentanglement part [36:24–40:11] leans on slides ("the causal matrix from the previous slide").
- [43:58] A sentence on foundation models appears without its lead-in (speech lost before it).
- [48:18–48:59] Gap: the size of the PlayStation bank is not in the transcript; [49:32] starts mid-sentence.
- [50:06] Lap times 114 s versus 115 s are given as an illustration of "about one second"; exact values are on the slide.
- [57:11–57:47] Slip: "in July, we the humans outraced the people" (means the humans beat the agents); [57:47–58:19] the points scheme is first misstated (7, 6, 5, 4, 3) and then corrected (10, 8, 6, 5, 4, 3, 2, 1); "SART" = Sarthe.
- Recognition errors: "GT Sofi / GT Sophie / GTSophie" = GT Sophy, "Slack" = SLAC, "Doosty" = DUSDi, "Diane" = DIAYN, "coral" = CoRL, "Tamawaki Yamanaka" = Tomoaki Yamanaka, "Ubo Visser" = Ubbo Visser, "Kirk Dressner" = Kurt Dresner, "Adam Clivens" = Adam Klivans.
- No Q&A is in the transcript.

---

# Notes on cross-lecture and absent questions

Files: `cross.jsonl` (x01–x22), `absent.jsonl` (a01–a07). Lecture 8 was not opened; it appears only in `grep -c` counts for the absent questions.

## Participation

| Lecture | Questions | Ids |
|---|---|---|
| 1 | 6 | x01 x02 x03 x04 x05 x06 |
| 2 | 4 | x01 x02 x07 x08 |
| 3 | 5 | x10 x11 x12 x13 x22 |
| 4 | 4 | x04 x14 x15 x22 |
| 5 | 6 | x03 x04 x10 x16 x20 x22 |
| 6 | 4 | x12 x16 x17 x21 |
| 7 | 4 | x06 x11 x15 x19 |
| 9 | 5 | x07 x08 x13 x14 x21 |
| 10 | 5 | x05 x09 x17 x18 x19 |
| 11 | 4 | x02 x09 x18 x20 |

Three-lecture questions: x02 (1, 2, 11), x04 (1, 4, 5), x22 (3, 4, 5). No pair of lectures shares more than two questions.

Gold ranges: the transcript lines that carry the claim, plus one mark before the first line and one mark after the end of the last line. Longest ranges: 4 min 13 s (x04, lecture 4) and 3 min 58 s (x13, lecture 9).

## Unsure, by question

- x02: the lecture 2 point rests on adjacency: the speaker says AI infra became "the third pillar", then "what is the need", then cites Sutton and concludes "so we need a large amount of computation power". Lecture 10 also names the bitter lesson (20:17–20:47), but the sentence is garbled and is not in gold.
- x03: the lecture 1 safeguard is only half said ("this is really just a hack ... reinforcement learning can get you in places that I usually don't. For example, the hacks that we talked about").
- x04: lecture 4 range is the widest (4 min 13 s): the two approaches are announced at 14:35 and the LLM judge is described only from 17:11. Lecture 3 (02:09–03:43, reward model from human ratings for chatbots) and lecture 10 (29:49–30:51, a poem cannot be rewarded) touch the same topic and are not in gold; an answer that cites them as well is not wrong.
- x06: lecture 4 (18:15) also says LLM output is random under sampling; not in gold.
- x08: the lecture 9 range (20:33–24:03) holds hedged requests and failover with an unrelated stretch on interruption detection in between.
- x09: the lecture 10 sentence on the random policy is garbled ("pretty sharply in the next video so problem is random policy will not do much"). Lecture 11 20:46 repeats that the racing agent learned "purely from random exploration"; not in gold.
- x10: lecture 3 needs two ranges (requirement at 23:20, harness example at 28:08).
- x11: lecture 4's "hard to saturate" principle (42:11–43:15) is close to lecture 3's hardness criterion; not in gold.
- x12: lecture 1 (65:00) also says SFT is done first to prepare and accelerate RL, and lecture 10 says the same for AlphaStar; the question pins post-training of LLM agents and Diplomacy, but a system may add those.
- x13: lecture 9 range is wide (3 min 58 s) because messy input (32:02) and personas (33:42) are apart.
- x14: both transcripts garble the metric name ("positive one / positive K" in lecture 4, "pass at K / Pass It K" in lecture 9), and lecture 9 never states the exponent cleanly ("95, 0.95 to asymptotically approaching zero"). The lecture 4 line at 51:02 is one long line of about a minute.
- x15: the lecture 4 sentence on measuring contamination has lost its beginning ("performance on the overall data set, we can also measure how severe data contamination happens"), so the point says only that contamination can be gauged. Lecture 4's dynamic benchmarks (21:25–22:27) are another design-time safeguard; the question is pinned to the cybersecurity benchmark to keep one answer.
- x16: in lecture 5 "after you change the model" is a recognition error for "after you train the model". Lecture 3 (72:37–73:45) calls GenSelect "parallel reasoning"; related, not in gold.
- x17: lecture 10 points at slides ("here are the three figures", "the right most"); the point keeps only what is said in words (play against the strongest opponent in the population).
- x18: lecture 11's doubt about chatbots is put as a question ("is a chatbot an agent?") followed by the continuous-loop definition; the doubt is implied, not asserted.
- x19: "Fields medal" is transcribed "fields metal".
- x20: lecture 1 (30:27–33:05, reweighting domains in pre-training data) and lecture 3 (67:54–70:32, difficulty mix of RL prompts) also talk about data mixes; the question pins industrial agentic training and the racing agent.
- x21: lecture 6 gold ends at 54:31, the last mark of the transcript; the closing sentence ("on the cusp") starts at that mark and is outside the range, so "expects this to change soon" rests on 51:55 ("the models are nearing the point where they can be effective").
- x22: lecture 4 needs two ranges (CRMArena case study and project checklist). Overlaps partly with single question s05-4 on the lecture 5 side (length penalty), but the comparability point is not asked there.
- Overlap with single-lecture questions in one half of a cross question: x09 (s10-2), x10 (s03-2), x11 (s07-2), x12 (s03-3), x14 (s04-5), x15 (s04-4, s07-5), x16 (s06-6), x20 (s11-5). None repeats a single question as a whole.
- Absent questions, lecture 8 counted but not read: a01 "merge" 1 hit, a02 "agreement" 1 hit, a03 "alpha" 13 hits, a06 "simulation" 1 hit. The defining terms of each question (BPE, kappa, LoRA/rank, domain randomization) have 0 hits there.
- Dropped absent candidate: anything on MCP details ("MCP" has 12 hits in lecture 8, which could not be read).
