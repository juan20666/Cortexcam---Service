package com.cortexcam.camera.infrastructure.adapters.out.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cortexcam.camera.infrastructure.adapters.out.persistence.entity.OutboxEntity;

import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEntity, UUID> {
}