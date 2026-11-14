"""OCR layouts on one machine, one layout per invocation (a fresh process each time), CPU measured machine-wide.
usage: python bench_layouts.py '<glob>' <mode: threads|procs> <engines> <callers> <ort threads> <spin 0|1>"""
import glob
import queue
import sys
import threading
import time
from concurrent.futures import ProcessPoolExecutor

from PIL import Image

N = 240
_engine = None


def busy():
    """Machine-wide busy CPU seconds so far (everything but idle and iowait), from /proc/stat."""
    f = open("/proc/stat").readline().split()[1:]
    return (sum(map(int, f[:8])) - int(f[3]) - int(f[4])) / 100.0


def patch(spin):
    if spin:
        return
    from rapidocr_onnxruntime.utils import infer_engine
    orig = infer_engine.OrtInferSession._init_sess_opts

    def no_spin(config):
        opts = orig(config)
        opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
        return opts

    infer_engine.OrtInferSession._init_sess_opts = staticmethod(no_spin)


def make(threads, spin):
    patch(spin)
    from app.frames import RapidOcr
    e = RapidOcr(threads)
    e.read(Image.new("RGB", (640, 360), "white"))
    return e


def _init(threads, spin):
    global _engine
    _engine = make(threads, spin)


def _read(path):
    return len(_engine.read(Image.open(path)))


if __name__ == "__main__":
    paths = sorted(glob.glob(sys.argv[1]))[:N]
    mode, engines, callers, threads, spin = sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), sys.argv[6] == "1"
    if mode == "threads":
        pool = [make(threads, spin) for _ in range(engines)]
        q = queue.Queue()
        for p in paths:
            q.put(p)
        chars = [0] * callers

        def work(k):
            while True:
                try:
                    p = q.get_nowait()
                except queue.Empty:
                    return
                chars[k] += len(pool[k % engines].read(Image.open(p)))

        c0, t0 = busy(), time.time()
        ts = [threading.Thread(target=work, args=(k,)) for k in range(callers)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        wall, used, total = time.time() - t0, busy() - c0, sum(chars)
    else:
        with ProcessPoolExecutor(engines, initializer=_init, initargs=(threads, spin)) as ex:
            list(ex.map(_read, paths[:engines * 2]))   # every process has loaded its engine before the clock starts
            c0, t0 = busy(), time.time()
            total = sum(ex.map(_read, paths))
            wall, used = time.time() - t0, busy() - c0
    print(f"{mode} | engines {engines} | callers {callers} | ort threads {threads} | spin {'on' if spin else 'off'} | "
          f"{wall:.1f} s | {len(paths) / wall:.2f} img/s | {used / wall:.1f} cores busy | {used / len(paths):.2f} cpu-s per image | {total} chars",
          flush=True)
