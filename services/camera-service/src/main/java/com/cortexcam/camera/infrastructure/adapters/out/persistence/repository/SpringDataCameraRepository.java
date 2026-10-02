package com.cortexcam.camera.infrastructure.adapters.out.persistence.repository;

import com.cortexcam.camera.infrastructure.adapters.out.persistence.entity.camera.CameraJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SpringDataCameraRepository extends JpaRepository<CameraJpaEntity, UUID> {
}
