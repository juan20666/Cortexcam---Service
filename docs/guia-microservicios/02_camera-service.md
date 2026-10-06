# 02 — `camera-service` (Java · puerto 8091 · BD `camera_db`)

**Responsabilidad única:** ser dueño del catálogo de cámaras (datos, estado, credenciales cifradas, ajustes por detector) y mantener sincronizado el stream en MediaMTX.
**Publica:** `camera.lifecycle.v1` (snapshot compactado). **Consume:** `ingestion.camera-health.v1`.
**No hace:** leer video, detectar, notificar.

Ya tienes: registrar cámara, cifrado, outbox (sin relay), provisioner. Falta: el resto de comandos y consultas, errores de negocio, seguridad, relay, reconciliador, health y pruebas.

---

## 1. Decisiones de diseño (por qué cambia lo que ya hiciste)

| Antes | Ahora | Por qué |
|---|---|---|
| Se provisiona MediaMTX al registrar | La cámara nace `PENDING`; un **reconciliador** compara BD ↔ MediaMTX cada 10 s | Llamar a MediaMTX dentro de la transacción rompe el registro si MediaMTX está caído. El reconciliador se autocura y limpia huérfanos |
| `delete`/`disable` llaman a MediaMTX | Solo cambian la BD; el reconciliador quita el *path* | Un solo mecanismo, sin llamadas en caliente |
| Un evento por tipo con campos propios | **Snapshot** completo en `camera.lifecycle.v1` con `change` | Tópico compactado: el último valor por `cameraId` basta para reconstruir el estado en cualquier consumidor |
| Credenciales obligatorias | Opcionales | Cámaras sin auth y cámara falsa de pruebas |

**Máquina de estados:** `PENDING → ACTIVE ⇄ OFFLINE`, y desde cualquiera `→ DISABLED`; `DISABLED → PENDING` al habilitar; `rotateCredentials → PENDING` (se re-provisiona).

---

## 2. Árbol completo (✅ existe · ✏️ modificar · 🆕 crear)

```
services/camera-service/
├── pom.xml ✅ (agrega starter, validation, security, kafka — ver 01 §1.1)
├── .env ✅ (no versionado)  ·  .env.example 🆕
└── src/main/
    ├── java/com/cortexcam/camera/
    │   ├── CameraApplication.java ✅  (+ @ConfigurationPropertiesScan)
    │   ├── domain/
    │   │   ├── event/        DomainEvent ✅ · CameraRegistered ✏️ · CameraUpdated 🆕 · CameraRemoved 🆕 · CameraSnapshot 🆕
    │   │   ├── exception/    DomainException 🆕 · CameraNotFoundException 🆕 · DuplicateCameraNameException 🆕 · InvalidCameraStateException 🆕 · DomainValidationException 🆕
    │   │   ├── model/
    │   │   │   ├── camera/   Camera ✏️ · CameraId ✅ · CameraStatus ✏️ · StreamSource ✏️ · DetectorKind 🆕 · DetectorSettings 🆕
    │   │   │   └── shared/   TenantId ✅ · EncryptedSecret ✅
    │   │   └── service/      RtspUrlBuilder 🆕
    │   ├── application/
    │   │   ├── port/in/camera/   RegisterCameraUseCase ✏️ · UpdateCameraUseCase 🆕 · SetCameraEnabledUseCase 🆕 · RotateCredentialsUseCase 🆕
    │   │   │                     RemoveCameraUseCase 🆕 · ReportCameraHealthUseCase 🆕 · ReconcileStreamsUseCase 🆕
    │   │   │                     ListCamerasQuery 🆕 · GetCameraQuery 🆕 · CameraView 🆕
    │   │   ├── port/out/camera/  CameraRepositoryPort ✏️ · CredentialCipherPort ✅
    │   │   ├── port/out/stream/  StreamProvisioningPort ✏️
    │   │   ├── port/out/shared/  ClockPort · IdGeneratorPort · DomainEventPublisherPort (mover, C-05) · ProcessedMessagePort 🆕
    │   │   ├── service/          ProvisionCameraStep 🆕
    │   │   └── usecase/
    │   │       ├── command/      RegisterCameraService ✏️ · UpdateCameraService · SetCameraEnabledService · RotateCredentialsService
    │   │       │                 RemoveCameraService · ReportCameraHealthService · ReconcileStreamsService   (🆕 todas)
    │   │       └── query/        ListCamerasService 🆕 · GetCameraService 🆕
    │   └── infrastructure/
    │       ├── adapters/in/
    │       │   ├── rest/controller/CameraController ✏️
    │       │   ├── rest/dto/request/   RegisterCameraRequest ✏️ · UpdateCameraRequest 🆕 · RotateCredentialsRequest 🆕
    │       │   ├── rest/dto/response/  CameraResponse 🆕 · PlaybackResponse 🆕
    │       │   ├── rest/mapper/        CameraRestMapper 🆕
    │       │   ├── rest/advice/        CameraRestExceptionHandler 🆕
    │       │   ├── messaging/          CameraHealthListener 🆕 · contract/CameraHealthMessage 🆕
    │       │   └── scheduler/          StreamReconcileScheduler 🆕
    │       ├── adapters/out/
    │       │   ├── external/mediamtx/  MediaMtxStreamProvisioner ✏️
    │       │   ├── messaging/          OutboxEntity ✅ · OutboxEventPublisherAdapter ✅ · SpringDataOutboxRepository ✅ · EventContractMapper ✏️
    │       │   ├── persistence/        adapter/CameraPersistenceAdapter ✏️ · entity/camera/CameraJpaEntity ✏️ · mapper/CameraPersistenceMapper ✏️ · repository/SpringDataCameraRepository ✏️
    │       │   └── system/             AesGcmCredentialCipher ✅ · SystemClockAdapter ✅ · UuidV7GeneratorAdapter ✅ · ProcessedMessageAdapter 🆕
    │       ├── config/                 CameraProperties 🆕 · MediaMtxClientConfig 🆕
    │       ├── security/               (vacío: usa el starter)
    │       └── health/ · tracing/      (vacíos: usa el starter)
    └── resources/
        ├── application.yml ✏️
        └── db/migration/   V1__outbox_inbox.sql ✅ · V2__camera.sql ✅ · V3__camera_provisioning_and_settings.sql 🆕
```

