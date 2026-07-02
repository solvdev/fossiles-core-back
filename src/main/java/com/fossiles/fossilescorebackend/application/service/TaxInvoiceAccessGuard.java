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
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TaxInvoiceAccessGuard {

    public static final String PERM_EDIT_FEL = "CONTABILIDAD.FACTURAS.EDITAR";
    public static final String PERM_CERTIFY_FEL = "CONTABILIDAD.FACTURAS.CERTIFICAR";

    private static final Set<String> LOGISTICS_ROLE_TOKENS = Set.of("LOGIST", "LOGISTICA", "LOGISTICO");

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    public void assertCanEditFelMetadata() throws BusinessException {
        if (canEditFelMetadata()) {
            return;
        }
        throw new BusinessException(
                "No tiene permiso para corregir datos FEL. Solo administradores, logística y contabilidad.");
    }

    public boolean canEditFelMetadata() {
        UserEntity user = currentUser();
        if (user == null) {
            return false;
        }
        if (isAdminUser(user)) {
            return true;
        }
        if (isLogisticsUser(user)) {
            return true;
        }
        return hasPermission(user, PERM_EDIT_FEL) || hasPermission(user, PERM_CERTIFY_FEL);
    }

    private UserEntity currentUser() {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private boolean isAdminUser(UserEntity user) {
        return hasRoleToken(user, "ADMIN");
    }

    private boolean isLogisticsUser(UserEntity user) {
        if (user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null) {
                continue;
            }
            String normalized = normalizeRole(role.getName());
            if (LOGISTICS_ROLE_TOKENS.stream().anyMatch(normalized::contains)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRoleToken(UserEntity user, String token) {
        if (user.getRoles() == null || token == null || token.isBlank()) {
            return false;
        }
        String expected = normalizeRole(token);
        for (RoleEntity role : user.getRoles()) {
            if (role == null) {
                continue;
            }
            if (normalizeRole(role.getName()).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermission(UserEntity user, String permissionCode) {
        if (user.getRoles() == null || permissionCode == null) {
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
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }
}
