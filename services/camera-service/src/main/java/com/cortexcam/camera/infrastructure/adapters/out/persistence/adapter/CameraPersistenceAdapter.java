package com.cortexcam.camera.infrastructure.adapters.out.persistence.adapter;

import com.cortexcam.camera.application.port.out.camera.CameraRepositoryPort;
import com.cortexcam.camera.domain.model.camera.Camera;
import com.cortexcam.camera.infrastructure.adapters.out.persistence.mapper.CameraPersistenceMapper;
import com.cortexcam.camera.infrastructure.adapters.out.persistence.repository.SpringDataCameraRepository;
import org.springframework.stereotype.Component;

@SuppressWarnings("null")
@Component
public class CameraPersistenceAdapter implements CameraRepositoryPort {

    private final SpringDataCameraRepository repository;
    private final CameraPersistenceMapper mapper;

    public CameraPersistenceAdapter(SpringDataCameraRepository repository, CameraPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Camera save(Camera camera) {
        var entity = mapper.toEntity(camera);
        var savedEntity = repository.save(entity);
        return mapper.toDomain(savedEntity);
    }
}