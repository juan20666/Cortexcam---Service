# 01 — Base compartida (hacer antes de cualquier servicio)

Orden: 1.1 parent-pom → 1.2 starter → 1.3 contratos → 1.4 connections → 1.5 infraestructura → 1.6 plantilla `application.yml`.

---

## 1.1 `platform/parent-pom/pom.xml` ✏️

Centraliza versiones. Las versiones de librerías de terceros son **referencia**: confirma la última estable en Maven Central (o copia las que ya compilan en tu `camera-service/pom.xml`).

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>              <!-- o el último 4.1.x -->
    <relativePath/>
  </parent>

  <groupId>com.cortexcam</groupId>
  <artifactId>cortexcam-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <java.version>21</java.version>
    <archunit.version>1.4.0</archunit.version>
    <mapstruct.version>1.6.3</mapstruct.version>
    <minio.version>8.5.17</minio.version>
    <uuid-creator.version>6.0.0</uuid-creator.version>
    <spring-cloud.version>2025.1.2</spring-cloud.version>   <!-- compatible con Boot 4.1.0+ -->
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type><scope>import</scope>
      </dependency>
      <dependency><groupId>com.cortexcam</groupId><artifactId>cortexcam-spring-starter</artifactId><version>${project.version}</version></dependency>
      <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>${archunit.version}</version><scope>test</scope></dependency>
      <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>${mapstruct.version}</version></dependency>
      <dependency><groupId>io.minio</groupId><artifactId>minio</artifactId><version>${minio.version}</version></dependency>
      <dependency><groupId>com.github.f4b6a3</groupId><artifactId>uuid-creator</artifactId><version>${uuid-creator.version}</version></dependency>
    </dependencies>
  </dependencyManagement>
