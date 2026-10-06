package com.cortexcam.camera.infrastructure.adapters.out.messaging;

import com.cortexcam.camera.domain.event.CameraRegistered;
import com.cortexcam.camera.domain.event.DomainEvent;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class EventContractMapper {

    private final JsonMapper objectMapper;

    public EventContractMapper(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ContractMessage toContract(DomainEvent event) {
        try {
            if (event instanceof CameraRegistered e) {
                var payload = objectMapper.createObjectNode();
                payload.put("specversion", "1.0");
                payload.put("id", UUID.randomUUID().toString()); // ID único del mensaje
                payload.put("type", "cortexcam.camera.registered.v1");
                payload.put("source", "urn:cortexcam:camera-service");
                payload.put("time", e.occurredAt().toString());
                
                var data = payload.putObject("data");
                data.put("cameraId", e.cameraId().value().toString());
                data.put("tenantId", e.tenantId().value().toString());
                data.put("name", e.name());
                
                // Tópico compactado según catálogo
                String topic = "cortexcam.camera.lifecycle.v1"; 
                String key = e.cameraId().value().toString();
                
                return new ContractMessage(topic, key, "cortexcam.camera.registered.v1", objectMapper.writeValueAsString(payload));
            }
            throw new IllegalArgumentException("Unknown event type: " + event.getClass().getName());
        } catch (Exception ex) {
            throw new RuntimeException("Error serializing event to JSON", ex);
        }
    }

    public record ContractMessage(String topic, String key, String type, String jsonPayload) {}
}