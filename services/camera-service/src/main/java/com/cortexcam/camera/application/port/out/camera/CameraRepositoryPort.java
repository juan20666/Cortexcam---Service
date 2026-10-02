package com.cortexcam.camera.application.port.out.camera;

import com.cortexcam.camera.domain.model.camera.Camera;

public interface CameraRepositoryPort {
    Camera save(Camera camera);
}