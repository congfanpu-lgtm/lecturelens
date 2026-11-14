"""Throughput of k OCR replicas on one lecture's picked frames: batch n goes to replica n % k, one stream per replica.
usage: python replica_bench.py <media key> <replica url> [<replica url> ...]"""
import glob
import json
import os
import sys
import threading
import time
import urllib.request

key, urls = sys.argv[1], sys.argv[2:]
d = os.path.expanduser(f"~/agentflow-media-data/media/{key}")
for f in glob.glob(d + "/ocr.*.json"):
    os.remove(f)
files = json.load(open(d + "/candidates.v1.json"))["files"]
batches = -(-len(files) // 20)


def busy():
    f = open("/proc/stat").readline().split()[1:]
    return (sum(map(int, f[:8])) - int(f[3]) - int(f[4])) / 100.0


def stream(i):
    for n in range(i, batches, len(urls)):
        req = urllib.request.Request(f"{urls[i]}/media/{key}/frames/batches/{n}/ocr", method="POST")
        with urllib.request.urlopen(req, timeout=3000) as r:
            assert json.load(r)["cached"] is False


c0, t0 = busy(), time.time()
ts = [threading.Thread(target=stream, args=(i,)) for i in range(len(urls))]
[t.start() for t in ts]
[t.join() for t in ts]
wall = time.time() - t0
print(f"{len(urls)} replica(s) | {len(files)} frames in {batches} batches | {wall:.1f} s | {len(files) / wall:.2f} img/s | "
      f"{(busy() - c0) / wall:.1f} cores busy", flush=True)
