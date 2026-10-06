# 05 — `alert-service` (Java · puerto 8092 · BD `alert_db`)

**Responsabilidad única:** decidir **cuándo una detección merece una alerta** (umbral, horario, confirmación en varios frames, enfriamiento, lista de placas) y gestionar su ciclo de vida (`OPEN → ACKNOWLEDGED → RESOLVED`, o `FALSE_POSITIVE`).
**Consume:** `detection.person|weapon|vehicle-detected.v1` y `detection.plate-recognized.v1` (grupo `alert.detections`), `evidence.stored.v1` (grupo `alert.evidence`).
**Publica (Outbox):** `alert.raised.v1`, `alert.lifecycle.v1`.
**No hace:** detectar, enviar avisos, guardar imágenes.

Reemplaza la lógica de tu `IA_ENTRENADA.py` (`ultima_alerta` global, `conf < 0.4`, guardar captura en cada frame) por políticas de dominio testeables.

---

## 1. Árbol (🆕 todo salvo lo indicado)

```
services/alert-service/
├── pom.xml ✅(movido en C-01) · .env.example · README.md
└── src/main/
    ├── java/com/cortexcam/alert/
    │   ├── AlertApplication.java                                       (+ @ConfigurationPropertiesScan)
    │   ├── domain/
    │   │   ├── model/
    │   │   │   ├── alert/   Alert · AlertId · AlertType · AlertStatus · Severity · DetectionRef · DetectionBox · EvidenceRef
    │   │   │   ├── rule/    AlertRule · RuleId · Schedule · DetectionHit
    │   │   │   ├── watchlist/ WatchedPlate · WatchedPlateId
    │   │   │   └── shared/  TenantId · CameraId · UserId · Confidence
    │   │   ├── event/       DomainEvent · AlertRaised · AlertAcknowledged · AlertResolved · AlertMarkedFalsePositive
    │   │   ├── policy/      AlertEvaluationPolicy
    │   │   └── exception/   DomainException · AlertNotFoundException · InvalidAlertTransitionException · DomainValidationException
    │   ├── application/
    │   │   ├── port/in/alert/      EvaluateDetectionUseCase · AcknowledgeAlertUseCase · ResolveAlertUseCase · MarkFalsePositiveUseCase
    │   │   │                       AttachEvidenceUseCase · ListAlertsQuery · GetAlertQuery · PageResult · AlertView
    │   │   ├── port/in/rule/       UpsertAlertRuleUseCase · DeleteAlertRuleUseCase · ListAlertRulesQuery · AlertRuleView
    │   │   ├── port/in/watchlist/  AddWatchedPlateUseCase · RemoveWatchedPlateUseCase · ListWatchedPlatesQuery
    │   │   ├── port/in/maintenance/PurgeOldDetectionHitsUseCase
    │   │   ├── port/out/alert/     AlertRepositoryPort · AlertSearchCriteria · AlertRuleRepositoryPort · DetectionWindowPort · WatchlistRepositoryPort
    │   │   ├── port/out/shared/    ClockPort · IdGeneratorPort · DomainEventPublisherPort · ProcessedMessagePort · AuditTrailPort
    │   │   └── usecase/{command,query}/   (un *Service por puerto de entrada)
    │   └── infrastructure/
    │       ├── adapters/in/
    │       │   ├── messaging/    DetectionEventsListener · EvidenceStoredListener · DetectionMessageMapper · contract/(DetectionEventMessage · EvidenceStoredMessage)
    │       │   ├── rest/         controller/(AlertController · AlertRuleController · WatchlistController) · dto/ · mapper/ · advice/AlertRestExceptionHandler
    │       │   └── scheduler/    DetectionHitPurgeScheduler
    │       ├── adapters/out/
    │       │   ├── persistence/  entity/ · repository/ · mapper/ · adapter/(AlertPersistenceAdapter · AlertRulePersistenceAdapter · DetectionWindowAdapter · WatchlistPersistenceAdapter)
    │       │   ├── messaging/    OutboxEntity · SpringDataOutboxRepository · OutboxEventPublisherAdapter · EventContractMapper
    │       │   └── system/       SystemClockAdapter · UuidV7GeneratorAdapter · ProcessedMessageAdapter · AuditTrailLogAdapter
    │       ├── config/           AlertProperties · UseCaseConfig (si necesitas wiring manual)
    │       └── security/ · health/ · tracing/   (vacíos: starter)
    └── resources/ application.yml · db/migration/{V1__outbox_inbox.sql ✅, V2__alert.sql 🆕}
```

