package com.cortexcam.camera.infrastructure.adapters.out.messaging;

import com.cortexcam.camera.application.port.out.shared.DomainEventPublisherPort;
import com.cortexcam.camera.application.port.out.shared.IdGeneratorPort;
import com.cortexcam.camera.domain.event.DomainEvent;
import com.cortexcam.camera.infrastructure.adapters.out.persistence.entity.OutboxEntity;

import org.springframework.stereotype.Component;

import java.util.List;

@SuppressWarnings("null")
@Component
public class OutboxEventPublisherAdapter implements DomainEventPublisherPort {

    private final EventContractMapper mapper;
    private final SpringDataOutboxRepository outbox;
    private final IdGeneratorPort ids;

    public OutboxEventPublisherAdapter(EventContractMapper mapper, SpringDataOutboxRepository outbox, IdGeneratorPort ids) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.ids = ids;
    }

    @Override
    public void publish(List<DomainEvent> events) {
        for (DomainEvent e : events) {
            var m = mapper.toContract(e);
            outbox.save(OutboxEntity.pending(ids.newId(), m.topic(), m.key(), m.type(), m.jsonPayload()));
        }
    }
}
