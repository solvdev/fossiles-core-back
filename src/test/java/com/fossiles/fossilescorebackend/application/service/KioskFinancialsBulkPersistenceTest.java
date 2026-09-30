package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsConfigResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCostCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskFixedCostEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskImportBatchEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteAliasEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCostCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPeriodConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteAliasRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduce el guardado real de la pantalla "Costos por kiosco": JSON tal como lo envia el frontend ->
 * Jackson -> servicio transaccional -> JPA (H2) -> lectura posterior (config y repositorio).
 * Sin transaccion de test: el commit ocurre de verdad, como en produccion.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kfin_bulk;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = KioskFinancialsBulkPersistenceTest.Cfg.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KioskFinancialsBulkPersistenceTest {

    // No usar @SpringBootConfiguration: otros @SpringBootTest del paquete la detectarian como su configuracion.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = KioskPeriodConfigRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    KioskSiteRepository.class, KioskSiteAliasRepository.class, KioskCostCategoryRepository.class,
                    KioskFixedCostRepository.class, KioskPeriodConfigRepository.class}))
    @Import(KioskFinancialsConfigService.class)
    static class Cfg {
        @Bean
        PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(
                    KioskSiteEntity.class.getName(), KioskSiteAliasEntity.class.getName(),
                    KioskCostCategoryEntity.class.getName(), KioskFixedCostEntity.class.getName(),
                    KioskPeriodConfigEntity.class.getName(), KioskDailySalesHistEntity.class.getName(),
                    KioskImportBatchEntity.class.getName());
        }
    }

    @MockitoBean KioskFinancialsAccessGuard guard;
    @MockitoBean LocationRepository locationRepository;
    @MockitoBean KioskSalesSourceResolver salesSourceResolver;
    @MockitoBean KioskGoalModuleReader goalReader;

    @Autowired KioskFinancialsConfigService service;
    @Autowired KioskSiteRepository siteRepository;
    @Autowired KioskCostCategoryRepository categoryRepository;
    @Autowired KioskPeriodConfigRepository configRepository;
    @Autowired KioskFixedCostRepository fixedCostRepository;

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void guardarMetaDesdeElJsonDelFrontendPersisteYSeLee() throws Exception {
        org.mockito.Mockito.when(goalReader.forYear(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.fossiles.fossilescorebackend.application.util.KioskEffectiveGoals.empty());
        KioskSiteEntity site = siteRepository.save(KioskSiteEntity.builder().name("MIRAFLORES II").build());
        categoryRepository.save(KioskCostCategoryEntity.builder().code("ALQUILER").name("Alquiler").sortOrder(1).build());

        // Exactamente la forma que arma buildChanges() del frontend
        String json = "{\"year\":2026,\"changes\":[{\"siteId\":" + site.getId() + ",\"month\":9,"
                + "\"goal\":130000,\"productCostPct\":0.18,\"costs\":{\"ALQUILER\":14674.89}}]}";
        KioskFinancialsConfigBulkRequest request = mapper.readValue(json, KioskFinancialsConfigBulkRequest.class);

        KioskFinancialsBulkResponse res = service.bulkUpdate(request);
        assertThat(res.getUpdatedCells()).isEqualTo(3);

        List<KioskPeriodConfigEntity> saved = configRepository.findByPeriodYear(2026);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getSalesGoal()).isEqualByComparingTo("130000");
        assertThat(saved.get(0).getProductCostPct()).isEqualByComparingTo("0.18");
        assertThat(saved.get(0).getSource()).isEqualTo("MANUAL");
        assertThat(fixedCostRepository.findByPeriodYear(2026)).hasSize(1);

        KioskFinancialsConfigResponse config = service.getConfig(2026, null, null);
        KioskFinancialsConfigResponse.Month sep = config.getSites().get(0).getMonths().stream()
                .filter(m -> m.getMonth() == 9).findFirst().orElseThrow();
        assertThat(sep.getGoal()).isEqualByComparingTo(new BigDecimal("130000"));
    }
}
