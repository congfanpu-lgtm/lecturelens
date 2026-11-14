#!/usr/bin/env python3
"""课程问答评测的打分和统计(零依赖,标准库)。只读运行结果,不调模型、不连服务。

  python3 eval/course/score.py eval/course/runs/<名字>          # 写 summary.json 和 summary.md
  python3 eval/course/score.py --self-check

输入:<运行目录>/results.jsonl(run.py 写的,一行是一题一系统一轮)、meta.json(里面有题集文件的路径),
可选 grades.jsonl(grade.py 写的答对率打分)。题集的 SHA-256 和结果里记的对不上时拒绝打分。

每题每系统每轮算这些,再按题取各轮的平均,按系统汇总:
  located      通过核验的证据里,至少有一条和某个标准区间在同一讲、时间重叠够多(见 overlap_hit)。课里没讲的题不算
  quote_ok     给了证据的结论里,没有在第 1、2 层(时间不存在、原话找不到)被拿掉的占比
  unsupported  模型给出的结论里,被核验拿掉的占比(第 0 到 3 层)
  refused      课里没讲的题:说了找不到,或者没有一条通过核验的结论 = 正确拒答。其余的题:说了找不到 = 错误拒答
  steps / tokens / cost / seconds   工具调用次数、输入加输出 token、花费(百万分之一美元)、从提交到落定的秒数
  correct      答对率,有 grades.jsonl 才有:这一题命中的要点占比(课里没讲的题按是否正确拒答算 1 或 0)
两个系统之间:同一套题上逐题求差,bootstrap 10000 次给 95% 置信区间。区间跨过 0 就是"没有显著差别"。
"""
import argparse
import hashlib
import json
import os
import random
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from questions import clock_ms  # noqa: E402

MIN_OVERLAP_MS = 5000
METRICS = ("located", "quote_ok", "unsupported", "steps", "tokens", "cost", "seconds")


def overlap_hit(evidence, gold):
    """evidence: {"seq","startMs","endMs"};gold: {"lecture","from","to"}。同一讲,而且重叠不少于 5 秒
    或者不少于两段里较短那一段的一半(证据只是一个时间点时:落在标准区间里就算)。只沾边 1 秒不算。"""
    if evidence.get("seq") != gold["lecture"]:
        return False
    g0, g1 = clock_ms(gold["from"]), clock_ms(gold["to"])
    e0, e1 = evidence["startMs"], evidence["endMs"]
    overlap = min(e1, g1) - max(e0, g0)
    if overlap < 0:
        return False
    return overlap >= MIN_OVERLAP_MS or overlap >= min(e1 - e0, g1 - g0) / 2


def score_row(question, row):
    """一题一系统一轮的指标。没跑成的(任务失败、超时)各项为 None,只记 failed。"""
    answer = row.get("answer") or {}
    if row.get("status") != "COMPLETED" or answer.get("status") != "COMPLETED":
        return {"failed": 1}
    kept, dropped = answer.get("claims") or [], answer.get("dropped") or []
    proposed = len(kept) + len(dropped)
    by_layer = [sum(1 for d in dropped if d.get("layer") == n) for n in range(4)]
    cited = proposed - by_layer[0]
    usage = answer.get("usage") or {}
    out = {
        "failed": 0,
        "quote_ok": (cited - by_layer[1] - by_layer[2]) / cited if cited else None,
        "unsupported": len(dropped) / proposed if proposed else None,
        "steps": len(answer.get("toolCalls") or []),
        "tokens": usage.get("promptTokens", 0) + usage.get("completionTokens", 0),
        "cost": usage.get("costMicro", 0),
        "seconds": row.get("seconds"),
        "claims": len(kept),
    }
    if question["kind"] == "absent":
        out["refused_right"] = 1 if answer.get("notFound") or not kept else 0
    else:
        out["refused_wrong"] = 1 if answer.get("notFound") else 0
        out["located"] = 1 if any(overlap_hit(e, g) for c in kept for e in c.get("evidence") or []
                                  for g in question["gold"]) else 0
    return out


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def per_question(rows, questions, system, metric):
    """{题目 id: 这一题在各轮上的平均};这一题没有可用数值的不在里面。"""
    out = {}
    for qid, q in questions.items():
        v = mean(score_row(q, r).get(metric) for r in rows if r["qid"] == qid and r["system"] == system)
        if v is not None:
            out[qid] = v
    return out


