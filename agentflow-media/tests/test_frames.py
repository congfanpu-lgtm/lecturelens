import shutil
import threading
import time
import wave

import av
import numpy as np
import pytest
from PIL import Image, ImageDraw

from fastapi.testclient import TestClient

from app import frames
from app.frames import FRAMES_FILE, RapidOcr, changed, frames_media, keyframes, noisy_blocks, same_slide, small
from app.main import app, get_ocr, get_stats, get_store
from app.stats import Stats
from app.store import BadInput, NotFound, Store
from tests.fakes import KEY, CountingOcr

SLIDES = ["Gradient descent: theta = theta - eta * grad", "ReAct: reason then act with tools",
          "Evaluation of LLM agents"]


def slide(text):
    img = Image.new("RGB", (1280, 720), "white")
    ImageDraw.Draw(img).text((80, 120), text, fill="black", font_size=48)
    return img


@pytest.fixture(scope="module")
def video(tmp_path_factory):
    """60 秒,每 20 秒换一页,每 2 秒一个关键帧。"""
    path = tmp_path_factory.mktemp("v") / "video.mp4"
    out = av.open(str(path), "w")
    st = out.add_stream("libx264", rate=2)
    st.width, st.height, st.pix_fmt = 1280, 720, "yuv420p"
    st.codec_context.gop_size = 4
    for i in range(120):
        for p in st.encode(av.VideoFrame.from_image(slide(SLIDES[i // 40]))):
            out.mux(p)
    for p in st.encode():
        out.mux(p)
    out.close()
    return path


def media(tmp_path, video):
    s = Store(tmp_path)
    s.write_json(KEY, "meta.json", {"mediaKey": KEY, "audio": "audio.m4a", "video": "video.mp4", "durationMs": 60000})
    shutil.copy(video, s.dir(KEY) / "video.mp4")
    return s


def test_keyframes_are_decoded_with_timestamps(video):
    ks = list(keyframes(video))
    assert len(ks) == 30 and ks[0][0] == 0 and ks[1][0] == 2000 and ks[0][1].size == (1280, 720)


def test_changed_detects_a_text_only_slide_change():
    a, b = small(slide(SLIDES[0])), small(slide(SLIDES[1]))
    assert not changed(a, a.copy()) and changed(a, b)


def test_one_frame_per_slide_with_its_text(tmp_path, video):
    s, stats = media(tmp_path, video), Stats()
    out = frames_media(s, KEY, RapidOcr(), stats)
    assert out == {"mediaKey": KEY, "cached": False, "frames": 3}
    fs = s.read_json(KEY, FRAMES_FILE)["frames"]
    assert [f["tsMs"] for f in fs] == [0, 20000, 40000]
    assert [f["file"] for f in fs] == ["frames/000000000.jpg", "frames/000020000.jpg", "frames/000040000.jpg"]
    assert "Gradient descent" in fs[0]["text"] and "ReAct" in fs[1]["text"] and "Evaluation" in fs[2]["text"]
    assert all((s.dir(KEY) / f["file"]).is_file() for f in fs) and stats.ocr_runs == 1


def test_second_call_uses_the_cache(tmp_path, video):
    s, ocr, stats = media(tmp_path, video), CountingOcr("some slide text here"), Stats()
    frames_media(s, KEY, ocr, stats)
    n = ocr.calls
    assert frames_media(s, KEY, ocr, stats)["cached"] is True and ocr.calls == n and stats.ocr_runs == 1


def test_only_changed_keyframes_are_ocred(tmp_path, video):
    ocr = CountingOcr("some slide text here")
    frames_media(media(tmp_path, video), KEY, ocr, Stats())
    assert ocr.calls == 3                       # 30 个关键帧里只有 3 次画面变化


def test_frames_with_too_little_text_are_dropped(tmp_path, video):
    s = media(tmp_path, video)
    assert frames_media(s, KEY, CountingOcr("  ab \n"), Stats())["frames"] == 0
    assert s.read_json(KEY, FRAMES_FILE)["frames"] == []


def test_identical_text_is_kept_once(tmp_path, video):
    assert frames_media(media(tmp_path, video), KEY, CountingOcr("the same text on every slide"), Stats())["frames"] == 1


def test_media_without_video_has_no_frames(tmp_path):
    s = Store(tmp_path)
    s.write_json(KEY, "meta.json", {"mediaKey": KEY, "audio": "audio.m4a", "video": None, "durationMs": 1})
    assert frames_media(s, KEY, CountingOcr("x" * 20), Stats()) == {"mediaKey": KEY, "cached": False, "frames": 0}
    assert s.read_json(KEY, FRAMES_FILE)["frames"] == []


def test_unreadable_video_fails_without_writing_frames(tmp_path):
    s = Store(tmp_path)
    s.write_json(KEY, "meta.json", {"mediaKey": KEY, "audio": "a", "video": "video.mp4", "durationMs": 1})
    (s.dir(KEY) / "video.mp4").write_bytes(b"not a video")
    with pytest.raises(Exception):
        frames_media(s, KEY, CountingOcr("x" * 20), Stats())
    assert s.read_json(KEY, FRAMES_FILE) is None


def test_unknown_media_raises_key_error(tmp_path):
    with pytest.raises(KeyError):
        frames_media(Store(tmp_path), KEY, CountingOcr("x" * 20), Stats())


def test_file_without_a_video_stream_has_no_frames(tmp_path):
    """本地导入的纯音频文件:meta 里 video 和 audio 是同一个文件,但里面没有画面。"""
    s = Store(tmp_path)
    s.write_json(KEY, "meta.json", {"mediaKey": KEY, "audio": "source.wav", "video": "source.wav", "durationMs": 1000})
    with wave.open(str(s.dir(KEY) / "source.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\0\0" * 16000)
    assert frames_media(s, KEY, CountingOcr("x" * 20), Stats())["frames"] == 0
    assert s.read_json(KEY, FRAMES_FILE)["frames"] == []


def test_concurrent_calls_for_the_same_media_ocr_once(tmp_path, video):
    s, ocr, stats = media(tmp_path, video), CountingOcr("some slide text here"), Stats()
    threads = [threading.Thread(target=frames_media, args=(s, KEY, ocr, stats)) for _ in range(3)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert ocr.calls == 3 and stats.ocr_runs == 1


# ---- 计划 2 任务 1:截帧去重 ----

def encode_with_webcam(path, lower_half_period):
    """三页幻灯片 + 右上角 190×100 的"讲者摄像头"小窗。上半部分每个关键帧都变;
    下半部分每 lower_half_period 个关键帧变一次(真实录像里肩膀比脸动得少)。"""
    out = av.open(str(path), "w")
    st = out.add_stream("libx264", rate=2)
    st.width, st.height, st.pix_fmt = 1280, 720, "yuv420p"
    st.codec_context.gop_size = 4
    for i in range(120):
        img = slide(SLIDES[i // 40])
        k = i // 4                                         # 每 4 帧一个关键帧
        top, bottom = (40 if k % 2 == 0 else 200), (40 if (k // lower_half_period) % 2 == 0 else 200)
        img.paste((top, top, top), (1090, 0, 1280, 50))
        img.paste((bottom, bottom, bottom), (1090, 50, 1280, 100))
        for p in st.encode(av.VideoFrame.from_image(img)):
            out.mux(p)
    for p in st.encode():
        out.mux(p)
    out.close()
    return path


@pytest.fixture(scope="module")
def webcam_video(tmp_path_factory):
    return encode_with_webcam(tmp_path_factory.mktemp("w") / "video.mp4", lower_half_period=1)


@pytest.fixture(scope="module")
def calm_webcam_video(tmp_path_factory):
    return encode_with_webcam(tmp_path_factory.mktemp("c") / "video.mp4", lower_half_period=3)


def test_the_calmer_edge_of_the_webcam_is_ignored_too(tmp_path, calm_webcam_video):
    """真实的那一讲里,小窗下半部分只在 38% 到 47% 的相邻关键帧之间有变化,没到一半,
    却触发了 85% 的多余识别(docs/benchmarks.md 第 7 节)。"""
    mask = noisy_blocks(calm_webcam_video)
    assert mask[:2, 14:].all() and not mask[3:, :].any() and not mask[:, :12].any()
    s, ocr = media(tmp_path, calm_webcam_video), CountingOcr("some slide text here")
    frames_media(s, KEY, ocr, Stats())
    assert ocr.calls == 3


def test_noisy_blocks_marks_only_the_webcam_corner(webcam_video, video):
    mask = noisy_blocks(webcam_video)
    assert mask.shape == (9, 16) and mask[0, 14:].all()
    assert mask.sum() <= 12 and not mask[3:, :].any() and not mask[:, :12].any()   # 小窗 6 块 + 一圈边
    assert not noisy_blocks(video).any()


def test_webcam_overlay_does_not_trigger_ocr(tmp_path, webcam_video):
    s, ocr = media(tmp_path, webcam_video), CountingOcr("some slide text here")
    frames_media(s, KEY, ocr, Stats())
    doc = s.read_json(KEY, FRAMES_FILE)
    assert ocr.calls == 3 and doc["keyframes"] == 30 and doc["ocred"] == 3


def test_slides_are_still_found_next_to_a_webcam(tmp_path, webcam_video):
    s = media(tmp_path, webcam_video)
    assert frames_media(s, KEY, RapidOcr(), Stats())["frames"] == 3
    fs = s.read_json(KEY, FRAMES_FILE)["frames"]
    assert [f["tsMs"] for f in fs] == [0, 20000, 40000]
    assert "Gradient descent" in fs[0]["text"] and "ReAct" in fs[1]["text"] and "Evaluation" in fs[2]["text"]


def test_same_slide_ignores_ocr_spacing_jitter():
    a = "YannDubois\nIntroduction to training\nLLMs for Al agents\nLLM AgentsMOOC series\nYannDubois|Sep.15th 2025"
    b = "YannDub\nIntroduction to training\nLLMs for Al agents\nLLM Agents MOOC series\nYannDubois |Sep.15th 2025"
    assert same_slide(a, b)
    assert not same_slide(a, "ChatGPT\nLLMs\nWhat are you?\nLLMs & chatbots took over the world")


def test_repeated_ocr_of_one_slide_is_kept_once(tmp_path, video, monkeypatch):
    class JitterOcr:
        calls = 0

        def read(self, img):
            self.calls += 1
            return "LLM Agents MOOC series" if self.calls % 2 else "LLM AgentsMOOC  series"

    monkeypatch.setattr("app.frames.changed", lambda *a, **k: True)     # 每个关键帧都送去识别
    s, ocr = media(tmp_path, video), JitterOcr()
    assert frames_media(s, KEY, ocr, Stats())["frames"] == 1 and ocr.calls == 30


def test_a_rerun_does_not_keep_images_of_an_earlier_run(tmp_path, video):
    s = media(tmp_path, video)
    (s.dir(KEY) / "frames").mkdir()
    (s.dir(KEY) / "frames" / "999999000.jpg").write_bytes(b"stale")
    frames_media(s, KEY, CountingOcr("some slide text here"), Stats())
    assert not (s.dir(KEY) / "frames" / "999999000.jpg").exists()


# ---- 计划 2 整体 review 之后补的 ----

def test_a_slide_that_builds_up_keeps_its_fullest_reading(tmp_path, video, monkeypatch):
    """逐条出现的幻灯片:后面的读数和前面的相近但更完整。只留最早那次的话,最后几条要点永远进不了索引。"""
    base = "Supervised finetuning (SFT)\nIdea: finetune the LLM with language modeling of the desired answers"

    class GrowingOcr:
        calls = 0

        def read(self, img):
            self.calls += 1
            return base if self.calls <= 10 else base + "\nNext: scalable data"

    monkeypatch.setattr("app.frames.changed", lambda *a, **k: True)     # 每个关键帧都送去识别
    s = media(tmp_path, video)
    assert frames_media(s, KEY, GrowingOcr(), Stats())["frames"] == 1
    frame = s.read_json(KEY, FRAMES_FILE)["frames"][0]
    assert frame["tsMs"] == 0 and frame["text"].endswith("Next: scalable data")       # 时间是第一次出现,文字是最完整的
    assert (s.dir(KEY) / frame["file"]).is_file()


# ---- 11 讲一起摄入时发现的 ----

def test_only_key_frames_come_back_even_when_the_decoder_ignores_the_skip_setting(tmp_path):
    """有的解码器(实测:VP9)不理会"只解码关键帧"的设置,会把每一帧都交出来。
    一讲 44 分钟、每秒 30 帧的 VP9 录像因此被当成 8 万个"关键帧"处理,截帧这一步跑了两个小时。"""
    path = tmp_path / "vp9.webm"
    out = av.open(str(path), "w")
    st = out.add_stream("libvpx-vp9", rate=2)
    st.width, st.height, st.pix_fmt = 320, 180, "yuv420p"
    st.codec_context.gop_size = 4
    for i in range(40):
        img = Image.new("RGB", (320, 180), (i * 6, 80, 80))
        for p in st.encode(av.VideoFrame.from_image(img)):
            out.mux(p)
    for p in st.encode():
        out.mux(p)
    out.close()
    ks = [ts for ts, _ in keyframes(path)]
    assert len(ks) == 10 and ks[:3] == [0, 2000, 4000]


# ---- 识别限流(docs/open-problems.md P1 第一层) ----

class SlowOcr:
    """记录同一时刻有几次识别在跑。"""

    def __init__(self):
        self.active = self.max_active = self.calls = 0
        self.guard = threading.Lock()

    def read(self, img):
        with self.guard:
            self.active += 1
            self.calls += 1
            self.max_active = max(self.max_active, self.active)
        try:
            time.sleep(0.05)
            return "some slide text here"
        finally:
            with self.guard:
                self.active -= 1


def test_ocr_calls_of_different_lectures_share_a_fixed_number_of_slots(tmp_path, video, monkeypatch):
    """11 讲一起摄入时每一路识别各开二十多个线程抢 24 个核,单讲从几分钟拖到几十分钟。"""
    s = media(tmp_path, video)
    for other in ("a" * 32, "b" * 32, "c" * 32):
        s.write_json(other, "meta.json", {"mediaKey": other, "audio": "audio.m4a", "video": "video.mp4", "durationMs": 60000})
        shutil.copy(video, s.dir(other) / "video.mp4")
    monkeypatch.setattr("app.frames.OCR_SLOTS", threading.BoundedSemaphore(2))
    ocr, stats = SlowOcr(), Stats()
    threads = [threading.Thread(target=frames_media, args=(s, k, ocr, stats)) for k in (KEY, "a" * 32, "b" * 32, "c" * 32)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert ocr.calls == 12 and ocr.max_active == 2 and stats.ocr_runs == 4


def test_rapid_ocr_reads_the_same_text_with_a_thread_limit():
    img = slide(SLIDES[0])
    assert "Gradient descent" in RapidOcr(threads=2).read(img)


def test_ocr_threads_do_not_spin_while_they_wait_for_work():
    """推理库的线程默认在两次计算之间空转等活。识别一张图是几十次小计算,线程就一直在空转:
    实测一个 8 线程的引擎占着 20 个核,真正在算的只有 4 个(docs/benchmarks.md 第 8 节)。"""
    engine = RapidOcr(threads=2)._engine
    for session in (engine.text_det.infer, engine.text_cls.infer, engine.text_rec.session):
        options = session.session.get_session_options()
        assert options.get_session_config_entry("session.intra_op.allow_spinning") == "0"
        assert options.intra_op_num_threads == 2


# ---- 识别分批并行(docs/superpowers/plans/2026-11-14-ocr-fanout.md) ----

class SlideOcr:
    """像真的识别一样能分清测试视频里的三页:按像素认出是哪一页,返回那一页的文字。"""

    def __init__(self):
        self.calls, self.refs = 0, [small(slide(t)) for t in SLIDES]

    def read(self, img):
        self.calls += 1
        now = small(img)
        return SLIDES[min(range(3), key=lambda i: int(np.abs(now - self.refs[i]).sum()))]


def read_all(s, ocr, stats):
    """挑帧 → 逐批识别 → 合并,分开三步做。返回批数。"""
    picked = frames.pick(s, KEY)
    for n in range(picked["batches"]):
        frames.ocr_batch(frames.Local(s), KEY, n, ocr, stats)
    frames.merge(s, KEY, stats)
    return picked["batches"]


def test_pick_saves_candidates_without_reading_them(tmp_path, video, monkeypatch):
    monkeypatch.setattr("app.frames.changed", lambda *a, **k: True)     # 30 个关键帧都是候选
    monkeypatch.setattr("app.frames.OCR_BATCH", 4)
    s, ocr = media(tmp_path, video), SlideOcr()
    assert frames.pick(s, KEY) == {"mediaKey": KEY, "cached": False, "candidates": 30, "batches": 8}
    doc = s.read_json(KEY, frames.CANDIDATES_FILE)
    assert doc["keyframes"] == 30 and doc["files"][:2] == ["000000000.png", "000002000.png"]   # 无损:识别读的就是它
    assert all((s.dir(KEY) / "frames" / f).is_file() for f in doc["files"])
    assert ocr.calls == 0 and s.read_json(KEY, FRAMES_FILE) is None
    assert frames.pick(s, KEY)["cached"] is True                        # 再挑一次:用已有的清单
    assert frames.Local(s).batch(KEY, 7) == {"files": ["000056000.png", "000058000.png"], "done": False}
    with pytest.raises(NotFound):
        frames.Local(s).batch(KEY, 8)


def test_batches_then_merge_give_the_same_frames_as_the_one_call_version(tmp_path, video, monkeypatch):
    monkeypatch.setattr("app.frames.changed", lambda *a, **k: True)
    monkeypatch.setattr("app.frames.OCR_BATCH", 4)
    one, steps = media(tmp_path / "one", video), media(tmp_path / "steps", video)
    frames_media(one, KEY, SlideOcr(), Stats())
    ocr, stats = SlideOcr(), Stats()
    assert read_all(steps, ocr, stats) == 8 and ocr.calls == 30 and stats.ocr_runs == 1
    expected = one.read_json(KEY, FRAMES_FILE)
    assert [f["tsMs"] for f in expected["frames"]] == [0, 20000, 40000]
    assert steps.read_json(KEY, FRAMES_FILE) == expected
    assert expected["keyframes"] == 30 and expected["ocred"] == 30


def test_a_batch_read_twice_gives_the_same_result(tmp_path, video, monkeypatch):
    monkeypatch.setattr("app.frames.OCR_BATCH", 2)
    s, ocr = media(tmp_path, video), SlideOcr()
    frames.pick(s, KEY)                                                  # 3 个候选,2 批
    first = frames.ocr_batch(frames.Local(s), KEY, 0, ocr, Stats())
    assert first == {"mediaKey": KEY, "batch": 0, "frames": 2, "cached": False} and ocr.calls == 2
    texts = s.read_json(KEY, "ocr.0.json")
    again = frames.ocr_batch(frames.Local(s), KEY, 0, ocr, Stats())      # 超时重投、重试
    assert again["cached"] is True and ocr.calls == 2 and s.read_json(KEY, "ocr.0.json") == texts


def test_merge_refuses_when_a_batch_is_missing(tmp_path, video, monkeypatch):
    monkeypatch.setattr("app.frames.OCR_BATCH", 2)
    s = media(tmp_path, video)
    frames.pick(s, KEY)
    frames.ocr_batch(frames.Local(s), KEY, 0, SlideOcr(), Stats())       # 第 1 批没有识别
    with pytest.raises(BadInput):
        frames.merge(s, KEY, Stats())
    assert s.read_json(KEY, FRAMES_FILE) is None                         # 不能悄悄少几张幻灯片


def test_merge_deletes_the_images_it_did_not_keep(tmp_path, video, monkeypatch):
    monkeypatch.setattr("app.frames.changed", lambda *a, **k: True)
    s = media(tmp_path, video)
    read_all(s, SlideOcr(), Stats())
    kept = {f["file"] for f in s.read_json(KEY, FRAMES_FILE)["frames"]}
    assert {"frames/" + p.name for p in (s.dir(KEY) / "frames").iterdir()} == kept and len(kept) == 3
    assert all(name.endswith(".jpg") for name in kept)                                 # 30 张候选的 PNG 都不在了
    assert s.read_json(KEY, frames.CANDIDATES_FILE) is None and s.read_json(KEY, "ocr.0.json") is None   # 中间文件不留


def test_media_without_video_has_no_batches(tmp_path):
    s = Store(tmp_path)
    s.write_json(KEY, "meta.json", {"mediaKey": KEY, "audio": "audio.m4a", "video": None, "durationMs": 1})
    assert frames.pick(s, KEY) == {"mediaKey": KEY, "cached": False, "candidates": 0, "batches": 0}
    assert frames.merge(s, KEY, Stats()) == {"mediaKey": KEY, "cached": False, "frames": 0}
    assert s.read_json(KEY, FRAMES_FILE)["frames"] == []


def test_a_finished_lecture_has_nothing_left_to_read(tmp_path, video, monkeypatch):
    s = media(tmp_path, video)
    frames_media(s, KEY, SlideOcr(), Stats())
    monkeypatch.setattr("app.frames.keyframes", lambda v: pytest.fail("已经处理好的讲次不该再解码视频"))
    assert frames.pick(s, KEY) == {"mediaKey": KEY, "cached": True, "candidates": 0, "batches": 0}
    assert frames.merge(s, KEY, Stats()) == {"mediaKey": KEY, "cached": True, "frames": 3}


def test_storing_texts_for_files_outside_the_batch_is_rejected(tmp_path, video, monkeypatch):
    monkeypatch.setattr("app.frames.OCR_BATCH", 2)
    s = media(tmp_path, video)
    frames.pick(s, KEY)
    local = frames.Local(s)
    with pytest.raises(BadInput):
        local.save(KEY, 0, {"000000000.png": "a", "../../meta.json": "b"})
    with pytest.raises(BadInput):
        local.save(KEY, 0, {"000000000.png": "only one of the two"})
    assert s.read_json(KEY, "ocr.0.json") is None
    local.save(KEY, 0, {"000000000.png": "first slide text", "000020000.png": "second slide text"})
    assert local.batch(KEY, 0)["done"] is True


@pytest.fixture
def api(tmp_path, video):
    s, stats = media(tmp_path, video), Stats()
    app.dependency_overrides.update({get_store: lambda: s, get_stats: lambda: stats, get_ocr: lambda: SlideOcr()})
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_the_three_steps_over_http(api, monkeypatch):
    monkeypatch.setattr("app.frames.OCR_BATCH", 2)
    assert api.post(f"/media/{KEY}/frames/pick").json() == {"mediaKey": KEY, "cached": False, "candidates": 3, "batches": 2}
    assert api.get(f"/media/{KEY}/frames/batches/1").json() == {"files": ["000040000.png"], "done": False}
    picture = api.get(f"/media/{KEY}/frames/000040000.png")                       # 识别副本就是这样取图的
    assert picture.status_code == 200 and picture.headers["content-type"] == "image/png"
    assert api.post(f"/media/{KEY}/frames/merge").status_code == 400            # 还有批没识别
    assert api.post(f"/media/{KEY}/frames/batches/0/ocr").json() == {"mediaKey": KEY, "batch": 0, "frames": 2, "cached": False}
    r = api.put(f"/media/{KEY}/frames/batches/1", json={"texts": {"000040000.png": SLIDES[2]}})   # 识别副本写回结果
    assert r.status_code == 200 and r.json() == {"stored": 1}
    assert api.post(f"/media/{KEY}/frames/merge").json() == {"mediaKey": KEY, "cached": False, "frames": 3}
    kept = api.get(f"/media/{KEY}/frames").json()["frames"]
    assert [f["tsMs"] for f in kept] == [0, 20000, 40000]
    assert [f["file"] for f in kept] == ["frames/000000000.jpg", "frames/000020000.jpg", "frames/000040000.jpg"]
    assert api.get(f"/media/{KEY}/frames/000040000.jpg").headers["content-type"] == "image/jpeg"   # 留下的转成 JPEG 给页面
    assert api.get(f"/media/{KEY}/frames/000040000.png").status_code == 404                        # 候选图不留
    assert api.get("/stats").json()["ocrRuns"] == 1


def test_the_steps_report_what_is_missing(api):
    missing = "f" * 32
    assert api.post(f"/media/{missing}/frames/pick").status_code == 404
    assert api.post(f"/media/{KEY}/frames/merge").status_code == 404            # 还没挑帧
    assert api.get(f"/media/{KEY}/frames/batches/0").status_code == 404
    api.post(f"/media/{KEY}/frames/pick")
    assert api.get(f"/media/{KEY}/frames/batches/9").status_code == 404
    assert api.post(f"/media/{KEY}/frames/batches/9/ocr").status_code == 404
    assert api.put(f"/media/{KEY}/frames/batches/0", json={"texts": {"nope.jpg": "x"}}).status_code == 400
    assert api.put(f"/media/{KEY}/frames/batches/0", json={"texts": {"000000000.png": 5}}).status_code == 422


def test_zero_ocr_slots_is_treated_as_one(monkeypatch):
    """MEDIA_OCR_SLOTS=0 用来建信号量的话,所有识别都会永远等下去。"""
    import importlib

    from app import config
    monkeypatch.setenv("MEDIA_OCR_SLOTS", "0")
    try:
        assert importlib.reload(config).OCR_SLOTS == 1
    finally:
        monkeypatch.undo()
        importlib.reload(config)


def test_the_ocr_reads_exactly_the_decoded_frame(tmp_path, video):
    """候选帧存成 JPEG 的话,识别会少认约 16% 的空格(实测,docs/benchmarks.md 第 10 节),词粘在一起检索就找不到。
    所以存成无损的 PNG:识别读到的像素和解码出来的那一帧一模一样。"""
    s = media(tmp_path, video)
    frames.pick(s, KEY)
    decoded = next(img for _, img in keyframes(s.dir(KEY) / "video.mp4"))
    stored = frames.Local(s).image(KEY, "000000000.png")
    assert np.array_equal(np.asarray(decoded.convert("RGB")), np.asarray(stored.convert("RGB")))

