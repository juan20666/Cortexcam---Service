New-Item -ItemType Directory -Force -Path platform\templates
if (Test-Path services\_template-java) { Move-Item -Path services\_template-java -Destination platform\templates\java }
if (Test-Path services\_template-python) { Move-Item -Path services\_template-python -Destination platform\templates\python }

foreach ($svc in @('alert-service', 'evidence-service', 'notification-service')) {
    $src = "services\$svc\_template-java"
    if (Test-Path $src) {
        Copy-Item -Path "$src\*" -Destination "services\$svc\" -Recurse -Exclude "target"
        Remove-Item -Path $src -Recurse -Force
    }
}

New-Item -ItemType Directory -Force -Path services\alert-service\src\test\java\com\cortexcam\alert\architecture
if (Test-Path services\alert-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java) {
    Move-Item -Path services\alert-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java -Destination services\alert-service\src\test\java\com\cortexcam\alert\architecture\
}
if (Test-Path services\alert-service\src\test\java\com\cortexcam\template) { Remove-Item -Path services\alert-service\src\test\java\com\cortexcam\template -Recurse -Force }

New-Item -ItemType Directory -Force -Path services\evidence-service\src\test\java\com\cortexcam\evidence\architecture
if (Test-Path services\evidence-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java) {
    Move-Item -Path services\evidence-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java -Destination services\evidence-service\src\test\java\com\cortexcam\evidence\architecture\
}
if (Test-Path services\evidence-service\src\test\java\com\cortexcam\template) { Remove-Item -Path services\evidence-service\src\test\java\com\cortexcam\template -Recurse -Force }

New-Item -ItemType Directory -Force -Path services\notification-service\src\test\java\com\cortexcam\notification\architecture
if (Test-Path services\notification-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java) {
    Move-Item -Path services\notification-service\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java -Destination services\notification-service\src\test\java\com\cortexcam\notification\architecture\
}
if (Test-Path services\notification-service\src\test\java\com\cortexcam\template) { Remove-Item -Path services\notification-service\src\test\java\com\cortexcam\template -Recurse -Force }

New-Item -ItemType Directory -Force -Path services\camera-service\src\test\java\com\cortexcam\camera\architecture
if (Test-Path services\camera-service\_template-java\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java) {
    Copy-Item -Path services\camera-service\_template-java\src\test\java\com\cortexcam\template\architecture\ArchitectureTest.java -Destination services\camera-service\src\test\java\com\cortexcam\camera\architecture\
}
if (Test-Path services\camera-service\_template-java) { Remove-Item -Path services\camera-service\_template-java -Recurse -Force }

$py_services = @(
    @('ingestion-service', 'ingestion'),
    @('person-detection-service', 'person_detection'),
    @('weapon-detection-service', 'weapon_detection'),
    @('vehicle-detection-service', 'vehicle_detection'),
    @('plate-recognition-service', 'plate_recognition')
)

foreach ($pair in $py_services) {
    $svc = $pair[0]
    $pkg = $pair[1]
    $src = "services\$svc\_template-python"
    
    if (Test-Path $src) {
        Move-Item -Path "$src\pyproject.toml" -Destination "services\$svc\"
        Move-Item -Path "$src\.env.example" -Destination "services\$svc\"
        
        New-Item -ItemType Directory -Force -Path "services\$svc\src\$pkg\infrastructure\config"
        Move-Item -Path "$src\src\template_service\bootstrap.py" -Destination "services\$svc\src\$pkg\"
        Move-Item -Path "$src\src\template_service\config\settings.py" -Destination "services\$svc\src\$pkg\infrastructure\config\"
        
        Remove-Item -Path $src -Recurse -Force
        
        Get-ChildItem -Path "services\$svc\src" -Recurse -Directory | ForEach-Object {
            $initPath = Join-Path $_.FullName "__init__.py"
            if (-not (Test-Path $initPath)) {
                New-Item -ItemType File -Path $initPath | Out-Null
            }
        }
    }
}
