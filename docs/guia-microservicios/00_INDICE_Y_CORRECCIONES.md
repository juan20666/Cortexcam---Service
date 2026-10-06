# CortexCam v2 — Guía de implementación de TODOS los microservicios

Paquete de 9 documentos. Se escribió sobre tu árbol real (`D:\GitHub\Cortexcam---Service`). Complementa la guía de arquitectura anterior (reglas R-01…R-30, §3).

| Archivo | Contenido |
|---|---|
| **00_INDICE_Y_CORRECCIONES** (este) | Estado actual, **correcciones obligatorias**, tabla maestra de puertos/tópicos/BD, orden de trabajo |
| 01_BASE_COMPARTIDA | parent-pom, librería `cortexcam-spring-starter`, contratos, Docker Compose, Keycloak, MediaMTX, bases de datos |
| 02_camera-service | Java. Completar lo que ya corre |
| 03_ingestion-service | Python. RTSP → frames |
| 04_detectores | Python. person / weapon / vehicle / plate |
| 05_alert-service | Java. Reglas, ciclo de vida de alertas |
| 06_evidence-service | Java. Buffer de frames, MinIO, retención |
| 07_notification-service | Java. SSE, email, preferencias |
| 08_gateway_y_frontend | Gateway (BFF) y React |
| 09_EJECUTAR_Y_PROBAR_TODO | Orden de arranque, cámara falsa, pruebas de extremo a extremo |

Símbolos en los árboles: ✅ ya existe en tu repo · 🆕 crear · ✏️ modificar.

---

## 1. Lo que veo en tu árbol hoy

| Pieza | Estado |
|---|---|
| `docs/adr` (6 ADRs), `contracts/` (envelope + 4 esquemas + 1 ejemplo), `connections/` (topics, 2 env, script) | ✅ Buen comienzo |
| `camera-service`: dominio, puertos, `RegisterCameraService`, persistencia, outbox (sin relay), cifrado AES-GCM, provisioner MediaMTX, `CameraController`, Flyway V1/V2 | ✅ Corre. Falta ~60 % de la funcionalidad (§02) |
| `alert`, `evidence`, `notification`: esqueleto de carpetas vacío | ⚠️ Sin `pom.xml` en la raíz (la plantilla quedó **anidada**) |
| 5 servicios Python: esqueleto vacío | ⚠️ Sin `pyproject.toml` en la raíz (plantilla anidada) |
| `platform/libs/cortexcam-spring-starter` | ⚠️ Es una **copia de tu proyecto viejo** (con `adapters/`, Feign, `JwtTokenGenerator`…) |
| `gateway-service`, `frontend/src` (casi todo), `ml/*` | Esqueleto |
| `platform/docker-compose.yml`, `parent-pom`, `init-databases.sql` | ✅ Existen; se reemplazan por las versiones de §01 |

---

## 2. CORRECCIONES OBLIGATORIAS (hazlas primero, en este orden)

Haz un commit antes de empezar para poder revertir. Comandos para `cmd` en `D:\GitHub\Cortexcam---Service`.

### C-01 Plantillas anidadas (Java)

```bat
cd /d D:\GitHub\Cortexcam---Service

:: 1) Las plantillas globales salen de services/ (el CI no debe tratarlas como servicios)
mkdir platform\templates
move services\_template-java   platform\templates\java
move services\_template-python platform\templates\python

:: 2) alert / evidence / notification: sube el contenido anidado un nivel
robocopy services\alert-service\_template-java        services\alert-service        /E /XD target
robocopy services\evidence-service\_template-java     services\evidence-service     /E /XD target
robocopy services\notification-service\_template-java services\notification-service /E /XD target
rmdir /s /q services\alert-service\_template-java
rmdir /s /q services\evidence-service\_template-java
rmdir /s /q services\notification-service\_template-java

:: 3) Mueve el ArchitectureTest al paquete real (ejemplo alert; repite con evidence y notification)
mkdir services\alert-service\src\test\java\com\cortexcam\alert\architecture
move services\alert-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java services\alert-service\src\test\java\com\cortexcam\alert\architecture\
rmdir /s /q services\alert-service\src\test\java\com\cortexcam\template

:: 4) camera-service ya tiene pom: solo rescata el ArchitectureTest
mkdir services\camera-service\src\test\java\com\cortexcam\camera\architecture
copy  services\camera-service\_template-java\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java services\camera-service\src\test\java\com\cortexcam\camera\architecture\
rmdir /s /q services\camera-service\_template-java
```
Después, en cada `ArchitectureTest.java`: cambia la línea `package` y `@AnalyzeClasses(packages = "com.cortexcam.<servicio>")`. En cada `pom.xml`: `artifactId`, `name`, y `<parent>` apuntando al `parent-pom` (§01.1). Si el pom usa `V1__outbox_inbox.sql`, ya está en `src/main/resources/db/migration`.

