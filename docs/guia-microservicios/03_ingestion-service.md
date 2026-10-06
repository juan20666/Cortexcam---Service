# 03 — `ingestion-service` (Python 3.12 · sin BD · health/métricas en :9101)

**Responsabilidad única:** convertir el stream de cada cámara activa en una secuencia de **frames JPEG muestreados** y publicarlos en Kafka. Además avisa si una cámara se cae o se recupera.
**Consume:** `camera.lifecycle.v1` (catálogo de cámaras, leído **desde el inicio**). **Publica:** `ingestion.frames.v1` (binario) y `ingestion.camera-health.v1`.
**Lee el video de MediaMTX** (`rtsp://localhost:8554/<streamPath>`), nunca de la cámara: la cámara solo atiende una conexión.
**No hace:** detectar nada, guardar nada, conocer credenciales.

Reemplaza a tu `IA_ENTRENADA.py` (captura de ventana Win32) y a `Detector_movimiento.py` (queda como *motion gate* opcional).

---

## 1. Árbol (✅ existe · 🆕 crear)

```
services/ingestion-service/
├── pyproject.toml ✅(movido en C-02)  ·  .env.example ✅  ·  Dockerfile 🆕  ·  README.md 🆕
├── src/ingestion/
│   ├── __init__.py 🆕
│   ├── domain/
│   │   ├── model/       value_objects.py · camera_entry.py · frame.py                      🆕
│   │   ├── policy/      sampling_policy.py                                                  🆕
│   │   └── errors.py                                                                        🆕
│   ├── application/
│   │   ├── ports/inbound/    apply_camera_snapshot.py · ingest_frame.py · report_stream_health.py        🆕
│   │   ├── ports/outbound/   frame_publisher.py · capture_supervisor.py · camera_catalog.py
│   │   │                     camera_health_publisher.py · motion_detector.py · clock.py · id_generator.py   🆕
│   │   └── usecases/         apply_camera_snapshot.py · ingest_frame.py · report_stream_health.py            🆕
│   ├── infrastructure/
│   │   ├── adapters/
│   │   │   ├── contract/    messages.py                                                                      🆕
│   │   │   ├── inbound/     kafka_camera_lifecycle_consumer.py                                               🆕
│   │   │   └── outbound/    kafka_frame_publisher.py · kafka_camera_health_publisher.py · opencv_capture_supervisor.py
│   │   │                    in_memory_camera_catalog.py · frame_diff_motion_detector.py · system_clock.py · uuid7_generator.py   🆕
│   │   ├── config/          settings.py ✅(movido) · kafka_config.py 🆕
│   │   ├── observability/   logging_setup.py · metrics.py 🆕
│   │   └── health.py 🆕
│   ├── bootstrap.py ✅(movido; reescríbelo)  ·  main.py 🆕
└── tests/ (domain/ · application/ · architecture/ · infrastructure/)
```
Cada carpeta lleva `__init__.py` (C-02).

---

## 2. `pyproject.toml`

```toml
[project]
name = "ingestion-service"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = [
  "confluent-kafka>=2.5",
  "opencv-python-headless>=4.10",
  "numpy>=1.26",
  "pydantic>=2.8",
  "pydantic-settings>=2.4",
  "prometheus-client>=0.20",
  "uuid-utils>=0.9",
]

[dependency-groups]
dev = ["pytest>=8", "ruff", "mypy", "import-linter>=2.0"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
[tool.hatch.build.targets.wheel]
packages = ["src/ingestion"]

[tool.pytest.ini_options]
pythonpath = ["src"]

[tool.importlinter]
root_package = "ingestion"
include_external_packages = true

[[tool.importlinter.contracts]]
name = "El dominio es puro"
type = "forbidden"
source_modules = ["ingestion.domain"]
forbidden_modules = ["ingestion.application", "ingestion.infrastructure", "cv2", "numpy", "confluent_kafka", "pydantic"]

[[tool.importlinter.contracts]]
name = "Application no conoce infraestructura ni librerías"
type = "forbidden"
source_modules = ["ingestion.application"]
forbidden_modules = ["ingestion.infrastructure", "cv2", "numpy", "confluent_kafka", "pydantic"]
```
Instalar y probar: `uv sync` · `uv run lint-imports` · `uv run pytest`.

