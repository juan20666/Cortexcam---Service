# 06 — `evidence-service` (Java · puerto 8093 · BD `evidence_db` + MinIO)

**Responsabilidad única:** guardar la **evidencia** de cada alerta (captura con las cajas dibujadas), aplicar **retención** y dar acceso **auditado** mediante URLs firmadas de vida corta.
**Consume:** `ingestion.frames.v1` (binario, grupo `evidence.frames`) y `alert.raised.v1` (grupo `evidence.alerts`).
**Publica (Outbox):** `evidence.stored.v1`.
**No hace:** decidir alertas, detectar, notificar.

Reemplaza el guardado de capturas de tu `IA_ENTRENADA.py` (`cv2.imwrite` en `~/Documents/CortexCam_Data/capturas_alertas` + copia a `web/capturas_alertas`): ahora van a MinIO y se ven con URLs firmadas, no con rutas de disco.

**Idea clave:** cuando llega la alerta, el frame del disparo ya pasó. Por eso el servicio mantiene un **buffer circular en memoria** (últimos ~15 s por cámara) alimentado por el tópico de frames. Al llegar `alert.raised`, busca por `frameId`, dibuja las cajas, guarda y publica.

> *Modo compacto* (guía anterior §5.3): puedes empezar con esto dentro de `alert-service`. Es un servicio aparte porque aquí viven la **retención** y la **auditoría de acceso**, y porque consumir frames tiene un perfil de carga distinto.

---

## 1. Árbol

```
services/evidence-service/
├── pom.xml ✅ · .env.example · README.md
└── src/main/
    ├── java/com/cortexcam/evidence/
    │   ├── EvidenceApplication.java                               (+ @ConfigurationPropertiesScan)
    │   ├── domain/
    │   │   ├── model/evidence/  EvidenceItem · EvidenceId · EvidenceKind · StorageKey · ContentHash · AnnotationBox · RetentionPolicy
    │   │   ├── model/shared/    TenantId · CameraId · AlertId · UserId
    │   │   ├── event/           DomainEvent · EvidenceStored
    │   │   └── exception/       DomainException · EvidenceNotFoundException · FrameNotAvailableException · DomainValidationException
    │   ├── application/
    │   │   ├── port/in/evidence/  BufferFrameUseCase · CaptureEvidenceForAlertUseCase · GetEvidenceAccessUrlQuery · ListEvidenceQuery
    │   │   │                      DeleteEvidenceUseCase · PurgeExpiredEvidenceUseCase · EvidenceView
    │   │   ├── port/out/evidence/ FrameBufferPort · ObjectStoragePort · ImageAnnotatorPort · EvidenceRepositoryPort · RetentionPolicyRepositoryPort
    │   │   ├── port/out/shared/   ClockPort · IdGeneratorPort · DomainEventPublisherPort · ProcessedMessagePort · AuditTrailPort
    │   │   └── usecase/{command,query}/
    │   └── infrastructure/
    │       ├── adapters/in/
    │       │   ├── messaging/  FrameBufferListener · AlertRaisedListener · AlertRaisedMapper · contract/AlertRaisedMessage
    │       │   ├── rest/       controller/EvidenceController · dto/ · mapper/ · advice/EvidenceRestExceptionHandler
    │       │   └── scheduler/  EvidencePurgeScheduler · FrameBufferEvictionScheduler
    │       ├── adapters/out/
    │       │   ├── memory/      InMemoryFrameBuffer
    │       │   ├── storage/     MinioObjectStorageAdapter · AwtImageAnnotator
    │       │   ├── persistence/ entity/ · repository/ · mapper/ · adapter/
    │       │   ├── messaging/   OutboxEntity · SpringDataOutboxRepository · OutboxEventPublisherAdapter · EventContractMapper
    │       │   └── system/      SystemClockAdapter · UuidV7GeneratorAdapter · ProcessedMessageAdapter · DbAuditTrailAdapter
    │       └── config/          EvidenceProperties · MinioConfig
    └── resources/ application.yml · db/migration/{V1__outbox_inbox.sql ✅, V2__evidence.sql 🆕}
```
Dependencias extra: `io.minio:minio`.

---

