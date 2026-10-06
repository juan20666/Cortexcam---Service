# 04 — Detectores (Python 3.12 · sin BD): person · weapon · vehicle · plate

Los cuatro son **el mismo servicio con distinto modelo y distinto vocabulario**. Por eso aquí se describe **`person-detection-service` completo** y luego, para cada uno de los otros tres, solo lo que cambia (§4–§6). Copia la carpeta, renombra el paquete y aplica los cambios.

**Responsabilidad única:** leer frames, correr un modelo, publicar lo que encontró. **No decide si hay alerta** (eso es de `alert-service`).
**Consume:** `ingestion.frames.v1` (grupo `<servicio>.frames`) y `camera.lifecycle.v1` (grupo único por instancia, desde el inicio).
**Publica:** `detection.<tipo>.v1`.

| Servicio | Paquete | Puerto | Tópico de salida | Modelo base |
|---|---|---|---|---|
| person-detection | `person_detection` | 9102 | `cortexcam.detection.person-detected.v1` | `yolov8n.pt` (COCO) |
| weapon-detection | `weapon_detection` | 9103 | `cortexcam.detection.weapon-detected.v1` | tu `best.pt` |
| vehicle-detection | `vehicle_detection` | 9104 | `cortexcam.detection.vehicle-detected.v1` | `yolov8n.pt` (COCO) |
| plate-recognition | `plate_recognition` | 9105 | `cortexcam.detection.plate-recognized.v1` | `fast-alpr` |

---

## 1. Árbol de `person-detection-service`

```
services/person-detection-service/
├── pyproject.toml ✅ · .env.example ✅ · Dockerfile 🆕 · README.md 🆕
├── models/                      ← vacío en git; aquí copias yolov8n.pt (está en .gitignore)
├── src/person_detection/
│   ├── domain/
│   │   ├── model/    value_objects.py · frame.py · detection.py · detector_config.py · frame_detections.py     🆕
│   │   ├── policy/   stale_frame_policy.py                                                                        🆕
│   │   └── errors.py                                                                                              🆕
│   ├── application/
│   │   ├── ports/inbound/    process_frame.py · apply_camera_config.py                                           🆕
│   │   ├── ports/outbound/   object_detector.py · detection_publisher.py · camera_config.py · clock.py · id_generator.py   🆕
│   │   └── usecases/         process_frame.py · apply_camera_config.py                                           🆕
│   ├── infrastructure/
│   │   ├── adapters/
│   │   │   ├── contract/    messages.py (igual que ingestion)                                                    🆕
│   │   │   ├── inbound/     kafka_frame_consumer.py · kafka_camera_lifecycle_consumer.py                         🆕
│   │   │   └── outbound/    ultralytics_object_detector.py · kafka_detection_publisher.py · in_memory_camera_config.py
│   │   │                    system_clock.py · uuid7_generator.py                                                  🆕
│   │   ├── config/          settings.py ✅ · kafka_config.py 🆕
│   │   ├── observability/   logging_setup.py · metrics.py 🆕
│   │   ├── integrity.py 🆕  ·  health.py 🆕 (igual que ingestion)
│   ├── bootstrap.py ✅(reescribir)  ·  main.py 🆕
└── tests/
```
`pyproject.toml`: como el de ingestion, cambiando `name`, `root_package = "person_detection"` y las dependencias a:
`confluent-kafka, opencv-python-headless, numpy, ultralytics, pydantic, pydantic-settings, prometheus-client, uuid-utils`.
(`ultralytics` instala PyTorch; para CPU en Windows/Linux usa el índice CPU de PyTorch si quieres una instalación liviana.)

`.env.example`:
```
SERVICE_NAME=person-detection-service
KAFKA_BOOTSTRAP_SERVERS=localhost:19092
HTTP_PORT=9102
DETECTOR_KIND=PERSON
MODEL_PATH=models/yolov8n.pt
MODEL_SHA256=
MODEL_VERSION=yolov8n-coco@1
LABEL_MAP={"person":"PERSON"}
OUTPUT_TOPIC=cortexcam.detection.person-detected.v1
MAX_FRAME_AGE_MS=1500
IMGSZ=640
```
SHA-256 del modelo en Windows: `certutil -hashfile models\yolov8n.pt SHA256` (pega el valor en `MODEL_SHA256`; vacío = solo para desarrollo, se registra una advertencia).

---

## 2. Dominio (stdlib pura)

