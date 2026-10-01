package com.CortexcamService.infrastructure.security;

import com.CortexcamService.infrastructure.config.CorsProperties;
import com.CortexcamService.infrastructure.tracing.MutationLoggingFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test")
@Slf4j
public class WebSecurityConfig {

    private final CorsProperties corsProperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final MutationLoggingFilter mutationLoggingFilter;
    private final InternalServiceTokenAuthFilter internalServiceTokenAuthFilter;

    public WebSecurityConfig(CorsProperties corsProperties,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            MutationLoggingFilter mutationLoggingFilter,
            InternalServiceTokenAuthFilter internalServiceTokenAuthFilter) {
        this.corsProperties = corsProperties;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.mutationLoggingFilter = mutationLoggingFilter;
        this.internalServiceTokenAuthFilter = internalServiceTokenAuthFilter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
        configuration.setAllowedMethods(corsProperties.getAllowedMethods());
        configuration.setAllowedHeaders(corsProperties.getAllowedHeaders());
        configuration.setExposedHeaders(corsProperties.getExposedHeaders());
        configuration.setAllowCredentials(corsProperties.isAllowCredentials());
        configuration.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**",
                                "/swagger-ui.html", "/webjars/**")
                        .permitAll()
                        .requestMatchers("/health", "/health/**", "/actuator/**").permitAll()
                        .requestMatchers("/v1/internal/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(internalServiceTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, InternalServiceTokenAuthFilter.class)
                .addFilterAfter(mutationLoggingFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}