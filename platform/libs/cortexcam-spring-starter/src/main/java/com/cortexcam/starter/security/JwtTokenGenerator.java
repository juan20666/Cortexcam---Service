package com.CortexcamService.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtTokenGenerator {
    public static void main(String[] args) {
        // Usa el mismo secret que tienes en application.yml
        String secret = "TuSuperSecretoDeDesarrolloQueDebeTenerAlMenos32CaracteresParaHS256";
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        String token = Jwts.builder()
                .claim("userId", "123")
                .claim("email", "juan@example.com")
                .claim("roles", new String[] { "ADMIN" })
                .subject("123456789") // documentNumber opcional
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000)) // 1 hora
                .signWith(key)
                .compact();

        System.out.println("TOKEN: " + token);
    }
}