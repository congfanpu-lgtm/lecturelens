#!/usr/bin/env python3
"""核验第 3 层(判官)自己的准确率,以及没有判官的系统答案里有多少站不住(零依赖,标准库)。

  python3 eval/course/calibrate.py export eval/course/runs/<名字> [--n 100] [--seed 7] [--force]
      从运行结果里抽"结论 + 证据"对:判官放行的(答案里的结论)和判官否掉的(被第 3 层拿掉的,各轮都算)各占一半左右。
      写 pairs.csv(给人标注:label 一列填 y 或 n,表示"这段原文支持这条结论吗")和 pairs.key.json(判官的判定,标注时不要看)。
      每条证据带着判官当时看到的那段原文。已经有标注的 pairs.csv 不会被覆盖(除非 --force)。
  python3 eval/course/calibrate.py score eval/course/runs/<名字>
      标完之后算判官的准确率、漏拦率(人说不支持、判官放行了)、误拦率(人说支持、判官否掉了)。
      抽样时判官否掉的占了一半,而实际运行里判官否掉的只是少数,所以三个数都按实际比例加权(weighted);
      没加权的(sample)只是这份样本上的一致率,不能当成判官的准确率。
  python3 eval/course/calibrate.py swap eval/course/runs/<名字> --course 1 [--n 50] [--seed 7]
      不费人工的补充测试:把通过核验的结论换上别的讲次里随机的一句原文当证据,交给核验(COURSE_VERIFY 任务)。
      引文是真的,所以前两层会过;原文和结论无关,判官应该全部拒绝。报告拒绝率,并列出被放行的那几条供人看。
  python3 eval/course/calibrate.py audit eval/course/runs/<名字> --course 1
      没有判官的系统(S3、B1):把它们答案里的结论交给判官(COURSE_VERIFY 任务),记下被否掉了几条,写 audit.jsonl。
      score.py 据此给出"答案里的结论事后被判官否掉的占比"。中断后再跑会接着做。
  python3 eval/course/calibrate.py --self-check
"""
import argparse
import csv
import json
import os
import random
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score as scoring  # noqa: E402

MARGIN_MS = 15_000       # 和 EvidenceChecker.MARGIN_MS 一样:只在结果里没有存判官看到的原文时(旧结果)才用来重新取
CONTEXT_CHARS = 600
MAX_CLAIMS = 12          # COURSE_VERIFY 一次最多核验这么多条
YES, NO = {"y", "yes", "1", "是", "支持"}, {"n", "no", "0", "否", "不支持"}
HEADER = ["id", "question", "claim", "evidence", "label(y/n)"]


def http(method, url, body=None, timeout=60):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def clock(ms):
    s = max(0, ms) // 1000
    return f"{s // 60:02d}:{s % 60:02d}"


def pairs(rows, judged_systems, questions):
    """每条被判官判过的结论一对:{"qid","system","round","question","claim","evidence","judge": True/False}。
    判官否掉的从每一轮核验里取(补过一轮的话,第一轮被否掉的也在),同一条结论只算一次。"""
    out = []
    for r in rows:
        a = r.get("answer") or {}
        if r["system"] not in judged_systems or r.get("status") != "COMPLETED":
            continue
        base = {"qid": r["qid"], "system": r["system"], "round": r["round"],
                "question": questions.get(r["qid"], {}).get("question", "")}
        for c in a.get("claims") or []:
            out.append({**base, "claim": c["text"], "evidence": c.get("evidence") or [], "judge": True})
        seen = set()
        for rnd in a.get("verifications") or [{"dropped": a.get("dropped") or []}]:
            for d in rnd.get("dropped") or []:
                if d.get("layer") == 3 and d.get("evidence") and d["text"] not in seen:
                    seen.add(d["text"])
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
    """给标注的人看的原文:就是判官当时看到的那一段(核验把它存在证据里)。旧结果里没有存的,才按同样的范围重新取。"""
    if e.get("context"):
        return e["context"]
    lo, hi = e["startMs"] - MARGIN_MS, e["endMs"] + MARGIN_MS
    if e.get("source") == "slide":
        frames = fetch(e["lectureId"], "frames").get("frames", [])
        shown = [f["text"] for i, f in enumerate(frames)
                 if (frames[i + 1]["tsMs"] if i + 1 < len(frames) else float("inf")) > lo and f["tsMs"] < hi]
        return around("\n".join(shown), e.get("quote", ""))
    spoken = [u["text"] for u in fetch(e["lectureId"], "transcript").get("utterances", []) if u["endMs"] > lo and u["startMs"] < hi]
    return around(" ".join(spoken), e.get("quote", ""))


