# Cabeceras del Tópico de Frames (cortexcam.ingestion.frames.v1)

Los frames viajan como **mensajes binarios** en Kafka.
- **Key (Partition Key):** `cameraId` (UUID en formato string)
- **Value:** Array de bytes con la imagen codificada en JPEG

Para evitar parsear el binario para obtener metadatos, se usan las siguientes cabeceras de Kafka (`Kafka Headers`):

| Cabecera | Tipo | Ejemplo / Descripción |
|---|---|---|
| `frame-id` | `String` (UUID v7) | `0192f3b4-7c1e-7a4e-9d0a-5f0c2a1b7e00` |
| `camera-id` | `String` (UUID) | `7e2b7a1a-3c55-4d4f-9a45-0d3b9f1e6c10` |
| `captured-at` | `String` (ISO-8601 UTC) | `2026-09-30T15:04:05.050Z` |
| `content-type` | `String` | `image/jpeg` |
| `width` | `String` (Entero) | `1280` |
| `height` | `String` (Entero) | `720` |
| `traceparent` | `String` | Contexto de traza W3C (para Observabilidad) |
