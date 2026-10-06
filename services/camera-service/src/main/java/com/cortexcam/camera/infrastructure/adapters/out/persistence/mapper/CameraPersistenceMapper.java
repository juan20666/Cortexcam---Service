package com.cortexcam.camera.infrastructure.adapters.out.persistence.mapper;

import com.cortexcam.camera.domain.model.camera.*;
import com.cortexcam.camera.domain.model.shared.EncryptedSecret;
import com.cortexcam.camera.domain.model.shared.TenantId;
import com.cortexcam.camera.infrastructure.adapters.out.persistence.entity.camera.CameraJpaEntity;

import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class CameraPersistenceMapper {

    public CameraJpaEntity toEntity(Camera domain) {
        var entity = new CameraJpaEntity();
        entity.setId(domain.id().value());
        entity.setTenantId(domain.tenantId().value());
        entity.setName(domain.name());
        entity.setStatus(domain.status().name());
        
        var stream = domain.streamSource();
        entity.setStreamHost(stream.host());
        entity.setStreamPort(stream.port());
        entity.setStreamPath(stream.path());
        
        var creds = stream.credentials();
        if (creds.isPresent()) {
            EncryptedSecret secret = creds.get();
            entity.setCredentialsKeyId(secret.keyId());
            entity.setCredentialsIv(secret.iv());
            entity.setCredentialsCiphertext(secret.cipherText());
        }
        
        return entity;
    }

    public Camera toDomain(CameraJpaEntity entity) {
        EncryptedSecret creds = null;
        if (entity.getCredentialsKeyId() != null && entity.getCredentialsIv() != null && entity.getCredentialsCiphertext() != null) {
            creds = new EncryptedSecret(entity.getCredentialsKeyId(), entity.getCredentialsIv(), entity.getCredentialsCiphertext());
        }
        var stream = new StreamSource(entity.getStreamHost(), entity.getStreamPort(), entity.getStreamPath(), Optional.ofNullable(creds));
        
        return Camera.reconstitute(
            new CameraId(entity.getId()),
            new TenantId(entity.getTenantId()),
            entity.getName(),
            stream,
            CameraStatus.valueOf(entity.getStatus())
        );
    }
}