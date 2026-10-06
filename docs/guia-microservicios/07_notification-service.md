# 07 — `notification-service` (Java · puerto 8094 · BD `notification_db`)

**Responsabilidad única:** avisar a las personas correctas, por el canal correcto, en el momento correcto cuando se levanta una alerta.
**Consume:** `alert.raised.v1` (grupo `notification.alerts`). **Publica:** nada en esta fase (`cortexcam.outbox.enabled=false`).
**Canales:** `IN_APP` (SSE al navegador, **completo**) y `EMAIL` (SMTP, **completo**); `TELEGRAM` y `WEBHOOK` se agregan con el mismo patrón (§5.5).
**No hace:** decidir si hay alerta ni guardar evidencia.

Reemplaza las `plyer.notification` + `pygame` de tu `IA_ENTRENADA.py`: el servidor ya no toca parlantes ni el escritorio; el navegador recibe el evento por SSE y reproduce el sonido.

---

## 1. Árbol

```
services/notification-service/
├── pom.xml ✅ · .env.example · README.md
└── src/main/
    ├── java/com/cortexcam/notification/
    │   ├── NotificationApplication.java                             (+ @ConfigurationPropertiesScan)
    │   ├── domain/
    │   │   ├── model/notification/ Notification · NotificationId · Channel · DeliveryStatus · NotificationContent · AlertSummary
    │   │   ├── model/preference/   NotificationPreference · QuietHours · Severity
    │   │   ├── model/shared/       TenantId · UserId · AlertId · CameraId
    │   │   ├── policy/             DeliveryPolicy · RetryBackoffPolicy
    │   │   ├── service/            NotificationMessageFactory
    │   │   └── exception/          DomainException · DomainValidationException
    │   ├── application/
    │   │   ├── port/in/notification/ DispatchAlertNotificationUseCase · RetryPendingNotificationsUseCase · OpenRealtimeStreamUseCase
    │   │   │                         UpdatePreferencesUseCase · GetPreferencesQuery · ListNotificationsQuery · PreferenceView · NotificationView
    │   │   ├── port/out/notification/ NotificationRepositoryPort · PreferenceRepositoryPort · NotificationChannelPort · RealtimeSubscriptionPort
    │   │   ├── port/out/shared/      ClockPort · IdGeneratorPort · ProcessedMessagePort
    │   │   ├── service/              NotificationPlanner · NotificationDeliverer
    │   │   └── usecase/{command,query}/
    │   └── infrastructure/
    │       ├── adapters/in/
    │       │   ├── messaging/  AlertRaisedListener · AlertRaisedMapper · contract/AlertRaisedMessage
    │       │   ├── rest/       controller/(NotificationController · RealtimeStreamController) · dto/ · mapper/ · advice/
    │       │   └── scheduler/  NotificationRetryScheduler
    │       ├── adapters/out/
    │       │   ├── channel/    InAppRealtimeChannelAdapter · EmailSmtpChannelAdapter · (Telegram…, Webhook… opcionales)
    │       │   ├── persistence/ entity/ · repository/ · mapper/ · adapter/
    │       │   └── system/      SystemClockAdapter · UuidV7GeneratorAdapter · ProcessedMessageAdapter
    │       └── config/          NotificationProperties
    └── resources/ application.yml · db/migration/{V1__outbox_inbox.sql ✅, V2__notification.sql 🆕}
```
Dependencias extra: `spring-boot-starter-mail`. Como no publica eventos, la tabla `outbox_event` de V1 queda sin uso (puedes quitarla de la V1 de este servicio).

---

