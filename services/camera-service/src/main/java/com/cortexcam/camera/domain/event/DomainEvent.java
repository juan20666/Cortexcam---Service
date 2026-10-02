package com.cortexcam.camera.domain.event;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredAt();
}