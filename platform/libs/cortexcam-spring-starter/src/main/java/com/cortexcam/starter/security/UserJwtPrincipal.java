package com.cortexcam.starter.security;

/**
 * Principal del JWT: userId de auth y correo/documento opcional.
 */
public record UserJwtPrincipal(String userId, String email, String documentNumber) {

    @Override
    public String toString() {
        return userId;  
    }
}