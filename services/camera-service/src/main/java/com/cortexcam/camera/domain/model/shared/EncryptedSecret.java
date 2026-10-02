package com.cortexcam.camera.domain.model.shared;

// Representa las credenciales RTSP cifradas (Nunca en texto plano)
public record EncryptedSecret(String keyId, String iv, String cipherText) {
    public EncryptedSecret {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("KeyId is required");
        if (iv == null || iv.isBlank()) throw new IllegalArgumentException("IV is required");
        if (cipherText == null || cipherText.isBlank()) throw new IllegalArgumentException("CipherText is required");
    }
}