#!/usr/bin/env python3
"""课程问答评测的题集:校验和冻结(零依赖,标准库)。

题集是 questions.jsonl,一行一题:
  {"id":"x07","kind":"cross","question":"…","points":["要点一","要点二"],
   "gold":[{"lecture":3,"from":"12:30","to":"14:10"}],"author":"user","note":"可省"}
kind: single(单讲)/ cross(跨讲次)/ absent(课里没讲:points 和 gold 必须为空)。lecture 是讲次序号。

用法(在仓库根目录):
  python3 eval/course/questions.py check [--course 1] [--file eval/course/questions.jsonl]
      只校验格式;带 --course 时还对着课程环境核对讲次序号和时间有没有超出这一讲的长度。
      有 questions.lock 时一并核对题集有没有在冻结之后被改过(改过则退出码 1)。
  python3 eval/course/questions.py freeze [--course 1]
      校验通过后写 questions.lock(题数、各类题数、文件的 SHA-256)。题集在看任何系统的结果之前冻结。
  python3 eval/course/questions.py --self-check
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
KINDS = ("single", "cross", "absent")


def clock_ms(s):
    """"分:秒" 或 "时:分:秒" → 毫秒;分钟可以超过 59。写得不对抛 ValueError。"""
    parts = str(s).strip().split(":")
    if len(parts) not in (2, 3) or not all(p.isdigit() for p in parts):
        raise ValueError(f"时间要写成 分:秒,收到的是 {s!r}")
    nums = [int(p) for p in parts]
    secs = nums[0] * 60 + nums[1] if len(nums) == 2 else nums[0] * 3600 + nums[1] * 60 + nums[2]
    return secs * 1000


def load(path):
    """返回 (题目列表, 文件内容的 SHA-256)。空行跳过;某一行不是 JSON 时指出是第几行。"""
    raw = open(path, "rb").read()
    questions = []
    for n, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            questions.append(json.loads(line))
        except json.JSONDecodeError as e:
            raise ValueError(f"第 {n} 行不是 JSON:{e}") from None
    return questions, hashlib.sha256(raw).hexdigest()


def validate(questions, lectures=None):
    """返回问题清单(空 = 通过)。lectures:{讲次序号: 时长毫秒},给了就核对 gold 的讲次和时间。"""
    errors, seen = [], set()
    for i, q in enumerate(questions, 1):
        who = f"第 {i} 题({q.get('id', '没有 id')})" if isinstance(q, dict) else f"第 {i} 题"
        if not isinstance(q, dict):
            errors.append(f"{who}:不是一个对象")
            continue
        qid = q.get("id")
        if not isinstance(qid, str) or not qid.strip():
            errors.append(f"{who}:缺 id")
        elif qid in seen:
            errors.append(f"{who}:id 重复")
        seen.add(qid)
        if not isinstance(q.get("question"), str) or not q["question"].strip():
            errors.append(f"{who}:缺 question")
        kind = q.get("kind")
        if kind not in KINDS:
            errors.append(f"{who}:kind 只能是 {' / '.join(KINDS)},收到的是 {kind!r}")
            continue
        points, gold = q.get("points", []), q.get("gold", [])
        if not isinstance(points, list) or not all(isinstance(p, str) and p.strip() for p in points):
            errors.append(f"{who}:points 要是一组非空的文字")
            points = []
        if not isinstance(gold, list):
            errors.append(f"{who}:gold 要是一个列表")
            gold = []
        if kind == "absent":
            if points or gold:
                errors.append(f"{who}:课里没讲的题不能有 points 和 gold")
            continue
        if not points:
            errors.append(f"{who}:缺 points(要点式的标准答案)")
        if not gold:
            errors.append(f"{who}:缺 gold(答案所在的讲次和时间)")
        lectures_cited = set()
        for g in gold:
            if not isinstance(g, dict) or not isinstance(g.get("lecture"), int) or isinstance(g.get("lecture"), bool):
                errors.append(f"{who}:gold 里每一项要有 lecture(讲次序号,整数)")
                continue
            lectures_cited.add(g["lecture"])
            try:
                start, end = clock_ms(g.get("from")), clock_ms(g.get("to"))
            except ValueError as e:
                errors.append(f"{who}:{e}")
                continue
            if start >= end:
                errors.append(f"{who}:第 {g['lecture']} 讲的区间 from 不早于 to")
            if lectures is not None:
                if g["lecture"] not in lectures:
                    errors.append(f"{who}:这门课没有第 {g['lecture']} 讲")
                elif end > lectures[g["lecture"]]:
                    errors.append(f"{who}:第 {g['lecture']} 讲的区间超出了这一讲的长度")
        if kind == "cross" and len(lectures_cited) < 2:
            errors.append(f"{who}:跨讲次的题,gold 至少要落在两讲里")
        if kind == "single" and len(lectures_cited) > 1:
            errors.append(f"{who}:单讲的题,gold 只能在一讲里")
    return errors


def summary(questions, sha):
    by_kind = {k: sum(1 for q in questions if q.get("kind") == k) for k in KINDS}
    return {"count": len(questions), "byKind": by_kind, "sha256": sha}


def course_lectures(api, course_id):
    with urllib.request.urlopen(f"{api}/courses/{course_id}", timeout=30) as r:
        course = json.load(r)
    return {l["seq"]: l["durationMs"] or 0 for l in course["lectures"] if l["status"] in ("READY", "PARTIAL")}


def self_check():
    assert clock_ms("12:30") == 750000 and clock_ms("1:02:30") == 3750000 and clock_ms("90:00") == 5400000
    for bad in ("12", "ab:cd", "1:2:3:4", "", None, "-1:00"):
        try:
            clock_ms(bad)
            raise AssertionError(f"{bad!r} 应该被拒绝")
        except ValueError:
            pass
    ok = [{"id": "s1", "kind": "single", "question": "q", "points": ["p"], "gold": [{"lecture": 3, "from": "12:30", "to": "13:00"}]},
          {"id": "x1", "kind": "cross", "question": "q", "points": ["p"],
           "gold": [{"lecture": 3, "from": "1:00", "to": "2:00"}, {"lecture": 5, "from": "1:00", "to": "2:00"}]},
          {"id": "a1", "kind": "absent", "question": "q", "points": [], "gold": []}]
    lectures = {3: 4648000, 5: 3840000}
    assert validate(ok) == [] and validate(ok, lectures) == []

    def errs(change, lectures=None, base=0):
        q = json.loads(json.dumps(ok))
        change(q[base])
        return " | ".join(validate(q, lectures))

    assert "缺 question" in errs(lambda q: q.pop("question"))
    assert "kind 只能是" in errs(lambda q: q.update(kind="hard"))
    assert "缺 points" in errs(lambda q: q.update(points=[]))
    assert "缺 gold" in errs(lambda q: q.update(gold=[]))
    assert "from 不早于 to" in errs(lambda q: q["gold"][0].update({"from": "13:00", "to": "12:30"}))
    assert "时间要写成" in errs(lambda q: q["gold"][0].update({"from": "noon"}))
    assert "没有第 9 讲" in errs(lambda q: q["gold"][0].update(lecture=9), lectures)
    assert "超出了这一讲的长度" in errs(lambda q: q["gold"][0].update(to="99:00"), lectures)
    assert "不能有 points 和 gold" in errs(lambda q: q.update(points=["p"]), base=2)
    assert "至少要落在两讲里" in errs(lambda q: q["gold"].pop(), base=1)
    assert "只能在一讲里" in errs(lambda q: q["gold"].append({"lecture": 5, "from": "1:00", "to": "2:00"}))
    assert "id 重复" in errs(lambda q: q.update(id="x1"))
    assert "lecture(讲次序号,整数)" in errs(lambda q: q["gold"][0].update(lecture="3"))
    assert validate(["not an object"]) == ["第 1 题:不是一个对象"]

    import tempfile
    with tempfile.TemporaryDirectory() as d:
        path, lock = os.path.join(d, "q.jsonl"), os.path.join(d, "q.lock")
        open(path, "w", encoding="utf-8").write("\n".join(json.dumps(q, ensure_ascii=False) for q in ok) + "\n")
        assert main(["check", "--file", path, "--lock", lock]) == 0            # 还没冻结
        assert main(["freeze", "--file", path, "--lock", lock]) == 0
        frozen = json.load(open(lock))
        assert frozen["count"] == 3 and frozen["byKind"] == {"single": 1, "cross": 1, "absent": 1}
        assert main(["check", "--file", path, "--lock", lock]) == 0
        open(path, "a", encoding="utf-8").write("\n")                           # 冻结之后改了一个字
        assert load(path)[1] != frozen["sha256"]
        assert main(["check", "--file", path, "--lock", lock]) == 1
        open(path, "w", encoding="utf-8").write('{"id":"s1"}\nnot json\n')
        assert main(["check", "--file", path, "--lock", lock]) == 1            # 第 2 行不是 JSON
        assert main(["freeze", "--file", path, "--lock", lock]) == 1 and json.load(open(lock)) == frozen
    print("self-check ok")


def main(argv=None):
    p = argparse.ArgumentParser(description="课程问答评测题集的校验和冻结")
    p.add_argument("command", nargs="?", choices=("check", "freeze"))
    p.add_argument("--file", default=os.path.join(HERE, "questions.jsonl"))
    p.add_argument("--lock", default=None, help="默认是题集旁边的 questions.lock")
    p.add_argument("--course", type=int, help="课程 id:对着课程环境核对讲次和时间")
    p.add_argument("--api", default="http://127.0.0.1:18090/api/v1")
    p.add_argument("--self-check", action="store_true")
    args = p.parse_args(argv)
    if args.self_check:
        self_check()
        return 0
    if not args.command:
        p.error("要给 check 或 freeze")
    lock = args.lock or os.path.join(os.path.dirname(args.file), "questions.lock")
    try:
        questions, sha = load(args.file)
    except (OSError, ValueError) as e:
        print(f"读不了题集:{e}", file=sys.stderr)
        return 1
    errors = validate(questions, course_lectures(args.api, args.course) if args.course else None)
    for e in errors:
        print(e, file=sys.stderr)
    info = summary(questions, sha)
    print(f"{info['count']} 题:单讲 {info['byKind']['single']}、跨讲次 {info['byKind']['cross']}、课里没讲 {info['byKind']['absent']}")
    if errors:
        print(f"{len(errors)} 处问题", file=sys.stderr)
        return 1
    if args.command == "freeze":
        with open(lock, "w", encoding="utf-8") as f:
            json.dump(info, f, ensure_ascii=False, indent=2)
        print(f"已冻结:{lock}  sha256={sha[:12]}…")
        return 0
    if os.path.exists(lock) and json.load(open(lock))["sha256"] != sha:
        print("题集在冻结之后被改过(和 questions.lock 对不上)。改题要重新 freeze,而且之前的运行结果不能再用。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
