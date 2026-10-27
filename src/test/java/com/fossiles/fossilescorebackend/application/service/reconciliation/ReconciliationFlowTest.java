package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flujo completo contra Hibernate + H2: generar casos, importar fondos, asociar por identificador exacto, ajustar,
 * crear y cerrar un lote, descargar el Excel, anular el lote y cerrar las comisiones. Verifica las reglas de negocio
 * confirmadas: cobrado = recibido exacto, el ajuste no oculta la diferencia, cerrar dos veces no duplica y el libro
 * deja cada caso en saldo cero. Sin transacción de test: cada operación hace commit, como en producción.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recon_flow;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ReconciliationFlowTest.Cfg.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReconciliationFlowTest {

    // No usar @SpringBootConfiguration: otros @SpringBootTest del paquete la detectarían como su configuración.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = OnlineSaleRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    ReconCaseRepository.class, ReconSellerRepository.class, ReconImportFileRepository.class, ReconBatchRepository.class,
                    ReconReceiptRepository.class, ReconReceiptApplicationRepository.class, ReconCheckResultRepository.class,
                    ReconDifferenceRepository.class, ReconCommissionLineRepository.class, ReconExportRepository.class,
                    ReconExportLineRepository.class, ReconLedgerEntryRepository.class, ReconEventRepository.class,
                    OnlineSaleRepository.class, OnlineSaleItemRepository.class}))
    @Import({ReconActor.class, ReconciliationCaseService.class, ReconciliationRecomputeService.class, ReconciliationRuleEngine.class, ReconEventService.class,
            ReconciliationLedgerService.class, ReconciliationReceiptService.class, ReconciliationDifferenceService.class,
            ReconciliationBatchService.class, ReconciliationCommissionService.class, ReconciliationQueryService.class,
            ReconciliationDashboardService.class, ReconciliationSellerService.class, ReconciliationGuideImportService.class,
            ReconciliationSettlementService.class})
    static class Cfg {
        @Bean
        PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(
                    ReconSellerEntity.class.getName(), ReconImportFileEntity.class.getName(), ReconBatchEntity.class.getName(),
                    ReconCaseEntity.class.getName(), ReconReceiptEntity.class.getName(), ReconReceiptApplicationEntity.class.getName(),
                    ReconCheckResultEntity.class.getName(), ReconDifferenceEntity.class.getName(), ReconCommissionLineEntity.class.getName(),
                    ReconExportEntity.class.getName(), ReconExportLineEntity.class.getName(), ReconLedgerEntryEntity.class.getName(),
                    ReconEventEntity.class.getName(), OnlineSaleEntity.class.getName(), OnlineSaleItemEntity.class.getName());
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ReconciliationProperties reconciliationProperties() {
            return new ReconciliationProperties();
        }
    }

    @MockitoBean SecurityUtil securityUtil;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TaxInvoiceRepository taxInvoiceRepository;

    @Autowired ReconciliationCaseService caseService;
    @Autowired ReconciliationReceiptService receiptService;
    @Autowired ReconciliationDifferenceService differenceService;
    @Autowired ReconciliationBatchService batchService;
    @Autowired ReconciliationCommissionService commissionService;
    @Autowired ReconciliationQueryService queryService;
    @Autowired ReconciliationDashboardService dashboardService;
    @Autowired ReconciliationGuideImportService guideImportService;
    @Autowired ReconciliationLedgerService ledgerService;
    @Autowired ReconciliationSettlementService settlementService;

    @Autowired OnlineSaleRepository saleRepository;
    @Autowired OnlineSaleItemRepository itemRepository;
    @Autowired ReconCaseRepository caseRepository;
    @Autowired ReconSellerRepository sellerRepository;
    @Autowired ReconLedgerEntryRepository ledgerRepository;
    @Autowired ReconReceiptRepository receiptRepository;
    @Autowired ReconBatchRepository batchRepository;
    @Autowired ReconEventRepository eventRepository;
    @Autowired ReconCommissionLineRepository commissionRepository;
    @Autowired ReconExportRepository exportRepository;
    @Autowired ReconExportLineRepository exportLineRepository;
    @Autowired ReconImportFileRepository importFileRepository;
    @Autowired ReconReceiptApplicationRepository applicationRepository;
    @Autowired ReconCheckResultRepository checkResultRepository;
    @Autowired ReconDifferenceRepository differenceRepository;

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 14);

    private Long s1;   // contra entrega Forza, Q480, guía 58123456789
    private Long s2;   // contra entrega Forza, Q105, guía 58123456790
    private Long s3;   // depósito Guatex, Q265, guía 7712345678, referencia DEP-123456
    private Long s4;   // devolución
    private Long s5;   // contra entrega Guatex sin guía, Q280

    @BeforeEach
    void setUp() {
        Mockito.when(securityUtil.getCurrentUserId()).thenReturn(7L);
        ledgerRepository.deleteAll();
        eventRepository.deleteAll();
        commissionRepository.deleteAll();
        exportLineRepository.deleteAll();
        exportRepository.deleteAll();
        applicationRepository.deleteAll();
        checkResultRepository.deleteAll();
        differenceRepository.deleteAll();
        importFileRepository.deleteAll();
        caseRepository.deleteAll();
        batchRepository.deleteAll();
        receiptRepository.deleteAll();
        itemRepository.deleteAll();
        saleRepository.deleteAll();
        sellerRepository.deleteAll();
        for (String n : List.of("Anthony Ixcajo", "Eduardo Ramirez", "Luisa Marquez")) {
            sellerRepository.save(ReconSellerEntity.builder().name(n).build());
        }
        s1 = sale("1", "COD Forza", "CONTRA_ENTREGA", "FORZA_DELIVERY", "450.00", "30.00", "ENVL-01601", "58123456789", null, "ENVIADO",
                LocalDate.of(2026, 10, 5), "Anthony Ixcajo");
        s2 = sale("2", "COD Forza 2", "CONTRA_ENTREGA", "FORZA_DELIVERY", "75.00", "30.00", "ENVL-01602", "58123456790", null, "ENVIADO",
                LocalDate.of(2026, 10, 5), "Anthony Ixcajo");
        s3 = sale("3", "Deposito", "DEPOSITO_LISTO", "GUATEX", "250.00", "15.00", "ENVL-01603", "7712345678", "DEP-123456", "ENVIADO",
                LocalDate.of(2026, 10, 6), "Luisa Marquez");
        s4 = sale("4", "Devuelta", "CONTRA_ENTREGA", "FORZA_DELIVERY", "100.00", "30.00", "ENVL-01604", "58123450000", null, "DEVOLUCION",
                LocalDate.of(2026, 10, 6), "Anthony Ixcajo");
        s5 = sale("5", "Sin guia", "CONTRA_ENTREGA", "GUATEX", "250.00", "30.00", "ENVL-01605", null, null, "ENVIADO",
                LocalDate.of(2026, 10, 7), "Eduardo Ramirez");
    }

    private Long sale(String number, String customer, String method, String carrier, String net, String shipping, String envl, String guide,
                      String auth, String status, LocalDate date, String seller) {
        OnlineSaleEntity s = OnlineSaleEntity.builder().saleNumber(number).customerName(customer).paymentMethod(method).shippingCarrier(carrier)
                .netAmount(new BigDecimal(net)).shippingCost(new BigDecimal(shipping)).totalAmount(new BigDecimal(net).add(new BigDecimal(shipping)))
                .shipmentNumber(envl).guideNumber(guide).paymentAuthorization(auth).status(status).saleDate(date).salesperson(seller)
                .dispatchedAt(LocalDateTime.of(date.getYear(), date.getMonth(), date.getDayOfMonth(), 10, 0)).build();
        s.setSkipAmountCalculation(true);
        s = saleRepository.save(s);
        itemRepository.save(OnlineSaleItemEntity.builder().onlineSaleId(s.getId()).quantity(1).unitPrice(new BigDecimal(net)).build());
        return s.getId();
    }

    private ReconCaseEntity caseOf(Long saleId) {
        return caseRepository.findByOnlineSaleId(saleId).orElseThrow();
    }

    private byte[] csv(String... lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    /** Casos generados y fondos de transportista importados: S1 exacto, S2 con Q5 de menos. */
    private void generateAndImportCarrierFile() throws Exception {
        caseService.generate(FROM, TO);
        ImportResult r = receiptService.importFile(csv("Guia,Monto,Referencia,Fecha,Transportista",
                "58123456789,480.00,LIQ-FZ-312,2026-10-12,Forza Delivery",
                "58123456790,100.00,LIQ-FZ-312,2026-10-12,Forza Delivery"), "forza.csv", Recon.SourceType.CARRIER);
        assertThat(r.autoApplied()).isEqualTo(2);
    }

    private void importBankFile() throws Exception {
        ImportResult r = receiptService.importFile(csv("Referencia,Monto,Fecha", "DEP-123456,265.00,2026-10-08"), "banco.csv", Recon.SourceType.BANK);
        assertThat(r.autoApplied()).isEqualTo(1);
    }

    @Test
    void generarCasos_creaUnoPorVenta_cargaElLibro_yExcluyeLaDevolucion() throws Exception {
        GenerateResult r = caseService.generate(FROM, TO);

        assertThat(r.created()).isEqualTo(5);
        assertThat(r.excluded()).isEqualTo(1);
        assertThat(caseOf(s4).getStatus()).isEqualTo(Recon.Status.EXCLUIDO);
        assertThat(caseOf(s1).getStatus()).isEqualTo(Recon.Status.INFORMACION_INCOMPLETA);
        assertThat(ledgerRepository.findByCaseIdOrderByIdAsc(caseOf(s1).getId())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getEntryType()).isEqualTo(Recon.LedgerType.CARGO);
                    assertThat(e.getAmount()).isEqualByComparingTo("480.00");
                });
        assertThat(ledgerRepository.findByCaseIdOrderByIdAsc(caseOf(s4).getId())).isEmpty();
        // idempotente: repetir no crea casos ni cargos nuevos
        GenerateResult again = caseService.generate(FROM, TO);
        assertThat(again.created()).isZero();
        assertThat(again.alreadyExisted()).isEqualTo(5);
        assertThat(ledgerRepository.count()).isEqualTo(4);
    }

    @Test
    void importarFondos_aplicaSoloPorIdentificadorExacto_yDeteccionDeDiferencia() throws Exception {
        generateAndImportCarrierFile();

        ReconCaseEntity c1 = caseOf(s1);
        ReconCaseEntity c2 = caseOf(s2);
        assertThat(c1.getStatus()).isEqualTo(Recon.Status.CONCILIADO_AUTO);
        assertThat(c1.getPendingBalance()).isEqualByComparingTo("0.00");
        assertThat(c2.getStatus()).isEqualTo(Recon.Status.CON_DIFERENCIAS);
        assertThat(c2.getDifference()).isEqualByComparingTo("-5.00");
        assertThat(c2.getPendingBalance()).isEqualByComparingTo("5.00");
        assertThat(c2.getProblemType()).isEqualTo(Recon.DifferenceType.MONTO_INCORRECTO);

        // el mismo archivo otra vez: no procesa nada
        ImportResult repeated = receiptService.importFile(csv("Guia,Monto,Referencia,Fecha,Transportista",
                "58123456789,480.00,LIQ-FZ-312,2026-10-12,Forza Delivery",
                "58123456790,100.00,LIQ-FZ-312,2026-10-12,Forza Delivery"), "forza.csv", Recon.SourceType.CARRIER);
        assertThat(repeated.duplicateFile()).isTrue();
        assertThat(receiptRepository.count()).isEqualTo(2);
        // la misma fila en otro archivo se reconoce como duplicada
        ImportResult dupRow = receiptService.importFile(csv("Guia,Monto,Referencia,Fecha,Transportista",
                "58123456789,480.00,LIQ-FZ-312,2026-10-12,Forza Delivery", "58123450001,50.00,LIQ-FZ-313,2026-10-12,Forza Delivery"),
                "forza-2.csv", Recon.SourceType.CARRIER);
        assertThat(dupRow.duplicatedRows()).isEqualTo(1);
        assertThat(dupRow.imported()).isEqualTo(1);
        assertThat(dupRow.unmatched()).isEqualTo(1);
    }

    @Test
    void montoYFechaParecidos_nuncaSeAplicanSolos_soloGeneranCandidatos() throws Exception {
        caseService.generate(FROM, TO);

        // Q280 es el cobrado de S5, pero el fondo no trae guía ni referencia ni ENVL
        ReceiptMatch m = receiptService.createManual(new ReceiptRequest(Recon.SourceType.CASH, null, null, null, null, LocalDate.of(2026, 10, 12),
                new BigDecimal("280.00"), null));

        assertThat(m.kind()).isEqualTo("NONE");
        assertThat(m.candidates()).extracting(CandidateDto::shipmentNumber).containsExactly("ENVL-01605");
        assertThat(caseOf(s5).getReceivedAmount()).isEqualByComparingTo("0.00");

        // una persona decide y lo aplica
        receiptService.apply(caseOf(s5).getId(), m.receipt().id(), null);
        assertThat(caseOf(s5).getReceivedAmount()).isEqualByComparingTo("280.00");
        assertThatThrownBy(() -> receiptService.apply(caseOf(s5).getId(), m.receipt().id(), null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void ajuste_resuelveElFaltante_conMotivo_yQuedaVisibleAparte() throws Exception {
        generateAndImportCarrierFile();
        Long id = caseOf(s2).getId();

        assertThatThrownBy(() -> differenceService.propose(id, "corto")).isInstanceOf(BusinessException.class).hasMessageContaining("15 caracteres");
        ReconDifferenceEntity d = differenceService.propose(id, "El transportista descontó Q5 por ruta, aprobado.");
        assertThat(caseOf(s2).getStatus()).isEqualTo(Recon.Status.REQUIERE_AUTORIZACION);

        differenceService.authorize(d.getId());   // quien propone también puede autorizar (decisión del negocio)

        ReconCaseEntity c = caseOf(s2);
        assertThat(c.getStatus()).isEqualTo(Recon.Status.CONCILIADO_CON_AJUSTE);
        assertThat(c.getAuthorizedAdjustment()).isEqualByComparingTo("5.00");
        assertThat(c.getDifference()).isEqualByComparingTo("-5.00");   // la diferencia sigue visible
        assertThat(c.getPendingBalance()).isEqualByComparingTo("0.00");
        CaseDetail detail = queryService.detail(id);
        assertThat(detail.differences()).singleElement().satisfies(x -> {
            assertThat(x.status()).isEqualTo("AUTHORIZED");
            assertThat(x.proposedBy()).isEqualTo(7L);
            assertThat(x.authorizedBy()).isEqualTo(7L);
        });
        assertThat(detail.events()).extracting(EventDto::type).contains("AJUSTE_PROPUESTO", "AJUSTE_AUTORIZADO");
    }

    @Test
    void sobrante_noSePuedeAjustar() throws Exception {
        caseService.generate(FROM, TO);
        ReceiptMatch m = receiptService.createManual(new ReceiptRequest(Recon.SourceType.CARRIER, "Forza", "58123456789", null, null,
                LocalDate.of(2026, 10, 12), new BigDecimal("500.00"), null));
        assertThat(caseOf(s1).getReceivedAmount()).isEqualByComparingTo("500.00");

        assertThatThrownBy(() -> differenceService.propose(caseOf(s1).getId(), "El cliente pagó de más, aceptar el sobrante"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("faltante");
        assertThat(m.receipt().amount()).isEqualByComparingTo("500.00");
        assertThat(caseOf(s1).getStatus()).isEqualTo(Recon.Status.CON_DIFERENCIAS);
    }

    @Test
    void lote_seCierraUnaVez_dejaElSaldoEnCero_yElExcelCoincide() throws Exception {
        generateAndImportCarrierFile();
        importBankFile();
        ReconDifferenceEntity d = differenceService.propose(caseOf(s2).getId(), "El transportista descontó Q5 por ruta, aprobado.");
        differenceService.authorize(d.getId());

        BatchDto batch = batchService.create(new BatchCreateRequest(FROM, TO, null, null));
        assertThat(batch.cases()).isEqualTo(3);
        assertThat(batch.expected()).isEqualByComparingTo("850.00");
        assertThat(batch.received()).isEqualByComparingTo("845.00");
        assertThat(batch.adjusted()).isEqualByComparingTo("5.00");

        BatchCloseResult closed = batchService.close(batch.id(), "cierre-oct-1");
        assertThat(closed.alreadyClosed()).isFalse();
        assertThat(closed.closedCases()).isEqualTo(3);
        assertThat(closed.exportedCases()).isEqualTo(3);

        for (Long saleId : List.of(s1, s2, s3)) {
            assertThat(ledgerService.balance(caseOf(saleId).getId())).as("saldo de la venta " + saleId).isEqualByComparingTo("0.00");
            assertThat(caseOf(saleId).isClosed()).isTrue();
        }
        long ledgerEntries = ledgerRepository.count();

        // cerrar de nuevo devuelve el mismo cierre, sin movimientos nuevos
        BatchCloseResult again = batchService.close(batch.id(), "cierre-oct-1");
        assertThat(again.alreadyClosed()).isTrue();
        assertThat(again.contentSha256()).isEqualTo(closed.contentSha256());
        assertThat(ledgerRepository.count()).isEqualTo(ledgerEntries);

        // un caso cerrado no se modifica
        assertThatThrownBy(() -> caseService.reopen(caseOf(s1).getId(), "quiero revisar")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("lote cerrado");

        // el Excel sale de las mismas filas y la descarga repite la misma huella
        ReconciliationBatchService.ExportFile file = batchService.download(batch.id());
        assertThat(file.sha256()).isEqualTo(closed.contentSha256());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(file.bytes()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("Resumen");
            assertThat(wb.getSheet("Detalle").getLastRowNum()).isEqualTo(3);
            assertThat(wb.getSheet("Ajustes autorizados").getLastRowNum()).isEqualTo(1);
        }
        assertThat(exportRepository.findByBatchIdOrderByIdDesc(batch.id())).extracting(ReconExportEntity::getKind).containsExactly("DESCARGA", "CIERRE");
        // un caso solo puede estar en un lote vigente
        assertThatThrownBy(() -> batchService.create(new BatchCreateRequest(FROM, TO, null, null))).isInstanceOf(BusinessException.class);
    }

    @Test
    void lote_noSeCierraSiUnCasoDejoDeEstarConciliado() throws Exception {
        generateAndImportCarrierFile();
        BatchDto batch = batchService.create(new BatchCreateRequest(FROM, TO, null, null));
        assertThat(batch.cases()).isEqualTo(1);   // solo S1 está conciliado

        // se anula el fondo de S1 después de armar el lote
        Long appId = queryService.detail(caseOf(s1).getId()).applications().get(0).id();
        receiptService.voidApplication(appId, "El fondo se aplicó a la venta equivocada");

        assertThatThrownBy(() -> batchService.close(batch.id(), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("ENVL-01601").hasMessageContaining("INFORMACION_INCOMPLETA");
        assertThat(batchRepository.findById(batch.id()).orElseThrow().getStatus()).isEqualTo(Recon.BatchStatus.OPEN);
        assertThat(ledgerRepository.findByCaseIdOrderByIdAsc(caseOf(s1).getId())).extracting(ReconLedgerEntryEntity::getEntryType)
                .containsExactly(Recon.LedgerType.CARGO);
    }

    @Test
    void anularUnLoteCerrado_reviertaConMovimientosNuevos_ySeVuelveAPoderCerrar() throws Exception {
        generateAndImportCarrierFile();
        BatchDto batch = batchService.create(new BatchCreateRequest(FROM, TO, null, null));
        batchService.close(batch.id(), null);
        Long caseId = caseOf(s1).getId();
        assertThat(ledgerService.balance(caseId)).isEqualByComparingTo("0.00");

        assertThatThrownBy(() -> batchService.voidBatch(batch.id(), "corto")).isInstanceOf(BusinessException.class);
        BatchDto voided = batchService.voidBatch(batch.id(), "Se cerró con un fondo equivocado, se rehace");

        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(caseOf(s1).isClosed()).isFalse();
        assertThat(caseOf(s1).getBatchId()).isNull();
        assertThat(caseOf(s1).getStatus()).isEqualTo(Recon.Status.CONCILIADO_AUTO);
        assertThat(ledgerService.balance(caseId)).isEqualByComparingTo("480.00");   // la deuda vuelve
        assertThat(ledgerRepository.findByCaseIdOrderByIdAsc(caseId)).extracting(ReconLedgerEntryEntity::getEntryType)
                .containsExactly(Recon.LedgerType.CARGO, Recon.LedgerType.DESCARGA, Recon.LedgerType.REVERSA);

        BatchDto second = batchService.create(new BatchCreateRequest(FROM, TO, null, null));
        batchService.close(second.id(), null);
        assertThat(ledgerService.balance(caseId)).isEqualByComparingTo("0.00");
    }

    @Test
    void comisiones_dosPorCiento_sinEnvio_seCierranCuandoElMesEstaRevisado() throws Exception {
        generateAndImportCarrierFile();
        importBankFile();
        ReconDifferenceEntity d = differenceService.propose(caseOf(s2).getId(), "El transportista descontó Q5 por ruta, aprobado.");
        differenceService.authorize(d.getId());

        CommissionSummary open = commissionService.calculate("2026-10");
        // S5 sigue sin conciliar: el mes no se puede cerrar
        assertThat(open.pendingCases()).isEqualTo(1);
        assertThat(open.canClose()).isFalse();
        assertThatThrownBy(() -> commissionService.closeMonth("2026-10")).isInstanceOf(BusinessException.class).hasMessageContaining("Faltan 1 caso");

        // 2% de los netos: Anthony 450 + 75 = 525 → 10.50 (+ devolución anulada); Luisa 250 → 5.00
        assertThat(open.sellers()).extracting(SellerCommission::seller).contains("Anthony Ixcajo", "Luisa Marquez");
        SellerCommission anthony = open.sellers().stream().filter(s -> s.seller().equals("Anthony Ixcajo")).findFirst().orElseThrow();
        assertThat(anthony.commission()).isEqualByComparingTo("10.50");
        assertThat(anthony.annulled()).isEqualTo(1);
        assertThat(open.sellers().stream().filter(s -> s.seller().equals("Luisa Marquez")).findFirst().orElseThrow().commission()).isEqualByComparingTo("5.00");

        caseService.exclude(caseOf(s5).getId(), "Venta duplicada en Ventas Online, se anula");
        CommissionSummary closed = commissionService.closeMonth("2026-10");

        assertThat(closed.closed()).isTrue();
        assertThat(commissionRepository.findByMonth("2026-10")).allSatisfy(l -> assertThat(l.getStatus()).isIn(Recon.CommissionStatus.CONGELADA));
        assertThat(caseOf(s5).getStatus()).isEqualTo(Recon.Status.EXCLUIDO);
        assertThat(ledgerService.balance(caseOf(s5).getId())).isEqualByComparingTo("0.00");   // el cargo se revirtió al excluir
    }

    @Test
    void vendedorFueraDelCatalogo_bloqueaElCierreDeComisiones_hastaAgregarlo() throws Exception {
        sellerRepository.deleteAll(sellerRepository.findAll().stream().filter(s -> s.getName().equals("Luisa Marquez")).toList());
        generateAndImportCarrierFile();
        importBankFile();

        CaseDetail detail = queryService.detail(caseOf(s3).getId());
        assertThat(detail.checks()).filteredOn(c -> c.code().equals("R13")).singleElement().satisfies(c -> {
            assertThat(c.outcome()).isEqualTo("FAIL");
            assertThat(c.message()).contains("catálogo");
        });
        // sin vendedor en el catálogo el caso queda en revisión; si se concilia a mano, el mes sigue sin poder cerrarse
        assertThat(caseOf(s3).getStatus()).isEqualTo(Recon.Status.PENDIENTE_REVISION);
        caseService.reconcile(caseOf(s3).getId(), "Fondo correcto; falta dar de alta a la vendedora");
        caseService.exclude(caseOf(s5).getId(), "Venta duplicada en Ventas Online, se anula");
        CommissionSummary summary = commissionService.calculate("2026-10");
        assertThat(summary.casesWithoutSeller()).isEqualTo(1);
        assertThat(summary.canClose()).isFalse();
    }

    @Test
    void guiasHistoricas_seLlenanSoloSiFaltan_nuncaSePisan_yRevalidanElCaso() throws Exception {
        caseService.generate(FROM, TO);

        byte[] file = csv("ENVL,Guia", "ENVL-01605,7799990001", "ENVL-01601,OTRA-GUIA", "ENVL-09999,123456");
        GuideImportResult dry = guideImportService.importGuides(file, "guias.csv", true);
        assertThat(dry.toSet()).isEqualTo(1);
        assertThat(dry.set()).isZero();
        assertThat(dry.conflicts()).isEqualTo(1);
        assertThat(dry.notFound()).isEqualTo(1);
        assertThat(saleRepository.findById(s5).orElseThrow().getGuideNumber()).isNull();

        GuideImportResult real = guideImportService.importGuides(file, "guias.csv", false);
        assertThat(real.set()).isEqualTo(1);
        assertThat(saleRepository.findById(s5).orElseThrow().getGuideNumber()).isEqualTo("7799990001");
        assertThat(saleRepository.findById(s5).orElseThrow().getTotalAmount()).isEqualByComparingTo("280.00");   // los montos no se tocan
        assertThat(saleRepository.findById(s1).orElseThrow().getGuideNumber()).isEqualTo("58123456789");          // la guía existente no se pisa
        assertThat(caseOf(s5).getGuideNumber()).isEqualTo("7799990001");
    }

    @Test
    void dashboard_reflejaLosCasosReales() throws Exception {
        generateAndImportCarrierFile();

        Dashboard d = dashboardService.dashboard(new CaseFilter(FROM, TO, null, null, null, List.of(), null, false, null, null, null));

        assertThat(d.reconciled()).isEqualTo(1);
        assertThat(d.withDifferences()).isEqualTo(1);
        assertThat(d.excluded()).isEqualTo(1);
        assertThat(d.expected()).isEqualByComparingTo("1130.00");   // 480 + 105 + 265 + 280 (la devolución no cuenta)
        assertThat(d.received()).isEqualByComparingTo("580.00");
        assertThat(d.pendingDifferences()).isEqualByComparingTo("5.00");
        assertThat(d.byProblem()).containsEntry("MONTO_INCORRECTO", 1L);
        assertThat(d.byCarrier()).extracting(Bucket::key).contains("FORZA_DELIVERY", "GUATEX");
    }

    @Test
    void bandeja_filtraYBuscaPorGuiaEnvlYReferencia() throws Exception {
        generateAndImportCarrierFile();
        importBankFile();

        assertThat(queryService.list(new CaseFilter(null, null, null, null, null, List.of(), null, false, "58123456790", null, null), 0, 50, null, null).rows())
                .extracting(CaseRow::shipmentNumber).containsExactly("ENVL-01602");
        assertThat(queryService.list(new CaseFilter(null, null, null, null, null, List.of(), null, false, "envl-01603", null, null), 0, 50, null, null).rows())
                .extracting(CaseRow::shipmentNumber).containsExactly("ENVL-01603");
        assertThat(queryService.list(new CaseFilter(null, null, null, null, null, List.of(), null, false, "DEP-123456", null, null), 0, 50, null, null).rows())
                .extracting(CaseRow::shipmentNumber).containsExactly("ENVL-01603");
        CasePage con = queryService.list(new CaseFilter(null, null, null, null, null, List.of(Recon.Status.CON_DIFERENCIAS), null, false, null, null, null),
                0, 50, "difference", "asc");
        assertThat(con.rows()).extracting(CaseRow::shipmentNumber).containsExactly("ENVL-01602");
        assertThat(con.rows().get(0).difference()).isEqualByComparingTo("-5.00");
        assertThat(con.rows().get(0).reference()).isEqualTo("LIQ-FZ-312");
    }

    @Test
    void conciliarAManual_soloConAdvertencias_yEnBloqueSoloLoSeguro() throws Exception {
        caseService.generate(FROM, TO);
        // S1 ya quedó conciliada sola (guía exacta); S5 no tiene fondos; S4 está excluida: ninguna se puede conciliar en bloque
        receiptService.createManual(new ReceiptRequest(Recon.SourceType.CARRIER, "Forza", "58123456789", "LIQ-1", null, LocalDate.of(2026, 10, 12),
                new BigDecimal("480.00"), null));
        ReconCaseEntity c1 = caseOf(s1);
        assertThat(c1.getStatus()).isEqualTo(Recon.Status.CONCILIADO_AUTO);

        BulkResult bulk = caseService.bulkReconcile(List.of(c1.getId(), caseOf(s5).getId(), caseOf(s4).getId()), "revisado");

        assertThat(bulk.done()).isZero();
        assertThat(bulk.skipped()).hasSize(3);
        assertThatThrownBy(() -> caseService.reconcile(caseOf(s5).getId(), "forzar")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Pendiente de revisión");
    }

    @Test
    void pagoEscrito_porEnvl_aplicaAlCasoYCalculaLaDiferencia() throws Exception {
        caseService.generate(FROM, TO);

        QuickReceiptResult exact = receiptService.recordByIdentifier(new QuickReceiptRequest("envl-01605", null, "LIQ-GX-9",
                LocalDate.of(2026, 10, 12), new BigDecimal("280.00"), null));
        assertThat(exact.difference()).isEqualByComparingTo("0.00");
        assertThat(exact.received()).isEqualByComparingTo("280.00");
        assertThat(caseOf(s5).getPendingBalance()).isEqualByComparingTo("0.00");

        // el faltante queda como diferencia: no se redondea ni se perdona
        QuickReceiptResult short5 = receiptService.recordByIdentifier(new QuickReceiptRequest("ENVL-01602", null, "LIQ-FZ-1",
                LocalDate.of(2026, 10, 12), new BigDecimal("100.00"), null));
        assertThat(short5.difference()).isEqualByComparingTo("-5.00");
        assertThat(short5.status()).isEqualTo(Recon.Status.CON_DIFERENCIAS.name());
        assertThat(caseOf(s2).getPendingBalance()).isEqualByComparingTo("5.00");
        // la fuente se dedujo del cobro contra entrega
        assertThat(receiptRepository.findAll()).extracting(ReconReceiptEntity::getSourceType).containsOnly(Recon.SourceType.CARRIER);
    }

    @Test
    void pagoEscrito_porGuia_y_deduceLaFuenteDelDeposito() throws Exception {
        caseService.generate(FROM, TO);

        QuickReceiptResult byGuide = receiptService.recordByIdentifier(new QuickReceiptRequest("58123456789", null, "LIQ-FZ-2",
                LocalDate.of(2026, 10, 12), new BigDecimal("480.00"), null));
        assertThat(byGuide.caseId()).isEqualTo(caseOf(s1).getId());
        assertThat(byGuide.difference()).isEqualByComparingTo("0.00");

        receiptService.recordForCase(caseOf(s3).getId(), new CaseReceiptRequest(null, "DEP-123456", LocalDate.of(2026, 10, 8),
                new BigDecimal("265.00"), null));
        assertThat(receiptRepository.findAll()).extracting(ReconReceiptEntity::getSourceType)
                .containsExactlyInAnyOrder(Recon.SourceType.CARRIER, Recon.SourceType.BANK);
        assertThat(caseOf(s3).getReceivedAmount()).isEqualByComparingTo("265.00");
    }

    @Test
    void pagoEscrito_rechazaLoDudosoSinGuardarNada() throws Exception {
        caseService.generate(FROM, TO);
        Long id = caseOf(s5).getId();

        assertThatThrownBy(() -> receiptService.recordByIdentifier(new QuickReceiptRequest("ENVL-99999", null, "X", null, new BigDecimal("10"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("No encontré");
        assertThatThrownBy(() -> receiptService.recordByIdentifier(new QuickReceiptRequest(" ", null, "X", null, new BigDecimal("10"), null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> receiptService.recordForCase(id, new CaseReceiptRequest(null, "X", null, BigDecimal.ZERO, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("monto");
        assertThatThrownBy(() -> receiptService.recordForCase(id, new CaseReceiptRequest(null, "X", null, new BigDecimal("10.005"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("decimales");
        assertThatThrownBy(() -> receiptService.recordForCase(id, new CaseReceiptRequest(null, "X", LocalDate.now().plusDays(3), new BigDecimal("10"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("futura");
        assertThatThrownBy(() -> receiptService.recordForCase(caseOf(s4).getId(), new CaseReceiptRequest(null, "X", null, new BigDecimal("10"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("excluido");
        assertThat(receiptRepository.count()).isZero();

        // el mismo pago dos veces (misma referencia y monto) se detiene; uno distinto sí entra
        receiptService.recordForCase(id, new CaseReceiptRequest(null, "LIQ-GX-9", null, new BigDecimal("100.00"), null));
        assertThatThrownBy(() -> receiptService.recordForCase(id, new CaseReceiptRequest(null, "liq-gx-9", null, new BigDecimal("100.00"), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya tiene un pago");
        receiptService.recordForCase(id, new CaseReceiptRequest(null, "LIQ-GX-10", null, new BigDecimal("180.00"), null));
        assertThat(caseOf(s5).getReceivedAmount()).isEqualByComparingTo("280.00");
        assertThat(receiptRepository.count()).isEqualTo(2);
    }

    // ───────── liquidación simplificada: guardar varios pagos y liquidar lo elegido ─────────

    @Test
    void pagosEnBloque_atajoRecibidoIgualCobrado_ySoloRegistraLoQueLeCorresponde() throws Exception {
        caseService.generate(FROM, TO);
        Long c1 = caseOf(s1).getId();
        Long c2 = caseOf(s2).getId();

        BulkReceiptResult r = settlementService.recordMany(List.of(
                new BulkReceiptItem(c1, null, true, "LIQ-FZ-400"),                          // Recibido = cobrado
                new BulkReceiptItem(c2, new BigDecimal("100.00"), false, "LIQ-FZ-400"),     // escrito a mano, faltan Q5
                new BulkReceiptItem(c1, null, true, null),                                  // ya tiene pago: se rechaza
                new BulkReceiptItem(999999L, null, true, null)));                           // no existe

        assertThat(r.saved()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(2);
        assertThat(r.rows().get(0).ok()).isTrue();
        assertThat(r.rows().get(0).received()).isEqualByComparingTo("480.00");
        assertThat(r.rows().get(1).difference()).isEqualByComparingTo("-5.00");
        assertThat(r.rows().get(2).message()).contains("Ya tiene un pago");
        assertThat(r.rows().get(3).message()).contains("no existe");
        // la regla no se movió: cobrado = recibido exacto, la diferencia sigue visible y sin ajuste
        assertThat(caseOf(s1).getStatus()).isEqualTo(Recon.Status.CONCILIADO_AUTO);
        assertThat(caseOf(s2).getStatus()).isEqualTo(Recon.Status.CON_DIFERENCIAS);
        assertThat(caseOf(s2).getPendingBalance()).isEqualByComparingTo("5.00");
        assertThat(receiptRepository.count()).isEqualTo(2);
        assertThatThrownBy(() -> settlementService.recordMany(List.of())).isInstanceOf(BusinessException.class);
    }

    @Test
    void liquidar_cierraSoloLoElegido_aceptaAvisosDeLoQueCuadra_yNuncaLoQueTieneDiferencia() throws Exception {
        caseService.generate(FROM, TO);
        Long c1 = caseOf(s1).getId();
        Long c2 = caseOf(s2).getId();
        Long c3 = caseOf(s3).getId();
        settlementService.recordMany(List.of(
                new BulkReceiptItem(c1, null, true, "LIQ-FZ-401"),
                new BulkReceiptItem(c2, new BigDecimal("100.00"), false, "LIQ-FZ-401"),
                new BulkReceiptItem(c3, null, true, null)));   // depósito sin referencia: cuadra, pero con aviso (R08)
        assertThat(caseOf(s3).getStatus()).isEqualTo(Recon.Status.PENDIENTE_REVISION);

        SettleResult r = settlementService.settle(List.of(c1, c2, c3), "liquidar-1");

        assertThat(r.close().closedCases()).isEqualTo(2);
        assertThat(r.acceptedWithWarnings()).isEqualTo(1);
        assertThat(r.skipped()).singleElement().satisfies(x -> {
            assertThat(x.caseId()).isEqualTo(c2);
            assertThat(x.reason()).contains("No cuadra");
        });
        assertThat(caseOf(s1).isClosed()).isTrue();
        assertThat(caseOf(s3).isClosed()).isTrue();
        assertThat(caseOf(s3).getStatus()).isEqualTo(Recon.Status.CONCILIADO_MANUAL);
        assertThat(caseOf(s2).isClosed()).isFalse();
        assertThat(caseOf(s2).getBatchId()).isNull();
        assertThat(ledgerService.balance(c1)).isEqualByComparingTo("0.00");
        assertThat(ledgerService.balance(c3)).isEqualByComparingTo("0.00");
        assertThat(ledgerService.balance(c2)).isEqualByComparingTo("105.00");   // la deuda de la que no cuadra sigue
        // los avisos aceptados quedan en el historial
        assertThat(queryService.detail(c3).events()).filteredOn(e -> e.type().equals("CONCILIADO_MANUAL"))
                .singleElement().satisfies(e -> assertThat(e.payload()).contains("R08"));

        // reintentar con la misma clave devuelve el mismo cierre y no duplica nada
        long entries = ledgerRepository.count();
        SettleResult again = settlementService.settle(List.of(c1, c2, c3), "liquidar-1");
        assertThat(again.close().alreadyClosed()).isTrue();
        assertThat(again.close().batch().id()).isEqualTo(r.close().batch().id());
        assertThat(ledgerRepository.count()).isEqualTo(entries);
        assertThat(batchRepository.count()).isEqualTo(1);
    }

    @Test
    void liquidar_sinNingunaListaFalla_yNoDejaNadaAMedias() throws Exception {
        caseService.generate(FROM, TO);
        Long c2 = caseOf(s2).getId();
        settlementService.recordMany(List.of(new BulkReceiptItem(c2, new BigDecimal("100.00"), false, "LIQ-FZ-402")));

        assertThatThrownBy(() -> settlementService.settle(List.of(c2, caseOf(s5).getId()), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Ninguna");
        assertThat(batchRepository.count()).isZero();
        assertThatThrownBy(() -> settlementService.settle(List.of(), null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void detalle_traeLasLineasDeLaVentaParaVerlasSinSalirDeLaLiquidacion() throws Exception {
        caseService.generate(FROM, TO);

        SaleInfo sale = queryService.detail(caseOf(s1).getId()).sale();

        assertThat(sale.items()).singleElement().satisfies(i -> {
            assertThat(i.quantity()).isEqualTo(1);
            assertThat(i.subtotal()).isEqualByComparingTo("450.00");
        });
    }
}
