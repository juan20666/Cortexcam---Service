#!/bin/bash
# Script para crear los tópicos de Kafka/Redpanda localmente
# Requiere que Redpanda esté corriendo en localhost:9092

echo "Creando tópicos en Redpanda..."

# Función para crear un tópico si no existe
create_topic() {
  local topic=$1
  local partitions=$2
  local config=$3

  # rpk topic create $topic -p $partitions -c $config --brokers localhost:9092
  # Como estamos usando Kafka/Redpanda en docker, ejecutamos rpk dentro del contenedor:
  docker exec -it cortexcam-redpanda rpk topic create $topic -p $partitions -c $config
}

create_topic "cortexcam.camera.lifecycle.v1" 3 "cleanup.policy=compact"
create_topic "cortexcam.ingestion.frames.v1" 6 "retention.ms=60000"
create_topic "cortexcam.detection.person-detected.v1" 6 "retention.ms=604800000"
create_topic "cortexcam.detection.weapon-detected.v1" 6 "retention.ms=604800000"
create_topic "cortexcam.alert.raised.v1" 3 "retention.ms=2592000000"

echo "Tópicos creados exitosamente."
