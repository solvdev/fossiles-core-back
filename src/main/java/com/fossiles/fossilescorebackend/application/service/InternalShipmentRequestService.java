package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.InternalShipmentRequestCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneInternalShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DispatchStockShortageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentEligibilityResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InternalShipmentRequestService {

    private final InternalShipmentRequestRepository requestRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductDistributionService productDistributionService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderCodeService productionOrderCodeService;
    private final OpiVendorShipmentNumberService opiVendorShipmentNumberService;
    private final SmartMaterialRequestService smartMaterialRequestService;
    private final ObjectMapper objectMapper;
    private final SecurityUtil securityUtil;
    private final InternalShipmentRequestAccessGuard accessGuard;

    @Transactional(readOnly = true)
    public List<InternalShipmentRequestResponse> list(String status, String requestType)
            throws BusinessException {
        accessGuard.assertCanListRequests();
        String normalizedStatus = trimToNull(accessGuard.enforceListStatusFilter(status));
        String normalizedType = trimToNull(requestType);
        if (normalizedType != null) {
            normalizedType = ProductDistributionService.normalizeInternalRequestType(normalizedType);
        }
        return requestRepository.findFiltered(normalizedStatus, normalizedType).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InternalShipmentRequestResponse getById(Long id) throws ResourceNotFoundException, BusinessException {
        accessGuard.assertCanListRequests();
        InternalShipmentRequestEntity entity = requestRepository.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException("InternalShipmentRequest", id));
        if (!accessGuard.canViewAccountingWorkspace()
                && !"PENDIENTE".equalsIgnoreCase(safe(entity.getStatus()))) {
            throw new ResourceNotFoundException("InternalShipmentRequest", id);
        }
        return toResponse(entity);
    }

    @Transactional
    public InternalShipmentRequestResponse createRequest(InternalShipmentRequestCreateRequest request)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanCreateRequest();
        return createRequestInternal(request);
    }

    @Transactional
    public InternalShipmentRequestResponse createRequestFromLegacy(StandaloneInternalShipmentRequest request)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanCreateRequest();
        InternalShipmentRequestCreateRequest mapped = InternalShipmentRequestCreateRequest.builder()
                .requestType("PLANILLA")
                .recipientName(request.getRecipientName())
                .recipientPhone(request.getRecipientPhone())
                .recipientTaxId(request.getRecipientTaxId())
                .notes(request.getNotes())
                .documentDate(request.getDocumentDate())
                .products(request.getProducts())
                .build();
        return createRequestInternal(mapped);
    }

    @Transactional
    public InternalShipmentRequestResponse approve(Long id)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveOrReject();
        InternalShipmentRequestEntity entity = requestRepository.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException("InternalShipmentRequest", id));
        if (!"PENDIENTE".equalsIgnoreCase(safe(entity.getStatus()))) {
            throw new BusinessException("Solo se pueden aprobar solicitudes pendientes.");
        }
        List<ProductShipmentRequest.ProductShipmentDetailRequest> products = toProductLines(entity);
        try {
            productDistributionService.validateDispatchStock(products);
        } catch (BusinessException ex) {
            if (entity.getProductionOrderId() != null) {
                ProductionOrderEntity linkedOpi = productionOrderRepository.findById(entity.getProductionOrderId())
                        .orElse(null);
                String opiRef = linkedOpi != null && linkedOpi.getCode() != null
                        ? linkedOpi.getCode()
                        : "OPI #" + entity.getProductionOrderId();
                throw new BusinessException(
                        "Aún no hay stock suficiente en Devoluciones / Bodega PT. "
                                + "Complete la orden " + opiRef
                                + " e ingrese el producto terminado antes de autorizar.\n"
                                + ex.getMessage());
            }
            throw ex;
        }

        ProductShipmentResponse shipment = productDistributionService.dispatchStandaloneInternal(
                entity.getRecipientName(),
                entity.getRecipientPhone(),
                entity.getRecipientTaxId(),
                entity.getNotes(),
                entity.getDocumentDate(),
                entity.getRequestType(),
                entity.getDiscountPercent(),
                entity.getDiscountAmount(),
                products);

        entity.setStatus("APROBADA");
        entity.setReviewedBy(securityUtil.getCurrentUserId());
        entity.setReviewedAt(LocalDateTime.now());
        entity.setProductShipmentId(shipment.getId());
        requestRepository.save(entity);
        return toResponse(entity);
    }

    @Transactional
    public InternalShipmentRequestResponse reject(Long id, String reason)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveOrReject();
        InternalShipmentRequestEntity entity = requestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("InternalShipmentRequest", id));
        if (!"PENDIENTE".equalsIgnoreCase(safe(entity.getStatus()))) {
            throw new BusinessException("Solo se pueden denegar solicitudes pendientes.");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank()) {
            throw new BusinessException("Debe indicar el motivo de denegación.");
        }
        entity.setStatus("RECHAZADA");
        entity.setReviewedBy(securityUtil.getCurrentUserId());
        entity.setReviewedAt(LocalDateTime.now());
        entity.setRejectionReason(normalizedReason);
        requestRepository.save(entity);
        return getById(id);
    }

    @Transactional(readOnly = true)
    public List<ProductShipmentResponse> listExistingEnvi() throws BusinessException {
        accessGuard.assertCanViewExistingEnvi();
        return productDistributionService.listAllInternalEnviShipments();
    }

    @Transactional(readOnly = true)
    public InternalShipmentEligibilityResponse getEmployeePlanillaEligibility(Long employeeId, String month)
            throws ResourceNotFoundException {
        employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
        YearMonth ym = parseMonth(month);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        var existing = requestRepository.findActivePlanillaRequestsForEmployeeInMonth(employeeId, from, to);
        if (existing.isEmpty()) {
            return InternalShipmentEligibilityResponse.builder()
                    .employeeId(employeeId)
                    .month(ym.toString())
                    .eligible(true)
                    .message("Puede crear solicitud planilla para este mes.")
                    .build();
        }
        InternalShipmentRequestEntity first = existing.get(0);
        return InternalShipmentEligibilityResponse.builder()
                .employeeId(employeeId)
                .month(ym.toString())
                .eligible(false)
                .message("Ya tiene solicitud planilla "
                        + first.getStatus().toLowerCase(Locale.ROOT)
                        + " en " + ym.getMonth().name().toLowerCase(Locale.ROOT)
                        + " " + ym.getYear() + ".")
                .existingRequestId(first.getId())
                .existingRequestStatus(first.getStatus())
                .build();
    }

    private InternalShipmentRequestResponse createRequestInternal(InternalShipmentRequestCreateRequest request)
            throws BusinessException, ResourceNotFoundException {
        String requestType = ProductDistributionService.normalizeInternalRequestType(request.getRequestType());
        String recipient = request.getRecipientName() == null ? "" : request.getRecipientName().trim();
        Long employeeId = request.getEmployeeId();
        String recipientPhone = trimToNull(request.getRecipientPhone());
        String recipientTaxId = trimToNull(request.getRecipientTaxId());

        if ("PLANILLA".equals(requestType)) {
            if (employeeId == null) {
                throw new BusinessException("Debe seleccionar un empleado de planilla.");
            }
            EmployeeEntity employee = employeeRepository.findById(employeeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
            recipient = formatEmployeeName(employee);
            if (recipientPhone == null) {
                recipientPhone = trimToNull(employee.getPhone());
            }
            if (recipientTaxId == null) {
                recipientTaxId = trimToNull(employee.getDpi());
            }
            YearMonth month = resolveRequestMonth(request.getDocumentDate());
            assertPlanillaMonthlyLimit(employeeId, month, null);
        } else if (recipient.isBlank()) {
            throw new BusinessException("El nombre del colaborador es obligatorio.");
        }
        ProductDistributionService.validateDefectosDiscount(
                requestType, request.getDiscountPercent(), request.getDiscountAmount());

        java.math.BigDecimal discountPercent = null;
        java.math.BigDecimal discountAmount = null;
        if ("DEFECTOS".equals(requestType)) {
            if (request.getDiscountAmount() != null
                    && request.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                discountAmount = request.getDiscountAmount();
            } else if (request.getDiscountPercent() != null
                    && request.getDiscountPercent().compareTo(java.math.BigDecimal.ZERO) > 0) {
                discountPercent = request.getDiscountPercent();
            }
        }

        InternalShipmentRequestEntity entity = InternalShipmentRequestEntity.builder()
                .status("PENDIENTE")
                .requestType(requestType)
                .employeeId(employeeId)
                .recipientName(recipient)
                .recipientPhone(recipientPhone)
                .recipientTaxId(recipientTaxId)
                .notes(trimToNull(request.getNotes()))
                .documentDate(trimToNull(request.getDocumentDate()))
                .discountPercent(discountPercent)
                .discountAmount(discountAmount)
                .requestedBy(securityUtil.getCurrentUserId())
                .requestedAt(LocalDateTime.now())
                .lines(new ArrayList<>())
                .build();

        int lineOrder = 0;
        for (ProductShipmentRequest.ProductShipmentDetailRequest line : request.getProducts()) {
            if (line == null || line.getProductId() == null) {
                continue;
            }
            lineOrder++;
            InternalShipmentRequestLineEntity lineEntity = InternalShipmentRequestLineEntity.builder()
                    .request(entity)
                    .lineOrder(lineOrder)
                    .productId(line.getProductId())
                    .colorId(line.getColorId())
                    .size(line.getSize())
                    .quantity(line.getQuantity())
                    .build();
            entity.getLines().add(lineEntity);
        }
        if (entity.getLines().isEmpty()) {
            throw new BusinessException("Debe incluir al menos un producto con cantidad.");
        }
        InternalShipmentRequestEntity saved = requestRepository.save(entity);

        List<DispatchStockShortageResponse> shortages =
                productDistributionService.computeDispatchStockShortages(request.getProducts());
        if (!shortages.isEmpty()) {
            Long productionOrderId = createOpiForShortages(saved, shortages);
            saved.setProductionOrderId(productionOrderId);
            saved = requestRepository.save(saved);
        }

        return toResponse(saved);
    }

    private Long createOpiForShortages(
            InternalShipmentRequestEntity request,
            List<DispatchStockShortageResponse> shortages) throws BusinessException {
        if (shortages == null || shortages.isEmpty()) {
            return null;
        }
        String orderCode = productionOrderCodeService.generateNextCode("INTERNA");
        String recipient = request.getRecipientName() == null ? "Colaborador" : request.getRecipientName().trim();
        String requestTypeLabel = "PLANILLA".equalsIgnoreCase(safe(request.getRequestType()))
                ? "Planilla"
                : "Defectos";
        ProductionOrderEntity order = ProductionOrderEntity.builder()
                .code(orderCode)
                .orderType("INTERNA")
                .customerName(recipient)
                .startDate(LocalDate.now())
                .deliveryDate(LocalDate.now())
                .observations("OPI generada por faltante de stock PT/Devoluciones. "
                        + "Solicitud ENVI #" + request.getId() + " (" + requestTypeLabel + "). "
                        + "Colaborador: " + recipient + ".")
                .status("PENDING")
                .createdBy(securityUtil.getCurrentUserId())
                .build();
        opiVendorShipmentNumberService.assignIfMissing(order);
        ProductionOrderEntity savedOrder = productionOrderRepository.save(order);

        for (DispatchStockShortageResponse shortage : shortages) {
            int qty = shortage.getShortageQuantity() == null
                    ? 0
                    : shortage.getShortageQuantity().intValue();
            if (qty <= 0) {
                continue;
            }
            String sizeLabel = shortage.getSize();
            String sizesData = buildProductionItemSizesData(sizeLabel, qty);
            ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                    .productionOrderId(savedOrder.getId())
                    .productId(shortage.getProductId())
                    .colorId(shortage.getColorId())
                    .quantity(qty)
                    .warehouseReceivedQty(0)
                    .sizesData(sizesData)
                    .observations("Faltante solicitud ENVI #" + request.getId())
                    .createdBy(securityUtil.getCurrentUserId())
                    .build();
            productionOrderItemRepository.save(item);
            try {
                smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                        savedOrder.getId(),
                        shortage.getProductId(),
                        BigDecimal.valueOf(qty));
            } catch (Exception ignored) {
                // No bloquear la OPI si falla la solicitud automática de materiales.
            }
        }
        return savedOrder.getId();
    }

    private String buildProductionItemSizesData(String sizeLabel, int quantity) {
        if (sizeLabel == null || sizeLabel.isBlank() || quantity <= 0) {
            return null;
        }
        try {
            Map<String, Integer> sizes = new LinkedHashMap<>();
            sizes.put(sizeLabel.trim().toUpperCase(Locale.ROOT), quantity);
            return objectMapper.writeValueAsString(sizes);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ProductShipmentRequest.ProductShipmentDetailRequest> toProductLines(
            InternalShipmentRequestEntity entity) {
        return entity.getLines().stream()
                .map(line -> ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(line.getProductId())
                        .colorId(line.getColorId())
                        .size(line.getSize())
                        .quantity(line.getQuantity())
                        .build())
                .collect(Collectors.toList());
    }

    private InternalShipmentRequestResponse toResponse(InternalShipmentRequestEntity entity) {
        String shipmentNumber = null;
        if (entity.getProductShipmentId() != null) {
            shipmentNumber = shipmentRepository.findById(entity.getProductShipmentId())
                    .map(ProductShipmentEntity::getShipmentNumber)
                    .orElse(null);
        }
        String productionOrderCode = null;
        if (entity.getProductionOrderId() != null) {
            productionOrderCode = productionOrderRepository.findById(entity.getProductionOrderId())
                    .map(ProductionOrderEntity::getCode)
                    .orElse(null);
        }
        List<InternalShipmentRequestResponse.LineResponse> lines = entity.getLines() == null
                ? List.of()
                : entity.getLines().stream()
                .map(this::toLineResponse)
                .collect(Collectors.toList());
        return InternalShipmentRequestResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus())
                .requestType(entity.getRequestType())
                .employeeId(entity.getEmployeeId())
                .recipientName(entity.getRecipientName())
                .recipientPhone(entity.getRecipientPhone())
                .recipientTaxId(entity.getRecipientTaxId())
                .notes(entity.getNotes())
                .documentDate(entity.getDocumentDate())
                .discountPercent(entity.getDiscountPercent())
                .discountAmount(entity.getDiscountAmount())
                .requestedBy(entity.getRequestedBy())
                .requestedAt(entity.getRequestedAt())
                .reviewedBy(entity.getReviewedBy())
                .reviewedAt(entity.getReviewedAt())
                .rejectionReason(entity.getRejectionReason())
                .productShipmentId(entity.getProductShipmentId())
                .shipmentNumber(shipmentNumber)
                .productionOrderId(entity.getProductionOrderId())
                .productionOrderCode(productionOrderCode)
                .lines(lines)
                .build();
    }

    private InternalShipmentRequestResponse.LineResponse toLineResponse(InternalShipmentRequestLineEntity line) {
        ProductEntity product = productRepository.findById(line.getProductId()).orElse(null);
        ColorEntity color = line.getColorId() == null ? null : colorRepository.findById(line.getColorId()).orElse(null);
        return InternalShipmentRequestResponse.LineResponse.builder()
                .id(line.getId())
                .lineOrder(line.getLineOrder())
                .productId(line.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(line.getColorId())
                .colorName(color != null ? color.getName() : null)
                .size(line.getSize())
                .quantity(line.getQuantity())
                .catalogPrice(product != null ? product.getSalePrice() : null)
                .build();
    }

    private void assertPlanillaMonthlyLimit(Long employeeId, YearMonth month, Long excludeRequestId)
            throws BusinessException {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        var existing = requestRepository.findActivePlanillaRequestsForEmployeeInMonth(employeeId, from, to);
        for (InternalShipmentRequestEntity row : existing) {
            if (excludeRequestId != null && excludeRequestId.equals(row.getId())) {
                continue;
            }
            throw new BusinessException(
                    "El empleado ya tiene una solicitud planilla "
                            + row.getStatus().toLowerCase(Locale.ROOT)
                            + " en " + month.getMonth().name().toLowerCase(Locale.ROOT)
                            + " " + month.getYear() + ".");
        }
    }

    private YearMonth resolveRequestMonth(String documentDate) {
        if (documentDate != null && !documentDate.isBlank()) {
            try {
                return YearMonth.from(LocalDate.parse(documentDate.trim()));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return YearMonth.now();
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        return YearMonth.parse(month.trim(), DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private static String formatEmployeeName(EmployeeEntity employee) {
        String first = employee.getFirstName() == null ? "" : employee.getFirstName().trim();
        String last = employee.getLastName() == null ? "" : employee.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? "Empleado #" + employee.getId() : full;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
