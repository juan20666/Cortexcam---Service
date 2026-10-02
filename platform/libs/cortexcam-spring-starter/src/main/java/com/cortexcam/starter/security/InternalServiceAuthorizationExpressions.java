package com.cortexcam.starter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("internalAuthz")
@Slf4j
public class InternalServiceAuthorizationExpressions {

    public boolean isInternalService() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof ServiceAccountAuthentication;
    }

    public boolean canReadAssets(String serviceName) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof ServiceAccountAuthentication serviceAuth)) {
            return false;
        }
        String actualService = serviceAuth.getServiceName();
        boolean authorized = actualService.equals("candidate-service") ||
                actualService.equals("employee-service") ||
                actualService.equals("company-admin");
        log.debug("canReadAssets: servicio={}, autorizado={}", actualService, authorized);
        return authorized;
    }

    public boolean canModifyAssets(String serviceName) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof ServiceAccountAuthentication serviceAuth)) {
            return false;
        }
        String actualService = serviceAuth.getServiceName();
        boolean authorized = actualService.equals("employee-service");
        log.debug("canModifyAssets: servicio={}, autorizado={}", actualService, authorized);
        return authorized;
    }
}