## 2. Base de datos — `V2__notification.sql`
```sql
CREATE TABLE notification_preference (
  user_id        uuid PRIMARY KEY,
  tenant_id      uuid         NOT NULL,
  in_app         boolean      NOT NULL DEFAULT true,
  email          boolean      NOT NULL DEFAULT false,
  email_address  varchar(254),
  min_severity   varchar(10)  NOT NULL DEFAULT 'INFO',
  quiet_from     time,
  quiet_to       time,
  quiet_timezone varchar(40)  NOT NULL DEFAULT 'America/Bogota',
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT ck_pref_severity CHECK (min_severity IN ('INFO','WARNING','CRITICAL'))
);
CREATE INDEX ix_pref_tenant ON notification_preference (tenant_id);

CREATE TABLE notification (
  id                uuid PRIMARY KEY,
  tenant_id         uuid         NOT NULL,
  alert_id          uuid         NOT NULL,
  user_id           uuid         NOT NULL,
  channel           varchar(12)  NOT NULL,                  -- IN_APP | EMAIL | TELEGRAM | WEBHOOK
  status            varchar(10)  NOT NULL,                  -- PENDING | SENT | FAILED | SKIPPED
  severity          varchar(10)  NOT NULL,
  title             varchar(200) NOT NULL,
  body              varchar(1000) NOT NULL,
  payload           text         NOT NULL,                  -- JSON para el canal IN_APP (alertId, type, cameraId, ...)
  recipient_address varchar(254),                           -- email destino resuelto al planificar
  attempts          int          NOT NULL DEFAULT 0,
  last_error        varchar(500),
  next_attempt_at   timestamptz,
  created_at        timestamptz  NOT NULL DEFAULT now(),
  sent_at           timestamptz,
  version           bigint       NOT NULL DEFAULT 0,
  UNIQUE (alert_id, user_id, channel)                       -- idempotencia a nivel de datos
);
CREATE INDEX ix_notif_due  ON notification (next_attempt_at) WHERE status IN ('PENDING','FAILED');
CREATE INDEX ix_notif_user ON notification (user_id, created_at DESC);
```
**Destinatarios:** los usuarios con fila en `notification_preference` del tenant. Para que nadie se quede fuera, `GET /api/v1/notifications/preferences` **crea la fila por defecto** la primera vez que el usuario la consulta (el frontend la pide al iniciar sesión).

---

## 3. Dominio

```java
public enum Channel { IN_APP, EMAIL, TELEGRAM, WEBHOOK }
public enum DeliveryStatus { PENDING, SENT, FAILED, SKIPPED }
public enum Severity { INFO, WARNING, CRITICAL;
    public boolean atLeast(Severity min) { return this.compareTo(min) >= 0; } }

public record QuietHours(LocalTime from, LocalTime to, ZoneId zone) {
    public boolean covers(Instant at) {
        var t = at.atZone(zone).toLocalTime();
        return from.isBefore(to) ? (!t.isBefore(from) && t.isBefore(to))            // 13:00–15:00
                                 : (!t.isBefore(from) || t.isBefore(to));           // 22:00–06:00 (cruza medianoche)
    }
}
```
```java
public final class NotificationPreference {                         // AGGREGATE ROOT
    private final UserId userId; private final TenantId tenantId;
    private boolean inApp, email; private String emailAddress; private Severity minSeverity; private QuietHours quiet /* nullable */;
    public static NotificationPreference defaults(UserId u, TenantId t) { /* inApp=true, email=false, minSeverity=INFO, sin quiet */ }
    public void update(boolean inApp, boolean email, String emailAddress, Severity min, QuietHours quiet) {
        if (email && (emailAddress == null || !emailAddress.contains("@"))) throw new DomainValidationException("email_address requerido para el canal EMAIL");
        this.inApp = inApp; this.email = email; this.emailAddress = emailAddress; this.minSeverity = min; this.quiet = quiet;
    }
    // getters + reconstitute
}
```
```java
// domain/policy/DeliveryPolicy.java — ¿por qué canales avisar a este usuario por esta alerta ahora?
public final class DeliveryPolicy {
    public List<Channel> channelsFor(NotificationPreference p, Severity severity, Instant now) {
        if (!severity.atLeast(p.minSeverity())) return List.of();
        var out = new ArrayList<Channel>();
        if (p.inApp()) out.add(Channel.IN_APP);                          // in-app siempre llega: es pasivo (no suena en el celular)
        boolean quiet = p.quiet() != null && p.quiet().covers(now);
        if (p.email() && (!quiet || severity == Severity.CRITICAL)) out.add(Channel.EMAIL);   // lo CRÍTICO rompe el silencio
        return out;
    }
}

// domain/policy/RetryBackoffPolicy.java
public final class RetryBackoffPolicy {
    private static final Duration[] STEPS = { Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1) };
    public Optional<Duration> delayAfter(int attemptsSoFar) { return attemptsSoFar >= STEPS.length ? Optional.empty() : Optional.of(STEPS[attemptsSoFar]); }
}
```
```java
public final class Notification {                                    // AGGREGATE ROOT
    private final NotificationId id; private final TenantId tenantId; private final AlertId alertId; private final UserId userId;
    private final Channel channel; private final Severity severity; private final NotificationContent content;   // title, body, payloadJson
    private final String recipientAddress;
    private DeliveryStatus status; private int attempts; private String lastError; private Instant nextAttemptAt, sentAt; private final long version;

    public static Notification create(NotificationId id, TenantId t, AlertId a, UserId u, Channel ch, Severity sev,
                                      NotificationContent c, String recipient, Instant now) { /* PENDING, attempts 0, nextAttemptAt = now */ }
    public void markSent(Instant now) { status = DeliveryStatus.SENT; sentAt = now; nextAttemptAt = null; lastError = null; attempts++; }
    public void markFailed(String error, Instant now, Optional<Duration> retryIn) {
        attempts++; lastError = truncate(error, 500);
        status = DeliveryStatus.FAILED; nextAttemptAt = retryIn.map(now::plus).orElse(null);     // sin siguiente intento = se rinde
    }
    public boolean isDue(Instant now) { return (status == DeliveryStatus.PENDING || status == DeliveryStatus.FAILED) && nextAttemptAt != null && !nextAttemptAt.isAfter(now); }
}

// domain/service/NotificationMessageFactory.java — textos
public final class NotificationMessageFactory {
    public NotificationContent forAlert(AlertSummary a) {
        String what = switch (a.type()) { case "WEAPON" -> "Posible arma detectada"; case "PERSON" -> "Persona detectada";
                                           case "VEHICLE" -> "Vehículo detectado"; case "PLATE_MATCH" -> "Placa de la lista detectada"; default -> "Alerta"; };
        String title = "[%s] %s".formatted(a.severity(), what);
        String body = "Cámara %s · %s".formatted(shortId(a.cameraId()), a.raisedAt());
        String payload = "{\"alertId\":\"%s\",\"cameraId\":\"%s\",\"type\":\"%s\",\"severity\":\"%s\",\"raisedAt\":\"%s\"}"
                          .formatted(a.alertId(), a.cameraId(), a.type(), a.severity(), a.raisedAt());
        return new NotificationContent(title, body, payload);
    }
}
```
Mejora posterior: que este servicio consuma `camera.lifecycle` y guarde un *read model* de nombres para decir "Entrada principal" en vez del id.

