import logging

# Configuración básica de logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s'
)

logger = logging.getLogger(__name__)

def bootstrap() -> None:
    """
    Composition Root: único lugar donde se inicializan y conectan:
    - Configuración (Settings)
    - Adaptadores de salida (Kafka, ONNX, Repositorios en memoria)
    - Casos de Uso
    - Adaptadores de entrada (Kafka Consumers, FastAPI)
    """
    logger.info("Iniciando servicio (Composition Root)...")
    
    # 1. Cargar Settings
    # settings = Settings()
    
    # 2. Inicializar adaptadores de salida
    # publisher = KafkaPublisher(settings)
    
    # 3. Inicializar casos de uso
    # usecase = MyUseCase(publisher)
    
    # 4. Inicializar adaptadores de entrada
    # consumer = KafkaConsumer(usecase, settings)
    
    logger.info("Servicio conectado y listo.")

if __name__ == "__main__":
    bootstrap()