def labelled(path):
    """pairs.csv 里已经填了标注的行数(文件不存在是 0)。"""
    if not os.path.exists(path):
        return 0
    return sum(1 for row in csv.DictReader(open(path, encoding="utf-8-sig")) if (row.get(HEADER[4]) or "").strip())


def export(run_dir, n, seed, fetch, judged_systems, questions, rows, force=False):
    path = os.path.join(run_dir, "pairs.csv")
    if labelled(path) and not force:
        raise SystemExit(f"{path} 里已经有 {labelled(path)} 行标注,不覆盖。真要重新抽样就加 --force(标注会丢)。")
    everything = pairs(rows, judged_systems, questions)
    picked = sample(everything, n, seed)
    key = {"population": {"judgeYes": sum(1 for p in everything if p["judge"]),      # 抽样之前判官放行、否掉的各有多少:加权用
                          "judgeNo": sum(1 for p in everything if not p["judge"])}, "pairs": {}}
    with open(path, "w", newline="", encoding="utf-8-sig") as f:   # 带 BOM:Excel 直接打开不乱码
        w = csv.writer(f)
        w.writerow(HEADER)
        for i, p in enumerate(picked, 1):
            pid = f"p{i:03d}"
            shown = "\n\n".join(f"[L{e.get('seq')} {clock(e['startMs'])}-{clock(e['endMs'])} {e.get('source')}] 引用:{e.get('quote', '')}\n"
                                f"原文:{context(e, fetch)}" for e in p["evidence"])
            w.writerow([pid, p["question"], p["claim"], shown, ""])
            key["pairs"][pid] = {k: p[k] for k in ("qid", "system", "round", "judge")}
    json.dump(key, open(os.path.join(run_dir, "pairs.key.json"), "w"), ensure_ascii=False, indent=1)
    return len(picked), sum(1 for p in picked if not p["judge"])


def rates(yy, yn, ny, nn, judge_yes, judge_no):
    """第一个字母是人,第二个是判官。judge_yes / judge_no:实际运行里判官放行、否掉的结论数(抽样之前)。
    样本是按判官的判定分层抽的,所以先算每一层里人和判官的一致率,再按实际比例合起来。"""
    a = yy / (yy + ny) if yy + ny else None          # 判官放行的里面,人也说支持的占比
    b = yn / (yn + nn) if yn + nn else None          # 判官否掉的里面,人说其实支持的占比
    total = judge_yes + judge_no
    out = {"agreeWhenJudgeYes": a, "agreeWhenJudgeNo": None if b is None else 1 - b, "accuracy": None, "missRate": None, "falseBlockRate": None}
    if total == 0 or (judge_yes and a is None) or (judge_no and b is None):
        return out                                    # 有一层没有标注:合不出来
    w = judge_yes / total
    a, b = a or 0.0, b or 0.0
    human_no = w * (1 - a) + (1 - w) * (1 - b)
    human_yes = w * a + (1 - w) * b
    out["accuracy"] = w * a + (1 - w) * (1 - b)
    out["missRate"] = w * (1 - a) / human_no if human_no else None        # 人说不支持的里面,判官放行了多少
    out["falseBlockRate"] = (1 - w) * b / human_yes if human_yes else None  # 人说支持的里面,判官否掉了多少
    return out


