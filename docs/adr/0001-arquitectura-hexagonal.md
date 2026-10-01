# ADR-0001: Arquitectura Hexagonal + DDD + Clean Architecture

**Estado:** Aceptado  
**Fecha:** 2026-09-30  

## Contexto

CortexCam necesita aislar reglas de negocio (alertas, cooldowns, confirmación temporal) de los frameworks (Spring, Kafka, YOLO, PostgreSQL).

## Decisión

Cada microservicio sigue la arquitectura hexagonal con capas `domain/`, `application/`, `infrastructure/`. DDD táctico (aggregates, VOs, domain events, policies) modela el núcleo. Clean Architecture define la dirección de dependencias: infrastructure → application → domain.

## Consecuencias

- El dominio se prueba sin Spring ni base de datos.
- Cambiar de framework o broker no toca reglas de negocio.
- Requiere disciplina en mapeos entre fronteras (ArchUnit lo verifica).
