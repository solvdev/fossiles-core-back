package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.EmployeeChargeResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeApplicationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeChargeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cargos a colaboradores por compras con 50 % (solicitudes de envío interno tipo PLANILLA).
 * Planta se descuenta completo en planilla; en administración Contabilidad decide si se descuenta,
 * se paga por transferencia o en efectivo. Kioscos no tiene compras autorizadas.
 */
@Service
@RequiredArgsConstructor
public class EmployeeChargeService {

    public static final String SOURCE_INTERNAL_SHIPMENT = "ENVIO_INTERNO";
    public static final String SETTLEMENT_DEDUCTION = "DESCUENTO";
    public static final String SETTLEMENT_TRANSFER = "TRANSFERENCIA";
    public static final String SETTLEMENT_CASH = "EFECTIVO";
    private static final Set<String> SETTLEMENT_METHODS = Set.of(SETTLEMENT_DEDUCTION, SETTLEMENT_TRANSFER, SETTLEMENT_CASH);

    private final EmployeeChargeRepository chargeRepository;
    private final EmployeeChargeApplicationRepository applicationRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final ProductRepository productRepository;
    private final PayrollAccessGuard accessGuard;
    private final AccountingService accountingService;
    private final InternalShipmentRequestRepository requestRepository;

    /** Lanza si el colaborador pertenece a kioscos: allí no hay compras de colaborador autorizadas. */
    @Transactional(readOnly = true)
    public void assertEmployeeMayBuy(EmployeeEntity employee) throws BusinessException {
        DepartmentEntity department = employee.getDepartmentId() == null ? null
                : departmentRepository.findById(employee.getDepartmentId()).orElse(null);
        if (department != null && PayrollRules.isKiosk(department.getName(), department.getCode())) {
            throw new BusinessException("El personal de kioscos no tiene autorizadas compras de colaborador.");
        }
    }

    /**
     * Registra el cargo de una solicitud PLANILLA ya despachada, al precio de catálogo con el descuento de colaborador.
     * Es idempotente: una segunda llamada para la misma solicitud devuelve el cargo existente.
     */
    @Transactional
    public EmployeeChargeEntity chargeInternalShipment(InternalShipmentRequestEntity request, String shipmentNumber)
            throws BusinessException {
        if (request.getEmployeeId() == null) {
            throw new BusinessException("La solicitud de planilla no tiene colaborador asignado.");
        }
        var existing = chargeRepository.findBySourceTypeAndSourceId(SOURCE_INTERNAL_SHIPMENT, request.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        EmployeeEntity employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new BusinessException("El colaborador de la solicitud ya no existe."));
        BigDecimal amount = internalShipmentAmount(request);
        if (amount.signum() <= 0) {
            throw new BusinessException("La solicitud no tiene productos con cantidad; no hay cargo que registrar.");
        }
        boolean plant = PayrollRules.GROUP_PLANTA.equals(employee.getPayGroup());
        String reference = shipmentNumber != null && !shipmentNumber.isBlank() ? shipmentNumber : "solicitud #" + request.getId();
        EmployeeChargeEntity charge = chargeRepository.save(EmployeeChargeEntity.builder()
                .employeeId(employee.getId())
                .sourceType(SOURCE_INTERNAL_SHIPMENT)
                .sourceId(request.getId())
                .description("Compra de colaborador " + reference)
                .amount(amount)
                .balance(amount)
                .settlementMethod(plant ? SETTLEMENT_DEDUCTION : null)
                .chargedOn(GuatemalaDateTime.today())
                .createdBy(accessGuard.currentUserId())
                .build());
        accountingService.postEmployeeCharge(charge.getId(), amount, reference, employee.getCostCenterId());
        return charge;
    }

