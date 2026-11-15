#!/usr/bin/env python3
"""探索性分析(不在事先写下的指标里):不管核验结果,把模型给出的全部结论(通过的 + 每一轮被拿掉的)
交给同一个打分模型,看"不要求证据时答对了多少"。只做长上下文基线(B1)跑过的那些题里有要点的题,几个系统都做。
结果写进运行目录的 grades.raw.jsonl,tables.py 读它。

  python3 eval/course/exploratory/raw_grade.py eval/course/runs/<名字> --model claude-haiku-5-5 --env-file .env
"""
import argparse
import json
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))          # eval/course
sys.path.insert(0, os.path.join(HERE, "..", ".."))    # eval
import score, grade, llm  # noqa: E402

p = argparse.ArgumentParser()
p.add_argument("run_dir")
p.add_argument("--model", required=True)
p.add_argument("--env-file", default=os.path.join(HERE, "..", "..", "..", ".env"))
args = p.parse_args()
RUN = args.run_dir
os.environ.update(llm.read_env_file(args.env_file))
judge = llm.Client(args.model)
ask = lambda s, u: judge.ask(s, u, max_tokens=1000)
meta, questions, rows = score.load_run(RUN)
b1 = {r['qid'] for r in rows if r['system'] == 'B1'}
out_path = RUN + '/grades.raw.jsonl'
done = {(g['qid'], g['system'], g['round']) for g in score.read_jsonl(out_path)} if os.path.exists(out_path) else set()
todo = [r for r in rows if r['qid'] in b1 and questions[r['qid']]['kind'] != 'absent' and (r['qid'], r['system'], r['round']) not in done]
print(len(todo), 'to grade', flush=True)
with open(out_path, 'a', encoding='utf-8') as out:
    for r in todo:
        a = r.get('answer') or {}
        # 通过核验的结论,加上每一轮被拿掉的(补一轮之前被拿掉的也算;同一句话只算一次)
        allc, seen = list(a.get('claims') or []), {c.get('text') for c in a.get('claims') or []}
        for d in [d for v in a.get('verifications') or [] for d in v.get('dropped') or []] + list(a.get('dropped') or []):
            if d.get('text') not in seen:
                seen.add(d.get('text'))
                allc.append({'text': d.get('text', ''), 'evidence': d.get('evidence') or []})
        fake = {'status': r['status'], 'answer': {**a, 'claims': allc}}
        try:
            g = grade.grade_row(questions[r['qid']], fake, ask)
        except Exception as e:
            print('skip', r['qid'], r['system'], r['round'], type(e).__name__, flush=True); continue
        out.write(json.dumps({'qid': r['qid'], 'system': r['system'], 'round': r['round'], 'score': g['score'], 'hits': g['hits'],
                              'claims': len(allc), 'kept': len(a.get('claims') or []), 'reply': g.get('reply')}, ensure_ascii=False) + '\n'); out.flush()
print('done', flush=True)