`.env.example`:
```
SERVICE_NAME=ingestion-service
KAFKA_BOOTSTRAP_SERVERS=localhost:19092
MEDIAMTX_RTSP_BASE_URL=rtsp://localhost:8554
HTTP_PORT=9101
LOG_LEVEL=INFO
JPEG_QUALITY=80
MAX_FRAME_WIDTH=1280
OFFLINE_AFTER_SECONDS=15
MOTION_GATE_ENABLED=false
```

---

## 3. Dominio (stdlib pura)

```python
# domain/model/value_objects.py
from typing import NewType
CameraId = NewType("CameraId", str)
TenantId = NewType("TenantId", str)
```
```python
# domain/model/camera_entry.py
from dataclasses import dataclass
from enum import Enum
from .value_objects import CameraId, TenantId

class CameraStatus(str, Enum):
    PENDING = "PENDING"; ACTIVE = "ACTIVE"; OFFLINE = "OFFLINE"; DISABLED = "DISABLED"

@dataclass(frozen=True, slots=True)
class CameraEntry:
    camera_id: CameraId
    tenant_id: TenantId
    stream_path: str
    sampling_fps: float
    status: CameraStatus

    def should_capture(self) -> bool:
        # OFFLINE se sigue intentando para detectar la recuperación; PENDING aún no existe en MediaMTX
        return self.status in (CameraStatus.ACTIVE, CameraStatus.OFFLINE)
```
```python
# domain/model/frame.py
from dataclasses import dataclass
from datetime import datetime
from .value_objects import CameraId, TenantId

@dataclass(frozen=True, slots=True)
class Frame:
    frame_id: str
    camera_id: CameraId
    tenant_id: TenantId
    captured_at: datetime        # UTC
    jpeg: bytes                  # bytes opacos: el dominio no conoce numpy ni cv2
    width: int
    height: int
```
```python
# domain/policy/sampling_policy.py
from datetime import datetime

class SamplingPolicy:
    """¿Toca emitir otro frame de esta cámara? Regla pura, testeable sin reloj real."""
    def is_due(self, last_emit: datetime | None, now: datetime, fps: float) -> bool:
        if fps <= 0:
            return False
        return last_emit is None or (now - last_emit).total_seconds() >= 1.0 / fps
```

---

## 4. Aplicación

### 4.1 Puertos
```python
# application/ports/inbound/apply_camera_snapshot.py
from dataclasses import dataclass
from typing import Protocol

@dataclass(frozen=True, slots=True)
class CameraSnapshotCommand:
    camera_id: str; tenant_id: str; stream_path: str; sampling_fps: float; status: str; removed: bool

class ApplyCameraSnapshotUseCase(Protocol):
    def handle(self, cmd: CameraSnapshotCommand) -> None: ...
```
```python
# application/ports/inbound/ingest_frame.py
from typing import Protocol
from ingestion.domain.model.frame import Frame
from ingestion.domain.model.value_objects import CameraId

class IngestFrameUseCase(Protocol):
    def is_due(self, camera_id: CameraId) -> bool: ...   # barato: decide ANTES de decodificar/codificar
    def handle(self, frame: Frame) -> bool: ...          # True si se publicó
```
```python
# application/ports/inbound/report_stream_health.py
from dataclasses import dataclass
from typing import Protocol

@dataclass(frozen=True, slots=True)
class ReportStreamHealth:
    camera_id: str; tenant_id: str; online: bool; reason: str | None = None

class ReportStreamHealthUseCase(Protocol):
    def handle(self, cmd: ReportStreamHealth) -> None: ...
```
Puertos de salida (`Protocol`, un archivo por puerto):
```python
class FramePublisherPort(Protocol):        def publish(self, frame: Frame) -> None: ...
class CaptureSupervisorPort(Protocol):
    def start_or_update(self, entry: CameraEntry) -> None: ...
    def stop(self, camera_id: CameraId) -> None: ...
class CameraCatalogPort(Protocol):
    def get(self, camera_id: CameraId) -> CameraEntry | None: ...
    def put(self, entry: CameraEntry) -> None: ...
    def remove(self, camera_id: CameraId) -> None: ...
class CameraHealthPublisherPort(Protocol): def publish(self, camera_id: str, tenant_id: str, online: bool, reason: str | None, at: datetime) -> None: ...
class MotionDetectorPort(Protocol):        def has_motion(self, camera_id: CameraId, jpeg: bytes) -> bool: ...
class ClockPort(Protocol):                 def now(self) -> datetime: ...
class IdGeneratorPort(Protocol):           def new_id(self) -> str: ...
```

