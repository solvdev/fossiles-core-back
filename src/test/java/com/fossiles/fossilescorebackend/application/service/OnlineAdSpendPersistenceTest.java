package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.OnlineAdSpendBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendEntryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineAdSpendReportResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineAdSpendEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineAdSpendRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Hibernate + H2: el mapeo de {@code online_ad_spend}, las consultas derivadas del repositorio y el servicio real
 * (upsert, borrado, guardado masivo y reporte) sobre una base de datos de verdad, con el JSON tal como lo manda el frontend.
 * Sin transacción de test: cada operación del servicio hace commit, como en producción.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:online_ad_spend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OnlineAdSpendPersistenceTest.Cfg.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OnlineAdSpendPersistenceTest {

    // No usar @SpringBootConfiguration: otros @SpringBootTest del paquete la detectarian como su configuracion.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = OnlineAdSpendRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OnlineAdSpendRepository.class))
    @Import(OnlineAdSpendService.class)
    static class Cfg {
        @Bean
        PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(OnlineAdSpendEntity.class.getName());
        }
    }

    @MockitoBean SalesSourceLoader sourceLoader;
    @MockitoBean UserRepository userRepository;
    @MockitoBean SecurityUtil securityUtil;

    @Autowired OnlineAdSpendService service;
    @Autowired OnlineAdSpendRepository repository;

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    private final LocalDate today = SalesDashboardSupport.today();

    @BeforeEach
    void cleanTable() {
        repository.deleteAll();
        Mockito.when(securityUtil.getCurrentUserId()).thenReturn(7L);
    }

    @Test
    void upsertTwiceKeepsASingleRowPerDayAndSetsAuditColumns() throws Exception {
        LocalDate day = today.minusDays(2);

        service.upsert(day, new BigDecimal("100"), "primera");
        OnlineAdSpendEntryResponse second = service.upsert(day, new BigDecimal("125.50"), "segunda");

        List<OnlineAdSpendEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        OnlineAdSpendEntity row = rows.get(0);
        assertThat(row.getSpendDate()).isEqualTo(day);
        assertThat(row.getAmount()).isEqualByComparingTo("125.50");
        assertThat(row.getNotes()).isEqualTo("segunda");
        assertThat(row.getCreatedBy()).isEqualTo(7L);
        assertThat(row.getUpdatedBy()).isEqualTo(7L);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getUpdatedAt()).isNotNull();
        assertThat(second.getAmount()).isEqualByComparingTo("125.50");
        assertThat(second.getUpdatedAt()).isCloseTo(row.getUpdatedAt(), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void uniqueConstraintOnSpendDateIsEnforcedByTheSchema() {
        LocalDate day = today.minusDays(1);
        repository.saveAndFlush(OnlineAdSpendEntity.builder().spendDate(day).amount(BigDecimal.ONE).build());

        assertThatThrownBy(() -> repository.saveAndFlush(
                OnlineAdSpendEntity.builder().spendDate(day).amount(BigDecimal.TEN).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void derivedQueriesFilterByRangeAndOrderByDate() {
        LocalDate d1 = today.minusDays(5);
        LocalDate d2 = today.minusDays(3);
        LocalDate d3 = today.minusDays(1);
        for (LocalDate d : List.of(d3, d1, d2)) {
            repository.save(OnlineAdSpendEntity.builder().spendDate(d).amount(BigDecimal.TEN).build());
        }

        assertThat(repository.findBySpendDateBetweenOrderBySpendDateAsc(d1, d3))
                .extracting(OnlineAdSpendEntity::getSpendDate).containsExactly(d1, d2, d3);
        assertThat(repository.findBySpendDateBetweenOrderBySpendDateAsc(d2, d2))
                .extracting(OnlineAdSpendEntity::getSpendDate).containsExactly(d2);
        assertThat(repository.findBySpendDate(d2)).isPresent();
        assertThat(repository.findBySpendDate(today)).isEmpty();
        assertThat(repository.findBySpendDateIn(List.of(d1, d3, today)))
                .extracting(OnlineAdSpendEntity::getSpendDate).containsExactlyInAnyOrder(d1, d3);
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        LocalDate day = today.minusDays(1);
        service.upsert(day, BigDecimal.TEN, null);

        service.delete(day);
        service.delete(day);

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void bulkFromFrontendJsonSavesUpdatesAndDeletesAtomically() throws Exception {
        LocalDate d1 = today.minusDays(3);
        LocalDate d2 = today.minusDays(2);
        LocalDate d3 = today.minusDays(1);
        service.upsert(d2, new BigDecimal("40"), "viejo");
        service.upsert(d3, new BigDecimal("60"), null);

        String json = "{\"entries\":["
                + "{\"date\":\"" + d1 + "\",\"amount\":1500.00,\"notes\":null},"
                + "{\"date\":\"" + d2 + "\",\"amount\":45.5,\"notes\":\"nuevo\"},"
                + "{\"date\":\"" + d3 + "\",\"amount\":null}]}";
        OnlineAdSpendBulkResponse response = service.bulk(
                mapper.readValue(json, OnlineAdSpendBulkRequest.class).getEntries());

        assertThat(response.getSaved()).isEqualTo(2);
        assertThat(response.getDeleted()).isEqualTo(1);
        List<OnlineAdSpendEntity> rows = repository.findBySpendDateBetweenOrderBySpendDateAsc(d1, d3);
        assertThat(rows).extracting(OnlineAdSpendEntity::getSpendDate).containsExactly(d1, d2);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("1500.00");
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo("45.50");
        assertThat(rows.get(1).getNotes()).isEqualTo("nuevo");
    }

    @Test
    void invalidBulkLeavesTheTableUntouched() throws Exception {
        LocalDate d1 = today.minusDays(3);
        LocalDate d2 = today.minusDays(2);
        service.upsert(d1, new BigDecimal("40"), "intacto");

        assertThatThrownBy(() -> service.bulk(List.of(
                new OnlineAdSpendBulkRequest.Entry(d1, new BigDecimal("999"), null),
                new OnlineAdSpendBulkRequest.Entry(d2, new BigDecimal("-1"), null))))
                .isInstanceOf(BusinessException.class);

        List<OnlineAdSpendEntity> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("40.00");
        assertThat(rows.get(0).getNotes()).isEqualTo("intacto");
    }

    @Test
    void reportJoinsPersistedSpendWithOnlineSales() throws Exception {
        LocalDate d1 = today.minusDays(1);
        service.upsert(d1, new BigDecimal("50"), "Meta");
        Mockito.when(sourceLoader.loadOnlineSales(d1, today)).thenReturn(List.of(
                OnlineSaleEntity.builder().id(1L).saleDate(d1).totalAmount(new BigDecimal("125.00")).status("ENTREGADO").build(),
                OnlineSaleEntity.builder().id(2L).saleDate(today).totalAmount(new BigDecimal("10.00")).status("PENDIENTE").build()));

        OnlineAdSpendReportResponse report = service.report(d1, today);

        assertThat(report.getDays()).hasSize(2);
        assertThat(report.getDays().get(0).getStatus()).isEqualTo("WIN");
        assertThat(report.getDays().get(0).getRoas()).isEqualByComparingTo("2.50");
        assertThat(report.getDays().get(1).getStatus()).isEqualTo("NO_SPEND");
        assertThat(report.getTotals().getSalesAmount()).isEqualByComparingTo("135.00");
        assertThat(report.getTotals().getComparableSales()).isEqualByComparingTo("125.00");
        assertThat(report.getTotals().getNetResult()).isEqualByComparingTo("75.00");
    }
}
