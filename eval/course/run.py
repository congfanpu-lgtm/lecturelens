#!/usr/bin/env python3
"""课程问答评测的运行器(零依赖,标准库):把题集里的每道题按每个系统、每一轮提一次,存下原始结果。

  python3 eval/course/run.py --name pilot --course 1 --systems S1,S2,S3 --rounds 3
      [--questions eval/course/questions.jsonl] [--limit N] [--concurrency 2] [--model 名字]
      [--api http://127.0.0.1:18090/api/v1] [--timeout 900] [--retry-failed]
      [--real --price-in 0.10 --price-out 0.50]   真实模型的运行:不带 --real 的话报告里会标成"模拟模型";单价是美元 / 百万 token
      [--unfrozen]                                 题集还没冻结也跑(只给示例题和试链路用)
  python3 eval/course/run.py --self-check

被比较的系统都是同一个 COURSE_QA 任务,只是 payload 里的 eval 选项不同:
  S1  完整系统:agent + 三层核验(默认)
  S2  固定检索流水线:检索一次 → 一次模型调用作答 → 核验          eval.mode = rag
  S3  agent,但核验不调判官(只有前两层)                           eval.judge = false
  B1  整门课的转写放进一次模型调用(长上下文基线),核验只做前两层   eval.mode = long, eval.judge = false

题集必须先冻结(questions.py freeze):运行开始时把题集抄进运行目录,之后打分只认这一份。
结果逐行追加到 eval/course/runs/<name>/results.jsonl(一行是一题一系统一轮),中断后再跑同一条命令会接着跑:
已经有结果的不重跑。失败和超时的也记一行(status 说明原因),默认不重跑;--retry-failed 时重跑,打分取最后一行。
"""
import argparse
import datetime
import json
import os
import shutil
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import questions as qs  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# 引擎默认不调判官(评测之后改的,见 docs/benchmarks.md 第 13 节),所以带判官的系统要显式写 judge: true。
# 2026-11-14 的三次运行(haiku-v1 到 v3)是在默认带判官的时候跑的,那时 S1 是 {}、S2 是 {"mode": "rag"}。
SYSTEMS = {
    "S1": {"judge": True},
    "S2": {"mode": "rag", "judge": True},
    "S3": {"judge": False},
    "B1": {"mode": "long", "judge": False},
}


def http(method, url, body=None, timeout=30):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def payload(course, question, system, model=None):
    p = {"courseId": course, "question": question["question"]}
    if SYSTEMS[system]:
        p["eval"] = dict(SYSTEMS[system])
    if model:
        p["model"] = model
    return p


def ask(api, body, timeout, submit=None, fetch=None, sleep=time.sleep):
    """提交一个 COURSE_QA 任务并等到落定。返回 (status, taskUuid, answer)。任何网络错误都记成这一题的结果,不让线程死掉。"""
    submit = submit or (lambda b: http("POST", f"{api}/tasks", {"type": "COURSE_QA", "payload": b})["taskUuid"])
    fetch = fetch or (lambda uuid: http("GET", f"{api}/questions/{uuid}"))
    try:
        uuid = submit(body)
    except Exception as e:
        return f"REJECTED: {type(e).__name__}: {str(e)[:120]}", None, None
    deadline = time.time() + timeout
    answer = None
    while time.time() < deadline:
        try:
            answer = fetch(uuid)
            if answer.get("status") != "RUNNING":
                return answer["status"], uuid, answer
        except Exception:
            pass   # 取答案偶尔失败(server 重启):接着等
        sleep(1)
    return "TIMEOUT", uuid, answer


def done_keys(path, retry_failed):
    done = set()
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            if line.strip():
                try:
                    r = json.loads(line)
                except json.JSONDecodeError:
                    continue      # 上次被杀时写到一半的一行:当它没有,这一题会重跑
                if r["status"] == "COMPLETED" or not retry_failed:
                    done.add((r["qid"], r["system"], r["round"]))
                elif retry_failed:
                    done.discard((r["qid"], r["system"], r["round"]))
    return done


def run(run_dir, questions, sha, course, systems, rounds, concurrency=2, model=None, api=None, timeout=900,
        retry_failed=False, asker=ask, log=print):
    os.makedirs(run_dir, exist_ok=True)
    results = os.path.join(run_dir, "results.jsonl")
    done = done_keys(results, retry_failed)
    todo = [(q, s, n) for n in range(1, rounds + 1) for s in systems for q in questions
            if (q["id"], s, n) not in done]
    log(f"{len(todo)} 次要跑,{len(done)} 次已经有结果")
    lock = threading.Lock()
    out = open(results, "a", encoding="utf-8")

    def one(item):
        q, s, n = item
        t0 = time.time()
        status, uuid, answer = asker(api, payload(course, q, s, model), timeout)
        row = {"qid": q["id"], "system": s, "round": n, "questionsSha": sha, "taskUuid": uuid, "status": status,
               "seconds": round(time.time() - t0, 1), "answer": answer}
        with lock:   # 写一行落盘一次:中断时最多丢正在跑的那几题
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
            log(f"{s} 第 {n} 轮 {q['id']}: {status} {row['seconds']} 秒")

    with ThreadPoolExecutor(concurrency) as pool:
        list(pool.map(one, todo))
    out.close()
    return len(todo)