### 4.2 Casos de uso
```python
# application/usecases/apply_camera_snapshot.py
class ApplyCameraSnapshotService:
    def __init__(self, catalog: CameraCatalogPort, supervisor: CaptureSupervisorPort) -> None:
        self._catalog, self._supervisor = catalog, supervisor

    def handle(self, cmd: CameraSnapshotCommand) -> None:
        cid = CameraId(cmd.camera_id)
        if cmd.removed:
            self._catalog.remove(cid); self._supervisor.stop(cid); return
        entry = CameraEntry(cid, TenantId(cmd.tenant_id), cmd.stream_path, cmd.sampling_fps, CameraStatus(cmd.status))
        self._catalog.put(entry)
        if entry.should_capture():
            self._supervisor.start_or_update(entry)
        else:
            self._supervisor.stop(cid)
```
```python
# application/usecases/ingest_frame.py
class IngestFrameService:
    def __init__(self, catalog, publisher, clock, sampling: SamplingPolicy, motion: MotionDetectorPort | None = None) -> None:
        self._catalog, self._publisher, self._clock = catalog, publisher, clock
        self._sampling, self._motion = sampling, motion
        self._last_emit: dict[CameraId, datetime] = {}

    def is_due(self, camera_id: CameraId) -> bool:
        entry = self._catalog.get(camera_id)
        if entry is None or not entry.should_capture():
            return False
        return self._sampling.is_due(self._last_emit.get(camera_id), self._clock.now(), entry.sampling_fps)

    def handle(self, frame: Frame) -> bool:
        self._last_emit[frame.camera_id] = self._clock.now()          # se marca aunque el motion gate lo descarte
        if self._motion is not None and not self._motion.has_motion(frame.camera_id, frame.jpeg):
            return False
        self._publisher.publish(frame)
        return True
```
```python
# application/usecases/report_stream_health.py — solo publica TRANSICIONES (online↔offline)
class ReportStreamHealthService:
    def __init__(self, publisher: CameraHealthPublisherPort, clock: ClockPort) -> None:
        self._publisher, self._clock = publisher, clock
        self._state: dict[str, bool] = {}

    def handle(self, cmd: ReportStreamHealth) -> None:
        if self._state.get(cmd.camera_id) is cmd.online:
            return
        self._state[cmd.camera_id] = cmd.online
        self._publisher.publish(cmd.camera_id, cmd.tenant_id, cmd.online, cmd.reason, self._clock.now())
```

---

## 5. Infraestructura

