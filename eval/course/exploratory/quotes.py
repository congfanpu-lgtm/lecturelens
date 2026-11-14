#!/usr/bin/env python3
"""探索性分析:模型给出的每一条讲话引文,和逐字稿对一遍,分成
  1 在它标的时间里逐字找得到(核验第 2 层会放行的)  2 逐字找得到,但在这一讲的别处  3 只差标点
  4 小改动(连续 5 个词的片段有 8 成以上找得到:省略号、漏掉口头语)  5 / 6 改写得更多
核验没通过的结论,它的引文不在问答接口的返回里,所以要先从课程环境的数据库里导出作答那一步的输出:

  mysql --default-character-set=utf8mb4 --batch --raw -N agentflow -e \
    "select t.task_uuid, s.seq, s.output from subtask s join task t on s.task_id=t.id \
     where s.kind='QA_AGENT' and s.output is not null" > agent-out.tsv
  python3 eval/course/exploratory/quotes.py eval/course/runs/<名字> agent-out.tsv [--api http://127.0.0.1:18090/api/v1]

只统计讲话引文;幻灯片引文只计数。逐字稿从课程环境的接口取。
"""
import collections
import json
import re
import sys
import urllib.request

R = sys.argv[1]
TSV = sys.argv[2]
API = sys.argv[sys.argv.index("--api") + 1] if "--api" in sys.argv else "http://127.0.0.1:18090/api/v1"
runs={}
for l in open(R+'/results.jsonl'):
    r=json.loads(l); runs[r['taskUuid']]=(r['system'],r['qid'],r['round'])
def norm(s):
    return re.sub(r'\s+',' ',(s or '').lower().replace('‘',"'").replace('’',"'").replace('“','"').replace('”','"')).strip()
def alnum(s): return re.sub(r'[^a-z0-9]+',' ',norm(s)).strip()
utt={}
def lecture(i):
    if i not in utt:
        utt[i]=json.load(urllib.request.urlopen(f'{API}/lectures/{i}/transcript'))['utterances']
    return utt[i]
full={}; fullA={}; grams={}
def prep(i):
    if i not in full:
        t=' '.join(u['text'] for u in lecture(i)); full[i]=norm(t); a=alnum(t); fullA[i]=' '+a+' '
        w=a.split(); grams[i]={' '.join(w[k:k+5]) for k in range(len(w)-4)}
def window(i,a,b):
    return norm(' '.join(u['text'] for u in lecture(i) if u['endMs']>a-15000 and u['startMs']<b+15000))
cnt=collections.defaultdict(collections.Counter)
for line in open(TSV, encoding='utf-8'):
    uuid,_,out=line.rstrip('\n').split('\t',2)
    if uuid not in runs: continue
    sysname=runs[uuid][0]
    try: o=json.loads(out)
    except Exception: cnt[sysname]['unparsable output']+=1; continue
    for c in o.get('claims',[]):
        for e in c.get('evidence',[]):
            if e.get('source')!='speech': cnt[sysname]['slide evidence (not classified)']+=1; continue
            i=e.get('lectureId'); q=norm(e.get('quote'))
            if not i or not q: cnt[sysname]['no lecture or empty quote']+=1; continue
            prep(i)
            if q in window(i,e['startMs'],e['endMs']): k='1 verbatim in the cited time'
            elif q in full[i]: k='2 verbatim, but elsewhere in the lecture'
            elif (' '+alnum(q)+' ') in fullA[i]: k='3 differs only in punctuation'
            else:
                w=alnum(q).split(); g=[' '.join(w[j:j+5]) for j in range(len(w)-4)]
                cov=sum(x in grams[i] for x in g)/len(g) if g else 0
                k='4 small edits (>=80% of 5-word runs found)' if cov>=0.8 else ('5 partly reworded (40-80%)' if cov>=0.4 else '6 reworded (<40%)')
            cnt[sysname][k]+=1
for s in sorted(cnt):
    tot=sum(v for k,v in cnt[s].items() if k[0].isdigit())
    print(s, 'speech evidence:', tot)
    for k,v in sorted(cnt[s].items()): print(f'   {k}: {v}' + (f' ({100*v/tot:.1f}%)' if k[0].isdigit() else ''))
