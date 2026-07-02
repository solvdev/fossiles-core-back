package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KioskExchangeAuthorizationGuard {

    public static final String PERM_VIEW = "KIOSCOS.CAMBIOS.AUTORIZAR.VER";
    public static final String PERM_APPROVE = "KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public boolean hasPermission(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getRoles() == null) {
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

    public void assertCanViewPendingAuthorizations() throws BusinessException {
        if (!hasPermission(PERM_VIEW) && !hasPermission(PERM_APPROVE)) {
            throw new BusinessException("No tiene permiso para ver solicitudes de cambio pendientes.");
        }
    }

    public void assertCanApproveOrReject() throws BusinessException {
        if (!hasPermission(PERM_APPROVE)) {
            throw new BusinessException("No tiene permiso para autorizar o rechazar cambios sin diferencia.");
        }
    }
}
