package com.cortexcam.camera.application.port.out.shared;

import com.cortexcam.camera.domain.event.DomainEvent;
import java.util.List;

public interface DomainEventPublisherPort {
    void publish(List<DomainEvent> events);
}
