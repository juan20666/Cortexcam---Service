package com.cortexcam.camera.domain.model.camera;

import com.cortexcam.camera.domain.event.CameraRegistered;
import com.cortexcam.camera.domain.event.DomainEvent;
import com.cortexcam.camera.domain.model.shared.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Camera {
    private final CameraId id;
    private final TenantId tenantId;
    private final String name;
    private final StreamSource streamSource;
    private CameraStatus status;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    // Constructor privado para forzar el uso del Factory Method
    private Camera(CameraId id, TenantId tenantId, String name, StreamSource streamSource, CameraStatus status) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.streamSource = streamSource;
        this.status = status;
    }

    /** Factory Method: Crea la cámara, le asigna estado y genera el evento de dominio */
    public static Camera register(CameraId id, TenantId tenantId, String name, StreamSource streamSource, Instant now) {
        var camera = new Camera(id, tenantId, name, streamSource, CameraStatus.ONLINE);
        
        // Emite el evento inmutable (R-11)
        camera.pendingEvents.add(new CameraRegistered(id, tenantId, name, now));
        
        return camera;
    }

    /** Reconstruye la cámara desde la base de datos sin emitir nuevos eventos */
    public static Camera reconstitute(CameraId id, TenantId tenantId, String name, StreamSource streamSource, CameraStatus status) {
        return new Camera(id, tenantId, name, streamSource, status);
    }

    /** Extrae y limpia los eventos pendientes para que el Outbox los guarde */
    public List<DomainEvent> pullEvents() {
        var copy = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return copy;
    }

    // Getters
    public CameraId id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public String name() { return name; }
    public StreamSource streamSource() { return streamSource; }
    public CameraStatus status() { return status; }
}