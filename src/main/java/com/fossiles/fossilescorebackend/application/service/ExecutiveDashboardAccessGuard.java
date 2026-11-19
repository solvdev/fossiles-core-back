package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/**
 * El tablero gerencial es solo para administración: pasa un rol cuyo nombre contiene "ADMIN", el acceso total
 * (ADMIN_FULL_ACCESS / SUPER_ADMIN) o el permiso {@value #PERM_VIEW} (para la gerente u otro rol que se decida).
 */
@Component
@RequiredArgsConstructor
public class ExecutiveDashboardAccessGuard {

    public static final String PERM_VIEW = "DASHBOARDS.GERENCIAL.VER";
    private static final Set<String> FULL_ACCESS = Set.of("ADMIN_FULL_ACCESS", "SUPER_ADMIN");

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void assertCanView() {
        if (!canView()) {
            throw new AccessDeniedException("El tablero gerencial es solo para administradores.");
        }
    }

    @Transactional(readOnly = true)
    public boolean canView() {
        Long userId = securityUtil.getCurrentUserId();
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null) {
                continue;
            }
            if (role.getName() != null && role.getName().toUpperCase(Locale.ROOT).contains("ADMIN")) {
                return true;
            }
            if (role.getPermissions() == null) {
                continue;
            }
            for (PermissionEntity permission : role.getPermissions()) {
                String code = permission == null ? null : permission.getCode();
                if (code != null && (FULL_ACCESS.contains(code) || PERM_VIEW.equals(code))) {
                    return true;
                }
            }
        }
        return false;
    }
}
