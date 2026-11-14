# 课程问答评测

设计见 `docs/superpowers/specs/2026-11-13-course-video-qa-design.md` 第 11 节,实施计划见
`docs/superpowers/plans/2026-11-14-course-video-4-eval.md`。这里的脚本都是标准库 Python,在仓库根目录运行。

## 题集

`questions.jsonl`,一行一题(还没有:正式题集由用户写,写完冻结)。格式看 `questions.example.jsonl`,那三道是示例,
只用来跑通链路,不算进任何结果。

| 字段 | 说明 |
|---|---|
| `id` | 自己起,不重复。建议单讲题 `s01`、跨讲次题 `x01`、课里没讲的题 `a01` |
| `kind` | `single` 单讲 / `cross` 跨讲次(答案至少落在两讲里)/ `absent` 课里没讲 |
| `question` | 复习时真会问的问法。不要照着转写的原句写,否则关键词检索白占便宜。**用课程的语言写**(评测课是英文课,题目用英文):向量模型是英文的,用中文问会让"只检索一次"的那条基线吃亏 |
| `points` | 要点式的标准答案,每条一句自己的话。打分时逐条判"答案里有没有说到" |
| `gold` | 答案所在的位置:讲次序号 + 时间段(`分:秒`,分钟可以超过 59),可以多个;一段最长 6 分钟 |
| `author` | `user`(自己写的)或 `model-drafted`(模型起草、自己改写核对过的) |

写法上的几条:
- 跨讲次题至少占三分之一,自己写,不让模型起草(能起草跨讲次题的只有长上下文模型,而它正是被比较的对象)。
- 课里没讲的题放 6 道左右:看起来和课程相关、但课上确实没讲。
- 时间段写宽一点没关系(前后各留十几秒),写窄了会把说对了位置的答案判成没命中。

    python3 eval/course/questions.py check --course 1     # 格式、讲次序号、时间有没有超出这一讲
    python3 eval/course/questions.py freeze --course 1    # 写 questions.lock;之后再改题,之前的运行结果就不能用了

单讲题可以让模型先起草(要真实模型),再逐题改写核对:

    python3 eval/course/draft.py --course 1 --per-lecture 4      # 写 questions.draft.jsonl

**题集在看任何系统的结果之前冻结。**

## 运行、打分

被比较的系统都是同一个 `COURSE_QA` 任务,只是 payload 里的 `eval` 选项不同:

| 系统 | 是什么 | `eval` |
|---|---|---|
| S1 | 完整系统:agent + 三层核验 | (不带) |
| S2 | 固定检索流水线:检索一次 → 一次模型调用 → 核验 | `{"mode":"rag"}` |
| S3 | agent,核验不调判官 | `{"judge":false}` |
| B1 | 长上下文基线:整门课的逐字稿放进一次调用,核验只做前两层 | `{"mode":"long","judge":false}` |

    python3 eval/course/run.py --name pilot --course 1 --systems S1,S2,S3 --rounds 3 \
        --real --price-in 0.10 --price-out 0.50                                           # 中断后再跑同一条命令会接着跑
    python3 eval/course/calibrate.py audit eval/course/runs/pilot --course 1              # 没有判官的系统:事后让判官看它的答案
    python3 eval/course/grade.py eval/course/runs/pilot --model <打分模型>                # 答对率
    python3 eval/course/score.py eval/course/runs/pilot                                   # 出报告:summary.md

- 题集必须先冻结;`run.py` 开始时把题集抄进运行目录,打分、校准只认那一份。示例题或试链路用 `--unfrozen`。
- 不带 `--real` 的运行在报告里标成"模拟模型";单价(美元 / 百万 token)不给的话报告里没有成本一栏。
- 有的型号有两张价目表,按一次调用的输入长度选(例如超过 10 万 token 整次调用按高价)。给
  `--tier-tokens 100000 --price-in-tier 0.50 --price-out-tier 2.50`,只有一次调用的运行(B1)超过这个长度时按高价算。
- 没跑成的运行在"命中"类指标上按 0 算。报告开头会提醒还有几次没跑成、几次没打分;先 `--retry-failed` 再看数字。

判官(核验第 3 层)自己的准确率,要有人的标注来量:

    python3 eval/course/calibrate.py export eval/course/runs/pilot --n 100    # 写 pairs.csv:在 label 一列填 y 或 n
    python3 eval/course/calibrate.py score eval/course/runs/pilot             # 标完之后:准确率、漏拦率、误拦率
    python3 eval/course/calibrate.py swap eval/course/runs/pilot --course 1   # 不费人工:换成随机证据,判官应该全部拒绝

抽样时判官否掉的结论占一半,而实际运行里它们只是少数。所以 `score` 给两组数:`sample` 是这份样本上的一致率,
`weighted` 才是按实际比例加权之后判官的准确率、漏拦率和误拦率,写报告用后一组。

- `run.py` 和课程环境在同一台机器上跑(默认连 `127.0.0.1:18090`);`grade.py` 只需要运行目录和 `.env` 里的模型地址、key,
  可以在别的机器上跑。
- `runs/` 不进仓库(`.gitignore`)。真实运行的汇总(`summary.md`)到时候抄进 `eval/results/` 和 `docs/benchmarks.md`。
- 每个脚本都有 `--self-check`。
- 课程环境默认是模拟模型:跑出来的数字只说明链路通了(`docs/benchmarks.md` 第 11 节)。

## 转写质量抽查

    python3 eval/course/transcript_check.py 3 5 9     # 讲次 id;每讲取 3 段各 10 分钟,和 YouTube 上的字幕逐词对齐

结果在 `docs/benchmarks.md` 第 11 节。

