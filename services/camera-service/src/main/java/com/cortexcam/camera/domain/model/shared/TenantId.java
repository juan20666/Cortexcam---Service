package com.cortexcam.camera.domain.model.shared;

import java.util.UUID;

public record TenantId(UUID value) {
    public TenantId {
        if (value == null) throw new IllegalArgumentException("TenantId cannot be null");
    }
}