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
public class InternalShipmentRequestAccessGuard {

    public static final String PERM_DISTRIBUTION_VIEW = "DISTRIBUCION.AUTORIZAR_ENVIOS.VER";
    public static final String PERM_DISTRIBUTION_CREATE = "DISTRIBUCION.AUTORIZAR_ENVIOS.CREAR";
    public static final String PERM_ACCOUNTING_VIEW = "CONTABILIDAD.ENVIOS.VER";
    public static final String PERM_ACCOUNTING_APPROVE = "CONTABILIDAD.ENVIOS.APROBAR";

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

    public boolean canViewAccountingWorkspace() {
        return hasPermission(PERM_ACCOUNTING_APPROVE) || hasPermission(PERM_ACCOUNTING_VIEW);
    }

    public void assertCanCreateRequest() throws BusinessException {
        if (!hasPermission(PERM_DISTRIBUTION_CREATE)) {
            throw new BusinessException("No tiene permiso para crear solicitudes de envío interno.");
        }
    }

    public void assertCanApproveOrReject() throws BusinessException {
        if (!hasPermission(PERM_ACCOUNTING_APPROVE)) {
            throw new BusinessException("Solo Contabilidad puede autorizar o denegar solicitudes de envío interno.");
        }
    }

    public void assertCanViewExistingEnvi() throws BusinessException {
        if (!canViewAccountingWorkspace()) {
            throw new BusinessException("No tiene permiso para consultar el listado de ENVI existentes.");
        }
    }

    public void assertCanListRequests() throws BusinessException {
        if (!canViewAccountingWorkspace() && !hasPermission(PERM_DISTRIBUTION_VIEW)) {
            throw new BusinessException("No tiene permiso para ver solicitudes de envío interno.");
        }
    }

    /** Distribución solo ve solicitudes pendientes de autorización. */
    public String enforceListStatusFilter(String requestedStatus) {
        if (canViewAccountingWorkspace()) {
            return requestedStatus;
        }
        return "PENDIENTE";
    }
}
