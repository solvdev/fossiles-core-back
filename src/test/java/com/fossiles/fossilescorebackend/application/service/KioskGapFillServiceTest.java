package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGapFillResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskGapFillPlanner;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskImportBatchEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteAliasEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskImportBatchRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteAliasRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Corrección de "días sin sistema" contra el Excel real de septiembre 2026 (se omite si el archivo no está) y las
 * cifras de Documentacion/difs.md: Q42,331.30 en 10 kioscos.
 */
class KioskGapFillServiceTest {

    private static final String FILE = "Reporte de ventas Septiembe (1).xlsx";
    private static final Path FOLDER = Paths.get("C:/Users/eduar/Desktop/Work/Fossiles/Documentacion/reportesventas");

    /** Primer día con ventas en el POS de los 10 kioscos con días sin sistema (el resto, desde el día 1). */
    private static final Map<String, Integer> GO_LIVE_DAY = Map.of(
            "REU", 2, "COATEPQUE", 3, "INTERPLAZA XELA", 8, "UTZULEW MALL", 7, "CELAJES QUICHE", 8,
            "CHIQUIMULA", 3, "PLAZA MAGDALENA", 4, "PRADERA ESCUINTLA", 9, "INTERPLAZA ESCUINTLA", 9, "SANTALU", 11);

    private KioskSiteRepository siteRepository;
    private KioskSiteAliasRepository aliasRepository;
    private KioskSalesSourceResolver resolver;
    private KioskDailySalesHistRepository histRepository;
    private KioskImportBatchRepository batchRepository;
    private KioskFinancialsAccessGuard guard;
    private KioskGapFillService service;

    private final Map<String, KioskSiteEntity> siteByAlias = new HashMap<>();
    private final Map<Long, KioskSiteEntity> siteById = new HashMap<>();
    /** Ventas que "tiene el sistema": las del reporte, salvo antes del go-live de los kioscos con días sin sistema. */
    private final Map<Long, TreeMap<LocalDate, BigDecimal>> systemBySite = new HashMap<>();
    private byte[] workbook;

    @BeforeEach
    void setUp() throws Exception {
        Path path = FOLDER.resolve(FILE);
        Assumptions.assumeTrue(Files.exists(path), "Excel real de septiembre no disponible");
        workbook = Files.readAllBytes(path);

        siteRepository = mock(KioskSiteRepository.class);
        aliasRepository = mock(KioskSiteAliasRepository.class);
        resolver = mock(KioskSalesSourceResolver.class);
        histRepository = mock(KioskDailySalesHistRepository.class);
        batchRepository = mock(KioskImportBatchRepository.class);
        guard = mock(KioskFinancialsAccessGuard.class);
        when(guard.currentUserId()).thenReturn(7L);
        service = new KioskGapFillService(new KioskExcelParser(), siteRepository, aliasRepository, resolver,
                histRepository, batchRepository, guard, new ObjectMapper());

        // Un sitio por columna del Excel, con alias = nombre normalizado
        KioskExcelParser.ParseResult parsed = new KioskExcelParser().parse(FILE, workbook);
        long id = 1;
        for (KioskExcelParser.ParsedColumn c : parsed.getColumns()) {
            KioskSiteEntity site = KioskSiteEntity.builder().id(id).name(c.getNormalized()).locationId(1000 + id)
                    .sortOrder((int) id).build();
            siteByAlias.put(c.getNormalized(), site);
            siteById.put(id, site);
            TreeMap<LocalDate, BigDecimal> system = new TreeMap<>();
            int goLive = GO_LIVE_DAY.getOrDefault(c.getNormalized(), 1);
            for (var day : parsed.getData().getDays()) {
                BigDecimal v = day.getValues().get(c.getExcelName());
                if (v != null && v.signum() > 0 && day.getDate().getDayOfMonth() >= goLive) {
                    system.put(day.getDate(), v);
                }
            }
            systemBySite.put(id, system);
            id++;
        }

        when(aliasRepository.findByAliasNormalized(anyString())).thenAnswer(inv -> {
            KioskSiteEntity site = siteByAlias.get(inv.<String>getArgument(0));
            return site == null ? Optional.empty()
                    : Optional.of(KioskSiteAliasEntity.builder().aliasNormalized(inv.getArgument(0)).siteId(site.getId()).build());
        });
        when(siteRepository.findById(any(Long.class))).thenAnswer(inv -> Optional.ofNullable(siteById.get(inv.<Long>getArgument(0))));
        when(resolver.resolve(anyCollection(), any(LocalDate.class), any(LocalDate.class))).thenAnswer(inv -> {
            Map<Long, KioskSalesSourceResolver.SiteSales> out = new HashMap<>();
            for (Object o : inv.<java.util.Collection<KioskSiteEntity>>getArgument(0)) {
                KioskSiteEntity site = (KioskSiteEntity) o;
                int goLive = GO_LIVE_DAY.getOrDefault(site.getName(), 1);
                out.put(site.getId(), new KioskSalesSourceResolver.SiteSales(site.getId(),
                        LocalDate.of(2026, 9, goLive), systemBySite.get(site.getId()), true, false));
            }
            return out;
        });
        when(batchRepository.save(any(KioskImportBatchEntity.class))).thenAnswer(inv -> {
            KioskImportBatchEntity b = inv.getArgument(0);
            b.setId(99L);
            return b;
        });
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", FILE, "application/octet-stream", workbook);
    }

