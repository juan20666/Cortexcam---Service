import os
import re

def update_java_architecture_test(service_name):
    # e.g., alert-service -> alert
    short_name = service_name.replace('-service', '')
    path = f"services/{service_name}/src/test/java/com/cortexcam/{short_name}/architecture/ArchitectureTest.java"
    if os.path.exists(path):
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
        content = re.sub(r'package com\.cortexcam\.template\.architecture;', f'package com.cortexcam.{short_name}.architecture;', content)
        content = re.sub(r'@AnalyzeClasses\(packages = "com\.cortexcam\.template"\)', f'@AnalyzeClasses(packages = "com.cortexcam.{short_name}")', content)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)

for s in ['alert-service', 'evidence-service', 'notification-service', 'camera-service']:
    update_java_architecture_test(s)

def update_pom(service_name):
    short_name = service_name.replace('-service', '')
    path = f"services/{service_name}/pom.xml"
    if os.path.exists(path):
        with open(path, 'r', encoding='utf-8') as f:
            content = f.read()
        content = re.sub(r'<artifactId>.*-template</artifactId>', f'<artifactId>{service_name}</artifactId>', content)
        content = re.sub(r'<name>.*-template</name>', f'<name>{service_name}</name>', content)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)

for s in ['alert-service', 'evidence-service', 'notification-service']:
    update_pom(s)

def create_spring_boot_app(service_name):
    short_name = service_name.replace('-service', '')
    class_name = short_name.capitalize() + 'Application'
    dir_path = f"services/{service_name}/src/main/java/com/cortexcam/{short_name}"
    os.makedirs(dir_path, exist_ok=True)
    app_path = f"{dir_path}/{class_name}.java"
    if not os.path.exists(app_path):
        content = f"""package com.cortexcam.{short_name};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class {class_name} {{
    public static void main(String[] args) {{
        SpringApplication.run({class_name}.class, args);
    }}
}}
"""
        with open(app_path, 'w', encoding='utf-8') as f:
            f.write(content)

for s in ['alert-service', 'evidence-service', 'notification-service']:
    create_spring_boot_app(s)

python_services = {
    'ingestion-service': 'ingestion',
    'person-detection-service': 'person_detection',
    'weapon-detection-service': 'weapon_detection',
    'vehicle-detection-service': 'vehicle_detection',
    'plate-recognition-service': 'plate_recognition'
}

for svc, pkg in python_services.items():
    toml_path = f"services/{svc}/pyproject.toml"
    if os.path.exists(toml_path):
        with open(toml_path, 'r', encoding='utf-8') as f:
            content = f.read()
        content = content.replace('template-service', svc)
        content = content.replace('template_service', pkg)
        with open(toml_path, 'w', encoding='utf-8') as f:
            f.write(content)

    bootstrap_path = f"services/{svc}/src/{pkg}/bootstrap.py"
    if os.path.exists(bootstrap_path):
        with open(bootstrap_path, 'r', encoding='utf-8') as f:
            content = f.read()
        content = content.replace('template_service', pkg)
        with open(bootstrap_path, 'w', encoding='utf-8') as f:
            f.write(content)

    settings_path = f"services/{svc}/src/{pkg}/infrastructure/config/settings.py"
    if os.path.exists(settings_path):
        with open(settings_path, 'r', encoding='utf-8') as f:
            content = f.read()
        content = content.replace('template_service', pkg)
        with open(settings_path, 'w', encoding='utf-8') as f:
            f.write(content)

gitignore_additions = """
target/
.idea/
*.iml
.venv/
__pycache__/
node_modules/
dist/
**/.env
!**/.env.example
secrets/*.env
!secrets/*.enc.env
secrets/run/
*.pt
*.onnx
"""
with open('.gitignore', 'a', encoding='utf-8') as f:
    f.write(gitignore_additions)

gitattributes_content = """* text=auto
*.sh text eol=lf
*.yml text eol=lf
*.sql text eol=lf
"""
with open('.gitattributes', 'w', encoding='utf-8') as f:
    f.write(gitattributes_content)