## 2. Base de datos — `V2__evidence.sql`
```sql
CREATE TABLE evidence_item (
  id            uuid PRIMARY KEY,
  tenant_id     uuid         NOT NULL,
  alert_id      uuid         NOT NULL,
  camera_id     uuid         NOT NULL,
  kind          varchar(10)  NOT NULL,                       -- SNAPSHOT | CLIP
  storage_bucket varchar(63) NOT NULL,
  storage_key   varchar(300) NOT NULL,
  content_type  varchar(40)  NOT NULL,
  size_bytes    bigint       NOT NULL,
  sha256        char(64)     NOT NULL,
  frame_id      uuid         NOT NULL,
  captured_at   timestamptz  NOT NULL,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  expires_at    timestamptz  NOT NULL,
  deleted_at    timestamptz,
  version       bigint       NOT NULL DEFAULT 0,
  CONSTRAINT ck_evidence_kind CHECK (kind IN ('SNAPSHOT','CLIP')),
  UNIQUE (alert_id, frame_id, kind)                          -- idempotencia: misma alerta + mismo frame = una sola evidencia
);
CREATE INDEX ix_evidence_alert   ON evidence_item (tenant_id, alert_id);
CREATE INDEX ix_evidence_expires ON evidence_item (expires_at) WHERE deleted_at IS NULL;

CREATE TABLE evidence_access_log (                           -- solo se inserta; nunca se actualiza ni se borra
  id          uuid PRIMARY KEY,
  evidence_id uuid         NOT NULL,
  tenant_id   uuid         NOT NULL,
  actor_id    uuid,                                          -- NULL cuando lo hace el sistema (purga)
  action      varchar(24)  NOT NULL,                         -- URL_ISSUED | DELETED | EXPIRED_DELETED
  occurred_at timestamptz  NOT NULL DEFAULT now(),
  trace_id    varchar(64)
);
CREATE INDEX ix_access_evidence ON evidence_access_log (evidence_id, occurred_at DESC);

CREATE TABLE retention_policy (                              -- override por tenant; si no hay fila rige el default
  tenant_id uuid PRIMARY KEY, snapshot_days int NOT NULL CHECK (snapshot_days BETWEEN 1 AND 3650),
  updated_at timestamptz NOT NULL DEFAULT now()
);
```
**Bucket:** `evidence` (lo crea `minio-init`). **Clave de objeto:** `{tenantId}/{yyyy}/{MM}/{dd}/{alertId}/{evidenceId}.jpg`. Sin datos personales en el nombre.

---

## 3. Dominio
```java
public enum EvidenceKind { SNAPSHOT, CLIP }
public record AnnotationBox(String label, double confidence, double x1, double y1, double x2, double y2) {}

public record StorageKey(String value) {
    public static StorageKey of(TenantId t, Instant at, AlertId a, EvidenceId e) {
        var d = at.atZone(ZoneOffset.UTC);
        return new StorageKey("%s/%04d/%02d/%02d/%s/%s.jpg".formatted(t.value(), d.getYear(), d.getMonthValue(), d.getDayOfMonth(), a.value(), e.value()));
    }
}
public record ContentHash(String hex) {
    public static ContentHash of(byte[] data) {                     // java.* permitido en el dominio
        try { return new ContentHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
public record RetentionPolicy(int snapshotDays) {
    public RetentionPolicy { if (snapshotDays < 1 || snapshotDays > 3650) throw new DomainValidationException("retención: 1..3650 días"); }
    public Instant expiresAt(Instant from) { return from.plus(Duration.ofDays(snapshotDays)); }
}
```
```java
public final class EvidenceItem {                                  // AGGREGATE ROOT
    private final EvidenceId id; private final TenantId tenantId; private final AlertId alertId; private final CameraId cameraId;
    private final EvidenceKind kind; private final String bucket; private final StorageKey key; private final long sizeBytes;
    private final ContentHash hash; private final UUID frameId; private final Instant capturedAt, createdAt, expiresAt;
    private Instant deletedAt; private final long version;
    private final List<DomainEvent> pending = new ArrayList<>();

    public static EvidenceItem create(EvidenceId id, TenantId t, AlertId a, CameraId c, String bucket, StorageKey key,
                                      long size, ContentHash hash, UUID frameId, Instant capturedAt, RetentionPolicy retention, Instant now) {
        var e = new EvidenceItem(id, t, a, c, EvidenceKind.SNAPSHOT, bucket, key, size, hash, frameId, capturedAt, now, retention.expiresAt(now), null, 0L);
        e.pending.add(new EvidenceStored(id, t, a, c, EvidenceKind.SNAPSHOT, capturedAt, e.expiresAt, now));
        return e;
    }
    public boolean isExpired(Instant now) { return deletedAt == null && !expiresAt.isAfter(now); }
    public void markDeleted(Instant now) { if (deletedAt == null) deletedAt = now; }
    public boolean isDeleted() { return deletedAt != null; }
    // reconstitute(...), pullEvents(), getters
}
```
Tests: `StorageKey` formato; `RetentionPolicy` límites; `isExpired` en el borde; `markDeleted` idempotente.

