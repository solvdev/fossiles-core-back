package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Acceso global a kioskos (todos los locales, promociones, operaciones POS agregadas).
 * Aplica a administradores y logística; no otorga permisos fuera del módulo kiosko.
 */
public final class KioskAccessHelper {

    private static final Set<String> LOGISTICS_ROLE_TOKENS = Set.of("LOGIST", "LOGISTICA", "LOGISTICO");
    private static final Set<String> ACCOUNTING_ROLE_TOKENS = Set.of("CONTABIL");

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

    /** Reportes agregados de ventas/caja (todos los kioskos): admin, logística y contabilidad. */
    public static boolean hasKioskReportsAccess(UserEntity user) {
        return hasAllKiosksAccess(user) || hasAccountingRole(user);
    }

    private static boolean hasAccountingRole(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(KioskAccessHelper::normalizeRole)
                .anyMatch(normalized -> ACCOUNTING_ROLE_TOKENS.stream().anyMatch(normalized::contains));
    }

    private static boolean isGlobalKioskRole(String normalizedRoleName) {
        if (normalizedRoleName.contains("ADMIN")) {
            return true;
        }
        if (normalizedRoleName.contains("SUPERVIS") && normalizedRoleName.contains("KIOSKO")) {
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