    BigDecimal internalShipmentAmount(InternalShipmentRequestEntity request) throws BusinessException {
        BigDecimal pricePercent = ProductDistributionService
                .resolveInternalDiscount("PLANILLA", null, null).discountPercent();
        Set<Long> productIds = request.getLines().stream()
                .map(InternalShipmentRequestLineEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        BigDecimal total = BigDecimal.ZERO;
        for (InternalShipmentRequestLineEntity line : request.getLines()) {
            if (line.getQuantity() == null || line.getQuantity().signum() <= 0) {
                continue;
            }
            ProductEntity product = products.get(line.getProductId());
            if (product == null || product.getSalePrice() == null || product.getSalePrice().signum() <= 0) {
                String label = product == null ? "#" + line.getProductId() : product.getCode() + " " + product.getName();
                throw new BusinessException("El producto " + label.trim()
                        + " no tiene precio de venta; asígnelo antes de autorizar la compra del colaborador.");
            }
            total = total.add(product.getSalePrice().multiply(line.getQuantity()));
        }
        return total.multiply(pricePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Validaciones previas al despacho, para que una compra que no puede cargarse al colaborador
     * no llegue a mover inventario.
     */
    @Transactional(readOnly = true)
    public void assertChargeable(InternalShipmentRequestEntity request) throws BusinessException {
        if (request.getEmployeeId() == null) {
            throw new BusinessException("La solicitud de planilla no tiene colaborador asignado.");
        }
        EmployeeEntity employee = employeeRepository.findById(request.getEmployeeId())
                .orElseThrow(() -> new BusinessException("El colaborador de la solicitud ya no existe."));
        assertEmployeeMayBuy(employee);
        internalShipmentAmount(request);
    }

    @Transactional(readOnly = true)
    public List<EmployeeChargeResponse> list(String status, Long employeeId) {
        accessGuard.assertCanViewCharges();
        String normalizedStatus = status == null || status.isBlank() ? null : PayrollRules.normalize(status);
        List<EmployeeChargeEntity> charges = chargeRepository.findAllByOrderByChargedOnDescIdDesc().stream()
                .filter(c -> normalizedStatus == null || normalizedStatus.equals(c.getStatus()))
                .filter(c -> employeeId == null || employeeId.equals(c.getEmployeeId()))
                .toList();
        Map<Long, EmployeeEntity> employees = employeeRepository.findAllById(
                        charges.stream().map(EmployeeChargeEntity::getEmployeeId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(EmployeeEntity::getId, Function.identity()));
        return charges.stream().map(c -> toResponse(c, employees.get(c.getEmployeeId()))).toList();
    }

    /** Contabilidad decide cómo se liquida el cargo. La planta siempre se descuenta en planilla. */
    @Transactional
    public EmployeeChargeResponse decide(Long chargeId, String settlementMethod)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanEditCharges();
        String method = PayrollRules.normalize(settlementMethod);
        if (method == null || !SETTLEMENT_METHODS.contains(method)) {
            throw new BusinessException("Forma de liquidación inválida. Use DESCUENTO, TRANSFERENCIA o EFECTIVO.");
        }
        EmployeeChargeEntity charge = chargeRepository.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new ResourceNotFoundException("EmployeeCharge", chargeId));
        assertOpen(charge);
        EmployeeEntity employee = employeeRepository.findById(charge.getEmployeeId()).orElse(null);
        if (stillInPlant(employee) && !SETTLEMENT_DEDUCTION.equals(method)) {
            throw new BusinessException("Las compras del personal de planta se descuentan completas en planilla.");
        }
        assertNotReserved(charge);
        charge.setSettlementMethod(method);
        charge.setDecidedBy(accessGuard.currentUserId());
        charge.setDecidedAt(GuatemalaDateTime.now());
        return toResponse(chargeRepository.save(charge), employee);
    }

    /** El pago por transferencia o en efectivo se hace fuera del sistema; aquí solo se registra que quedó saldado. */
    @Transactional
    public EmployeeChargeResponse markPaid(Long chargeId) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanEditCharges();
        EmployeeChargeEntity charge = chargeRepository.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new ResourceNotFoundException("EmployeeCharge", chargeId));
        assertOpen(charge);
        if (!SETTLEMENT_TRANSFER.equals(charge.getSettlementMethod()) && !SETTLEMENT_CASH.equals(charge.getSettlementMethod())) {
            throw new BusinessException("Solo los cargos a pagar por transferencia o en efectivo se marcan como pagados; los demás se descuentan en planilla.");
        }
        accountingService.postEmployeeChargePayment(charge.getId(), charge.getBalance(),
                SETTLEMENT_CASH.equals(charge.getSettlementMethod()), charge.getDescription());
        charge.setBalance(BigDecimal.ZERO.setScale(2));
        charge.setStatus("PAGADO");
        charge.setDecidedBy(accessGuard.currentUserId());
        charge.setDecidedAt(GuatemalaDateTime.now());
        return toResponse(chargeRepository.save(charge), employeeRepository.findById(charge.getEmployeeId()).orElse(null));
    }