---

## 3. Base de datos

### Tabla objetivo `camera` (lo que debe quedar tras V1–V3)

| Columna | Tipo | Notas |
|---|---|---|
| `id` | uuid PK | UUID v7 |
| `tenant_id` | uuid NOT NULL | del JWT |
| `name` | varchar(120) NOT NULL | único por tenant |
| `location` | varchar(200) | |
| `status` | varchar(16) NOT NULL | PENDING, ACTIVE, OFFLINE, DISABLED |
| `host`, `port`, `path` | varchar(255), int, varchar(255) | origen RTSP (sin credenciales) |
| `credentials_key_id`, `credentials_iv`, `credentials_ciphertext` | varchar(40), varchar(40), text | **nullables**; `ciphertext` = AES-GCM de `usuario:clave` |
| `stream_path` | varchar(80) NOT NULL UNIQUE | `cam-<uuid>` (nombre del *path* en MediaMTX) |
| `detectors` | text NOT NULL | JSON `[{"kind":"PERSON","enabled":true,"minConfidence":0.5}]` |
| `sampling_fps` | numeric(4,1) NOT NULL | 0.2 – 10 |
| `version` | bigint NOT NULL | bloqueo optimista (`@Version`) |
| `created_at`, `updated_at` | timestamptz NOT NULL | |

> Las columnas JSON se guardan como `text` y las (de)serializas tú con el `JsonMapper` en el mapper de persistencia. Así no dependes de cómo Hibernate elige el mapeador JSON bajo Jackson 3.

### `V3__camera_provisioning_and_settings.sql` 🆕
No edites V2 (Flyway verifica el checksum). Ajusta los nombres de las columnas de credenciales a los reales de tu V2.
```sql
UPDATE camera SET status = 'PENDING'
 WHERE status NOT IN ('PENDING','ACTIVE','OFFLINE','DISABLED');

ALTER TABLE camera
  ADD COLUMN IF NOT EXISTS location     varchar(200),
  ADD COLUMN IF NOT EXISTS stream_path  varchar(80),
  ADD COLUMN IF NOT EXISTS detectors    text         NOT NULL DEFAULT '[]',
  ADD COLUMN IF NOT EXISTS sampling_fps numeric(4,1) NOT NULL DEFAULT 2.0,
  ADD COLUMN IF NOT EXISTS version      bigint       NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS created_at   timestamptz  NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS updated_at   timestamptz  NOT NULL DEFAULT now();

UPDATE camera SET stream_path = 'cam-' || id WHERE stream_path IS NULL;
ALTER TABLE camera ALTER COLUMN stream_path SET NOT NULL;

-- credenciales opcionales (C-06)
ALTER TABLE camera ALTER COLUMN credentials_key_id    DROP NOT NULL;
ALTER TABLE camera ALTER COLUMN credentials_iv        DROP NOT NULL;
ALTER TABLE camera ALTER COLUMN credentials_ciphertext DROP NOT NULL;

ALTER TABLE camera ADD CONSTRAINT ck_camera_status CHECK (status IN ('PENDING','ACTIVE','OFFLINE','DISABLED'));
CREATE UNIQUE INDEX IF NOT EXISTS ux_camera_stream_path ON camera(stream_path);
CREATE UNIQUE INDEX IF NOT EXISTS ux_camera_tenant_name ON camera(tenant_id, name);
CREATE INDEX        IF NOT EXISTS ix_camera_status      ON camera(status);
```
Si estás en desarrollo y prefieres empezar limpio: `docker compose down -v`, vuelve a levantar y deja que Flyway aplique todo.

---

## 4. Dominio

### 4.1 Excepciones (`domain/exception`)
```java
public abstract class DomainException extends RuntimeException {
    private final String code;
    protected DomainException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
public class CameraNotFoundException extends DomainException {
    public CameraNotFoundException(UUID id) { super("CAMERA_NOT_FOUND", "Cámara no encontrada: " + id); }
}
public class DuplicateCameraNameException extends DomainException {
    public DuplicateCameraNameException(String n) { super("CAMERA_NAME_DUPLICATED", "Ya existe una cámara llamada " + n); }
}
public class InvalidCameraStateException extends DomainException {
    public InvalidCameraStateException(String m) { super("CAMERA_INVALID_STATE", m); }
}
public class DomainValidationException extends DomainException {
    public DomainValidationException(String m) { super("CAMERA_VALIDATION", m); }
}
```