    private KioskGapFillResponse.Site site(KioskGapFillResponse response, String name) {
        return response.getSites().stream().filter(s -> name.equals(s.getName())).findFirst().orElseThrow();
    }

    @Test
    void previewFindsTheTenKiosksAndTheQ42331_30OfDifsMd() throws Exception {
        KioskGapFillResponse r = service.preview(file(), null, null);

        assertThat(r.getYear()).isEqualTo(2026);
        assertThat(r.getMonth()).isEqualTo(9);
        List<String> withCandidates = r.getSites().stream().filter(s -> !s.getCandidates().isEmpty())
                .map(KioskGapFillResponse.Site::getName).toList();
        assertThat(withCandidates).containsExactlyInAnyOrder("REU", "COATEPQUE", "INTERPLAZA XELA", "UTZULEW MALL",
                "CELAJES QUICHE", "CHIQUIMULA", "PLAZA MAGDALENA", "PRADERA ESCUINTLA", "INTERPLAZA ESCUINTLA", "SANTALU");
        assertThat(site(r, "REU").getCandidateTotal()).isEqualByComparingTo("1356.50");
        assertThat(site(r, "COATEPQUE").getCandidateTotal()).isEqualByComparingTo("621.00");
        assertThat(site(r, "INTERPLAZA XELA").getCandidateTotal()).isEqualByComparingTo("6320.00");
        assertThat(site(r, "UTZULEW MALL").getCandidateTotal()).isEqualByComparingTo("5502.00");
        assertThat(site(r, "CELAJES QUICHE").getCandidateTotal()).isEqualByComparingTo("3915.40");
        assertThat(site(r, "CHIQUIMULA").getCandidateTotal()).isEqualByComparingTo("891.60");
        assertThat(site(r, "PLAZA MAGDALENA").getCandidateTotal()).isEqualByComparingTo("2007.30");
        assertThat(site(r, "PRADERA ESCUINTLA").getCandidateTotal()).isEqualByComparingTo("7434.80");
        assertThat(site(r, "INTERPLAZA ESCUINTLA").getCandidateTotal()).isEqualByComparingTo("6298.20");
        assertThat(site(r, "SANTALU").getCandidateTotal()).isEqualByComparingTo("7984.50");
        assertThat(r.getTotals().getCandidateAmount()).isEqualByComparingTo("42331.30");
        assertThat(r.getTotals().getCandidateDays()).isEqualTo(47);
        assertThat(r.getTotals().getSitesWithCandidates()).isEqualTo(10);

        // los días en 0 dentro del bloque (p. ej. 2 y 4 de Santalú) no son candidatos
        assertThat(site(r, "SANTALU").getCandidates().stream().map(d -> d.getDate().getDayOfMonth()).toList())
                .containsExactly(1, 3, 5, 6, 7, 8, 9, 10);
        assertThat(site(r, "CELAJES QUICHE").getCandidates().stream().map(d -> d.getDate().getDayOfMonth()).toList())
                .containsExactly(1, 3, 4, 6);
        assertThat(site(r, "SANTALU").getGoLive()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    void commitWritesOnlyTheSelectedKiosksIntoHistWithARevertibleBatch() throws Exception {
        KioskSiteEntity santalu = siteByAlias.get("SANTALU");
        KioskSiteEntity reu = siteByAlias.get("REU");

        KioskGapFillResponse.Applied applied = service.commit(file(), null, null, Set.of(santalu.getId(), reu.getId()));

        assertThat(applied.getBatchId()).isEqualTo(99L);
        assertThat(applied.getSalesRows()).isEqualTo(9); // 8 de Santalú + 1 de Retalhuleu
        assertThat(applied.getAmount()).isEqualByComparingTo("9341.00");

        ArgumentCaptor<KioskImportBatchEntity> batch = ArgumentCaptor.forClass(KioskImportBatchEntity.class);
        Mockito.verify(batchRepository).save(batch.capture());
        assertThat(batch.getValue().getFileName()).startsWith(KioskGapFillService.BATCH_PREFIX);
        assertThat(batch.getValue().getFileSha256()).hasSize(64);
        assertThat(batch.getValue().getPeriodMonth()).isEqualTo(9);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KioskDailySalesHistEntity>> rows = ArgumentCaptor.forClass(List.class);
        Mockito.verify(histRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(9);
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.getImportBatchId()).isEqualTo(99L);
            assertThat(row.getSiteId()).isIn(santalu.getId(), reu.getId());
            // sólo días anteriores al arranque del kiosco en el POS
            LocalDate goLive = LocalDate.of(2026, 9, row.getSiteId().equals(santalu.getId()) ? 11 : 2);
            assertThat(row.getSaleDate()).isBefore(goLive);
            assertThat(row.getAmount().scale()).isEqualTo(2);
        });
    }

