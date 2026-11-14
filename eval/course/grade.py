#!/usr/bin/env python3
"""课程问答评测的答对率打分(标准库)。打分模型对照题目的要点,逐条判这个系统的答案有没有说到。

  python3 eval/course/grade.py eval/course/runs/<名字> [--model claude-sonnet-5-5] [--env-file .env]
  python3 eval/course/grade.py --self-check

- 被打分的"答案"是通过核验的结论连起来的文字:没通过核验的结论不算数,这正是要比较的东西。
- 课里没讲的题不调模型:说了找不到(或者没有一条通过核验的结论)得 1 分,否则 0 分。
- 没有通过核验的结论的题不调模型,0 分。
- 每题的分数 = 说到的要点数 / 要点总数。打分的原始输出、用的模型、被打分的文字都存进 grades.jsonl,人工抽查用。
- 中断后再跑会接着打,已经有分的不重打。打完之后 score.py 的报告里就会多出"答对率"两列。

打分模型的地址和 key 和 eval/run_eval.py 一样,从 .env 的 OPENAI_BASE_URL / OPENAI_API_KEY 读(按字面读,不要 source)。
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))

SYSTEM = ("You grade one answer to a question about a lecture course against a list of required points. "
          "For each point decide whether the answer states it: 1 if the answer says the same thing in any wording "
          "or language, 0 if it is missing, only hinted at, or contradicted. Judge only what the answer says; "
          "do not use your own knowledge to fill gaps, and ignore anything in the answer that is not asked for. "
          'Reply with JSON only: {"hits":[1,0,...]} with one number per point, in the same order.')


def answer_text(answer):
    return "\n".join(c.get("text", "") for c in (answer or {}).get("claims") or [])


def user_prompt(question, text):
    points = "\n".join(f"{i}. {p}" for i, p in enumerate(question["points"], 1))
    return f"Question: {question['question']}\n\nRequired points:\n{points}\n\nAnswer:\n{text}"


def parse_hits(reply, n):
    """打分模型的输出 → 长度为 n 的 0/1 列表。少给的算 0,多给的丢掉,不是 0/1 的算 0。"""
    raw = reply.get("hits") if isinstance(reply, dict) else None
    hits = [1 if x in (1, True, "1") else 0 for x in raw][:n] if isinstance(raw, list) else []
    return hits + [0] * (n - len(hits))


def grade_row(question, row, ask):
    """返回 {"score", "hits", "graded"(被打分的文字), "by"("rule" 或 "model")}。ask(system, user) → 解析好的 JSON。"""
    answer = row.get("answer") or {}
    kept = answer.get("claims") or []
    if row.get("status") != "COMPLETED":
        return {"score": 0.0, "hits": [], "graded": "", "by": "rule"}       # 没跑成:这一题算没答对
    if question["kind"] == "absent":
        right = bool(answer.get("notFound")) or not kept
        return {"score": 1.0 if right else 0.0, "hits": [], "graded": answer_text(answer), "by": "rule"}
    text = answer_text(answer)
    if not text.strip():
        return {"score": 0.0, "hits": [0] * len(question["points"]), "graded": "", "by": "rule"}
    hits = parse_hits(ask(SYSTEM, user_prompt(question, text)), len(question["points"]))
    return {"score": sum(hits) / len(hits), "hits": hits, "graded": text, "by": "model"}


def grade_dir(run_dir, ask, model, log=print):
    meta = json.load(open(os.path.join(run_dir, "meta.json")))
    questions = {q["id"]: q for q in (json.loads(l) for l in open(meta["questions"], encoding="utf-8") if l.strip())}
    rows = {}
    for line in open(os.path.join(run_dir, "results.jsonl"), encoding="utf-8"):
        if line.strip():
            r = json.loads(line)
            rows[(r["qid"], r["system"], r["round"])] = r       # 重跑过的取最后一行
    path = os.path.join(run_dir, "grades.jsonl")
    done = set()
    if os.path.exists(path):
        done = {(g["qid"], g["system"], g["round"]) for g in (json.loads(l) for l in open(path, encoding="utf-8") if l.strip())}
    todo = [k for k in rows if k not in done]
    log(f"{len(todo)} 条要打分,{len(done)} 条已经有分")
    with open(path, "a", encoding="utf-8") as out:
        for key in todo:
            g = grade_row(questions[key[0]], rows[key], ask)
            out.write(json.dumps({"qid": key[0], "system": key[1], "round": key[2], "model": model if g["by"] == "model" else None,
                                  **g}, ensure_ascii=False) + "\n")
            out.flush()
    return len(todo)


def self_check():
    import tempfile
    q = {"id": "s1", "kind": "single", "question": "what is rlhf", "points": ["uses human preferences", "trains a reward model"], "gold": []}
    absent = {"id": "a1", "kind": "absent", "question": "k8s?", "points": [], "gold": []}
    calls = []

    def ask(system, user):
        calls.append(user)
        return {"hits": [1, 0]}

    def row(claims, status="COMPLETED", not_found=False):
        return {"status": status, "answer": {"notFound": not_found, "claims": [{"text": t} for t in claims]}}

    g = grade_row(q, row(["RLHF collects human preferences.", "Second claim."]), ask)
    assert g["score"] == 0.5 and g["hits"] == [1, 0] and g["by"] == "model"
    assert "1. uses human preferences\n2. trains a reward model" in calls[0]
    assert calls[0].endswith("Answer:\nRLHF collects human preferences.\nSecond claim.") and "what is rlhf" in calls[0]
    n = len(calls)
    assert grade_row(q, row([]), ask) == {"score": 0.0, "hits": [0, 0], "graded": "", "by": "rule"}      # 没有通过核验的结论
    assert grade_row(q, row(["x"], status="FAILED"), ask)["score"] == 0.0
    assert grade_row(absent, row([], not_found=True), ask)["score"] == 1.0
    assert grade_row(absent, row([]), ask)["score"] == 1.0                    # 没说找不到,但也没给出通过核验的结论
    assert grade_row(absent, row(["Kubernetes is covered in lecture 2."]), ask)["score"] == 0.0
    assert len(calls) == n, "这几种情况都不该调模型"
    assert parse_hits({"hits": [1, "1", True, 2, "yes"]}, 5) == [1, 1, 1, 0, 0]
    assert parse_hits({"hits": [1]}, 3) == [1, 0, 0] and parse_hits({"hits": [1, 1, 1]}, 2) == [1, 1]
    assert parse_hits({"scores": [1]}, 2) == [0, 0] and parse_hits("nonsense", 1) == [0]

    with tempfile.TemporaryDirectory() as d:
        qpath = os.path.join(d, "q.jsonl")
        open(qpath, "w", encoding="utf-8").write(json.dumps(q) + "\n" + json.dumps(absent) + "\n")
        json.dump({"questions": qpath}, open(os.path.join(d, "meta.json"), "w"))
        with open(os.path.join(d, "results.jsonl"), "w") as f:
            for qid, system, rnd, r in [("s1", "S1", 1, row(["x"], status="FAILED")), ("s1", "S1", 1, row(["RLHF…"])),
                                        ("a1", "S1", 1, row([], not_found=True)), ("s1", "S2", 1, row([]))]:
                f.write(json.dumps({"qid": qid, "system": system, "round": rnd, **r}) + "\n")
        quiet = lambda *_: None  # noqa: E731
        calls.clear()
        assert grade_dir(d, ask, "grader-1", quiet) == 3 and len(calls) == 1
        grades = {(g["qid"], g["system"]): g for g in (json.loads(l) for l in open(os.path.join(d, "grades.jsonl")))}
        assert grades[("s1", "S1")]["score"] == 0.5 and grades[("s1", "S1")]["model"] == "grader-1"   # 取重跑后的那一行
        assert grades[("a1", "S1")]["score"] == 1.0 and grades[("a1", "S1")]["model"] is None
        assert grade_dir(d, ask, "grader-1", quiet) == 0 and len(calls) == 1                           # 接着打:都有了
        import score
        sha = __import__("hashlib").sha256(open(qpath, "rb").read()).hexdigest()
        json.dump({"questions": qpath, "questionsSha": sha, "name": "t", "course": 1, "model": "mock"}, open(os.path.join(d, "meta.json"), "w"))
        lines = [json.loads(l) for l in open(os.path.join(d, "results.jsonl"))]
        with open(os.path.join(d, "results.jsonl"), "w") as f:
            for r in lines:
                f.write(json.dumps({**r, "questionsSha": sha, "seconds": 1.0}) + "\n")
        assert score.score_dir(d)["systems"]["S1"]["correct"] == 0.75        # score.py 读得懂这里写的 grades.jsonl
    print("self-check ok")


def main():
    p = argparse.ArgumentParser(description="课程问答评测的答对率打分")
    p.add_argument("run_dir", nargs="?")
    p.add_argument("--model", default=os.environ.get("JUDGE_MODEL", "claude-sonnet-5-5"))
    p.add_argument("--env-file", default=os.path.join(HERE, "..", "..", ".env"))
    p.add_argument("--self-check", action="store_true")
    args = p.parse_args()
    if args.self_check:
        self_check()
        return 0
    if not args.run_dir:
        p.error("要给运行目录")
    import run_eval   # eval/run_eval.py:评审模型的调用(重试、厂商参数)已经在那里写好了
    if os.path.exists(args.env_file):
        os.environ.update(run_eval.read_env_file(args.env_file))
    judge = run_eval.Judge(args.model)
    n = grade_dir(args.run_dir, lambda system, user: judge.ask(system, user, max_tokens=300), args.model)
    print(f"打了 {n} 条;重新出报告:python3 eval/course/score.py {args.run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
