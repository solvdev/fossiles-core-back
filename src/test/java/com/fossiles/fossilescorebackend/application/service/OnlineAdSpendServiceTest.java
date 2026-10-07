package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineAdSpendBulkRequest.Entry;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse.Day;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse.Totals;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineAdSpendEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineAdSpendRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnlineAdSpendServiceTest {

    private static final long USER_ID = 7L;

    private final LocalDate today = SalesDashboardSupport.today();

    private OnlineAdSpendRepository repository;
    private OnlineSaleRepository onlineSaleRepository;
    private UserRepository userRepository;
    private OnlineAdSpendService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OnlineAdSpendRepository.class);
        onlineSaleRepository = mock(OnlineSaleRepository.class);
        userRepository = mock(UserRepository.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        when(securityUtil.getCurrentUserId()).thenReturn(USER_ID);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findBySpendDate(any())).thenReturn(Optional.empty());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(
                UserEntity.builder().id(USER_ID).username("eramirez").firstName("Eduardo").lastName("Ramirez").build()));

        // Cargador real (con repositorios simulados): el reporte debe usar el mismo filtro de venta válida del dashboard.
        SalesSourceLoader loader = new SalesSourceLoader(mock(KioskSaleRepository.class), onlineSaleRepository,
                mock(ProductionOrderRepository.class), mock(ProductRepository.class), mock(CustomerAccountService.class));
        service = new OnlineAdSpendService(repository, loader, userRepository, securityUtil);
    }

    // ------------------------------------------------------------------ upsert

    @Test
    void upsertCreatesCaptureAndRecordsActingUser() throws Exception {
        LocalDate day = today.minusDays(2);

        OnlineAdSpendEntryResponse response = service.upsert(day, new BigDecimal("1500"), "  Meta + Google  ");

        ArgumentCaptor<OnlineAdSpendEntity> saved = ArgumentCaptor.forClass(OnlineAdSpendEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        OnlineAdSpendEntity entity = saved.getValue();
        assertThat(entity.getSpendDate()).isEqualTo(day);
        assertThat(entity.getAmount()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(entity.getNotes()).isEqualTo("Meta + Google");
        assertThat(entity.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(entity.getUpdatedBy()).isEqualTo(USER_ID);

        assertThat(response.getDate()).isEqualTo(day);
        assertThat(response.getAmount()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(response.getNotes()).isEqualTo("Meta + Google");
        assertThat(response.getUpdatedBy()).isEqualTo("Eduardo Ramirez");
    }

    @Test
    void upsertUpdatesExistingCaptureKeepingCreator() throws Exception {
        LocalDate day = today.minusDays(1);
        OnlineAdSpendEntity existing = OnlineAdSpendEntity.builder().id(5L).spendDate(day)
                .amount(new BigDecimal("100.00")).notes("viejo").createdBy(3L).updatedBy(3L).build();
        when(repository.findBySpendDate(day)).thenReturn(Optional.of(existing));

        service.upsert(day, new BigDecimal("250.50"), "   ");

        assertThat(existing.getAmount()).isEqualTo(new BigDecimal("250.50"));
        assertThat(existing.getNotes()).isNull();
        assertThat(existing.getCreatedBy()).isEqualTo(3L);
        assertThat(existing.getUpdatedBy()).isEqualTo(USER_ID);
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void upsertAcceptsTodayZeroTrailingZerosAndMaxAmount() throws Exception {
        assertThat(service.upsert(today, BigDecimal.ZERO, null).getAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(service.upsert(today, new BigDecimal("10.500"), null).getAmount()).isEqualTo(new BigDecimal("10.50"));
        assertThat(service.upsert(today, new BigDecimal("9999999.99"), "x".repeat(255)).getAmount())
                .isEqualTo(new BigDecimal("9999999.99"));
    }

    @Test
    void upsertRejectsInvalidInputWithoutSaving() {
        LocalDate day = today.minusDays(1);

        assertThatThrownBy(() -> service.upsert(null, BigDecimal.TEN, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fecha");
        assertThatThrownBy(() -> service.upsert(day, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("obligatorio");
        assertThatThrownBy(() -> service.upsert(day, new BigDecimal("-0.01"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("negativo");
        assertThatThrownBy(() -> service.upsert(day, new BigDecimal("10000000.00"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("9,999,999.99");
        assertThatThrownBy(() -> service.upsert(day, new BigDecimal("10.005"), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2 decimales");
        assertThatThrownBy(() -> service.upsert(day, BigDecimal.TEN, "x".repeat(256)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("255");
        assertThatThrownBy(() -> service.upsert(today.plusDays(1), BigDecimal.TEN, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posterior a hoy");

        verify(repository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteRemovesTheDayAndToleratesMissingCapture() throws Exception {
        LocalDate day = today.minusDays(4);

        service.delete(day);

        verify(repository).deleteBySpendDate(day);
        assertThatThrownBy(() -> service.delete(null)).isInstanceOf(BusinessException.class);
    }

    // -------------------------------------------------------------------- list

    @Test
    void listReturnsEntriesWithUserNamesInRepositoryOrder() throws Exception {
        LocalDate d1 = today.minusDays(3);
        LocalDate d2 = today.minusDays(2);
        when(repository.findBySpendDateBetweenOrderBySpendDateAsc(d1, d2)).thenReturn(List.of(
                OnlineAdSpendEntity.builder().spendDate(d1).amount(new BigDecimal("10.00")).updatedBy(USER_ID).build(),
                OnlineAdSpendEntity.builder().spendDate(d2).amount(new BigDecimal("20")).notes("n").build()));

        List<OnlineAdSpendEntryResponse> result = service.list(d1, d2);

        assertThat(result).extracting(OnlineAdSpendEntryResponse::getDate).containsExactly(d1, d2);
        assertThat(result.get(0).getUpdatedBy()).isEqualTo("Eduardo Ramirez");
        assertThat(result.get(1).getUpdatedBy()).isNull();
        assertThat(result.get(1).getAmount()).isEqualTo(new BigDecimal("20.00"));
    }

    @Test
    void listRejectsInvertedRange() {
        assertThatThrownBy(() -> service.list(today, today.minusDays(1))).isInstanceOf(BusinessException.class);
    }

    // -------------------------------------------------------------------- bulk

    private static Entry entry(LocalDate date, String amount, String notes) {
        return new Entry(date, amount != null ? new BigDecimal(amount) : null, notes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkSavesUpdatesAndDeletesInOneBatch() throws Exception {
        LocalDate created = today.minusDays(3);
        LocalDate updated = today.minusDays(2);
        LocalDate removed = today.minusDays(1);
        LocalDate neverCaptured = today;
        OnlineAdSpendEntity existingUpdated = OnlineAdSpendEntity.builder().id(1L).spendDate(updated)
                .amount(new BigDecimal("5.00")).createdBy(3L).updatedBy(3L).build();
        OnlineAdSpendEntity existingRemoved = OnlineAdSpendEntity.builder().id(2L).spendDate(removed)
                .amount(new BigDecimal("9.00")).build();
        when(repository.findBySpendDateIn(anyCollection())).thenReturn(List.of(existingUpdated, existingRemoved));

        OnlineAdSpendBulkResponse response = service.bulk(List.of(
                entry(created, "1500.00", "nota"),
                entry(updated, "20", null),
                entry(removed, null, "se ignora"),
                entry(neverCaptured, null, null)));

        assertThat(response.getSaved()).isEqualTo(2);
        assertThat(response.getDeleted()).isEqualTo(1);

        ArgumentCaptor<List<OnlineAdSpendEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(OnlineAdSpendEntity::getSpendDate).containsExactly(created, updated);
        assertThat(saved.getValue().get(0).getAmount()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(saved.getValue().get(0).getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getValue().get(1)).isSameAs(existingUpdated);
        assertThat(existingUpdated.getAmount()).isEqualTo(new BigDecimal("20.00"));
        assertThat(existingUpdated.getCreatedBy()).isEqualTo(3L);
        assertThat(existingUpdated.getUpdatedBy()).isEqualTo(USER_ID);

        ArgumentCaptor<List<OnlineAdSpendEntity>> deleted = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(existingRemoved);
    }

    @Test
    void bulkIsAllOrNothingWhenAnyEntryIsInvalid() {
        LocalDate d1 = today.minusDays(3);
        LocalDate d2 = today.minusDays(2);

        assertThatThrownBy(() -> service.bulk(List.of(entry(d1, "100", null), entry(d2, "-5", null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("negativo");
        assertThatThrownBy(() -> service.bulk(List.of(entry(d1, "100", null), entry(d2, "1.234", null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2 decimales");
        assertThatThrownBy(() -> service.bulk(List.of(entry(d1, "100", null), entry(d2, "10000000", null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("9,999,999.99");
        assertThatThrownBy(() -> service.bulk(List.of(entry(d1, "100", null), entry(d2, "5", "x".repeat(256)))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("255");
        assertThatThrownBy(() -> service.bulk(List.of(entry(d1, "100", null), entry(null, "5", null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fecha");

        verify(repository, never()).saveAll(any());
        verify(repository, never()).deleteAll(anyCollection());
        verify(repository, never()).findBySpendDateIn(anyCollection());
    }

    @Test
    void bulkRejectsDuplicateDatesAndFutureDates() {
        LocalDate day = today.minusDays(1);

        assertThatThrownBy(() -> service.bulk(List.of(entry(day, "100", null), entry(day, null, null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("repetida");
        assertThatThrownBy(() -> service.bulk(List.of(entry(day, "100", null), entry(today.plusDays(1), "1", null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posterior a hoy");
        // Borrar un día futuro tampoco se permite: la fecha de toda entrada se valida igual.
        assertThatThrownBy(() -> service.bulk(List.of(entry(today.plusDays(1), null, null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posterior a hoy");

        verify(repository, never()).saveAll(any());
    }

    @Test
    void bulkRejectsNullEmptyAndOversizedBatches() throws Exception {
        assertThatThrownBy(() -> service.bulk(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bulk(List.of())).isInstanceOf(BusinessException.class);

        List<Entry> tooMany = new ArrayList<>();
        for (int i = 0; i <= OnlineAdSpendService.MAX_BULK_ENTRIES; i++) {
            tooMany.add(entry(today.minusDays(i), "1", null));
        }
        assertThat(tooMany).hasSize(401);
        assertThatThrownBy(() -> service.bulk(tooMany))
                .isInstanceOf(BusinessException.class).hasMessageContaining("400");
        verify(repository, never()).saveAll(any());

        List<Entry> exactlyMax = tooMany.subList(0, OnlineAdSpendService.MAX_BULK_ENTRIES);
        assertThat(service.bulk(exactlyMax).getSaved()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ report

    private OnlineSaleEntity sale(long id, LocalDate date, String total, String status) {
        return OnlineSaleEntity.builder().id(id).saleNumber("ON-" + id).saleDate(date)
                .totalAmount(new BigDecimal(total)).status(status).build();
    }

    private OnlineAdSpendEntity spend(LocalDate date, String amount, String notes) {
        return OnlineAdSpendEntity.builder().spendDate(date).amount(new BigDecimal(amount)).notes(notes).build();
    }

    private Day dayOf(OnlineAdSpendReportResponse report, LocalDate date) {
        return report.getDays().stream().filter(d -> d.getDate().equals(date)).findFirst().orElseThrow();
    }

    @Test
    void reportComputesWinLossEvenNoSpendAndComparableTotals() throws Exception {
        LocalDate d0 = today.minusDays(5);
        LocalDate d1 = today.minusDays(4);
        LocalDate d2 = today.minusDays(3);
        LocalDate d3 = today.minusDays(2);
        LocalDate d4 = today.minusDays(1);
        LocalDate d5 = today;
        when(onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(d0, d5)).thenReturn(List.of(
                sale(1, d0, "100.00", "ENTREGADO"), sale(2, d0, "50.00", "PENDIENTE"),
                sale(3, d1, "40.00", "ENVIADO"),
                sale(4, d2, "200.00", "ENTREGADO"),
                sale(5, d2, "999.00", "CANCELADO"),
                sale(6, d2, "999.00", "Cancelada"),
                sale(7, d2, "999.00", " anulada "),
                sale(8, d4, "80.00", "DEVOLUCION"),
                sale(9, d5, "60.00", "PRODUCIDO")));
        when(repository.findBySpendDateBetweenOrderBySpendDateAsc(d0, d5)).thenReturn(List.of(
                spend(d0, "100.00", "Meta"),
                spend(d1, "40.00", null),
                spend(d2, "250.00", null),
                spend(d3, "30.00", "sin ventas"),
                spend(d5, "0.00", "orgánico")));

        OnlineAdSpendReportResponse report = service.report(d0, d5);

        assertThat(report.getStartDate()).isEqualTo(d0);
        assertThat(report.getEndDate()).isEqualTo(d5);
        assertThat(report.getDays()).extracting(Day::getDate).containsExactly(d0, d1, d2, d3, d4, d5);

        Day win = dayOf(report, d0);
        assertThat(win.getSalesAmount()).isEqualTo(new BigDecimal("150.00"));
        assertThat(win.getOrdersCount()).isEqualTo(2);
        assertThat(win.getAdSpend()).isEqualTo(new BigDecimal("100.00"));
        assertThat(win.getNetResult()).isEqualTo(new BigDecimal("50.00"));
        assertThat(win.getRoas()).isEqualTo(new BigDecimal("1.50"));
        assertThat(win.getStatus()).isEqualTo("WIN");
        assertThat(win.getNotes()).isEqualTo("Meta");

        Day even = dayOf(report, d1);
        assertThat(even.getNetResult()).isEqualTo(new BigDecimal("0.00"));
        assertThat(even.getRoas()).isEqualTo(new BigDecimal("1.00"));
        assertThat(even.getStatus()).isEqualTo("EVEN");

        Day loss = dayOf(report, d2);
        assertThat(loss.getSalesAmount()).isEqualTo(new BigDecimal("200.00"));
        assertThat(loss.getOrdersCount()).isEqualTo(1);
        assertThat(loss.getNetResult()).isEqualTo(new BigDecimal("-50.00"));
        assertThat(loss.getRoas()).isEqualTo(new BigDecimal("0.80"));
        assertThat(loss.getStatus()).isEqualTo("LOSS");

        Day spendNoSales = dayOf(report, d3);
        assertThat(spendNoSales.getSalesAmount()).isEqualTo(new BigDecimal("0.00"));
        assertThat(spendNoSales.getOrdersCount()).isZero();
        assertThat(spendNoSales.getNetResult()).isEqualTo(new BigDecimal("-30.00"));
        assertThat(spendNoSales.getRoas()).isEqualTo(new BigDecimal("0.00"));
        assertThat(spendNoSales.getStatus()).isEqualTo("LOSS");

        Day noSpend = dayOf(report, d4);
        assertThat(noSpend.getSalesAmount()).isEqualTo(new BigDecimal("80.00"));
        assertThat(noSpend.getOrdersCount()).isEqualTo(1);
        assertThat(noSpend.getAdSpend()).isNull();
        assertThat(noSpend.getNetResult()).isNull();
        assertThat(noSpend.getRoas()).isNull();
        assertThat(noSpend.getStatus()).isEqualTo("NO_SPEND");
        assertThat(noSpend.getNotes()).isNull();

        Day zeroSpend = dayOf(report, d5);
        assertThat(zeroSpend.getAdSpend()).isEqualTo(new BigDecimal("0.00"));
        assertThat(zeroSpend.getNetResult()).isEqualTo(new BigDecimal("60.00"));
        assertThat(zeroSpend.getRoas()).isNull();
        assertThat(zeroSpend.getStatus()).isEqualTo("WIN");

        Totals totals = report.getTotals();
        assertThat(totals.getSalesAmount()).isEqualTo(new BigDecimal("530.00"));
        assertThat(totals.getOrdersCount()).isEqualTo(6);
        assertThat(totals.getComparableSales()).isEqualTo(new BigDecimal("450.00"));
        assertThat(totals.getAdSpend()).isEqualTo(new BigDecimal("420.00"));
        assertThat(totals.getNetResult()).isEqualTo(new BigDecimal("30.00"));
        assertThat(totals.getRoas()).isEqualTo(new BigDecimal("1.07"));
        assertThat(totals.getDaysWithSpend()).isEqualTo(5);
        assertThat(totals.getDaysNoSpend()).isEqualTo(1);
        assertThat(totals.getDaysWin()).isEqualTo(2);
        assertThat(totals.getDaysLoss()).isEqualTo(2);
        assertThat(totals.getDaysEven()).isEqualTo(1);
    }

    @Test
    void reportWithoutAnySpendHasNullRoasAndOnlyNoSpendDays() throws Exception {
        LocalDate from = today.minusDays(2);
        when(onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(from, today)).thenReturn(List.of(
                sale(1, from, "70.00", "ENTREGADO"), sale(2, today, "30.00", "ENTREGADO")));

        OnlineAdSpendReportResponse report = service.report(from, today);

        Totals totals = report.getTotals();
        assertThat(totals.getSalesAmount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(totals.getOrdersCount()).isEqualTo(2);
        assertThat(totals.getComparableSales()).isEqualTo(new BigDecimal("0.00"));
        assertThat(totals.getAdSpend()).isEqualTo(new BigDecimal("0.00"));
        assertThat(totals.getNetResult()).isEqualTo(new BigDecimal("0.00"));
        assertThat(totals.getRoas()).isNull();
        assertThat(totals.getDaysWithSpend()).isZero();
        assertThat(totals.getDaysNoSpend()).isEqualTo(3);
        assertThat(totals.getDaysWin() + totals.getDaysLoss() + totals.getDaysEven()).isZero();
        assertThat(report.getDays()).hasSize(3).allMatch(d -> d.getStatus().equals("NO_SPEND"));
    }

    @Test
    void reportIgnoresSalesOutsideTheRangeAndZeroFillsEmptyRange() throws Exception {
        LocalDate from = today.minusDays(1);
        when(onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(from, today)).thenReturn(List.of(
                sale(1, from.minusDays(5), "500.00", "ENTREGADO")));

        OnlineAdSpendReportResponse report = service.report(from, today);

        assertThat(report.getDays()).hasSize(2);
        assertThat(report.getDays()).allSatisfy(d -> {
            assertThat(d.getSalesAmount()).isEqualTo(new BigDecimal("0.00"));
            assertThat(d.getOrdersCount()).isZero();
        });
        assertThat(report.getTotals().getSalesAmount()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void reportDefaultsToMonthStartThroughToday() throws Exception {
        OnlineAdSpendReportResponse report = service.report(null, null);

        assertThat(report.getStartDate()).isEqualTo(today.withDayOfMonth(1));
        assertThat(report.getEndDate()).isEqualTo(today);
        assertThat(report.getDays()).hasSize(today.getDayOfMonth());
    }

    @Test
    void reportAllowsExactlyFourHundredDaysAndRejectsMore() throws Exception {
        LocalDate from400 = today.minusDays(399);
        assertThat(service.report(from400, today).getDays()).hasSize(400);

        assertThatThrownBy(() -> service.report(today.minusDays(400), today))
                .isInstanceOf(BusinessException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> service.report(today, today.minusDays(1)))
                .isInstanceOf(BusinessException.class);
    }
}
