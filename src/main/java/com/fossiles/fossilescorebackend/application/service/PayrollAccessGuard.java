package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Los salarios solo los ven administradores y Contabilidad. ADMIN (rol cuyo nombre contiene "ADMIN") siempre pasa;
 * los demás necesitan el permiso PLANILLA.* correspondiente.
 */
@Component
@RequiredArgsConstructor
public class PayrollAccessGuard {

    public static final String PERM_RUNS_VIEW = "PLANILLA.CORRIDAS.VER";
    public static final String PERM_RUNS_CREATE = "PLANILLA.CORRIDAS.CREAR";
    public static final String PERM_RUNS_APPROVE = "PLANILLA.CORRIDAS.APROBAR";
    public static final String PERM_CHARGES_VIEW = "PLANILLA.CARGOS.VER";
    public static final String PERM_CHARGES_EDIT = "PLANILLA.CARGOS.EDITAR";
    private static final java.util.Set<String> FULL_ACCESS = java.util.Set.of("ADMIN_FULL_ACCESS", "SUPER_ADMIN");

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanViewRuns() {
        require("No tiene permiso para ver la planilla.", PERM_RUNS_VIEW, PERM_RUNS_CREATE, PERM_RUNS_APPROVE);
    }

    public void assertCanCreateRuns() {
        require("No tiene permiso para preparar planillas.", PERM_RUNS_CREATE);
    }

    /** Aprobar, registrar el pago y anular. */
    public void assertCanApproveRuns() {
        require("No tiene permiso para aprobar, pagar o anular planillas.", PERM_RUNS_APPROVE);
    }

    public void assertCanViewCharges() {
        require("No tiene permiso para ver los cargos a colaboradores.", PERM_CHARGES_VIEW, PERM_CHARGES_EDIT);
    }

    public void assertCanEditCharges() {
        require("No tiene permiso para decidir cómo se liquidan los cargos a colaboradores.", PERM_CHARGES_EDIT);
    }

    /** Salarios y bonificaciones: solo administradores y quien trabaja la planilla (Contabilidad). */
    public boolean canViewSalaries() {
        return allowed(PERM_RUNS_VIEW, PERM_RUNS_CREATE, PERM_RUNS_APPROVE);
    }

    public Long currentUserId() {
        return securityUtil.getCurrentUserId();
    }

    private void require(String message, String... permissionCodes) {
        if (!allowed(permissionCodes)) {
            throw new AccessDeniedException(message);
        }
    }

    private boolean allowed(String... permissionCodes) {
        Long userId = securityUtil.getCurrentUserId();
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null) {
                continue;
            }
            if (normalizeRole(role.getName()).contains("ADMIN")) {
                return true;
            }
            if (role.getPermissions() == null) {
                continue;
            }
            for (PermissionEntity permission : role.getPermissions()) {
                if (permission == null || permission.getCode() == null) {
                    continue;
                }
                // Igual que el menú del frontend: estos dos permisos abren todo.
                if (FULL_ACCESS.contains(permission.getCode())) {
                    return true;
                }
                for (String code : permissionCodes) {
                    if (code.equals(permission.getCode())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String normalizeRole(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }
}