```python
# domain/model/value_objects.py
from dataclasses import dataclass
from typing import NewType
from ..errors import DomainValidationError

CameraId = NewType("CameraId", str)
TenantId = NewType("TenantId", str)

@dataclass(frozen=True, slots=True)
class Confidence:
    value: float
    def __post_init__(self) -> None:
        if not (0.0 <= self.value <= 1.0):
            raise DomainValidationError(f"confidence fuera de rango: {self.value}")

@dataclass(frozen=True, slots=True)
class BoundingBox:
    """Normalizada 0..1: independiente de la resolución del frame (V-02)."""
    x1: float; y1: float; x2: float; y2: float
    def __post_init__(self) -> None:
        if not (0 <= self.x1 < self.x2 <= 1 and 0 <= self.y1 < self.y2 <= 1):
            raise DomainValidationError("caja inválida")
```
```python
# domain/model/detection.py
class DetectionLabel(str, Enum):
    PERSON = "person"                       # el valor es lo que viaja en el evento

@dataclass(frozen=True, slots=True)
class Detection:
    label: DetectionLabel
    confidence: Confidence
    box: BoundingBox
    attributes: dict[str, str | float | bool] = field(default_factory=dict)

# domain/model/frame.py  → igual que en ingestion (frame_id, camera_id, tenant_id, captured_at, jpeg, width, height)
# domain/model/detector_config.py
@dataclass(frozen=True, slots=True)
class DetectorConfig:
    enabled: bool
    min_confidence: Confidence

# domain/model/frame_detections.py
@dataclass(frozen=True, slots=True)
class FrameDetections:
    frame: Frame
    model_version: str
    detections: tuple[Detection, ...]
```
```python
# domain/policy/stale_frame_policy.py — backpressure como regla de negocio (V-04)
class StaleFramePolicy:
    def __init__(self, max_age_ms: int) -> None: self._max = max_age_ms
    def is_stale(self, captured_at: datetime, now: datetime) -> bool:
        return (now - captured_at).total_seconds() * 1000 > self._max
```

---

## 3. Aplicación

### 3.1 Puertos
```python
# ports/outbound/object_detector.py
class ObjectDetectorPort(Protocol):
    def detect(self, frame: Frame, min_confidence: Confidence) -> list[Detection]: ...
# ports/outbound/detection_publisher.py
class DetectionPublisherPort(Protocol):
    def publish(self, result: FrameDetections) -> None: ...
# ports/outbound/camera_config.py
class CameraConfigPort(Protocol):
    def get(self, camera_id: CameraId) -> DetectorConfig | None: ...
    def put(self, camera_id: CameraId, cfg: DetectorConfig) -> None: ...
    def remove(self, camera_id: CameraId) -> None: ...
# clock.py / id_generator.py como en ingestion

# ports/inbound/process_frame.py
class Outcome(str, Enum):
    PUBLISHED = "published"; NO_DETECTION = "no_detection"; STALE = "stale"; DISABLED = "disabled"
class ProcessFrameUseCase(Protocol):
    def handle(self, frame: Frame) -> Outcome: ...

# ports/inbound/apply_camera_config.py
@dataclass(frozen=True, slots=True)
class CameraConfigCommand:
    camera_id: str; status: str; removed: bool
    detectors: tuple[tuple[str, bool, float], ...]        # (kind, enabled, min_confidence)
class ApplyCameraConfigUseCase(Protocol):
    def handle(self, cmd: CameraConfigCommand) -> None: ...
```

### 3.2 Casos de uso
```python
# usecases/process_frame.py
class ProcessFrameService:
    def __init__(self, detector, publisher, configs, clock, stale: StaleFramePolicy, model_version: str) -> None:
        self._detector, self._publisher, self._configs = detector, publisher, configs
        self._clock, self._stale, self._model_version = clock, stale, model_version

    def handle(self, frame: Frame) -> Outcome:
        if self._stale.is_stale(frame.captured_at, self._clock.now()):
            return Outcome.STALE                                   # mejor perder el frame que ir atrasado
        cfg = self._configs.get(frame.camera_id)
        if cfg is None or not cfg.enabled:
            return Outcome.DISABLED
        found = [d for d in self._detector.detect(frame, cfg.min_confidence)
                 if d.confidence.value >= cfg.min_confidence.value]
        if not found:
            return Outcome.NO_DETECTION
        self._publisher.publish(FrameDetections(frame, self._model_version, tuple(found)))
        return Outcome.PUBLISHED

# usecases/apply_camera_config.py — read model local alimentado por camera.lifecycle
class ApplyCameraConfigService:
    def __init__(self, configs: CameraConfigPort, kind: str) -> None:   # kind: "PERSON", "WEAPON", ...
        self._configs, self._kind = configs, kind

    def handle(self, cmd: CameraConfigCommand) -> None:
        cid = CameraId(cmd.camera_id)
        if cmd.removed:
            self._configs.remove(cid); return
        mine = next((d for d in cmd.detectors if d[0] == self._kind), None)
        active_camera = cmd.status in ("ACTIVE", "OFFLINE")
        if mine is None or not active_camera or not mine[1]:
            self._configs.put(cid, DetectorConfig(False, Confidence(0.5)))
        else:
            self._configs.put(cid, DetectorConfig(True, Confidence(mine[2])))
```

