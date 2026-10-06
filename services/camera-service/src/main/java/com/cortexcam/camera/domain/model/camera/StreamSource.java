package com.cortexcam.camera.domain.model.camera;

import com.cortexcam.camera.domain.model.shared.EncryptedSecret;
import java.util.Optional;

public record StreamSource(String host, int port, String path, Optional<EncryptedSecret> credentials) {
    public StreamSource {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host cannot be empty");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid port number");
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("Path must start with '/'");
    }
}