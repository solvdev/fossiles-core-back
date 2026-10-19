package com.fossiles.fossilescorebackend.application.service.reconciliation;

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
 * Autorización de la conciliación. Los {@code @PreAuthorize} no están activos en este backend, por eso cada
 * servicio llama a {@code assertCan*}. Cualquier rol cuyo nombre contenga "ADMIN" siempre pasa.
 * Por decisión del negocio, proponer, autorizar, cerrar e importar son solo de Administración y Contabilidad:
 * se asignan esos permisos solo a esos roles.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationAccessGuard {

    public static final String PERM_VIEW = "VENTAS.CONCILIACION.VER";
    public static final String PERM_RECONCILE = "VENTAS.CONCILIACION.CONCILIAR";
    public static final String PERM_IMPORT = "VENTAS.CONCILIACION.IMPORTAR";
    public static final String PERM_ADJUST = "VENTAS.CONCILIACION.AJUSTAR";
    public static final String PERM_AUTHORIZE = "VENTAS.CONCILIACION.AUTORIZAR";
    public static final String PERM_CLOSE = "VENTAS.CONCILIACION.CERRAR";
    public static final String PERM_COMMISSIONS = "VENTAS.CONCILIACION.COMISIONES";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanView() throws BusinessException {
        require(PERM_VIEW, "No tiene permiso para ver la conciliación de ventas en línea.");
    }

    public void assertCanReconcile() throws BusinessException {
        require(PERM_RECONCILE, "No tiene permiso para conciliar casos.");
    }

    public void assertCanImport() throws BusinessException {
        require(PERM_IMPORT, "No tiene permiso para importar o capturar fondos.");
    }

    public void assertCanAdjust() throws BusinessException {
        require(PERM_ADJUST, "No tiene permiso para proponer ajustes.");
    }

    public void assertCanAuthorize() throws BusinessException {
        require(PERM_AUTHORIZE, "No tiene permiso para autorizar ajustes ni anular lotes.");
    }

    public void assertCanClose() throws BusinessException {
        require(PERM_CLOSE, "No tiene permiso para cerrar lotes.");
    }

    public void assertCanCommissions() throws BusinessException {
        require(PERM_COMMISSIONS, "No tiene permiso para las comisiones.");
    }

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
        return userId == null ? null : userRepository.findById(userId).orElse(null);
    }

    private boolean hasRoleToken(UserEntity user, String token) {
        if (user.getRoles() == null) return false;
        String expected = normalize(token);
        for (RoleEntity role : user.getRoles()) {
            if (role != null && normalize(role.getName()).contains(expected)) return true;
        }
        return false;
    }

    private boolean hasPermission(UserEntity user, String permissionCode) {
        if (user.getRoles() == null) return false;
        for (RoleEntity role : user.getRoles()) {
            if (role == null || role.getPermissions() == null) continue;
            for (PermissionEntity p : role.getPermissions()) {
                if (p != null && permissionCode.equals(p.getCode())) return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }
}