---

## 2. Base de datos — `V2__alert.sql`

```sql
CREATE TABLE alert (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid         NOT NULL,
  camera_id             uuid         NOT NULL,
  type                  varchar(16)  NOT NULL,
  severity              varchar(10)  NOT NULL,
  status                varchar(16)  NOT NULL,
  raised_at             timestamptz  NOT NULL,
  trigger_event_id      varchar(160) NOT NULL,
  trigger_frame_id      uuid         NOT NULL,
  trigger_captured_at   timestamptz  NOT NULL,
  trigger_confidence    numeric(5,4) NOT NULL,
  trigger_model_version varchar(80)  NOT NULL,
  detection_count       int          NOT NULL DEFAULT 1,
  plate_text            varchar(12),
  trigger_boxes         text         NOT NULL DEFAULT '[]',      -- JSON: [{label,confidence,box{x1,y1,x2,y2}}]
  acknowledged_by uuid, acknowledged_at timestamptz,
  resolved_by     uuid, resolved_at     timestamptz,
  evidence_refs         text         NOT NULL DEFAULT '[]',      -- JSON: [{evidenceId,kind}]
  version               bigint       NOT NULL DEFAULT 0,
  created_at            timestamptz  NOT NULL DEFAULT now(),
  updated_at            timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT ck_alert_type     CHECK (type     IN ('PERSON','WEAPON','VEHICLE','PLATE_MATCH')),
  CONSTRAINT ck_alert_severity CHECK (severity IN ('INFO','WARNING','CRITICAL')),
  CONSTRAINT ck_alert_status   CHECK (status   IN ('OPEN','ACKNOWLEDGED','RESOLVED','FALSE_POSITIVE'))
);
CREATE INDEX ix_alert_tenant_raised ON alert (tenant_id, raised_at DESC);
CREATE INDEX ix_alert_cam_type      ON alert (camera_id, type, raised_at DESC);
CREATE INDEX ix_alert_open          ON alert (tenant_id, raised_at DESC) WHERE status = 'OPEN';

CREATE TABLE alert_rule (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  camera_id uuid,                                               -- NULL = todas las cámaras del tenant
  type varchar(16) NOT NULL, enabled boolean NOT NULL DEFAULT true,
  min_confidence numeric(5,4) NOT NULL, severity varchar(10) NOT NULL,
  cooldown_seconds int NOT NULL DEFAULT 10,
  confirmation_hits int NOT NULL DEFAULT 1, confirmation_window_seconds int NOT NULL DEFAULT 5,
  schedule text,                                                -- JSON o NULL (= siempre activa)
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_alert_rule_scope
  ON alert_rule (tenant_id, COALESCE(camera_id, '00000000-0000-0000-0000-000000000000'::uuid), type);

CREATE TABLE detection_hit (                                    -- ventana para "N detecciones en M segundos"
  id bigserial PRIMARY KEY, camera_id uuid NOT NULL, type varchar(16) NOT NULL,
  detected_at timestamptz NOT NULL, event_id varchar(160) NOT NULL, confidence numeric(5,4) NOT NULL
);
CREATE INDEX ix_hit_lookup ON detection_hit (camera_id, type, detected_at DESC);

CREATE TABLE watchlist_plate (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, plate varchar(10) NOT NULL, label varchar(120),
  severity varchar(10) NOT NULL DEFAULT 'WARNING', enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (tenant_id, plate)
);
```
JSON como `text` (mismo motivo que en `camera-service`). `V1` ya trae `outbox_event` y `processed_message`.

---

## 3. Dominio