def score(run_dir):
    key = json.load(open(os.path.join(run_dir, "pairs.key.json")))
    yy = yn = ny = nn = skipped = 0
    for row in csv.DictReader(open(os.path.join(run_dir, "pairs.csv"), encoding="utf-8-sig")):
        label = (row.get(HEADER[4]) or "").strip().lower()
        if label not in YES | NO or row["id"] not in key["pairs"]:
            skipped += 1
            continue
        human, judge = label in YES, key["pairs"][row["id"]]["judge"]
        yy += human and judge
        yn += human and not judge
        ny += (not human) and judge
        nn += (not human) and (not judge)
    total = yy + yn + ny + nn
    pop = key["population"]
    return {"labelled": total, "skipped": skipped, "population": pop,
            "counts": {"bothYes": yy, "humanYesJudgeNo": yn, "humanNoJudgeYes": ny, "bothNo": nn},
            "sample": {"agreement": (yy + nn) / total if total else None,
                       "note": "样本里判官否掉的占了一半左右,这个一致率不是判官在实际运行里的准确率"},
            "weighted": rates(yy, yn, ny, nn, pop["judgeYes"], pop["judgeNo"])}


def swapped(pair, lectures, utterances_of, rng):
    """把这条结论的证据换成别的讲次里随机的一句话(够长,真实存在)。lectures: [{"id","seq"}]。只有一讲的课做不了。"""
    used = {e.get("seq") for e in pair["evidence"]}
    others = [l for l in lectures if l["seq"] not in used]
    if not others:
        return None
    lecture = rng.choice(others)
    u = rng.choice([u for u in utterances_of(lecture["id"]) if len(u["text"]) >= 40])
    return {"text": pair["claim"], "evidence": [{"lecture": lecture["seq"], "startMs": u["startMs"], "endMs": u["endMs"],
                                                 "source": "speech", "quote": u["text"][:150].strip()}]}


def verify(api, course, question, claims, model=None, timeout=600):
    """提交一个 COURSE_VERIFY 任务,等它落定,返回 QA_VERIFY 的输出(没跑成返回 None)。"""
    body = {"courseId": course, "question": question or "(评测)", "claims": claims}
    if model:
        body["model"] = model
    uuid = http("POST", f"{api}/tasks", {"type": "COURSE_VERIFY", "payload": body})["taskUuid"]
    deadline, view = time.time() + timeout, {}
    while time.time() < deadline:
        view = http("GET", f"{api}/tasks/{uuid}")
        if view["status"] not in ("PENDING", "RUNNING"):
            break
        time.sleep(1)
    return ((view.get("result") or [{}])[0] or {}).get("output") if view.get("status") == "COMPLETED" else None


def swap(rows, course, n, seed, api, judged_systems, questions, model=None, verifier=verify):
    rng = random.Random(seed)
    kept, seen = [], set()
    for p in pairs(rows, judged_systems, questions):
        if p["judge"] and p["claim"] not in seen:     # 各轮里文字相同的结论只用一次
            seen.add(p["claim"])
            kept.append(p)
    rng.shuffle(kept)
    lectures = [{"id": l["id"], "seq": l["seq"]} for l in http("GET", f"{api}/courses/{course}")["lectures"]
                if l["status"] in ("READY", "PARTIAL")]
    cache = {}

    def utterances_of(lecture_id):
        if lecture_id not in cache:
            cache[lecture_id] = http("GET", f"{api}/lectures/{lecture_id}/transcript")["utterances"]
        return cache[lecture_id]

    rejected = other = 0
    accepted = []
    for p in kept[:n]:
        claim = swapped(p, lectures, utterances_of, rng)
        out = verifier(api, course, p["question"], [claim], model) if claim else None
        if out and out.get("claims"):
            accepted.append({"claim": claim["text"], "evidence": claim["evidence"][0]})   # 放行了一条和原文无关的结论:列出来给人看
        elif out and any(d.get("layer") == 3 for d in out.get("dropped", [])):
            rejected += 1
        else:
            other += 1                         # 没走到判官(前两层没过、任务失败、课只有一讲):不算进拒绝率
    judged = rejected + len(accepted)
    return {"tried": judged + other, "rejected": rejected, "accepted": len(accepted), "notJudged": other,
            "rejectionRate": rejected / judged if judged else None, "acceptedCases": accepted}