def merged_meta(old, new):
    """同一个名字的运行接着跑:开始时间、已经跑过的系统沿用;这次没给的单价沿用开始时的;用真实模型开始的运行一直算真实模型。"""
    out = dict(new)
    out["startedAt"] = old["startedAt"]
    out["systems"] = {**old["systems"], **new["systems"]}
    out["mock"] = old.get("mock", True) and new.get("mock", True)
    for k, v in old.items():
        if out.get(k) is None:
            out[k] = v
    return out


def frozen_problem(path, sha):
    """题集冻结了、而且冻结之后没改过 → None;否则返回一句说明。"""
    lock = os.path.join(os.path.dirname(os.path.abspath(path)), "questions.lock")
    if not os.path.exists(lock):
        return "题集还没有冻结:先 python3 eval/course/questions.py freeze(示例题或试链路用 --unfrozen)。"
    if json.load(open(lock))["sha256"] != sha:
        return "题集在冻结之后被改过(和 questions.lock 对不上)。重新 freeze,而且之前的运行结果不能再用。"
    return None


def self_check():
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "questions.jsonl")
        open(path, "w").write('{"id":"s1"}\n')
        assert "还没有冻结" in frozen_problem(path, "abc")
        json.dump({"sha256": "abc"}, open(os.path.join(d, "questions.lock"), "w"))
        assert frozen_problem(path, "abc") is None and "被改过" in frozen_problem(path, "other")
    # 接着跑时没再给单价、没带 --real:沿用开始时的,不把真实模型的运行改成"模拟模型、没有成本"
    old = {"systems": {"S1": None}, "mock": False, "priceIn": 0.1, "tierTokens": 100000, "startedAt": "t0"}
    new = {"systems": {"B1": {"mode": "long"}}, "mock": True, "priceIn": None, "tierTokens": None, "startedAt": "t1"}
    assert merged_meta(old, new) == {"systems": {"S1": None, "B1": {"mode": "long"}}, "mock": False, "priceIn": 0.1,
                                     "tierTokens": 100000, "startedAt": "t0"}
    assert merged_meta(old, {**new, "priceIn": 0.2})["priceIn"] == 0.2
    questions = [{"id": "s1", "kind": "single", "question": "what is rlhf"}, {"id": "a1", "kind": "absent", "question": "k8s?"}]
    # 引擎默认不调判官(评测之后改的):带判官的系统要显式写,不然 S1、S2 会悄悄变成没有判官
    assert payload(1, questions[0], "S1") == {"courseId": 1, "question": "what is rlhf", "eval": {"judge": True}}
    assert payload(1, questions[0], "S2", "m")["eval"] == {"mode": "rag", "judge": True} and payload(1, questions[0], "S2", "m")["model"] == "m"
    assert payload(1, questions[0], "S3")["eval"] == {"judge": False}
    assert payload(1, questions[0], "B1")["eval"] == {"mode": "long", "judge": False}

    asked = []

    def fake(api, body, timeout):
        asked.append(body)
        if body["question"] == "k8s?" and body.get("eval", {}).get("mode") == "rag":
            return "FAILED", "u-fail", {"status": "FAILED", "error": "QA_AGENT: boom"}
        return "COMPLETED", "u-ok", {"status": "COMPLETED", "claims": [], "dropped": [], "notFound": True}

    with tempfile.TemporaryDirectory() as d:
        quiet = lambda *_: None  # noqa: E731
        assert run(d, questions, "sha-1", 1, ["S1", "S2"], 2, asker=fake, log=quiet) == 8
        rows = [json.loads(line) for line in open(os.path.join(d, "results.jsonl"))]
        assert len(rows) == 8 and all(r["questionsSha"] == "sha-1" for r in rows)
        assert sorted({(r["system"], r["round"]) for r in rows}) == [("S1", 1), ("S1", 2), ("S2", 1), ("S2", 2)]
        failed = [r for r in rows if r["status"] == "FAILED"]
        assert len(failed) == 2 and failed[0]["answer"]["error"] == "QA_AGENT: boom"     # 失败的也记一行
        asked.clear()
        assert run(d, questions, "sha-1", 1, ["S1", "S2"], 2, asker=fake, log=quiet) == 0 and not asked   # 接着跑:都有了
        assert run(d, questions, "sha-1", 1, ["S1", "S2"], 3, asker=fake, log=quiet) == 4               # 多一轮:只跑新的
        assert run(d, questions, "sha-1", 1, ["S1", "S2"], 3, retry_failed=True, asker=fake, log=quiet) == 3
        assert all(b["question"] == "k8s?" and b["eval"] == {"mode": "rag", "judge": True} for b in asked[-3:])
        open(os.path.join(d, "results.jsonl"), "a").write('{"qid": "s1", "system": "S1", "rou')   # 被杀时写到一半的一行
        assert run(d, questions, "sha-1", 1, ["S1"], 3, asker=fake, log=quiet) == 0                  # 接着跑时不会因为它崩掉

    # ask:提交被拒、超时、取答案时出错都变成这一题的状态
    assert ask(None, {}, 5, submit=lambda b: 1 / 0)[0].startswith("REJECTED: ZeroDivisionError")
    states = iter([{"status": "RUNNING"}, RuntimeError("server restarting"), {"status": "COMPLETED", "claims": []}])

    def fetch(uuid):
        s = next(states)
        if isinstance(s, Exception):
            raise s
        return s

    assert ask(None, {}, 5, submit=lambda b: "u1", fetch=fetch, sleep=lambda s: None) == ("COMPLETED", "u1", {"status": "COMPLETED", "claims": []})
    assert ask(None, {}, 0, submit=lambda b: "u1", fetch=fetch)[0] == "TIMEOUT"
    print("self-check ok")


