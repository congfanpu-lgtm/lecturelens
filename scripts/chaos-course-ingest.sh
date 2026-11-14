#!/usr/bin/env bash
# 容错演练(课程摄入):几讲同时摄入,转写和识别进行到一半时 kill -9 一个 worker,过一会儿拉起。
# 预期:每一讲都完成;每一讲只转写一次、幻灯片只合并一次、一次都不重新下载——做完的步骤没有重做。
#
# 用法:scripts/chaos-course-ingest.sh <已有讲次的 id> [<已有讲次的 id> ...]
#   把这几讲的来源(链接或文件)再加进一门临时课程里。要让转写和识别真的跑起来,演练前先把这几讲在媒体服务里的
#   转写和幻灯片结果挪开(transcript.*.json、frames.v3.json),演练后放回去或者留新的(内容相同)。
# 环境变量:VICTIM(默认 worker-1)、KILL_AFTER(挑帧完成后再等几秒才杀,默认 15)、DOWN(杀掉后几秒拉起,默认 20)、
#   MEDIA(媒体服务地址,默认 http://172.17.0.1:8010)、BASE、PREFIX。
set -euo pipefail
[ $# -ge 1 ] || { echo "用法: $0 <讲次 id> [<讲次 id> ...]" >&2; exit 2; }
BASE="${BASE:-http://127.0.0.1:18090/api/v1}"
MEDIA="${MEDIA:-http://172.17.0.1:8010}"
PREFIX="${PREFIX:-agentflow-course-}"
VICTIM="${VICTIM:-worker-1}"
C="${PREFIX}${VICTIM}-1"

stats() { curl -sf "$MEDIA/stats"; }
field() { python3 -c 'import sys,json; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

BEFORE=$(stats)
COURSE=$(curl -sf -X POST "$BASE/courses" -H 'Content-Type: application/json' \
  -d "{\"name\":\"chaos-ingest-$(date +%H%M%S)\"}" | field id)
NEW=""
for id in "$@"; do
  BODY=$(curl -sf "$BASE/lectures/$id" | python3 -c '
import sys,json
l=json.load(sys.stdin)
print(json.dumps({"url": l["sourceUrl"]} if l.get("sourceUrl") else {"path": l["sourcePath"]}))')
  NEW="$NEW $(curl -sf -X POST "$BASE/courses/$COURSE/lectures" -H 'Content-Type: application/json' -d "$BODY" | field id)"
done
START=$(date +%s)
echo "==> 临时课程 $COURSE,讲次:$NEW"

picked() {   # 有几讲的挑帧已经完成
  local n=0
  for id in $NEW; do
    curl -sf "$BASE/lectures/$id" | python3 -c '
import sys,json
s=json.load(sys.stdin)["stages"]
sys.exit(0 if any(x["kind"]=="LEC_FRAMES" and x["status"]=="COMPLETED" for x in s) else 1)' && n=$((n+1))
  done
  echo $n
}
for i in $(seq 1 300); do [ "$(picked)" -ge 1 ] && break; sleep 1; done
sleep "${KILL_AFTER:-15}"
echo "==> +$(( $(date +%s) - START )) 秒:docker kill $C(转写和识别都在进行中)"
docker kill "$C" >/dev/null
trap 'docker start "$C" >/dev/null 2>&1 || true' EXIT
sleep "${DOWN:-20}"
docker start "$C" >/dev/null
echo "==> +$(( $(date +%s) - START )) 秒:$C 拉起来了"

for i in $(seq 1 1800); do
  LEFT=0
  for id in $NEW; do
    [ "$(curl -sf "$BASE/lectures/$id" | field status)" = "INGESTING" ] && LEFT=$((LEFT+1))
  done
  [ "$LEFT" -eq 0 ] && break
  sleep 2
done
echo "==> +$(( $(date +%s) - START )) 秒:全部落定"

FAIL=0
for id in $NEW; do
  curl -sf "$BASE/lectures/$id" | python3 -c '
import sys,json
l=json.load(sys.stdin)
steps=" ".join("%s %ss" % (s["kind"][4:].lower(), s["seconds"]) + (" (%d 批)" % s["total"] if s.get("total") else "") for s in l["stages"])
print("  讲次 %d:%s  %s%s" % (l["id"], l["status"], steps, "  错误:" + l["error"] if l["error"] else ""))
sys.exit(0 if l["status"]=="READY" else 1)' || FAIL=1
done
AFTER=$(stats)
python3 - "$BEFORE" "$AFTER" $# <<'PY' || FAIL=1
import json, sys
before, after, n = json.loads(sys.argv[1]), json.loads(sys.argv[2]), int(sys.argv[3])
d = {k: after[k] - before[k] for k in before}
print("  媒体服务这段时间:下载 %d 次,转写 %d 次,幻灯片合并 %d 次(摄入了 %d 讲)" % (d["downloads"], d["asrRuns"], d["ocrRuns"], n))
ok = d["downloads"] == 0 and d["asrRuns"] <= n and d["ocrRuns"] <= n
if not ok:
    print("  ❌ 有步骤被重做了")
sys.exit(0 if ok else 1)
PY
[ "$FAIL" -eq 0 ] || { echo "❌ CHAOS FAIL"; exit 1; }
echo "✅ CHAOS PASS(临时课程 $COURSE 可以删掉)"
