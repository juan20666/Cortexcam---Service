package com.cortexcam.camera.infrastructure.adapters.out.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEntity {
    @Id
    private UUID id;
    private String topic;
    private String partitionKey;
    private String eventType;
    private String payload; // En Postgres es JSONB, aquí lo manejamos como String
    private Instant createdAt;
    private Instant publishedAt;

    // Factory method para crear un evento pendiente de envío
    public static OutboxEntity pending(UUID id, String topic, String key, String type, String payload) {
        var entity = new OutboxEntity();
        entity.id = id;
        entity.topic = topic;
        entity.partitionKey = key;
        entity.eventType = type;
        entity.payload = payload;
        entity.createdAt = Instant.now();
        return entity;
    }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getPartitionKey() { return partitionKey; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}