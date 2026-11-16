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
 * El calendario de conteos es confidencial: las encargadas no deben saber cuándo llega la supervisora.
 * Solo administración y quien tenga el permiso de Contabilidad > Calendario.
 */
@Component
@RequiredArgsConstructor
public class KioskCountCalendarAccessGuard {

    public static final String PERM_VIEW = "CONTABILIDAD.CALENDARIO.VER";
    public static final String PERM_GENERATE = "CONTABILIDAD.CALENDARIO.GENERAR";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanView() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_VIEW) || hasPermission(user, PERM_GENERATE))) {
            throw new BusinessException("No tiene permiso para ver el calendario de conteos de kioscos.");
        }
    }

    /** Devuelve el usuario que genera/modifica (para guardar quién lo hizo). */
    public Long assertCanGenerate() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_GENERATE))) {
            throw new BusinessException("No tiene permiso para generar o modificar el calendario de conteos.");
        }
        return user.getId();
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
            if (role != null && role.getName() != null
                    && role.getName().toUpperCase(Locale.ROOT).contains("ADMIN")) {
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
