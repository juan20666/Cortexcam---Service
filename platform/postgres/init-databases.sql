-- =========================================================================
-- Base de datos para Keycloak
-- =========================================================================
CREATE DATABASE keycloak_db;
CREATE USER keycloak_user WITH ENCRYPTED PASSWORD 'change-me';
GRANT ALL PRIVILEGES ON DATABASE keycloak_db TO keycloak_user;

-- =========================================================================
-- Base de datos para Camera Service
-- =========================================================================
CREATE DATABASE camera_db;
CREATE USER camera_svc WITH ENCRYPTED PASSWORD 'change-me';
GRANT ALL PRIVILEGES ON DATABASE camera_db TO camera_svc;

-- =========================================================================
-- Base de datos para Alert Service
-- =========================================================================
CREATE DATABASE alert_db;
CREATE USER alert_svc WITH ENCRYPTED PASSWORD 'change-me';
GRANT ALL PRIVILEGES ON DATABASE alert_db TO alert_svc;

-- =========================================================================
-- Base de datos para Evidence Service
-- =========================================================================
CREATE DATABASE evidence_db;
CREATE USER evidence_svc WITH ENCRYPTED PASSWORD 'change-me';
GRANT ALL PRIVILEGES ON DATABASE evidence_db TO evidence_svc;

-- =========================================================================
-- Base de datos para Notification Service
-- =========================================================================
CREATE DATABASE notification_db;
CREATE USER notification_svc WITH ENCRYPTED PASSWORD 'change-me';
GRANT ALL PRIVILEGES ON DATABASE notification_db TO notification_svc;
