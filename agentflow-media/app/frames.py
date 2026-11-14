"""Slides: decode key frames only, OCR a frame only when the picture changed.

Three steps, so that the OCR (the expensive part) can run in batches on any number of machines:
pick (decode, keep the frames whose picture changed) -> OCR in batches of OCR_BATCH -> merge (drop near-duplicate
readings). frames_media runs all three in one call."""
import difflib
import io
import json
import shutil
import tempfile
import threading
import urllib.error
import urllib.request
from pathlib import Path
from typing import Iterator, Protocol

import av
import numpy as np
from PIL import Image

from . import config
from .stats import Stats
from .store import BadInput, NotFound, Store, valid_key

MIN_TEXT = 10   # frames whose text is shorter than this carry nothing worth indexing
# The file name carries the version of the algorithm: bumping it makes older results count as "not done yet".
# v2 = change detection ignores always-moving regions, near-duplicate text removed.
# v3 = of several similar readings of one slide the fullest is kept (v2 kept the first).
FRAMES_FILE = "frames.v3.json"
CANDIDATES_FILE = "candidates.v1.json"   # {"keyframes": n, "files": [...]}: the frames picked for OCR, in time order
OCR_BATCH = config.OCR_BATCH             # candidate frames per OCR batch (one batch = one subtask of the engine)
ROWS, COLS, BLOCK = 9, 16, 10   # the 160x90 thumbnail as 10x10-pixel blocks
BLOCK_SHARE = 0.05              # a block "changed" when more than 5% of its pixels moved
NOISY_SHARE = 0.5               # a block that changes between more than half of all key-frame pairs is always moving
MIN_BLOCKS = 2                  # the picture "changed" when at least this many steady blocks changed
SAME_TEXT = 0.8                 # two OCR results this similar are the same slide read twice
# At most this many OCR calls run at once, across all lectures (see config.OCR_SLOTS). In-process, like the GPU lock.
OCR_SLOTS = threading.BoundedSemaphore(config.OCR_SLOTS)


def keyframes(video: Path) -> Iterator[tuple[int, Image.Image]]:
    with av.open(str(video)) as container:
        if not container.streams.video:
            return   # an audio-only file imported from the inbox: no picture, no frames
        stream = container.streams.video[0]
        stream.codec_context.skip_frame = "NONKEY"   # key frames only: an hour of lecture takes seconds
        for frame in container.decode(stream):
            # Some decoders ignore skip_frame and hand back every frame (measured: VP9). One 44-minute lecture
            # at 30 fps became 80,000 "key frames" and its frames step ran for two hours.
            if not frame.key_frame:
                continue
            yield round(float(frame.pts * stream.time_base) * 1000), frame.to_image()


def small(img: Image.Image) -> np.ndarray:
    return np.asarray(img.convert("L").resize((160, 90)), dtype=np.int16)


