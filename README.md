# CortexCam v2

Plataforma de videovigilancia inteligente con detección de personas, armas, vehículos y placas.

## Arquitectura

- **Hexagonal + DDD + Clean Architecture** en cada microservicio
- **Microservicios**: Java 21 + Spring Boot 4.1.x (negocio) · Python 3.12+ (visión/IA)
- **Mensajería**: Kafka (Redpanda en dev) — coreografía por eventos
- **Video en vivo**: MediaMTX (RTSP → WebRTC/HLS)
- **Autenticación**: Keycloak (OIDC) + gateway BFF
- **Frontend**: React + TypeScript + Vite, hexagonal por features

## Estructura del monorepo

```
cortexcam/
├── docs/                  # ADRs, diagramas C4, runbooks
├── contracts/             # Lo ÚNICO compartido: esquemas de eventos y OpenAPI
├── connections/           # Quién habla con quién: tópicos, rutas, ACLs, env templates
├── platform/              # Docker Compose, Keycloak, MediaMTX, parent POM, starter
├── services/              # Microservicios (Java y Python)
├── frontend/              # React + TypeScript + Vite
└── ml/                    # Entrenamiento, evaluación y exportación de modelos
```

## Servicios

| Servicio | Lenguaje | Responsabilidad |
|---|---|---|
| `gateway-service` | Java | BFF, OIDC login, CORS, rate limit |
| `camera-service` | Java | Registro de cámaras, credenciales cifradas, MediaMTX |
| `alert-service` | Java | Evaluar detecciones → alertas (umbral, cooldown, horario) |
| `evidence-service` | Java | Capturas/clips, retención, URLs firmadas |
| `notification-service` | Java | SSE, email, push, Telegram |
| `ingestion-service` | Python | RTSP → frames al tópico binario |
| `person-detection-service` | Python | YOLO → PersonDetected |
| `weapon-detection-service` | Python | YOLO → WeaponDetected |
| `vehicle-detection-service` | Python | YOLO → VehicleDetected |
| `plate-recognition-service` | Python | fast-alpr → PlateRecognized |

## Inicio rápido
contraseña del postgres change-me-admin-pwd
contraseña del Redpanda change-me
contraseña del Minio change-me-admin
contraseña del Keycloak admin/admin

```bash
# 1. Infraestructura
docker compose -f platform/docker-compose.yml up -d

# 2. Crear tópicos
bash connections/scripts/create-topics.sh

# 3. Servicios Java (ejemplo: camera-service)
cd services/camera-service && mvn spring-boot:run

# 4. Frontend
cd frontend && npm install && npm run dev
```

## Documentación

- [Reglas de arquitectura](docs/architecture-rules.md)
- [ADRs](docs/adr/)
- [Contratos](contracts/)
- [Conexiones](connections/)
