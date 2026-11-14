#!/usr/bin/env python3
"""课程问答评测的答对率打分(标准库)。打分模型对照题目的要点,逐条判这个系统的答案有没有说到。

  python3 eval/course/grade.py eval/course/runs/<名字> [--model claude-sonnet-5-5] [--env-file .env]
  python3 eval/course/grade.py --self-check

- 被打分的"答案"是通过核验的结论连起来的文字:没通过核验的结论不算数,这正是要比较的东西。
  每条结论前面标着它的证据所在的讲次([L3]):跨讲次题的要点常常是"第几讲怎么说",打分模型要能看出结论出自哪一讲。
- 课里没讲的题不调模型:说了找不到(或者没有一条通过核验的结论)得 1 分,否则 0 分。
- 没有通过核验的结论的题不调模型,0 分。
- 每题的分数 = 说到的要点数 / 要点总数。打分模型的原始输出、用的模型、被打分的文字都存进 grades.jsonl,人工抽查用。
- 中断后再跑会接着打,已经有分的不重打。分数是跟着那一次运行(taskUuid)记的:某一题用 --retry-failed 重跑过,
  旧的分数就作废,再跑一次 grade.py 会给新的运行打分。打完之后 score.py 的报告里就会多出"答对率"两列。

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
          "Each line of the answer is one claim; a tag such as [L3] in front of it says which lecture it was taken from. "
          "For each point decide whether the answer states it: 1 if the answer says the same thing in any wording "
          "or language, 0 if it is missing, only hinted at, or contradicted. Judge only what the answer says; "
          "do not use your own knowledge to fill gaps, and ignore anything in the answer that is not asked for. "
          'Reply with JSON only: {"hits":[1,0,...]} with exactly one number per required point '
          "(not one per claim of the answer), in the order of the points.")


def answer_text(answer):
    """通过核验的结论,一行一条,前面标着它的证据所在的讲次。"""
    lines = []
    for c in (answer or {}).get("claims") or []:
        seqs = sorted({e.get("seq") for e in c.get("evidence") or [] if e.get("seq") is not None})
        lines.append(("[" + ", ".join(f"L{s}" for s in seqs) + "] " if seqs else "") + c.get("text", ""))
    return "\n".join(lines)


def user_prompt(question, text):
    points = "\n".join(f"{i}. {p}" for i, p in enumerate(question["points"], 1))
    n = len(question["points"])
    return (f"Question: {question['question']}\n\nRequired points:\n{points}\n\nAnswer:\n{text}\n\n"
            f"There are {n} required points: return exactly {n} numbers, one per required point, not one per line of the answer.")


def parse_hits(reply, n):
    """打分模型的输出 → 长度为 n 的 0/1 列表;不是 0/1 的算 0。个数不是 n 就抛 ValueError:
    第一轮全量打分里有一成的回复是"每条结论一个数",截掉多出来的等于给前几条结论打分,不是给要点打分。"""
    raw = reply.get("hits") if isinstance(reply, dict) else None
    if not isinstance(raw, list) or len(raw) != n:
        raise ValueError(f"要 {n} 个数,收到的是 {str(raw)[:80]}")
    return [1 if x in (1, True, "1") else 0 for x in raw]


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
    for attempt in range(3):          # 个数不对就再问;三次都不对,抛给 grade_dir,这一题先空着
        reply = ask(SYSTEM, user_prompt(question, text))
        try:
            hits = parse_hits(reply, len(question["points"]))
            break
        except ValueError:
            if attempt == 2:
                raise
    return {"score": sum(hits) / len(hits), "hits": hits, "graded": text, "by": "model", "reply": reply}


def grade_dir(run_dir, ask, model, log=print):
    import score
    _, questions, result_rows = score.load_run(run_dir)       # 重跑过的取最后一行;题集改过则拒绝
    rows = {(r["qid"], r["system"], r["round"]): r for r in result_rows}
    path = os.path.join(run_dir, "grades.jsonl")
    done = set()
    if os.path.exists(path):
        done = {(g["qid"], g["system"], g["round"], g.get("taskUuid")) for g in score.read_jsonl(path)}
    todo = [k for k in rows if (*k, rows[k].get("taskUuid")) not in done]   # 分数跟着那一次运行:重跑过的要重新打
    log(f"{len(todo)} 条要打分,{len(done)} 条已经有分")
    failed = 0
    with open(path, "a", encoding="utf-8") as out:
        for key in todo:
            try:
                g = grade_row(questions[key[0]], rows[key], ask)
            except Exception as e:   # 打分模型没按格式回,或者接口出错:这一条先空着,别的照打
                failed += 1
                log(f"{key} 没打成:{type(e).__name__}: {str(e)[:120]}")
                continue
            out.write(json.dumps({"qid": key[0], "system": key[1], "round": key[2], "taskUuid": rows[key].get("taskUuid"),
                                  "model": model if g["by"] == "model" else None, **g}, ensure_ascii=False) + "\n")
            out.flush()
    if failed:
        log(f"{failed} 条没打成;再跑一次只重打这几条")
    return len(todo) - failed


def self_check():
    import tempfile
    q = {"id": "s1", "kind": "single", "question": "what is rlhf", "points": ["uses human preferences", "trains a reward model"], "gold": []}
    absent = {"id": "a1", "kind": "absent", "question": "k8s?", "points": [], "gold": []}
    calls = []

    def ask(system, user):
        calls.append(user)
        return {"hits": [1, 0]}

    def row(claims, status="COMPLETED", not_found=False, uuid="u1"):
        return {"status": status, "taskUuid": uuid, "answer": {"status": status, "notFound": not_found, "claims": [
            {"text": t, "evidence": [{"seq": 3}, {"seq": 1}, {"seq": 3}] if i == 0 else []} for i, t in enumerate(claims)]}}

    g = grade_row(q, row(["RLHF collects human preferences.", "Second claim."]), ask)
    assert g["score"] == 0.5 and g["hits"] == [1, 0] and g["by"] == "model" and g["reply"] == {"hits": [1, 0]}
    assert "1. uses human preferences\n2. trains a reward model" in calls[0]
    # 每条结论前面标着证据所在的讲次;没有证据信息的不标
    assert "Answer:\n[L1, L3] RLHF collects human preferences.\nSecond claim.\n" in calls[0] and "what is rlhf" in calls[0]
    assert "exactly 2 numbers" in calls[0]                                  # 说明要几个数:不然它常常按结论的条数回
    n = len(calls)
    assert grade_row(q, row([]), ask) == {"score": 0.0, "hits": [0, 0], "graded": "", "by": "rule"}      # 没有通过核验的结论
    assert grade_row(q, row(["x"], status="FAILED"), ask)["score"] == 0.0
    assert grade_row(absent, row([], not_found=True), ask)["score"] == 1.0
    assert grade_row(absent, row([]), ask)["score"] == 1.0                    # 没说找不到,但也没给出通过核验的结论
    assert grade_row(absent, row(["Kubernetes is covered in lecture 2."]), ask)["score"] == 0.0
    assert len(calls) == n, "这几种情况都不该调模型"
    assert parse_hits({"hits": [1, "1", True, 2, "yes"]}, 5) == [1, 1, 1, 0, 0]
    for bad, n in (({"hits": [1]}, 3), ({"hits": [1, 1, 1]}, 2), ({"scores": [1]}, 2), ("nonsense", 1)):
        try:
            parse_hits(bad, n)
            raise AssertionError("个数不对的回复不能当成分数")
        except ValueError:
            pass
    # 打分模型按结论的条数回了(4 个数,要点只有 2 个):再问一次;三次都不对,这一题不打分
    replies = iter([{"hits": [1, 1, 0, 1]}, {"hits": [0, 1]}])
    g = grade_row(q, row(["a", "b", "c", "d"]), lambda system, user: next(replies))
    assert g["hits"] == [0, 1] and g["score"] == 0.5 and g["reply"] == {"hits": [0, 1]}
    asked = []
    try:
        grade_row(q, row(["a"]), lambda system, user: asked.append(1) or {"hits": [1, 1, 1]})
        raise AssertionError("三次都按结论回,不能给分")
    except ValueError:
        assert len(asked) == 3

    with tempfile.TemporaryDirectory() as d:
        import hashlib
        import score
        raw = json.dumps(q) + "\n" + json.dumps(absent) + "\n"
        open(os.path.join(d, "questions.jsonl"), "w", encoding="utf-8").write(raw)
        sha = hashlib.sha256(raw.encode()).hexdigest()
        json.dump({"questions": "questions.jsonl", "questionsSha": sha, "name": "t", "course": 1, "model": "m", "systems": {}},
                  open(os.path.join(d, "meta.json"), "w"))

        def write(rows):
            with open(os.path.join(d, "results.jsonl"), "a") as f:
                for qid, system, rnd, r in rows:
                    f.write(json.dumps({"qid": qid, "system": system, "round": rnd, "questionsSha": sha, "seconds": 1.0, **r}) + "\n")

        write([("s1", "S1", 1, row(["x"], status="FAILED", uuid="first")), ("a1", "S1", 1, row([], not_found=True)), ("s1", "S2", 1, row([]))])
        quiet = lambda *_: None  # noqa: E731
        calls.clear()
        assert grade_dir(d, ask, "grader-1", quiet) == 3 and len(calls) == 0                           # 失败的那次按规则给 0 分
        assert score.score_dir(d)["systems"]["S1"]["correct"] == 0.5                                   # (0 + 1) / 2
        write([("s1", "S1", 1, row(["RLHF…"], uuid="second"))])                                        # --retry-failed 重跑成功了
        assert score.score_dir(d)["systems"]["S1"]["correct"] == 1.0                                   # 旧的 0 分不再算在这次运行头上
        assert grade_dir(d, ask, "grader-1", quiet) == 1 and len(calls) == 1                           # 只给重跑的那一次打分
        grades = [json.loads(l) for l in open(os.path.join(d, "grades.jsonl"))]
        assert grades[-1]["taskUuid"] == "second" and grades[-1]["score"] == 0.5 and grades[-1]["model"] == "grader-1"
        assert grades[1]["score"] == 1.0 and grades[1]["model"] is None                                # 课里没讲的题:规则给分
        assert grade_dir(d, ask, "grader-1", quiet) == 0 and len(calls) == 1                           # 接着打:都有了
        assert score.score_dir(d)["systems"]["S1"]["correct"] == 0.75        # score.py 读得懂这里写的 grades.jsonl
        # 打分模型有一条没按格式回:这一条先空着,别的照打;下次再跑只重打这一条
        write([("s1", "S3", 1, row(["a"], uuid="u3")), ("s1", "S3", 2, row(["b"], uuid="u4"))])
        flaky = iter([ValueError("no JSON object"), {"hits": [1, 1]}, {"hits": [1, 0]}])

        def sometimes(system, user):
            reply = next(flaky)
            if isinstance(reply, Exception):
                raise reply
            return reply

        assert grade_dir(d, sometimes, "grader-1", quiet) == 1
        assert grade_dir(d, sometimes, "grader-1", quiet) == 1
        assert sorted(g["score"] for g in score.read_jsonl(os.path.join(d, "grades.jsonl")) if g["system"] == "S3") == [0.5, 1.0]
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
    # 打分模型偶尔先逐条说理由再给 JSON,300 个 token 不够它说完
    n = grade_dir(args.run_dir, lambda system, user: judge.ask(system, user, max_tokens=1000), args.model)
    print(f"打了 {n} 条;重新出报告:python3 eval/course/score.py {args.run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
