package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskSalesByProductColorReportResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KioskSalesByProductColorReportServiceTest {

    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ColorRepository colorRepository;
    @Mock
    private ProductCategoryRepository productCategoryRepository;
    @Mock
    private KioscoStockRepository kioscoStockRepository;
    @Mock
    private KioskSaleItemRepository kioskSaleItemRepository;
    @Mock
    private KioscoMovementRepository kioscoMovementRepository;

    @InjectMocks
    private KioskSalesByProductColorReportService service;

    private LocationEntity kioskA;
    private LocationEntity kioskB;
    private LocationEntity pilot;
    private ProductEntity wallet;
    private ColorEntity black;
    private ColorEntity brown;

    @BeforeEach
    void setUp() {
        kioskA = LocationEntity.builder().id(1L).code("K1").name("Kiosko Norte").categoria("KIOSKO").posTestMode(false).build();
        kioskB = LocationEntity.builder().id(2L).code("K2").name("Kiosko Sur").categoria("KIOSKO").posTestMode(false).build();
        pilot = LocationEntity.builder().id(9L).code("K9").name("Kiosko piloto").categoria("KIOSKO").posTestMode(true).build();
        wallet = ProductEntity.builder()
                .id(10L)
                .code("BOL-01")
                .name("Billetera")
                .categoryId(3L)
                .audienceCategory("DAMA")
                .build();
        black = ColorEntity.builder().id(21L).name("Negro").build();
        brown = ColorEntity.builder().id(22L).name("Cafe").build();
        lenient().when(colorRepository.findAll()).thenReturn(List.of(black, brown));
        lenient().when(kioscoMovementRepository.aggregateEntriesByProductColor(anyList(), any()))
                .thenReturn(List.of());
    }

    @Test
    void excludesVoidAndTestSalesAndPilotKiosks() throws BusinessException {
        stubAdmin();
        when(locationRepository.findAll()).thenReturn(List.of(kioskA, kioskB, pilot));
        when(kioscoStockRepository.aggregateStockByProductColor(List.of(1L, 2L))).thenReturn(rows(
                new Object[] { 1L, 10L, 21L, 4 },
                new Object[] { 1L, 10L, 22L, 2 },
                new Object[] { 2L, 10L, 21L, 1 }
        ));
        when(kioskSaleItemRepository.aggregateCompletedSalesByProductColor(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), List.of(1L, 2L)
        )).thenReturn(rows(
                new Object[] { 10L, 21L, "Negro", 1L, new BigDecimal("5"), new BigDecimal("1500.00"), 3L }
        ));
        stubCatalog();

        KioskSalesByProductColorReportResponse report = service.getReport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), null, true);

        assertThat(report.getKiosks()).extracting(KioskSalesByProductColorReportResponse.KioskRef::getId)
                .containsExactly(1L, 2L);
        assertThat(report.getProducts()).hasSize(1);
        var product = report.getProducts().get(0);
        assertThat(product.getProductCode()).isEqualTo("BOL-01");
        assertThat(product.getColorsWithSales()).isEqualTo(1);
        assertThat(product.getColorsWithoutSales()).isEqualTo(1);
        assertThat(product.getTotalQuantity()).isEqualByComparingTo("5.000");
        var blackCell = product.getColors().stream()
                .filter(c -> Long.valueOf(21L).equals(c.getColorId()))
                .findFirst()
                .orElseThrow();
        var brownCell = product.getColors().stream()
                .filter(c -> Long.valueOf(22L).equals(c.getColorId()))
                .findFirst()
                .orElseThrow();
        assertThat(blackCell.getQuantity()).isEqualByComparingTo("5.000");
        assertThat(brownCell.getQuantity()).isEqualByComparingTo("0.000");
        assertThat(brownCell.getCurrentStock()).isEqualTo(2);
        assertThat(report.getTotals().getProductsWithoutSales()).isZero();
        assertThat(report.getTotals().getColorCombinationsWithoutSales()).isEqualTo(1);
    }

    @Test
    void keepsStockOnlyColorsEvenWhenZeroSalesHidden() throws BusinessException {
        stubAdmin();
        when(locationRepository.findAll()).thenReturn(List.of(kioskA));
        when(kioscoStockRepository.aggregateStockByProductColor(List.of(1L))).thenReturn(rows(
                new Object[] { 1L, 10L, 21L, 4 },
                new Object[] { 1L, 10L, 22L, 2 }
        ));
        when(kioskSaleItemRepository.aggregateCompletedSalesByProductColor(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), List.of(1L)
        )).thenReturn(rows(
                new Object[] { 10L, 21L, "Negro", 1L, new BigDecimal("2"), new BigDecimal("400.00"), 1L }
        ));
        stubCatalog();

        KioskSalesByProductColorReportResponse report = service.getReport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), 1L, false);

        assertThat(report.getProducts()).hasSize(1);
        assertThat(report.getProducts().get(0).getColors()).hasSize(2);
        var brownCell = report.getProducts().get(0).getColors().stream()
                .filter(c -> Long.valueOf(22L).equals(c.getColorId()))
                .findFirst()
                .orElseThrow();
        assertThat(brownCell.getQuantity()).isEqualByComparingTo("0.000");
        assertThat(brownCell.getCurrentStock()).isEqualTo(2);
        assertThat(report.getKioskLabel()).contains("Kiosko Norte");
    }

    @Test
    void includesEntriesEvenWithoutSales() throws BusinessException {
        stubAdmin();
        when(locationRepository.findAll()).thenReturn(List.of(kioskA));
        when(kioscoStockRepository.aggregateStockByProductColor(List.of(1L))).thenReturn(List.of());
        when(kioskSaleItemRepository.aggregateCompletedSalesByProductColor(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), List.of(1L)
        )).thenReturn(List.of());
        when(kioscoMovementRepository.aggregateEntriesByProductColor(anyList(), any()))
                .thenReturn(rows(
                        new Object[] { 1L, 10L, 21L, 8 }
                ));
        stubCatalog();

        KioskSalesByProductColorReportResponse report = service.getReport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), 1L, false);

        assertThat(report.getProducts()).hasSize(1);
        var blackCell = report.getProducts().get(0).getColors().get(0);
        assertThat(blackCell.getQuantityIn()).isEqualTo(8);
        assertThat(blackCell.getQuantity()).isEqualByComparingTo("0.000");
        assertThat(report.getTotals().getQuantityIn()).isEqualTo(8);
    }

    @Test
    void joinsSalesStockAndEntriesOnCatalogColorIdEvenIfSaleNameDiffers() throws BusinessException {
        stubAdmin();
        when(locationRepository.findAll()).thenReturn(List.of(kioskA));
        when(kioscoStockRepository.aggregateStockByProductColor(List.of(1L))).thenReturn(rows(
                new Object[] { 1L, 10L, 21L, 4 }
        ));
        when(kioskSaleItemRepository.aggregateCompletedSalesByProductColor(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), List.of(1L)
        )).thenReturn(rows(
                new Object[] { 10L, 21L, "Negro", 1L, new BigDecimal("2"), new BigDecimal("400.00"), 1L },
                new Object[] { 10L, 21L, "NEGRO", 1L, new BigDecimal("3"), new BigDecimal("600.00"), 1L }
        ));
        when(kioscoMovementRepository.aggregateEntriesByProductColor(anyList(), any()))
                .thenReturn(rows(new Object[] { 1L, 10L, 21L, 7 }));
        stubCatalog();

        KioskSalesByProductColorReportResponse report = service.getReport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), 1L, true);

        assertThat(report.getProducts()).hasSize(1);
        assertThat(report.getProducts().get(0).getColors()).hasSize(1);
        var cell = report.getProducts().get(0).getColors().get(0);
        assertThat(cell.getColorId()).isEqualTo(21L);
        assertThat(cell.getQuantity()).isEqualByComparingTo("5.000");
        assertThat(cell.getCurrentStock()).isEqualTo(4);
        assertThat(cell.getQuantityIn()).isEqualTo(7);
        assertThat(report.getProducts().get(0).getTotalQuantity()).isEqualByComparingTo("5.000");
        assertThat(report.getProducts().get(0).getCurrentStock()).isEqualTo(4);
        assertThat(report.getTotals().getQuantityIn()).isEqualTo(7);
    }

    @Test
    void resolvesSaleWithoutColorIdUsingCatalogName() throws BusinessException {
        stubAdmin();
        when(locationRepository.findAll()).thenReturn(List.of(kioskA));
        when(kioscoStockRepository.aggregateStockByProductColor(List.of(1L))).thenReturn(rows(
                new Object[] { 1L, 10L, 21L, 4 }
        ));
        when(kioskSaleItemRepository.aggregateCompletedSalesByProductColor(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), List.of(1L)
        )).thenReturn(rows(
                new Object[] { 10L, null, "NEGRO", 1L, new BigDecimal("2"), new BigDecimal("400.00"), 1L }
        ));
        stubCatalog();

        KioskSalesByProductColorReportResponse report = service.getReport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), 1L, true);

        assertThat(report.getProducts().get(0).getColors()).hasSize(1);
        var cell = report.getProducts().get(0).getColors().get(0);
        assertThat(cell.getColorId()).isEqualTo(21L);
        assertThat(cell.getQuantity()).isEqualByComparingTo("2.000");
        assertThat(cell.getCurrentStock()).isEqualTo(4);
    }

    @Test
    void keepsDistinctCatalogColorsSeparateEvenIfNamesLookAlike() throws BusinessException {
        ColorEntity negro2 = ColorEntity.builder().id(99L).name("Negro mate").build();
        lenient().when(colorRepository.findAll()).thenReturn(List.of(black, brown, negro2));
        stubAdmin();
        when(locationRepository.findAll()).thenReturn(List.of(kioskA));
        when(kioscoStockRepository.aggregateStockByProductColor(List.of(1L))).thenReturn(rows(
                new Object[] { 1L, 10L, 21L, 4 },
                new Object[] { 1L, 10L, 99L, 8 }
        ));
        when(kioskSaleItemRepository.aggregateCompletedSalesByProductColor(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), List.of(1L)
        )).thenReturn(rows(
                new Object[] { 10L, 21L, "Negro", 1L, new BigDecimal("2"), new BigDecimal("400.00"), 1L },
                new Object[] { 10L, 99L, "Negro mate", 1L, new BigDecimal("3"), new BigDecimal("600.00"), 1L }
        ));
        stubCatalog();

        KioskSalesByProductColorReportResponse report = service.getReport(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), 1L, true);

        assertThat(report.getProducts().get(0).getColors()).hasSize(2);
        var negro = report.getProducts().get(0).getColors().stream()
                .filter(c -> Long.valueOf(21L).equals(c.getColorId())).findFirst().orElseThrow();
        var mate = report.getProducts().get(0).getColors().stream()
                .filter(c -> Long.valueOf(99L).equals(c.getColorId())).findFirst().orElseThrow();
        assertThat(negro.getQuantity()).isEqualByComparingTo("2.000");
        assertThat(negro.getCurrentStock()).isEqualTo(4);
        assertThat(mate.getQuantity()).isEqualByComparingTo("3.000");
        assertThat(mate.getCurrentStock()).isEqualTo(8);
        assertThat(report.getProducts().get(0).getTotalQuantity()).isEqualByComparingTo("5.000");
        assertThat(report.getProducts().get(0).getCurrentStock()).isEqualTo(12);
    }

    @Test
    void rejectsUsersWithoutReportsAccess() {
        UserEntity user = UserEntity.builder()
                .id(8L)
                .username("caja")
                .roles(Set.of(RoleEntity.builder().id(2L).name("ENCARGADA_KIOSKO").build()))
                .build();
        when(securityUtil.getCurrentUserId()).thenReturn(8L);
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.getReport(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), null, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("administradores");
    }

    private void stubAdmin() {
        UserEntity user = UserEntity.builder()
                .id(1L)
                .username("admin")
                .roles(Set.of(RoleEntity.builder().id(1L).name("ADMIN").build()))
                .build();
        when(securityUtil.getCurrentUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    private void stubCatalog() {
        when(productRepository.findAllById(anyList())).thenReturn(List.of(wallet));
        when(productCategoryRepository.findAllById(anyList())).thenReturn(List.of(
                ProductCategoryEntity.builder().id(3L).code("BOL").name("Billeteras").build()
        ));
    }

    @SafeVarargs
    private static List<Object[]> rows(Object[]... items) {
        return Arrays.asList(items);
    }
}