def block_changes(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """(9, 16) booleans: which blocks changed between two thumbnails (pixel brightness moved by more than 24)."""
    moved = np.abs(a - b) > 24
    return moved.reshape(ROWS, BLOCK, COLS, BLOCK).mean(axis=(1, 3)) > BLOCK_SHARE


def noisy_blocks(video: Path) -> np.ndarray:
    """(9, 16) booleans: blocks that keep moving through the whole recording, such as a speaker's webcam in a
    corner. On the first real lecture that corner made 76% of key frames count as "changed"."""
    counts, pairs, prev = np.zeros((ROWS, COLS)), 0, None
    for _, img in keyframes(video):
        now = small(img)
        if prev is not None:
            counts += block_changes(prev, now)
            pairs += 1
        prev = now
    if pairs < 4:   # too short to tell movement from slide changes
        return np.zeros((ROWS, COLS), dtype=bool)
    moving = counts / pairs > NOISY_SHARE
    # One block of margin around the moving region is ignored as well: an overlay's edge runs through the middle
    # of blocks, and its calmer part moves less often. Measured on the first real lecture: the lower half of the
    # webcam changed in 38-47% of pairs (under the 50% line) and caused 85% of the wasted OCR runs.
    padded = np.pad(moving, 1)
    return np.logical_or.reduce([padded[r:r + ROWS, c:c + COLS] for r in range(3) for c in range(3)])


def changed(a: np.ndarray, b: np.ndarray, ignore: np.ndarray | None = None) -> bool:
    blocks = block_changes(a, b)
    if ignore is not None:
        blocks &= ~ignore
    return int(blocks.sum()) >= MIN_BLOCKS


def same_slide(a: str, b: str) -> bool:
    """OCR of an unchanged slide differs in spacing from read to read ("AgentsMOOC" / "Agents MOOC")."""
    x, y = "".join(a.split()).lower(), "".join(b.split()).lower()
    return difflib.SequenceMatcher(None, x, y, autojunk=False).ratio() >= SAME_TEXT


class Ocr(Protocol):
    def read(self, img: Image.Image) -> str:
        """Text on the image, lines joined with a newline."""


def _stop_spinning() -> None:
    """ONNX Runtime's worker threads busy-wait ("spin") between two pieces of work by default. Reading one slide
    is dozens of small inferences, so the threads never stop spinning: measured, one engine with 8 threads kept
    20 cores busy for work worth 4, and lectures read at the same time starved each other (docs/benchmarks.md
    section 8). The OCR library has no setting for it, so the place where it builds its session options is wrapped."""
    from rapidocr_onnxruntime.utils.infer_engine import OrtInferSession

    if getattr(OrtInferSession, "_spinning_off", False):
        return
    build = OrtInferSession._init_sess_opts

    def options(cfg):
        opts = build(cfg)
        opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
        return opts

    OrtInferSession._init_sess_opts = staticmethod(options)
    OrtInferSession._spinning_off = True


class RapidOcr:
    def __init__(self, threads: int = config.OCR_THREADS):
        import rapidocr_onnxruntime   # lazy: loads ONNX models
        import yaml

        _stop_spinning()

        # The thread count can only be set through the library's config file: its three models (detect, classify,
        # recognise) read it from their own sections, and keyword arguments do not reach all of them.
        root = Path(rapidocr_onnxruntime.__file__).parent
        cfg = yaml.safe_load((root / "config.yaml").read_text())
        for part in ("Det", "Cls", "Rec"):
            cfg[part]["intra_op_num_threads"] = threads
            cfg[part]["model_path"] = str(root / cfg[part]["model_path"])
        with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
            yaml.safe_dump(cfg, f)
        try:
            self._engine = rapidocr_onnxruntime.RapidOCR(config_path=f.name)
        finally:
            Path(f.name).unlink(missing_ok=True)

    def read(self, img: Image.Image) -> str:
        result, _ = self._engine(np.asarray(img.convert("RGB")))
        return "\n".join(line[1] for line in result or [])


def _batches(n_files: int) -> int:
    return -(-n_files // OCR_BATCH)


def _texts_file(n: int) -> str:
    return f"ocr.{n}.json"   # {"texts": {file name: text}} for batch n


def pick(store: Store, key: str) -> dict:
    """Step 1: save the key frames whose picture changed as frames/<ms>.png and list them. No OCR.
    Which frames are picked depends only on the pictures, never on what the OCR reads, so the batches
    can be read in any order and on any machine."""
    # ponytail: 一直在动的块是按整段视频统计的。镜头对着讲台超过一半时长的录像,所有块都会被当成"一直在动",
    # 幻灯片就找不到了;真遇到再改成按时间窗口统计
    meta = store.read_json(key, "meta.json")
    if meta is None:
        raise NotFound(key)
    with store.lock("frames:" + key):   # a retry that arrives while the first run is still going waits
        if store.read_json(key, FRAMES_FILE) is not None:   # this media was finished earlier: nothing left to read
            return {"mediaKey": key, "cached": True, "candidates": 0, "batches": 0}
        doc = store.read_json(key, CANDIDATES_FILE)
        cached = doc is not None
        if not cached:
            files: list[str] = []
            n_key = 0
            for old in store.dir(key).glob("ocr.*.json"):   # readings of an earlier list of candidates
                old.unlink()
            if meta["video"]:
                video = store.dir(key) / meta["video"]
                ignore = noisy_blocks(video)   # first pass over the key frames (seconds); an unreadable video fails here
                shutil.rmtree(store.dir(key) / "frames", ignore_errors=True)   # images of an earlier or failed run
                (store.dir(key) / "frames").mkdir()
                seen = None
                for ts_ms, img in keyframes(video):
                    n_key += 1
                    now = small(img)
                    if seen is not None and not changed(seen, now, ignore):
                        continue
                    seen = now   # compare against the last frame we picked, so slow fades still add up to a change
                    # Lossless, because this file is what the OCR reads: from a JPEG it finds 16% fewer spaces
                    # between words (docs/benchmarks.md section 10), and words run together cannot be searched.
                    # compress_level 1: several times faster to write than the default, somewhat larger.
                    files.append(f"{ts_ms:09d}.png")
                    img.save(store.dir(key) / "frames" / files[-1], compress_level=1)
            doc = {"keyframes": n_key, "files": files}
            store.write_json(key, CANDIDATES_FILE, doc)   # written last: a failed run leaves no list
        return {"mediaKey": key, "cached": cached, "candidates": len(doc["files"]), "batches": _batches(len(doc["files"]))}


class Local:
    """Where the OCR step finds a batch and leaves its result, when this process holds the files."""

    def __init__(self, store: Store):
        self.store = store

    def batch(self, key: str, n: int) -> dict:
        if self.store.read_json(key, FRAMES_FILE) is not None:
            # Merged already, by another task that was reading the same media (the same link submitted twice,
            # the same video in two courses): the list is gone because the work is finished, not missing.
            return {"files": [], "done": True}
        doc = self.store.read_json(key, CANDIDATES_FILE)
        files = doc["files"][n * OCR_BATCH:(n + 1) * OCR_BATCH] if doc is not None and n >= 0 else []
        if not files:
            raise NotFound(f"batch {n} of {key}")
        return {"files": files, "done": self.store.read_json(key, _texts_file(n)) is not None}

    def image(self, key: str, name: str) -> Image.Image:
        return Image.open(self.store.dir(key) / "frames" / name)

    def save(self, key: str, n: int, texts: dict[str, str]) -> int:
        batch = self.batch(key, n)
        if batch["done"] and not batch["files"]:
            return 0   # a reading that arrives after the merge: nothing left to store it for
        # the names arrive over HTTP from an OCR replica: only this batch's own files may be written
        if set(texts) != set(batch["files"]):
            raise BadInput(f"texts do not match the files of batch {n}")
        self.store.write_json(key, _texts_file(n), {"texts": texts})
        return len(texts)


def _http(method: str, url: str, body: bytes | None) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"Content-Type": "application/json"} if body else {})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