### 4.2 Value objects
```java
public enum CameraStatus { PENDING, ACTIVE, OFFLINE, DISABLED }
public enum DetectorKind { PERSON, WEAPON, VEHICLE, PLATE }

public record DetectorSettings(DetectorKind kind, boolean enabled, double minConfidence) {
    public DetectorSettings {
        if (kind == null) throw new DomainValidationException("kind requerido");
        if (Double.isNaN(minConfidence) || minConfidence < 0 || minConfidence > 1)
            throw new DomainValidationException("minConfidence debe estar entre 0 y 1");
    }
    /** Solo PERSON activo por defecto: cada detector extra cuesta cómputo. */
    public static List<DetectorSettings> defaults() {
        return List.of(new DetectorSettings(DetectorKind.PERSON, true, 0.50),
                       new DetectorSettings(DetectorKind.WEAPON, false, 0.60),
                       new DetectorSettings(DetectorKind.VEHICLE, false, 0.50),
                       new DetectorSettings(DetectorKind.PLATE, false, 0.50));
    }
}

public record StreamSource(String host, int port, String path, EncryptedSecret credentials /* nullable */) {
    public StreamSource {
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}")) throw new DomainValidationException("host inválido");
        if (port < 1 || port > 65535) throw new DomainValidationException("puerto inválido");
        if (path == null || path.isBlank()) path = "/";
    }
    public boolean hasCredentials() { return credentials != null; }
    public StreamSource withCredentials(EncryptedSecret c) { return new StreamSource(host, port, path, c); }
}
```
```java
// domain/service/RtspUrlBuilder.java — función pura; la URL con clave existe solo un instante, en memoria
public final class RtspUrlBuilder {
    private RtspUrlBuilder() {}
    public static String build(StreamSource s, String userPass /* "user:pass" o null */) {
        String auth = "";
        if (userPass != null) {
            int i = userPass.indexOf(':');
            auth = enc(userPass.substring(0, i)) + ":" + enc(userPass.substring(i + 1)) + "@";
        }
        String p = s.path().startsWith("/") ? s.path() : "/" + s.path();
        return "rtsp://" + auth + s.host() + ":" + s.port() + p;
    }
    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20"); }
}
```

### 4.3 Eventos
```java
public record CameraSnapshot(UUID cameraId, UUID tenantId, String name, CameraStatus status, String streamPath,
                             double samplingFps, List<DetectorSettings> detectors, Change change, Instant occurredAt) {
    public enum Change { REGISTERED, UPDATED, REMOVED }
}
public record CameraRegistered(CameraSnapshot snapshot) implements DomainEvent { public Instant occurredAt() { return snapshot.occurredAt(); } }
public record CameraUpdated(CameraSnapshot snapshot)    implements DomainEvent { public Instant occurredAt() { return snapshot.occurredAt(); } }
public record CameraRemoved(CameraSnapshot snapshot)    implements DomainEvent { public Instant occurredAt() { return snapshot.occurredAt(); } }
```