def audit(run_dir, rows, course, api, unjudged_systems, questions, model=None, verifier=verify, log=print):
    """没有判官的系统:它答案里的每条结论(证据已经过了前两层)交给判官,记下被否掉几条。"""
    path = os.path.join(run_dir, "audit.jsonl")
    done = {(a["qid"], a["system"], a["round"], a.get("taskUuid")) for a in scoring.read_jsonl(path)} if os.path.exists(path) else set()
    todo = [r for r in rows if r["system"] in unjudged_systems and r.get("status") == "COMPLETED"
            and (r.get("answer") or {}).get("claims") and (r["qid"], r["system"], r["round"], r.get("taskUuid")) not in done]
    log(f"{len(todo)} 次运行的答案要让判官看")
    with open(path, "a", encoding="utf-8") as out:
        for r in todo:
            claims = [{"text": c["text"], "evidence": [{"lecture": e["seq"], "startMs": e["startMs"], "endMs": e["endMs"],
                                                        "source": e["source"], "quote": e["quote"]} for e in (c.get("evidence") or [])[:4]]}
                      for c in r["answer"]["claims"]]
            kept = rejected = 0
            failed = False
            for i in range(0, len(claims), MAX_CLAIMS):
                result = verifier(api, course, questions.get(r["qid"], {}).get("question", ""), claims[i:i + MAX_CLAIMS], model)
                if result is None:
                    failed = True
                    break
                kept += len(result.get("claims") or [])
                rejected += sum(1 for d in result.get("dropped") or [] if d.get("layer") == 3)
            if failed:
                log(f"{r['system']} {r['qid']} 第 {r['round']} 轮:核验没跑成,下次再试")
                continue
            # kept 是交给判官的结论数(分母),rejected 是被否掉的;前两层这次没过的(逐字稿变了才会有)不算在内
            out.write(json.dumps({"qid": r["qid"], "system": r["system"], "round": r["round"], "taskUuid": r.get("taskUuid"),
                                  "kept": kept + rejected, "rejected": rejected}, ensure_ascii=False) + "\n")
            out.flush()
    return len(todo)


