package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Acceso a metas mensuales, comisión y asignación supervisora↔kiosko.
 * ADMIN y SUPERVISORA_KIOSKO configuran; ENCARGADA_KIOSKO solo ve su propio kiosko.
 */
@Component
public class KioskGoalAccessGuard {

    public boolean isAdmin(UserEntity user) {
        return hasRoleToken(user, "ADMIN");
    }

    public boolean isSupervisor(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(this::normalizeRole)
                .anyMatch(normalized -> normalized.contains("SUPERVIS") && normalized.contains("KIOSKO"));
    }

    public boolean canEditGoalsAndAssignments(UserEntity user) {
        return isAdmin(user) || isSupervisor(user);
    }

    public void assertCanEditGoalsAndAssignments(UserEntity user) throws BusinessException {
        if (!canEditGoalsAndAssignments(user)) {
            throw new BusinessException(
                    "No tiene permiso para configurar metas o asignaciones de kioskos. Solo administración y supervisoras.");
        }
    }

    public boolean canViewKiosk(UserEntity user, LocationEntity kiosk) {
        if (user == null || kiosk == null) {
            return false;
        }
        if (isAdmin(user) || isSupervisor(user)) {
            return true;
        }
        return Objects.equals(kiosk.getEncargadoId(), user.getId());
    }

    public void assertCanViewKiosk(UserEntity user, LocationEntity kiosk) throws BusinessException {
        if (!canViewKiosk(user, kiosk)) {
            throw new BusinessException("No tienes acceso a la meta o comisión de este kiosko.");
        }
    }

    private boolean hasRoleToken(UserEntity user, String token) {
        if (user == null || user.getRoles() == null || token == null || token.isBlank()) {
            return false;
        }
        String expected = normalizeRole(token);
        for (RoleEntity role : user.getRoles()) {
            if (role == null) {
                continue;
            }
            if (normalizeRole(role.getName()).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeRole(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }
}