### 4.4 Agregado `Camera` ✏️
```java
public final class Camera {
    private final CameraId id;
    private final TenantId tenantId;
    private String name;
    private String location;
    private CameraStatus status;
    private StreamSource source;
    private final String streamPath;
    private List<DetectorSettings> detectors;
    private double samplingFps;
    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<DomainEvent> pending = new ArrayList<>();

    private Camera(CameraId id, TenantId t, String name, String location, CameraStatus st, StreamSource src, String path,
                   List<DetectorSettings> det, double fps, long version, Instant created, Instant updated) {
        this.id = id; this.tenantId = t; this.name = name; this.location = location; this.status = st; this.source = src;
        this.streamPath = path; this.detectors = List.copyOf(det); this.samplingFps = fps; this.version = version;
        this.createdAt = created; this.updatedAt = updated;
    }

    public static Camera register(CameraId id, TenantId tenant, String name, String location, StreamSource source,
                                  double samplingFps, Instant now) {
        validateName(name); validateFps(samplingFps);
        var c = new Camera(id, tenant, name.trim(), location, CameraStatus.PENDING, source, "cam-" + id.value(),
                           DetectorSettings.defaults(), samplingFps, 0L, now, now);
        c.pending.add(new CameraRegistered(c.snapshot(CameraSnapshot.Change.REGISTERED, now)));
        return c;
    }

    /** Solo para el mapper de persistencia: no valida ni emite eventos. */
    public static Camera reconstitute(CameraId id, TenantId t, String name, String location, CameraStatus st, StreamSource src,
                                      String path, List<DetectorSettings> det, double fps, long version, Instant created, Instant updated) {
        return new Camera(id, t, name, location, st, src, path, det, fps, version, created, updated);
    }

    public void updateSettings(String name, String location, List<DetectorSettings> detectors, Double fps, Instant now) {
        if (name != null) { validateName(name); this.name = name.trim(); }
        if (location != null) this.location = location;
        if (detectors != null) {
            if (detectors.stream().map(DetectorSettings::kind).distinct().count() != detectors.size())
                throw new DomainValidationException("detectores duplicados");
            this.detectors = List.copyOf(detectors);
        }
        if (fps != null) { validateFps(fps); this.samplingFps = fps; }
        changed(now);
    }

    /** Lo llama el reconciliador cuando MediaMTX ya tiene el path. */
    public void markProvisioned(Instant now) {
        if (status != CameraStatus.PENDING) throw new InvalidCameraStateException("Solo una cámara PENDING se puede provisionar");
        status = CameraStatus.ACTIVE; changed(now);
    }
    public void reportOffline(Instant now) { if (status == CameraStatus.ACTIVE)  { status = CameraStatus.OFFLINE; changed(now); } }  // idempotente
    public void reportOnline(Instant now)  { if (status == CameraStatus.OFFLINE) { status = CameraStatus.ACTIVE;  changed(now); } }
    public void disable(Instant now)       { if (status != CameraStatus.DISABLED) { status = CameraStatus.DISABLED; changed(now); } }
    public void enable(Instant now)        { if (status == CameraStatus.DISABLED) { status = CameraStatus.PENDING;  changed(now); } }
    public void rotateCredentials(EncryptedSecret s, Instant now) {
        if (status == CameraStatus.DISABLED) throw new InvalidCameraStateException("Habilita la cámara antes de rotar credenciales");
        source = source.withCredentials(s); status = CameraStatus.PENDING; changed(now);
    }
    public void markRemoved(Instant now) { updatedAt = now; pending.add(new CameraRemoved(snapshot(CameraSnapshot.Change.REMOVED, now))); }

    private void changed(Instant now) { updatedAt = now; pending.add(new CameraUpdated(snapshot(CameraSnapshot.Change.UPDATED, now))); }
    private CameraSnapshot snapshot(CameraSnapshot.Change ch, Instant now) {
        return new CameraSnapshot(id.value(), tenantId.value(), name, status, streamPath, samplingFps, detectors, ch, now);
    }
    private static void validateName(String n) { if (n == null || n.isBlank() || n.length() > 120) throw new DomainValidationException("name: 1 a 120 caracteres"); }
    private static void validateFps(double f) { if (f < 0.2 || f > 10) throw new DomainValidationException("samplingFps: entre 0.2 y 10"); }

    public List<DomainEvent> pullEvents() { var c = List.copyOf(pending); pending.clear(); return c; }
    // getters: id(), tenantId(), name(), location(), status(), source(), streamPath(), detectors(), samplingFps(), version(), createdAt(), updatedAt()
}
```
Tests de dominio primero (JUnit puro): nace `PENDING`; `markProvisioned` desde `ACTIVE` lanza excepción; `reportOffline` dos veces emite un solo evento; `rotateCredentials` vuelve a `PENDING`; fps fuera de rango falla.

---

## 5. Aplicación

### 5.1 Puertos de entrada (todos con `Command`/`Result` como *records* internos; nunca dominio ni DTOs)

| Puerto | Command → Result | Quién lo llama |
|---|---|---|
| `RegisterCameraUseCase` ✏️ | `(tenantId, name, location, host, port, path, username?, password?, samplingFps)` → `CameraView` | REST POST |
| `UpdateCameraUseCase` | `(tenantId, cameraId, name?, location?, detectors?, samplingFps?)` → `CameraView` | REST PATCH |
| `SetCameraEnabledUseCase` | `(tenantId, cameraId, enabled)` → `CameraView` | REST enable/disable |
| `RotateCredentialsUseCase` | `(tenantId, cameraId, username, password)` → void | REST PUT credentials |
| `RemoveCameraUseCase` | `(tenantId, cameraId)` → void | REST DELETE |
| `ReportCameraHealthUseCase` | `(eventId, tenantId, cameraId, online, reason?, observedAt)` → void | Listener Kafka |
| `ReconcileStreamsUseCase` | `()` → void | Scheduler |
| `ListCamerasQuery` / `GetCameraQuery` | `(tenantId[, cameraId])` → `List<CameraView>` / `CameraView` | REST GET |

```java
// port/in/camera/CameraView.java — lo único que sale del núcleo (sin credenciales)
public record CameraView(UUID id, UUID tenantId, String name, String location, CameraStatus status, String streamPath,
                         String host, int port, String path, boolean hasCredentials, double samplingFps,
                         List<DetectorSettings> detectors, Instant createdAt, Instant updatedAt) {
    public static CameraView from(Camera c) {
        return new CameraView(c.id().value(), c.tenantId().value(), c.name(), c.location(), c.status(), c.streamPath(),
            c.source().host(), c.source().port(), c.source().path(), c.source().hasCredentials(), c.samplingFps(),
            c.detectors(), c.createdAt(), c.updatedAt());
    }
}
```