---

## 4. Infraestructura

### 4.1 Configuración
```python
class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", secrets_dir=_SECRETS, extra="ignore")
    service_name: str = "person-detection-service"
    log_level: str = "INFO"
    http_port: int = 9102
    # kafka_* igual que ingestion
    frames_topic: str = "cortexcam.ingestion.frames.v1"
    lifecycle_topic: str = "cortexcam.camera.lifecycle.v1"
    output_topic: str
    detector_kind: str
    model_path: Path
    model_sha256: str = ""
    model_version: str
    label_map: dict[str, str] = {}               # clase del modelo -> etiqueta del dominio (JSON en el .env)
    max_frame_age_ms: int = 1500
    imgsz: int = 640
    device: str | None = None                    # "cpu", "cuda:0"…
    publish_enabled: bool = True                 # False = modo sombra (registra, no publica)
```

### 4.2 Integridad del modelo (cadena de suministro, V-03)
```python
# infrastructure/integrity.py
def verify_sha256(path: Path, expected: str) -> None:
    if not expected:
        log.warning("MODEL_SHA256 vacío: modelo sin verificar (solo desarrollo)"); return
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    if h.hexdigest().lower() != expected.lower():
        raise RuntimeError(f"El modelo {path.name} no coincide con MODEL_SHA256")
```

### 4.3 Adaptador de inferencia (el único que conoce `ultralytics`, `cv2`, `numpy`)
```python
# adapters/outbound/ultralytics_object_detector.py
class UltralyticsObjectDetector:                      # implementa ObjectDetectorPort
    def __init__(self, model_path: Path, sha256: str, label_map: dict[str, DetectionLabel], imgsz: int, device: str | None) -> None:
        verify_sha256(model_path, sha256)
        from ultralytics import YOLO                  # import perezoso: arranque más rápido y tests sin torch
        self._model = YOLO(str(model_path))
        self._label_map, self._imgsz, self._device = label_map, imgsz, device

    def detect(self, frame: Frame, min_confidence: Confidence) -> list[Detection]:
        img = cv2.imdecode(np.frombuffer(frame.jpeg, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            return []
        h, w = img.shape[:2]
        res = self._model.predict(img, conf=min_confidence.value, imgsz=self._imgsz, device=self._device, verbose=False)[0]
        out: list[Detection] = []
        for b in res.boxes:
            raw = res.names[int(b.cls[0])]
            label = self._label_map.get(raw)
            if label is None:
                continue                              # clase que este servicio no publica
            x1, y1, x2, y2 = (float(v) for v in b.xyxy[0].tolist())
            x1, x2 = max(0.0, x1 / w), min(1.0, x2 / w)
            y1, y2 = max(0.0, y1 / h), min(1.0, y2 / h)
            if x2 <= x1 or y2 <= y1:
                continue
            out.append(Detection(label, Confidence(min(1.0, float(b.conf[0]))), BoundingBox(x1, y1, x2, y2)))
        return out
```
Ultralytics también carga `.onnx` (`YOLO("model.onnx")`), así que exportar a ONNX después no cambia este adaptador.

