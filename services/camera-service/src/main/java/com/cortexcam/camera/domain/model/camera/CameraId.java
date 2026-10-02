package com.cortexcam.camera.domain.model.camera;

import java.util.UUID;

public record CameraId(UUID value) {
    public CameraId {
        if (value == null) throw new IllegalArgumentException("CameraId cannot be null");
    }
}