Tests de dominio: `QuietHours` cruzando medianoche; `DeliveryPolicy` (INFO bajo `minSeverity` → vacío; `CRITICAL` rompe el silencio del email; in-app nunca se silencia); `RetryBackoffPolicy` (5 pasos y luego `empty`); transiciones de `Notification`.

---

## 4. Aplicación

### 4.1 Puertos
```java
public interface DispatchAlertNotificationUseCase {
    void handle(Command c);
    record Command(String eventId, UUID tenantId, UUID alertId, UUID cameraId, String type, String severity, Instant raisedAt) {}
}
public interface OpenRealtimeStreamUseCase {
    Subscription handle(Command c);
    record Command(UUID tenantId, UUID userId, Consumer<RealtimeMessage> sink) {}     // el sink lo crea el controller (SseEmitter)
    interface Subscription extends AutoCloseable { @Override void close(); }
    record RealtimeMessage(String id, String event, String json) {}
}
// otros: RetryPendingNotificationsUseCase.handle(), UpdatePreferencesUseCase.Command(tenantId, userId, inApp, email, emailAddress, minSeverity, quietFrom?, quietTo?, quietTimezone),
//        GetPreferencesQuery(tenantId, userId) [crea por defecto si no existe], ListNotificationsQuery(tenantId, userId, page, size)

public interface NotificationChannelPort {
    Channel channel();
    void deliver(Notification n);                                  // lanza excepción si falla; no devuelve códigos
}
public interface RealtimeSubscriptionPort {
    OpenRealtimeStreamUseCase.Subscription subscribe(UUID tenantId, UUID userId, Consumer<OpenRealtimeStreamUseCase.RealtimeMessage> sink);
}
public interface PreferenceRepositoryPort { Optional<NotificationPreference> findByUser(UserId u); List<NotificationPreference> findByTenant(TenantId t); NotificationPreference save(NotificationPreference p); }
public interface NotificationRepositoryPort {
    Notification save(Notification n);  List<Notification> saveAll(List<Notification> n);
    List<Notification> findDue(Instant now, int limit);  Page<Notification> findByUser(TenantId t, UserId u, int page, int size);
}
```
### 4.2 Planificar (transaccional) y entregar (no transaccional)
**Por qué separado:** el registro en BD y el *inbox* van en **una** transacción; el envío (SMTP, red) va **fuera**, para no mantener una transacción abierta contra servicios externos. Si el proceso cae entre ambos, las filas `PENDING` las recoge el reintento.
```java
@Service @Transactional
class NotificationPlanner {                                          // application/service
    private static final String CONSUMER = "notification.alerts";
    List<Notification> plan(DispatchAlertNotificationUseCase.Command c) {
        if (!processed.markIfFirstTime(c.eventId(), CONSUMER)) return List.of();                 // inbox
        var tenant = new TenantId(c.tenantId()); var sev = Severity.valueOf(c.severity()); var now = clock.now();
        var summary = new AlertSummary(c.alertId(), c.cameraId(), c.type(), sev, c.raisedAt());
        var content = messages.forAlert(summary);
        var planned = new ArrayList<Notification>();
        for (var pref : preferences.findByTenant(tenant))
            for (var ch : policy.channelsFor(pref, sev, now))
                planned.add(Notification.create(new NotificationId(ids.newId()), tenant, new AlertId(c.alertId()), pref.userId(), ch, sev,
                                                content, ch == Channel.EMAIL ? pref.emailAddress() : null, now));
        return notifications.saveAll(planned);
    }
}

@Service                                                             // SIN @Transactional de clase
class NotificationDeliverer {
    void deliver(Notification n) {
        var channel = byChannel.get(n.channel());                    // Map<Channel, NotificationChannelPort> armado con la List<> inyectada
        var now = clock.now();
        try {
            if (channel == null) throw new IllegalStateException("canal sin adaptador: " + n.channel());
            channel.deliver(n);
            n.markSent(now);
        } catch (Exception e) {
            n.markFailed(e.getClass().getSimpleName(), now, backoff.delayAfter(n.attempts()));    // NO guardar e.getMessage(): puede traer datos
        }
        notifications.save(n);                                       // cada guardado es su propia transacción (Spring Data)
    }
}

@Service
class DispatchAlertNotificationService implements DispatchAlertNotificationUseCase {
    public void handle(Command c) { planner.plan(c).forEach(deliverer::deliver); }
}
@Service
class RetryPendingNotificationsService implements RetryPendingNotificationsUseCase {
    public void handle() { notifications.findDue(clock.now(), 50).forEach(deliverer::deliver); }
}
@Service
class OpenRealtimeStreamService implements OpenRealtimeStreamUseCase {
    public Subscription handle(Command c) { return realtime.subscribe(c.tenantId(), c.userId(), c.sink()); }
}
```
Importante: `NotificationPlanner` y `NotificationDeliverer` son beans **distintos**; si estuvieran en la misma clase, `@Transactional` no se aplicaría a la llamada interna.