### 5.1 Configuración
```python
# infrastructure/config/settings.py
from pathlib import Path
from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

_SECRETS = "/run/secrets" if Path("/run/secrets").exists() else None

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", secrets_dir=_SECRETS, extra="ignore")

    service_name: str = "ingestion-service"
    log_level: str = "INFO"
    http_port: int = 9101

    kafka_bootstrap_servers: str
    kafka_security_protocol: str = "PLAINTEXT"
    kafka_sasl_mechanism: str | None = None
    kafka_sasl_username: str | None = None
    kafka_sasl_password: SecretStr | None = None

    lifecycle_topic: str = "cortexcam.camera.lifecycle.v1"
    frames_topic: str = "cortexcam.ingestion.frames.v1"
    health_topic: str = "cortexcam.ingestion.camera-health.v1"

    mediamtx_rtsp_base_url: str = "rtsp://localhost:8554"
    jpeg_quality: int = 80
    max_frame_width: int = 1280
    offline_after_seconds: float = 15.0
    motion_gate_enabled: bool = False
```
```python
# infrastructure/config/kafka_config.py
def kafka_client_config(s: Settings) -> dict:
    cfg = {"bootstrap.servers": s.kafka_bootstrap_servers, "security.protocol": s.kafka_security_protocol}
    if s.kafka_sasl_mechanism:
        cfg |= {"sasl.mechanism": s.kafka_sasl_mechanism, "sasl.username": s.kafka_sasl_username,
                "sasl.password": s.kafka_sasl_password.get_secret_value()}
    return cfg
```

### 5.2 Contrato (ACL) — `adapters/contract/messages.py`
```python
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

class Contract(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

class DetectorData(Contract):
    kind: str; enabled: bool; min_confidence: float

class CameraSnapshotData(Contract):
    change: str; camera_id: str; tenant_id: str; name: str; status: str
    stream_path: str; sampling_fps: float; detectors: list[DetectorData] = []

class CameraLifecycleEnvelope(Contract):
    id: str; type: str; data: CameraSnapshotData
```

### 5.3 Entrada: catálogo de cámaras
**Detalle crítico:** el catálogo vive en memoria, así que al arrancar hay que leer el tópico **completo**. Por eso el *group id* es **único por instancia** y no se hace *commit*: siempre empieza en `earliest`.
```python
# adapters/inbound/kafka_camera_lifecycle_consumer.py
class KafkaCameraLifecycleConsumer(threading.Thread):
    def __init__(self, kafka_cfg: dict, topic: str, use_case: ApplyCameraSnapshotUseCase, stop: threading.Event) -> None:
        super().__init__(name="camera-lifecycle-consumer", daemon=True)
        self._cfg, self._topic, self._uc, self._stop_evt = kafka_cfg, topic, use_case, stop
        self.ready = threading.Event()

    def run(self) -> None:
        c = Consumer({**self._cfg, "group.id": f"ingestion.camera-lifecycle.{uuid.uuid4()}",
                      "auto.offset.reset": "earliest", "enable.auto.commit": False})
        c.subscribe([self._topic], on_assign=lambda *_: self.ready.set())
        try:
            while not self._stop_evt.is_set():
                msg = c.poll(1.0)
                if msg is None or msg.value() is None:        # sin mensaje o tombstone
                    continue
                if msg.error():
                    log.warning("kafka error: %s", msg.error()); continue
                try:
                    env = CameraLifecycleEnvelope.model_validate_json(msg.value())
                except ValidationError:
                    log.warning("snapshot inválido ignorado"); continue
                d = env.data
                self._uc.handle(CameraSnapshotCommand(d.camera_id, d.tenant_id, d.stream_path, d.sampling_fps,
                                                      d.status, removed=(d.change == "REMOVED")))
        finally:
            c.close()
```

