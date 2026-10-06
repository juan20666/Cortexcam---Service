package com.cortexcam.camera.infrastructure.adapters.out.system;

import com.cortexcam.camera.application.port.out.camera.CredentialCipherPort;
import com.cortexcam.camera.domain.model.shared.EncryptedSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AesGcmCredentialCipher implements CredentialCipherPort {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BIT = 128;
    private static final int GCM_IV_LENGTH_BYTE = 12;

    private final byte[] key;
    private final String keyId;

    public AesGcmCredentialCipher(
            @Value("${CAMERA_CRED_KEY_B64}") String keyBase64,
            @Value("${CAMERA_CRED_KEY_ID}") String keyId) {
        this.key = Base64.getDecoder().decode(keyBase64);
        this.keyId = keyId;
    }

    @Override
    public EncryptedSecret encrypt(String plainText, String associatedData) {
        if (plainText == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv);
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");

            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, parameterSpec);
            
            // Usamos el ID de la cámara como AAD para atar el cifrado a ese registro
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return new EncryptedSecret(
                    keyId,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherText)
            );
        } catch (Exception e) {
            throw new RuntimeException("Error encrypting camera credentials", e);
        }
    }
}