---

## 5. Infraestructura

### 5.1 Tiempo real (SSE) — cómo mantener el desacople
El `SseEmitter` es un tipo de Spring Web: **no puede entrar al núcleo**. Reparto:
- El **controller** (adaptador de entrada) crea el `SseEmitter` y un `sink` (`Consumer<RealtimeMessage>`) que escribe en él.
- El **caso de uso** `OpenRealtimeStream` solo recibe ese `sink` y se lo pasa al puerto.
- El **adaptador de salida** `InAppRealtimeChannelAdapter` guarda los sinks por `tenant/usuario` y les empuja los mensajes.
```java
@RestController @RequestMapping("/api/v1/notifications")
class RealtimeStreamController {
    private final OpenRealtimeStreamUseCase useCase;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) @PreAuthorize("isAuthenticated()")
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        var actor = CurrentActor.from(jwt);
        var emitter = new SseEmitter(0L);                                              // sin timeout propio; el proxy puede cortar, el cliente reconecta
        Consumer<OpenRealtimeStreamUseCase.RealtimeMessage> sink = msg -> {
            synchronized (emitter) {                                                   // SseEmitter no es seguro entre hilos
                try { emitter.send(SseEmitter.event().id(msg.id()).name(msg.event()).data(msg.json())); }
                catch (IOException e) { emitter.completeWithError(e); }
            }
        };
        var sub = useCase.handle(new OpenRealtimeStreamUseCase.Command(actor.tenantId(), actor.userId(), sink));
        emitter.onCompletion(sub::close); emitter.onTimeout(emitter::complete); emitter.onError(t -> sub.close());
        sink.accept(new OpenRealtimeStreamUseCase.RealtimeMessage("0", "hello", "{}"));   // confirma la conexión
        return emitter;
    }
}
```
```java
@Component
class InAppRealtimeChannelAdapter implements NotificationChannelPort, RealtimeSubscriptionPort {
    private final Map<UUID, Map<UUID, Set<Consumer<RealtimeMessage>>>> sinks = new ConcurrentHashMap<>();   // tenant → user → sinks

    public Channel channel() { return Channel.IN_APP; }

    public void deliver(Notification n) {                                              // alerta → todos los sinks de ESE usuario
        var userSinks = sinks.getOrDefault(n.tenantId().value(), Map.of()).getOrDefault(n.userId().value(), Set.of());
        var msg = new RealtimeMessage(n.id().value().toString(), "alert", n.content().payloadJson());
        userSinks.forEach(s -> s.accept(msg));                                         // sin conexiones abiertas = nada que hacer (queda en historial)
    }
    public Subscription subscribe(UUID tenant, UUID user, Consumer<RealtimeMessage> sink) {
        var set = sinks.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>()).computeIfAbsent(user, u -> ConcurrentHashMap.newKeySet());
        set.add(sink);
        return () -> set.remove(sink);
    }
    @Scheduled(fixedRate = 20_000)                                                     // latido: mantiene viva la conexión y detecta las muertas
    void heartbeat() { sinks.values().forEach(m -> m.values().forEach(set -> set.forEach(s -> s.accept(new RealtimeMessage("hb", "heartbeat", "{}"))))); }
}
```
Un sink cuya escritura falla completa el emitter con error y se desregistra solo (`onError → close`). Límites a vigilar: nº de conexiones por usuario, y que un proxy intermedio no use *buffering* (en Nginx: `proxy_buffering off`).

