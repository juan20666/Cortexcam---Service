package com.cortexcam.camera.infrastructure.adapters.out.persistence.entity.camera;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "camera")
public class CameraJpaEntity {
    @Id
    private UUID id;
    private UUID tenantId;
    private String name;
    
    // StreamSource aplanado
    private String streamHost;
    private int streamPort;
    private String streamPath;
    
    // EncryptedSecret aplanado
    private String credentialsKeyId;
    private String credentialsIv;
    private String credentialsCiphertext;
    
    private String status;

    // Getters y Setters obligatorios para JPA
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStreamHost() { return streamHost; }
    public void setStreamHost(String streamHost) { this.streamHost = streamHost; }
    public int getStreamPort() { return streamPort; }
    public void setStreamPort(int streamPort) { this.streamPort = streamPort; }
    public String getStreamPath() { return streamPath; }
    public void setStreamPath(String streamPath) { this.streamPath = streamPath; }
    public String getCredentialsKeyId() { return credentialsKeyId; }
    public void setCredentialsKeyId(String credentialsKeyId) { this.credentialsKeyId = credentialsKeyId; }
    public String getCredentialsIv() { return credentialsIv; }
    public void setCredentialsIv(String credentialsIv) { this.credentialsIv = credentialsIv; }
    public String getCredentialsCiphertext() { return credentialsCiphertext; }
    public void setCredentialsCiphertext(String credentialsCiphertext) { this.credentialsCiphertext = credentialsCiphertext; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