### 4.4 Consumidor de frames (backpressure: solo el frame **más reciente** por cámara)
```python
# adapters/inbound/kafka_frame_consumer.py
class KafkaFrameConsumer(threading.Thread):
    def __init__(self, cfg: dict, topic: str, group: str, use_case: ProcessFrameUseCase, stop: threading.Event) -> None:
        super().__init__(name="frame-consumer", daemon=True)
        self._cfg, self._topic, self._group, self._uc, self._stop_evt = cfg, topic, group, use_case, stop
        self.ready = threading.Event()

    def run(self) -> None:
        c = Consumer({**self._cfg, "group.id": self._group, "auto.offset.reset": "latest", "enable.auto.commit": True})
        c.subscribe([self._topic], on_assign=lambda *_: self.ready.set())
        try:
            while not self._stop_evt.is_set():
                msgs = c.consume(num_messages=50, timeout=0.5)
                latest: dict[bytes, object] = {}
                for m in msgs:
                    if m.error():
                        continue
                    latest[m.key()] = m                           # el último pisa a los anteriores
                DROPPED.inc(len(msgs) - len(latest))              # descartados por ir detrás de la inferencia
                for m in latest.values():
                    t0 = time.perf_counter()
                    try:
                        outcome = self._uc.handle(self._to_frame(m))
                        OUTCOMES.labels(outcome.value).inc()
                    except Exception:
                        log.exception("fallo procesando frame"); OUTCOMES.labels("error").inc()
                    INFERENCE_SECONDS.observe(time.perf_counter() - t0)
        finally:
            c.close()

    @staticmethod
    def _to_frame(m) -> Frame:
        h = {k: v.decode() for k, v in (m.headers() or [])}
        return Frame(h["frame-id"], CameraId(h["camera-id"]), TenantId(h["tenant-id"]),
                     datetime.fromisoformat(h["captured-at"]), m.value(), int(h["width"]), int(h["height"]))
```
Métricas: `frames_dropped_total`, `frames_outcome_total{outcome}`, `inference_seconds` (histograma). El consumidor de `camera.lifecycle` es el mismo de ingestion pero llama a `ApplyCameraConfigUseCase` con `detectors=tuple((d.kind, d.enabled, d.min_confidence) for d in env.data.detectors)`.

### 4.5 Publicador de detecciones
```python
# adapters/outbound/kafka_detection_publisher.py
class KafkaDetectionPublisher:
    def __init__(self, cfg: dict, topic: str, service_name: str, ids: IdGeneratorPort, clock: ClockPort) -> None:
        self._topic, self._source, self._ids, self._clock = topic, f"urn:cortexcam:{service_name}", ids, clock
        self._p = Producer({**cfg, "enable.idempotence": True, "acks": "all"})   # sin BD: publica directo (V-06)

    def publish(self, r: FrameDetections) -> None:
        f = r.frame
        env = {"specversion": "1.0", "id": self._ids.new_id(), "type": self._topic, "source": self._source,
               "subject": f"camera/{f.camera_id}", "time": _iso(self._clock.now()),
               "datacontenttype": "application/json", "tenantid": f.tenant_id,
               "data": {"cameraId": f.camera_id, "frameId": f.frame_id, "capturedAt": _iso(f.captured_at),
                        "modelVersion": r.model_version,
                        "detections": [{"label": d.label.value, "confidence": round(d.confidence.value, 4),
                                        "box": {"x1": round(d.box.x1, 4), "y1": round(d.box.y1, 4),
                                                "x2": round(d.box.x2, 4), "y2": round(d.box.y2, 4)},
                                        "attributes": d.attributes} for d in r.detections]}}
        self._p.produce(self._topic, key=f.camera_id.encode(), value=json.dumps(env).encode())
        self._p.poll(0)

def _iso(dt: datetime) -> str: return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
```
**Modo sombra** (útil para armas): un `ShadowDetectionPublisher` que implementa el mismo puerto y solo hace `log.info("[SOMBRA] …")`. `bootstrap` lo elige si `PUBLISH_ENABLED=false`.

### 4.6 Composition root
```python
def build() -> App:
    s = Settings(); setup_logging(s); kcfg = kafka_client_config(s)
    clock, ids = SystemClock(), Uuid7Generator()
    label_map = {k: DetectionLabel[v] for k, v in s.label_map.items()}
    detector = UltralyticsObjectDetector(s.model_path, s.model_sha256, label_map, s.imgsz, s.device)
    publisher = (KafkaDetectionPublisher(kcfg, s.output_topic, s.service_name, ids, clock) if s.publish_enabled
                 else ShadowDetectionPublisher())
    configs = InMemoryCameraConfig()
    process = ProcessFrameService(detector, publisher, configs, clock, StaleFramePolicy(s.max_frame_age_ms), s.model_version)
    stop = threading.Event()
    frames = KafkaFrameConsumer(kcfg, s.frames_topic, f"{s.service_name.removesuffix('-service')}.frames", process, stop)
    lifecycle = KafkaCameraLifecycleConsumer(kcfg, s.lifecycle_topic, ApplyCameraConfigService(configs, s.detector_kind), stop)
    return App(s, frames, lifecycle, stop)
```
`main.py`: arranca primero `lifecycle` (carga el catálogo), espera a `lifecycle.ready` y luego `frames`; `readyz = frames.ready and lifecycle.ready`; mismo manejo de señales. Ejecutar: `uv run python -m person_detection.main`.

