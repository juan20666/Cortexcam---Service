package com.cortexcam.camera.application.port.in.camera;

import java.util.UUID;

public interface RegisterCameraUseCase {
    Result handle(Command command);

    // R-07: Solo primitivos y tipos de Java puros
    record Command(UUID tenantId, String name, String host, int port, String path, String rawPassword) {}
    record Result(UUID cameraId, String status) {}
}