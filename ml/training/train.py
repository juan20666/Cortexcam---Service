import yaml
import argparse
# from ultralytics import YOLO

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="Ruta al archivo YAML de configuración")
    args = parser.parse_args()

    with open(args.config, "r") as f:
        config = yaml.safe_load(f)
    
    print(f"Iniciando entrenamiento con config: {config['model']['name']}")
    
    # model = YOLO(config['model']['base'])
    # model.train(
    #     data=config['dataset']['path'],
    #     epochs=config['training']['epochs'],
    #     imgsz=config['training']['img_size'],
    #     batch=config['training']['batch_size'],
    #     device=config['training']['device'],
    #     project="runs/train",
    #     name=config['model']['name']
    # )

if __name__ == "__main__":
    main()
