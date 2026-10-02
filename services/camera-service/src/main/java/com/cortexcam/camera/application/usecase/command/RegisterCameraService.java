package com.cortexcam.camera.application.usecase.command;

import com.cortexcam.camera.application.port.in.camera.RegisterCameraUseCase;
import com.cortexcam.camera.application.port.out.camera.*;
import com.cortexcam.camera.application.port.out.stream.StreamProvisioningPort;
import com.cortexcam.camera.domain.model.camera.*;
import com.cortexcam.camera.domain.model.shared.TenantId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RegisterCameraService implements RegisterCameraUseCase {

    private final CameraRepositoryPort repository;
    private final CredentialCipherPort cipher;
    private final DomainEventPublisherPort eventPublisher;
    private final IdGeneratorPort idGenerator;
    private final ClockPort clock;
    private final StreamProvisioningPort provisioner;

    public RegisterCameraService(
            CameraRepositoryPort repository,
            CredentialCipherPort cipher,
            DomainEventPublisherPort eventPublisher,
            IdGeneratorPort idGenerator,
            ClockPort clock,
            StreamProvisioningPort provisioner
    ) {
        this.repository = repository;
        this.cipher = cipher;
        this.eventPublisher = eventPublisher;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.provisioner = provisioner;
    }

    @Override
    public Result handle(Command command) {

        var cameraId = new CameraId(idGenerator.newId());
        var tenantId = new TenantId(command.tenantId());

        // Cifrar credencial usando CameraId como AAD
        var encryptedSecret =
                cipher.encrypt(
                        command.rawPassword(),
                        cameraId.value().toString()
                );

        var streamSource =
                new StreamSource(
                        command.host(),
                        command.port(),
                        command.path(),
                        encryptedSecret
                );

        // Crear agregado
        var camera =
                Camera.register(
                        cameraId,
                        tenantId,
                        command.name(),
                        streamSource,
                        clock.now()
                );

        repository.save(camera);

        // Publicar eventos al Outbox
        eventPublisher.publish(camera.pullEvents());

        // Provisionar pipeline de streaming
        provisioner.provision(cameraId, streamSource);

        return new Result(
                cameraId.value(),
                camera.status().name()
        );
    }
}