</project>
```
En cada servicio: `<parent>` = `cortexcam-parent` con `<relativePath>../../platform/parent-pom/pom.xml</relativePath>`.

**Dependencias que usa cada servicio Java (nombres de starter de Spring Boot 4):**

| Capacidad | artifactId |
|---|---|
| REST | `spring-boot-starter-webmvc`, `spring-boot-starter-validation` |
| Seguridad (Resource Server) | `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server` |
| JPA + PostgreSQL | `spring-boot-starter-data-jpa`, `org.postgresql:postgresql` |
| Flyway | `spring-boot-starter-flyway` **y** `org.flywaydb:flyway-database-postgresql` |
| Kafka | `spring-boot-starter-kafka` (o `org.springframework.kafka:spring-kafka`) |
| Operación | `spring-boot-starter-actuator`, `micrometer-registry-prometheus` |
| OpenAPI | `springdoc-openapi-starter-webmvc-ui` (versión compatible con Boot 4) |
| IDs | `com.github.f4b6a3:uuid-creator` |
| Starter propio | `com.cortexcam:cortexcam-spring-starter` |
| Tests | `spring-boot-starter-test`, `archunit-junit5`; para adaptadores Testcontainers (PostgreSQL y Kafka) con la versión que administre el BOM de Boot (revisa los nombres de artefacto de la serie 2.x) |

---

## 1.2 `platform/libs/cortexcam-spring-starter` ✏️ (solo técnica)

Reglas: **no** contiene dominio, DTOs ni puertos de ningún servicio. Todas las dependencias son `<optional>true</optional>` y cada auto-configuración está protegida con `@ConditionalOnClass`/propiedad.

### Árbol final
```
cortexcam-spring-starter/
├── pom.xml
└── src/main/
    ├── java/com/cortexcam/starter/
    │   ├── tracing/        (lo que ya tienes: TraceIdFilter, TraceIdProvider, TraceConstants, TraceProperties, ApplicationStartupLogger, LevelColorConverter)
    │   ├── web/            TechnicalExceptionHandler, WebAutoConfiguration, CorsProperties, OpenApiConfig
    │   ├── security/       CortexSecurityAutoConfiguration, KeycloakJwtAuthoritiesConverter, CurrentActor
    │   ├── outbox/         OutboxRelay, OutboxProperties, OutboxAutoConfiguration
    │   ├── inbox/          ProcessedMessageStore, InboxAutoConfiguration
    │   ├── kafka/          KafkaErrorHandlingAutoConfiguration, InvalidMessageException
    │   └── messaging/      CloudEventEnvelope
    └── resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```
`AutoConfiguration.imports` (una clase por línea):
```
com.cortexcam.starter.web.WebAutoConfiguration
com.cortexcam.starter.security.CortexSecurityAutoConfiguration
com.cortexcam.starter.outbox.OutboxAutoConfiguration
com.cortexcam.starter.inbox.InboxAutoConfiguration
com.cortexcam.starter.kafka.KafkaErrorHandlingAutoConfiguration
```
Mueve tus clases de `tracing` y registra `TraceIdFilter` en `WebAutoConfiguration` (un `FilterRegistrationBean` con `@ConditionalOnMissingBean`).

### `outbox/OutboxRelay.java`
```java
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager txm, OutboxProperties props) {
        this.jdbc = jdbc; this.kafka = kafka; this.props = props; this.tx = new TransactionTemplate(txm);
    }

    @Scheduled(fixedDelayString = "${cortexcam.outbox.poll-ms:500}")
    public void relay() {
        try { tx.executeWithoutResult(s -> publishBatch()); }
        catch (Exception e) { log.warn("Outbox relay falló; se reintenta en el próximo ciclo", e); }
    }

    private void publishBatch() {
        var rows = jdbc.queryForList("""
            SELECT id, topic, partition_key, event_type, payload::text AS payload
              FROM outbox_event WHERE published_at IS NULL
             ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED""", props.batchSize());
        for (var r : rows) {
            var rec = new ProducerRecord<String, String>((String) r.get("topic"), (String) r.get("partition_key"), (String) r.get("payload"));
            rec.headers().add("ce_id", r.get("id").toString().getBytes(StandardCharsets.UTF_8));
            rec.headers().add("ce_type", ((String) r.get("event_type")).getBytes(StandardCharsets.UTF_8));
            try { kafka.send(rec).get(10, TimeUnit.SECONDS); }
            catch (Exception e) { log.warn("No se pudo publicar {}: {}", r.get("id"), e.toString()); break; } // conserva el orden; reintenta luego
            jdbc.update("UPDATE outbox_event SET published_at = now() WHERE id = ?", r.get("id"));
        }
    }

    @Scheduled(cron = "${cortexcam.outbox.purge-cron:0 0 3 * * *}")
    public void purge() {
        jdbc.update("DELETE FROM outbox_event WHERE published_at < now() - make_interval(days => ?)", props.retentionDays());
    }
}
```
```java
@ConfigurationProperties(prefix = "cortexcam.outbox")
public record OutboxProperties(boolean enabled, Integer batchSizeValue, Integer retentionDaysValue) {
    public int batchSize() { return batchSizeValue == null ? 100 : batchSizeValue; }
    public int retentionDays() { return retentionDaysValue == null ? 7 : retentionDaysValue; }
}

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "cortexcam.outbox", name = "enabled", havingValue = "true")   // opt-in por servicio
public class OutboxAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txm, OutboxProperties p) {
        return new OutboxRelay(jdbc, kafka, txm, p);
    }
}
```
Entrega *at-least-once*: el relay marca `published_at` después del *ack* de Kafka. Si cae entre ambos, se reenvía y el consumidor deduplica (inbox).

### `inbox/ProcessedMessageStore.java`
```java
public class ProcessedMessageStore {
    private final JdbcTemplate jdbc;
    public ProcessedMessageStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** true = primera vez (procésalo); false = duplicado (ignóralo). Debe correr DENTRO de la transacción del use case. */
    public boolean markIfFirstTime(String messageId, String consumer) {
        return jdbc.update("INSERT INTO processed_message(message_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING",
                messageId, consumer) == 1;
    }
}
```
Cada servicio tiene un adaptador de 3 líneas `ProcessedMessageAdapter implements ProcessedMessagePort` que delega en este bean (el starter **no** conoce los puertos del servicio).

### `kafka/KafkaErrorHandlingAutoConfiguration.java` (reintentos + DLQ)
```java
@AutoConfiguration
@ConditionalOnClass(DefaultErrorHandler.class)
public class KafkaErrorHandlingAutoConfiguration {
    @Bean @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler cortexKafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + ".dlq", -1));   // -1: Kafka elige partición
        var backoff = new ExponentialBackOffWithMaxRetries(5);
        backoff.setInitialInterval(500); backoff.setMultiplier(2.0); backoff.setMaxInterval(10_000);
        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(InvalidMessageException.class, IllegalArgumentException.class); // mensaje inválido: directo a DLQ
        return handler;
    }
}
public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException(String msg, Throwable cause) { super(msg, cause); }
}
```
Boot detecta el bean `CommonErrorHandler` y lo aplica a todos los `@KafkaListener`.

### `web/TechnicalExceptionHandler.java` (errores técnicos; los de negocio los maneja cada servicio)
```java
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class TechnicalExceptionHandler extends ResponseEntityExceptionHandler {   // ya traduce excepciones de Spring MVC a ProblemDetail

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) { return base(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acceso denegado"); }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Error no controlado", e);                                       // el detalle va al log, no al cliente
        return base(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno");
    }

    static ProblemDetail base(HttpStatus s, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(s, detail);
        p.setProperty("code", code);
        p.setProperty("traceId", MDC.get("traceId"));
        return p;
    }
}
```
`WebAutoConfiguration` expone este bean con `@ConditionalOnMissingBean` y `@ConditionalOnWebApplication(type = SERVLET)`.

### `security/` (todos los endpoints protegidos por defecto — R-29)
```java
public class KeycloakJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> out = new HashSet<>();
        Map<String, Object> realm = jwt.getClaim("realm_access");
        if (realm != null && realm.get("roles") instanceof Collection<?> roles)
            roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r.toString().toUpperCase())));
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) for (String s : scope.split(" ")) out.add(new SimpleGrantedAuthority("SCOPE_" + s));
        return out;
    }
}