def main():
    p = argparse.ArgumentParser(description="课程问答评测的运行器")
    p.add_argument("--name")
    p.add_argument("--course", type=int)
    p.add_argument("--systems", default="S1,S2,S3")
    p.add_argument("--rounds", type=int, default=3)
    p.add_argument("--questions", default=os.path.join(HERE, "questions.jsonl"))
    p.add_argument("--limit", type=int, help="只跑前 N 道题(试跑用)")
    p.add_argument("--every", type=int, help="每隔 N 道题取一道(按题集里的顺序,贵的系统只跑一部分题时用)")
    p.add_argument("--concurrency", type=int, default=2)
    p.add_argument("--model")
    p.add_argument("--api", default="http://127.0.0.1:18090/api/v1")
    p.add_argument("--timeout", type=int, default=900)
    p.add_argument("--retry-failed", action="store_true")
    p.add_argument("--real", action="store_true", help="这次是真实模型(否则报告里标成模拟模型)")
    p.add_argument("--price-in", type=float, help="输入单价,美元 / 百万 token")
    p.add_argument("--price-out", type=float, help="输出单价,美元 / 百万 token")
    p.add_argument("--tier-tokens", type=int, help="一次调用的输入超过这么多 token 时,这次调用整个按下面两个单价算")
    p.add_argument("--price-in-tier", type=float)
    p.add_argument("--price-out-tier", type=float)
    p.add_argument("--unfrozen", action="store_true", help="题集没冻结也跑(示例题、试链路)")
    p.add_argument("--self-check", action="store_true")
    args = p.parse_args()
    if args.self_check:
        self_check()
        return 0
    if not args.name or not args.course:
        p.error("要给 --name 和 --course")
    systems = [s.strip() for s in args.systems.split(",") if s.strip()]
    unknown = [s for s in systems if s not in SYSTEMS]
    if unknown:
        p.error(f"不认识的系统:{unknown};可用的是 {list(SYSTEMS)}")
    questions, sha = qs.load(args.questions)
    errors = qs.validate(questions)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    problem = frozen_problem(args.questions, sha)
    if problem and not args.unfrozen:
        print(problem, file=sys.stderr)
        return 1
    if args.every:
        questions = questions[::args.every]
    if args.limit:
        questions = questions[:args.limit]
    run_dir = os.path.join(HERE, "runs", args.name)
    meta_path = os.path.join(run_dir, "meta.json")
    meta = {"name": args.name, "course": args.course, "model": args.model, "questions": "questions.jsonl",
            "questionsSha": sha, "systems": {s: SYSTEMS[s] for s in systems}, "mock": not args.real,
            "priceIn": args.price_in, "priceOut": args.price_out, "tierTokens": args.tier_tokens,
            "priceInTier": args.price_in_tier, "priceOutTier": args.price_out_tier,
            "startedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds")}
    if os.path.exists(meta_path):
        old = json.load(open(meta_path))
        if old["questionsSha"] != sha or old.get("model") != args.model or old["course"] != args.course:
            print("这个名字的运行是用另一版题集、另一个模型或另一门课开始的。换一个 --name。", file=sys.stderr)
            return 1
        meta = merged_meta(old, meta)
    os.makedirs(run_dir, exist_ok=True)
    shutil.copyfile(args.questions, os.path.join(run_dir, "questions.jsonl"))   # 打分、校准只认运行目录里的这一份
    json.dump(meta, open(meta_path, "w"), ensure_ascii=False, indent=2)
    run(run_dir, questions, sha, args.course, systems, args.rounds, args.concurrency, args.model, args.api,
        args.timeout, args.retry_failed)
    print(f"结果在 {run_dir};打分:python3 eval/course/score.py {run_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
