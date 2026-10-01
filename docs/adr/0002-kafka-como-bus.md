# ADR-0002: Kafka como bus de eventos

**Estado:** Aceptado  
**Fecha:** 2026-09-30  

## Contexto

Los microservicios necesitan comunicarse de forma desacoplada. Las detecciones de IA generan un flujo continuo de eventos que múltiples servicios consumen.

## Decisión

Usar la API de Kafka (Redpanda en dev, Kafka en prod) como columna vertebral de mensajería. Orden garantizado por `cameraId` (partition key). Consumer groups por servicio. Dos tipos de tópicos: binarios (frames, vida corta) y JSON (eventos de negocio, durables).

## Consecuencias

- Desacoplamiento total entre productores y consumidores.
- Agregar un nuevo consumidor no toca al productor.
- Requiere Outbox para at-least-once y consumidores idempotentes.
