package com.cortexcam.camera.infrastructure.adapters.out.system;

import com.cortexcam.camera.application.port.out.shared.IdGeneratorPort;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class UuidV7GeneratorAdapter implements IdGeneratorPort {
    @Override
    public UUID newId() {
        // En un proyecto real puedes usar una librería de UUID v7 (java-uuid-generator).
        // Por ahora usaremos v4 estándar para avanzar.
        return UUID.randomUUID();
    }
}
