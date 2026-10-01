# ADR-0006: Puertos en application/ (no en domain/)

**Estado:** Aceptado  
**Fecha:** 2026-09-30  

## Contexto

En la estructura anterior, los puertos (interfaces de I/O) vivían en `domain/ports/`. Los puertos expresan lo que los casos de uso necesitan, que es la frontera de Clean Architecture.

## Decisión

Mover los puertos a `application/port/in/` y `application/port/out/`. El dominio queda sin ninguna interfaz de I/O: sólo contiene reglas (aggregates, VOs, policies, domain events, domain services, exceptions).

## Consecuencias

- El dominio es puro y testeable sin mocks.
- Los puertos se nombran por negocio (AlertRepositoryPort, no KafkaPort).
- ArchUnit verifica que domain no tenga interfaces de I/O.
- Consistente entre todos los microservicios Java y Python.
