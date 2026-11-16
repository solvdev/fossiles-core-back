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
 * Las rentas son información de Contabilidad: solo administración y quien tenga el permiso de Contabilidad > Rentas.
 * Los {@code @PreAuthorize} no están activos en este backend, por eso el servicio llama a {@code assertCan*}.
 */
@Component
@RequiredArgsConstructor
public class KioskRentAccessGuard {

    public static final String PERM_VIEW = "CONTABILIDAD.RENTAS.VER";
    public static final String PERM_EDIT = "CONTABILIDAD.RENTAS.EDITAR";

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanView() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_VIEW) || hasPermission(user, PERM_EDIT))) {
            throw new BusinessException("No tiene permiso para ver las rentas de los kioscos.");
        }
    }

    /** Devuelve el id del usuario que edita (para guardar quién lo hizo). */
    public Long assertCanEdit() throws BusinessException {
        UserEntity user = currentUser();
        if (!(isAdmin(user) || hasPermission(user, PERM_EDIT))) {
            throw new BusinessException("No tiene permiso para editar las rentas de los kioscos.");
        }
        return user.getId();
    }

    /** True si puede editar (la pantalla oculta los botones de editar y subir si no). */
    public boolean canEdit() throws BusinessException {
        UserEntity user = currentUser();
        return isAdmin(user) || hasPermission(user, PERM_EDIT);
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
