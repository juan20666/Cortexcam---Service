CREATE TABLE camera (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    stream_host VARCHAR(255) NOT NULL,
    stream_port INT NOT NULL,
    stream_path VARCHAR(255) NOT NULL,
    credentials_key_id VARCHAR(50),
    credentials_iv VARCHAR(50),
    credentials_ciphertext VARCHAR(500),
    status VARCHAR(50) NOT NULL
);

-- Evitar cámaras duplicadas con el mismo nombre en el mismo tenant
CREATE UNIQUE INDEX ix_camera_tenant_name ON camera (tenant_id, name);