def bootstrap_diff(a, b, n=10000, seed=7):
    """a、b:{题目 id: 数值}。在两边都有的题上逐题求差(a − b),返回 (平均差, 区间下限, 区间上限, 题数)。"""
    diffs = [a[q] - b[q] for q in sorted(a.keys() & b.keys())]
    if not diffs:
        return None
    rng = random.Random(seed)
    means = sorted(sum(rng.choices(diffs, k=len(diffs))) / len(diffs) for _ in range(n))
    return sum(diffs) / len(diffs), means[int(0.025 * n)], means[int(0.975 * n) - 1], len(diffs)


def summarise(rows, questions, grades=None):
    systems = sorted({r["system"] for r in rows})
    extra = ("refused_right", "refused_wrong") + (("correct",) if grades else ())
    if grades:
        for r in rows:
            r["_correct"] = grades.get((r["qid"], r["system"], r["round"]))
    summary = {"systems": {}, "pairs": [], "questions": len(questions)}
    table = {}
    for s in systems:
        mine = [r for r in rows if r["system"] == s]
        table[s] = {m: per_question(mine, questions, s, m) for m in METRICS + extra[:2]}
        if grades:
            table[s]["correct"] = {q: v for q in questions
                                   if (v := mean(r["_correct"] for r in mine if r["qid"] == q)) is not None}
        cross = {q for q, d in questions.items() if d["kind"] == "cross"}
        rounds = sorted({r["round"] for r in mine})
        info = {"rows": len(mine), "failed": sum(score_row(questions[r["qid"]], r)["failed"] for r in mine),
                "rounds": len(rounds)}
        for m in METRICS + extra:
            info[m] = mean(table[s][m].values())
        info["located_cross"] = mean(v for q, v in table[s]["located"].items() if q in cross)
        if grades:
            info["correct_cross"] = mean(v for q, v in table[s]["correct"].items() if q in cross)
            # 次与次之间的波动:每一轮单独的平均
            info["correct_by_round"] = [mean(r["_correct"] for r in mine if r["round"] == n) for n in rounds]
        info["located_by_round"] = [mean(score_row(questions[r["qid"]], r).get("located") for r in mine if r["round"] == n)
                                    for n in rounds]
        steps = [score_row(questions[r["qid"]], r).get("steps") for r in mine]
        info["steps_histogram"] = {str(k): steps.count(k) for k in sorted({x for x in steps if x is not None})}
        for m in ("seconds", "tokens"):
            vals = sorted(table[s][m].values())
            info[m + "_median"] = statistics.median(vals) if vals else None
            info[m + "_p90"] = vals[min(len(vals) - 1, int(0.9 * len(vals)))] if vals else None
        summary["systems"][s] = info
    for i, a in enumerate(systems):
        for b in systems[i + 1:]:
            for m in ("located", "unsupported", "quote_ok") + (("correct",) if grades else ()):
                d = bootstrap_diff(table[a][m], table[b][m])
                if d:
                    summary["pairs"].append({"a": a, "b": b, "metric": m, "diff": d[0], "low": d[1], "high": d[2],
                                             "n": d[3], "significant": d[1] > 0 or d[2] < 0})
    return summary


def pct(v):
    return "—" if v is None else f"{100 * v:.0f}%"


def render(summary, meta):
    lines = [f"# 课程问答评测:{meta.get('name', '')}", "",
             f"模型 `{meta.get('model') or '默认'}`,课程 {meta.get('course')},{summary['questions']} 道题,"
             f"题集 SHA-256 `{meta.get('questionsSha', '')[:12]}…`。", ""]
    if "mock" in str(meta.get("model") or "mock"):
        lines += ["**这是模拟模型跑的:只说明评测链路通了,下面的数字没有意义。**", ""]
    head = ["系统", "证据定位命中", "其中跨讲次题", "引文忠实", "被核验拿掉", "正确拒答", "错误拒答", "步数", "token(中位)", "秒(中位)", "失败"]
    has_correct = any("correct" in s for s in summary["systems"].values())
    if has_correct:
        head[1:1] = ["答对率", "其中跨讲次题"]
    lines += ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for name, s in summary["systems"].items():
        row = [name, pct(s["located"]), pct(s["located_cross"]), pct(s["quote_ok"]), pct(s["unsupported"]),
               pct(s["refused_right"]), pct(s["refused_wrong"]), "—" if s["steps"] is None else f"{s['steps']:.1f}",
               "—" if s["tokens_median"] is None else f"{s['tokens_median']:.0f}",
               "—" if s["seconds_median"] is None else f"{s['seconds_median']:.0f}", f"{s['failed']}/{s['rows']}"]
        if has_correct:
            row[1:1] = [pct(s.get("correct")), pct(s.get("correct_cross"))]
        lines.append("| " + " | ".join(row) + " |")
    lines += ["", "## 系统两两比较(同一套题,逐题配对,bootstrap 95% 区间)", "",
              "| 比较 | 指标 | 平均差 | 区间 | 题数 | 结论 |", "|---|---|---|---|---|---|"]
    names = {"located": "证据定位命中", "unsupported": "被核验拿掉", "quote_ok": "引文忠实", "correct": "答对率"}
    for p in summary["pairs"]:
        lines.append(f"| {p['a']} − {p['b']} | {names[p['metric']]} | {100 * p['diff']:+.1f} 点 | "
                     f"[{100 * p['low']:+.1f}, {100 * p['high']:+.1f}] | {p['n']} | "
                     f"{'分得开' if p['significant'] else '没有显著差别'} |")
    lines += ["", "## 各轮之间的波动", ""]
    for name, s in summary["systems"].items():
        parts = [f"证据定位 {' / '.join(pct(v) for v in s['located_by_round'])}"]
        if has_correct:
            parts.append(f"答对率 {' / '.join(pct(v) for v in s['correct_by_round'])}")
        lines.append(f"- {name}({s['rounds']} 轮):" + ";".join(parts) + f";步数分布 {s['steps_histogram']}")
    return "\n".join(lines) + "\n"