/** Quién llama. Lo extrae el CONTROLLER del JWT y lo pasa en el Command; el use case nunca ve SecurityContext. */
public record CurrentActor(UUID userId, UUID tenantId, Set<String> roles) {
    public static CurrentActor from(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant_id");
        if (tenant == null) throw new AccessDeniedException("Falta el claim tenant_id");
        Map<String, Object> realm = jwt.getClaim("realm_access");
        Set<String> roles = new HashSet<>();
        if (realm != null && realm.get("roles") instanceof Collection<?> c) c.forEach(r -> roles.add(r.toString().toUpperCase()));
        return new CurrentActor(UUID.fromString(jwt.getSubject()), UUID.fromString(tenant), roles);
    }
}

@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class CortexSecurityAutoConfiguration {
    @Bean @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain cortexChain(HttpSecurity http) throws Exception {
        var jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakJwtAuthoritiesConverter());
        return http
            .csrf(AbstractHttpConfigurer::disable)           // API stateless con Bearer; los navegadores pasan por el gateway (BFF) que protege CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter)))
            .build();
    }
}
```
> En producción restringe `/actuator/prometheus` a la red interna y apaga Swagger.

### `messaging/CloudEventEnvelope.java` (sobre común)
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudEventEnvelope<T>(
        String specversion, String id, String type, String source, String subject,
        Instant time, String datacontenttype, String tenantid, String traceparent, T data) {
    public static <T> CloudEventEnvelope<T> of(String id, String type, String source, String subject,
                                               Instant time, String tenantId, T data) {
        return new CloudEventEnvelope<>("1.0", id, type, source, subject, time, "application/json", tenantId,
                MDC.get("traceparent"), data);
    }
}
```
(`@JsonInclude` es de `com.fasterxml.jackson.annotation`, que no cambió con Jackson 3.)

