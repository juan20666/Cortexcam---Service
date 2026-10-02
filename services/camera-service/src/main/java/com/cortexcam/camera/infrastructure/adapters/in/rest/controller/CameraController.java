package com.cortexcam.camera.infrastructure.adapters.in.rest.controller;

import com.cortexcam.camera.application.port.in.camera.RegisterCameraUseCase;
import com.cortexcam.camera.infrastructure.adapters.in.rest.dto.request.RegisterCameraRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cameras")
public class CameraController {

    private final RegisterCameraUseCase registerUseCase;

    public CameraController(RegisterCameraUseCase registerUseCase) {
        this.registerUseCase = registerUseCase;
    }

    @PostMapping
    // @PreAuthorize("hasAuthority('SCOPE_cameras:write')") // Lo activaremos cuando encendamos Keycloak
    public ResponseEntity<RegisterCameraUseCase.Result> register(@RequestBody RegisterCameraRequest request) {
        
        var command = new RegisterCameraUseCase.Command(
            request.tenantId(), 
            request.name(), 
            request.host(), 
            request.port(), 
            request.path(), 
            request.password()
        );
        
        var result = registerUseCase.handle(command);
        
        return ResponseEntity.ok(result);
    }
}