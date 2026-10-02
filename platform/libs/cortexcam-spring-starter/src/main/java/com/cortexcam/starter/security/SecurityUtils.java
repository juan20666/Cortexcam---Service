package com.cortexcam.starter.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@SuppressWarnings("null")
@Component
public class SecurityUtils {

    public static UUID getCurrentCompanyId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        return authentication.getAuthorities().stream()
                .map(auth -> auth.getAuthority())
                .filter(auth -> auth.startsWith("COMPANY_ID_"))
                .map(auth -> auth.substring("COMPANY_ID_".length()))
                .findFirst()
                .map(UUID::fromString)
                .orElse(null);
    }

    public static String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        return authentication.getPrincipal().toString();
    }

    public static String getCurrentEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof UserJwtPrincipal principal) {
            String email = principal.email();
            return email == null || email.isBlank() ? null : email.trim();
        }
        return null;
    }

    public static String getCurrentDocumentNumber() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof UserJwtPrincipal principal) {
            String documentNumber = principal.documentNumber();
            return documentNumber == null || documentNumber.isBlank() ? null : documentNumber.trim();
        }
        return null;
    }

    public static String getUserRoleFromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null
                || authentication.getAuthorities().isEmpty()) {
            return null;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse(null);
    }

    public static String getUserRoleIdFromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null
                || authentication.getAuthorities().isEmpty()) {
            return null;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth.startsWith("ROLE_ID_"))
                .map(auth -> auth.substring("ROLE_ID_".length()))
                .findFirst()
                .orElse(null);
    }

    public static boolean canSeeInternalComments() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(String::toUpperCase)
                .anyMatch(SecurityUtils::isInternalCommentsAuthority);
    }

    private static boolean isInternalCommentsAuthority(String authority) {
        return "ROLE_SUPERADMIN".equals(authority)
                || "ROLE_SUPER_ADMIN".equals(authority)
                || "ROLE_ADMIN".equals(authority)
                || authority.contains("SUPERADMIN")
                || authority.contains("GESTION_HUMANA")
                || authority.contains("RECURSOS_HUMANOS")
                || authority.contains("RRHH");
    }
}