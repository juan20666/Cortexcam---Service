package com.cortexcam.camera.application.port.out.camera;

import com.cortexcam.camera.domain.model.shared.EncryptedSecret;

public interface CredentialCipherPort {
    // AAD = Associated Data (usaremos el ID de la cámara para atar la llave al registro)
    EncryptedSecret encrypt(String plainText, String associatedData); 
}