---

## 4. Aplicación

### 4.1 Puertos
```java
public interface FrameBufferPort {
    void put(BufferedFrame f);                                    // record(UUID cameraId, UUID frameId, Instant capturedAt, byte[] jpeg)
    Optional<BufferedFrame> find(CameraId cam, UUID frameId);
    void evictOlderThan(Instant cutoff);
}
public interface ObjectStoragePort {
    void put(String bucket, StorageKey key, byte[] data, String contentType);
    void delete(String bucket, StorageKey key);                   // idempotente
    URL presignGet(String bucket, StorageKey key, Duration ttl);
}
public interface ImageAnnotatorPort { byte[] annotate(byte[] jpeg, List<AnnotationBox> boxes); }
public interface EvidenceRepositoryPort {
    EvidenceItem save(EvidenceItem e);
    Optional<EvidenceItem> findById(TenantId t, EvidenceId id);
    List<EvidenceItem> findByAlert(TenantId t, AlertId a);
    List<EvidenceItem> findExpired(Instant now, int limit);
    boolean existsByAlertAndFrame(AlertId a, UUID frameId);
}
public interface RetentionPolicyRepositoryPort { Optional<RetentionPolicy> findByTenant(TenantId t); }
public interface AuditTrailPort { void record(String action, UUID evidenceId, UUID tenantId, UUID actorId); }
```
### 4.2 Casos de uso
```java
// BufferFrameUseCase: NO transaccional, sin BD. Lo llama el listener por cada frame.
@Service
class BufferFrameService implements BufferFrameUseCase {
    public void handle(Command c) { buffer.put(new BufferedFrame(c.cameraId(), c.frameId(), c.capturedAt(), c.jpeg())); }
}

@Service @Transactional
class CaptureEvidenceForAlertService implements CaptureEvidenceForAlertUseCase {
    private static final String CONSUMER = "evidence.alerts";
    // puertos: processed, buffer, annotator, storage, evidenceRepo, retentionRepo, events, clock, ids  + EvidenceProperties(bucket, defaultRetentionDays) vía puerto/constructor

    public void handle(Command c) {
        if (!processed.markIfFirstTime(c.eventId(), CONSUMER)) return;                       // inbox
        var tenant = new TenantId(c.tenantId()); var alert = new AlertId(c.alertId()); var camera = new CameraId(c.cameraId());
        if (evidence.existsByAlertAndFrame(alert, c.frameId())) return;

        var frame = buffer.find(camera, c.frameId()).orElseThrow(() -> new FrameNotAvailableException(c.frameId()));   // reintentable
        byte[] annotated = annotator.annotate(frame.jpeg(), c.boxes());
        var now = clock.now(); var id = new EvidenceId(ids.newId());
        var key = StorageKey.of(tenant, now, alert, id);
        storage.put(bucket, key, annotated, "image/jpeg");

        var retention = retentionRepo.findByTenant(tenant).orElse(new RetentionPolicy(defaultDays));
        var item = EvidenceItem.create(id, tenant, alert, camera, bucket, key, annotated.length, ContentHash.of(annotated),
                                       c.frameId(), c.capturedAt(), retention, now);
        evidence.save(item);
        events.publish(item.pullEvents());
    }
}
```
`FrameNotAvailableException` **no** está en la lista de no reintentables del starter: el *error handler* reintenta con *backoff* (0.5→8 s, 5 veces). Eso absorbe la carrera "llegó la alerta antes que el frame al buffer". Si se agota, va a `alert.raised.v1.dlq`.
Si falla el commit tras subir a MinIO queda un objeto huérfano: se barre en la purga (objeto sin fila) o por una regla de ciclo de vida del bucket.