### 3.1 Valores y agregado
```java
public enum AlertType { PERSON, WEAPON, VEHICLE, PLATE_MATCH }
public enum Severity  { INFO, WARNING, CRITICAL }
public enum AlertStatus { OPEN, ACKNOWLEDGED, RESOLVED, FALSE_POSITIVE }

public record DetectionBox(String label, double confidence, double x1, double y1, double x2, double y2) {}
public record EvidenceRef(UUID evidenceId, String kind) {}
public record DetectionRef(String eventId, UUID frameId, Instant capturedAt, Confidence confidence, String modelVersion,
                           int detectionCount, List<DetectionBox> boxes, String plateText /* nullable */) {}
```
```java
public final class Alert {
    private final AlertId id; private final TenantId tenantId; private final CameraId cameraId;
    private final AlertType type; private final Severity severity; private final Instant raisedAt;
    private final DetectionRef trigger;
    private AlertStatus status; private UserId acknowledgedBy; private Instant acknowledgedAt;
    private UserId resolvedBy; private Instant resolvedAt;
    private final List<EvidenceRef> evidence;
    private final long version;
    private final List<DomainEvent> pending = new ArrayList<>();
    // constructor privado ...

    public static Alert raise(AlertId id, TenantId t, CameraId cam, AlertType type, Severity sev, DetectionRef trigger, Instant now) {
        var a = new Alert(id, t, cam, type, sev, now, trigger, AlertStatus.OPEN, null, null, null, null, List.of(), 0L);
        a.pending.add(new AlertRaised(id, t, cam, type, sev, now, trigger));
        return a;
    }
    public static Alert reconstitute(/* todos los campos */) { ... }          // solo para el mapper

    public void acknowledge(UserId by, Instant now) {
        require(status == AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED);
        status = AlertStatus.ACKNOWLEDGED; acknowledgedBy = by; acknowledgedAt = now;
        pending.add(new AlertAcknowledged(id, tenantId, cameraId, type, by, now));
    }
    public void resolve(UserId by, Instant now) {
        require(status == AlertStatus.OPEN || status == AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED);
        status = AlertStatus.RESOLVED; resolvedBy = by; resolvedAt = now;
        pending.add(new AlertResolved(id, tenantId, cameraId, type, by, now));
    }
    public void markFalsePositive(UserId by, Instant now) {
        require(status == AlertStatus.OPEN || status == AlertStatus.ACKNOWLEDGED, AlertStatus.FALSE_POSITIVE);
        status = AlertStatus.FALSE_POSITIVE; resolvedBy = by; resolvedAt = now;
        pending.add(new AlertMarkedFalsePositive(id, tenantId, cameraId, type, by, now));   // alimenta el reentrenamiento (Fase 6)
    }
    public void attachEvidence(EvidenceRef ref) { if (evidence.stream().noneMatch(e -> e.evidenceId().equals(ref.evidenceId()))) evidence.add(ref); }  // sin evento; idempotente

    private void require(boolean ok, AlertStatus target) { if (!ok) throw new InvalidAlertTransitionException(id.value(), status, target); }
    public List<DomainEvent> pullEvents() { var c = List.copyOf(pending); pending.clear(); return c; }
}
```
(`evidence` es una lista mutable interna; `reconstitute` la copia.) Eventos: `AlertRaised(alertId, tenantId, cameraId, type, severity, raisedAt, trigger)`; `AlertAcknowledged|Resolved|MarkedFalsePositive(alertId, tenantId, cameraId, type, actorId, occurredAt)`.