### 5.4 Captura RTSP — `adapters/outbound/opencv_capture_supervisor.py`
```python
class OpenCvCaptureSupervisor:                       # implementa CaptureSupervisorPort
    def __init__(self, s: Settings, ingest: IngestFrameUseCase, health: ReportStreamHealthUseCase,
                 clock: ClockPort, ids: IdGeneratorPort) -> None:
        os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "rtsp_transport;tcp")     # TCP: sin pérdida de paquetes
        self._s, self._ingest, self._health, self._clock, self._ids = s, ingest, health, clock, ids
        self._workers: dict[str, tuple[_Worker, threading.Event]] = {}
        self._lock = threading.Lock()

    def start_or_update(self, entry: CameraEntry) -> None:
        with self._lock:
            cur = self._workers.get(entry.camera_id)
            if cur and cur[0].stream_path == entry.stream_path:
                return                                    # el fps lo lee el use case del catálogo; no hace falta reiniciar
            if cur: cur[1].set()
            stop = threading.Event()
            w = _Worker(entry, self._s, self._ingest, self._health, self._clock, self._ids, stop)
            self._workers[entry.camera_id] = (w, stop); w.start()

    def stop(self, camera_id: CameraId) -> None:
        with self._lock:
            cur = self._workers.pop(camera_id, None)
            if cur: cur[1].set()

    def stop_all(self) -> None:
        for cid in list(self._workers): self.stop(CameraId(cid))


class _Worker(threading.Thread):
    def __init__(self, entry, s, ingest, health, clock, ids, stop):
        super().__init__(name=f"capture-{entry.camera_id[:8]}", daemon=True)
        self.stream_path = entry.stream_path
        self._cid, self._tid = entry.camera_id, entry.tenant_id
        self._url = f"{s.mediamtx_rtsp_base_url}/{entry.stream_path}"
        self._s, self._ingest, self._health, self._clock, self._ids, self._stop = s, ingest, health, clock, ids, stop

    def run(self) -> None:
        backoff, down_since = 1.0, None
        while not self._stop.is_set():
            cap = cv2.VideoCapture(self._url, cv2.CAP_FFMPEG)
            got_frame = False
            try:
                if cap.isOpened():
                    while not self._stop.is_set():
                        if not cap.grab():                 # grab es barato: vacía el buffer y mantiene baja latencia
                            break
                        if not self._ingest.is_due(self._cid):
                            continue
                        ok, img = cap.retrieve()
                        if not ok:
                            continue
                        self._ingest.handle(self._to_frame(img))
                        if not got_frame:
                            got_frame, down_since, backoff = True, None, 1.0
                            self._health.handle(ReportStreamHealth(self._cid, self._tid, True))
            finally:
                cap.release()
            if self._stop.is_set():
                return
            down_since = down_since or time.monotonic()
            if time.monotonic() - down_since >= self._s.offline_after_seconds:
                self._health.handle(ReportStreamHealth(self._cid, self._tid, False, "stream_unavailable"))
            self._stop.wait(backoff)
            backoff = min(backoff * 2, 30.0)

    def _to_frame(self, img) -> Frame:
        h, w = img.shape[:2]
        if w > self._s.max_frame_width:
            scale = self._s.max_frame_width / w
            img = cv2.resize(img, (self._s.max_frame_width, int(h * scale)), interpolation=cv2.INTER_AREA)
            h, w = img.shape[:2]
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, self._s.jpeg_quality])
        if not ok:
            raise RuntimeError("no se pudo codificar el frame")
        return Frame(self._ids.new_id(), self._cid, self._tid, self._clock.now(), buf.tobytes(), w, h)
```
*Cómo funciona:* un hilo por cámara abre el RTSP de MediaMTX; `grab()` consume todos los frames pero solo se decodifica y codifica (`retrieve` + JPEG) cuando el muestreo lo permite. Si se pierde el stream reintenta con *backoff* (1→30 s) y, pasados `OFFLINE_AFTER_SECONDS`, publica `OFFLINE`; al volver a recibir un frame publica `ONLINE` (el use case filtra repeticiones).

