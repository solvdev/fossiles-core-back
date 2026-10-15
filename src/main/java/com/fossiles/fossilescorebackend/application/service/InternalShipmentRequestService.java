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
import java.util.Objects;
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
    private final InternalShipmentRequestSlipService slipService;
    private final SmartMaterialRequestService smartMaterialRequestService;
    private final ProductionAutoPlannerService productionAutoPlannerService;
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
        if ("OPI".equalsIgnoreCase(safe(entity.getRequestType()))) {
            throw new BusinessException(
                    "Las solicitudes de producción OPI se autorizan con «Autorizar producción», no con envío.");
        }
        List<ProductShipmentRequest.ProductShipmentDetailRequest> products = toProductLines(entity);

        // El envío solo se autoriza cuando hay stock en Devoluciones / Bodega PT. Si falta, Contabilidad
        // primero autoriza la producción del faltante (authorizeProduction) y espera el ingreso a Bodega PT.
        List<DispatchStockShortageResponse> shortages =
                productDistributionService.computeDispatchStockShortages(products);
        ProductionOrderEntity activeOpi = findActiveLinkedOpi(entity);
        if (!shortages.isEmpty()) {
            String detail = formatShortageSummary(shortages);
            if (activeOpi == null) {
                throw new BusinessException(
                        "No hay stock suficiente en Devoluciones / Bodega PT. "
                                + "Autorice primero la producción del faltante.\n" + detail);
            }
            String opiRef = activeOpi.getCode() != null ? activeOpi.getCode() : "OPI #" + activeOpi.getId();
            if ("DRAFT".equalsIgnoreCase(safe(activeOpi.getStatus()))) {
                throw new BusinessException(
                        "Debe autorizar la producción de " + opiRef + " antes de autorizar el envío.\n" + detail);
            }
            throw new BusinessException(
                    "Aún no hay stock suficiente en Devoluciones / Bodega PT. Complete la orden " + opiRef
                            + " e ingrese el producto terminado antes de autorizar el envío.\n" + detail);
        }
        if (activeOpi != null && "DRAFT".equalsIgnoreCase(safe(activeOpi.getStatus()))) {
            // Ya hay stock: la OPI en borrador por faltante quedó innecesaria.
            activeOpi.setStatus("CANCELLED");
            productionOrderRepository.save(activeOpi);
            entity.setProductionOrderId(null);
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
        cancelLinkedDraftOpi(entity);
        requestRepository.save(entity);
        return getById(id);
    }

    @Transactional
    public InternalShipmentRequestResponse authorizeProduction(Long id)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveOrReject();
        InternalShipmentRequestEntity entity = requestRepository.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException("InternalShipmentRequest", id));
        String status = safe(entity.getStatus());
        if (!"PENDIENTE".equalsIgnoreCase(status) && !"APROBADA".equalsIgnoreCase(status)) {
            throw new BusinessException("Solo se puede autorizar la producción de solicitudes pendientes o aprobadas.");
        }
        if (findActiveLinkedOpi(entity) == null) {
            // Sin OPI vigente: solo se genera (por el faltante) cuando la solicitud sigue pendiente.
            if (!"PENDIENTE".equalsIgnoreCase(status)
                    || "OPI".equalsIgnoreCase(safe(entity.getRequestType()))) {
                throw new BusinessException("Esta solicitud no tiene una OPI vinculada.");
            }
            List<DispatchStockShortageResponse> shortages =
                    productDistributionService.computeDispatchStockShortages(toProductLines(entity));
            if (shortages.isEmpty()) {
                throw new BusinessException(
                        "Hay stock suficiente en Devoluciones / Bodega PT; no hace falta producir. "
                                + "Autorice el envío.");
            }
            entity.setProductionOrderId(createOpiForShortages(entity, shortages));
            entity.setOpiAuthorizedBy(null);
            entity.setOpiAuthorizedAt(null);
            requestRepository.save(entity);
        }
        if (entity.getOpiAuthorizedAt() != null) {
            throw new BusinessException("La producción OPI ya fue autorizada para esta solicitud.");
        }
        ProductionOrderEntity order = productionOrderRepository.findById(entity.getProductionOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", entity.getProductionOrderId()));
        if (!"DRAFT".equalsIgnoreCase(safe(order.getStatus()))) {
            throw new BusinessException("La OPI vinculada no está en borrador o ya fue autorizada.");
        }

        order.setStatus("PENDING");
        opiVendorShipmentNumberService.assignIfMissing(order);
        productionOrderRepository.save(order);
        generateMaterialsForProductionOrder(order.getId());
        productionAutoPlannerService.planQuietly(order.getId());

        entity.setOpiAuthorizedBy(securityUtil.getCurrentUserId());
        entity.setOpiAuthorizedAt(LocalDateTime.now());
        if ("OPI".equalsIgnoreCase(safe(entity.getRequestType()))) {
            entity.setStatus("APROBADA");
            entity.setReviewedBy(securityUtil.getCurrentUserId());
            entity.setReviewedAt(LocalDateTime.now());
        }
        requestRepository.save(entity);
        return toResponse(entity);
    }

    @Transactional
    public InternalShipmentRequestResponse generateOpi(Long id)
            throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanApproveOrReject();
        InternalShipmentRequestEntity entity = requestRepository.findByIdWithLines(id)
                .orElseThrow(() -> new ResourceNotFoundException("InternalShipmentRequest", id));
        assertCanGenerateOpi(entity, null);
        Long productionOrderId = createOpiFromRequestLines(entity);
        entity.setProductionOrderId(productionOrderId);
        requestRepository.save(entity);
        return toResponse(entity);
    }

    @Transactional
    public InternalShipmentRequestResponse generateOpiByShipment(Long shipmentId)
            throws BusinessException, ResourceNotFoundException {
        if (shipmentId == null) {
            throw new BusinessException("Debe indicar el envío.");
        }
        Long requestId = requestRepository.findByProductShipmentId(shipmentId)
                .map(InternalShipmentRequestEntity::getId)
                .orElseThrow(() -> new BusinessException(
                        "Este ENVI no tiene una solicitud interna ligada. No se puede generar OPI."));
        return generateOpi(requestId);
    }

    /**
     * Crea solicitud tipo OPI vinculada a una orden INTERNA recién registrada (estado DRAFT).
     */
    @Transactional
    public InternalShipmentRequestEntity createRequestForManualOpi(
            ProductionOrderEntity order,
            List<ProductionOrderItemEntity> items) throws BusinessException {
        if (order == null || order.getId() == null) {
            throw new BusinessException("Orden de producción inválida para solicitud OPI.");
        }
        String recipient = order.getCustomerName() == null || order.getCustomerName().isBlank()
                ? "Producción interna"
                : order.getCustomerName().trim();
        InternalShipmentRequestEntity entity = InternalShipmentRequestEntity.builder()
                .status("PENDIENTE")
                .requestType("OPI")
                .recipientName(recipient)
                .notes(trimToNull(order.getObservations()))
                .documentDate(order.getStartDate() != null ? order.getStartDate().toString() : null)
                .productionOrderId(order.getId())
                .requestedBy(securityUtil.getCurrentUserId())
                .requestedAt(LocalDateTime.now())
                .lines(new ArrayList<>())
                .build();
        entity.getLines().addAll(buildRequestLinesFromProductionItems(entity, items));
        if (entity.getLines().isEmpty()) {
            throw new BusinessException("La OPI debe tener al menos un producto para crear la solicitud.");
        }
        return requestRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ProductShipmentResponse> listExistingEnvi() throws BusinessException {
        accessGuard.assertCanViewExistingEnvi();
        List<ProductShipmentResponse> shipments = productDistributionService.listAllInternalEnviShipments();
        List<Long> shipmentIds = shipments.stream()
                .map(ProductShipmentResponse::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (shipmentIds.isEmpty()) {
            return shipments;
        }
        Map<Long, InternalShipmentRequestEntity> byShipmentId = requestRepository
                .findByProductShipmentIdIn(shipmentIds).stream()
                .filter(req -> req.getProductShipmentId() != null)
                .collect(Collectors.toMap(
                        InternalShipmentRequestEntity::getProductShipmentId,
                        req -> req,
                        (first, ignored) -> first));
        for (ProductShipmentResponse shipment : shipments) {
            InternalShipmentRequestEntity linked = byShipmentId.get(shipment.getId());
            if (linked == null) {
                shipment.setCanGenerateOpi(false);
                continue;
            }
            shipment.setInternalShipmentRequestId(linked.getId());
            shipment.setCanGenerateOpi(canGenerateOpi(linked, shipment.getProductionOrderId()));
        }
        return shipments;
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
        if ("OPI".equals(requestType)) {
            throw new BusinessException("Las solicitudes OPI se crean automáticamente al registrar una orden INTERNA.");
        }

        String rawSlip = request.getSlipNumber();
        if (rawSlip == null || rawSlip.isBlank()) {
            throw new BusinessException("El número de boleta física de solicitud (BLS) es obligatorio.");
        }
        String normalizedSlip = InternalShipmentRequestSlipService.normalizeSlipNumber(rawSlip);

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
                .slipNumber(normalizedSlip)
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
        slipService.validateAndUseSlip(normalizedSlip, saved.getId());

        // La OPI por faltante de stock (si aplica) se genera hasta que Contabilidad
        // autorice el envío (ver approve()); mientras esté PENDIENTE no debe existir OP.
        return toResponse(saved);
    }

    private Long createOpiForShortages(
            InternalShipmentRequestEntity request,
            List<DispatchStockShortageResponse> shortages) throws BusinessException {
        if (shortages == null || shortages.isEmpty()) {
            return null;
        }
        String requestTypeLabel = "PLANILLA".equalsIgnoreCase(safe(request.getRequestType()))
                ? "Planilla"
                : "Defectos";
        String recipient = request.getRecipientName() == null ? "Colaborador" : request.getRecipientName().trim();
        ProductionOrderEntity savedOrder = createDraftInternaOrder(
                request,
                "OPI generada por faltante de stock PT/Devoluciones. "
                        + "Solicitud ENVI #" + request.getId() + " (" + requestTypeLabel + "). "
                        + "Colaborador: " + recipient + ".");

        for (DispatchStockShortageResponse shortage : shortages) {
            int qty = shortage.getShortageQuantity() == null
                    ? 0
                    : shortage.getShortageQuantity().intValue();
            if (qty <= 0) {
                continue;
            }
            saveOpiItem(
                    savedOrder.getId(),
                    shortage.getProductId(),
                    shortage.getColorId(),
                    shortage.getSize(),
                    qty,
                    "Faltante solicitud ENVI #" + request.getId());
        }
        return savedOrder.getId();
    }

    private Long createOpiFromRequestLines(InternalShipmentRequestEntity request) throws BusinessException {
        List<InternalShipmentRequestLineEntity> lines = request.getLines() == null
                ? List.of()
                : request.getLines();
        List<InternalShipmentRequestLineEntity> validLines = lines.stream()
                .filter(line -> line != null && line.getProductId() != null)
                .filter(line -> line.getQuantity() != null && line.getQuantity().intValue() > 0)
                .collect(Collectors.toList());
        if (validLines.isEmpty()) {
            throw new BusinessException("La solicitud no tiene cantidades válidas para generar la OPI.");
        }
        String requestTypeLabel = "PLANILLA".equalsIgnoreCase(safe(request.getRequestType()))
                ? "Planilla"
                : "Defectos";
        String recipient = request.getRecipientName() == null ? "Colaborador" : request.getRecipientName().trim();
        String enviRef = request.getProductShipmentId() == null
                ? ""
                : shipmentRepository.findById(request.getProductShipmentId())
                        .map(ProductShipmentEntity::getShipmentNumber)
                        .filter(number -> number != null && !number.isBlank())
                        .map(number -> " Documento " + number + ".")
                        .orElse("");
        ProductionOrderEntity savedOrder = createDraftInternaOrder(
                request,
                "OPI generada desde envío interno autorizado. "
                        + "Solicitud ENVI #" + request.getId() + " (" + requestTypeLabel + "). "
                        + "Colaborador: " + recipient + "." + enviRef);
        for (InternalShipmentRequestLineEntity line : validLines) {
            int qty = line.getQuantity().intValue();
            saveOpiItem(
                    savedOrder.getId(),
                    line.getProductId(),
                    line.getColorId(),
                    line.getSize(),
                    qty,
                    "Reposición solicitud ENVI #" + request.getId());
        }
        return savedOrder.getId();
    }

    private ProductionOrderEntity createDraftInternaOrder(
            InternalShipmentRequestEntity request,
            String observations) throws BusinessException {
        String orderCode = productionOrderCodeService.generateNextCode("INTERNA");
        String recipient = request.getRecipientName() == null ? "Colaborador" : request.getRecipientName().trim();
        ProductionOrderEntity order = ProductionOrderEntity.builder()
                .code(orderCode)
                .orderType("INTERNA")
                .customerName(recipient)
                .startDate(LocalDate.now())
                .deliveryDate(LocalDate.now())
                .observations(observations)
                .status("DRAFT")
                .createdBy(securityUtil.getCurrentUserId())
                .build();
        return productionOrderRepository.save(order);
    }

    private void saveOpiItem(
            Long orderId,
            Long productId,
            Long colorId,
            String size,
            int qty,
            String observations) {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .productionOrderId(orderId)
                .productId(productId)
                .colorId(colorId)
                .quantity(qty)
                .warehouseReceivedQty(0)
                .sizesData(buildProductionItemSizesData(size, qty))
                .observations(observations)
                .createdBy(securityUtil.getCurrentUserId())
                .build();
        productionOrderItemRepository.save(item);
    }

    private void assertCanGenerateOpi(InternalShipmentRequestEntity entity, Long shipmentProductionOrderId)
            throws BusinessException {
        Long shipmentPoId = shipmentProductionOrderId;
        if (shipmentPoId == null && entity.getProductShipmentId() != null) {
            shipmentPoId = shipmentRepository.findById(entity.getProductShipmentId())
                    .map(ProductShipmentEntity::getProductionOrderId)
                    .orElse(null);
        }
        if (canGenerateOpi(entity, shipmentPoId)) {
            return;
        }
        if (entity.getProductionOrderId() != null) {
            throw new BusinessException("Esta solicitud ya tiene una OPI vinculada.");
        }
        if (shipmentPoId != null) {
            throw new BusinessException("Este ENVI ya está asociado a una orden de producción.");
        }
        throw new BusinessException("No se puede generar OPI para esta solicitud.");
    }

    private boolean canGenerateOpi(InternalShipmentRequestEntity entity, Long shipmentProductionOrderId) {
        if (entity == null) {
            return false;
        }
        if (!"APROBADA".equalsIgnoreCase(safe(entity.getStatus()))) {
            return false;
        }
        if (entity.getProductShipmentId() == null) {
            return false;
        }
        if (entity.getProductionOrderId() != null) {
            return false;
        }
        if ("OPI".equalsIgnoreCase(safe(entity.getRequestType()))) {
            return false;
        }
        return shipmentProductionOrderId == null;
    }

    private void generateMaterialsForProductionOrder(Long productionOrderId) {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        for (ProductionOrderItemEntity item : items) {
            if (item.getProductId() == null) {
                continue;
            }
            int totalQuantity = item.getQuantity() != null ? item.getQuantity() : 0;
            if (item.getSizesData() != null && !item.getSizesData().isBlank()) {
                try {
                    Map<String, Integer> sizes = objectMapper.readValue(
                            item.getSizesData(),
                            objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Integer.class));
                    int fromSizes = sizes.values().stream().mapToInt(v -> v != null ? Math.max(v, 0) : 0).sum();
                    if (fromSizes > 0) {
                        totalQuantity = fromSizes;
                    }
                } catch (Exception ignored) {
                    // usar quantity base
                }
            }
            if (totalQuantity <= 0) {
                continue;
            }
            try {
                smartMaterialRequestService.checkAndGenerateRequestsForProductionOrder(
                        productionOrderId,
                        item.getProductId(),
                        BigDecimal.valueOf(totalQuantity));
            } catch (Exception ignored) {
                // No bloquear autorización si falla solicitud automática de materiales.
            }
        }
    }

    /** OPI vinculada que sigue vigente (existe y no está cancelada); null en otro caso. */
    private ProductionOrderEntity findActiveLinkedOpi(InternalShipmentRequestEntity entity) {
        if (entity.getProductionOrderId() == null) {
            return null;
        }
        ProductionOrderEntity order = productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null);
        if (order == null || "CANCELLED".equalsIgnoreCase(safe(order.getStatus()))) {
            return null;
        }
        return order;
    }

    private String formatShortageSummary(List<DispatchStockShortageResponse> shortages) {
        return shortages.stream()
                .map(s -> "• " + (s.getProductCode() != null ? s.getProductCode() + " - " : "")
                        + s.getProductName()
                        + (s.getSize() != null && !s.getSize().isBlank() ? " (" + s.getSize() + ")" : "")
                        + ": disponible " + s.getAvailableQuantity()
                        + ", requerido " + s.getRequiredQuantity())
                .collect(Collectors.joining("\n"));
    }

    private static String stockKey(Long productId, Long colorId, String size) {
        return productId + "|" + colorId + "|" + (size == null ? "" : size.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Calcula el estado de stock de una solicitud pendiente de envío. Devuelve null si no aplica
     * (ya resuelta, tipo OPI) o si no se pudo calcular, para no romper el listado.
     */
    private StockSnapshot computeStockSnapshot(
            InternalShipmentRequestEntity entity, ProductionOrderEntity linkedOrder) {
        if (!"PENDIENTE".equalsIgnoreCase(safe(entity.getStatus()))
                || "OPI".equalsIgnoreCase(safe(entity.getRequestType()))
                || entity.getLines() == null || entity.getLines().isEmpty()) {
            return null;
        }
        List<DispatchStockShortageResponse> stockLines;
        try {
            stockLines = productDistributionService.computeDispatchStockLines(toProductLines(entity));
        } catch (Exception ex) {
            return null;
        }
        Map<String, DispatchStockShortageResponse> byKey = new LinkedHashMap<>();
        BigDecimal shortageTotal = BigDecimal.ZERO;
        for (DispatchStockShortageResponse row : stockLines) {
            byKey.put(stockKey(row.getProductId(), row.getColorId(), row.getSize()), row);
            if (row.getShortageQuantity() != null) {
                shortageTotal = shortageTotal.add(row.getShortageQuantity());
            }
        }
        boolean opiActive = linkedOrder != null && !"CANCELLED".equalsIgnoreCase(safe(linkedOrder.getStatus()));
        boolean opiAuthorized = opiActive
                && (entity.getOpiAuthorizedAt() != null || !"DRAFT".equalsIgnoreCase(safe(linkedOrder.getStatus())));
        String status;
        String nextAction;
        if (shortageTotal.signum() <= 0) {
            status = "DISPONIBLE";
            nextAction = "AUTORIZAR_ENVIO";
        } else if (opiAuthorized) {
            status = "EN_PRODUCCION";
            nextAction = "ESPERAR_PRODUCCION";
        } else {
            status = "SIN_STOCK";
            nextAction = "AUTORIZAR_PRODUCCION";
        }
        return new StockSnapshot(status, nextAction, shortageTotal, byKey);
    }

    private record StockSnapshot(
            String status,
            String nextAction,
            BigDecimal shortageTotal,
            Map<String, DispatchStockShortageResponse> byKey) {
    }

    private void assertLinkedOpiProductionAuthorized(InternalShipmentRequestEntity entity)
            throws BusinessException {
        if (entity.getProductionOrderId() == null) {
            return;
        }
        ProductionOrderEntity linkedOpi = productionOrderRepository.findById(entity.getProductionOrderId())
                .orElse(null);
        if (linkedOpi == null) {
            return;
        }
        if ("DRAFT".equalsIgnoreCase(safe(linkedOpi.getStatus()))) {
            String opiRef = linkedOpi.getCode() != null ? linkedOpi.getCode() : "OPI #" + linkedOpi.getId();
            throw new BusinessException(
                    "Debe autorizar la producción de " + opiRef + " antes de autorizar el envío.");
        }
    }

    private void cancelLinkedDraftOpi(InternalShipmentRequestEntity entity) {
        if (entity.getProductionOrderId() == null) {
            return;
        }
        productionOrderRepository.findById(entity.getProductionOrderId()).ifPresent(order -> {
            if ("DRAFT".equalsIgnoreCase(safe(order.getStatus()))) {
                order.setStatus("CANCELLED");
                productionOrderRepository.save(order);
            }
        });
    }

    private List<InternalShipmentRequestLineEntity> buildRequestLinesFromProductionItems(
            InternalShipmentRequestEntity request,
            List<ProductionOrderItemEntity> items) {
        List<InternalShipmentRequestLineEntity> lines = new ArrayList<>();
        int lineOrder = 0;
        for (ProductionOrderItemEntity item : items) {
            if (item == null || item.getProductId() == null) {
                continue;
            }
            if (item.getSizesData() != null && !item.getSizesData().isBlank()) {
                try {
                    Map<String, Integer> sizes = objectMapper.readValue(
                            item.getSizesData(),
                            objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Integer.class));
                    for (Map.Entry<String, Integer> entry : sizes.entrySet()) {
                        int qty = entry.getValue() != null ? entry.getValue() : 0;
                        if (qty <= 0) {
                            continue;
                        }
                        lineOrder++;
                        lines.add(InternalShipmentRequestLineEntity.builder()
                                .request(request)
                                .lineOrder(lineOrder)
                                .productId(item.getProductId())
                                .colorId(item.getColorId())
                                .size(entry.getKey())
                                .quantity(BigDecimal.valueOf(qty))
                                .build());
                    }
                    continue;
                } catch (Exception ignored) {
                    // fallback a quantity simple
                }
            }
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            if (qty <= 0) {
                continue;
            }
            lineOrder++;
            lines.add(InternalShipmentRequestLineEntity.builder()
                    .request(request)
                    .lineOrder(lineOrder)
                    .productId(item.getProductId())
                    .colorId(item.getColorId())
                    .quantity(BigDecimal.valueOf(qty))
                    .build());
        }
        return lines;
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
        Long shipmentProductionOrderId = null;
        if (entity.getProductShipmentId() != null) {
            ProductShipmentEntity shipment = shipmentRepository.findById(entity.getProductShipmentId()).orElse(null);
            if (shipment != null) {
                shipmentNumber = shipment.getShipmentNumber();
                shipmentProductionOrderId = shipment.getProductionOrderId();
            }
        }
        String productionOrderCode = null;
        String productionOrderStatus = null;
        if (entity.getProductionOrderId() != null) {
            ProductionOrderEntity linkedOrder = productionOrderRepository.findById(entity.getProductionOrderId())
                    .orElse(null);
            if (linkedOrder != null) {
                productionOrderCode = linkedOrder.getCode();
                productionOrderStatus = linkedOrder.getStatus();
            }
        }
        ProductionOrderEntity linkedForStock = entity.getProductionOrderId() == null
                ? null
                : productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null);
        StockSnapshot stock = computeStockSnapshot(entity, linkedForStock);
        List<InternalShipmentRequestResponse.LineResponse> lines = entity.getLines() == null
                ? List.of()
                : entity.getLines().stream()
                .map(line -> toLineResponse(line, stock))
                .collect(Collectors.toList());
        return InternalShipmentRequestResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus())
                .requestType(entity.getRequestType())
                .employeeId(entity.getEmployeeId())
                .recipientName(entity.getRecipientName())
                .recipientPhone(entity.getRecipientPhone())
                .recipientTaxId(entity.getRecipientTaxId())
                .slipNumber(entity.getSlipNumber())
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
                .productionOrderStatus(productionOrderStatus)
                .canGenerateOpi(canGenerateOpi(entity, shipmentProductionOrderId))
                .opiAuthorizedBy(entity.getOpiAuthorizedBy())
                .opiAuthorizedAt(entity.getOpiAuthorizedAt())
                .stockStatus(stock != null ? stock.status() : null)
                .stockShortageTotal(stock != null ? stock.shortageTotal() : null)
                .nextAction(stock != null ? stock.nextAction() : null)
                .lines(lines)
                .build();
    }

    private InternalShipmentRequestResponse.LineResponse toLineResponse(
            InternalShipmentRequestLineEntity line, StockSnapshot stock) {
        DispatchStockShortageResponse stockRow = stock == null
                ? null
                : stock.byKey().get(stockKey(line.getProductId(), line.getColorId(), line.getSize()));
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
                .availableQuantity(stockRow != null ? stockRow.getAvailableQuantity() : null)
                .shortageQuantity(stockRow != null ? stockRow.getShortageQuantity() : null)
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