Crea además la clase `@SpringBootApplication` de `alert`, `evidence` y `notification` (`AlertApplication`, `EvidenceApplication`, `NotificationApplication`) en su paquete raíz.

### C-02 Plantillas anidadas (Python)

Repite para `ingestion-service` (paquete `ingestion`) y para los cuatro detectores (`person_detection`, `weapon_detection`, `vehicle_detection`, `plate_recognition`):

```bat
set S=services\ingestion-service
set P=ingestion
move %S%\_template-python\pyproject.toml  %S%\
move %S%\_template-python\.env.example    %S%\
move %S%\_template-python\src\template_service\bootstrap.py        %S%\src\%P%\
move %S%\_template-python\src\template_service\config\settings.py  %S%\src\%P%\infrastructure\config\
rmdir /s /q %S%\_template-python
:: crea __init__.py en cada carpeta del paquete (sin pisar los existentes)
pushd %S%
for /d /r src %d in (*) do @if not exist "%d\__init__.py" type nul > "%d\__init__.py"
popd
```
(Ese `for` se escribe tal cual en el prompt de `cmd`; dentro de un `.bat` hay que duplicar el `%`: `%%d`.)
En `pyproject.toml` y en los `import` cambia `template_service` por el nombre real del paquete. Sin `__init__.py` en `src/<paquete>` los imports fallan.

### C-03 Higiene de git (crítico: tienes un `.env` en `camera-service`)

Añade a `.gitignore`:
```
target/
.idea/
*.iml
.venv/
__pycache__/
node_modules/
dist/
**/.env
!**/.env.example
secrets/*.env
!secrets/*.enc.env
secrets/run/
*.pt
*.onnx
```
Y verifica:
```bat
git check-ignore -v services\camera-service\.env
git rm -r --cached services/camera-service/.env 2>nul
git rm -r --cached services/*/target platform/libs/*/target 2>nul
```
Crea `.gitattributes` en la raíz (evita que Windows rompa scripts que corren en contenedores Linux):
```
* text=auto
*.sh text eol=lf
*.yml text eol=lf
*.sql text eol=lf
```
Git no versiona carpetas vacías: casi todo tu esqueleto desaparecerá al clonar. Opcional (PowerShell, después de `mvn clean`):
```powershell
Get-ChildItem -Recurse -Directory | Where-Object { $_.FullName -notmatch 'node_modules|\.git|target|\.venv' -and -not (Get-ChildItem $_.FullName -Force) } | ForEach-Object { New-Item -ItemType File -Path (Join-Path $_.FullName '.gitkeep') | Out-Null }
```

### C-04 Limpiar el starter

