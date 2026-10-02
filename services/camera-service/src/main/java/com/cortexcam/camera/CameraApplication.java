package com.cortexcam.camera;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Necesario para que corra el OutboxRelay en el futuro
public class CameraApplication {
    public static void main(String[] args) {
        SpringApplication.run(CameraApplication.class, args);
    }
}