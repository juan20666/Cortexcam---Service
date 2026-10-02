package com.cortexcam.camera.domain.model.camera;

public enum CameraStatus {
    ONLINE, 
    OFFLINE, 
    DISABLED, 
    PROVISIONING_PENDING // Usado si MediaMTX falla temporalmente
}