### 5.2 Canal email
```java
@Component @ConditionalOnProperty(prefix = "cortexcam.notification.email", name = "enabled", havingValue = "true")
class EmailSmtpChannelAdapter implements NotificationChannelPort {
    private final JavaMailSender mail; private final NotificationProperties props;
    public Channel channel() { return Channel.EMAIL; }
    public void deliver(Notification n) {
        if (n.recipientAddress() == null) throw new IllegalStateException("sin destinatario");
        var m = new SimpleMailMessage();
        m.setFrom(props.email().from()); m.setTo(n.recipientAddress()); m.setSubject(n.content().title()); m.setText(n.content().body());
        mail.send(m);                                                                  // excepción = el Deliverer reintenta con backoff
    }
}
```
En desarrollo usa **Mailpit** (agrega al compose: `image: axllent/mailpit`, puertos `1025:1025` y `8025:8025`; los correos se ven en `http://localhost:8025`). `application.yml`:
```yaml
spring.mail: { host: "${SMTP_HOST:localhost}", port: "${SMTP_PORT:1025}", username: "${SMTP_USER:}", password: "${SMTP_PASSWORD:}" }
cortexcam.notification.email: { enabled: "${EMAIL_ENABLED:false}", from: "${EMAIL_FROM:alertas@cortexcam.local}" }
```
`SMTP_PASSWORD` es un secreto: en producción por Docker secret/Vault, solo para este servicio.

### 5.3 Entrada Kafka
```java
@KafkaListener(topics = "cortexcam.alert.raised.v1", groupId = "notification.alerts")
void onAlert(ConsumerRecord<String, String> rec) { useCase.handle(mapper.toCommand(rec.value())); }
```
`AlertRaisedMapper`: JSON → `AlertRaisedMessage(id, tenantid, data{alertId, cameraId, tenantId, type, severity, raisedAt})` con `@JsonIgnoreProperties(ignoreUnknown = true)` → `Command(eventId = id, …)`; inválido → `InvalidMessageException` (DLQ).

### 5.4 Persistencia, REST y scheduler
- `NotificationJpaEntity`, `NotificationPreferenceJpaEntity` (+ `@Version` en `Notification`), repositorios Spring Data (`findByStatusInAndNextAttemptAtLessThanEqual…` con `Pageable` para `findDue`; `findByTenantIdAndUserIdOrderByCreatedAtDesc`), adaptadores y mappers (`LocalTime` ↔ `time`, `ZoneId` ↔ `varchar`).
- **REST**