```java
@Service @Transactional
class GetEvidenceAccessUrlService implements GetEvidenceAccessUrlQuery {
    public Result handle(Query q) {                                // Query(tenantId, evidenceId, actorId)
        var e = evidence.findById(new TenantId(q.tenantId()), new EvidenceId(q.evidenceId()))
                        .filter(x -> !x.isDeleted()).orElseThrow(() -> new EvidenceNotFoundException(q.evidenceId()));
        var ttl = Duration.ofSeconds(60);                          // vida corta
        var url = storage.presignGet(e.bucket(), e.key(), ttl);
        audit.record("URL_ISSUED", q.evidenceId(), q.tenantId(), q.actorId());                  // CADA acceso queda registrado
        return new Result(url.toString(), clock.now().plus(ttl));
    }
}

@Service                                                           // sin @Transactional de clase: cada paso persiste por separado
class PurgeExpiredEvidenceService implements PurgeExpiredEvidenceUseCase {
    public int handle() {
        var expired = evidence.findExpired(clock.now(), 200);
        for (var e : expired) {
            storage.delete(e.bucket(), e.key());                   // idempotente
            e.markDeleted(clock.now());
            evidence.save(e);
            audit.record("EXPIRED_DELETED", e.id().value(), e.tenantId().value(), null);
        }
        return expired.size();
    }
}
```
`ListEvidenceQuery(tenantId, alertId)` → `List<EvidenceView>` (id, alertId, kind, capturedAt, expiresAt; **sin** URL ni clave de objeto). `DeleteEvidenceUseCase` (solo ADMIN): borra el objeto, `markDeleted`, `audit "DELETED"`.

---

## 5. Infraestructura

### 5.1 Buffer de frames en memoria
```java
@Component
class InMemoryFrameBuffer implements FrameBufferPort {
    private final Map<UUID, Deque<BufferedFrame>> byCamera = new ConcurrentHashMap<>();
    private final int maxPerCamera;                                  // p. ej. 60 (≈ 20-30 s a 2-3 fps)
    InMemoryFrameBuffer(EvidenceProperties p) { this.maxPerCamera = p.buffer().maxFramesPerCamera(); }

    public void put(BufferedFrame f) {
        var q = byCamera.computeIfAbsent(f.cameraId(), k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(f); while (q.size() > maxPerCamera) q.removeFirst(); }
    }
    public Optional<BufferedFrame> find(CameraId cam, UUID frameId) {
        var q = byCamera.get(cam.value()); if (q == null) return Optional.empty();
        synchronized (q) { for (var f : q) if (f.frameId().equals(frameId)) return Optional.of(f); }
        return Optional.empty();
    }
    public void evictOlderThan(Instant cutoff) {
        byCamera.values().forEach(q -> { synchronized (q) { q.removeIf(f -> f.capturedAt().isBefore(cutoff)); } });
        byCamera.values().removeIf(q -> { synchronized (q) { return q.isEmpty(); } });
    }
}
```
Memoria ≈ `frames/seg × segundos × tamaño JPEG × cámaras` (3 fps × 20 s × 100 KB = 6 MB por cámara). Dimensiona `-Xmx` en consecuencia. `FrameBufferEvictionScheduler`: cada 5 s `evictOlderThan(now - 30 s)`.

### 5.2 Listener de frames (binario) — **nunca lanza excepción**
```java
@Component
class FrameBufferListener {
    private final BufferFrameUseCase useCase;
    @KafkaListener(topics = "cortexcam.ingestion.frames.v1", groupId = "evidence.frames",
        properties = { "value.deserializer:org.apache.kafka.common.serialization.ByteArrayDeserializer", "auto.offset.reset:latest" })
    void onFrame(ConsumerRecord<String, byte[]> rec) {
        try {
            var h = rec.headers();
            useCase.handle(new BufferFrameUseCase.Command(
                UUID.fromString(header(h, "camera-id")), UUID.fromString(header(h, "frame-id")),
                Instant.parse(header(h, "captured-at")), rec.value()));
        } catch (Exception e) { /* un frame malo se descarta: reintentarlo o mandarlo a DLQ no tiene sentido (y el DLQ es de texto) */ }
    }
    private static String header(Headers h, String k) { return new String(h.lastHeader(k).value(), StandardCharsets.UTF_8); }
}
```
La sintaxis `clave:valor` de `properties` sobrescribe propiedades del *consumer* solo para este listener.

