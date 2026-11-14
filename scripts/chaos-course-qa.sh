#!/usr/bin/env bash
# 容错演练(课程问答):agent 回答到一半时杀掉它所在的 worker → 另一个 worker 从存档续跑,
# 已经调用过的工具不重跑,答案照常经过核验。
# 前置:课程环境带着模拟模型的延迟起来,否则 agent 几毫秒就跑完了,来不及杀:
#   LLM_MOCK_DELAY_MS=4000 ./scripts/course-env.sh up
# 用法:scripts/chaos-course-qa.sh <课程 id> ["问题"]
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:18090/api/v1}"
COURSE="${1:?用法: $0 <课程 id> [问题]}"
Q="${2:-reinforcement learning from human feedback}"
PREFIX="${PREFIX:-agentflow-course-}"

BODY=$(python3 -c 'import json,sys; print(json.dumps({"question":sys.argv[1]}))' "$Q")
UUID=$(curl -sf -X POST "$BASE/courses/$COURSE/questions" -H 'Content-Type: application/json' -d "$BODY" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["taskUuid"])')
echo "==> taskUuid=$UUID,等待 agent 的第一步…"

VICTIM=""
for i in $(seq 1 120); do
  VICTIM=$(curl -sf "$BASE/tasks/$UUID/trace" | python3 -c '
import sys,json
for e in json.load(sys.stdin):
    if e["stage"]=="AGENT_STEP":
        d=json.loads(e["detail"])
        if not d.get("final"):
            print(d["workerId"]); break
')
  [ -n "$VICTIM" ] && break
  sleep 0.5
done
[ -n "$VICTIM" ] || { echo "❌ 60 秒内没有出现 agent 步骤(模拟模型的延迟设了吗?)"; exit 1; }

C="${PREFIX}${VICTIM}-1"
echo "==> docker kill $C(它正在跑 agent 的下一步)"
docker kill "$C" >/dev/null
KILLED=$(date +%s)
trap 'docker start "$C" >/dev/null' EXIT

echo "==> 等待另一个 worker 接手(Kafka 会话超时约 45 秒)…"
STATUS=RUNNING
for i in $(seq 1 180); do
  STATUS=$(curl -sf "$BASE/questions/$UUID" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])')
  [ "$STATUS" != "RUNNING" ] && break
  sleep 1
done
echo "==> 从杀掉到答完:$(( $(date +%s) - KILLED )) 秒,状态 $STATUS"

curl -sf "$BASE/tasks/$UUID/trace" | python3 -c '
import sys,json
events=json.load(sys.stdin)
resumed=[json.loads(e["detail"]) for e in events if e["stage"]=="RESUMED"]
assert resumed, "没有出现 RESUMED:agent 没有从存档续跑"
for r in resumed:
    print("  ⚡", r["workerId"], "从第", r["fromStep"] + 1, "步续跑")
steps=[json.loads(e["detail"]) for e in events if e["stage"]=="AGENT_STEP"]
first=[s for s in steps if s.get("step")==1 and not s.get("final")]
assert len(first)==1, "第 1 步(检索)执行了 %d 次,应该只有 1 次" % len(first)
print("  第 1 步的工具调用只执行了 1 次,由", first[0]["workerId"], "执行")
'
curl -sf "$BASE/questions/$UUID" | python3 -c '
import sys,json
a=json.load(sys.stdin)
print("  通过核验的结论:%d 条,被拿掉:%d 条,轮数:%d" % (len(a["claims"]), len(a["dropped"]), a["rounds"]))
'
[ "$STATUS" = "COMPLETED" ] || { echo "❌ 提问没有完成"; exit 1; }
echo "✅ CHAOS PASS"
