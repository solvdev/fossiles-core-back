package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KioscoStockProvisioningServiceTest {

    @Mock
    private KioscoStockRepository kioscoStockRepository;
    @Mock
    private KioscoMovementRepository kioscoMovementRepository;

    @InjectMocks
    private KioscoStockProvisioningService service;

    @Test
    void collapseDuplicates_singleRow_returnsAsIs() {
        KioscoStockEntity only = stock(10L, null, 5, null);

        KioscoStockEntity result = service.collapseDuplicates(List.of(only));

        assertThat(result).isSameAs(only);
        verify(kioscoStockRepository, never()).delete(any());
        verify(kioscoStockRepository, never()).save(any());
    }

    @Test
    void collapseDuplicates_nullColor_mergesSizesAndSetsCurrentFromSizesSum() {
        KioscoStockEntity keeper = stock(1L, null, 3, "{\"28\":2,\"30\":1}");
        KioscoStockEntity dup = stock(2L, null, 4, "{\"28\":1,\"32\":5}");
        when(kioscoStockRepository.save(any(KioscoStockEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        KioscoStockEntity result = service.collapseDuplicates(List.of(keeper, dup));

        verify(kioscoMovementRepository).reassignKioscoStockId(2L, 1L);
        verify(kioscoStockRepository).delete(dup);

        ArgumentCaptor<KioscoStockEntity> saved = ArgumentCaptor.forClass(KioscoStockEntity.class);
        verify(kioscoStockRepository).save(saved.capture());

        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(saved.getValue().getSizesData());
        assertThat(sizes.get("28")).isEqualByComparingTo("3");
        assertThat(sizes.get("30")).isEqualByComparingTo("1");
        assertThat(sizes.get("32")).isEqualByComparingTo("5");
        assertThat(saved.getValue().getCurrentStock()).isEqualTo(9);
        assertThat(saved.getValue().getColorId()).isNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void collapseDuplicates_withoutSizes_sumsCurrentStock() {
        KioscoStockEntity keeper = stock(1L, null, 7, null);
        KioscoStockEntity dup = stock(2L, null, 5, "   ");
        when(kioscoStockRepository.save(any(KioscoStockEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        KioscoStockEntity result = service.collapseDuplicates(List.of(keeper, dup));

        assertThat(result.getCurrentStock()).isEqualTo(12);
        assertThat(result.getSizesData()).isNull();
        verify(kioscoMovementRepository).reassignKioscoStockId(eq(2L), eq(1L));
    }

    @Test
    void collapseDuplicates_takesMaxMinimumStock() {
        KioscoStockEntity keeper = stock(1L, null, 1, null);
        keeper.setMinimumStock(2);
        KioscoStockEntity dup = stock(2L, null, 1, null);
        dup.setMinimumStock(8);
        when(kioscoStockRepository.save(any(KioscoStockEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        KioscoStockEntity result = service.collapseDuplicates(List.of(keeper, dup));

        assertThat(result.getMinimumStock()).isEqualTo(8);
    }

    @Test
    void ensureStockRow_collapsesExistingDuplicates() throws Exception {
        KioscoStockEntity keeper = stock(1L, null, 2, "{\"34\":2}");
        KioscoStockEntity dup = stock(2L, null, 3, "{\"34\":1,\"36\":2}");
        when(kioscoStockRepository.findAllForUpdateByHardware(10L, 20L, null, "NUEVO"))
                .thenReturn(List.of(keeper, dup));
        when(kioscoStockRepository.save(any(KioscoStockEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        KioscoStockEntity result = service.ensureStockRow(10L, 20L, null, 99L, "NUEVO");

        assertThat(result.getCurrentStock()).isEqualTo(5);
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(result.getSizesData());
        assertThat(sizes.get("34")).isEqualByComparingTo("3");
        assertThat(sizes.get("36")).isEqualByComparingTo("2");
        verify(kioscoStockRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    private static KioscoStockEntity stock(Long id, Long colorId, int current, String sizesData) {
        return KioscoStockEntity.builder()
                .id(id)
                .locationId(10L)
                .productId(20L)
                .colorId(colorId)
                .currentStock(current)
                .minimumStock(0)
                .sizesData(sizesData)
                .hardwareCondition("NUEVO")
                .build();
    }
}
