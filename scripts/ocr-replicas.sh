#!/usr/bin/env bash
# 识别副本:和媒体服务同一份代码,自己没有文件,图片和结果都经 HTTP 找媒体服务(MEDIA_SOURCE)。
# 一个 worker 配一个副本(worker 的 MEDIA_OCRBASEURL / course.yml 里的 OCR_URL_n),加机器 = 加一对"worker + 副本"。
# 在一台机器上量"加副本有没有用"时,每个副本用 taskset 限定在自己的几个核上:第 i 个副本用核 i、i+N、i+2N…,
# 这样每个副本拿到的快核和慢核一样多。
#   scripts/ocr-replicas.sh up <N>     起 N 个副本,端口 8011 起,监听 MEDIA_HOST(默认 172.17.0.1,容器能访问到)
#   scripts/ocr-replicas.sh down       全部停掉
# 环境变量:MEDIA_SOURCE(默认 http://172.17.0.1:8010)、PIN=0 不绑核、MEDIA_OCR_THREADS / MEDIA_OCR_SLOTS 原样传给副本。
set -euo pipefail
cd "$(dirname "$0")/../agentflow-media"
HOST="${MEDIA_HOST:-172.17.0.1}"
SOURCE="${MEDIA_SOURCE:-http://172.17.0.1:8010}"
LOGS="${OCR_REPLICA_LOGS:-/tmp}"
case "${1:-}" in
  up)
    n="${2:?how many replicas}"
    cores=$(nproc)
    for i in $(seq 0 $((n - 1))); do
      port=$((8011 + i))
      pin=()
      if [ "${PIN:-1}" = 1 ]; then pin=(taskset -c "$(seq -s, "$i" "$n" $((cores - 1)))"); fi
      MEDIA_SOURCE="$SOURCE" MEDIA_HOST="$HOST" MEDIA_PORT="$port" setsid nohup "${pin[@]}" ./run.sh \
        > "$LOGS/ocr-replica-$port.log" 2>&1 < /dev/null &
      echo "replica $i: http://$HOST:$port  ${pin[*]:-not pinned}"
    done ;;
  down) pkill -f "[u]vicorn app.main:app --host $HOST --port 801[1-9]" || true ;;
  *) echo "usage: $0 up <N> | down" >&2; exit 2 ;;
esac
