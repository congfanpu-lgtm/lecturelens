"""All settings come from the environment; nothing else reads os.environ."""
import os
from pathlib import Path

MEDIA_ROOT = Path(os.environ.get("MEDIA_ROOT", Path(__file__).resolve().parent.parent / "data")).resolve()
ASR_MODEL = os.environ.get("MEDIA_ASR_MODEL", "large-v3")
ASR_DEVICE = os.environ.get("MEDIA_ASR_DEVICE", "cuda")
ASR_COMPUTE = os.environ.get("MEDIA_ASR_COMPUTE", "float16")
ASR_BATCH = int(os.environ.get("MEDIA_ASR_BATCH", "16"))
# Slide OCR is CPU work. One OCR call gains nothing beyond a few threads, and many calls with the default
# "one thread per core" each fight for the same cores, so both are bounded: THREADS per call, SLOTS calls at once.
# Keep SLOTS x THREADS at or below the number of cores. Measured on 24 cores with eight lectures at once
# (docs/benchmarks.md section 8): no limit 1,243 s; 12 x 2 threads 541 s; 6 x 4 455 s; 3 x 8 405 s.
OCR_THREADS = int(os.environ.get("MEDIA_OCR_THREADS", "8"))
# MEDIA_OCR_THREADS=-1 leaves the library's default (one thread per core); the slot default then falls back to 1.
OCR_SLOTS = int(os.environ.get("MEDIA_OCR_SLOTS", str(max(1, (os.cpu_count() or 4) // OCR_THREADS) if OCR_THREADS > 0 else 1)))
ALLOWED_HOSTS = {"www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"}
