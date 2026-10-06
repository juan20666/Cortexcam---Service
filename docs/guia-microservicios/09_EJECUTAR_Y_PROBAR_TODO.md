# 09 — Ejecutar y probar todo (de cero a "una persona entra en cuadro → alerta en pantalla")

Modo de desarrollo recomendado: **infraestructura en Docker, servicios desde terminal/IDE** (iteras rápido y los hostnames son siempre `localhost`). Dockerizar los servicios queda para el final (§8).

## 1. Requisitos
| Herramienta | Versión | Para qué |
|---|---|---|
| Docker Desktop | reciente | Postgres, Kafka (Redpanda), MinIO, Keycloak, MediaMTX |
| Java + Maven | 21 (o 25) · 3.9+ | servicios Java |
| Python + `uv` | 3.12+ | ingestion y detectores |
| Node.js | 20+ | frontend |
| `ffmpeg` | cualquiera | cámara falsa |
| Git Bash o WSL | — | `create-topics.sh` |
| Un video de prueba con personas (`sample.mp4`) | — | simular una cámara |

---

## 2. Orden de arranque (cada paso tiene su verificación; no pases al siguiente si falla)

| # | Qué | Comando (desde `D:\GitHub\Cortexcam---Service`) | Verificación |
|---|---|---|---|
| 0 | Variables de la infraestructura | `copy platform\.env.example platform\.env` | — |
| 1 | Infraestructura | `docker compose -f platform\docker-compose.yml --env-file platform\.env up -d` | `docker compose -f platform\docker-compose.yml ps` → todo *healthy*; Redpanda Console `http://localhost:8082`; MinIO `http://localhost:9001`; Keycloak `http://localhost:8081` |
| 2 | Tópicos | `bash connections/scripts/create-topics.sh` | Console muestra los 10 tópicos + los `.dlq` |
| 3 | Compilar base | `mvn -f platform\parent-pom\pom.xml install` y `mvn -f platform\libs\cortexcam-spring-starter\pom.xml clean install` | `BUILD SUCCESS` |
| 4 | camera-service | `cd services\camera-service` → `mvn spring-boot:run` | `http://localhost:8091/actuator/health` → `UP`; Flyway V1–V3 aplicadas |
| 5 | alert-service | `cd services\alert-service` → `mvn spring-boot:run` | `:8092` UP |
| 6 | notification-service | `cd services\notification-service` → `mvn spring-boot:run` | `:8094` UP |
| 7 | evidence-service | `cd services\evidence-service` → `mvn spring-boot:run` | `:8093` UP |
| 8 | ingestion | `cd services\ingestion-service` → `uv sync` → `uv run python -m ingestion.main` | `http://localhost:9101/readyz` → `ready` |
| 9 | person-detection | `cd services\person-detection-service` → `uv sync` → `uv run python -m person_detection.main` | `:9102/readyz` → `ready` |
| 10 | gateway | `cd services\gateway-service` → `mvn spring-boot:run` | `:8080` UP |
| 11 | frontend | `cd frontend` → `npm install` → `npm run dev` | `http://localhost:5173` redirige a Keycloak |

Cada servicio necesita su `.env` (puerto, BD, secretos de desarrollo; ver el documento de cada uno). **Orden de los servicios de negocio:** da igual, pero los que consumen deben estar arriba antes de generar tráfico. Los detectores weapon/vehicle/plate se agregan después (copias de person-detection).

Token para probar la API directamente (sin pasar por el gateway):
```bat
curl -s -X POST http://localhost:8081/realms/cortexcam/protocol/openid-connect/token ^
  -d "client_id=cortexcam-web" -d "client_secret=dev-secret-change-me" -d "grant_type=password" -d "username=admin" -d "password=admin"
```

---

