#!/usr/bin/env python3
"""报告用的补充表(探索性,不在事先写下的比较里):长上下文基线跑过的 30 道题上四个系统的并排指标;按题型的答对率;不看核验的答对率(探索性)。"""
import statistics
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))          # eval/course
sys.path.insert(0, os.path.join(HERE, "..", ".."))    # eval
import score  # noqa: E402

RUN = sys.argv[1]      # python3 eval/course/exploratory/tables.py eval/course/runs/<名字>
meta, questions, rows = score.load_run(RUN)
grades = {(g["qid"], g["system"], g["round"]): (g.get("taskUuid"), g["score"]) for g in score.read_jsonl(RUN + '/grades.jsonl')}
audit = {(a["qid"], a["system"], a["round"]): (a.get("taskUuid"), a["kept"], a["rejected"]) for a in score.read_jsonl(RUN + '/audit.jsonl')}
P = lambda v: '—' if v is None else f'{100*v:.0f}%'
b1 = {r['qid'] for r in rows if r['system'] == 'B1'}
print('graded', len(grades), 'of', len(rows))
print('\n== the 30 questions B1 ran, all four systems ==')
sub = score.summarise([r for r in rows if r['qid'] in b1], {q: d for q, d in questions.items() if q in b1}, meta, grades, audit)
for s, i in sub['systems'].items():
    print(s, 'correct', P(i['correct']), 'located', P(i['located']), 'quote_ok', P(i['quote_ok']), 'weak', P(i['weak']), 'judged_out', P(i['judged_out']),
          'final_bad', P(i['final_bad']), '$med', None if i['cost_median'] is None else round(i['cost_median'], 4), 'sec', i['seconds_median'], 'in', i['tokens_in_median'])
for p in sub['pairs']:
    if p['a'] == 'B1' and p['scope'] == 'all': print('  ', p['a'], '-', p['b'], p['metric'], f"{100*p['diff']:+.1f} [{100*p['low']:+.1f}, {100*p['high']:+.1f}] n={p['n']} {p['verdict']}")
print('\n== correctness by kind, all questions ==')
kinds = {}
for (qid, s, rnd), (u, sc) in grades.items():
    kinds.setdefault((s, questions[qid]['kind']), {}).setdefault(qid, []).append(sc)
for (s, k), d in sorted(kinds.items()):
    vals = [statistics.mean(v) for v in d.values()]
    m, lo, hi, n = score.bootstrap(vals)
    print(s, k, f'{100*m:.0f}% [{100*lo:.0f}, {100*hi:.0f}] n={n}')
print('\n== full-score answers (every point hit), non-absent ==')
for s in sorted({k[0] for k in kinds}):
    allv = [x for (ss, k), d in kinds.items() if ss == s and k != 'absent' for v in d.values() for x in v]
    print(s, 'runs', len(allv), 'all points', P(sum(x == 1 for x in allv) / len(allv)), 'no point', P(sum(x == 0 for x in allv) / len(allv)))
rawp = RUN + '/grades.raw.jsonl'
if os.path.exists(rawp):
    print('\n== exploratory: every proposed claim graded, verification ignored (28 questions with points) ==')
    raw = {}; ver = {}
    for g in score.read_jsonl(rawp):
        raw.setdefault(g['system'], {}).setdefault(g['qid'], []).append(g['score'])
    for (qid, s, rnd), (u, sc) in grades.items():
        if qid in b1 and questions[qid]['kind'] != 'absent': ver.setdefault(s, {}).setdefault(qid, []).append(sc)
    R = {s: {q: statistics.mean(v) for q, v in d.items()} for s, d in raw.items()}
    V = {s: {q: statistics.mean(v) for q, v in d.items()} for s, d in ver.items()}
    for s in sorted(R):
        m = score.bootstrap(list(R[s].values())); v = score.bootstrap(list(V[s].values()))
        print(s, f'raw {100*m[0]:.0f}% [{100*m[1]:.0f}, {100*m[2]:.0f}] n={m[3]}   verified {100*v[0]:.0f}% [{100*v[1]:.0f}, {100*v[2]:.0f}]')
    for a, b in (('B1', 'S1'), ('B1', 'S3'), ('B1', 'S2')):
        d = score.bootstrap_diff(R[a], R[b]); print(f'  raw {a} - {b}: {100*d[0]:+.1f} [{100*d[1]:+.1f}, {100*d[2]:+.1f}] n={d[3]}')
        d = score.bootstrap_diff(V[a], V[b]); print(f'  verified {a} - {b}: {100*d[0]:+.1f} [{100*d[1]:+.1f}, {100*d[2]:+.1f}] n={d[3]}')

print('\n== the same judge asked again: share of an answer\'s claims it rejects, paired by question ==')
fb = {}
for (qid, s, rnd), (u, n, rej) in audit.items():
    if n: fb.setdefault(s, {}).setdefault(qid, []).append(rej / n)
FB = {s: {q: statistics.mean(v) for q, v in d.items()} for s, d in fb.items()}
pooled = {}
for (qid, s, rnd), (u, n, rej) in audit.items():
    p = pooled.setdefault(s, [0, 0]); p[0] += rej; p[1] += n
for s in sorted(FB):
    m = score.bootstrap(list(FB[s].values()))
    print(s, f'{100*m[0]:.1f}% [{100*m[1]:.1f}, {100*m[2]:.1f}] n={m[3]}  pooled {pooled[s][0]}/{pooled[s][1]} = {100*pooled[s][0]/pooled[s][1]:.1f}%')
if 'S1' in FB and 'S3' in FB:
    d = score.bootstrap_diff(FB['S3'], FB['S1']); print(f'  S3 - S1: {100*d[0]:+.1f} [{100*d[1]:+.1f}, {100*d[2]:+.1f}] n={d[3]}')

if os.path.exists(rawp):
    print('\n== raw minus verified, paired by question ==')
    for s in sorted(R):
        d = score.bootstrap_diff(R[s], V[s]); print(f'  {s}: {100*d[0]:+.1f} [{100*d[1]:+.1f}, {100*d[2]:+.1f}] n={d[3]}')

print('\n== B1 - S1 correctness against each S1 round separately (B1 has one round) ==')
by_round = {}
for (qid, s, rnd), (u, sc) in grades.items():
    if qid in b1: by_round.setdefault((s, rnd), {})[qid] = sc
for rnd in (1, 2, 3) if ('B1', 1) in by_round else ():
    d = score.bootstrap_diff(by_round[('B1', 1)], by_round[('S1', rnd)])
    print(f'  S1 round {rnd}: {100*d[0]:+.1f} [{100*d[1]:+.1f}, {100*d[2]:+.1f}] n={d[3]}')

print('\n== B1 evidence location: answers with at least one verified claim ==')
hit = tot = none = 0
for r in rows:
    if r['system'] != 'B1' or questions[r['qid']]['kind'] == 'absent': continue
    sc = score.score_row(questions[r['qid']], r, False)
    if not (r['answer'].get('claims') or []): none += 1; continue
    tot += 1; hit += 1 if sc.get('located') else 0
print(f'  no verified claim: {none}; with claims: {hit} of {tot} hit a gold range')
