package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Autorización del módulo Finanzas de kioscos. Los {@code @PreAuthorize} no están activos en este backend,
 * por eso cada servicio llama a {@code assertCan*} explícitamente.
 * ADMIN (cualquier rol cuyo nombre contenga "ADMIN") siempre pasa.
 */
@Component
@RequiredArgsConstructor
public class KioskFinancialsAccessGuard {

    public static final String PERM_VIEW = "KIOSCOS.FINANZAS.VER";
    public static final String PERM_EDIT = "KIOSCOS.FINANZAS.EDITAR";
    public static final String PERM_IMPORT = "KIOSCOS.FINANZAS.IMPORTAR";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanView() throws BusinessException {
        require(PERM_VIEW, "No tiene permiso para ver las finanzas de kioscos.");
    }

    public void assertCanEdit() throws BusinessException {
        require(PERM_EDIT, "No tiene permiso para editar costos, metas o sitios de kioscos.");
    }

    public void assertCanImport() throws BusinessException {
        require(PERM_IMPORT, "No tiene permiso para importar reportes de kioscos.");
    }

    /** Id del usuario actual (para auditoría updated_by/created_by); null si no hay sesión. */
    public Long currentUserId() {
        return securityUtil.getCurrentUserId();
    }

    private void require(String permissionCode, String message) throws BusinessException {
        UserEntity user = currentUser();
        if (user == null) {
            throw new BusinessException(message);
        }
        if (hasRoleToken(user, "ADMIN") || hasPermission(user, permissionCode)) {
            return;
        }
        throw new BusinessException(message);
    }

    private UserEntity currentUser() {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private boolean hasRoleToken(UserEntity user, String token) {
        if (user.getRoles() == null) {
            return false;
        }
        String expected = normalizeRole(token);
        for (RoleEntity role : user.getRoles()) {
            if (role != null && normalizeRole(role.getName()).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermission(UserEntity user, String permissionCode) {
        if (user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null || role.getPermissions() == null) {
                continue;
            }
            for (PermissionEntity permission : role.getPermissions()) {
                if (permission != null && permissionCode.equals(permission.getCode())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeRole(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }
}