### 5.2 Puertos de salida
```java
public interface CameraRepositoryPort {
    Camera save(Camera camera);
    Optional<Camera> findById(TenantId tenant, CameraId id);
    List<Camera> findAllByTenant(TenantId tenant);
    boolean existsByTenantAndName(TenantId tenant, String name);
    void delete(CameraId id);
    List<Camera> findByStatus(CameraStatus status);
    Set<String> findStreamPathsByStatuses(Collection<CameraStatus> statuses);
}
public interface StreamProvisioningPort {
    Set<String> listManagedPaths();                    // solo los que empiezan con "cam-"
    void provision(String streamPath, String sourceUrl);   // upsert
    void remove(String streamPath);                    // idempotente
}
public interface ProcessedMessagePort { boolean markIfFirstTime(String messageId, String consumer); }   // port/out/shared
```
`CredentialCipherPort`: `EncryptedSecret encrypt(String plain, String aad)` y `String decrypt(EncryptedSecret s, String aad)` (con AAD = `cameraId`).

### 5.3 Casos de uso (patrón común: cargar → dominio → guardar → publicar, en **una** transacción)
```java
@Service @Transactional
class RegisterCameraService implements RegisterCameraUseCase {
    // ...puertos por constructor: cameras, cipher, events, clock, ids
    public CameraView handle(Command c) {
        var tenant = new TenantId(c.tenantId());
        if (cameras.existsByTenantAndName(tenant, c.name().trim())) throw new DuplicateCameraNameException(c.name());
        var id = new CameraId(ids.newId());
        EncryptedSecret secret = null;
        if (c.username() != null && !c.username().isBlank())
            secret = cipher.encrypt(c.username() + ":" + c.password(), id.value().toString());      // AAD = cameraId
        var camera = Camera.register(id, tenant, c.name(), c.location(),
                new StreamSource(c.host(), c.port(), c.path(), secret), c.samplingFps(), clock.now());
        cameras.save(camera);
        events.publish(camera.pullEvents());                  // → outbox, misma transacción
        return CameraView.from(camera);
    }
}

@Service @Transactional
class UpdateCameraService implements UpdateCameraUseCase {
    public CameraView handle(Command c) {
        var cam = cameras.findById(new TenantId(c.tenantId()), new CameraId(c.cameraId()))
                         .orElseThrow(() -> new CameraNotFoundException(c.cameraId()));
        cam.updateSettings(c.name(), c.location(), c.detectors(), c.samplingFps(), clock.now());
        cameras.save(cam); events.publish(cam.pullEvents());
        return CameraView.from(cam);
    }
}
```
`SetCameraEnabledService` (`cam.enable/disable`), `RotateCredentialsService` (cifra con AAD = id y llama `cam.rotateCredentials`) y `RemoveCameraService` (`cam.markRemoved`; `events.publish(...)`; `cameras.delete(id)`) siguen el mismo molde. **Un nombre duplicado en `UpdateCamera`** también debe lanzar `DuplicateCameraNameException` (compruébalo si cambia `name`).

```java
@Service @Transactional
class ReportCameraHealthService implements ReportCameraHealthUseCase {
    private static final String CONSUMER = "camera.health";
    public void handle(Command c) {
        if (!processed.markIfFirstTime(c.eventId(), CONSUMER)) return;                    // inbox
        var cam = cameras.findById(new TenantId(c.tenantId()), new CameraId(c.cameraId())).orElse(null);
        if (cam == null) return;                                                          // cámara borrada: ignorar
        if (c.online()) cam.reportOnline(clock.now()); else cam.reportOffline(clock.now());
        cameras.save(cam); events.publish(cam.pullEvents());                              // sin cambio de estado = sin eventos
    }
}
```
**Reconciliador** (orquesta I/O externo, **sin** transacción propia; la parte transaccional vive en otro bean):
```java
@Service
class ReconcileStreamsService implements ReconcileStreamsUseCase {
    private static final Logger log = LoggerFactory.getLogger(ReconcileStreamsService.class);
    // puertos: cameras, streams, cipher, step (ProvisionCameraStep)

    public void handle() {
        Set<String> existing = streams.listManagedPaths();
        Set<String> wanted = cameras.findStreamPathsByStatuses(Set.of(CameraStatus.PENDING, CameraStatus.ACTIVE, CameraStatus.OFFLINE));
        existing.stream().filter(p -> !wanted.contains(p)).forEach(streams::remove);        // huérfanos / deshabilitadas / borradas

        for (Camera c : cameras.findByStatus(CameraStatus.PENDING)) {
            try {
                String userPass = c.source().hasCredentials() ? cipher.decrypt(c.source().credentials(), c.id().value().toString()) : null;
                streams.provision(c.streamPath(), RtspUrlBuilder.build(c.source(), userPass));
                step.markProvisioned(c.tenantId(), c.id());
            } catch (Exception e) {
                log.warn("No se pudo provisionar {}: {}", c.streamPath(), e.getClass().getSimpleName());   // NUNCA loguear la URL ni el cuerpo
            }
        }
    }
}

@Service @Transactional
class ProvisionCameraStep {
    void markProvisioned(TenantId t, CameraId id) {
        cameras.findById(t, id).ifPresent(cam -> { cam.markProvisioned(clock.now()); cameras.save(cam); events.publish(cam.pullEvents()); });
    }
}
```

---

## 6. Infraestructura

