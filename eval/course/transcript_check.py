#!/usr/bin/env python3
"""转写质量抽查(标准库 + 媒体服务 venv 里的 yt-dlp):拿我们的逐字稿和 YouTube 上的字幕逐词对齐。

  python3 eval/course/transcript_check.py 3 5 9 [--windows 3] [--minutes 10] [--api …] [--yt-dlp agentflow-media/.venv/bin/yt-dlp]
  python3 eval/course/transcript_check.py --self-check

每一讲取几段(默认 3 段,每段 10 分钟,分别从时长的 10%、45%、80% 处开始),每段报告:
  两边各多少词、对上了多少;连续 5 个词以上只在字幕里有的段(我们可能漏了)、只在我们这边有的段(我们可能多了)。
字幕优先用上传者提供的那一轨;没有才用 YouTube 自动生成的,那种情况下对照的是另一个语音识别的结果,报告里会写明。
上传者的字幕也不保证是人工校对过的,所以这是抽查,不是字错率。
"""
import argparse
import difflib
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

RUN = 5   # 连续这么多个词对不上才算一段(零星的错词、口头语不算)


def words(text):
    return re.findall(r"[a-z0-9']+", text.lower())


def window(items, lo, hi):
    """items: [(startMs, text)];取开始时间落在 [lo, hi) 里的,连成词列表。"""
    return [w for start, text in items if lo <= start < hi for w in words(text)]


def captions(json3):
    """YouTube 的 json3 字幕 → [(startMs, text)]。"""
    out = []
    for e in json3.get("events", []):
        text = "".join(s.get("utf8", "") for s in e.get("segs", []) or []).strip()
        if text:
            out.append((e.get("tStartMs", 0), text))
    return out


def compare(reference, ours):
    """两个词列表 → 对上的词数,以及连续 RUN 个词以上对不上的段:[(词数, 开头几个词)]。"""
    sm = difflib.SequenceMatcher(None, reference, ours, autojunk=False)
    matched, missing, extra = 0, [], []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            matched += i2 - i1
            continue
        if i2 - i1 >= RUN:
            missing.append((i2 - i1, " ".join(reference[i1:min(i2, i1 + 8)])))
        if j2 - j1 >= RUN:
            extra.append((j2 - j1, " ".join(ours[j1:min(j2, j1 + 8)])))
    return {"reference": len(reference), "ours": len(ours), "matched": matched, "missing": missing, "extra": extra}


