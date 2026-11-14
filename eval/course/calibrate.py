#!/usr/bin/env python3
"""核验第 3 层(判官)自己的准确率(零依赖,标准库)。

判官说"支持"或"不支持",对不对要有人的标注来量。三个子命令:

  python3 eval/course/calibrate.py export eval/course/runs/<名字> [--n 100] [--seed 7]
      从运行结果里抽"结论 + 证据"对:判官放行的(答案里的结论)和判官否掉的(被第 3 层拿掉的)各占一半左右。
      写 pairs.csv(给人标注:label 一列填 y 或 n,表示"这段原文支持这条结论吗")和 pairs.key.json(判官的判定,标注时不要看)。
      每条证据带着它前后的原文(和判官看到的范围一样),从课程环境取。
  python3 eval/course/calibrate.py score eval/course/runs/<名字>
      标完之后:判官和人一致的比例(准确率)、人说不支持而判官放行的比例(漏拦率)、人说支持而判官否掉的比例(误拦率)。
  python3 eval/course/calibrate.py swap eval/course/runs/<名字> --course 1 [--n 50] [--seed 7]
      不费人工的补充测试:把通过核验的结论换上别的讲次里随机的一句原文当证据,交给核验(COURSE_VERIFY 任务)。
      引文是真的,所以前两层会过;原文和结论无关,判官应该全部拒绝。报告拒绝率。
  python3 eval/course/calibrate.py --self-check

只有带判官的系统(S1、S2)的结果会被抽到:S3、B1 没有第 3 层。
"""
import argparse
import csv
import json
import os
import random
import sys
import time
import urllib.request

MARGIN_MS = 15_000       # 和 EvidenceChecker.MARGIN_MS 一样:判官看到的是证据前后各 15 秒的原文
CONTEXT_CHARS = 600      # 和 CourseVerifyProcessor.MAX_CONTEXT_CHARS 一样
YES, NO = {"y", "yes", "1", "是", "支持"}, {"n", "no", "0", "否", "不支持"}


def http(method, url, body=None, timeout=60):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def clock(ms):
    s = max(0, ms) // 1000
    return f"{s // 60:02d}:{s % 60:02d}"


def read_rows(run_dir):
    rows = {}
    for line in open(os.path.join(run_dir, "results.jsonl"), encoding="utf-8"):
        if line.strip():
            r = json.loads(line)
            rows[(r["qid"], r["system"], r["round"])] = r
    return list(rows.values())


def pairs(rows, judged_systems, questions):
    """每条被判官判过的结论一对:{"qid","system","round","question","claim","evidence","judge": True/False}。"""
    out = []
    for r in rows:
        a = r.get("answer") or {}
        if r["system"] not in judged_systems or r.get("status") != "COMPLETED":
            continue
        q = questions.get(r["qid"], {}).get("question", "")
        base = {"qid": r["qid"], "system": r["system"], "round": r["round"], "question": q}
        for c in a.get("claims") or []:
            out.append({**base, "claim": c["text"], "evidence": c.get("evidence") or [], "judge": True})
        for d in a.get("dropped") or []:
            if d.get("layer") == 3 and d.get("evidence"):
                out.append({**base, "claim": d["text"], "evidence": d["evidence"], "judge": False})
    return out


