#!/usr/bin/env python3
"""课程问答评测的打分和统计(零依赖,标准库)。只读运行结果,不调模型、不连服务。

  python3 eval/course/score.py eval/course/runs/<名字>          # 写 summary.json 和 summary.md
  python3 eval/course/score.py --self-check

输入:<运行目录>/results.jsonl(run.py 写的,一行是一题一系统一轮)、meta.json、questions.jsonl(run.py 抄进来的那一版题集),
可选 grades.jsonl(grade.py 写的答对率)、audit.jsonl(calibrate.py audit 写的:没有判官的系统,事后让判官看它答案里的结论)。
题集的 SHA-256 和结果里记的对不上时拒绝打分。

每题每系统每轮算这些,再按题取各轮的平均,按系统汇总。没跑成的运行(任务失败、超时)在"命中"类指标上一律算 0,
不是丢掉:丢掉的话,一个系统在它答不出的题上失败反而显得更好。

  located      通过核验的证据里,至少有一条和某个标准区间在同一讲、时间重叠够多(见 overlap_hit)。课里没讲的题不算
  located_all  只对跨讲次题:标准答案涉及的每一讲都有证据命中(located 只要命中其中一讲就算)
  quote_ok     引文忠实:模型给出的每一条证据里,时间真实存在、原话真的在那里的占比(按证据条数算,各轮加起来)
  weak         模型给出的结论里,因为没有证据、时间不存在或原话找不到(核验第 0 到 2 层)被拿掉的占比。各系统可比
  judged_out   模型给出的结论里,被判官(第 3 层)拿掉的占比。只有带判官的系统有这一项
  refused      课里没讲的题:说了找不到,或者没有一条通过核验的结论 = 正确拒答。其余的题:说了找不到 = 错误拒答
  steps / 输入输出 token / 成本 / 秒   工具调用次数、token(输入和输出分开)、按 meta 里的单价算的美元、从提交到落定的秒数
  correct      答对率,有 grades.jsonl 才有:这一题说到的要点占比(课里没讲的题按是否正确拒答算 1 或 0)
  final_bad    只对没有判官的系统,有 audit.jsonl 才有:它答案里的结论,事后被判官否掉的占比

weak 和 judged_out 都把补一轮之前被拿掉的结论算在内(视图里的 verifications 是每一轮核验的明细)。
两个系统之间:同一套题上逐题求差,bootstrap 10000 次给 95% 置信区间。区间跨过 0 就是"没有显著差别";
两边都有的题不到 MIN_N 道时不下结论。事先写下的预期对应的那几项比较标"预期",其余是探索性的:
一次看几十个区间,总会有几个碰巧分得开。
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
MIN_N = 10          # 配对比较至少要这么多道题才下结论
# 设计文档第 11 节事先写下的预期:(系统甲, 系统乙, 指标, 题目范围)
EXPECTED = {("S1", "S2", "correct", "cross"): "预期 1", ("S1", "S3", "correct", "all"): "预期 2",
            ("B1", "S1", "correct", "all"): "预期 3", ("B1", "S1", "located", "all"): "预期 3"}
NAMES = {"located": "证据定位命中", "located_all": "每一讲都命中", "weak": "证据站不住", "quote_ok": "引文忠实", "correct": "答对率"}


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


def verification_rounds(answer):
    """每一轮核验的明细:[{"kept": n, "dropped": [...], "evidence": {"proposed","passed"} 或 None}]。
    旧格式的结果没有 verifications,退回到视图里的 claims / dropped(只有最后一轮,补过一轮的会少算)。"""
    rounds = answer.get("verifications")
    if rounds:
        return rounds
    kept, dropped = answer.get("claims") or [], answer.get("dropped") or []
    return [{"kept": len(kept), "dropped": dropped, "evidence": None}] if kept or dropped else []


def score_row(question, row, judged=True):
    """一题一系统一轮的指标。judged:这个系统的核验有没有判官。"""
    answer = row.get("answer") or {}
    absent, cross = question["kind"] == "absent", question["kind"] == "cross"
    if row.get("status") != "COMPLETED" or answer.get("status") != "COMPLETED":
        out = {"failed": 1}      # 没跑成:命中类指标算 0
        out.update({"refused_right": 0} if absent else {"located": 0, "refused_wrong": 0})
        if cross:
            out["located_all"] = 0
        return out
    kept = answer.get("claims") or []
    rounds = verification_rounds(answer)
    proposed = sum(r["kept"] + len(r["dropped"]) for r in rounds)
    by_layer = [sum(1 for r in rounds for d in r["dropped"] if d.get("layer") == n) for n in range(4)]
    counted = [r["evidence"] for r in rounds if r.get("evidence")]
    if counted and len(counted) == len(rounds):       # 按证据条数
        ev_proposed, ev_passed = sum(e["proposed"] for e in counted), sum(e["passed"] for e in counted)
        quote_ok = ev_passed / ev_proposed if ev_proposed else None
    else:                                              # 旧格式:只能按结论算
        cited = proposed - by_layer[0]
        quote_ok = (cited - by_layer[1] - by_layer[2]) / cited if cited else None
    usage = answer.get("usage") or {}
    out = {
        "failed": 0,
        "quote_ok": quote_ok,
        "weak": sum(by_layer[:3]) / proposed if proposed else None,
        "judged_out": by_layer[3] / proposed if proposed and judged else None,
        "steps": len(answer.get("toolCalls") or []),
        "tokens_in": usage.get("promptTokens", 0),
        "tokens_out": usage.get("completionTokens", 0),
        "calls": usage.get("calls"),
        "seconds": row.get("seconds"),
        "claims": len(kept),
    }
    if absent:
        out["refused_right"] = 1 if answer.get("notFound") or not kept else 0
        return out
    out["refused_wrong"] = 1 if answer.get("notFound") else 0
    evidence = [e for c in kept for e in c.get("evidence") or []]
    out["located"] = 1 if any(overlap_hit(e, g) for e in evidence for g in question["gold"]) else 0
    if cross:
        out["located_all"] = 1 if all(any(overlap_hit(e, g) for e in evidence for g in question["gold"] if g["lecture"] == seq)
                                      for seq in {g["lecture"] for g in question["gold"]}) else 0
    return out


def mean(values):
    values = [v for v in values if v is not None]
    return sum(values) / len(values) if values else None


def bootstrap(values, n=10000, seed=7):
    """一组逐题的数值 → (平均, 区间下限, 区间上限, 题数)。"""
    if not values:
        return None
    rng = random.Random(seed)
    means = sorted(sum(rng.choices(values, k=len(values))) / len(values) for _ in range(n))
    return sum(values) / len(values), means[int(0.025 * n)], means[int(0.975 * n) - 1], len(values)


def bootstrap_diff(a, b, n=10000, seed=7):
    """a、b:{题目 id: 数值}。在两边都有的题上逐题求差(a − b),返回 (平均差, 区间下限, 区间上限, 题数)。"""
    return bootstrap([a[q] - b[q] for q in sorted(a.keys() & b.keys())], n, seed)


def dollars(tokens_in, tokens_out, meta, calls=None):
    """按 meta 里的单价(美元 / 百万 token)算;没给单价返回 None。
    有的型号有两张价目表,按一次调用的输入长度选:超过 tierTokens 时,这次调用的输入和输出整个按另一张算
    (不是只算超出的部分)。只有一次调用的运行(长上下文基线)才知道那一次有多长,所以只对它们换表;
    多次调用的运行每次都远小于这个长度。"""
    if meta.get("priceIn") is None or meta.get("priceOut") is None:
        return None
    tier = meta.get("tierTokens")
    if tier and calls == 1 and tokens_in > tier and meta.get("priceInTier") is not None:
        out_tier = meta["priceOut"] if meta.get("priceOutTier") is None else meta["priceOutTier"]
        return (tokens_in * meta["priceInTier"] + tokens_out * out_tier) / 1e6
    return (tokens_in * meta["priceIn"] + tokens_out * meta["priceOut"]) / 1e6


def summarise(rows, questions, meta, grades=None, audit=None):
    """grades:{(qid, system, round): (taskUuid, 分数)};audit:{(qid, system, round): (taskUuid, 结论数, 被否掉的数)}。"""
    systems = sorted({r["system"] for r in rows})
    options = meta.get("systems") or {}
    cross = {q for q, d in questions.items() if d["kind"] == "cross"}
    summary = {"systems": {}, "pairs": [], "questions": len(questions), "warnings": []}
    table = {}
    for s in systems:
        judged = (options.get(s) or {}).get("judge", True)
        mine = [r for r in rows if r["system"] == s]
        scored = {(r["qid"], r["round"]): score_row(questions[r["qid"]], r, judged) for r in mine}
        for r in mine:
            sc = scored[(r["qid"], r["round"])]
            sc["cost"] = None if sc["failed"] else dollars(sc["tokens_in"], sc["tokens_out"], meta, sc.get("calls"))
            g = (grades or {}).get((r["qid"], s, r["round"]))
            sc["correct"] = g[1] if g and g[0] == r.get("taskUuid") else None   # 重跑过的运行,旧的分数不算
            a = (audit or {}).get((r["qid"], s, r["round"]))
            sc["final_bad"] = a[2] / a[1] if a and a[0] == r.get("taskUuid") and a[1] else None
        metrics = ("located", "located_all", "quote_ok", "weak", "judged_out", "refused_right", "refused_wrong", "steps",
                   "tokens_in", "tokens_out", "cost", "seconds", "correct", "final_bad")
        table[s] = {m: {q: v for q in questions
                        if (v := mean(sc.get(m) for (qid, _), sc in scored.items() if qid == q)) is not None} for m in metrics}
        rounds = sorted({r["round"] for r in mine})
        info = {"rows": len(mine), "failed": sum(sc["failed"] for sc in scored.values()), "rounds": len(rounds),
                "questionsRun": len({r["qid"] for r in mine}), "judged": judged}
        for m in metrics:
            info[m] = mean(table[s][m].values())
        for m in ("located", "correct"):
            info[m + "_cross"] = mean(v for q, v in table[s][m].items() if q in cross)
            info[m + "_by_round"] = [mean(sc.get(m) for (_, n), sc in scored.items() if n == rnd) for rnd in rounds]
        steps = [sc.get("steps") for sc in scored.values()]
        info["steps_histogram"] = {str(k): steps.count(k) for k in sorted({x for x in steps if x is not None})}
        for m in ("seconds", "tokens_in", "tokens_out", "cost"):
            vals = sorted(table[s][m].values())
            info[m + "_median"] = statistics.median(vals) if vals else None
            info[m + "_p90"] = vals[min(len(vals) - 1, int(0.9 * len(vals)))] if vals else None
        info["final_bad_ci"] = bootstrap(list(table[s]["final_bad"].values()))
        ungraded = sum(1 for sc in scored.values() if sc["correct"] is None) if grades is not None else 0
        if info["failed"]:
            summary["warnings"].append(f"{s}:{info['failed']} 次运行没跑成(命中类指标按 0 算)。先用 run.py --retry-failed 重跑。")
        if ungraded:
            summary["warnings"].append(f"{s}:{ungraded} 次运行还没有答对率的分数(或者分数是重跑之前的)。再跑一次 grade.py。")
        if info["questionsRun"] < len(questions):
            summary["warnings"].append(f"{s}:只跑了 {info['questionsRun']} / {len(questions)} 道题。")
        summary["systems"][s] = info
    for i, a in enumerate(systems):
        for b in systems[i + 1:]:
            for m in ("correct", "located", "located_all", "weak", "quote_ok"):
                for scope in ("all", "cross"):
                    keep = (lambda q: q in cross) if scope == "cross" else (lambda q: True)
                    if scope == "cross" and m not in ("correct", "located"):
                        continue
                    for x, y in ((a, b), (b, a)):
                        label = EXPECTED.get((x, y, m, scope))
                        if label or (x, y) == (a, b) and not EXPECTED.get((y, x, m, scope)):
                            d = bootstrap_diff({q: v for q, v in table[x][m].items() if keep(q)},
                                               {q: v for q, v in table[y][m].items() if keep(q)})
                            if d:
                                enough = d[3] >= MIN_N
                                summary["pairs"].append({
                                    "a": x, "b": y, "metric": m, "scope": scope, "expected": label, "diff": d[0], "low": d[1],
                                    "high": d[2], "n": d[3], "verdict": "题数太少,不下结论" if not enough
                                    else "分得开" if d[1] > 0 or d[2] < 0 else "没有显著差别"})
    summary["pairs"].sort(key=lambda p: (p["expected"] is None, p["expected"] or "", p["a"], p["b"], p["metric"], p["scope"]))
    return summary


def pct(v):
    return "—" if v is None else f"{100 * v:.0f}%"


def num(v, fmt="{:.0f}"):
    return "—" if v is None else fmt.format(v)


def render(summary, meta):
    lines = [f"# 课程问答评测:{meta.get('name', '')}", "",
             f"模型 `{meta.get('model') or '(环境默认)'}`,课程 {meta.get('course')},题集 {summary['questions']} 道题,"
             f"SHA-256 `{str(meta.get('questionsSha', ''))[:12]}…`。", ""]
    if meta.get("mock", True):
        lines += ["**这是模拟模型跑的:只说明评测链路通了,下面的数字没有意义。**", ""]
    if summary["warnings"]:
        lines += ["**注意:**"] + [f"- {w}" for w in summary["warnings"]] + [""]
    systems = summary["systems"]
    has_correct = any(s.get("correct") is not None for s in systems.values())
    head = ["系统"] + (["答对率", "跨讲次题"] if has_correct else []) + ["证据定位命中", "跨讲次题", "每一讲都命中", "引文忠实",
                                                                "证据站不住", "被判官拿掉", "正确拒答", "错误拒答", "失败"]
    lines += ["## 质量", "", "| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for name, s in systems.items():
        row = [name] + ([pct(s["correct"]), pct(s["correct_cross"])] if has_correct else [])
        row += [pct(s["located"]), pct(s["located_cross"]), pct(s["located_all"]), pct(s["quote_ok"]), pct(s["weak"]),
                pct(s["judged_out"]) if s["judged"] else "没有判官", pct(s["refused_right"]), pct(s["refused_wrong"]),
                f"{s['failed']}/{s['rows']}"]
        lines.append("| " + " | ".join(row) + " |")
    lines += ["", "- 证据站不住:模型给出的结论里,因为没有证据、时间不存在或原话找不到被拿掉的占比(核验前两层,各系统可比)。",
              "- 被判官拿掉:只有带判官的系统有。没有判官的系统这一栏不是 0,而是没有做这一步;它们答案里的结论站不站得住,看下面的\"事后让判官看\"。",
              "- 两栏都把补一轮之前被拿掉的结论算在内。", ""]
    audited = {n: s for n, s in systems.items() if s.get("final_bad_ci")}
    if audited:
        lines += ["## 事后让判官看没有判官的系统的答案", ""]
        for name, s in audited.items():
            m, lo, hi, n = s["final_bad_ci"]
            lines.append(f"- {name}:答案里的结论有 {100 * m:.0f}% 被判官否掉(95% 区间 {100 * lo:.0f}% 到 {100 * hi:.0f}%,{n} 道题)。"
                         "带判官的系统这些结论已经被拿掉了,不会出现在答案里;判官自己的准确率见校准结果。")
        lines.append("")
    lines += ["## 成本和延迟(每问)", "", "| 系统 | 工具调用 | 输入 token 中位 / p90 | 输出 token 中位 / p90 | 美元 中位 / p90 | 秒 中位 / p90 |",
              "|---|---|---|---|---|---|"]
    for name, s in systems.items():
        lines.append(f"| {name} | {num(s['steps'], '{:.1f}')} | {num(s['tokens_in_median'])} / {num(s['tokens_in_p90'])} | "
                     f"{num(s['tokens_out_median'])} / {num(s['tokens_out_p90'])} | "
                     f"{num(s['cost_median'], '{:.4f}')} / {num(s['cost_p90'], '{:.4f}')} | {num(s['seconds_median'])} / {num(s['seconds_p90'])} |")
    if meta.get("priceIn") is None:
        lines += ["", "没有给单价(run.py 的 --price-in / --price-out),所以没有美元一栏。"]
    lines += ["", "## 系统两两比较(同一套题,逐题配对,bootstrap 95% 区间)", "",
              f"标\"预期\"的是跑之前写下的预期对应的比较;其余是探索性的,一次看这么多区间总会有几个碰巧分得开。两边都有的题不到 {MIN_N} 道时不下结论。", "",
              "| | 比较 | 指标 | 范围 | 平均差 | 区间 | 题数 | 结论 |", "|---|---|---|---|---|---|---|---|"]
    for p in summary["pairs"]:
        lines.append(f"| {p['expected'] or ''} | {p['a']} − {p['b']} | {NAMES[p['metric']]} | {'跨讲次题' if p['scope'] == 'cross' else '全部'} | "
                     f"{100 * p['diff']:+.1f} 点 | [{100 * p['low']:+.1f}, {100 * p['high']:+.1f}] | {p['n']} | {p['verdict']} |")
    lines += ["", "## 各轮之间的波动", ""]
    for name, s in systems.items():
        parts = [f"证据定位 {' / '.join(pct(v) for v in s['located_by_round'])}"]
        if has_correct:
            parts.append(f"答对率 {' / '.join(pct(v) for v in s['correct_by_round'])}")
        lines.append(f"- {name}({s['rounds']} 轮,{s['questionsRun']} 道题):" + ";".join(parts) + f";工具调用次数的分布 {s['steps_histogram']}")
    return "\n".join(lines) + "\n"


def read_jsonl(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        if line.strip():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                pass      # 最后一行写到一半(进程被杀、磁盘满):当它没有
    return rows


def load_run(run_dir):
    """返回 (meta, 题目 {id: 题}, 结果行)。同一题同一系统同一轮有几行(--retry-failed 重跑过)取最后一行。题集改过则拒绝。"""
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    raw = open(os.path.join(run_dir, meta["questions"]), "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    rows = list({(r["qid"], r["system"], r["round"]): r for r in read_jsonl(os.path.join(run_dir, "results.jsonl"))}.values())
    wrong = {r["questionsSha"] for r in rows} - {sha}
    if wrong:
        raise SystemExit(f"这次运行用的不是现在这版题集(结果里是 {sorted(wrong)[0][:12]}…,现在是 {sha[:12]}…)。"
                         "两版题集的结果不能混在一起算。")
    questions = {q["id"]: q for q in (json.loads(l) for l in raw.decode("utf-8").splitlines() if l.strip())}
    return meta, questions, rows


def score_dir(run_dir):
    meta, questions, rows = load_run(run_dir)
    grades = audit = None
    path = os.path.join(run_dir, "grades.jsonl")
    if os.path.exists(path):
        grades = {(g["qid"], g["system"], g["round"]): (g.get("taskUuid"), g["score"]) for g in read_jsonl(path)}
    path = os.path.join(run_dir, "audit.jsonl")
    if os.path.exists(path):
        audit = {(a["qid"], a["system"], a["round"]): (a.get("taskUuid"), a["kept"], a["rejected"]) for a in read_jsonl(path)}
    summary = summarise(rows, questions, meta, grades, audit)
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

    q = {"id": "s1", "kind": "single", "points": ["p"], "gold": [g]}
    x = {"id": "x1", "kind": "cross", "points": ["p"], "gold": [g, {"lecture": 5, "from": "1:00", "to": "2:00"}]}
    absent = {"id": "a1", "kind": "absent", "points": [], "gold": []}

    def row(claims, dropped, system="S1", rnd=1, qid="s1", not_found=False, status="COMPLETED", verifications=None, uuid="u"):
        return {"qid": qid, "system": system, "round": rnd, "questionsSha": "x", "status": status, "seconds": 40.0, "taskUuid": uuid,
                "answer": {"status": status, "notFound": not_found, "claims": claims, "dropped": dropped, "verifications": verifications,
                           "toolCalls": [{}, {}], "usage": {"promptTokens": 900, "completionTokens": 100}}}

    hit3 = {"text": "c", "evidence": [ev(3, 760, 800)]}
    hit5 = {"text": "c", "evidence": [ev(5, 70, 90)]}
    miss = {"text": "c", "evidence": [ev(3, 0, 10)]}
    s = score_row(q, row([hit3, miss], [{"layer": 2}, {"layer": 3}, {"layer": 0}]))
    assert s["located"] == 1 and s["claims"] == 2 and s["steps"] == 2 and s["tokens_in"] == 900 and s["tokens_out"] == 100
    assert abs(s["weak"] - 2 / 5) < 1e-9 and abs(s["judged_out"] - 1 / 5) < 1e-9          # 5 条里:前两层拿掉 2 条,判官拿掉 1 条
    assert abs(s["quote_ok"] - 3 / 4) < 1e-9                                               # 旧格式:按结论算
    assert score_row(q, row([hit3], [{"layer": 3}]), judged=False)["judged_out"] is None   # 没有判官的系统没有这一项
    assert score_row(q, row([miss], []))["located"] == 0
    assert score_row(q, row([], [], not_found=True))["refused_wrong"] == 1
    assert score_row(q, row([], []))["weak"] is None                         # 没有结论:没有分母
    assert score_row(absent, row([], [], not_found=True))["refused_right"] == 1
    assert score_row(absent, row([], [{"layer": 3}]))["refused_right"] == 1  # 没说找不到,但也没有通过核验的结论
    assert score_row(absent, row([hit3], []))["refused_right"] == 0 and "located" not in score_row(absent, row([hit3], []))
    # 跨讲次题:命中一讲算 located,两讲都命中才算 located_all
    assert score_row(x, row([hit3], []))["located"] == 1 and score_row(x, row([hit3], []))["located_all"] == 0
    assert score_row(x, row([hit3, hit5], []))["located_all"] == 1 and "located_all" not in score_row(q, row([hit3], []))
    # 没跑成的:命中类指标算 0,不是丢掉
    assert score_row(q, row([hit3], [], status="FAILED")) == {"failed": 1, "located": 0, "refused_wrong": 0}
    assert score_row(x, row([], [], status="TIMEOUT"))["located_all"] == 0
    assert score_row(absent, row([], [], status="FAILED")) == {"failed": 1, "refused_right": 0}
    # 补过一轮:第一轮 6 条里拿掉 3 条(1 条原话找不到,2 条被判官否掉),第二轮 3 条里拿掉 1 条(判官)。视图的 dropped 只有最后一轮
    two = [{"kept": 3, "dropped": [{"layer": 2}, {"layer": 3}, {"layer": 3}], "evidence": {"proposed": 8, "passed": 6}},
           {"kept": 2, "dropped": [{"layer": 3}], "evidence": {"proposed": 3, "passed": 3}}]
    s = score_row(q, row([hit3] * 5, [{"layer": 3}], verifications=two))
    assert abs(s["weak"] - 1 / 9) < 1e-9 and abs(s["judged_out"] - 3 / 9) < 1e-9          # 9 条里,不是最后一轮的 3 条里
    assert abs(s["quote_ok"] - 9 / 11) < 1e-9                                              # 按证据条数:11 条里 9 条是真的

    same = {f"q{i}": i % 2 for i in range(40)}
    d = bootstrap_diff(same, dict(same))
    assert d[0] == 0 and d[1] <= 0 <= d[2] and d[3] == 40                    # 两组一样:区间包含 0
    better = {k: 1.0 for k in same}
    worse = {k: (0.0 if i % 4 else 1.0) for i, k in enumerate(same)}
    d = bootstrap_diff(better, worse)
    assert d[1] > 0 and abs(d[0] - 0.75) < 1e-9                              # 差别明显:区间不包含 0
    assert bootstrap_diff({"a": 1}, {"b": 1}) is None                        # 没有共同的题
    assert dollars(1_000_000, 100_000, {"priceIn": 0.1, "priceOut": 0.5}) == 0.15 and dollars(1, 1, {}) is None
    tiered = {"priceIn": 0.1, "priceOut": 0.5, "tierTokens": 100_000, "priceInTier": 0.5, "priceOutTier": 2.5}
    assert abs(dollars(200_000, 1_000, tiered, calls=1) - (0.1 + 0.0025)) < 1e-12           # 一次 20 万 token 的调用:整次按另一张价目表
    assert abs(dollars(100_000, 1_000, tiered, calls=1) - 0.0105) < 1e-12                    # 正好 10 万:还是原来的价
    assert abs(dollars(200_000, 1_000, tiered, calls=5) - 0.0205) < 1e-12                    # 五次调用加起来 20 万:每次都不超

    # 汇总:12 道单讲题 + 12 道跨讲次题;S1 全答对,S2 跨讲次题全错。失败的那次按 0 算
    questions = {f"s{i}": {**q, "id": f"s{i}"} for i in range(12)}
    questions.update({f"x{i}": {**x, "id": f"x{i}"} for i in range(12)})
    questions["a1"] = absent
    rows, grades = [], {}
    for qid in questions:
        if qid == "a1":
            rows += [row([], [], "S1", 1, qid, True), row([hit3], [], "S2", 1, qid)]
            grades[(qid, "S1", 1)], grades[(qid, "S2", 1)] = ("u", 1.0), ("u", 0.0)
            continue
        rows.append(row([hit3, hit5], [], "S1", 1, qid))
        rows.append(row([hit3], [], "S2", 1, qid))
        grades[(qid, "S1", 1)] = ("u", 1.0)
        grades[(qid, "S2", 1)] = ("u", 0.0 if qid.startswith("x") else 1.0)
    rows.append(row([hit3], [], "S1", 2, "s0", status="FAILED"))
    meta = {"name": "t", "model": "m", "course": 1, "questionsSha": "abc", "mock": False, "priceIn": 0.1, "priceOut": 0.5,
            "systems": {"S1": {}, "S2": {"mode": "rag"}}}
    out = summarise(rows, questions, meta, grades)
    s1, s2 = out["systems"]["S1"], out["systems"]["S2"]
    assert s1["failed"] == 1 and s1["located"] < 1.0 and s2["located"] == 1.0            # 失败的那次把 s0 的平均拉到 0.5
    assert s1["located_all"] == 1.0 and s2["located_all"] == 0 and s1["refused_right"] == 1 and s2["refused_right"] == 0
    assert s1["correct_cross"] == 1.0 and s2["correct_cross"] == 0.0 and abs(s1["cost_median"] - 0.00014) < 1e-9
    e1 = [p for p in out["pairs"] if p["expected"] == "预期 1"]
    assert len(e1) == 1 and (e1[0]["a"], e1[0]["b"], e1[0]["scope"], e1[0]["n"]) == ("S1", "S2", "cross", 12)
    assert e1[0]["verdict"] == "分得开" and abs(e1[0]["diff"] - 1.0) < 1e-9 and out["pairs"][0] is e1[0]   # 预期的排在前面
    assert any("没跑成" in w for w in out["warnings"]) and any("还没有答对率" in w for w in out["warnings"])
    few = summarise([r for r in rows if r["qid"] in ("x0", "x1")], questions, meta, grades)
    assert all(p["verdict"] == "题数太少,不下结论" for p in few["pairs"])                 # 两道题不下结论
    assert any("只跑了 2 / 25" in w for w in few["warnings"])
    stale = summarise([row([hit3], [], "S1", 1, "s0", uuid="new")], questions, meta, {("s0", "S1", 1): ("old", 1.0)})
    assert stale["systems"]["S1"]["correct"] is None                                      # 重跑之后,旧的分数不算
    audited = summarise([row([hit3], [], "S3", 1, f"s{i}") for i in range(12)], questions,
                        {**meta, "systems": {"S3": {"judge": False}}}, None, {(f"s{i}", "S3", 1): ("u", 4, 1) for i in range(12)})
    assert audited["systems"]["S3"]["judged_out"] is None and abs(audited["systems"]["S3"]["final_bad"] - 0.25) < 1e-9
    text = render(out, meta)
    assert "模拟模型" not in text and "| S1 |" in text and "预期 1" in text and "| 没有判官 |" not in text
    assert "## 事后让判官看" not in text
    assert "模拟模型" in render(out, {**meta, "mock": True})
    assert "| 没有判官 |" in render(audited, meta) and "## 事后让判官看" in render(audited, meta)

    import tempfile
    with tempfile.TemporaryDirectory() as d:                                 # 题集改过:拒绝打分;写到一半的行不算
        raw = "".join(json.dumps(v) + "\n" for v in questions.values())
        open(os.path.join(d, "questions.jsonl"), "w", encoding="utf-8").write(raw)
        sha = hashlib.sha256(raw.encode()).hexdigest()
        json.dump({**meta, "questions": "questions.jsonl", "questionsSha": sha}, open(os.path.join(d, "meta.json"), "w"))
        with open(os.path.join(d, "results.jsonl"), "w") as f:
            # 第一行是同一题同一轮先失败的那次(后来用 --retry-failed 重跑成功了):只算最后一行
            for r in [row([], [], "S1", 1, "s0", status="FAILED")] + rows[:-1]:
                f.write(json.dumps({**r, "questionsSha": sha}) + "\n")
            f.write('{"qid": "s1", "system": "S1", "round": 1, "quest')            # 进程被杀时写到一半的一行
        with open(os.path.join(d, "grades.jsonl"), "w") as f:
            for (qid, system, rnd), (uuid, score) in grades.items():
                f.write(json.dumps({"qid": qid, "system": system, "round": rnd, "taskUuid": uuid, "score": score}) + "\n")
        scored = score_dir(d)["systems"]["S1"]
        assert scored["failed"] == 0 and scored["located"] == 1.0 and scored["correct"] == 1.0
        assert os.path.exists(os.path.join(d, "summary.md"))
        open(os.path.join(d, "questions.jsonl"), "a").write("\n")
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
        score_dir(args.run_dir)
        print(open(os.path.join(args.run_dir, "summary.md"), encoding="utf-8").read())
    else:
        p.error("要给运行目录")