def self_check():
    import tempfile
    ev = {"lectureId": 13, "seq": 3, "startMs": 750000, "endMs": 805000, "source": "speech", "quote": "You collect human preferences."}
    first_round = {"kept": 1, "dropped": [{"text": "rejected in round one", "layer": 3, "reason": "r", "evidence": [ev]},
                                           {"text": "judge said no", "layer": 3, "reason": "r", "evidence": [ev]}]}
    rows = [{"qid": "s1", "system": "S1", "round": 1, "status": "COMPLETED", "taskUuid": "u1", "answer": {
                "claims": [{"text": "kept A", "evidence": [ev]}, {"text": "kept B", "evidence": [ev]}],
                "dropped": [{"text": "judge said no", "layer": 3, "reason": "r", "evidence": [ev]},
                            {"text": "fabricated", "layer": 2, "reason": "r"}, {"text": "old row without evidence", "layer": 3, "reason": "r"},
                            {"text": "dropped before the judge saw it", "layer": 1, "reason": "r", "evidence": [ev]}],
                "verifications": [first_round, {"kept": 1, "dropped": [
                    {"text": "judge said no", "layer": 3, "reason": "r", "evidence": [ev]}, {"text": "fabricated", "layer": 2, "reason": "r"},
                    {"text": "dropped before the judge saw it", "layer": 1, "reason": "r", "evidence": [ev]}]}]}},
            {"qid": "s1", "system": "S3", "round": 1, "status": "COMPLETED", "taskUuid": "u3",
             "answer": {"claims": [{"text": "no judge", "evidence": [ev]}], "dropped": []}},
            {"qid": "s1", "system": "S1", "round": 2, "status": "FAILED", "answer": {"claims": [{"text": "x", "evidence": [ev]}]}}]
    questions = {"s1": {"question": "what is rlhf"}}
    ps = pairs(rows, {"S1", "S2"}, questions)
    # 判官否掉的从每一轮里取(第一轮被否掉的也在),同一条只算一次;没有判官的系统、没跑成的运行、前两层拿掉的都不算
    assert [(p["claim"], p["judge"]) for p in ps] == [("kept A", True), ("kept B", True), ("rejected in round one", False), ("judge said no", False)]
    assert ps[0]["question"] == "what is rlhf"
    old_format = [{**rows[0], "answer": {k: v for k, v in rows[0]["answer"].items() if k != "verifications"}}]
    assert [p["claim"] for p in pairs(old_format, {"S1"}, questions) if not p["judge"]] == ["judge said no"]
    picked = sample(ps, 2, 7)
    assert len(picked) == 2 and sum(1 for p in picked if not p["judge"]) == 1      # 判官否掉的占一半
    assert len(sample(ps, 10, 7)) == 4 and sample(ps, 2, 7) == sample(ps, 2, 7)    # 不够就全要;同一个种子结果相同
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

    # 判官看到的原文存在证据里:就用它,不重新取、不重新截
    assert context({**ev, "context": "exactly what the judge saw"}, lambda *a: 1 / 0) == "exactly what the judge saw"
    assert context(ev, fetch) == "So what is reward modeling? You collect human preferences."      # 旧结果:重新取
    assert context({**ev, "source": "slide"}, fetch) == "Old slide Reward modeling from preferences"
    long_text = "filler " * 200 + "the quoted sentence is here" + " tail" * 200
    cut = around(long_text, "the quoted sentence is here")
    assert "the quoted sentence is here" in cut and len(cut) <= CONTEXT_CHARS + 2 and cut.startswith("…") and cut.endswith("…")

    # 加权:1000 条结论,判官放行 900、否掉 100。放行的里有 10% 其实不支持;否掉的里有 20% 其实支持。
    # 真实的漏拦率 = 90 / (90 + 80) = 52.9%,误拦率 = 20 / (810 + 20) = 2.4%,准确率 = (810 + 80) / 1000 = 89%。
    # 样本各抽 50 条:放行的 45 支持 5 不支持,否掉的 10 支持 40 不支持。不加权的话漏拦率是 5 / 45 = 11%,差了近五倍
    r = rates(yy=45, yn=10, ny=5, nn=40, judge_yes=900, judge_no=100)
    assert abs(r["accuracy"] - 0.89) < 1e-9 and abs(r["missRate"] - 90 / 170) < 1e-9 and abs(r["falseBlockRate"] - 20 / 830) < 1e-9
    assert abs(r["agreeWhenJudgeYes"] - 0.9) < 1e-9 and abs(r["agreeWhenJudgeNo"] - 0.8) < 1e-9
    assert rates(45, 0, 5, 0, 900, 100)["accuracy"] is None                # 判官否掉的那一层没有标注:合不出来
    assert abs(rates(45, 0, 5, 0, 900, 0)["accuracy"] - 0.9) < 1e-9        # 判官一条都没否掉过:只有一层

    with tempfile.TemporaryDirectory() as d:
        n, no = export(d, 100, 7, fetch, {"S1", "S2"}, questions, rows)
        assert (n, no) == (4, 2)
        table = list(csv.DictReader(open(os.path.join(d, "pairs.csv"), encoding="utf-8-sig")))
        key = json.load(open(os.path.join(d, "pairs.key.json")))
        assert key["population"] == {"judgeYes": 2, "judgeNo": 2} and len(table) == 4 and set(table[0]) == set(HEADER)
        assert "judge" not in " ".join(table[0].keys()).lower()                      # 标注的人看不到判官的判定
        assert "[L3 12:30-13:25 speech] 引用:You collect human preferences." in table[0]["evidence"]
        assert score(d)["labelled"] == 0 and score(d)["skipped"] == 4              # 还没标
        # 人的标注:判官放行的两条里一条其实不支持;判官否掉的两条里一条其实支持
        human, yes_seen, no_seen = {}, 0, 0
        for row in table:
            if key["pairs"][row["id"]]["judge"]:
                yes_seen += 1
                human[row["id"]] = "y" if yes_seen == 1 else "否"
            else:
                no_seen += 1
                human[row["id"]] = "Y" if no_seen == 1 else "n"
        with open(os.path.join(d, "pairs.csv"), "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(HEADER)
            for row in table:
                w.writerow([row["id"], row["question"], row["claim"], row["evidence"], human[row["id"]]])
        s = score(d)
        assert s["labelled"] == 4 and s["sample"]["agreement"] == 0.5
        assert s["counts"] == {"bothYes": 1, "humanYesJudgeNo": 1, "humanNoJudgeYes": 1, "bothNo": 1}
        assert s["weighted"]["accuracy"] == 0.5 and s["weighted"]["missRate"] == 0.5 and s["weighted"]["falseBlockRate"] == 0.5
        try:                                                                         # 已经有标注:不覆盖
            export(d, 100, 7, fetch, {"S1", "S2"}, questions, rows)
            raise AssertionError("有标注的 pairs.csv 不该被覆盖")
        except SystemExit as e:
            assert "已经有 4 行标注" in str(e)
        assert labelled(os.path.join(d, "pairs.csv")) == 4
        assert export(d, 100, 7, fetch, {"S1", "S2"}, questions, rows, force=True) == (4, 2)

        # audit:没有判官的系统答案里的结论交给判官;只看 S3;做过的不重做
        asked = []

        def verifier(api, course, question, claims, model):
            asked.append((question, [c["text"] for c in claims], claims[0]["evidence"][0]["lecture"]))
            return {"claims": [], "dropped": [{"text": claims[0]["text"], "layer": 3, "reason": "r"}]}

        quiet = lambda *_: None  # noqa: E731
        assert audit(d, rows, 1, "api", {"S3", "B1"}, questions, verifier=verifier, log=quiet) == 1
        assert asked == [("what is rlhf", ["no judge"], 3)]
        line = json.loads(open(os.path.join(d, "audit.jsonl")).read())
        assert line == {"qid": "s1", "system": "S3", "round": 1, "taskUuid": "u3", "kept": 1, "rejected": 1}
        assert audit(d, rows, 1, "api", {"S3", "B1"}, questions, verifier=verifier, log=quiet) == 0 and len(asked) == 1
        assert audit(d, rows, 1, "api", {"S3"}, questions, verifier=lambda *a: None, log=quiet) == 0   # 都做过了

    rng = random.Random(7)
    lectures = [{"id": 11, "seq": 1}, {"id": 13, "seq": 3}]
    utter = {11: [{"startMs": 0, "endMs": 9000, "text": "short"}, {"startMs": 9000, "endMs": 20000, "text": "A sentence from another lecture that is long enough to quote."}]}
    c = swapped(ps[0], lectures, lambda i: utter[i], rng)
    assert c["text"] == "kept A" and c["evidence"][0]["lecture"] == 1                # 换到了别的讲次
    assert c["evidence"][0]["quote"].startswith("A sentence from another lecture") and c["evidence"][0]["startMs"] == 9000
    assert swapped(ps[0], [{"id": 13, "seq": 3}], lambda i: utter[i], rng) is None    # 课只有一讲:没有"别的讲次"
    print("self-check ok")


def main():
    p = argparse.ArgumentParser(description="判官(核验第 3 层)的校准")
    p.add_argument("command", nargs="?", choices=("export", "score", "swap", "audit"))
    p.add_argument("run_dir", nargs="?")
    p.add_argument("--n", type=int)
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--course", type=int)
    p.add_argument("--model")
    p.add_argument("--force", action="store_true")
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
    meta, questions, rows = scoring.load_run(args.run_dir)
    judged = {name for name, options in meta["systems"].items() if options.get("judge", True)}
    if args.command == "export":
        fetch = lambda lecture_id, what: http("GET", f"{args.api}/lectures/{lecture_id}/{what}")  # noqa: E731
        n, no = export(args.run_dir, args.n or 100, args.seed, fetch, judged, questions, rows, args.force)
        print(f"抽了 {n} 对(其中判官否掉的 {no} 对):{args.run_dir}/pairs.csv。在 label 一列填 y 或 n,不要看 pairs.key.json。")
        return 0
    if not args.course:
        p.error(f"{args.command} 要给 --course")
    if args.command == "swap":
        print(json.dumps(swap(rows, args.course, args.n or 50, args.seed, args.api, judged, questions, args.model), ensure_ascii=False, indent=2))
    else:
        n = audit(args.run_dir, rows, args.course, args.api, set(meta["systems"]) - judged, questions, args.model)
        print(f"让判官看了 {n} 次运行的答案;重新出报告:python3 eval/course/score.py {args.run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