### 6.1 Persistencia ✏️
`CameraJpaEntity`: campos de la tabla; `@Version private long version;`; `@Enumerated(EnumType.STRING) status`; `detectors` como `String` (texto JSON). `CameraPersistenceMapper` serializa/deserializa `detectors` con el `JsonMapper` (record privado `DetectorJson(String kind, boolean enabled, double minConfidence)`), arma `StreamSource` con las 3 columnas de credenciales (todas null = sin credenciales) y usa `Camera.reconstitute(...)` pasando `version`. `SpringDataCameraRepository` añade:
```java
boolean existsByTenantIdAndName(UUID tenantId, String name);
List<CameraJpaEntity> findByTenantIdOrderByNameAsc(UUID tenantId);
Optional<CameraJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);
List<CameraJpaEntity> findByStatus(String status);
@Query("select c.streamPath from CameraJpaEntity c where c.status in :statuses")
Set<String> findStreamPaths(@Param("statuses") Collection<String> statuses);
```
`ProcessedMessageAdapter`:
```java
@Component
class ProcessedMessageAdapter implements ProcessedMessagePort {
    private final ProcessedMessageStore store;            // del starter
    ProcessedMessageAdapter(ProcessedMessageStore s) { this.store = s; }
    public boolean markIfFirstTime(String id, String consumer) { return store.markIfFirstTime(id, consumer); }
}
```

### 6.2 Eventos hacia Kafka ✏️ (`EventContractMapper`)
Todos los eventos de cámara van al mismo tópico, con la `cameraId` como clave.
```java
@Component
class EventContractMapper {
    static final String TOPIC = "cortexcam.camera.lifecycle.v1";
    private final JsonMapper json;  private final IdGeneratorPort ids;

    ContractMessage toContract(DomainEvent e) {
        CameraSnapshot s = switch (e) {
            case CameraRegistered r -> r.snapshot();
            case CameraUpdated u    -> u.snapshot();
            case CameraRemoved d    -> d.snapshot();
            default -> throw new IllegalArgumentException("Evento sin contrato: " + e.getClass().getSimpleName());
        };
        var data = new LifecycleData(s.change().name(), s.cameraId(), s.tenantId(), s.name(), s.status().name(), s.streamPath(),
                s.samplingFps(), s.detectors().stream().map(d -> new DetectorData(d.kind().name(), d.enabled(), d.minConfidence())).toList(), s.occurredAt());
        var env = CloudEventEnvelope.of(ids.newId().toString(), TOPIC, "urn:cortexcam:camera-service",
                "camera/" + s.cameraId(), s.occurredAt(), s.tenantId().toString(), data);
        return new ContractMessage(TOPIC, s.cameraId().toString(), TOPIC, json.writeValueAsString(env));
    }
    record LifecycleData(String change, UUID cameraId, UUID tenantId, String name, String status, String streamPath,
                         double samplingFps, List<DetectorData> detectors, Instant occurredAt) {}
    record DetectorData(String kind, boolean enabled, double minConfidence) {}
    record ContractMessage(String topic, String key, String type, String jsonPayload) {}
}
```
`OutboxEventPublisherAdapter` (ya lo tienes) recorre los eventos, usa este mapper y guarda `OutboxEntity`. **El relay lo aporta el starter** (activa `cortexcam.outbox.enabled=true`).

### 6.3 MediaMTX ✏️ y configuración 🆕
```java
@Component
class MediaMtxStreamProvisioner implements StreamProvisioningPort {
    private static final String PREFIX = "cam-";
    private final RestClient http;                                   // bean "mediaMtxClient"
    MediaMtxStreamProvisioner(@Qualifier("mediaMtxClient") RestClient http) { this.http = http; }

    public Set<String> listManagedPaths() {
        var res = http.get().uri("/v3/config/paths/list?itemsPerPage=1000").retrieve().body(PathList.class);
        return res == null || res.items() == null ? Set.of()
             : res.items().stream().map(PathItem::name).filter(n -> n.startsWith(PREFIX)).collect(Collectors.toSet());
    }
    public void provision(String path, String sourceUrl) {
        remove(path);                                                // upsert simple y robusto
        http.post().uri("/v3/config/paths/add/{n}", path).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("source", sourceUrl, "sourceOnDemand", false, "rtspTransport", "tcp"))
            .retrieve().toBodilessEntity();
    }
    public void remove(String path) {
        try { http.delete().uri("/v3/config/paths/delete/{n}", path).retrieve().toBodilessEntity(); }
        catch (HttpClientErrorException.NotFound ignored) { }
    }
    @JsonIgnoreProperties(ignoreUnknown = true) record PathList(List<PathItem> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true) record PathItem(String name) {}
}

@Validated @ConfigurationProperties("cortexcam.camera")
public record CameraProperties(Cipher cipher, MediaMtx mediamtx, Playback playback) {
    public record Cipher(@NotBlank String keyId, @NotBlank String keyBase64) {}
    public record MediaMtx(@NotBlank String apiUrl, String user, String password) {}
    public record Playback(@NotBlank String webrtcBaseUrl, @NotBlank String hlsBaseUrl) {}
}

@Configuration
class MediaMtxClientConfig {
    @Bean RestClient mediaMtxClient(CameraProperties p) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        var b = RestClient.builder().baseUrl(p.mediamtx().apiUrl()).requestFactory(factory);
        if (p.mediamtx().user() != null && !p.mediamtx().user().isBlank())
            b.defaultHeaders(h -> h.setBasicAuth(p.mediamtx().user(), p.mediamtx().password()));
        return b.build();
    }
}
```
`AesGcmCredentialCipher` toma `CameraProperties.cipher()` (32 bytes en Base64; falla al arrancar si no mide 32).