### 3.2 Regla, horario y política
```java
public record Schedule(ZoneId zone, List<Window> windows) {
    public record Window(Set<DayOfWeek> days, LocalTime from, LocalTime to) {
        public Window { if (from.equals(to)) throw new DomainValidationException("from y to no pueden ser iguales"); }
    }
    public static Schedule always() { return new Schedule(ZoneOffset.UTC, List.of()); }
    public boolean isActive(Instant at) {
        if (windows.isEmpty()) return true;
        var z = at.atZone(zone); var t = z.toLocalTime(); var d = z.getDayOfWeek();
        for (var w : windows) {
            if (w.from().isBefore(w.to())) {
                if (w.days().contains(d) && !t.isBefore(w.from()) && t.isBefore(w.to())) return true;
            } else {                                                   // cruza medianoche (22:00–06:00)
                if (w.days().contains(d) && !t.isBefore(w.from())) return true;
                if (w.days().contains(d.minus(1)) && t.isBefore(w.to())) return true;
            }
        }
        return false;
    }
}

public record AlertRule(RuleId id, TenantId tenantId, CameraId cameraId /* null = todas */, AlertType type, boolean enabled,
                        Confidence minConfidence, Severity severity, Duration cooldown, int confirmationHits,
                        Duration confirmationWindow, Schedule schedule) {
    public AlertRule {
        if (confirmationHits < 1 || confirmationHits > 20) throw new DomainValidationException("confirmationHits: 1..20");
        if (cooldown.isNegative()) throw new DomainValidationException("cooldown negativo");
    }
    public static AlertRule defaultFor(TenantId t, AlertType type) {
        return switch (type) {
            case WEAPON      -> new AlertRule(null, t, null, type, true, new Confidence(0.60), Severity.CRITICAL, Duration.ofSeconds(30), 3, Duration.ofSeconds(5), Schedule.always());
            case PLATE_MATCH -> new AlertRule(null, t, null, type, true, new Confidence(0.70), Severity.WARNING,  Duration.ofSeconds(60), 1, Duration.ofSeconds(5), Schedule.always());
            case VEHICLE     -> new AlertRule(null, t, null, type, true, new Confidence(0.50), Severity.INFO,     Duration.ofSeconds(15), 1, Duration.ofSeconds(5), Schedule.always());
            default          -> new AlertRule(null, t, null, type, true, new Confidence(0.50), Severity.INFO,     Duration.ofSeconds(10), 1, Duration.ofSeconds(5), Schedule.always());
        };
    }
}
```
```java
// domain/policy/AlertEvaluationPolicy.java — pura: sin reloj, sin BD
public final class AlertEvaluationPolicy {
    public enum Decision { PASS, DISABLED, OUT_OF_SCHEDULE, LOW_CONFIDENCE, AWAITING_CONFIRMATION, COOLDOWN, RAISE }

    /** Fase 1: ¿esta detección cuenta siquiera? (si no, ni se registra como "hit"). */
    public Decision precheck(AlertRule r, Confidence c, Instant now) {
        if (!r.enabled()) return Decision.DISABLED;
        if (!r.schedule().isActive(now)) return Decision.OUT_OF_SCHEDULE;
        if (!c.isAtLeast(r.minConfidence())) return Decision.LOW_CONFIDENCE;
        return Decision.PASS;
    }
    /** Fase 2: con los hits recientes ya contados. */
    public Decision decide(AlertRule r, int hitsInWindow, Optional<Instant> lastRaisedAt, Instant now) {
        if (hitsInWindow < r.confirmationHits()) return Decision.AWAITING_CONFIRMATION;
        boolean cooled = lastRaisedAt.map(l -> Duration.between(l, now).compareTo(r.cooldown()) >= 0).orElse(true);
        return cooled ? Decision.RAISE : Decision.COOLDOWN;
    }
}
```
Tests de dominio (tabla): umbral exacto, horario nocturno cruzando medianoche (`22:00–06:00`, lunes 23:00 sí, martes 05:00 sí si lunes está en `days`, martes 07:00 no), `hits=2` con `confirmationHits=3` → `AWAITING_CONFIRMATION`, cooldown justo en el límite, transiciones inválidas del agregado.

---

## 4. Aplicación

### 4.1 Puertos de entrada principales
```java
public interface EvaluateDetectionUseCase {
    Outcome handle(Command c);
    enum DetectionKind { PERSON, WEAPON, VEHICLE, PLATE }
    record Box(String label, double confidence, double x1, double y1, double x2, double y2) {}
    record Command(String eventId, UUID tenantId, UUID cameraId, DetectionKind kind, double confidence, int detectionCount,
                   UUID frameId, Instant capturedAt, String modelVersion, List<Box> boxes, String plateText) {}
    enum Outcome { ALERT_RAISED, IGNORED_DUPLICATE, IGNORED_DISABLED, IGNORED_OUT_OF_SCHEDULE, IGNORED_LOW_CONFIDENCE,
                   IGNORED_NOT_WATCHED, AWAITING_CONFIRMATION, IGNORED_COOLDOWN }
}
```
Otros: `AcknowledgeAlertUseCase.Command(tenantId, alertId, actorId)` (igual `Resolve` y `MarkFalsePositive`), `AttachEvidenceUseCase.Command(eventId, tenantId, alertId, evidenceId, kind)`, `ListAlertsQuery.Query(tenantId, status?, type?, cameraId?, from?, to?, page, size)` → `PageResult<AlertView>`, `GetAlertQuery(tenantId, id)`, `UpsertAlertRuleUseCase.Command(tenantId, cameraId?, type, enabled, minConfidence, severity, cooldownSeconds, confirmationHits, confirmationWindowSeconds, schedule?)`, y los de lista de placas.

