package com.cortexcam.camera.application.port.out.stream;

import com.cortexcam.camera.domain.model.camera.CameraId;
import com.cortexcam.camera.domain.model.camera.StreamSource;

public interface StreamProvisioningPort {
    void provision(CameraId cameraId, StreamSource source);
}