| Ruta | Rol | Notas |
|---|---|---|
| `GET /api/v1/notifications/preferences` | autenticado | del **usuario del token**; crea la fila por defecto si no existe |
| `PUT /api/v1/notifications/preferences` | autenticado | valida email si `email=true`, severidad, horas |
| `GET /api/v1/notifications?page&size` | autenticado | historial del usuario |
| `GET /api/v1/notifications/stream` | autenticado | SSE: eventos `hello`, `alert`, `heartbeat` |

`userId` y `tenantId` siempre salen del JWT (nunca de la URL/body: nadie lee ni edita preferencias ajenas).
- `NotificationRetryScheduler`: `@Scheduled(fixedDelay = 15_000)` → `RetryPendingNotificationsUseCase`.

### 5.5 Agregar Telegram o Webhook más adelante (Strategy)
1. Nuevo valor en `Channel` (ya existen `TELEGRAM`, `WEBHOOK`) y campo de destino en la preferencia (`telegram_chat_id`, `webhook_url`) con su migración V3.
2. `DeliveryPolicy` decide cuándo aplica.
3. Un adaptador `TelegramChannelAdapter implements NotificationChannelPort` (`channel() = TELEGRAM`) que haga `POST` a la Bot API con `RestClient`; token en `TELEGRAM_BOT_TOKEN` (secreto solo de este servicio). Para webhook: firma HMAC-SHA256 del cuerpo y *timeout* corto; **valida la URL contra SSRF** (sin redes internas).
4. **No cambia nada** en casos de uso ni dominio: el `Map<Channel, NotificationChannelPort>` del `Deliverer` lo recoge solo.

### 5.6 `.env`
```
SERVICE_NAME=notification-service
SERVER_PORT=8094
DB_URL=jdbc:postgresql://localhost:5432/notification_db
DB_USER=notification_svc
DB_PASSWORD=notification_dev
EMAIL_ENABLED=false
```
`application.yml`: plantilla de 01 §1.6 con `cortexcam.outbox.enabled: false`.

---

## 6. Pruebas
| Nivel | Qué |
|---|---|
| Dominio | `QuietHours`, `DeliveryPolicy`, `RetryBackoffPolicy`, `Notification` (estados, `isDue`) |
| Use case (fakes) | `NotificationPlanner`: 2 usuarios, uno con email y otro solo in-app → 3 filas; severidad bajo el mínimo → 0 filas; mismo `eventId` → 0 filas (inbox). `NotificationDeliverer`: canal que lanza → `FAILED` con `next_attempt_at`; tras 5 fallos deja de reintentar. `RetryPending` solo toma filas vencidas |
| Adaptador SSE | Suscribir 2 sinks para el mismo usuario y 1 de otro: `deliver` llega solo a los 2; `close()` desregistra; un sink que lanza no afecta a los demás |
| Persistencia (Testcontainers) | Flyway V1–V2; unicidad `(alert_id, user_id, channel)`; `findDue` |
| REST | 401 sin token; `PUT` con `email=true` y sin dirección → 422; `GET preferences` crea el default |
| Mensajería | `alert-raised.v1.json` de ejemplo → Command correcto; JSON roto → DLQ |

## 7. Probar a mano
1. Obtén un token (01 §1.5) y abre el stream: `curl -N -H "Authorization: Bearer %T%" http://localhost:8094/api/v1/notifications/stream` → llega `event: hello` y luego `heartbeat` cada 20 s.
2. `GET /api/v1/notifications/preferences` (crea tu fila por defecto) y, si quieres correo, `PUT` con `email=true` + dirección y arranca con `EMAIL_ENABLED=true` y Mailpit.
3. Genera una alerta (05 §7): en el `curl` del paso 1 aparece `event: alert` con el JSON; el correo llega a Mailpit; `GET /api/v1/notifications` lista ambas filas `SENT`.
4. Apaga Mailpit y dispara otra alerta: la fila de EMAIL queda `FAILED` con `next_attempt_at`; al encenderlo, el reintento la deja `SENT`.

## 8. Listo cuando
- [ ] Una alerta llega al navegador por SSE en menos de ~2 s y queda en el historial.
- [ ] Un canal caído no impide los demás y se reintenta con *backoff* hasta rendirse.
- [ ] Reentregar el mismo `alert.raised` no duplica avisos (inbox + unicidad en BD).
- [ ] Nadie puede ver ni modificar preferencias ajenas.
- [ ] Ningún tipo de Spring Web/Mail aparece en `domain` ni `application` (ArchUnit en verde).