class Remote:
    """The same three operations for an OCR replica: a process without the files, possibly on another machine.
    Everything goes through the media service that holds them, over HTTP."""

    def __init__(self, base_url: str, send=_http):
        self.base, self.send = base_url.rstrip("/"), send

    def _call(self, method: str, key: str, path: str, body: bytes | None = None) -> bytes:
        if not valid_key(key):   # a replica has no store to check it against, and the key goes into a URL
            raise BadInput(f"bad media key: {key!r}")
        status, data = self.send(method, f"{self.base}/media/{key}/{path}", body)
        if status // 100 == 2:
            return data
        detail = data.decode("utf-8", "replace")[:300]
        if status == 404:   # the same meaning as on the media service itself, so the caller's retry rules still hold
            raise NotFound(detail)
        if status // 100 == 4:
            raise BadInput(detail)
        raise RuntimeError(f"media service answered {status}: {detail}")

    def batch(self, key: str, n: int) -> dict:
        return json.loads(self._call("GET", key, f"frames/batches/{n}"))

    def image(self, key: str, name: str) -> Image.Image:
        return Image.open(io.BytesIO(self._call("GET", key, f"frames/{name}")))

    def save(self, key: str, n: int, texts: dict[str, str]) -> int:
        return json.loads(self._call("PUT", key, f"frames/batches/{n}", json.dumps({"texts": texts}).encode()))["stored"]


