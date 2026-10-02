package com.cortexcam.camera.domain.event;

import com.cortexcam.camera.domain.model.camera.CameraId;
import com.cortexcam.camera.domain.model.shared.TenantId;
import java.time.Instant;

public record CameraRegistered(
    CameraId cameraId,
    TenantId tenantId,
    String name,
    Instant occurredAt
) implements DomainEvent {}
