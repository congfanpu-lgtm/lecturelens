"""Does the stored picture's quality change what the OCR reads? 60 key frames of one lecture, read from the decoded
frame (what the OCR saw before the fan-out) and from the same frame stored as JPEG q80 / q92 / q95 4:4:4 / PNG."""
import difflib
import io
import json
import os
import sys

from PIL import Image

from app.frames import RapidOcr, keyframes

key = sys.argv[1]
d = os.path.expanduser(f"~/agentflow-media-data/media/{key}")
meta = json.load(open(d + "/meta.json"))
want = sorted(f["tsMs"] for f in json.load(open(d + "/frames.v3.before-fanout.json"))["frames"])
want = set(want[:: max(1, len(want) // 60)][:60])
ocr = RapidOcr()


def stored(img, fmt, **kw):
    buf = io.BytesIO()
    img.save(buf, fmt, **kw)
    return Image.open(io.BytesIO(buf.getvalue())), len(buf.getvalue())


variants = {"jpeg q80": ("JPEG", {"quality": 80}), "jpeg q92": ("JPEG", {"quality": 92}),
            "jpeg q95 4:4:4": ("JPEG", {"quality": 95, "subsampling": 0}), "png": ("PNG", {})}
stats = {name: {"sim": [], "chars": 0, "spaces": 0, "bytes": 0, "identical": 0} for name in variants}
raw_chars = raw_spaces = n = 0
for ts, img in keyframes(d + "/" + meta["video"]):
    if ts not in want:
        continue
    n += 1
    raw = ocr.read(img)
    raw_chars += len(raw)
    raw_spaces += raw.count(" ")
    for name, (fmt, kw) in variants.items():
        again, size = stored(img, fmt, **kw)
        text = ocr.read(again)
        s = stats[name]
        s["sim"].append(difflib.SequenceMatcher(None, raw, text, autojunk=False).ratio())
        s["chars"] += len(text)
        s["spaces"] += text.count(" ")
        s["bytes"] += size
        s["identical"] += raw == text
print(f"{n} frames | decoded frame: {raw_chars} chars, {raw_spaces} spaces")
for name, s in stats.items():
    sim = sorted(s["sim"])
    print(f"{name:15s} | identical {s['identical']:2d}/{n} | similarity median {sim[len(sim) // 2]:.3f} min {sim[0]:.3f} | "
          f"{s['chars']} chars, {s['spaces']} spaces | {s['bytes'] / n / 1024:.0f} KB per frame")