## 3. Cámara falsa (un video en bucle como si fuera una cámara RTSP)
```bat
ffmpeg -re -stream_loop -1 -i sample.mp4 -an -c:v libx264 -preset ultrafast -tune zerolatency -g 30 -pix_fmt yuv420p ^
       -f rtsp -rtsp_transport tcp rtsp://localhost:8554/fake
```
Compruébalo con VLC: `rtsp://localhost:8554/fake`.
Registra la cámara apuntando a ese *path* (MediaMTX se lo pedirá a sí mismo): `host=127.0.0.1`, `port=8554`, `path=/fake`, sin usuario/clave (`credenciales opcionales`, corrección C-06).
Para una **cámara real**: `host` = IP de la cámara en tu red, `port` normalmente 554, `path` según el fabricante, y usuario/clave. Activa RTSP/ONVIF en la cámara.

---

## 4. Prueba de humo de extremo a extremo (PowerShell)
Guarda como `scripts/e2e-smoke.ps1` y ejecútalo con la cámara falsa corriendo y todos los servicios arriba.
```powershell
$ErrorActionPreference = 'Stop'
$tok = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/realms/cortexcam/protocol/openid-connect/token' `
        -Body @{client_id='cortexcam-web'; client_secret='dev-secret-change-me'; grant_type='password'; username='admin'; password='admin'}).access_token
$h = @{ Authorization = "Bearer $tok" }

# 1) registrar cámara
$body = @{ name = "e2e-$(Get-Random)"; host = '127.0.0.1'; port = 8554; path = '/fake'; samplingFps = 2 } | ConvertTo-Json
$cam  = Invoke-RestMethod -Method Post -Uri 'http://localhost:8091/api/v1/cameras' -Headers $h -ContentType 'application/json' -Body $body
Write-Host "Camara creada: $($cam.id) ($($cam.status))"

# 2) esperar a que el reconciliador la deje ACTIVE
for ($i = 0; $i -lt 30; $i++) {
  $c = Invoke-RestMethod -Uri "http://localhost:8091/api/v1/cameras/$($cam.id)" -Headers $h
  if ($c.status -eq 'ACTIVE') { break }; Start-Sleep 2
}
if ($c.status -ne 'ACTIVE') { throw "La camara no llego a ACTIVE (estado: $($c.status))" }
Write-Host "Camara ACTIVE"

# 3) esperar la alerta (ingestion -> person-detection -> alert)
$alert = $null
for ($i = 0; $i -lt 40 -and -not $alert; $i++) {
  $r = Invoke-RestMethod -Uri "http://localhost:8092/api/v1/alerts?cameraId=$($cam.id)&size=5" -Headers $h
  if ($r.items.Count -gt 0) { $alert = $r.items[0] } else { Start-Sleep 3 }
}
if (-not $alert) { throw "No llego ninguna alerta en ~2 minutos" }
Write-Host "Alerta: $($alert.id) tipo=$($alert.type) severidad=$($alert.severity)"

# 4) evidencia
$ev = $null
for ($i = 0; $i -lt 10 -and -not $ev; $i++) {
  $e = Invoke-RestMethod -Uri "http://localhost:8093/api/v1/evidence?alertId=$($alert.id)" -Headers $h
  if ($e.Count -gt 0) { $ev = $e[0] } else { Start-Sleep 2 }
}
if (-not $ev) { throw "La alerta no tiene evidencia" }
$u = Invoke-RestMethod -Uri "http://localhost:8093/api/v1/evidence/$($ev.id)/url" -Headers $h
Write-Host "Evidencia lista: $($u.url.Substring(0,60))..."