def sample(all_pairs, n, seed):
    """判官否掉的本来就少:最多占一半,不够就有多少要多少;其余用判官放行的补足。"""
    rng = random.Random(seed)
    no = [p for p in all_pairs if not p["judge"]]
    yes = [p for p in all_pairs if p["judge"]]
    rng.shuffle(no)
    rng.shuffle(yes)
    picked = no[:n // 2]
    picked += yes[:n - len(picked)]
    rng.shuffle(picked)
    return picked


def around(text, quote, limit=CONTEXT_CHARS):
    flat = " ".join(text.split())
    if len(flat) <= limit:
        return flat
    at = max(0, flat.lower().find(" ".join(quote.split()).lower()))
    start = max(0, min(at - max(0, limit - len(quote)) // 2, len(flat) - limit))
    return ("…" if start else "") + flat[start:start + limit] + ("…" if start + limit < len(flat) else "")


def context(e, fetch):
    """这条证据前后的原文。fetch(lectureId, "transcript" 或 "frames") → 那一讲的逐字稿或幻灯片(课程环境的接口)。"""
    lo, hi = e["startMs"] - MARGIN_MS, e["endMs"] + MARGIN_MS
    if e.get("source") == "slide":
        frames = fetch(e["lectureId"], "frames").get("frames", [])
        shown = [f["text"] for i, f in enumerate(frames)
                 if (frames[i + 1]["tsMs"] if i + 1 < len(frames) else float("inf")) > lo and f["tsMs"] < hi]
        return around("\n".join(shown), e.get("quote", ""))
    spoken = [u["text"] for u in fetch(e["lectureId"], "transcript").get("utterances", []) if u["endMs"] > lo and u["startMs"] < hi]
    return around(" ".join(spoken), e.get("quote", ""))


def export(run_dir, n, seed, fetch, judged_systems, questions):
    picked = sample(pairs(read_rows(run_dir), judged_systems, questions), n, seed)
    key = {}
    with open(os.path.join(run_dir, "pairs.csv"), "w", newline="", encoding="utf-8-sig") as f:   # 带 BOM:Excel 直接打开不乱码
        w = csv.writer(f)
        w.writerow(["id", "question", "claim", "evidence", "label(y/n)"])
        for i, p in enumerate(picked, 1):
            pid = f"p{i:03d}"
            shown = "\n\n".join(f"[L{e.get('seq')} {clock(e['startMs'])}-{clock(e['endMs'])} {e.get('source')}] 引用:{e.get('quote', '')}\n"
                                f"原文:{context(e, fetch)}" for e in p["evidence"])
            w.writerow([pid, p["question"], p["claim"], shown, ""])
            key[pid] = {k: p[k] for k in ("qid", "system", "round", "judge")}
    json.dump(key, open(os.path.join(run_dir, "pairs.key.json"), "w"), ensure_ascii=False, indent=1)
    return len(picked), sum(1 for p in picked if not p["judge"])


def score(run_dir):
    key = json.load(open(os.path.join(run_dir, "pairs.key.json")))
    yy = yn = ny = nn = skipped = 0          # 第一个字母是人,第二个是判官
    for row in csv.DictReader(open(os.path.join(run_dir, "pairs.csv"), encoding="utf-8-sig")):
        label = (row.get("label(y/n)") or "").strip().lower()
        if label not in YES | NO or row["id"] not in key:
            skipped += 1
            continue
        human, judge = label in YES, key[row["id"]]["judge"]
        yy += human and judge
        yn += human and not judge
        ny += (not human) and judge
        nn += (not human) and (not judge)
    total = yy + yn + ny + nn
    return {"labelled": total, "skipped": skipped,
            "accuracy": (yy + nn) / total if total else None,
            "missRate": ny / (ny + nn) if ny + nn else None,           # 人说不支持,判官放行了
            "falseBlockRate": yn / (yy + yn) if yy + yn else None,     # 人说支持,判官否掉了
            "counts": {"bothYes": yy, "humanYesJudgeNo": yn, "humanNoJudgeYes": ny, "bothNo": nn}}


def swapped(pair, lectures, utterances_of, rng):
    """把这条结论的证据换成别的讲次里随机的一句话(够长,真实存在)。lectures: [{"id","seq"}]。"""
    used = {e.get("seq") for e in pair["evidence"]}
    others = [l for l in lectures if l["seq"] not in used] or lectures
    lecture = rng.choice(others)
    long_enough = [u for u in utterances_of(lecture["id"]) if len(u["text"]) >= 40]
    u = rng.choice(long_enough)
    return {"text": pair["claim"], "evidence": [{"lecture": lecture["seq"], "startMs": u["startMs"], "endMs": u["endMs"],
                                                 "source": "speech", "quote": u["text"][:150].strip()}]}


def swap(run_dir, course, n, seed, api, judged_systems, questions, model=None, timeout=600):
    rng = random.Random(seed)
    kept = [p for p in pairs(read_rows(run_dir), judged_systems, questions) if p["judge"]]
    rng.shuffle(kept)
    lectures = [{"id": l["id"], "seq": l["seq"]} for l in http("GET", f"{api}/courses/{course}")["lectures"]
                if l["status"] in ("READY", "PARTIAL")]
    cache = {}

    def utterances_of(lecture_id):
        if lecture_id not in cache:
            cache[lecture_id] = http("GET", f"{api}/lectures/{lecture_id}/transcript")["utterances"]
        return cache[lecture_id]

    rejected = accepted = other = 0
    for p in kept[:n]:
        body = {"courseId": course, "question": p["question"] or "(评测:随机证据)", "claims": [swapped(p, lectures, utterances_of, rng)]}
        if model:
            body["model"] = model
        uuid = http("POST", f"{api}/tasks", {"type": "COURSE_VERIFY", "payload": body})["taskUuid"]
        deadline, view = time.time() + timeout, {}
        while time.time() < deadline:
            view = http("GET", f"{api}/tasks/{uuid}")
            if view["status"] not in ("PENDING", "RUNNING"):
                break
            time.sleep(1)
        out = ((view.get("result") or [{}])[0] or {}).get("output") or {}
        if out.get("claims"):
            accepted += 1                      # 判官放行了一条和原文无关的结论
        elif any(d.get("layer") == 3 for d in out.get("dropped", [])):
            rejected += 1
        else:
            other += 1                         # 没走到判官(前两层就没过,或者任务失败):不算进拒绝率
    judged = rejected + accepted
    return {"tried": rejected + accepted + other, "rejected": rejected, "accepted": accepted, "notJudged": other,
            "rejectionRate": rejected / judged if judged else None}


def self_check():
    import tempfile
    ev = {"lectureId": 13, "seq": 3, "startMs": 750000, "endMs": 805000, "source": "speech", "quote": "You collect human preferences."}
    rows = [{"qid": "s1", "system": "S1", "round": 1, "status": "COMPLETED", "answer": {
                "claims": [{"text": "kept A", "evidence": [ev]}, {"text": "kept B", "evidence": [ev]}],
                "dropped": [{"text": "judge said no", "layer": 3, "reason": "r", "evidence": [ev]},
                            {"text": "fabricated", "layer": 2, "reason": "r"}, {"text": "old row without evidence", "layer": 3, "reason": "r"},
                            {"text": "dropped before the judge saw it", "layer": 1, "reason": "r", "evidence": [ev]}]}},
            {"qid": "s1", "system": "S3", "round": 1, "status": "COMPLETED", "answer": {"claims": [{"text": "no judge", "evidence": [ev]}], "dropped": []}},
            {"qid": "s1", "system": "S1", "round": 2, "status": "FAILED", "answer": {"claims": [{"text": "x", "evidence": [ev]}]}}]
    questions = {"s1": {"question": "what is rlhf"}}
    ps = pairs(rows, {"S1", "S2"}, questions)
    assert [(p["claim"], p["judge"]) for p in ps] == [("kept A", True), ("kept B", True), ("judge said no", False)]
    assert ps[0]["question"] == "what is rlhf"
    picked = sample(ps, 2, 7)
    assert len(picked) == 2 and sum(1 for p in picked if not p["judge"]) == 1      # 判官否掉的占一半
    assert len(sample(ps, 10, 7)) == 3 and sample(ps, 2, 7) == sample(ps, 2, 7)    # 不够就全要;同一个种子结果相同
    many = [{"claim": f"no {i}", "judge": False} for i in range(4)] + [{"claim": f"yes {i}", "judge": True} for i in range(4)]
    assert sum(1 for p in sample(many, 4, 7) if not p["judge"]) == 2               # 判官否掉的再多,也只占一半

    def fetch(lecture_id, what):
        assert lecture_id == 13
        if what == "transcript":
            return {"utterances": [{"startMs": 700000, "endMs": 710000, "text": "far before"},
                                   {"startMs": 740000, "endMs": 750000, "text": "So what is reward modeling?"},
                                   {"startMs": 750000, "endMs": 760000, "text": "You collect human preferences."},
                                   {"startMs": 900000, "endMs": 910000, "text": "far after"}]}
        return {"frames": [{"tsMs": 100000, "text": "Old slide"}, {"tsMs": 740000, "text": "Reward modeling\nfrom preferences"},
                           {"tsMs": 900000, "text": "Next"}]}

    assert context(ev, fetch) == "So what is reward modeling? You collect human preferences."
    # 前一张幻灯片在证据开始前 10 秒才换掉,落在前后各 15 秒的范围里:和判官看到的一样,也带上
    assert context({**ev, "source": "slide"}, fetch) == "Old slide Reward modeling from preferences"
    assert context({**ev, "source": "slide", "startMs": 760000}, fetch) == "Reward modeling from preferences"
    long_text = "filler " * 200 + "the quoted sentence is here" + " tail" * 200
    cut = around(long_text, "the quoted sentence is here")
    assert "the quoted sentence is here" in cut and len(cut) <= CONTEXT_CHARS + 2 and cut.startswith("…") and cut.endswith("…")

    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "results.jsonl"), "w") as f:
            for r in rows:
                f.write(json.dumps(r) + "\n")
        n, no = export(d, 100, 7, fetch, {"S1", "S2"}, questions)
        assert (n, no) == (3, 1)
        table = list(csv.DictReader(open(os.path.join(d, "pairs.csv"), encoding="utf-8-sig")))
        key = json.load(open(os.path.join(d, "pairs.key.json")))
        assert len(table) == 3 and set(table[0]) == {"id", "question", "claim", "evidence", "label(y/n)"}
        assert "judge" not in " ".join(table[0].keys()).lower()                      # 标注的人看不到判官的判定
        assert "[L3 12:30-13:25 speech] 引用:You collect human preferences." in table[0]["evidence"]
        assert "原文:So what is reward modeling? You collect human preferences." in table[0]["evidence"]
        assert score(d)["labelled"] == 0 and score(d)["skipped"] == 3              # 还没标
        # 人的标注:判官放行的两条里一条其实不支持;判官否掉的那条其实支持
        human = {}
        seen_yes = 0
        for row in table:
            if key[row["id"]]["judge"]:
                seen_yes += 1
                human[row["id"]] = "y" if seen_yes == 1 else "否"
            else:
                human[row["id"]] = "Y"
        with open(os.path.join(d, "pairs.csv"), "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(["id", "question", "claim", "evidence", "label(y/n)"])
            for row in table:
                w.writerow([row["id"], row["question"], row["claim"], row["evidence"], human[row["id"]]])
        s = score(d)
        assert s["labelled"] == 3 and abs(s["accuracy"] - 1 / 3) < 1e-9
        assert s["missRate"] == 1.0                    # 人说不支持的 1 条,判官放行了
        assert s["falseBlockRate"] == 0.5              # 人说支持的 2 条,判官否掉 1 条
        assert s["counts"] == {"bothYes": 1, "humanYesJudgeNo": 1, "humanNoJudgeYes": 1, "bothNo": 0}

    rng = random.Random(7)
    lectures = [{"id": 11, "seq": 1}, {"id": 13, "seq": 3}]
    utter = {11: [{"startMs": 0, "endMs": 9000, "text": "short"}, {"startMs": 9000, "endMs": 20000, "text": "A sentence from another lecture that is long enough to quote."}]}
    c = swapped(ps[0], lectures, lambda i: utter[i], rng)
    assert c["text"] == "kept A" and c["evidence"][0]["lecture"] == 1                # 换到了别的讲次
    assert c["evidence"][0]["quote"].startswith("A sentence from another lecture") and c["evidence"][0]["startMs"] == 9000
    print("self-check ok")


def main():
    p = argparse.ArgumentParser(description="判官(核验第 3 层)的校准")
    p.add_argument("command", nargs="?", choices=("export", "score", "swap"))
    p.add_argument("run_dir", nargs="?")
    p.add_argument("--n", type=int)
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--course", type=int)
    p.add_argument("--model")
    p.add_argument("--api", default="http://127.0.0.1:18090/api/v1")
    p.add_argument("--self-check", action="store_true")
    args = p.parse_args()
    if args.self_check:
        self_check()
        return 0
    if not args.command or not args.run_dir:
        p.error("要给子命令和运行目录")
    if args.command == "score":
        print(json.dumps(score(args.run_dir), ensure_ascii=False, indent=2))
        return 0
    meta = json.load(open(os.path.join(args.run_dir, "meta.json")))
    judged = {name for name, options in meta["systems"].items() if options.get("judge", True)}
    questions = {q["id"]: q for q in (json.loads(l) for l in open(meta["questions"], encoding="utf-8") if l.strip())}
    if args.command == "export":
        fetch = lambda lecture_id, what: http("GET", f"{args.api}/lectures/{lecture_id}/{what}")  # noqa: E731
        n, no = export(args.run_dir, args.n or 100, args.seed, fetch, judged, questions)
        print(f"抽了 {n} 对(其中判官否掉的 {no} 对):{args.run_dir}/pairs.csv。在 label 一列填 y 或 n,不要看 pairs.key.json。")
        return 0
    if not args.course:
        p.error("swap 要给 --course")
    print(json.dumps(swap(args.run_dir, args.course, args.n or 50, args.seed, args.api, judged, questions, args.model),
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