def read_jsonl(path):
    return [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]


def score_dir(run_dir):
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    raw = open(meta["questions"], "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    # 同一题同一系统同一轮有几行(--retry-failed 重跑过):取最后一行
    rows = list({(r["qid"], r["system"], r["round"]): r for r in read_jsonl(os.path.join(run_dir, "results.jsonl"))}.values())
    wrong = {r["questionsSha"] for r in rows} - {sha}
    if wrong:
        raise SystemExit(f"这次运行用的不是现在这版题集(结果里是 {sorted(wrong)[0][:12]}…,现在是 {sha[:12]}…)。"
                         "两版题集的结果不能混在一起算。")
    questions = {q["id"]: q for q in (json.loads(l) for l in raw.decode("utf-8").splitlines() if l.strip())}
    grades = None
    gpath = os.path.join(run_dir, "grades.jsonl")
    if os.path.exists(gpath):
        grades = {(g["qid"], g["system"], g["round"]): g["score"] for g in read_jsonl(gpath)}
    summary = summarise(rows, questions, grades)
    json.dump(summary, open(os.path.join(run_dir, "summary.json"), "w"), ensure_ascii=False, indent=2)
    open(os.path.join(run_dir, "summary.md"), "w", encoding="utf-8").write(render(summary, meta))
    return summary


def self_check():
    g = {"lecture": 3, "from": "12:30", "to": "14:10"}                      # 750–850 秒

    def ev(seq, a, b):
        return {"seq": seq, "startMs": a * 1000, "endMs": b * 1000}

    assert overlap_hit(ev(3, 740, 760), g)                                   # 重叠 10 秒
    assert overlap_hit(ev(3, 700, 755), g)                                   # 重叠正好 5 秒
    assert not overlap_hit(ev(3, 700, 751), g)                               # 只沾边 1 秒
    assert overlap_hit(ev(3, 847, 851), g)                                   # 证据只有 4 秒,重叠 3 秒,过了它的一半
    assert overlap_hit(ev(3, 800, 800), g) and not overlap_hit(ev(3, 900, 900), g)   # 一个时间点:在区间里才算
    assert not overlap_hit(ev(5, 740, 760), g)                               # 别的讲次
    assert not overlap_hit(ev(3, 860, 900), g)                               # 不重叠

    q = {"id": "s1", "kind": "single", "points": ["p"], "gold": [g, {"lecture": 5, "from": "1:00", "to": "2:00"}]}
    absent = {"id": "a1", "kind": "absent", "points": [], "gold": []}

    def row(claims, dropped, system="S1", rnd=1, qid="s1", not_found=False, status="COMPLETED"):
        return {"qid": qid, "system": system, "round": rnd, "questionsSha": "x", "status": status, "seconds": 40.0,
                "answer": {"status": status, "notFound": not_found, "claims": claims, "dropped": dropped,
                           "toolCalls": [{}, {}], "usage": {"promptTokens": 900, "completionTokens": 100, "costMicro": 50}}}

    hit = {"text": "c", "evidence": [ev(5, 70, 90)]}                         # 命中第二个标准区间
    miss = {"text": "c", "evidence": [ev(3, 0, 10)]}
    s = score_row(q, row([hit, miss], [{"layer": 2}, {"layer": 3}, {"layer": 0}]))
    assert s["located"] == 1 and s["claims"] == 2 and s["steps"] == 2 and s["tokens"] == 1000 and s["cost"] == 50
    assert abs(s["quote_ok"] - 3 / 4) < 1e-9                                 # 给了证据的 4 条里,1 条的原话找不到
    assert abs(s["unsupported"] - 3 / 5) < 1e-9                              # 5 条里被拿掉 3 条
    assert score_row(q, row([miss], []))["located"] == 0
    assert score_row(q, row([], [], not_found=True))["refused_wrong"] == 1
    assert score_row(q, row([], []))["quote_ok"] is None                     # 没有结论:没有分母
    assert score_row(absent, row([], [], not_found=True))["refused_right"] == 1
    assert score_row(absent, row([], [{"layer": 3}]))["refused_right"] == 1  # 没说找不到,但也没有通过核验的结论
    assert score_row(absent, row([hit], []))["refused_right"] == 0
    assert "located" not in score_row(absent, row([hit], []))
    assert score_row(q, row([hit], [], status="FAILED")) == {"failed": 1}

    same = {f"q{i}": i % 2 for i in range(40)}
    d = bootstrap_diff(same, dict(same))
    assert d[0] == 0 and d[1] <= 0 <= d[2] and d[3] == 40                    # 两组一样:区间包含 0
    better = {k: 1.0 for k in same}
    worse = {k: (0.0 if i % 4 else 1.0) for i, k in enumerate(same)}
    d = bootstrap_diff(better, worse)
    assert d[1] > 0 and abs(d[0] - 0.75) < 1e-9                              # 差别明显:区间不包含 0
    assert bootstrap_diff({"a": 1}, {"b": 1}) is None                        # 没有共同的题

    questions = {"s1": q, "a1": absent}
    rows = [row([hit], [], "S1", 1), row([miss], [], "S1", 2), row([miss], [], "S2", 1), row([miss], [], "S2", 2),
            row([], [], "S1", 1, "a1", True), row([hit], [], "S2", 1, "a1")]
    grades = {("s1", "S1", 1): 1.0, ("s1", "S1", 2): 0.5, ("s1", "S2", 1): 0.0, ("s1", "S2", 2): 0.0,
              ("a1", "S1", 1): 1.0, ("a1", "S2", 1): 0.0}
    out = summarise(rows, questions, grades)
    s1, s2 = out["systems"]["S1"], out["systems"]["S2"]
    assert s1["located"] == 0.5 and s2["located"] == 0 and s1["refused_right"] == 1 and s2["refused_right"] == 0
    assert s1["located_by_round"] == [1, 0] and s1["rounds"] == 2 and s1["steps_histogram"] == {"2": 3}
    assert abs(s1["correct"] - (0.75 + 1.0) / 2) < 1e-9 and s2["correct"] == 0 and s1["correct_by_round"] == [1.0, 0.5]
    assert {p["metric"] for p in out["pairs"]} >= {"located", "correct"}
    text = render(out, {"name": "t", "model": "mock-small", "course": 1, "questionsSha": "abc"})
    assert "模拟模型" in text and "| S1 |" in text and "没有显著差别" in text

    import tempfile
    with tempfile.TemporaryDirectory() as d:                                 # 题集改过:拒绝打分
        qpath = os.path.join(d, "q.jsonl")
        open(qpath, "w", encoding="utf-8").write(json.dumps(q) + "\n" + json.dumps(absent) + "\n")
        sha = hashlib.sha256(open(qpath, "rb").read()).hexdigest()
        json.dump({"name": "t", "questions": qpath, "questionsSha": sha, "model": "mock", "course": 1},
                  open(os.path.join(d, "meta.json"), "w"))
        with open(os.path.join(d, "results.jsonl"), "w") as f:
            # 第一行是同一题同一轮先失败的那次(后来用 --retry-failed 重跑成功了):只算最后一行
            for r in [row([], [], "S1", 1, status="FAILED")] + rows:
                f.write(json.dumps({**{k: v for k, v in r.items() if k != "_correct"}, "questionsSha": sha}) + "\n")
        scored = score_dir(d)["systems"]["S1"]
        assert scored["located"] == 0.5 and scored["failed"] == 0 and scored["rows"] == 3
        assert os.path.exists(os.path.join(d, "summary.md"))
        open(qpath, "a").write("\n")
        try:
            score_dir(d)
            raise AssertionError("题集改过之后应该拒绝打分")
        except SystemExit as e:
            assert "不是现在这版题集" in str(e)
    print("self-check ok")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="课程问答评测的打分和统计")
    p.add_argument("run_dir", nargs="?")
    p.add_argument("--self-check", action="store_true")
    args = p.parse_args()
    if args.self_check:
        self_check()
    elif args.run_dir:
        s = score_dir(args.run_dir)
        print(open(os.path.join(args.run_dir, "summary.md"), encoding="utf-8").read())
    else:
        p.error("要给运行目录")
