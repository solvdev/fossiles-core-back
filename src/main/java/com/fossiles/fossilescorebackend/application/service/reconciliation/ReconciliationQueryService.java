package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.*;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/** Lecturas de la bandeja y del detalle de un caso. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationQueryService {

    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("saleDate", "saleDate"), Map.entry("shipmentNumber", "shipmentNumber"), Map.entry("guideNumber", "guideNumber"),
            Map.entry("carrier", "carrier"), Map.entry("paymentMethod", "paymentMethod"), Map.entry("expected", "expectedAmount"),
            Map.entry("received", "receivedAmount"), Map.entry("difference", "difference"), Map.entry("seller", "salesperson"),
            Map.entry("status", "status"), Map.entry("saleNumber", "saleNumber"), Map.entry("pending", "pendingBalance"));

    private final ReconCaseRepository caseRepository;
    private final ReconBatchRepository batchRepository;
    private final ReconReceiptRepository receiptRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconCheckResultRepository checkRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final ReconCommissionLineRepository commissionRepository;
    private final ReconEventRepository eventRepository;
    private final ReconLedgerEntryRepository ledgerRepository;
    private final ReconSellerRepository sellerRepository;
    private final OnlineSaleRepository saleRepository;
    private final OnlineSaleItemRepository itemRepository;
    private final ReconciliationProperties props;
    private final ReconciliationRecomputeService recompute;
    private final UserRepository userRepository;

    public CasePage list(CaseFilter filter, int page, int size, String sort, String dir) {
        int pageSize = Math.max(1, Math.min(size, 200));
        String field = SORTABLE.getOrDefault(sort == null ? "" : sort, "saleDate");
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pr = PageRequest.of(Math.max(0, page), pageSize, Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id")));
        Page<ReconCaseEntity> result = caseRepository.findAll(ReconciliationSpecs.of(filter, caseIdsByReference(filter)), pr);
        return new CasePage(rows(result.getContent()), result.getTotalElements(), result.getNumber(), pageSize);
    }

    /** Casos cuyos fondos coinciden exactamente con el texto buscado (referencia, autorización o transacción). */
    Set<Long> caseIdsByReference(CaseFilter filter) {
        if (filter == null || blank(filter.q())) return Set.of();
        List<Long> receiptIds = receiptRepository.findByReferenceOrAuthorization(filter.q().trim()).stream().map(ReconReceiptEntity::getId).toList();
        if (receiptIds.isEmpty()) return Set.of();
        return applicationRepository.findByReceiptIdIn(receiptIds).stream().map(ReconReceiptApplicationEntity::getCaseId).collect(Collectors.toSet());
    }

    public List<CaseRow> rows(List<ReconCaseEntity> cases) {
        if (cases.isEmpty()) return List.of();
        List<Long> caseIds = cases.stream().map(ReconCaseEntity::getId).toList();
        Map<Long, String> batchCodes = new HashMap<>();
        batchRepository.findAllById(cases.stream().map(ReconCaseEntity::getBatchId).filter(Objects::nonNull).distinct().toList())
                .forEach(b -> batchCodes.put(b.getId(), b.getCode()));
        Map<Long, List<ReconReceiptApplicationEntity>> apps = applicationRepository.findByCaseIdInAndStatus(caseIds, Recon.RowStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(ReconReceiptApplicationEntity::getCaseId));
        Map<Long, ReconReceiptEntity> receipts = receiptRepository.findByIdIn(apps.values().stream().flatMap(List::stream)
                .map(ReconReceiptApplicationEntity::getReceiptId).distinct().toList()).stream().collect(Collectors.toMap(ReconReceiptEntity::getId, r -> r));
        Map<Long, ReconCommissionLineEntity> lines = commissionRepository.findByCaseIdIn(caseIds).stream()
                .collect(Collectors.toMap(ReconCommissionLineEntity::getCaseId, l -> l));
        Map<Long, OnlineSaleEntity> sales = saleRepository.findAllById(cases.stream().map(ReconCaseEntity::getOnlineSaleId).toList()).stream()
                .collect(Collectors.toMap(OnlineSaleEntity::getId, s -> s));
        List<CaseRow> out = new ArrayList<>();
        for (ReconCaseEntity c : cases) {
            String reference = apps.getOrDefault(c.getId(), List.of()).stream().map(a -> receipts.get(a.getReceiptId())).filter(Objects::nonNull)
                    .map(r -> !blank(r.getReference()) ? r.getReference() : r.getAuthorizationNumber()).filter(s -> !blank(s)).distinct()
                    .collect(Collectors.joining(", "));
            ReconCommissionLineEntity line = lines.get(c.getId());
            OnlineSaleEntity sale = sales.get(c.getOnlineSaleId());
            BigDecimal commission = line != null ? line.getCommissionAmount()
                    : money(sale == null ? null : sale.getNetAmount()).multiply(props.getCommissionRate()).setScale(2, RoundingMode.HALF_UP);
            out.add(row(c, reference, commission, batchCodes.get(c.getBatchId()), apps.containsKey(c.getId()), sale == null ? null : sale.getCustomerName()));
        }
        return out;
    }

    private CaseRow row(ReconCaseEntity c, String reference, BigDecimal commission, String batchCode, boolean hasReceipts, String customerName) {
        return new CaseRow(c.getId(), c.getOnlineSaleId(), c.getSaleDate(), c.getSaleNumber(), c.getShipmentNumber(), c.getGuideNumber(), c.getCarrier(),
                c.getPaymentMethod(), reference, money(c.getExpectedAmount()), money(c.getReceivedAmount()), money(c.getDifference()),
                money(c.getAuthorizedAdjustment()), money(c.getPendingBalance()), commission, c.getSalesperson(), c.getStatus().name(),
                c.getOrigin().name(), c.getBatchId(), batchCode, c.getCriticalCount(), c.getWarningCount(),
                c.getProblemType() == null ? null : c.getProblemType().name(), hasReceipts, customerName);
    }

    private List<SaleItemInfo> saleItems(Long saleId) {
        return itemRepository.findByOnlineSaleIdInOrderByIdAsc(List.of(saleId)).stream()
                .map(i -> new SaleItemInfo(i.getProductCode(), i.getProductName(), i.getColorName(), i.getSize(), i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
    }

    public CaseDetail detail(Long id) throws ResourceNotFoundException {
        ReconCaseEntity c = caseRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + id));
        OnlineSaleEntity sale = saleRepository.findById(c.getOnlineSaleId()).orElse(null);
        CaseRow row = rows(List.of(c)).get(0);

        List<CheckDto> checks = c.isClosed()
                ? checkRepository.findByCaseIdOrderByIdAsc(id).stream().map(r -> new CheckDto(r.getRuleCode(), ruleName(r.getRuleCode()),
                        r.getSeverity().name(), r.getOutcome().name(), r.getExpectedValue(), r.getFoundValue(), r.getMessage(), r.getRecommendedAction())).toList()
                : recompute.evaluate(c).stream().map(r -> new CheckDto(r.code(), ruleName(r.code()), r.severity().name(), r.outcome().name(),
                        r.expected(), r.found(), r.message(), r.action())).toList();

        List<ReconReceiptApplicationEntity> appEntities = applicationRepository.findByCaseIdOrderByIdAsc(id);
        Map<Long, ReconReceiptEntity> receipts = receiptRepository.findByIdIn(appEntities.stream().map(ReconReceiptApplicationEntity::getReceiptId).distinct().toList())
                .stream().collect(Collectors.toMap(ReconReceiptEntity::getId, r -> r));
        List<ReconDifferenceEntity> diffEntities = differenceRepository.findByCaseIdOrderByIdDesc(id);
        List<ReconEventEntity> eventEntities = eventRepository.findByCaseIdOrderByIdDesc(id).stream().limit(200).toList();
        Map<Long, String> names = userNames(appEntities.stream().map(ReconReceiptApplicationEntity::getAppliedBy), diffEntities.stream().map(ReconDifferenceEntity::getProposedBy),
                diffEntities.stream().map(ReconDifferenceEntity::getAuthorizedBy), eventEntities.stream().map(ReconEventEntity::getActorId));
        List<ApplicationDto> applications = appEntities.stream().map(a -> {
            ReconReceiptEntity r = receipts.get(a.getReceiptId());
            return new ApplicationDto(a.getId(), a.getReceiptId(), a.getAmountApplied(), a.getMatchMethod().name(), a.getStatus().name(), a.getAppliedAt(),
                    a.getAppliedBy(), names.get(a.getAppliedBy()), r == null ? null : r.getSourceType().name(), r == null ? null : r.getCarrier(), r == null ? null : r.getGuideNumber(),
                    r == null ? null : (!blank(r.getReference()) ? r.getReference() : r.getAuthorizationNumber()), r == null ? null : r.getReceivedDate(),
                    r == null ? null : r.getAmount(), r == null ? null : r.getShippingAmount());
        }).toList();

        List<DifferenceDto> differences = diffEntities.stream().map(d -> ReconciliationDifferenceService.toDto(d, names)).toList();
        List<EventDto> events = eventEntities.stream()
                .map(e -> new EventDto(e.getId(), e.getEventType(), e.getFromStatus(), e.getToStatus(), e.getPayload(), e.getActorId(),
                        names.get(e.getActorId()), e.getCreatedAt())).toList();
        List<LedgerDto> ledger = ledgerRepository.findByCaseIdOrderByIdAsc(id).stream()
                .map(e -> new LedgerDto(e.getId(), e.getEntryType().name(), e.getAmount(), e.getBatchId(), e.getNote(), e.getCreatedAt())).toList();

        SaleInfo saleInfo = sale == null ? null : new SaleInfo(sale.getSaleNumber(), sale.getShipmentNumber(), sale.getSaleDate(), sale.getCustomerName(),
                sale.getStatus(), sale.getPaymentMethod(), sale.getPaymentAuthorization(), sale.getShippingCarrier(), sale.getGuideNumber(),
                sale.getSalesperson(), sale.getNetAmount(), sale.getShippingCost(), sale.getTotalAmount(), sale.getInvoiceId(), sale.getDispatchedAt(),
                sale.getUpdatedAt(), saleItems(sale.getId()));
        BigDecimal net = sale == null ? BigDecimal.ZERO : money(sale.getNetAmount());
        BigDecimal expectedCommission = net.multiply(props.getCommissionRate()).setScale(2, RoundingMode.HALF_UP);
        CommissionDto commission = commissionRepository.findByCaseId(id)
                .map(l -> new CommissionDto(l.getMonth(), l.getBaseAmount(), l.getRate(), expectedCommission, l.getCommissionAmount(), l.getStatus().name()))
                .orElse(new CommissionDto(c.getSaleDate().toString().substring(0, 7), net, props.getCommissionRate(), expectedCommission, null, "SIN_REGISTRAR"));
        boolean sourceChanged = sale != null && !sameMoney(sale.getTotalAmount(), c.getExpectedAmount());
        return new CaseDetail(row, saleInfo, checks, applications, differences, events, ledger, commission, sourceChanged, actions(c, differences, sourceChanged));
    }

    @SafeVarargs
    private Map<Long, String> userNames(java.util.stream.Stream<Long>... streams) {
        Set<Long> ids = new HashSet<>();
        for (java.util.stream.Stream<Long> s : streams) s.filter(Objects::nonNull).forEach(ids::add);
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (UserEntity u : userRepository.findAllById(ids)) out.put(u.getId(), u.getUsername());
        return out;
    }

    /** Acciones que tienen sentido según el estado. Los permisos los valida cada endpoint. */
    static List<String> actions(ReconCaseEntity c, List<DifferenceDto> differences, boolean sourceChanged) {
        if (c.isClosed()) return List.of();
        List<String> a = new ArrayList<>(List.of("OBSERVE", "REVALIDATE"));
        if (c.getExcludedReason() != null) {
            a.add("INCLUDE");
            return a;
        }
        a.add("APPLY_RECEIPT");
        if (c.getReceivedAmount().signum() > 0) a.add("VOID_APPLICATION");
        switch (c.getStatus()) {
            case PENDIENTE_REVISION -> a.add("RECONCILE");
            case CONCILIADO_AUTO, CONCILIADO_MANUAL, CONCILIADO_CON_AJUSTE -> a.add("REOPEN");
            case CON_DIFERENCIAS -> {
                if (c.getReceivedAmount().signum() > 0 && c.getReceivedAmount().compareTo(c.getExpectedAmount()) < 0) a.add("PROPOSE_ADJUSTMENT");
            }
            case REQUIERE_AUTORIZACION -> {
                a.add("AUTHORIZE");
                a.add("REJECT_ADJUSTMENT");
            }
            default -> {
            }
        }
        if (sourceChanged) a.add("REFRESH_AMOUNT");
        if (c.getReceivedAmount().signum() == 0) a.add("EXCLUDE");
        return a;
    }

    private static String ruleName(String code) {
        return switch (code) {
            case "R01" -> "La venta existe y es conciliable";
            case "R02" -> "ENVL presente";
            case "R03" -> "Guía presente";
            case "R04" -> "El transportista coincide con la fuente";
            case "R05" -> "La guía de la fuente coincide con la de la venta";
            case "R06" -> "Cobrado = recibido (exacto)";
            case "R07" -> "La fuente corresponde a la forma de pago";
            case "R08" -> "Referencia de pago presente y correcta";
            case "R09" -> "Fechas coherentes";
            case "R10" -> "Sin duplicados";
            case "R11" -> "Integridad: total = neto + envío; neto = líneas";
            case "R12" -> "Envío de la fuente = envío de la venta";
            case "R13" -> "Comisión del vendedor calculada y registrada";
            case "R14" -> "Factura FEL = cobrado";
            case "R15" -> "Venta devuelta o anulada sin fondos";
            case "R16" -> "Fondos aplicados ≤ cobrado";
            case "R17" -> "Cambio con cobro de envío registrado";
            default -> code;
        };
    }

    public List<SellerDto> sellers() {
        return sellerRepository.findAllByOrderByNameAsc().stream().map(s -> new SellerDto(s.getId(), s.getName(), s.isActive())).toList();
    }
}
