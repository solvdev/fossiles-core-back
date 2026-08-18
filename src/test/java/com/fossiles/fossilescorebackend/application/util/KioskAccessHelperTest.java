package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KioskAccessHelperTest {

    @Test
    void hasKioskReportsAccess_contabilidadRole() {
        UserEntity user = userWithRoles("CONTABILIDAD");
        assertThat(KioskAccessHelper.hasAllKiosksAccess(user)).isFalse();
        assertThat(KioskAccessHelper.hasKioskReportsAccess(user)).isTrue();
    }

    @Test
    void hasKioskReportsAccess_logisticaRole() {
        UserEntity user = userWithRoles("LOGISTICA");
        assertThat(KioskAccessHelper.hasKioskReportsAccess(user)).isTrue();
        assertThat(KioskAccessHelper.hasAllKiosksAccess(user)).isTrue();
    }

    @Test
    void hasKioskReportsAccess_encargadaKioskoDenied() {
        UserEntity user = userWithRoles("ENCARGADA_KIOSKO");
        assertThat(KioskAccessHelper.hasKioskReportsAccess(user)).isFalse();
        assertThat(KioskAccessHelper.hasAllKiosksAccess(user)).isFalse();
    }

    @Test
    void hasAllKiosksAccess_ventaEnLineaRole() {
        assertThat(KioskAccessHelper.hasAllKiosksAccess(userWithRoles("VENTA_EN_LINEA"))).isTrue();
        assertThat(KioskAccessHelper.hasAllKiosksAccess(userWithRoles("Venta en línea"))).isTrue();
        assertThat(KioskAccessHelper.hasAllKiosksAccess(userWithRoles("SALES_ONLINE"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskReportsAccess(userWithRoles("VENTA_EN_LINEA"))).isTrue();
    }

    @Test
    void hasAllKiosksAccess_genericVentasRoleDenied() {
        assertThat(KioskAccessHelper.hasAllKiosksAccess(userWithRoles("VENTAS"))).isFalse();
        assertThat(KioskAccessHelper.hasAllKiosksAccess(userWithRoles("VENDEDOR"))).isFalse();
    }

    private static UserEntity userWithRoles(String... roleNames) {
        Set<RoleEntity> roles = java.util.Arrays.stream(roleNames)
                .map(name -> RoleEntity.builder().name(name).build())
                .collect(java.util.stream.Collectors.toSet());
        return UserEntity.builder().roles(roles).build();
    }
}