### `pom.xml` del starter
`<parent>` = `cortexcam-parent`; `artifactId` `cortexcam-spring-starter`; dependencias `optional`: `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-kafka`, `spring-boot-starter-actuator`, `springdoc-openapi-starter-webmvc-ui`, `spring-boot-autoconfigure`. Instala con `mvn install` (C-08).

---

## 1.3 Contratos (`contracts/events/`)

### Regla general
Un evento = un esquema JSON + un ejemplo válido. CI valida cada ejemplo contra su esquema. Campos obligatorios mínimos; todo lo nuevo, opcional. `type` del sobre = nombre del tópico.

### `envelope.schema.json` ✅ (comprueba que sea así)
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://cortexcam/contracts/envelope.schema.json",
  "type": "object",
  "required": ["specversion", "id", "type", "source", "time", "data"],
  "properties": {
    "specversion": { "const": "1.0" },
    "id": { "type": "string", "minLength": 1 },
    "type": { "type": "string", "pattern": "^cortexcam\\.[a-z-]+\\.[a-z-]+\\.v[0-9]+$" },
    "source": { "type": "string" },
    "subject": { "type": "string" },
    "time": { "type": "string", "format": "date-time" },
    "datacontenttype": { "const": "application/json" },
    "tenantid": { "type": "string" },
    "traceparent": { "type": "string" },
    "data": { "type": "object" }
  }
}
```

### Forma de `data` de cada evento

| Evento (`type`) | Campos de `data` |
|---|---|
| `cortexcam.camera.lifecycle.v1` | `change` (REGISTERED, UPDATED, REMOVED), `cameraId`, `tenantId`, `name`, `status` (PENDING, ACTIVE, OFFLINE, DISABLED), `streamPath`, `samplingFps`, `detectors[{kind, enabled, minConfidence}]`, `occurredAt`. **Nunca** credenciales ni URL del origen |
| `cortexcam.ingestion.camera-health.v1` | `cameraId`, `tenantId`, `status` (ONLINE, OFFLINE), `reason?`, `observedAt` |
| `cortexcam.detection.<x>.v1` (person-detected, weapon-detected, vehicle-detected, plate-recognized) | `cameraId`, `frameId`, `capturedAt`, `modelVersion`, `detections[{label, confidence, box{x1,y1,x2,y2}, attributes{}}]`. Las placas llevan `attributes.plateText` y `attributes.ocrConfidence` |
| `cortexcam.alert.raised.v1` | `alertId`, `cameraId`, `tenantId`, `type` (PERSON, WEAPON, VEHICLE, PLATE_MATCH), `severity` (INFO, WARNING, CRITICAL), `raisedAt`, `trigger{eventId, frameId, capturedAt, confidence, modelVersion, detectionCount, plateText?, boxes[…]}` |
| `cortexcam.alert.lifecycle.v1` | `alertId`, `cameraId`, `tenantId`, `type`, `change` (ACKNOWLEDGED, RESOLVED, FALSE_POSITIVE), `actorId`, `occurredAt` |
| `cortexcam.evidence.stored.v1` | `evidenceId`, `alertId`, `cameraId`, `tenantId`, `kind` (SNAPSHOT, CLIP), `capturedAt`, `expiresAt` (sin URL) |

Ajusta tus esquemas `alert-raised`, `camera-lifecycle`, `person-detected`, `weapon-detected` a estas formas. Los campos nuevos que no existían antes (`boxes`, `plateText`, `tenantId`) van como opcionales para no invalidar tus ejemplos.

### `schemas/person-detected.v1.schema.json` (sirve de molde para weapon/vehicle/plate; solo cambia el `$id` y los `label`)
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://cortexcam/contracts/person-detected.v1.schema.json",
  "allOf": [ { "$ref": "envelope.schema.json" } ],
  "properties": {
    "type": { "const": "cortexcam.detection.person-detected.v1" },
    "data": {
      "type": "object",
      "required": ["cameraId", "frameId", "capturedAt", "modelVersion", "detections"],
      "properties": {
        "cameraId": { "type": "string", "format": "uuid" },
        "frameId": { "type": "string", "format": "uuid" },
        "capturedAt": { "type": "string", "format": "date-time" },
        "modelVersion": { "type": "string" },
        "detections": {
          "type": "array", "minItems": 1,
          "items": {
            "type": "object", "required": ["label", "confidence", "box"],
            "properties": {
              "label": { "type": "string" },
              "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
              "box": {
                "type": "object", "required": ["x1", "y1", "x2", "y2"],
                "properties": {
                  "x1": { "type": "number", "minimum": 0, "maximum": 1 }, "y1": { "type": "number", "minimum": 0, "maximum": 1 },
                  "x2": { "type": "number", "minimum": 0, "maximum": 1 }, "y2": { "type": "number", "minimum": 0, "maximum": 1 }
                }
              },
              "attributes": { "type": "object", "additionalProperties": { "type": ["string", "number", "boolean"] } }
            }
          }
        }
      }
    }
  }
}
```