---

## 5. Pruebas (todas sin Kafka ni modelo)
- **Dominio:** `Confidence(1.2)` falla; `BoundingBox` con `x2<=x1` falla; `StaleFramePolicy` en el límite.
- **Use case con fakes** (`FakeDetector` devuelve lo que le programes, `FakePublisher` guarda): frame viejo → `STALE`; cámara sin config → `DISABLED`; detector vacío → `NO_DETECTION` sin publicar; detección bajo el umbral → no publica; detección válida → `PUBLISHED` con `model_version`.
- **`ApplyCameraConfigService`:** snapshot con PERSON habilitado y cámara `ACTIVE` → config activa con su umbral; cámara `DISABLED` o `removed` → desactiva/elimina.
- **Adaptador de inferencia** (marcado `@pytest.mark.slow`): una imagen de prueba con una persona produce ≥1 `Detection` con caja dentro de 0..1.
- **Contrato:** el JSON que arma el publisher valida contra `person-detected.v1.schema.json` (`jsonschema`).
- `uv run lint-imports` en verde.

**Listo cuando:** con el video de prueba, aparece `person-detected.v1` válido en Kafka a ~el fps de ingestion; desactivar PERSON en la cámara detiene los eventos; `/metrics` muestra `inference_seconds` y descartes.

---

## 6. Deltas por detector

### 6.1 `weapon-detection-service` (:9103)
| Cambio | Detalle |
|---|---|
| Modelo | Copia `best.pt` a `models/`, calcula su SHA-256. **Antes** ejecuta `python -c "from ultralytics import YOLO; print(YOLO('models/best.pt').names)"` para ver tus clases reales |
| `.env` | `DETECTOR_KIND=WEAPON` · `OUTPUT_TOPIC=cortexcam.detection.weapon-detected.v1` · `MODEL_VERSION=weapons-yolov8m@<fecha>` · `IMGSZ=960` (objetos pequeños) · `LABEL_MAP` con tus clases, p. ej. `{"handgun":"HANDGUN","knife":"KNIFE","rifle":"RIFLE"}` (los nombres de la izquierda deben ser **exactamente** los de `model.names`; tu código viejo comparaba `"ARMA"` con minúsculas y nunca coincidía) |
| Dominio | `DetectionLabel` pasa a `HANDGUN="handgun"`, `KNIFE="knife"`, `RIFLE="rifle"` |
| Modo sombra | Arranca con `PUBLISH_ENABLED=false` **una semana**: mide falsos positivos en el log antes de generar alertas reales |
| Umbral | El default `0.6` lo pone `camera-service` (`DetectorSettings.defaults`). La confirmación N-de-M está en `alert-service` (05) |
| Evaluación | Antes de activarlo: `ml/evaluation/evaluate.py` con un set de validación **fijo** (precision/recall por clase) |

### 6.2 `vehicle-detection-service` (:9104)
| Cambio | Detalle |
|---|---|
| Modelo | El mismo `yolov8n.pt` (clases COCO) |
| `.env` | `DETECTOR_KIND=VEHICLE` · `OUTPUT_TOPIC=cortexcam.detection.vehicle-detected.v1` · `MODEL_VERSION=yolov8n-coco@1` · `LABEL_MAP={"car":"CAR","motorcycle":"MOTORCYCLE","bus":"BUS","truck":"TRUCK"}` |
| Dominio | `DetectionLabel`: `CAR="car"`, `MOTORCYCLE="motorcycle"`, `BUS="bus"`, `TRUCK="truck"` |
| Nota | Cargar `yolov8n` en dos procesos duplica memoria. Si molesta, una optimización posterior es un solo servicio con varios `ObjectDetectorPort`; hoy se mantienen separados por aislamiento y escalado independiente |

### 6.3 `plate-recognition-service` (:9105)
Cambia el puerto de inferencia: detecta la placa **y** lee su texto.

**Dependencias:** `fast-alpr[onnx]` (o la variante con tu *runtime*) en lugar de `ultralytics`. Los modelos se descargan la primera vez: en producción déjalos en la imagen o en una caché montada.