    /** Anula un cargo registrado por error, siempre que ninguna planilla lo haya descontado. */
    @Transactional
    public EmployeeChargeResponse cancel(Long chargeId) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanEditCharges();
        EmployeeChargeEntity charge = chargeRepository.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new ResourceNotFoundException("EmployeeCharge", chargeId));
        assertOpen(charge);
        if (charge.getBalance().compareTo(charge.getAmount()) != 0 || !applicationRepository.findByChargeId(chargeId).isEmpty()) {
            throw new BusinessException("El cargo ya tiene descuentos en planilla; no se puede anular.");
        }
        accountingService.postEmployeeChargeCancellation(charge.getId(), charge.getDescription());
        charge.setStatus("ANULADO");
        charge.setDecidedBy(accessGuard.currentUserId());
        charge.setDecidedAt(GuatemalaDateTime.now());
        return toResponse(chargeRepository.save(charge), employeeRepository.findById(charge.getEmployeeId()).orElse(null));
    }

    /**
     * Antes de anular el envío de una compra de colaborador: bloquea su cargo y verifica que nada se haya descontado
     * ni cobrado. Se llama antes de devolver el inventario, para no deshacer a medias.
     */
    @Transactional
    public Optional<EmployeeChargeEntity> lockCancellableChargeForShipment(Long shipmentId) throws BusinessException {
        Optional<EmployeeChargeEntity> found = chargeForShipment(shipmentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EmployeeChargeEntity charge = chargeRepository.findByIdForUpdate(found.get().getId()).orElse(null);
        if (charge == null || "ANULADO".equals(charge.getStatus())) {
            return Optional.empty();
        }
        if (!"PENDIENTE".equals(charge.getStatus()) || charge.getBalance().compareTo(charge.getAmount()) != 0) {
            throw new BusinessException("Esta compra de colaborador ya se descontó o se cobró (saldo " + charge.getBalance()
                    + " de " + charge.getAmount() + "); no se puede anular el envío.");
        }
        return Optional.of(charge);
    }

    /** Anula el cargo del envío anulado y libera lo que los borradores tenían reservado. */
    @Transactional
    public void cancelChargeOfCancelledShipment(EmployeeChargeEntity charge) {
        applicationRepository.deleteDraftReservations(charge.getId());
        accountingService.postEmployeeChargeCancellation(charge.getId(), charge.getDescription());
        charge.setStatus("ANULADO");
        charge.setDecidedBy(accessGuard.currentUserId());
        charge.setDecidedAt(GuatemalaDateTime.now());
        chargeRepository.save(charge);
    }

    /**
     * El cargo se calcula con la solicitud; regresar el envío a bodega para editarlo lo dejaría distinto de lo
     * despachado. Para corregir una compra se anula el envío y se hace otra solicitud.
     */
    @Transactional(readOnly = true)
    public void assertShipmentCanBeReverted(Long shipmentId) throws BusinessException {
        if (chargeForShipment(shipmentId).filter(charge -> !"ANULADO".equals(charge.getStatus())).isPresent()) {
            throw new BusinessException("Este envío es una compra de colaborador con cargo registrado: anúlelo y cree una"
                    + " solicitud nueva en lugar de regresarlo a bodega.");
        }
    }

    private Optional<EmployeeChargeEntity> chargeForShipment(Long shipmentId) {
        return requestRepository.findByProductShipmentId(shipmentId)
                .filter(request -> "PLANILLA".equalsIgnoreCase(String.valueOf(request.getRequestType()).trim()))
                .flatMap(request -> chargeRepository.findBySourceTypeAndSourceId(SOURCE_INTERNAL_SHIPMENT, request.getId()));
    }

    /** Planta descuenta en planilla; quien ya salió (baja o inactivo) puede pagar por transferencia o en efectivo. */
    private static boolean stillInPlant(EmployeeEntity employee) {
        return employee != null && PayrollRules.GROUP_PLANTA.equals(employee.getPayGroup())
                && employee.getTerminationDate() == null && "active".equalsIgnoreCase(employee.getStatus());
    }

    private void assertOpen(EmployeeChargeEntity charge) throws BusinessException {
        if (!"PENDIENTE".equals(charge.getStatus()) && !"PARCIAL".equals(charge.getStatus())) {
            throw new BusinessException("El cargo ya está " + charge.getStatus().toLowerCase() + ".");
        }
    }

    private void assertNotReserved(EmployeeChargeEntity charge) throws BusinessException {
        if (applicationRepository.reservedByOtherDrafts(charge.getId(), -1L).signum() > 0) {
            throw new BusinessException("El cargo está incluido en una planilla en borrador. Anúlela o recalcúlela después del cambio.");
        }
    }

    private EmployeeChargeResponse toResponse(EmployeeChargeEntity charge, EmployeeEntity employee) {
        return EmployeeChargeResponse.builder()
                .id(charge.getId())
                .employeeId(charge.getEmployeeId())
                .employeeName(employee == null ? "Empleado #" + charge.getEmployeeId() : fullName(employee))
                .payGroup(employee == null ? null : employee.getPayGroup())
                .sourceType(charge.getSourceType())
                .sourceId(charge.getSourceId())
                .description(charge.getDescription())
                .amount(charge.getAmount())
                .balance(charge.getBalance())
                .settlementMethod(charge.getSettlementMethod())
                .status(charge.getStatus())
                .chargedOn(charge.getChargedOn())
                .decidedAt(charge.getDecidedAt())
                .reservedInDrafts(applicationRepository.reservedByOtherDrafts(charge.getId(), -1L))
                .employeeActive(employee != null && employee.getTerminationDate() == null
                        && "active".equalsIgnoreCase(employee.getStatus()))
                .build();
    }

    static String fullName(EmployeeEntity employee) {
        String first = employee.getFirstName() == null ? "" : employee.getFirstName().trim();
        String last = employee.getLastName() == null ? "" : employee.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Empleado #" + employee.getId() : full;
    }
}