### Cabeceras del tópico binario `frames` (`frame-headers.v1.md`) ✏️
`frame-id`, `camera-id`, `tenant-id`, `captured-at` (ISO-8601 UTC), `content-type` (`image/jpeg`), `width`, `height`. Valor del mensaje = bytes del JPEG. Clave = `camera-id`.

### `examples/` (uno por evento)
`person-detected.v1.json`, `weapon-detected.v1.json`, `vehicle-detected.v1.json`, `plate-recognized.v1.json`, `alert-raised.v1.json` ✅, `alert-lifecycle.v1.json`, `camera-lifecycle.v1.json`, `camera-health.v1.json`, `evidence-stored.v1.json`. Copia el sobre de la guía de arquitectura §10.3 y sustituye `type` y `data`.

---

## 1.4 `connections/` ✏️

### `topics.yaml`
```yaml
topics:
  - { name: cortexcam.camera.lifecycle.v1,          partitions: 3, config: { cleanup.policy: compact, min.cleanable.dirty.ratio: "0.1" }, producer: camera-service }
  - { name: cortexcam.ingestion.frames.v1,          partitions: 6, config: { retention.ms: "60000", retention.bytes: "134217728", segment.bytes: "33554432", max.message.bytes: "2097152" }, producer: ingestion-service }
  - { name: cortexcam.ingestion.camera-health.v1,   partitions: 3, config: { retention.ms: "604800000" }, producer: ingestion-service }
  - { name: cortexcam.detection.person-detected.v1, partitions: 6, config: { retention.ms: "604800000" }, producer: person-detection-service }
  - { name: cortexcam.detection.weapon-detected.v1, partitions: 6, config: { retention.ms: "604800000" }, producer: weapon-detection-service }
  - { name: cortexcam.detection.vehicle-detected.v1,partitions: 6, config: { retention.ms: "604800000" }, producer: vehicle-detection-service }
  - { name: cortexcam.detection.plate-recognized.v1,partitions: 6, config: { retention.ms: "604800000" }, producer: plate-recognition-service }
  - { name: cortexcam.alert.raised.v1,              partitions: 3, config: { retention.ms: "2592000000" }, producer: alert-service }
  - { name: cortexcam.alert.lifecycle.v1,           partitions: 3, config: { retention.ms: "2592000000" }, producer: alert-service }
  - { name: cortexcam.evidence.stored.v1,           partitions: 3, config: { retention.ms: "2592000000" }, producer: evidence-service }
dlq_for: [cortexcam.ingestion.camera-health.v1, cortexcam.detection.person-detected.v1, cortexcam.detection.weapon-detected.v1,
          cortexcam.detection.vehicle-detected.v1, cortexcam.detection.plate-recognized.v1, cortexcam.alert.raised.v1, cortexcam.evidence.stored.v1]
```

