package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Acceso exclusivo al Product Ledger Lab (cirugía de ledger PT/Devoluciones). Solo username {@code eramirez}.
 */
@Component
@RequiredArgsConstructor
public class ProductLedgerLabGuard {

    public static final String ALLOWED_USERNAME = "eramirez";

    private final SecurityUtil securityUtil;

    public void requireEramirez() {
        String username = securityUtil.getCurrentUsername();
        if (username == null || !ALLOWED_USERNAME.equalsIgnoreCase(username.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product Ledger Lab: acceso denegado.");
        }
    }

    public String requireEramirezUsername() throws BusinessException {
        requireEramirez();
        String username = securityUtil.getCurrentUsername();
        if (username == null || username.isBlank()) {
            throw new BusinessException("Usuario no autenticado.");
        }
        return username.trim();
    }
}