### 5.3 Listener de alertas
```java
@KafkaListener(topics = "cortexcam.alert.raised.v1", groupId = "evidence.alerts")
void onAlert(ConsumerRecord<String, String> rec) {
    useCase.handle(mapper.toCommand(rec.value()));        // mapper: JSON → AlertRaisedMessage → Command(eventId=ce.id, tenantId, alertId, cameraId, frameId, capturedAt, boxes)
}
```
`AlertRaisedMessage` (contrato) lee `data.alertId`, `data.cameraId`, `data.tenantId`, `data.trigger.frameId`, `data.trigger.capturedAt`, `data.trigger.boxes[]` con `@JsonIgnoreProperties(ignoreUnknown = true)`. Mensaje inválido → `InvalidMessageException`.

### 5.4 MinIO y anotación
```java
@ConfigurationProperties("cortexcam.evidence")
public record EvidenceProperties(Minio minio, Buffer buffer, int defaultRetentionDays) {
    public record Minio(String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket) {}
    public record Buffer(int maxFramesPerCamera) {}
}

@Configuration
class MinioConfig {
    @Bean @Qualifier("minioInternal") MinioClient internal(EvidenceProperties p) {
        return MinioClient.builder().endpoint(p.minio().endpoint()).credentials(p.minio().accessKey(), p.minio().secretKey()).build();
    }
    /** Para FIRMAR URLs con el host que ve el navegador. 'region' evita una llamada de red al firmar. */
    @Bean @Qualifier("minioPublic") MinioClient publicClient(EvidenceProperties p) {
        return MinioClient.builder().endpoint(p.minio().publicEndpoint()).credentials(p.minio().accessKey(), p.minio().secretKey()).region("us-east-1").build();
    }
}

@Component
class MinioObjectStorageAdapter implements ObjectStoragePort {
    public void put(String b, StorageKey k, byte[] d, String ct) {
        try (var in = new ByteArrayInputStream(d)) {
            internal.putObject(PutObjectArgs.builder().bucket(b).object(k.value()).stream(in, d.length, -1).contentType(ct).build());
        } catch (Exception e) { throw new StorageUnavailableException(e); }
    }
    public void delete(String b, StorageKey k) {
        try { internal.removeObject(RemoveObjectArgs.builder().bucket(b).object(k.value()).build()); }
        catch (Exception e) { throw new StorageUnavailableException(e); }
    }
    public URL presignGet(String b, StorageKey k, Duration ttl) {
        try { return new URL(publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(b).object(k.value()).expiry((int) ttl.toSeconds()).build())); }
        catch (Exception e) { throw new StorageUnavailableException(e); }
    }
}
```
```java
@Component
class AwtImageAnnotator implements ImageAnnotatorPort {
    static { System.setProperty("java.awt.headless", "true"); }
    public byte[] annotate(byte[] jpeg, List<AnnotationBox> boxes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            int w = img.getWidth(), h = img.getHeight();
            Graphics2D g = img.createGraphics();
            g.setStroke(new BasicStroke(Math.max(2, w / 400f))); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, w / 60)));
            for (var b : boxes) {
                int x = (int) (b.x1() * w), y = (int) (b.y1() * h), bw = (int) ((b.x2() - b.x1()) * w), bh = (int) ((b.y2() - b.y1()) * h);
                g.setColor(Color.RED); g.drawRect(x, y, bw, bh);
                g.drawString("%s %.2f".formatted(b.label(), b.confidence()), x, Math.max(14, y - 4));
            }
            g.dispose();
            var out = new ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("No se pudo anotar la imagen", e); }
    }
}
```
Usa `ImageIO` (sin OpenCV en Java). El **frame original** no se guarda: la evidencia es la imagen anotada (si necesitas ambas, guarda dos objetos).

### 5.5 Persistencia, Outbox, auditoría
- `EvidenceJpaEntity` (`@Version`), `SpringDataEvidenceRepository` (`findByIdAndTenantId`, `findByTenantIdAndAlertId…`, `@Query` de expiradas `deletedAt is null and expiresAt <= :now` con `Pageable`, `existsByAlertIdAndFrameId`), `EvidencePersistenceAdapter`, `EvidencePersistenceMapper`.
- `DbAuditTrailAdapter` inserta en `evidence_access_log` con `JdbcTemplate` y `MDC.get("traceId")`. **Sin** `UPDATE`/`DELETE` sobre esa tabla (el usuario de BD del servicio puede tener solo `INSERT, SELECT` sobre ella).
- `EventContractMapper`: `EvidenceStored` → `cortexcam.evidence.stored.v1`, key = `alertId`, `data = {evidenceId, alertId, cameraId, tenantId, kind, capturedAt, expiresAt}` (sin URL).

