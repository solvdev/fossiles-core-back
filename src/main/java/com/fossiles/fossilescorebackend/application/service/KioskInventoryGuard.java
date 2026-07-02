package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Restricciones de inventario en ubicaciones kiosko: solo supervisoras (permisos inventario)
 * o administradores pueden ajustar/transferir/editar stock manualmente.
 * Las ventas POS y la recepcion de distribucion siguen sus flujos propios.
 */
@Component
@RequiredArgsConstructor
public class KioskInventoryGuard {

    private static final Set<String> SUPERVISOR_INVENTORY_PERMISSIONS = Set.of(
            "INVENTARIOS.AJUSTES_PRODUCTOS.CREAR",
            "INVENTARIOS.AJUSTES_PRODUCTOS.EDITAR",
            "INVENTARIOS.TRANSFERENCIAS.CREAR",
            "INVENTARIOS.PRODUCTOS.EDITAR"
    );

    private final LocationRepository locationRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    public void assertSupervisorMayModifyKioskInventory(Long locationId) throws BusinessException {
        if (locationId == null) {
            return;
        }
        LocationEntity location = locationRepository.findById(locationId).orElse(null);
        if (!isKioskLocation(location)) {
            return;
        }
        if (currentUserMaySuperviseKioskInventory()) {
            return;
        }
        throw new BusinessException(
                "El inventario de kiosko solo puede modificarse mediante recepcion de distribucion o ventas POS. "
                        + "Los ajustes y transferencias a kiosko requieren permiso de supervisora.");
    }

    public boolean isKioskLocation(LocationEntity location) {
        if (location == null) {
            return false;
        }
        String categoria = normalizeText(location.getCategoria());
        String name = normalizeText(location.getName());
        String code = normalizeText(location.getCode());
        return categoria.contains("KIOS")
                || name.contains("KIOS")
                || code.startsWith("K");
    }

    private String normalizeText(String value) {
        return (value == null ? "" : value.trim())
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }

    private boolean currentUserMaySuperviseKioskInventory() {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getRoles() == null) {
            return false;
        }
        if (KioskAccessHelper.hasAllKiosksAccess(user)) {
            return true;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null || role.getPermissions() == null) {
                continue;
            }
            for (PermissionEntity permission : role.getPermissions()) {
                if (permission != null
                        && permission.getCode() != null
                        && SUPERVISOR_INVENTORY_PERMISSIONS.contains(permission.getCode())) {
                    return true;
                }
            }
        }
        return false;
    }
}