### 6.4 Scheduler y health (adaptadores de entrada)
```java
@Component
class StreamReconcileScheduler {
    private final ReconcileStreamsUseCase useCase;
    StreamReconcileScheduler(ReconcileStreamsUseCase u) { this.useCase = u; }
    @Scheduled(fixedDelayString = "${cortexcam.camera.reconcile-delay-ms:10000}", initialDelay = 5000)
    void tick() { try { useCase.handle(); } catch (Exception e) { /* MediaMTX caído: reintenta en el próximo ciclo */ } }
}

@Component
class CameraHealthListener {
    private final ReportCameraHealthUseCase useCase;  private final JsonMapper json;

    @KafkaListener(topics = "cortexcam.ingestion.camera-health.v1", groupId = "camera.health")
    void on(ConsumerRecord<String, String> rec) {
        CameraHealthMessage m;
        try { m = json.readValue(rec.value(), CameraHealthMessage.class); }
        catch (JacksonException e) { throw new InvalidMessageException("camera-health inválido", e); }   // → DLQ sin reintentos
        useCase.handle(new ReportCameraHealthUseCase.Command(m.id(), UUID.fromString(m.tenantid()), m.data().cameraId(),
                "ONLINE".equals(m.data().status()), m.data().reason(), m.data().observedAt()));
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CameraHealthMessage(String id, String tenantid, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Data(UUID cameraId, String status, String reason, Instant observedAt) {}
    }
}
```
(Los `record` anidados del contrato viven en `adapters/in/messaging/contract/`; aquí van juntos solo por brevedad.)

### 6.5 REST ✏️ — endpoints
| Método y ruta | Rol | Respuesta |
|---|---|---|
| `POST /api/v1/cameras` | ADMIN, OPERATOR | 201 + `Location` + `CameraResponse` |
| `GET /api/v1/cameras` · `GET /api/v1/cameras/{id}` | cualquier autenticado | `CameraResponse` |
| `PATCH /api/v1/cameras/{id}` | ADMIN, OPERATOR | `CameraResponse` |
| `POST /api/v1/cameras/{id}/enable` · `/disable` | ADMIN, OPERATOR | `CameraResponse` |
| `PUT /api/v1/cameras/{id}/credentials` | ADMIN | 204 |
| `DELETE /api/v1/cameras/{id}` | ADMIN | 204 |
| `GET /api/v1/cameras/{id}/playback` | cualquier autenticado | `{ webrtcWhepUrl, hlsUrl }` |

```java
@RestController @RequestMapping("/api/v1/cameras")
class CameraController {
    // dependen de las INTERFACES de los use cases + CameraRestMapper + CameraProperties (solo para URLs públicas de playback)

    @PostMapping @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    ResponseEntity<CameraResponse> register(@Valid @RequestBody RegisterCameraRequest body, @AuthenticationPrincipal Jwt jwt) {
        var actor = CurrentActor.from(jwt);
        var view = register.handle(mapper.toCommand(body, actor.tenantId()));       // tenantId SIEMPRE del JWT
        return ResponseEntity.created(URI.create("/api/v1/cameras/" + view.id())).body(mapper.toResponse(view));
    }

    @GetMapping @PreAuthorize("isAuthenticated()")
    List<CameraResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return list.handle(CurrentActor.from(jwt).tenantId()).stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/{id}/playback") @PreAuthorize("isAuthenticated()")
    PlaybackResponse playback(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var v = get.handle(CurrentActor.from(jwt).tenantId(), id);
        return new PlaybackResponse(props.playback().webrtcBaseUrl() + "/" + v.streamPath() + "/whep",
                                    props.playback().hlsBaseUrl() + "/" + v.streamPath() + "/index.m3u8");
    }
    // PATCH, enable/disable, PUT credentials y DELETE: mismo molde (extraer actor → Command → use case → DTO)
}
```
DTOs con validación (solo forma):
```java
public record RegisterCameraRequest(
    @NotBlank @Size(max = 120) String name, @Size(max = 200) String location,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9.-]{1,253}") String host,
    @Min(1) @Max(65535) int port, String path, String username, String password,
    @DecimalMin("0.2") @DecimalMax("10.0") Double samplingFps) {}
```
`CameraResponse` espeja `CameraView` (nunca incluye usuario/clave/ciphertext). Errores de negocio:
```java
@RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)
class CameraRestExceptionHandler {
    @ExceptionHandler(CameraNotFoundException.class)       ProblemDetail nf(DomainException e)   { return p(HttpStatus.NOT_FOUND, e); }
    @ExceptionHandler(DuplicateCameraNameException.class)  ProblemDetail dup(DomainException e)  { return p(HttpStatus.CONFLICT, e); }
    @ExceptionHandler(InvalidCameraStateException.class)   ProblemDetail st(DomainException e)   { return p(HttpStatus.CONFLICT, e); }
    @ExceptionHandler(DomainValidationException.class)     ProblemDetail val(DomainException e)  { return p(HttpStatus.UNPROCESSABLE_ENTITY, e); }
    private ProblemDetail p(HttpStatus s, DomainException e) {
        var pd = ProblemDetail.forStatusAndDetail(s, e.getMessage());
        pd.setProperty("code", e.code()); pd.setProperty("traceId", MDC.get("traceId")); return pd;
    }
}
```
Seguridad: **no escribas** `WebSecurityConfig`; el starter ya deja todo autenticado. Solo asegúrate de que `application.yml` tenga el `issuer-uri`.