`platform/libs/cortexcam-spring-starter` debe quedar **solo técnico**. Estructura objetivo (código en §01.2):
```
com/cortexcam/starter/
├── tracing/        TraceIdFilter, TraceIdProvider, TraceConstants, TraceProperties, ApplicationStartupLogger, LevelColorConverter   ✅ (muévelos aquí desde tracing/)
├── web/            TechnicalExceptionHandler 🆕 (reemplaza GlobalExceptionHandler), CorsProperties ✅, ServletFilterConfig ✅, OpenApiConfig ✅
├── security/       CortexSecurityAutoConfiguration 🆕, KeycloakJwtAuthoritiesConverter 🆕, CurrentActor 🆕
├── outbox/         OutboxRelay 🆕, OutboxProperties 🆕, OutboxAutoConfiguration 🆕
├── inbox/          ProcessedMessageStore 🆕
├── kafka/          KafkaErrorHandlingAutoConfiguration 🆕, InvalidMessageException 🆕
└── messaging/      CloudEventEnvelope 🆕
```
**Borra:** carpetas `adapters/**` (en una librería no pintan nada), `FeignClientConfiguration`, `FeignRequestInterceptor`, `JwtTokenGenerator`, `InternalServiceTokenAuthFilter`, `InternalServiceAuthorizationExpressions`, `ServiceAccountAuthentication`, `UserJwtPrincipal`, `JwtAuthenticationFilter`, `SecurityUtils`, `WebSecurityConfig`, `MutationLog*` (la auditoría de negocio pasa a `AuditTrailPort`). Emitir tokens ya no es de cada micro: lo hace Keycloak. Registra el starter con `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (§01.2).

### C-05 Puertos compartidos en `camera-service`

Mueve `ClockPort`, `IdGeneratorPort` y `DomainEventPublisherPort` de `application/port/out/camera/` a `application/port/out/shared/` (IntelliJ: *Refactor → Move*). Así los demás agregados no importan del paquete `camera`.

### C-06 Credenciales de cámara opcionales
Una cámara falsa (cámara de prueba, archivo en bucle) no tiene usuario/clave. En `StreamSource` haz `credentials` **opcional** (`Optional<EncryptedSecret>`/null) y ajusta `CameraJpaEntity` y el cifrador.

### C-07 Jackson 3
Con Spring Boot 4.1 el JSON es **Jackson 3**: los paquetes son `tools.jackson.*` (p. ej. `tools.jackson.databind.json.JsonMapper`). Solo las **anotaciones** siguen en `com.fasterxml.jackson.annotation`. Usa el mismo mapper que ya usas en `EventContractMapper`; no mezcles Jackson 2 y 3.

### C-08 Compilar la base en orden
```bat
mvn -f platform\parent-pom\pom.xml install
mvn -f platform\libs\cortexcam-spring-starter\pom.xml clean install
mvn -f services\camera-service\pom.xml clean verify
```
Si `camera-service` aún no depende del starter, agrega la dependencia (§01.2). Hasta que existan tests reales, `verify` solo comprueba que compila y que pasa ArchUnit.

### C-09 Completar `contracts/` y `connections/`
Faltan: `vehicle-detected.v1`, `plate-recognized.v1`, `alert-lifecycle.v1`, `evidence-stored.v1`, `camera-health.v1` (esquemas) y un `examples/*.json` por evento; en `connections/`: `service-matrix.md`, `gateway-routes.yaml`, `kafka-acls.yaml`. El contenido está en §01.3–01.4. Actualiza `topics.yaml` con la tabla de §3.

### C-10 Reemplazar `docker-compose.yml` e `init-databases.sql`
Usa los de §01.5 (listeners internos/externos de Kafka, bucket de MinIO, Keycloak con realm, MediaMTX con API accesible desde el host, un usuario/BD por servicio con contraseñas por variable).

---

## 3. Tabla maestra (la única fuente de verdad de la integración)

### 3.1 Puertos (desarrollo: infraestructura en Docker, servicios desde IDE/terminal)

| Componente | Puerto host | | Componente | Puerto host |
|---|---|---|---|---|
| PostgreSQL | 5432 | | gateway-service | 8080 |
| Redpanda (Kafka, desde host) | 19092 | | camera-service | 8091 |
| Redpanda Console | 8082 | | alert-service | 8092 |
| MinIO API / consola | 9000 / 9001 | | evidence-service | 8093 |
| Keycloak | 8081 | | notification-service | 8094 |
| MediaMTX RTSP / HLS / WebRTC / API | 8554 / 8888 / 8889 / 9997 | | ingestion / person / weapon / vehicle / plate (health+métricas) | 9101 / 9102 / 9103 / 9104 / 9105 |

### 3.2 Bases de datos

| Servicio | BD / usuario | Tablas propias |
|---|---|---|
| camera | `camera_db` / `camera_svc` | `camera`, `outbox_event`, `processed_message` |
| alert | `alert_db` / `alert_svc` | `alert`, `alert_rule`, `detection_hit`, `watchlist_plate`, `outbox_event`, `processed_message` |
| evidence | `evidence_db` / `evidence_svc` | `evidence_item`, `evidence_access_log`, `retention_policy`, `outbox_event`, `processed_message` |
| notification | `notification_db` / `notification_svc` | `notification`, `notification_preference`, `processed_message` |
| ingestion, detectores, gateway | sin BD | — |

### 3.3 Tópicos, productores y consumidores

| Tópico | Productor | Consumidor → grupo | Key |
|---|---|---|---|
| `cortexcam.camera.lifecycle.v1` (compacted) | camera | ingestion, person, weapon, vehicle, plate → **grupo único por instancia, leyendo desde el inicio** | `cameraId` |
| `cortexcam.ingestion.frames.v1` (binario) | ingestion | person/weapon/vehicle/plate → `<svc>.frames`; evidence → `evidence.frames` | `cameraId` |
| `cortexcam.ingestion.camera-health.v1` | ingestion | camera → `camera.health` | `cameraId` |
| `cortexcam.detection.person-detected.v1` | person | alert → `alert.detections` | `cameraId` |
| `cortexcam.detection.weapon-detected.v1` | weapon | alert → `alert.detections` | `cameraId` |
| `cortexcam.detection.vehicle-detected.v1` | vehicle | alert → `alert.detections` | `cameraId` |
| `cortexcam.detection.plate-recognized.v1` | plate | alert → `alert.detections` | `cameraId` |
| `cortexcam.alert.raised.v1` | alert | evidence → `evidence.alerts`; notification → `notification.alerts` | `cameraId` |
| `cortexcam.alert.lifecycle.v1` | alert | (futuro: ml-dataset, notification) | `alertId` |
| `cortexcam.evidence.stored.v1` | evidence | alert → `alert.evidence` | `alertId` |
| `<tópico>.dlq` | el consumidor que falla | operación | igual |

### 3.4 Flujo de una detección
`cámara → MediaMTX → ingestion → frames → person → person-detected → alert → alert.raised → (evidence → evidence.stored → alert) + (notification → SSE → navegador)`.

---

## 4. Orden de trabajo recomendado (cada paso deja algo que se puede probar)

1. **Correcciones C-01…C-10** (medio día).
2. **§01 Base**: compose + topics + starter. Prueba: `docker compose up`, Redpanda Console ve los tópicos.
3. **§02 camera-service completo**. Prueba: registrar cámara → fila en `outbox_event` → mensaje en `camera.lifecycle` → *path* en MediaMTX.
4. **§03 ingestion**. Prueba: frames visibles en el tópico.
5. **§04 person-detection**. Prueba: `person-detected` con cajas.
6. **§05 alert-service**. Prueba: una alerta en BD y `alert.raised` en Kafka.
7. **§07 notification** y **§06 evidence** (en cualquier orden). Prueba: alerta por SSE; imagen en MinIO.
8. **§08 gateway + frontend**. Prueba: login, cámara en vivo, alerta con sonido.
9. Weapon → vehicle → plate (copiando el detector).

## 5. Convenciones comunes a todos los servicios

| Tema | Regla |
|---|---|
| IDs | UUID v7 generado por `IdGeneratorPort` (Java: librería `uuid-creator`; Python: `uuid-utils`) |
| Tiempo | Siempre UTC. Java `Instant`, Python `datetime` con `tzinfo=UTC`, BD `timestamptz`, JSON ISO-8601 con `Z` |
| JSON | `camelCase`; enums en `MAYÚSCULAS`; cajas normalizadas 0..1 |
| Errores REST | RFC 9457 `ProblemDetail` con `code` (p. ej. `CAMERA_NOT_FOUND`) y `traceId` |
| Multi-tenant | `tenantId` viene **del JWT** (claim `tenant_id`), nunca del body; todas las consultas filtran por tenant |
| Migraciones | Flyway; **nunca editar una migración ya aplicada** (se crea V+1) |
| Secretos | Nunca en git (ver guía anterior §11). En esta guía los `.env` solo llevan valores de desarrollo |
| Puertos de salida | `application/port/out/<agregado>/` y `application/port/out/shared/` |
