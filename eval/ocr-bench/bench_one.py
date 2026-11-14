"""Where does the CPU of one OCR engine go? Same 60 images, one process, one caller.
usage: python bench_one.py '<glob>' <ort threads> <cv2 threads or 0=default> <spin 0|1>"""
import glob
import os
import sys
import time

import cv2
from PIL import Image

paths = sorted(glob.glob(sys.argv[1]))[:60]
threads, cv_threads, spin = int(sys.argv[2]), int(sys.argv[3]), sys.argv[4] == "1"
if cv_threads:
    cv2.setNumThreads(cv_threads)
if not spin:
    from rapidocr_onnxruntime.utils import infer_engine
    orig = infer_engine.OrtInferSession._init_sess_opts

    def no_spin(config):
        opts = orig(config)
        opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
        return opts

    infer_engine.OrtInferSession._init_sess_opts = staticmethod(no_spin)

from app.frames import RapidOcr

ocr = RapidOcr(threads)
ocr.read(Image.open(paths[0]))
t = os.times()
c0, t0 = t.user + t.system, time.time()
chars = sum(len(ocr.read(Image.open(p))) for p in paths)
wall = time.time() - t0
t = os.times()
print(f"ort threads {threads} | cv2 threads {cv_threads or 'default'} | spin {'on' if spin else 'off'} | "
      f"{wall:.1f} s | {len(paths) / wall:.2f} img/s | {(t.user + t.system - c0) / wall:.1f} cores busy | "
      f"{(t.user + t.system - c0) / len(paths):.2f} cpu-s per image | {chars} chars")
