package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Acceso global a kioskos (todos los locales, promociones, reportes agregados).
 * Aplica a administradores y logística; no otorga permisos fuera del módulo kiosko.
 */
public final class KioskAccessHelper {

    private static final Set<String> LOGISTICS_ROLE_TOKENS = Set.of("LOGIST", "LOGISTICA", "LOGISTICO");

    private KioskAccessHelper() {
    }

    public static boolean hasAllKiosksAccess(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(KioskAccessHelper::normalizeRole)
                .anyMatch(KioskAccessHelper::isGlobalKioskRole);
    }

    private static boolean isGlobalKioskRole(String normalizedRoleName) {
        if (normalizedRoleName.contains("ADMIN")) {
            return true;
        }
        return LOGISTICS_ROLE_TOKENS.stream().anyMatch(normalizedRoleName::contains);
    }

    private static String normalizeRole(String value) {
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }
}
