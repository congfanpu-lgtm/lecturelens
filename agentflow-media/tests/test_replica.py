"""An OCR replica: a media-service process without the files (MEDIA_SOURCE set), possibly on another machine.
Here the media service that holds the files is the FastAPI app behind a TestClient."""
import pytest
from fastapi.testclient import TestClient

from app import config, frames
from app.main import app, get_ocr, get_source, get_stats, get_store
from app.stats import Stats
from app.store import BadInput, NotFound, Store
from tests.fakes import KEY
from tests.test_frames import SLIDES, SlideOcr, media, video  # noqa: F401  (video is a fixture)


@pytest.fixture
def service(tmp_path, video, monkeypatch):   # noqa: F811
    monkeypatch.setattr("app.frames.OCR_BATCH", 2)
    store = media(tmp_path, video)
    app.dependency_overrides.update({get_store: lambda: store, get_stats: lambda: Stats(), get_ocr: lambda: SlideOcr()})
    client = TestClient(app)
    client.store = store
    yield client
    app.dependency_overrides.clear()


def replica(client):
    def send(method, url, body):
        assert url.startswith("http://media:8010/")
        r = client.request(method, url.removeprefix("http://media:8010"), content=body,
                           headers={"Content-Type": "application/json"} if body else None)   # as frames._http sends it
        return r.status_code, r.content
    return frames.Remote("http://media:8010/", send)


def test_a_replica_reads_a_batch_through_the_media_service(service):
    assert service.post(f"/media/{KEY}/frames/pick").json()["batches"] == 2
    ocr = SlideOcr()
    out = frames.ocr_batch(replica(service), KEY, 0, ocr, Stats())
    assert out == {"mediaKey": KEY, "batch": 0, "frames": 2, "cached": False} and ocr.calls == 2
    assert service.store.read_json(KEY, "ocr.0.json") == {"texts": {"000000000.jpg": SLIDES[0], "000020000.jpg": SLIDES[1]}}
    frames.ocr_batch(replica(service), KEY, 1, ocr, Stats())
    assert service.post(f"/media/{KEY}/frames/merge").json()["frames"] == 3


def test_a_replica_leaves_a_finished_batch_alone(service):
    service.post(f"/media/{KEY}/frames/pick")
    ocr = SlideOcr()
    frames.ocr_batch(replica(service), KEY, 0, ocr, Stats())
    assert frames.ocr_batch(replica(service), KEY, 0, ocr, Stats())["cached"] is True and ocr.calls == 2


def test_a_replica_passes_on_what_the_media_service_says(service):
    with pytest.raises(NotFound):                                   # 没有这个媒体:worker 不用重试
        frames.ocr_batch(replica(service), "f" * 32, 0, SlideOcr(), Stats())
    service.post(f"/media/{KEY}/frames/pick")
    with pytest.raises(NotFound):                                   # 没有这一批
        frames.ocr_batch(replica(service), KEY, 5, SlideOcr(), Stats())
    with pytest.raises(BadInput):                                   # 写回的文件名不是这一批的
        replica(service).save(KEY, 0, {"000040000.jpg": "x"})
    broken = frames.Remote("http://media:8010", lambda method, url, body: (500, b'{"detail":"OSError: disk full"}'))
    with pytest.raises(RuntimeError, match="disk full"):            # 媒体服务自己出了故障:worker 可以重试
        frames.ocr_batch(broken, KEY, 0, SlideOcr(), Stats())


def test_the_ocr_endpoint_goes_through_the_media_service_when_this_process_is_a_replica(tmp_path, monkeypatch):
    store = Store(tmp_path)
    assert isinstance(get_source(store), frames.Local)
    monkeypatch.setattr(config, "SOURCE", "http://172.17.0.1:8010")
    source = get_source(store)
    assert isinstance(source, frames.Remote) and source.base == "http://172.17.0.1:8010"
