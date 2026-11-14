"""All settings come from the environment; nothing else reads os.environ."""
import os
from pathlib import Path

MEDIA_ROOT = Path(os.environ.get("MEDIA_ROOT", Path(__file__).resolve().parent.parent / "data")).resolve()
ASR_MODEL = os.environ.get("MEDIA_ASR_MODEL", "large-v3")
ASR_DEVICE = os.environ.get("MEDIA_ASR_DEVICE", "cuda")
ASR_COMPUTE = os.environ.get("MEDIA_ASR_COMPUTE", "float16")
ASR_BATCH = int(os.environ.get("MEDIA_ASR_BATCH", "16"))
# Slide OCR: SLOTS is how many OCR calls run at once, across all lectures; THREADS is the size of the OCR engine's
# thread pools. There is ONE engine, shared by all calls (main._rapid), so THREADS is not "threads per call".
# What limits OCR on the machine this was measured on (24 cores, laptop memory) is memory bandwidth, not cores:
# with one engine, with an engine per slot, or with an engine per process, total throughput stays near 4.5 slides
# per second, and 24 single-threaded engines are slower than 3 callers on one engine (docs/benchmarks.md section 8).
# So the defaults stay "pools of 8, cores // 8 calls at once"; more OCR needs more machines (OCR replicas).
OCR_THREADS = int(os.environ.get("MEDIA_OCR_THREADS", "8"))
# MEDIA_OCR_THREADS=-1 leaves the library's default (one thread per core); the slot default then falls back to 1.
# Never less than 1: zero slots would make every OCR call wait forever.
OCR_SLOTS = max(1, int(os.environ.get("MEDIA_OCR_SLOTS", str((os.cpu_count() or 4) // OCR_THREADS if OCR_THREADS > 0 else 1))))
# Picked frames per OCR batch; one batch is one subtask of the engine. Small batches spread a lecture over many
# workers and their OCR replicas; on a single machine without replicas a large value (one batch per lecture)
# means fewer subtasks waiting in queues behind other long steps.
OCR_BATCH = max(1, int(os.environ.get("MEDIA_OCR_BATCH", "20")))
ALLOWED_HOSTS = {"www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be"}
# Set on an OCR replica: the address of the media service that holds the files (for example http://172.17.0.1:8010).
# A replica has no media of its own; it fetches the pictures of a batch from there and sends the text back.
SOURCE = os.environ.get("MEDIA_SOURCE", "").rstrip("/")