### `scripts/create-topics.sh`
```bash
#!/usr/bin/env bash
set -euo pipefail
COMPOSE="docker compose -f platform/docker-compose.yml"
mk() { local t=$1 p=$2; shift 2; $COMPOSE exec -T redpanda rpk topic create "$t" -p "$p" -r 1 "$@" || true; }

mk cortexcam.camera.lifecycle.v1          3 -c cleanup.policy=compact -c min.cleanable.dirty.ratio=0.1
mk cortexcam.ingestion.frames.v1          6 -c retention.ms=60000 -c retention.bytes=134217728 -c segment.bytes=33554432 -c max.message.bytes=2097152
mk cortexcam.ingestion.camera-health.v1   3 -c retention.ms=604800000
for k in person-detected weapon-detected vehicle-detected; do mk cortexcam.detection.$k.v1 6 -c retention.ms=604800000; done
mk cortexcam.detection.plate-recognized.v1 6 -c retention.ms=604800000
mk cortexcam.alert.raised.v1              3 -c retention.ms=2592000000
mk cortexcam.alert.lifecycle.v1           3 -c retention.ms=2592000000
mk cortexcam.evidence.stored.v1           3 -c retention.ms=2592000000
for t in cortexcam.ingestion.camera-health.v1 cortexcam.detection.person-detected.v1 cortexcam.detection.weapon-detected.v1 \
         cortexcam.detection.vehicle-detected.v1 cortexcam.detection.plate-recognized.v1 cortexcam.alert.raised.v1 cortexcam.evidence.stored.v1; do
  mk "$t.dlq" 1 -c retention.ms=1209600000
done
$COMPOSE exec -T redpanda rpk topic list
```
Ejecútalo con Git Bash o WSL desde la raíz: `bash connections/scripts/create-topics.sh`.

### `service-matrix.md`
Copia la tabla §3.3 de `00_INDICE`. Es el documento que se actualiza **en cada PR** que agrega/quita un productor o consumidor.

### `gateway-routes.yaml`
```yaml
routes:
  - { id: camera,       path: /api/v1/cameras/**,       uri: http://localhost:8091 }
  - { id: alerts,       path: /api/v1/alerts/**,        uri: http://localhost:8092 }
  - { id: alert-rules,  path: /api/v1/alert-rules/**,   uri: http://localhost:8092 }
  - { id: watchlist,    path: /api/v1/watchlist/**,     uri: http://localhost:8092 }
  - { id: evidence,     path: /api/v1/evidence/**,      uri: http://localhost:8093 }
  - { id: notifications,path: /api/v1/notifications/**,uri: http://localhost:8094 }
```
### `kafka-acls.yaml` (documental en dev; se aplica en Kafka con SASL en prod)
Un usuario por servicio: **READ** en los tópicos que consume y **WRITE** en los que produce y en sus `.dlq`. Ejemplo: `person-detection-service` → READ `ingestion.frames`, READ `camera.lifecycle`, WRITE `detection.person-detected`.

---

## 1.5 Infraestructura de desarrollo (`platform/`)

### `platform/.env.example` (copia a `platform/.env`, **no** se versiona)
```
POSTGRES_ADMIN_PASSWORD=dev-admin
CAMERA_DB_PASSWORD=camera_dev
ALERT_DB_PASSWORD=alert_dev
EVIDENCE_DB_PASSWORD=evidence_dev
NOTIFICATION_DB_PASSWORD=notification_dev
MINIO_ROOT_USER=minio
MINIO_ROOT_PASSWORD=minio-dev-secret
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=admin
```