### 4.2 Puertos de salida
```java
public interface AlertRepositoryPort {
    Alert save(Alert a);
    Optional<Alert> findById(TenantId t, AlertId id);
    Optional<Instant> findLastRaisedAt(TenantId t, CameraId cam, AlertType type);
    PageResult<Alert> search(AlertSearchCriteria c);
}
public interface AlertRuleRepositoryPort {
    Optional<AlertRule> findEffective(TenantId t, CameraId cam, AlertType type);   // específica de la cámara; si no, la global; si no, vacío
    AlertRule save(AlertRule r);  List<AlertRule> findAll(TenantId t);  void delete(TenantId t, RuleId id);
}
public interface DetectionWindowPort {
    void record(DetectionHit hit);
    int countSince(CameraId cam, AlertType type, Instant since);
    void purgeBefore(Instant cutoff);
}
public interface WatchlistRepositoryPort { Optional<WatchedPlate> findEnabled(TenantId t, String plate); /* add, remove, list */ }
public interface AuditTrailPort { void record(String action, UUID tenantId, UUID actorId, String targetId); }
```

### 4.3 Caso de uso central
```java
@Service @Transactional
class EvaluateDetectionService implements EvaluateDetectionUseCase {
    private static final String CONSUMER = "alert.detections";
    // puertos: alerts, rules, window, watchlist, processed, events, clock, ids  +  AlertEvaluationPolicy policy

    @Override
    public Outcome handle(Command c) {
        if (!processed.markIfFirstTime(c.eventId(), CONSUMER)) return Outcome.IGNORED_DUPLICATE;           // inbox

        var tenant = new TenantId(c.tenantId()); var camera = new CameraId(c.cameraId());
        var confidence = new Confidence(c.confidence()); var now = clock.now();

        AlertType type = switch (c.kind()) { case PERSON -> AlertType.PERSON; case WEAPON -> AlertType.WEAPON;
                                              case VEHICLE -> AlertType.VEHICLE; case PLATE -> AlertType.PLATE_MATCH; };
        Severity forced = null;
        if (c.kind() == DetectionKind.PLATE) {                                  // una placa solo alerta si está en la lista
            var watched = watchlist.findEnabled(tenant, c.plateText());
            if (watched.isEmpty()) return Outcome.IGNORED_NOT_WATCHED;
            forced = watched.get().severity();
        }

        var rule = rules.findEffective(tenant, camera, type).orElseGet(() -> AlertRule.defaultFor(tenant, type));
        var pre = policy.precheck(rule, confidence, now);
        if (pre != AlertEvaluationPolicy.Decision.PASS) return map(pre);

        window.record(new DetectionHit(camera, type, c.capturedAt(), c.eventId(), confidence));
        int hits = window.countSince(camera, type, now.minus(rule.confirmationWindow()));
        var decision = policy.decide(rule, hits, alerts.findLastRaisedAt(tenant, camera, type), now);
        if (decision != AlertEvaluationPolicy.Decision.RAISE) return map(decision);

        var trigger = new DetectionRef(c.eventId(), c.frameId(), c.capturedAt(), confidence, c.modelVersion(), c.detectionCount(),
                c.boxes().stream().map(b -> new DetectionBox(b.label(), b.confidence(), b.x1(), b.y1(), b.x2(), b.y2())).toList(), c.plateText());
        var alert = Alert.raise(new AlertId(ids.newId()), tenant, camera, type, forced != null ? forced : rule.severity(), trigger, now);
        alerts.save(alert);
        events.publish(alert.pullEvents());                                      // outbox en ESTA transacción
        return Outcome.ALERT_RAISED;
    }
}
```
Los demás `*Service` siguen el molde *cargar (tenant + id) → método del agregado → guardar → publicar*. `AcknowledgeAlertService` además llama `audit.record("ALERT_ACK", …)`. `AttachEvidenceService`: inbox → `alerts.findById(...)` → si no existe, ignora (log) → `attachEvidence` → `save`. `PurgeOldDetectionHitsService`: `window.purgeBefore(now - retención)`.

