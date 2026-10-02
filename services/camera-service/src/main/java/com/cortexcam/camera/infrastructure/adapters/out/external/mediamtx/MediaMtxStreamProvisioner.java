package com.cortexcam.camera.infrastructure.adapters.out.external.mediamtx;

import com.cortexcam.camera.application.port.out.stream.StreamProvisioningPort;
import com.cortexcam.camera.domain.model.camera.CameraId;
import com.cortexcam.camera.domain.model.camera.StreamSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@SuppressWarnings("null")
@Component
public class MediaMtxStreamProvisioner implements StreamProvisioningPort {

    private final RestClient restClient;

    public MediaMtxStreamProvisioner(
            @Value("${MEDIAMTX_API_URL}") String baseUrl,
            @Value("${MEDIAMTX_API_USER}") String apiUser,
            @Value("${MEDIAMTX_API_PASSWORD}") String apiPassword) {
        
        String authHeader = "Basic " + Base64.getEncoder().encodeToString((apiUser + ":" + apiPassword).getBytes());
        
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authHeader)
                .build();
    }

    @Override
    public void provision(CameraId cameraId, StreamSource source) {
        // En un caso real, el CredentialCipherPort descifraría la contraseña aquí.
        // Para este paso, enviaremos la configuración base a MediaMTX.
        String sourceUrl = "rtsp://" + source.host() + ":" + source.port() + source.path();
        
        Map<String, Object> body = Map.of("source", sourceUrl);

        try {
            restClient.post()
                    .uri("/v3/config/paths/{name}", cameraId.value().toString())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // R-20: Si MediaMTX falla, se captura el error. Un scheduler reintentará luego.
            // No revertimos la transacción de la base de datos principal.
            System.err.println("MediaMTX provisioning failed for camera " + cameraId.value() + ": " + e.getMessage());
        }
    }
}