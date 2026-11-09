package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.LfPackingForbiddenException;
import com.fossiles.fossilescorebackend.application.exception.LfPackingUnauthenticatedException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Quién puede ver y editar las cajas de Luis Felipe y despacharlas: administración y Bodega.
 * <p>
 * {@code /api/product-distributions/**} está abierto sin login para el dispositivo de bodega, así que estas
 * rutas validan aquí el usuario del token: sin sesión responden 401 y con otro rol 403.
 */
@Component
@RequiredArgsConstructor
public class LfPackingAccessGuard {

    private final SecurityUtil securityUtil;

    public boolean canUse(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(LfPackingAccessGuard::normalizeRole)
                .anyMatch(r -> r.contains("ADMIN") || r.contains("BODEGA"));
    }

    /** Exige sesión con rol Administración o Bodega; devuelve el usuario. */
    public UserEntity requireUser() {
        Optional<UserEntity> user = securityUtil.getCurrentUser();
        if (user.isEmpty()) {
            throw new LfPackingUnauthenticatedException("Inicia sesión con un usuario de Bodega o Administración para usar las cajas.");
        }
        if (!canUse(user.get())) {
            throw new LfPackingForbiddenException("Solo Bodega y Administración pueden ver o editar las cajas de Luis Felipe.");
        }
        return user.get();
    }

    static String normalizeRole(String value) {
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