### 5.5 Salida: Kafka
```python
# adapters/outbound/kafka_frame_publisher.py
class KafkaFramePublisher:
    def __init__(self, kafka_cfg: dict, topic: str) -> None:
        self._topic = topic
        self._p = Producer({**kafka_cfg, "linger.ms": 5, "message.max.bytes": 2_097_152,
                            "queue.buffering.max.messages": 2000, "compression.type": "none"})   # el JPEG ya está comprimido

    def publish(self, f: Frame) -> None:
        headers = [("frame-id", f.frame_id.encode()), ("camera-id", f.camera_id.encode()), ("tenant-id", f.tenant_id.encode()),
                   ("captured-at", f.captured_at.isoformat().replace("+00:00", "Z").encode()),
                   ("content-type", b"image/jpeg"), ("width", str(f.width).encode()), ("height", str(f.height).encode())]
        try:
            self._p.produce(self._topic, key=f.camera_id.encode(), value=f.jpeg, headers=headers, on_delivery=self._cb)
            FRAMES_PUBLISHED.inc()
        except BufferError:
            FRAMES_DROPPED.inc()                           # cola llena: perder un frame es aceptable (flujo continuo)
        self._p.poll(0)

    @staticmethod
    def _cb(err, _msg) -> None:
        if err: PUBLISH_ERRORS.inc()

    def flush(self) -> None: self._p.flush(5)
```
```python
# adapters/outbound/kafka_camera_health_publisher.py — sobre CloudEvents
class KafkaCameraHealthPublisher:
    def __init__(self, kafka_cfg, topic, service_name, ids: IdGeneratorPort) -> None:
        self._topic, self._source, self._ids = topic, f"urn:cortexcam:{service_name}", ids
        self._p = Producer({**kafka_cfg, "enable.idempotence": True, "acks": "all"})

    def publish(self, camera_id, tenant_id, online, reason, at) -> None:
        ts = at.isoformat().replace("+00:00", "Z")
        env = {"specversion": "1.0", "id": self._ids.new_id(), "type": self._topic, "source": self._source,
               "subject": f"camera/{camera_id}", "time": ts, "datacontenttype": "application/json", "tenantid": tenant_id,
               "data": {"cameraId": camera_id, "tenantId": tenant_id, "status": "ONLINE" if online else "OFFLINE",
                        "reason": reason, "observedAt": ts}}
        self._p.produce(self._topic, key=camera_id.encode(), value=json.dumps(env).encode())
        self._p.flush(2)
```
Adaptadores pequeños: `InMemoryCameraCatalog` (dict + `threading.Lock`), `SystemClock` (`datetime.now(timezone.utc)`), `Uuid7Generator` (`str(uuid_utils.uuid7())`).

**Motion gate opcional** (`frame_diff_motion_detector.py`, tu `Detector_movimiento.py` reconvertido): decodifica el JPEG, gris + `GaussianBlur((21,21))`, `absdiff` con el frame anterior de esa cámara, umbral 25, `dilate`, y devuelve `True` si algún contorno supera ~800 px² (escalado a la resolución). Se activa con `MOTION_GATE_ENABLED=true`. Bájale el umbral de área si la escena es amplia.

### 5.6 Observabilidad y *health* (se copia igual a los detectores)
```python
# infrastructure/observability/metrics.py
FRAMES_PUBLISHED = Counter("ingestion_frames_published_total", "Frames publicados")
FRAMES_DROPPED   = Counter("ingestion_frames_dropped_total", "Frames descartados por cola llena")
PUBLISH_ERRORS   = Counter("ingestion_publish_errors_total", "Errores de entrega a Kafka")
```
```python
# infrastructure/health.py
class _H(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/healthz":   self._send(200, b"ok")
        elif self.path == "/readyz":  ok = self.server.ready(); self._send(200 if ok else 503, b"ready" if ok else b"not ready")
        elif self.path == "/metrics": self._send(200, generate_latest(), CONTENT_TYPE_LATEST)
        else:                         self._send(404, b"")
    def _send(self, code, body, ctype="text/plain"):
        self.send_response(code); self.send_header("Content-Type", ctype); self.end_headers(); self.wfile.write(body)
    def log_message(self, *_): pass

def start_health_server(port: int, ready) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer(("0.0.0.0", port), _H); srv.ready = ready
    threading.Thread(target=srv.serve_forever, daemon=True).start(); return srv
```
`logging_setup.py`: un `Formatter` que emite JSON (`ts`, `level`, `service`, `msg`, y `exc` si hay) a stdout.

