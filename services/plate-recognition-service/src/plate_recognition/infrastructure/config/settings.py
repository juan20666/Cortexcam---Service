from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import SecretStr

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        secrets_dir="/run/secrets",
        extra="ignore"
    )
    
    kafka_bootstrap_servers: str
    kafka_sasl_username: str
    kafka_sasl_password: SecretStr
    
    max_frame_age_ms: int = 1500