---

## 7. `application.yml` ✏️ y `.env`
Parte de la plantilla de 01 §1.6 y añade:
```yaml
cortexcam:
  camera:
    cipher:   { key-id: "${CAMERA_CRED_KEY_ID:k1}", key-base64: "${CAMERA_CRED_KEY_B64}" }
    mediamtx: { api-url: "${MEDIAMTX_API_URL:http://localhost:9997}", user: "${MEDIAMTX_API_USER:}", password: "${MEDIAMTX_API_PASSWORD:}" }
    playback: { webrtc-base-url: "${PLAYBACK_WEBRTC_URL:http://localhost:8889}", hls-base-url: "${PLAYBACK_HLS_URL:http://localhost:8888}" }
    reconcile-delay-ms: 10000
```
`.env` (valores de desarrollo; **no** se versiona):
```
SERVICE_NAME=camera-service
SERVER_PORT=8091
DB_URL=jdbc:postgresql://localhost:5432/camera_db
DB_USER=camera_svc
DB_PASSWORD=camera_dev
CAMERA_CRED_KEY_ID=k1
CAMERA_CRED_KEY_B64=<pega aquí 32 bytes aleatorios en Base64>
```
Generar la llave en Windows PowerShell 5.1:
```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

---

## 8. Pruebas
| Nivel | Qué escribir |
|---|---|
| Dominio | `CameraTest`: transiciones, idempotencia de `reportOffline`, `rotateCredentials` → PENDING, validaciones |
| Use case (fakes) | `RegisterCameraServiceTest`: duplicado, con y sin credenciales, publica 1 evento. `ReconcileStreamsServiceTest` con `FakeStreamProvisioning`: (a) provisiona PENDING y la marca ACTIVE, (b) borra huérfanos, (c) si el provisioner lanza, la cámara sigue PENDING |
| Persistencia | Testcontainers PostgreSQL: Flyway completo, guardar/leer, `@Version` (dos escrituras concurrentes → una falla), **ninguna URL en claro** |
| Mensajería | Mensaje de `camera-health` válido y uno inválido (→ `InvalidMessageException`) |
| REST | `@WebMvcTest`: 401 sin token, 403 VIEWER en POST, 422 con `port=0`, 404 id inexistente |
| Arquitectura | `ArchitectureTest` (01/guía anterior §3.4) en verde |

---

## 9. Probar a mano
```bat
:: 1) token (ver 01 §1.5) → set T=<access_token>
:: 2) registrar una cámara falsa (ver 09 para publicar el video en MediaMTX)
curl -i -X POST http://localhost:8091/api/v1/cameras -H "Authorization: Bearer %T%" -H "Content-Type: application/json" ^
  -d "{\"name\":\"Entrada\",\"location\":\"Puerta\",\"host\":\"127.0.0.1\",\"port\":8554,\"path\":\"/fake\",\"samplingFps\":2}"
:: 3) en ≤10 s la cámara pasa a ACTIVE
curl -s http://localhost:8091/api/v1/cameras -H "Authorization: Bearer %T%"
:: 4) MediaMTX tiene el path cam-<uuid>
curl -s http://localhost:9997/v3/config/paths/list
:: 5) Redpanda Console (http://localhost:8082) → tópico camera.lifecycle.v1 muestra REGISTERED y luego UPDATED(ACTIVE)
:: 6) activar el detector de armas
curl -X PATCH http://localhost:8091/api/v1/cameras/<id> -H "Authorization: Bearer %T%" -H "Content-Type: application/json" ^
  -d "{\"detectors\":[{\"kind\":\"PERSON\",\"enabled\":true,\"minConfidence\":0.5},{\"kind\":\"WEAPON\",\"enabled\":true,\"minConfidence\":0.6}]}"
```

## 10. Listo cuando
- [ ] `mvn verify` pasa (tests de dominio, use cases y ArchUnit).
- [ ] POST devuelve 201 **sin** credenciales; la BD solo guarda `ciphertext`.
- [ ] `outbox_event.published_at` se llena y el mensaje aparece en Kafka.
- [ ] Con MediaMTX apagado, el registro funciona y la cámara queda `PENDING`; al encenderlo pasa a `ACTIVE` sola.
- [ ] Deshabilitar/borrar quita el *path* de MediaMTX en ≤10 s.
- [ ] Sin token → 401; VIEWER intentando POST → 403; body inválido → 422 con `code` (nunca 500).
- [ ] Endurecimiento pendiente (antes de producción): restringe `host` a redes permitidas para evitar que un usuario haga que MediaMTX consulte servicios internos (SSRF), y limita cámaras por tenant.
