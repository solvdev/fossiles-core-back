package com.fossiles.fossilescorebackend.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KioscoSaleExitReclassifierTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private Query countQuery;
    @Mock
    private Query updateQuery;
    @Mock
    private Query configQuery;

    @InjectMocks
    private KioscoSaleExitReclassifier reclassifier;

    @BeforeEach
    void setUp() {
        when(entityManager.createNativeQuery(startsWith("SELECT COUNT(*)"))).thenReturn(countQuery);
        when(entityManager.createNativeQuery(startsWith("UPDATE kiosco_movement"))).thenReturn(updateQuery);
        when(entityManager.createNativeQuery(startsWith("SELECT set_config"))).thenReturn(configQuery);
        for (Query q : new Query[] {countQuery, updateQuery, configQuery}) {
            when(q.setParameter(anyString(), any())).thenReturn(q);
        }
    }

    @Test
    void sinSalidasPendientes_noTocaLaProteccionAppendOnly() {
        when(countQuery.getSingleResult()).thenReturn(0L);

        assertThat(reclassifier.restoreAsSale(42L, 900L)).isZero();

        verify(entityManager, never()).createNativeQuery(startsWith("UPDATE"));
        verify(entityManager, never()).createNativeQuery(startsWith("SELECT set_config"));
    }

    @Test
    void ventaSinFacturaQueSeFactura_vuelveAVenta_yRestablecePermisosAlTerminar() {
        when(countQuery.getSingleResult()).thenReturn(2L);
        when(updateQuery.executeUpdate()).thenReturn(2);

        assertThat(reclassifier.restoreAsSale(42L, 900L)).isEqualTo(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues()).anyMatch(s -> s.startsWith("UPDATE kiosco_movement SET movement_type = 'VENTA'")
                && s.contains("movement_type = 'SALIDA'") && s.contains("reason LIKE :reasonPrefix"));
        verify(updateQuery).setParameter("saleId", 900L);
        verify(updateQuery).setParameter("locationId", 42L);
        verify(updateQuery).setParameter("reasonPrefix", "Venta sin factura%");
        // set_config 'true' antes y 'false' despues.
        verify(configQuery).setParameter("value", "true");
        verify(configQuery).setParameter("value", "false");
    }

    @Test
    void siFallaElUpdate_elFlagAppendOnlyVuelveAFalse() {
        when(countQuery.getSingleResult()).thenReturn(1L);
        when(updateQuery.executeUpdate()).thenThrow(new IllegalStateException("trigger"));

        assertThatThrownBy(() -> reclassifier.restoreAsSale(42L, 900L))
                .isInstanceOf(IllegalStateException.class);

        verify(configQuery).setParameter("value", "false");
    }

    @Test
    void sinKioskoOVenta_noHaceNada() {
        assertThat(reclassifier.restoreAsSale(null, 900L)).isZero();
        assertThat(reclassifier.restoreAsSale(42L, null)).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
    }
}
