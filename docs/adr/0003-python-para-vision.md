# ADR-0003: Python para servicios de visión

**Estado:** Aceptado  
**Fecha:** 2026-09-30  

## Contexto

YOLO, OpenCV y ONNX Runtime son librerías de Python. El ecosistema de ML/visión vive en Python.

## Decisión

Los servicios de ingesta y detección (ingestion, person-detection, weapon-detection, vehicle-detection, plate-recognition) se implementan en Python 3.12+ con la misma arquitectura hexagonal. El dominio es stdlib pura (dataclasses, enum, typing). Los adaptadores implementan los puertos con las librerías de visión.

## Consecuencias

- Escalar detectores (GPU) independientemente de servicios de negocio (JVM).
- Dos runtimes en el monorepo, pero cada uno compila y prueba solo.
- import-linter verifica las reglas de arquitectura en Python.