---

## 5. Infraestructura

### 5.1 Entrada Kafka — contrato y traducción (ACL)
Un solo *record* de contrato sirve a los cuatro tópicos de detección:
```java
@JsonIgnoreProperties(ignoreUnknown = true)
record DetectionEventMessage(String id, String type, String tenantid, Data data) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(UUID cameraId, UUID frameId, Instant capturedAt, String modelVersion, List<Item> detections) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String label, double confidence, Box box, Map<String, Object> attributes) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Box(double x1, double y1, double x2, double y2) {}
}
```
```java
@Component
class DetectionMessageMapper {
    private final JsonMapper json;

    List<EvaluateDetectionUseCase.Command> toCommands(String topic, String payload) {
        DetectionEventMessage m;
        try { m = json.readValue(payload, DetectionEventMessage.class); }
        catch (JacksonException e) { throw new InvalidMessageException("detección inválida", e); }     // → DLQ sin reintentos
        if (m.tenantid() == null || m.data() == null || m.data().detections() == null || m.data().detections().isEmpty())
            throw new InvalidMessageException("detección incompleta", null);

        var kind = kindOf(topic);
        var d = m.data(); var tenant = UUID.fromString(m.tenantid());
        var boxes = d.detections().stream().map(i -> new EvaluateDetectionUseCase.Box(i.label(), i.confidence(),
                i.box().x1(), i.box().y1(), i.box().x2(), i.box().y2())).toList();

        if (kind == DetectionKind.PLATE) {                                    // cada placa es independiente: un Command por placa
            var out = new ArrayList<EvaluateDetectionUseCase.Command>();
            for (int idx = 0; idx < d.detections().size(); idx++) {
                var i = d.detections().get(idx); var plate = i.attributes() == null ? null : String.valueOf(i.attributes().get("plateText"));
                if (plate == null || plate.equals("null")) continue;
                out.add(new EvaluateDetectionUseCase.Command(m.id() + "#" + idx, tenant, d.cameraId(), kind, i.confidence(), 1,
                        d.frameId(), d.capturedAt(), d.modelVersion(), List.of(boxes.get(idx)), plate));
            }
            return out;
        }
        double max = d.detections().stream().mapToDouble(DetectionEventMessage.Item::confidence).max().orElse(0);
        return List.of(new EvaluateDetectionUseCase.Command(m.id(), tenant, d.cameraId(), kind, max, d.detections().size(),
                d.frameId(), d.capturedAt(), d.modelVersion(), boxes, null));
    }
    private DetectionKind kindOf(String topic) {
        if (topic.contains("person-detected"))  return DetectionKind.PERSON;
        if (topic.contains("weapon-detected"))  return DetectionKind.WEAPON;
        if (topic.contains("vehicle-detected")) return DetectionKind.VEHICLE;
        if (topic.contains("plate-recognized")) return DetectionKind.PLATE;
        throw new InvalidMessageException("tópico desconocido: " + topic, null);
    }
}

@Component
class DetectionEventsListener {
    private final EvaluateDetectionUseCase useCase;  private final DetectionMessageMapper mapper;     // depende de la interfaz (R-05)
    @KafkaListener(groupId = "alert.detections", topics = {
        "cortexcam.detection.person-detected.v1", "cortexcam.detection.weapon-detected.v1",
        "cortexcam.detection.vehicle-detected.v1", "cortexcam.detection.plate-recognized.v1" })
    void onMessage(ConsumerRecord<String, String> rec) {
        mapper.toCommands(rec.topic(), rec.value()).forEach(useCase::handle);
    }
}
```
`EvidenceStoredListener` (`alert.evidence`, tópico `evidence.stored.v1`): parsea `{evidenceId, alertId, tenantId, kind}` y llama `AttachEvidenceUseCase`.
Errores: el `DefaultErrorHandler` del starter reintenta 5 veces con *backoff* y manda a `<tópico>.dlq`; `InvalidMessageException` va directo a DLQ.