# 5) ciclo de vida
Invoke-RestMethod -Method Post -Uri "http://localhost:8092/api/v1/alerts/$($alert.id)/acknowledge" -Headers $h | Out-Null
Write-Host "OK: flujo completo"
```

---

## 5. Matriz de verificación (cada salto del flujo y dónde mirar)

| Salto | Evidencia de que funciona | Dónde |
|---|---|---|
| Registro | `201`; fila en `camera` con `credentials_ciphertext` (sin texto claro) | `psql -h localhost -U camera_svc camera_db` |
| Outbox → Kafka | `outbox_event.published_at` lleno; mensaje `REGISTERED` | tabla + Console `camera.lifecycle.v1` |
| Provisionar | `PENDING → ACTIVE` en ≤10 s; path `cam-<uuid>` | `curl http://localhost:9997/v3/config/paths/list` |
| Captura | Frames con cabeceras `camera-id`, `captured-at`… al fps pedido | Console `ingestion.frames.v1` |
| Detección | `person-detected.v1` con `detections[].box` entre 0 y 1 | Console + `:9102/metrics` (`frames_outcome_total{outcome="published"}`) |
| Alerta | **una** fila `OPEN` y un `alert.raised.v1`; repetir no duplica en 10 s | `alert_db.alert` + Console |
| Evidencia | `.jpg` en MinIO y `evidence.stored.v1` | MinIO Console + Console |
| Notificación | `event: alert` por SSE y fila `SENT` | `curl -N` al `/stream` + `notification_db.notification` |
| Seguridad | Sin token → 401; `viewer` en POST → 403; body inválido → 422 con `code` | `curl` |
| Resiliencia | Apaga MediaMTX 20 s → `OFFLINE` y vuelve a `ACTIVE`; apaga Kafka 1 min → al volver no se pierde ninguna alerta (Outbox) | prueba manual |

---

## 6. Problemas típicos y su causa

| Síntoma | Causa probable | Solución |
|---|---|---|
| `401` en todos los servicios aunque el token es válido | `issuer-uri` no coincide con el `iss` del token | El token debe pedirse a `http://localhost:8081/realms/cortexcam` y los servicios usar ese mismo `OIDC_ISSUER_URI` (por eso corren en el host) |
| Servicios Java no conectan a Kafka (`Connection refused`/timeout) | *advertised listener* mal configurado | Desde el host usa `localhost:19092`; entre contenedores `redpanda:9092` (ver compose) |
| Cámara se queda en `PENDING` | `camera-service` no alcanza la API de MediaMTX o la rechaza | `curl http://localhost:9997/v3/config/paths/list`; revisa `authInternalUsers` (dev) y `MEDIAMTX_API_URL` |
| Ingestion no abre el stream | El path aún no existe o el origen no responde | VLC a `rtsp://localhost:8554/cam-<uuid>`; logs de MediaMTX; ¿corre `ffmpeg`? |
| Ingestion arranca pero no captura nada | No leyó el catálogo | Debe existir `camera.lifecycle.v1` y haber mensajes; el grupo es único y `earliest` (03 §5.3); `readyz` debe decir `ready` |
| Detector no publica nada | Config desactivada o frames "viejos" | Por defecto solo `PERSON` está activo por cámara; revisa `outcome="stale"/"disabled"` en `/metrics`; si productor y consumidor están en máquinas distintas, sincroniza relojes (NTP) |
| Llegan detecciones pero no hay alerta | Umbral, horario, confirmación o *cooldown* | Revisa la regla efectiva; para armas hacen falta N detecciones en M segundos; mira `processed_message` (duplicados) y los `.dlq` |
| Alerta sin evidencia | Frame fuera del buffer o `evidence.frames` con *lag* | Aumenta `max-frames-per-camera`; mira el lag del grupo `evidence.frames`; revisa `alert.raised.v1.dlq` |
| SSE no llega por el gateway/Vite | *buffering* o cookie de sesión ausente | Prueba directo a `:8094` con `curl -N` y token; en proxies desactiva *buffering* |
| `Schema-validation: wrong column type … found [numeric], expecting [float(53)]` | Columna `numeric(…)` mapeada a `double` con `ddl-auto: validate` | En la entidad usa `BigDecimal`, o cambia la columna a `double precision` con una migración nueva (aplica a `sampling_fps`, `min_confidence`, `trigger_confidence`, `confidence`) |
| `Flyway: checksum mismatch` | Editaste una migración ya aplicada | Crea `V+1`; en desarrollo puedes `docker compose … down -v` |
| El script de Postgres no corrió | El volumen ya tenía datos | `docker compose … down -v` y vuelve a levantar |
| `create-topics.sh: line 1: $'\r': command not found` | Fin de línea CRLF | `.gitattributes` con `*.sh text eol=lf` y re-clona/convierte el archivo |
| Hibernate se queja del tipo JSON | JSON en `jsonb` mapeado como `String` | Mantén esas columnas como `text` (decisión de las guías 02/05) |
| Mensajes de Kafka "demasiado grandes" | Frame > 1 MB | `MAX_FRAME_WIDTH`/`JPEG_QUALITY` más bajos; tópico `max.message.bytes=2097152` y producer igual |

