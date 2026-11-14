"""Ingest several lectures at once (by the sources of existing lectures) into a scratch course and report when
each became searchable and how long every step took. usage: python3 ingest_many.py <label> <lecture id> ..."""
import json
import sys
import time
import urllib.request

API = "http://127.0.0.1:18090/api/v1"   # the course environment (scripts/course-env.sh up)


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


label, ids = sys.argv[1], sys.argv[2:]
course = call("POST", "/courses", {"name": f"scratch-{label}-{int(time.time())}"})
t0 = time.time()
new = []
for i in ids:
    src = call("GET", f"/lectures/{i}")
    new.append(call("POST", f"/courses/{course['id']}/lectures",
                    {"url": src["sourceUrl"]} if src.get("sourceUrl") else {"path": src["sourcePath"]})["id"])
done = {}
while len(done) < len(new):
    for i in new:
        if i not in done:
            v = call("GET", f"/lectures/{i}")
            if v["status"] != "INGESTING":
                done[i] = (time.time() - t0, v)
    time.sleep(1)
print(f"== {label}: course {course['id']}, all {len(new)} lectures settled after {max(t for t, _ in done.values()):.0f} s")
for i in new:
    t, v = done[i]
    steps = " ".join(f"{s['kind'][4:].lower()} {s['seconds']}s" + (f"({s['total']})" if s.get("total") else "") for s in v["stages"])
    print(f"   lecture {i} {v['status']} at {t:.0f} s | {steps}" + (f" | error {v['error']}" if v["error"] else ""))