### 5.6 REST
| Ruta | Rol | Respuesta |
|---|---|---|
| `GET /api/v1/evidence?alertId=` | autenticado | lista de `EvidenceView` |
| `GET /api/v1/evidence/{id}/url` | autenticado | `{url, expiresAt}` (60 s); registra el acceso |
| `DELETE /api/v1/evidence/{id}` | ADMIN | 204 |

`EvidenceRestExceptionHandler`: `EvidenceNotFoundException` → 404 `EVIDENCE_NOT_FOUND`; `StorageUnavailableException` → 503 `STORAGE_UNAVAILABLE`.
`EvidencePurgeScheduler`: `@Scheduled(cron = "0 30 3 * * *")` → `PurgeExpiredEvidenceUseCase`.

### 5.7 `.env` y `application.yml`
```
SERVICE_NAME=evidence-service
SERVER_PORT=8093
DB_URL=jdbc:postgresql://localhost:5432/evidence_db
DB_USER=evidence_svc
DB_PASSWORD=evidence_dev
MINIO_ENDPOINT=http://localhost:9000
MINIO_PUBLIC_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minio
MINIO_SECRET_KEY=minio-dev-secret
```
```yaml
cortexcam:
  evidence:
    minio:  { endpoint: "${MINIO_ENDPOINT}", public-endpoint: "${MINIO_PUBLIC_ENDPOINT}", access-key: "${MINIO_ACCESS_KEY}", secret-key: "${MINIO_SECRET_KEY}", bucket: evidence }
    buffer: { max-frames-per-camera: 60 }
    default-retention-days: 30
```
En producción crea un **usuario MinIO propio** de `evidence-service` con permiso solo sobre el bucket `evidence` (no uses el root) y activa HTTPS.

---

## 6. Pruebas
| Nivel | Qué |
|---|---|
| Dominio | `StorageKey`, `RetentionPolicy`, `isExpired` |
| Use case (fakes: `InMemoryFrameBuffer`, `FakeObjectStorage`, `FakeAnnotator`) | captura feliz (guarda objeto + fila + 1 evento); frame ausente → `FrameNotAvailableException`; misma alerta+frame dos veces → una evidencia; `GetEvidenceAccessUrl` registra auditoría; purga borra objeto y marca `deleted_at` |
| Persistencia (Testcontainers) | Flyway V1–V2; `findExpired` respeta `deleted_at`; unicidad `(alert_id, frame_id, kind)` |
| Almacenamiento (Testcontainers MinIO) | `put` + `presignGet` + descarga por HTTP; `delete` idempotente |
| Anotador | Imagen 640×480 + una caja → bytes JPEG válidos y distintos del original |
| Mensajería | `FrameBufferListener` con cabecera faltante no lanza; `AlertRaisedMapper` con ejemplo válido e inválido |
| REST | 401/403, 404 de evidencia ajena a otro tenant |

## 7. Probar a mano
1. Con ingestion publicando frames, deja correr ≥10 s; publica una detección falsa **con el `frameId` de un frame reciente** (cópialo de una cabecera en Redpanda Console) vía `alert-service` o directo en `alert.raised.v1`.
2. MinIO Console (`http://localhost:9001`) → bucket `evidence` → aparece `…/<alertId>/<evidenceId>.jpg`.
3. `GET /api/v1/evidence?alertId=<id>` → 1 elemento; `GET …/{id}/url` → abre la URL: se ve el frame con la caja roja; a los 60 s la URL deja de funcionar.
4. `evidence_access_log` tiene una fila `URL_ISSUED` por cada petición.
5. Cambia `expires_at` de una fila al pasado y ejecuta la purga: el objeto desaparece y `deleted_at` se llena.

## 8. Listo cuando
- [ ] Cada `alert.raised` produce una imagen anotada en MinIO y un `evidence.stored.v1`; `alert-service` adjunta la referencia.
- [ ] Reentregar el mismo `alert.raised` no duplica evidencia ni objetos.
- [ ] Ninguna respuesta REST expone claves de objeto ni URLs permanentes.
- [ ] Todo acceso y todo borrado quedan en `evidence_access_log`.
- [ ] La memoria del buffer es estable con tus cámaras (revísala en `/actuator/metrics`).