---

## 7. Ajuste de rendimiento (en este orden)
1. **`samplingFps`** por cámara (2 fps alcanza para personas; 1 fps para vehículos). Es el mayor ahorro.
2. **Motion gate** en ingestion (`MOTION_GATE_ENABLED=true`): no se infiere si no hay movimiento.
3. **`imgsz` y modelo:** `yolov8n` + `imgsz=640` para personas/vehículos; sube solo para armas (`960`).
4. **Escala los detectores:** más réplicas del mismo servicio comparten el grupo `<servicio>.frames`; el número útil de réplicas es ≤ particiones (6).
5. **GPU** solo para el detector que más cuesta; `DEVICE=cuda:0`.
6. **`evidence-service`:** `-Xmx` acorde al buffer (MB por cámara × cámaras).
7. Mira `inference_seconds` (p95) y `frames_dropped_total`: si el descarte es alto, el detector no da abasto.

---

## 8. Dockerizar los servicios (cuando la versión local funcione)
- **Java:** Dockerfile multi-stage (`eclipse-temurin:21-jdk` para compilar, `…-jre` para correr, usuario no-root, sin secretos). `docker-compose.services.yml` define cada servicio con `SPRING_CONFIG_IMPORT: optional:configtree:/run/secrets/` y `secrets:`.
- **Python:** `python:3.12-slim` + `uv sync --frozen --no-dev`; el modelo se monta como volumen (no va en la imagen) y se verifica con `MODEL_SHA256`.
- **Red:** dentro de Docker los hosts pasan a `postgres`, `redpanda:9092`, `minio:9000`, `mediamtx`. Keycloak: fija `KC_HOSTNAME` para que el `iss` sea el mismo para el navegador y los servicios (o define `jwk-set-uri` interno).
- Solo `gateway` y MediaMTX publican puertos al host.

## 9. Antes de llevarlo a producción (resumen; detalle en la guía de arquitectura §11)
- [ ] Secretos fuera de git (SOPS/Vault/Docker secrets); **API key de Roboflow revocada y rotada**.
- [ ] TLS en gateway, Keycloak, MediaMTX y MinIO; Kafka con SASL + ACLs por servicio.
- [ ] MediaMTX con `authInternalUsers` reales y API solo en red interna.
- [ ] Un usuario de BD y de MinIO por servicio con mínimo privilegio; `evidence_access_log` con solo `INSERT/SELECT`.
- [ ] Retención de evidencia definida y avisos de videovigilancia (Ley 1581 de 2012); confirmación humana antes de cualquier acción por una alerta de arma.
- [ ] Dashboards: lag por grupo, `inference_seconds` p95, DLQ, cámaras `OFFLINE`; alertas operativas.
- [ ] CI: build + tests + ArchUnit/import-linter/eslint-boundaries + gitleaks + validación de contratos.
- [ ] Licencia de Ultralytics (AGPL-3.0 o Enterprise) revisada si vas a comercializar.

## 10. Definición de "hecho" del proyecto
La prueba de humo (§4) pasa en limpio sobre una máquina nueva siguiendo solo estos documentos, el frontend muestra el video en vivo y la alerta con sonido, y `mvn verify`, `uv run pytest`, `uv run lint-imports` y `npm run lint` están en verde en CI.
