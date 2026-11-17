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
 * Autorización de Cuentas por pagar y Pagos a proveedores. Los {@code @PreAuthorize} no están activos en este backend,
 * por eso los servicios llaman a {@code assertCan*}. ADMIN (cualquier rol cuyo nombre contenga "ADMIN") siempre pasa.
 */
@Component
@RequiredArgsConstructor
public class SupplierPaymentAccessGuard {

    public static final String PERM_VIEW = "CONTABILIDAD.PAGOS_PROVEEDORES.VER";
    public static final String PERM_PAY = "CONTABILIDAD.PAGOS_PROVEEDORES.PAGAR";
    public static final String PERM_CONFIG = "CONTABILIDAD.PAGOS_PROVEEDORES.CONFIGURAR";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanView() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_VIEW) || hasPermission(user, PERM_PAY)
                || hasPermission(user, PERM_CONFIG))) {
            throw new BusinessException("No tiene permiso para ver las cuentas por pagar.");
        }
    }

    /** Devuelve el id del usuario que paga (para guardar quién lo hizo). */
    public Long assertCanPay() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_PAY))) {
            throw new BusinessException("No tiene permiso para registrar, imprimir o anular pagos a proveedores.");
        }
        return user.getId();
    }

    public Long assertCanConfigure() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_CONFIG))) {
            throw new BusinessException("No tiene permiso para configurar bancos, formas de pago o formatos de cheque.");
        }
        return user.getId();
    }

    public boolean canPay() throws BusinessException {
        UserEntity user = currentUser();
        return isAdmin(user) || hasPermission(user, PERM_PAY);
    }

    public boolean canConfigure() throws BusinessException {
        UserEntity user = currentUser();
        return isAdmin(user) || hasPermission(user, PERM_CONFIG);
    }

    private UserEntity currentUser() throws BusinessException {
        Long userId = securityUtil.getCurrentUserId();
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        return user;
    }

    private boolean isAdmin(UserEntity user) {
        if (user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role != null && role.getName() != null && role.getName().toUpperCase(Locale.ROOT).contains("ADMIN")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermission(UserEntity user, String code) {
        if (user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null || role.getPermissions() == null) {
                continue;
            }
            for (PermissionEntity permission : role.getPermissions()) {
                if (permission != null && code.equals(permission.getCode())) {
                    return true;
                }
            }
        }
        return false;
    }
}
