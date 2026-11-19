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

    @Test
    void hasKioskSalesByCategoryReportAccess_adminLogisticsAccounting() {
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("ADMIN"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("SUPER_ADMIN"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("ADMIN_FULL_ACCESS"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("LOGISTICA"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("LOGISTICO"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("CONTABILIDAD"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(
                userWithRoles("VENTA_EN_LINEA", "CONTABILIDAD"))).isTrue();
    }

    @Test
    void hasKioskSalesByCategoryReportAccess_deniedForOtherRoles() {
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("VENTA_EN_LINEA"))).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("SUPERVISORA_KIOSKO"))).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("ENCARGADA_KIOSKO"))).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("USUARIO"))).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("VENDEDOR"))).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles())).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(
                UserEntity.builder().roles(null).build())).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(null)).isFalse();
    }

    @Test
    void hasKioskReportsAccess_stillAllowsVentaEnLineaAndSupervisora() {
        assertThat(KioskAccessHelper.hasKioskReportsAccess(userWithRoles("VENTA_EN_LINEA"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskReportsAccess(userWithRoles("SUPERVISORA_KIOSKO"))).isTrue();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("VENTA_EN_LINEA"))).isFalse();
        assertThat(KioskAccessHelper.hasKioskSalesByCategoryReportAccess(userWithRoles("SUPERVISORA_KIOSKO"))).isFalse();
    }

    private static UserEntity userWithRoles(String... roleNames) {
        Set<RoleEntity> roles = java.util.Arrays.stream(roleNames)
                .map(name -> RoleEntity.builder().name(name).build())
                .collect(java.util.stream.Collectors.toSet());
        return UserEntity.builder().roles(roles).build();
    }
}
