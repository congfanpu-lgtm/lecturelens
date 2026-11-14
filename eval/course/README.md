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
| `question` | 复习时真会问的问法。不要照着转写的原句写,否则关键词检索白占便宜 |
| `points` | 要点式的标准答案,每条一句自己的话。打分时逐条判"答案里有没有说到" |
| `gold` | 答案所在的位置:讲次序号 + 时间段(`分:秒`,分钟可以超过 59),可以多个 |
| `author` | `user`(自己写的)或 `model-drafted`(模型起草、自己改写核对过的) |

写法上的几条:
- 跨讲次题至少占三分之一,自己写,不让模型起草(能起草跨讲次题的只有长上下文模型,而它正是被比较的对象)。
- 课里没讲的题放 6 道左右:看起来和课程相关、但课上确实没讲。
- 时间段写宽一点没关系(前后各留十几秒),写窄了会把说对了位置的答案判成没命中。

    python3 eval/course/questions.py check --course 1     # 格式、讲次序号、时间有没有超出这一讲
    python3 eval/course/questions.py freeze --course 1    # 写 questions.lock;之后再改题,之前的运行结果就不能用了

**题集在看任何系统的结果之前冻结。**
