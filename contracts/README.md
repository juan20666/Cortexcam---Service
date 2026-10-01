# Contratos — CortexCam v2

Lo **único** que se comparte entre microservicios. Define **qué se dice** (forma de los mensajes y APIs).

## Estructura

```
contracts/
├── events/
│   ├── asyncapi.yaml              # Catálogo de tópicos, productores y consumidores
│   ├── envelope.schema.json       # Sobre CloudEvents común
│   ├── schemas/                   # Un esquema JSON por evento
│   └── examples/                  # Un JSON válido por evento (se usan en tests)
└── rest/
    ├── camera-service.openapi.yaml
    └── alert-service.openapi.yaml
```

## Reglas

1. Los contratos son **inmutables y versionados** (`.v1`).
2. Agregar un campo opcional = compatible.
3. Quitar/renombrar/cambiar tipo = `.v2` conviviendo con `.v1`.
4. CI valida que los ejemplos cumplen sus esquemas.
5. El modelo del contrato **nunca entra al dominio**: se traduce en el adaptador (ACL).