### 5.2 Salida Kafka (Outbox) ✏️ `EventContractMapper`
- `AlertRaised` → tópico `cortexcam.alert.raised.v1`, **key = `cameraId`**, `data = {alertId, cameraId, tenantId, type, severity, raisedAt, trigger{eventId, frameId, capturedAt, confidence, modelVersion, detectionCount, plateText, boxes[{label,confidence,box{x1,y1,x2,y2}}]}}`.
- `AlertAcknowledged|Resolved|MarkedFalsePositive` → `cortexcam.alert.lifecycle.v1`, **key = `alertId`**, `data = {alertId, cameraId, tenantId, type, change (ACKNOWLEDGED|RESOLVED|FALSE_POSITIVE), actorId, occurredAt}`.
Mismo código que el de `camera-service` (switch por tipo de evento + `CloudEventEnvelope.of(...)`). Activa `cortexcam.outbox.enabled=true`.

### 5.3 Persistencia
Entidades `AlertJpaEntity`, `AlertRuleJpaEntity`, `DetectionHitJpaEntity`, `WatchedPlateJpaEntity` (con `@Version` en alert y rule). Los JSON (`trigger_boxes`, `evidence_refs`, `schedule`) son `String` serializados con el `JsonMapper` en el mapper. Búsqueda paginada con **Specification** (evita el problema de parámetros `null` tipados en PostgreSQL):
```java
interface SpringDataAlertRepository extends JpaRepository<AlertJpaEntity, UUID>, JpaSpecificationExecutor<AlertJpaEntity> {
    Optional<AlertJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    @Query("select max(a.raisedAt) from AlertJpaEntity a where a.tenantId = :t and a.cameraId = :c and a.type = :type")
    Optional<Instant> findLastRaisedAt(@Param("t") UUID t, @Param("c") UUID c, @Param("type") String type);
}

static Specification<AlertJpaEntity> spec(AlertSearchCriteria c) {
    return (root, q, cb) -> {
        var p = new ArrayList<Predicate>();
        p.add(cb.equal(root.get("tenantId"), c.tenantId()));
        if (c.status() != null)   p.add(cb.equal(root.get("status"), c.status().name()));
        if (c.type() != null)     p.add(cb.equal(root.get("type"), c.type().name()));
        if (c.cameraId() != null) p.add(cb.equal(root.get("cameraId"), c.cameraId()));
        if (c.from() != null)     p.add(cb.greaterThanOrEqualTo(root.get("raisedAt"), c.from()));
        if (c.to() != null)       p.add(cb.lessThan(root.get("raisedAt"), c.to()));
        return cb.and(p.toArray(Predicate[]::new));
    };
}
```
Orden `raisedAt DESC`, `size` máximo 100. `DetectionWindowAdapter`: `insert` y `select count(*) … where camera_id=? and type=? and detected_at >= ?` (JPQL con `@Query`) y `delete … where detected_at < ?`.
`AlertRulePersistenceAdapter.findEffective`: busca `(tenant, camera, type)` y, si no hay, `(tenant, camera is null, type)`.

### 5.4 REST
| Ruta | Rol | Notas |
|---|---|---|
| `GET /api/v1/alerts?status&type&cameraId&from&to&page&size` | autenticado | `{items,total,page,size}` |
| `GET /api/v1/alerts/{id}` | autenticado | incluye `trigger`, `evidenceRefs` |
| `POST /api/v1/alerts/{id}/acknowledge` · `/resolve` · `/false-positive` | ADMIN, OPERATOR | 409 `ALERT_INVALID_TRANSITION` |
| `GET /api/v1/alert-rules` · `PUT /api/v1/alert-rules` · `DELETE /api/v1/alert-rules/{id}` | lectura: autenticado · escritura: ADMIN | `PUT` hace *upsert* por `(cameraId?, type)` |
| `GET/POST/DELETE /api/v1/watchlist` | ADMIN, OPERATOR | `POST {plate, label?, severity}` normaliza la placa (mayúsculas, sin guiones) |

