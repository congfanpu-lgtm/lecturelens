#!/usr/bin/env python3
"""两次运行之间的逐题配对比较(同一份题集、同一套系统,中间改了一样东西)。

  python3 eval/course/exploratory/compare_runs.py eval/course/runs/<之前> eval/course/runs/<之后> [--systems S1,S3]

每个系统、每个指标:两次运行各自按题取各轮平均,再在两边都有的题上逐题求差(之后 − 之前),bootstrap 95% 区间。
两次运行要用同一版 grade.py 打分,否则答对率的差里混着打分提示的变化。
"""
import os
import statistics
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import score  # noqa: E402

METRICS = ("correct", "located", "located_all", "quote_ok", "weak", "judged_out", "steps", "tokens_in", "tokens_out", "seconds")


def per_question(run_dir):
    meta, questions, rows = score.load_run(run_dir)
    path = os.path.join(run_dir, "grades.jsonl")
    grades = {(g["qid"], g["system"], g["round"]): (g.get("taskUuid"), g["score"]) for g in score.read_jsonl(path)} if os.path.exists(path) else {}
    out = {}
    for r in rows:
        judged = (meta["systems"].get(r["system"]) or {}).get("judge", True)
        sc = score.score_row(questions[r["qid"]], r, judged)
        g = grades.get((r["qid"], r["system"], r["round"]))
        sc["correct"] = g[1] if g and g[0] == r.get("taskUuid") else None
        for m in METRICS:
            if sc.get(m) is not None:
                out.setdefault((r["system"], m), {}).setdefault(r["qid"], []).append(sc[m])
    return questions, {k: {q: statistics.mean(v) for q, v in d.items()} for k, d in out.items()}


def main():
    before, after = sys.argv[1], sys.argv[2]
    systems = sys.argv[sys.argv.index("--systems") + 1].split(",") if "--systems" in sys.argv else None
    questions, a = per_question(before)
    _, b = per_question(after)
    kinds = {"all": lambda q: True, "single": lambda q: questions[q]["kind"] == "single",
             "cross": lambda q: questions[q]["kind"] == "cross"}
    for s in systems or sorted({k[0] for k in a} & {k[0] for k in b}):
        print(f"== {s}: {os.path.basename(after.rstrip('/'))} − {os.path.basename(before.rstrip('/'))}")
        for m in METRICS:
            if (s, m) not in a or (s, m) not in b:
                continue
            for scope in (("all", "single", "cross") if m in ("correct", "located") else ("cross",) if m == "located_all" else ("all",)):
                keep = kinds[scope]
                x = {q: v for q, v in a[(s, m)].items() if keep(q)}
                y = {q: v for q, v in b[(s, m)].items() if keep(q)}
                d = score.bootstrap_diff(y, x)
                if not d:
                    continue
                pct = m not in ("steps", "tokens_in", "tokens_out", "seconds")
                k = 100 if pct else 1
                mean_a, mean_b = statistics.mean(x[q] for q in x if q in y), statistics.mean(y[q] for q in y if q in x)
                apart = d[1] > 0 or d[2] < 0
                print(f"   {m:12s} {scope:6s} {k * mean_a:8.1f} → {k * mean_b:8.1f}   diff {k * d[0]:+7.1f} [{k * d[1]:+.1f}, {k * d[2]:+.1f}] n={d[3]}"
                      f"  {'分得开' if apart else '分不开'}")


if __name__ == "__main__":
    main()
