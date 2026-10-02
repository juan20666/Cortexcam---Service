package com.cortexcam.camera.infrastructure.adapters.in.rest.dto.request;

import java.util.UUID;

// DTO separado del Command interno (Regla R-06)
public record RegisterCameraRequest(
    UUID tenantId,
    String name,
    String host,
    int port,
    String path,
    String password
) {}