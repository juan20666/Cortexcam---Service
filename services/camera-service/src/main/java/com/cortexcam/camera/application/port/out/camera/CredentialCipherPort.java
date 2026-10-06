package com.cortexcam.camera.application.port.out.camera;

import com.cortexcam.camera.domain.model.shared.EncryptedSecret;

public interface CredentialCipherPort {
    EncryptedSecret encrypt(String plainText, String associatedData); 
}
