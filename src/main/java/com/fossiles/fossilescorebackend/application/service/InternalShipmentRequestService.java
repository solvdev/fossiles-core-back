package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.InternalShipmentRequestCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneInternalShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InternalShipmentRequestService {

    private final InternalShipmentRequestRepository requestRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductDistributionService productDistributionService;
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
        productDistributionService.validateDispatchStock(products);

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

    private InternalShipmentRequestResponse createRequestInternal(InternalShipmentRequestCreateRequest request)
            throws BusinessException, ResourceNotFoundException {
        String recipient = request.getRecipientName() == null ? "" : request.getRecipientName().trim();
        if (recipient.isBlank()) {
            throw new BusinessException("El nombre del colaborador es obligatorio.");
        }
        String requestType = ProductDistributionService.normalizeInternalRequestType(request.getRequestType());
        ProductDistributionService.validateDefectosDiscount(
                requestType, request.getDiscountPercent(), request.getDiscountAmount());
        productDistributionService.validateDispatchStock(request.getProducts());

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
                .recipientName(recipient)
                .recipientPhone(trimToNull(request.getRecipientPhone()))
                .recipientTaxId(trimToNull(request.getRecipientTaxId()))
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
        return toResponse(saved);
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
        List<InternalShipmentRequestResponse.LineResponse> lines = entity.getLines() == null
                ? List.of()
                : entity.getLines().stream()
                .map(this::toLineResponse)
                .collect(Collectors.toList());
        return InternalShipmentRequestResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus())
                .requestType(entity.getRequestType())
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
                .build();
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
