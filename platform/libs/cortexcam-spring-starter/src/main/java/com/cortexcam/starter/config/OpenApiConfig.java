package com.CortexcamService.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${server.servlet.context-path:/asset/api/manager}")
    private String contextPath;

    @Value("${app.asset.public-base-urls.dev:https://dev-rrhh.dcsas.com.co}")
    private String devBaseUrl;

    @Value("${app.asset.public-base-urls.staging:https://gestorrhh.dcsas.com.co:8080}")
    private String stagingBaseUrl;

    @Value("${app.asset.public-base-urls.prod:https://gestorrhh.dcsas.com.co:8080}")
    private String prodBaseUrl;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("📦 Asset Service API")
                        .description(
                                """
                                        # Microservicio de Gestión de Activos

                                        Gestión de ciclo de vida, asignaciones, préstamos y tipos de activos corporativos.

                                        ## Características
                                        - Activos (CRUD, historial, estados)
                                        - Asignaciones de activos a empleados con generación de actas (PDF)
                                        - Préstamos entre compañías o áreas
                                        - Parámetros dinámicos (SubAssetType, FieldDefinitions)
                                        - Envelope `GeneralResponse` para centralización a HTTP 200 con códigos de error internos.

                                        ## Autenticación
                                        JWT HS256 verificado. Se requiere el claim `companyId` para separación de inquilinos (Tenant).
                                        ```
                                        Authorization: Bearer <tu-token>
                                        ```
                                        """)
                        .version("1.0.0")
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort + contextPath + "/v1")
                                .description("🖥️ Servidor de Desarrollo Local"),
                        new Server()
                                .url(devBaseUrl + contextPath + "/v1")
                                .description("🧪 Servidor de Desarrollo (Gateway DEV)"),
                        new Server()
                                .url(stagingBaseUrl + contextPath + "/v1")
                                .description("🔄 Servidor de Staging"),
                        new Server()
                                .url(prodBaseUrl + contextPath + "/v1")
                                .description("🚀 Servidor de Producción")))
                .tags(List.of(
                        new Tag().name("Activos").description("Gestión central de activos y su ciclo de vida"),
                        new Tag().name("Asignaciones")
                                .description("Asignación de equipos a empleados y actas de entrega"),
                        new Tag().name("Préstamos").description("Control de préstamos temporales y devoluciones"),
                        new Tag().name("Configuración").description("Gestión de tipos, sub-tipos y campos dinámicos")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .name("Bearer Authentication")
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}