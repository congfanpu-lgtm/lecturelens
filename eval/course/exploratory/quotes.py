#!/usr/bin/env python3
"""探索性分析:模型给出的每一条讲话引文,和逐字稿对一遍,分成
  1 在它标的时间里逐字找得到(核验第 2 层会放行的)  2 逐字找得到,但在这一讲的别处  3 只差标点
  4 小改动(连续 5 个词的片段有 8 成以上找得到:省略号、漏掉口头语)  5 / 6 改写得更多
核验没通过的结论,它的引文不在问答接口的返回里,所以要先从课程环境的数据库里导出作答那一步的输出:

  mysql --default-character-set=utf8mb4 --batch --raw -N agentflow -e \
    "select t.task_uuid, s.seq, s.output from subtask s join task t on s.task_id=t.id \
     where s.kind='QA_AGENT' and s.output is not null" > agent-out.tsv
  python3 eval/course/exploratory/quotes.py eval/course/runs/<名字> agent-out.tsv [--api http://127.0.0.1:18090/api/v1]

讲话引文对的是逐字稿;幻灯片引文对的是那段时间显示着的幻灯片的识别文字,分成
  1 逐字找得到  2 逐字找得到,但在别的时间显示的幻灯片上  3 字母和数字相同,空格或标点不同(识别文字常把词连在一起)
  4 同上,在别的幻灯片上  5 这一讲的幻灯片上都找不到
逐字稿和幻灯片文字从课程环境的接口取。不打印任何引文。
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
fr={}
def frames(i):
    if i not in fr:
        d=json.load(urllib.request.urlopen(f'{API}/lectures/{i}/frames'))
        fr[i]=d if isinstance(d,list) else (d.get('frames') or [])
    return fr[i]
def squash(s): return re.sub(r'[^a-z0-9]+','',norm(s))
def slide_class(e):
    i=e.get('lectureId'); q=norm(e.get('quote'))
    if not i or not q: return 'no lecture or empty quote'
    f=frames(i); a,b=e['startMs']-15000,e['endMs']+15000
    shown=[x.get('text','') for k,x in enumerate(f) if (f[k+1]['tsMs'] if k+1<len(f) else 10**15)>a and x['tsMs']<b]
    w=norm('\n'.join(shown)); allt=norm('\n'.join(x.get('text','') for x in f))
    if q in w: return '1 verbatim on the slide shown at the cited time'
    if q in allt: return '2 verbatim, but on a slide shown at another time'
    if squash(q) and squash(q) in squash(w): return '3 same letters and digits at the cited time, spacing or punctuation differs'
    if squash(q) and squash(q) in squash(allt): return '4 same letters and digits on another slide'
    return '5 not on any slide of the lecture'
cnt=collections.defaultdict(collections.Counter); slide_cnt=collections.defaultdict(collections.Counter)
for line in open(TSV, encoding='utf-8'):
    uuid,_,out=line.rstrip('\n').split('\t',2)
    if uuid not in runs: continue
    sysname=runs[uuid][0]
    try: o=json.loads(out)
    except Exception: cnt[sysname]['unparsable output']+=1; continue
    for c in o.get('claims',[]):
        for e in c.get('evidence',[]):
            if e.get('source')!='speech':
                slide_cnt[sysname][slide_class(e)]+=1; continue
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
for s in sorted(slide_cnt):
    tot=sum(slide_cnt[s].values())
    print(s, 'slide evidence:', tot)
    for k,v in sorted(slide_cnt[s].items()): print(f'   {k}: {v} ({100*v/tot:.1f}%)')

