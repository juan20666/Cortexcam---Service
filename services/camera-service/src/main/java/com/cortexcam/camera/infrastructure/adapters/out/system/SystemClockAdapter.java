package com.cortexcam.camera.infrastructure.adapters.out.system;

import com.cortexcam.camera.application.port.out.camera.ClockPort;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class SystemClockAdapter implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
