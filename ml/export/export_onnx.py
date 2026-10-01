# from ultralytics import YOLO

def export_to_onnx(model_path: str) -> None:
    print(f"Exportando {model_path} a ONNX...")
    # model = YOLO(model_path)
    # model.export(format="onnx", dynamic=True)
    print("Exportación completada.")

if __name__ == "__main__":
    # export_to_onnx("runs/train/yolo26-cortexcam-weapons/weights/best.pt")
    pass
