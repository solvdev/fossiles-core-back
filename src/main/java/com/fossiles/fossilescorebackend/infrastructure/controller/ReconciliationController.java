package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Conciliación de ventas en línea: casos, fondos recibidos, diferencias y ajustes, lotes de descarga y comisiones.
 * Cada endpoint valida su permiso con {@link ReconciliationAccessGuard} antes de llamar al servicio.
 */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReconciliationAccessGuard guard;
    private final ReconciliationCaseService caseService;
    private final ReconciliationQueryService queryService;
    private final ReconciliationDashboardService dashboardService;
    private final ReconciliationReceiptService receiptService;
    private final ReconciliationDifferenceService differenceService;
    private final ReconciliationBatchService batchService;
    private final ReconciliationSettlementService settlementService;
    private final ReconciliationCommissionService commissionService;
    private final ReconciliationSellerService sellerService;
    private final ReconciliationGuideImportService guideImportService;

    // ───────── dashboard y bandeja ─────────

    @GetMapping("/dashboard")
    public ResponseEntity<Dashboard> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String method, @RequestParam(required = false) String carrier,
            @RequestParam(required = false) Long sellerId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long batchId, @RequestParam(defaultValue = "false") boolean withoutBatch)
            throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(dashboardService.dashboard(filter(from, to, method, carrier, sellerId, status, batchId, withoutBatch, null, null, null)));
    }

    @GetMapping("/cases")
    public ResponseEntity<CasePage> cases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String method, @RequestParam(required = false) String carrier,
            @RequestParam(required = false) Long sellerId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long batchId, @RequestParam(defaultValue = "false") boolean withoutBatch,
            @RequestParam(required = false) String q, @RequestParam(required = false) String problem,
            @RequestParam(required = false) String origin, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir) throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(queryService.list(filter(from, to, method, carrier, sellerId, status, batchId, withoutBatch, q, problem, origin),
                page, size, sort, dir));
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<CaseDetail> caseDetail(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanView();
        return ResponseEntity.ok(queryService.detail(id));
    }

    // ───────── casos ─────────

    @PostMapping("/cases/generate")
    public ResponseEntity<GenerateResult> generate(@RequestBody Map<String, String> body) throws BusinessException {
        guard.assertCanReconcile();
        return ResponseEntity.ok(caseService.generate(date(body, "from"), date(body, "to")));
    }

    @PostMapping("/cases/revalidate")
    public ResponseEntity<Map<String, Integer>> revalidatePeriod(@RequestBody Map<String, String> body) throws BusinessException {
        guard.assertCanReconcile();
        return ResponseEntity.ok(Map.of("revalidated", caseService.revalidatePeriod(date(body, "from"), date(body, "to"))));
    }

    @PostMapping("/cases/{id}/revalidate")
    public ResponseEntity<CaseDetail> revalidate(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.revalidate(id);
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/{id}/reconcile")
    public ResponseEntity<CaseDetail> reconcile(@PathVariable Long id, @RequestBody(required = false) ReasonRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.reconcile(id, body == null ? null : body.reason());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/bulk-reconcile")
    public ResponseEntity<BulkResult> bulkReconcile(@RequestBody Map<String, Object> body) throws BusinessException {
        guard.assertCanReconcile();
        List<Long> ids = ((List<?>) body.getOrDefault("ids", List.of())).stream().map(o -> Long.valueOf(String.valueOf(o))).toList();
        Object obs = body.get("observation");
        return ResponseEntity.ok(caseService.bulkReconcile(ids, obs == null ? null : String.valueOf(obs)));
    }

    @PostMapping("/cases/{id}/reopen")
    public ResponseEntity<CaseDetail> reopen(@PathVariable Long id, @RequestBody ReasonRequest body) throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.reopen(id, body.reason());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/{id}/exclude")
    public ResponseEntity<CaseDetail> exclude(@PathVariable Long id, @RequestBody ReasonRequest body) throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.exclude(id, body.reason());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/{id}/include")
    public ResponseEntity<CaseDetail> include(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.include(id);
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/{id}/observe")
    public ResponseEntity<CaseDetail> observe(@PathVariable Long id, @RequestBody ReasonRequest body) throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.observe(id, body.reason());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/{id}/refresh-amount")
    public ResponseEntity<CaseDetail> refreshAmount(@PathVariable Long id, @RequestBody ReasonRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        caseService.refreshAmountFromSale(id, body.reason());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/cases/{id}/apply-receipt")
    public ResponseEntity<CaseDetail> applyReceipt(@PathVariable Long id, @RequestBody ApplyRequest body) throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        receiptService.apply(id, body.receiptId(), body.amount());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @PostMapping("/applications/{id}/void")
    public ResponseEntity<Map<String, String>> voidApplication(@PathVariable Long id, @RequestBody ReasonRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanReconcile();
        receiptService.voidApplication(id, body.reason());
        return ResponseEntity.ok(Map.of("status", "VOID"));
    }

    // ───────── fondos ─────────

    @PostMapping(value = "/receipts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importReceipts(@RequestParam("file") MultipartFile file, @RequestParam("sourceType") Recon.SourceType sourceType)
            throws BusinessException {
        guard.assertCanImport();
        return ResponseEntity.ok(receiptService.importFile(bytes(file), file.getOriginalFilename(), sourceType));
    }

    @PostMapping("/receipts")
    public ResponseEntity<ReceiptMatch> createReceipt(@RequestBody ReceiptRequest body) throws BusinessException {
        guard.assertCanImport();
        return ResponseEntity.ok(receiptService.createManual(body));
    }

    /** Pago escrito en la captura rápida: el ENVL o la guía identifican la venta. */
    @PostMapping("/receipts/quick")
    public ResponseEntity<QuickReceiptResult> quickReceipt(@RequestBody QuickReceiptRequest body) throws BusinessException, ResourceNotFoundException {
        guard.assertCanImport();
        return ResponseEntity.ok(receiptService.recordByIdentifier(body));
    }

    /** Pago escrito dentro del caso abierto. */
    @PostMapping("/cases/{id}/record-receipt")
    public ResponseEntity<CaseDetail> recordReceipt(@PathVariable Long id, @RequestBody CaseReceiptRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanImport();
        receiptService.recordForCase(id, body);
        return ResponseEntity.ok(queryService.detail(id));
    }

    /** Pagos escritos de varias ventas a la vez (pantalla de liquidación). Cada pago se valida y guarda por separado. */
    @PostMapping("/cases/receipts/bulk")
    public ResponseEntity<BulkReceiptResult> bulkReceipts(@RequestBody BulkReceiptRequest body) throws BusinessException {
        guard.assertCanImport();
        return ResponseEntity.ok(settlementService.recordMany(body == null ? null : body.items()));
    }

    @PostMapping("/receipts/auto-apply")
    public ResponseEntity<ImportResult> autoApply() throws BusinessException {
        guard.assertCanImport();
        return ResponseEntity.ok(receiptService.autoApplyPending());
    }

    @GetMapping("/receipts/unapplied")
    public ResponseEntity<List<ReceiptMatch>> unapplied(@RequestParam(defaultValue = "100") int limit) throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(receiptService.unapplied(limit));
    }

    @GetMapping("/receipts/{id}")
    public ResponseEntity<ReceiptMatch> receipt(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanView();
        return ResponseEntity.ok(receiptService.find(id));
    }

    @PostMapping("/receipts/{id}/void")
    public ResponseEntity<Map<String, String>> voidReceipt(@PathVariable Long id, @RequestBody ReasonRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanImport();
        receiptService.voidReceipt(id, body.reason());
        return ResponseEntity.ok(Map.of("status", "VOID"));
    }

    // ───────── diferencias y ajustes ─────────

    @PostMapping("/cases/{id}/adjustments")
    public ResponseEntity<CaseDetail> proposeAdjustment(@PathVariable Long id, @RequestBody AdjustmentRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanAdjust();
        differenceService.propose(id, body.reason());
        return ResponseEntity.ok(queryService.detail(id));
    }

    @GetMapping("/adjustments/pending")
    public ResponseEntity<List<DifferenceDto>> pendingAdjustments() throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(differenceService.pendingAuthorization());
    }

    @PostMapping("/adjustments/{id}/authorize")
    public ResponseEntity<DifferenceDto> authorize(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanAuthorize();
        return ResponseEntity.ok(ReconciliationDifferenceService.toDto(differenceService.authorize(id)));
    }

    @PostMapping("/adjustments/{id}/reject")
    public ResponseEntity<DifferenceDto> rejectAdjustment(@PathVariable Long id, @RequestBody(required = false) ReasonRequest body)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanAuthorize();
        return ResponseEntity.ok(ReconciliationDifferenceService.toDto(differenceService.reject(id, body == null ? null : body.reason())));
    }

    // ───────── lotes y descargas ─────────

    @PostMapping("/batches")
    public ResponseEntity<BatchDto> createBatch(@RequestBody BatchCreateRequest body) throws BusinessException {
        guard.assertCanClose();
        return ResponseEntity.ok(batchService.create(body));
    }

    /** Liquidar las ventas elegidas: acepta avisos de las que cuadran, crea el lote y lo cierra. El Excel se baja con /batches/{id}/export. */
    @PostMapping("/batches/settle")
    public ResponseEntity<SettleResult> settle(@RequestBody SettleRequest body, @RequestHeader(value = "Idempotency-Key", required = false) String key)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanClose();
        return ResponseEntity.ok(settlementService.settle(body == null ? null : body.caseIds(), key));
    }

    @GetMapping("/batches")
    public ResponseEntity<List<BatchDto>> batches() throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(batchService.list());
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<BatchDto> batch(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanView();
        return ResponseEntity.ok(batchService.get(id));
    }

    @PostMapping("/batches/{id}/close")
    public ResponseEntity<BatchCloseResult> closeBatch(@PathVariable Long id, @RequestHeader(value = "Idempotency-Key", required = false) String key)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanClose();
        return ResponseEntity.ok(batchService.close(id, key));
    }

    @PostMapping("/batches/{id}/void")
    public ResponseEntity<BatchDto> voidBatch(@PathVariable Long id, @RequestBody ReasonRequest body) throws BusinessException, ResourceNotFoundException {
        guard.assertCanAuthorize();
        return ResponseEntity.ok(batchService.voidBatch(id, body.reason()));
    }

    @PostMapping("/batches/{id}/release/{caseId}")
    public ResponseEntity<BatchDto> releaseCase(@PathVariable Long id, @PathVariable Long caseId) throws BusinessException, ResourceNotFoundException {
        guard.assertCanClose();
        return ResponseEntity.ok(batchService.release(id, caseId));
    }

    @GetMapping("/batches/{id}/export")
    public ResponseEntity<byte[]> exportBatch(@PathVariable Long id) throws BusinessException, ResourceNotFoundException {
        guard.assertCanView();
        ReconciliationBatchService.ExportFile file = batchService.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .header("X-Content-Sha256", file.sha256())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition, X-Content-Sha256")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(file.bytes());
    }

    @GetMapping("/exports")
    public ResponseEntity<List<ExportDto>> exports() throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(batchService.exports());
    }

    // ───────── comisiones y vendedores ─────────

    @GetMapping("/commissions")
    public ResponseEntity<CommissionSummary> commissions(@RequestParam String month) throws BusinessException {
        guard.assertCanCommissions();
        return ResponseEntity.ok(commissionService.summary(month));
    }

    @PostMapping("/commissions/calculate")
    public ResponseEntity<CommissionSummary> calculateCommissions(@RequestParam String month) throws BusinessException {
        guard.assertCanCommissions();
        return ResponseEntity.ok(commissionService.calculate(month));
    }

    @PostMapping("/commissions/close-month")
    public ResponseEntity<CommissionSummary> closeMonth(@RequestParam String month) throws BusinessException {
        guard.assertCanCommissions();
        return ResponseEntity.ok(commissionService.closeMonth(month));
    }

    @GetMapping("/sellers")
    public ResponseEntity<List<SellerDto>> sellers() throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(queryService.sellers());
    }

    @GetMapping("/sellers/missing")
    public ResponseEntity<List<String>> missingSellers() throws BusinessException {
        guard.assertCanView();
        return ResponseEntity.ok(sellerService.missingFromCatalog());
    }

    @PostMapping("/sellers")
    public ResponseEntity<SellerDto> createSeller(@RequestBody Map<String, String> body) throws BusinessException {
        guard.assertCanCommissions();
        return ResponseEntity.ok(sellerService.create(body.get("name")));
    }

    @PutMapping("/sellers/{id}/active")
    public ResponseEntity<SellerDto> setSellerActive(@PathVariable Long id, @RequestParam boolean active) throws BusinessException, ResourceNotFoundException {
        guard.assertCanCommissions();
        return ResponseEntity.ok(sellerService.setActive(id, active));
    }

    // ───────── guías históricas ─────────

    @PostMapping(value = "/guides/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GuideImportResult> importGuides(@RequestParam("file") MultipartFile file,
                                                          @RequestParam(defaultValue = "true") boolean dryRun) throws BusinessException {
        guard.assertCanImport();
        return ResponseEntity.ok(guideImportService.importGuides(bytes(file), file.getOriginalFilename(), dryRun));
    }

    // ───────── apoyo ─────────

    private static CaseFilter filter(LocalDate from, LocalDate to, String method, String carrier, Long sellerId, String status, Long batchId,
                                     boolean withoutBatch, String q, String problem, String origin) throws BusinessException {
        try {
            List<Recon.Status> statuses = status == null || status.isBlank() ? List.of()
                    : Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Recon.Status::valueOf).toList();
            return new CaseFilter(from, to, method, carrier, sellerId, statuses, batchId, withoutBatch, q,
                    problem == null || problem.isBlank() ? null : Recon.DifferenceType.valueOf(problem),
                    origin == null || origin.isBlank() ? null : Recon.Origin.valueOf(origin));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Valor de filtro no válido: " + e.getMessage());
        }
    }

    private static LocalDate date(Map<String, String> body, String key) throws BusinessException {
        try {
            return LocalDate.parse(body.get(key));
        } catch (Exception e) {
            throw new BusinessException("Indique '" + key + "' con el formato AAAA-MM-DD.");
        }
    }

    private static byte[] bytes(MultipartFile file) throws BusinessException {
        if (file == null || file.isEmpty()) throw new BusinessException("Seleccione un archivo.");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo: " + e.getMessage());
        }
    }
}
