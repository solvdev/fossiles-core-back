package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Autorización de cambios: solo admin / logística (acceso global a kioskos).
 * Las encargadas de un solo kiosko no pueden ver ni aprobar, aunque tengan
 * el permiso {@code KIOSCOS.CAMBIOS.AUTORIZAR.*} asignado por error.
 */
@Component
@RequiredArgsConstructor
public class KioskExchangeAuthorizationGuard {

    public static final String PERM_VIEW = "KIOSCOS.CAMBIOS.AUTORIZAR.VER";
    public static final String PERM_APPROVE = "KIOSCOS.CAMBIOS.AUTORIZAR.APROBAR";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanViewPendingAuthorizations() throws BusinessException {
        if (!currentUserHasGlobalKioskAccess()) {
            throw new BusinessException("Solo administración o logística pueden ver autorizaciones de cambios.");
        }
    }

    public void assertCanApproveOrReject() throws BusinessException {
        if (!currentUserHasGlobalKioskAccess()) {
            throw new BusinessException("Solo administración o logística pueden autorizar o rechazar cambios.");
        }
    }

    private boolean currentUserHasGlobalKioskAccess() {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        UserEntity user = userRepository.findById(userId).orElse(null);
        return KioskAccessHelper.hasAllKiosksAccess(user);
    }
}