### `platform/docker-compose.yml` ✏️
```yaml
name: cortexcam

services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD}
      CAMERA_DB_PASSWORD: ${CAMERA_DB_PASSWORD}
      ALERT_DB_PASSWORD: ${ALERT_DB_PASSWORD}
      EVIDENCE_DB_PASSWORD: ${EVIDENCE_DB_PASSWORD}
      NOTIFICATION_DB_PASSWORD: ${NOTIFICATION_DB_PASSWORD}
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init-databases.sh:/docker-entrypoint-initdb.d/10-init-databases.sh:ro
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U postgres"], interval: 5s, timeout: 3s, retries: 20 }

  redpanda:
    image: redpandadata/redpanda:latest          # fija un tag estable antes de staging
    command:
      - redpanda
      - start
      - --mode=dev-container
      - --smp=1
      - --memory=1G
      - --kafka-addr=internal://0.0.0.0:9092,external://0.0.0.0:19092
      - --advertise-kafka-addr=internal://redpanda:9092,external://localhost:19092
    ports: ["19092:19092"]
    healthcheck: { test: ["CMD-SHELL", "rpk cluster info"], interval: 10s, timeout: 5s, retries: 20 }

  redpanda-console:
    image: redpandadata/console:latest
    environment: { KAFKA_BROKERS: "redpanda:9092" }
    ports: ["8082:8080"]
    depends_on: { redpanda: { condition: service_healthy } }

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment: { MINIO_ROOT_USER: "${MINIO_ROOT_USER}", MINIO_ROOT_PASSWORD: "${MINIO_ROOT_PASSWORD}" }
    ports: ["9000:9000", "9001:9001"]
    volumes: [miniodata:/data]
    healthcheck: { test: ["CMD", "mc", "ready", "local"], interval: 10s, timeout: 5s, retries: 10 }

  minio-init:
    image: minio/mc:latest
    depends_on: { minio: { condition: service_healthy } }
    entrypoint: >
      /bin/sh -c "mc alias set local http://minio:9000 ${MINIO_ROOT_USER} ${MINIO_ROOT_PASSWORD} &&
      mc mb --ignore-existing local/evidence && mc mb --ignore-existing local/models && exit 0"

  keycloak:
    image: quay.io/keycloak/keycloak:latest      # fija 26.x antes de staging
    command: start-dev --import-realm
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: ${KEYCLOAK_ADMIN}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD}
      KC_HTTP_PORT: 8080
    ports: ["8081:8080"]
    volumes: ["./keycloak/realm-cortexcam.json:/opt/keycloak/data/import/realm-cortexcam.json:ro"]

  mediamtx:
    image: bluenviron/mediamtx:latest
    ports: ["8554:8554", "8888:8888", "8889:8889", "9997:9997", "8189:8189/udp"]
    volumes: ["./mediamtx/mediamtx.yml:/mediamtx.yml:ro"]

volumes: { pgdata: {}, miniodata: {} }
```
Arranque: `docker compose -f platform/docker-compose.yml --env-file platform/.env up -d`. El script de Postgres **solo corre con volumen vacío**; si cambias algo: `docker compose ... down -v`.

### `platform/postgres/init-databases.sh` ✏️ (reemplaza el `.sql`)
```bash
#!/usr/bin/env bash
set -euo pipefail
create_db() {   # nombre_bd usuario clave
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
    CREATE ROLE $2 LOGIN PASSWORD '$3';
    CREATE DATABASE $1 OWNER $2;
    REVOKE ALL ON DATABASE $1 FROM PUBLIC;
SQL
}
create_db camera_db       camera_svc       "$CAMERA_DB_PASSWORD"
create_db alert_db        alert_svc        "$ALERT_DB_PASSWORD"
create_db evidence_db     evidence_svc     "$EVIDENCE_DB_PASSWORD"
create_db notification_db notification_svc "$NOTIFICATION_DB_PASSWORD"
```
Un usuario por servicio y sin `SUPERUSER`: `alert_svc` no puede abrir `camera_db` (R-18). Debe quedar con fin de línea LF (`.gitattributes`).

### `platform/mediamtx/mediamtx.yml` ✏️
```yaml
logLevel: info
api: yes
apiAddress: :9997
rtsp: yes
rtspAddress: :8554
hls: yes
hlsAddress: :8888
webrtc: yes
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189
webrtcAdditionalHosts: [127.0.0.1]
metrics: no

# SOLO DESARROLLO: permite la API desde el host (Docker NAT no llega como localhost).
# En producción: usuarios con contraseña y la API solo desde la red interna.
authInternalUsers:
  - user: any
    pass:
    ips: []
    permissions:
      - { action: publish }
      - { action: read }
      - { action: playback }
      - { action: api }

pathDefaults:
  sourceOnDemand: no
```
La API que usa `camera-service`: `POST http://localhost:9997/v3/config/paths/add/{name}` con `{"source":"rtsp://…","sourceOnDemand":false,"rtspTransport":"tcp"}` y `DELETE …/paths/delete/{name}`. HLS: `http://localhost:8888/{name}/index.m3u8`. WebRTC (WHEP): `POST http://localhost:8889/{name}/whep`.

