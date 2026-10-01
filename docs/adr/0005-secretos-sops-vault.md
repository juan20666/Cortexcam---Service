# ADR-0005: Secretos con SOPS/age y Docker secrets

**Estado:** Aceptado  
**Fecha:** 2026-09-30  

## Contexto

El proyecto actual tiene secretos en claro en application.yml (contraseña de BD, JWT secret) y una API key de Roboflow en armas.py.

## Decisión

- `.env.example` versionado con nombres y valores de ejemplo. `.env` real NUNCA se versiona.
- Dev/CI: SOPS + age para cifrar secretos en reposo (commiteable como `.enc.env`).
- Producción: Docker secrets (`/run/secrets/`) o Vault.
- Credenciales de cámara: AES-256-GCM en la BD, con AAD = cameraId.
- gitleaks como pre-commit y en CI.

## Consecuencias

- Cero secretos en git, imágenes Docker o logs.
- Rotar secretos no requiere rebuild de imágenes.
- Cada servicio recibe sólo los secretos que necesita (mínimo privilegio).