def ocr_batch(source, key: str, n: int, ocr: Ocr, stats: Stats) -> dict:
    """Step 2: read the text of one batch of picked frames. Reading a batch twice (a retry, a redelivery)
    is harmless: the result is written whole, and both readings are of the same images."""
    batch = source.batch(key, n)
    if not batch["done"]:
        texts = {}
        for name in batch["files"]:
            img = source.image(key, name)
            with OCR_SLOTS:   # shared by all lectures: without it eleven lectures at once thrash the CPU
                texts[name] = ocr.read(img).strip()
        source.save(key, n, texts)
    return {"mediaKey": key, "batch": n, "frames": len(batch["files"]), "cached": batch["done"]}


def _clean_up(store: Store, key: str, candidates: dict) -> None:
    """After the merge: the picked PNGs and the batch readings first, the list of candidates last,
    so that an interrupted clean-up can be seen (the list is still there) and finished."""
    for name in candidates["files"]:
        (store.dir(key) / "frames" / name).unlink(missing_ok=True)
    for old in [*store.dir(key).glob("ocr.*.json"), store.dir(key) / CANDIDATES_FILE]:
        old.unlink(missing_ok=True)


def merge(store: Store, key: str, stats: Stats) -> dict:
    """Step 3: put the batches back in time order and keep one reading per slide."""
    with store.lock("frames:" + key):
        candidates = store.read_json(key, CANDIDATES_FILE)
        if (doc := store.read_json(key, FRAMES_FILE)) is not None:
            if candidates is not None:   # an earlier merge wrote the result and died before it had cleaned up
                _clean_up(store, key, candidates)
            return {"mediaKey": key, "cached": True, "frames": len(doc["frames"])}
        if candidates is None:
            raise NotFound(f"candidates of {key}")
        texts: dict[str, str] = {}
        for n in range(_batches(len(candidates["files"]))):
            part = store.read_json(key, _texts_file(n))
            if part is None:   # merging without it would silently lose up to OCR_BATCH slides
                raise BadInput(f"batch {n} of {key} has not been read yet")
            texts.update(part["texts"])
        frames: list[dict] = []
        text_kept = None
        for name in candidates["files"]:
            text = texts[name]
            if len(text) < MIN_TEXT:
                continue
            if text_kept is not None and same_slide(text, text_kept):
                # The same slide read again. If there is more on it now (bullets appearing one by one),
                # keep the fuller reading and picture, at the time the slide first appeared:
                # keeping only the first reading would leave the last bullets out of the index.
                if len(text) > len(text_kept):
                    text_kept = frames[-1]["text"] = text
                    frames[-1]["file"] = name
                continue
            text_kept = text
            frames.append({"tsMs": int(name[:9]), "file": name, "text": text})
        # The kept slides become JPEGs for the page (a quarter of the size); the lossless candidates were for the OCR.
        # Pictures first, then the result, then the clean-up: a crash in between leaves stray files, never a result
        # without its pictures. The next merge call (a retry) finishes an interrupted clean-up, see above.
        for f in frames:
            jpg = "frames/" + f["file"][:9] + ".jpg"
            Image.open(store.dir(key) / "frames" / f["file"]).convert("RGB").save(store.dir(key) / jpg, quality=80)
            f["file"] = jpg
        store.write_json(key, FRAMES_FILE, {"frames": frames, "keyframes": candidates["keyframes"],
                                            "ocred": len(candidates["files"])})
        stats.ocr_runs += 1
        _clean_up(store, key, candidates)
        return {"mediaKey": key, "cached": False, "frames": len(frames)}


def frames_media(store: Store, key: str, ocr: Ocr, stats: Stats) -> dict:
    """All three steps in one call, one batch after the other (a single machine, no engine in between)."""
    if store.read_json(key, "meta.json") is None:
        raise NotFound(key)
    with store.lock("frames-all:" + key):   # a second call for the same media waits and then finds the result
        picked = pick(store, key)
        for n in range(picked["batches"]):
            ocr_batch(Local(store), key, n, ocr, stats)
        return merge(store, key, stats)