### `platform/keycloak/realm-cortexcam.json` ✏️ (realm de desarrollo)
```json
{
  "realm": "cortexcam",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "roles": { "realm": [ { "name": "admin" }, { "name": "operator" }, { "name": "viewer" } ] },
  "clients": [
    {
      "clientId": "cortexcam-web",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "secret": "dev-secret-change-me",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "redirectUris": ["http://localhost:8080/*", "http://localhost:5173/*"],
      "webOrigins": ["+"],
      "protocolMappers": [
        {
          "name": "tenant-id", "protocol": "openid-connect", "protocolMapper": "oidc-hardcoded-claim-mapper",
          "config": { "claim.name": "tenant_id", "claim.value": "00000000-0000-4000-8000-000000000001",
                      "jsonType.label": "String", "access.token.claim": "true", "id.token.claim": "true", "userinfo.token.claim": "true" }
        }
      ]
    }
  ],
  "users": [
    { "username": "admin", "enabled": true, "email": "admin@cortexcam.local", "emailVerified": true,
      "firstName": "Admin", "lastName": "Cortex",
      "credentials": [ { "type": "password", "value": "admin", "temporary": false } ], "realmRoles": ["admin"] },
    { "username": "operator", "enabled": true, "email": "operator@cortexcam.local", "emailVerified": true,
      "firstName": "Op", "lastName": "Cortex",
      "credentials": [ { "type": "password", "value": "operator", "temporary": false } ], "realmRoles": ["operator"] }
  ]
}
```
El `tenant_id` fijo es una simplificación de desarrollo (un solo tenant). En producción sale de grupos/atributos del usuario.

**Obtener un token (para probar los servicios):**
```bat
curl -s -X POST http://localhost:8081/realms/cortexcam/protocol/openid-connect/token ^
  -d "client_id=cortexcam-web" -d "client_secret=dev-secret-change-me" ^
  -d "grant_type=password" -d "username=admin" -d "password=admin"
```
Copia `access_token` y úsalo como `Authorization: Bearer <token>`. El `issuer` del token será `http://localhost:8081/realms/cortexcam`, por eso los servicios corren en el host (ver `OIDC_ISSUER_URI`).

---

## 1.6 Plantilla `application.yml` (Java) ✏️

```yaml
spring:
  application.name: ${SERVICE_NAME}
  config.import: optional:file:.env[.properties]          # dev: lee .env de la carpeta del servicio (sin comillas)
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate                           # nunca update
    properties.hibernate.jdbc.time_zone: UTC
  flyway: { enabled: true, locations: classpath:db/migration }
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:19092}
    producer:
      acks: all
      properties: { enable.idempotence: true }
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener: { ack-mode: record }
  security.oauth2.resourceserver.jwt.issuer-uri: ${OIDC_ISSUER_URI:http://localhost:8081/realms/cortexcam}

server: { port: ${SERVER_PORT}, shutdown: graceful }

management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true

cortexcam:
  outbox: { enabled: true }
```
`.env` de ejemplo para `alert-service` (cada servicio ajusta nombre/puerto/BD):
```
SERVICE_NAME=alert-service
SERVER_PORT=8092
DB_URL=jdbc:postgresql://localhost:5432/alert_db
DB_USER=alert_svc
DB_PASSWORD=alert_dev
```
Copia estos `.env.example` a `connections/env/<servicio>.env.example` y a la carpeta de cada servicio.

**Listo cuando:** `docker compose up -d` deja todo *healthy*; `create-topics.sh` crea los tópicos; puedes pedir un token a Keycloak; `psql -h localhost -U camera_svc camera_db` entra y `-U alert_svc camera_db` **no**.