    @Test
    void commitRejectsAKioskWithoutDaysToCorrectAndAnEmptySelection() {
        KioskSiteEntity miraflores = siteByAlias.get("MIRAFLORES");
        assertThatThrownBy(() -> service.commit(file(), null, null, Set.of(miraflores.getId())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no tiene días sin sistema");
        assertThatThrownBy(() -> service.commit(file(), null, null, Set.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("al menos un kiosco");
        Mockito.verifyNoInteractions(histRepository);
    }

    @Test
    void externalSitesAreIgnoredEntirely() throws Exception {
        siteByAlias.get("SANTALU").setExcludeFromReports(true);

        KioskGapFillResponse r = service.preview(file(), null, null);

        assertThat(r.getSites().stream().map(KioskGapFillResponse.Site::getName)).doesNotContain("SANTALU");
        assertThat(r.getIgnoredColumns()).anySatisfy(c -> {
            assertThat(c.getExcelName()).startsWith("santal");
            assertThat(c.getReason()).contains("externo");
        });
        assertThat(r.getTotals().getCandidateAmount()).isEqualByComparingTo("34346.80"); // 42,331.30 - 7,984.50
    }

    @Test
    void plannerNeverOverwritesADayThatHasSalesInTheSystem() {
        var plan = KioskGapFillPlanner.plan(new KioskGapFillPlanner.SiteInput(1L, "X", "x", LocalDate.of(2026, 9, 5),
                new TreeMap<>(Map.of(
                        LocalDate.of(2026, 9, 1), new BigDecimal("100"),     // antes del go-live, sistema 0 -> candidato
                        LocalDate.of(2026, 9, 2), new BigDecimal("200"),     // antes del go-live, el sistema ya tiene 150 -> diferencia
                        LocalDate.of(2026, 9, 3), BigDecimal.ZERO,           // el reporte no tiene venta -> nada
                        LocalDate.of(2026, 9, 7), new BigDecimal("300"))),   // desde el go-live y sistema 0 -> hueco no cubrible
                new TreeMap<>(Map.of(LocalDate.of(2026, 9, 2), new BigDecimal("150")))));

        assertThat(plan.candidates()).extracting(KioskGapFillPlanner.Amount::date).containsExactly(LocalDate.of(2026, 9, 1));
        assertThat(plan.candidateTotal()).isEqualByComparingTo("100");
        assertThat(plan.differences()).extracting(KioskGapFillPlanner.Difference::date).containsExactly(LocalDate.of(2026, 9, 2));
        assertThat(plan.afterGoLiveGaps()).extracting(KioskGapFillPlanner.Amount::date).containsExactly(LocalDate.of(2026, 9, 7));
    }
}