def starts(duration_ms, n, minutes):
    """n 段各自的开始时间:均匀地落在 10% 到 80% 之间,不超出这一讲。"""
    length = minutes * 60_000
    at = [duration_ms // 10] if n == 1 else [duration_ms * (10 * (n - 1) + 70 * i) // (100 * (n - 1)) for i in range(n)]
    return [max(0, min(a, duration_ms - length)) for a in at]


def fetch_captions(url, yt_dlp):
    """返回 (字幕, "uploaded" 或 "automatic");都没有时返回 (None, None)。"""
    for kind, flag in (("uploaded", "--write-subs"), ("automatic", "--write-auto-subs")):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run([yt_dlp, "--skip-download", flag, "--sub-langs", "en.*,-live_chat", "--sub-format", "json3",
                            "--no-warnings", "-q", "-o", os.path.join(d, "%(id)s"), url], check=False, timeout=180)
            files = sorted(glob.glob(os.path.join(d, "*.json3")))
            if files:
                best = max(files, key=os.path.getsize)   # 几轨里取内容最多的那一轨
                return captions(json.load(open(best, encoding="utf-8"))), kind
    return None, None


def self_check():
    assert words("Hello, WORLD! It's 3.5x faster.") == ["hello", "world", "it's", "3", "5x", "faster"]
    items = [(0, "before the window"), (60_000, "Inside one."), (119_000, "Inside two."), (120_000, "after")]
    assert window(items, 60_000, 120_000) == ["inside", "one", "inside", "two"]
    j3 = {"events": [{"tStartMs": 1000, "segs": [{"utf8": "hello "}, {"utf8": "there"}]}, {"tStartMs": 2000}, {"tStartMs": 3000, "segs": [{"utf8": "\n"}]}]}
    assert captions(j3) == [(1000, "hello there")]
    ref = "we train a reward model from human preferences and then we optimize the policy against it".split()
    same = compare(ref, list(ref))
    assert same["matched"] == len(ref) and not same["missing"] and not same["extra"]
    dropped = compare(ref, ref[:4] + ref[10:])                         # 我们漏了 6 个连续的词
    assert dropped["missing"] == [(6, "model from human preferences and then")] and not dropped["extra"]
    added = compare(ref, ref[:4] + "thank you for watching please subscribe".split() + ref[4:])
    assert added["extra"] == [(6, "thank you for watching please subscribe")] and not added["missing"]
    noisy = compare(ref, [w if i % 6 else "uh" for i, w in enumerate(ref)])   # 零星的错词不算一段
    assert not noisy["missing"] and not noisy["extra"] and noisy["matched"] < len(ref)
    assert starts(3_600_000, 3, 10) == [360_000, 1_620_000, 2_880_000]
    assert starts(600_000, 3, 10) == [0, 0, 0] and starts(3_600_000, 1, 10) == [360_000]
    print("self-check ok")


def main():
    p = argparse.ArgumentParser(description="转写质量抽查:和 YouTube 字幕逐词对齐")
    p.add_argument("lectures", nargs="*", type=int, help="讲次 id")
    p.add_argument("--windows", type=int, default=3)
    p.add_argument("--minutes", type=int, default=10)
    p.add_argument("--api", default="http://127.0.0.1:18090/api/v1")
    p.add_argument("--yt-dlp", default="agentflow-media/.venv/bin/yt-dlp")
    p.add_argument("--self-check", action="store_true")
    args = p.parse_args()
    if args.self_check:
        self_check()
        return 0
    if not args.lectures:
        p.error("要给讲次 id")

    def get(path):
        with urllib.request.urlopen(args.api + path, timeout=60) as r:
            return json.load(r)

    total = {"reference": 0, "ours": 0, "matched": 0, "missing": 0, "extra": 0}
    for lecture_id in args.lectures:
        lecture = get(f"/lectures/{lecture_id}")
        if not lecture.get("sourceUrl"):
            print(f"讲次 {lecture_id}:不是 YouTube 链接,没有字幕可对照")
            continue
        reference, kind = fetch_captions(lecture["sourceUrl"], args.yt_dlp)
        if reference is None:
            print(f"讲次 {lecture_id}:YouTube 上没有英文字幕")
            continue
        ours = [(u["startMs"], u["text"]) for u in get(f"/lectures/{lecture_id}/transcript")["utterances"]]
        print(f"讲次 {lecture_id}(第 {lecture['seq']} 讲),对照的是{'上传者提供的字幕' if kind == 'uploaded' else 'YouTube 自动生成的字幕(另一个语音识别的结果)'}")
        for lo in starts(lecture["durationMs"], args.windows, args.minutes):
            hi = lo + args.minutes * 60_000
            c = compare(window(reference, lo, hi), window(ours, lo, hi))
            for key in ("reference", "ours", "matched"):
                total[key] += c[key]
            total["missing"] += len(c["missing"])
            total["extra"] += len(c["extra"])
            print(f"  {lo // 60000:3d}–{hi // 60000} 分:字幕 {c['reference']} 词,我们 {c['ours']} 词,对上 {c['matched']}"
                  f"({100 * c['matched'] / max(1, c['reference']):.1f}%);可能漏了 {len(c['missing'])} 段,可能多了 {len(c['extra'])} 段")
            for label, runs in (("漏", c["missing"]), ("多", c["extra"])):
                for n, head in runs:
                    print(f"      {label} {n} 词:{head} …")
    if total["reference"]:
        print(f"合计:字幕 {total['reference']} 词,我们 {total['ours']} 词,对上 {100 * total['matched'] / total['reference']:.1f}%;"
              f"连续 {RUN} 词以上只在字幕里有的 {total['missing']} 段,只在我们这边有的 {total['extra']} 段")
    return 0


if __name__ == "__main__":
    sys.exit(main())