**Dominio — `PlateNumber` (regla de negocio con tests):**
```python
# domain/model/plate_number.py
_TO_LETTER = {"0": "O", "1": "I", "5": "S", "8": "B", "2": "Z"}
_TO_DIGIT  = {"O": "0", "Q": "0", "D": "0", "I": "1", "L": "1", "S": "5", "B": "8", "Z": "2"}

@dataclass(frozen=True, slots=True)
class PlateNumber:
    value: str
    @staticmethod
    def parse(raw: str) -> "PlateNumber":
        s = re.sub(r"[^A-Z0-9]", "", raw.upper())
        if len(s) != 6:
            raise InvalidPlateError(raw)
        letters = "".join(_TO_LETTER.get(c, c) for c in s[:3])     # posiciones 1-3 son letras
        digits  = "".join(_TO_DIGIT.get(c, c) for c in s[3:5])     # posiciones 4-5 son dígitos
        plate = letters + digits + s[5]                            # 6.ª: dígito (auto) o letra (moto)
        if not re.fullmatch(r"[A-Z]{3}\d{2}[A-Z0-9]", plate):
            raise InvalidPlateError(raw)
        return PlateNumber(plate)
    @property
    def vehicle_hint(self) -> str: return "MOTORCYCLE" if self.value[5].isalpha() else "CAR"
```
Tests: `"ABC123"`→`ABC123`; `"A8C123"`→`ABC123`; `"abc-12d"`→`ABC12D` (moto); `"AB1234"`→ inválida; `"ABC12"`→ inválida.

**Puerto y adaptador:**
```python
class PlateRecognizerPort(Protocol):
    def recognize(self, frame: Frame, min_confidence: Confidence) -> list[Detection]: ...   # label=LICENSE_PLATE, attributes={"plateText","ocrConfidence","vehicleHint"}

class FastAlprPlateRecognizer:
    def __init__(self, detector_model: str, ocr_model: str) -> None:
        from fast_alpr import ALPR
        self._alpr = ALPR(detector_model=detector_model, ocr_model=ocr_model)     # nombres válidos: ver el README de fast-alpr

    def recognize(self, frame, min_confidence):
        img = cv2.imdecode(np.frombuffer(frame.jpeg, np.uint8), cv2.IMREAD_COLOR)
        if img is None: return []
        h, w = img.shape[:2]; out = []
        for r in self._alpr.predict(img):
            if r.ocr is None or not r.ocr.text: continue
            ocr_conf = r.ocr.confidence
            ocr_conf = sum(ocr_conf) / len(ocr_conf) if isinstance(ocr_conf, list) else float(ocr_conf)   # algunas versiones dan una confianza por carácter
            det_conf = float(r.detection.confidence)
            try: plate = PlateNumber.parse(r.ocr.text)
            except InvalidPlateError: continue                          # lectura que no cumple el formato: se descarta
            conf = min(det_conf, ocr_conf)
            if conf < min_confidence.value: continue
            b = r.detection.bounding_box
            x1, y1, x2, y2 = max(0, b.x1 / w), max(0, b.y1 / h), min(1, b.x2 / w), min(1, b.y2 / h)
            if x2 <= x1 or y2 <= y1: continue
            out.append(Detection(DetectionLabel.LICENSE_PLATE, Confidence(min(1.0, conf)), BoundingBox(x1, y1, x2, y2),
                                 {"plateText": plate.value, "ocrConfidence": round(ocr_conf, 4), "vehicleHint": plate.vehicle_hint}))
        return out
```
`DetectionLabel.LICENSE_PLATE = "license_plate"`. El resto (use case, publisher, consumers, bootstrap) es idéntico: `ProcessFrameService` recibe un adaptador con método `detect` que delega en `recognize` (o renombra el puerto a `PlateRecognizerPort` y ajusta el use case).
`.env`: `DETECTOR_KIND=PLATE` · `OUTPUT_TOPIC=cortexcam.detection.plate-recognized.v1` · `MODEL_VERSION=fast-alpr@<versión>`.
**Calidad:** prueba con placas reales de tus cámaras (ángulo, luz nocturna y reflejos cambian todo). Si el OCR global falla con placas colombianas, afínalo en `ml/`.

**Privacidad:** una placa vinculada a un vehículo identificable es dato personal (Ley 1581). Nunca la escribas completa en logs; enmascara (`AB***3`).