Mismo molde que `camera-service` (controller → `CurrentActor.from(jwt)` → `Command` → use case → DTO). Handler de negocio:
```java
@RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)
class AlertRestExceptionHandler {
    @ExceptionHandler(AlertNotFoundException.class)          ProblemDetail nf(DomainException e)  { return p(HttpStatus.NOT_FOUND, e); }
    @ExceptionHandler(InvalidAlertTransitionException.class) ProblemDetail tr(DomainException e)  { return p(HttpStatus.CONFLICT, e); }
    @ExceptionHandler(DomainValidationException.class)       ProblemDetail v(DomainException e)   { return p(HttpStatus.UNPROCESSABLE_ENTITY, e); }
    // p(...) igual que en camera-service: code + traceId
}
```
`DetectionHitPurgeScheduler`: `@Scheduled(fixedDelay = 300_000)` → `PurgeOldDetectionHitsUseCase` (retención `cortexcam.alert.hit-retention-minutes=60`).

### 5.5 `.env` y `application.yml`
```
SERVICE_NAME=alert-service
SERVER_PORT=8092
DB_URL=jdbc:postgresql://localhost:5432/alert_db
DB_USER=alert_svc
DB_PASSWORD=alert_dev
```
Plantilla de 01 §1.6 más `cortexcam.alert.hit-retention-minutes: 60`.

---

## 6. Pruebas
| Nivel | Qué |
|---|---|
| Dominio | Tabla de `AlertEvaluationPolicy`; `Schedule` con ventanas nocturnas; transiciones de `Alert` |
| Use case (fakes en memoria) | `EvaluateDetectionServiceTest`: 1 alerta y luego cooldown; mismo `eventId` dos veces → duplicado; arma con 3 hits requeridos → `AWAITING_CONFIRMATION`, `AWAITING_CONFIRMATION`, `ALERT_RAISED`; placa fuera de lista → `IGNORED_NOT_WATCHED`; regla de cámara pisa la global; horario fuera de ventana |
| Mapper | Con `contracts/events/examples/*.json`: persona → 1 Command con la confianza máxima; placas → 1 Command por placa; JSON roto → `InvalidMessageException` |
| Persistencia (Testcontainers) | Flyway V1–V2; `search` con filtros combinados; `findEffective` específica vs global; `@Version` |
| REST (`@WebMvcTest`) | 401/403/404/409/422; VIEWER no puede `acknowledge` |
| Arquitectura | `ArchitectureTest` en verde |

## 7. Probar a mano (sin detectores)
1. Publica una detección falsa en una sola línea (minifica `person-detected.v1.json` y pon tu `cameraId` y `tenantid` `00000000-0000-4000-8000-000000000001`):
   ```bat
   type person-detected.line.json | docker compose -f platform\docker-compose.yml exec -T redpanda rpk topic produce cortexcam.detection.person-detected.v1 -k <cameraId>
   ```
2. `GET /api/v1/alerts` → una alerta `PERSON`/`INFO`/`OPEN`. Repite el mensaje con otro `id`: en 10 s **no** crea otra (cooldown); mismo `id` → ignorado (inbox).
3. Redpanda Console → `alert.raised.v1` tiene el evento con `boxes`.
4. `POST …/acknowledge` → 200; otra vez → 409; `alert.lifecycle.v1` recibe `ACKNOWLEDGED`.
5. `PUT /api/v1/alert-rules` para `WEAPON` con `confirmationHits=3` y publica 3 eventos `weapon-detected` seguidos: solo el tercero genera alerta `CRITICAL`.

## 8. Listo cuando
- [ ] Una detección válida produce **una** alerta y **un** `alert.raised.v1` (Outbox), y repetir el mensaje no duplica nada.
- [ ] Cooldown, confirmación N-de-M, horario y lista de placas funcionan y están cubiertos por tests.
- [ ] `evidence.stored.v1` adjunta la referencia a la alerta.
- [ ] Ningún test necesita Kafka ni PostgreSQL salvo los de adaptador.
- [ ] Fallos de mensaje van a `<tópico>.dlq` y no bloquean la partición.