### 5.7 Composition root y arranque
```python
# bootstrap.py — ÚNICO sitio que conecta todo
@dataclass
class App:
    settings: Settings; consumer: KafkaCameraLifecycleConsumer; supervisor: OpenCvCaptureSupervisor
    frame_publisher: KafkaFramePublisher; stop: threading.Event

def build() -> App:
    s = Settings(); setup_logging(s)
    kcfg = kafka_client_config(s)
    clock, ids = SystemClock(), Uuid7Generator()
    catalog = InMemoryCameraCatalog()
    frame_pub = KafkaFramePublisher(kcfg, s.frames_topic)
    health = ReportStreamHealthService(KafkaCameraHealthPublisher(kcfg, s.health_topic, s.service_name, ids), clock)
    motion = FrameDiffMotionDetector() if s.motion_gate_enabled else None
    ingest = IngestFrameService(catalog, frame_pub, clock, SamplingPolicy(), motion)
    supervisor = OpenCvCaptureSupervisor(s, ingest, health, clock, ids)
    stop = threading.Event()
    consumer = KafkaCameraLifecycleConsumer(kcfg, s.lifecycle_topic, ApplyCameraSnapshotService(catalog, supervisor), stop)
    return App(s, consumer, supervisor, frame_pub, stop)
```
```python
# main.py
def main() -> None:
    app = build()
    start_health_server(app.settings.http_port, ready=app.consumer.ready.is_set)
    signal.signal(signal.SIGTERM, lambda *_: app.stop.set()); signal.signal(signal.SIGINT, lambda *_: app.stop.set())
    app.consumer.start()
    app.stop.wait()                       # bloquea hasta SIGINT/SIGTERM
    app.supervisor.stop_all(); app.frame_publisher.flush()

if __name__ == "__main__":
    main()
```
Ejecutar: `uv run python -m ingestion.main`.

**Dockerfile (cuando lo necesites):** `python:3.12-slim` → `pip install uv` → `uv sync --frozen --no-dev` → usuario no-root → `CMD ["uv","run","python","-m","ingestion.main"]`. Sin secretos en la imagen.

---

## 6. Pruebas
| Nivel | Test |
|---|---|
| Dominio | `SamplingPolicy`: primer frame siempre; a 2 fps no antes de 0.5 s; `fps<=0` nunca |
| Use case (fakes) | `IngestFrameService` con `FakeClock`/`FakePublisher`: no publica si no está `ACTIVE`; respeta el fps; con motion gate que dice "no", no publica. `ApplyCameraSnapshotService`: `ACTIVE` → `start_or_update`; `DISABLED`/`PENDING`/`removed` → `stop`. `ReportStreamHealthService`: dos `OFFLINE` seguidos publican uno |
| Contrato | `CameraLifecycleEnvelope.model_validate_json(...)` sobre `contracts/events/examples/camera-lifecycle.v1.json` |
| Arquitectura | `uv run lint-imports` en verde |

## 7. Probar a mano
1. `camera-service` con una cámara `ACTIVE` (02 §9) y la cámara falsa publicando (09 §2).
2. `uv run python -m ingestion.main`. En el log verás el worker abrir `rtsp://localhost:8554/cam-…`.
3. Redpanda Console → `cortexcam.ingestion.frames.v1` → llegan mensajes con cabeceras `camera-id`, `captured-at`…
4. Guardar un frame para verlo:
   ```bat
   docker compose -f platform\docker-compose.yml exec -T redpanda rpk topic consume cortexcam.ingestion.frames.v1 -n 1 -f "%v" > frame.raw
   ```
   (o desde Console: *Download value*). Ábrelo como `.jpg`.
5. Apaga MediaMTX 20 s → aparece `OFFLINE` en `camera-health.v1` y en `camera-service` el estado pasa a `OFFLINE`; al encenderlo vuelve a `ACTIVE`.

## 8. Listo cuando
- [ ] `uv run pytest` y `uv run lint-imports` pasan.
- [ ] Frames llegan al fps configurado (±10 %) y con `captured-at` en UTC.
- [ ] Deshabilitar la cámara en `camera-service` detiene la captura en segundos.
- [ ] Reiniciar el servicio re-lee el catálogo completo (grupo único + `earliest`).
- [ ] `/healthz`, `/readyz` y `/metrics` responden en :9101.
