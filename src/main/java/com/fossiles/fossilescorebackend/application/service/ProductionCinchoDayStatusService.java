package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CinchoDayStatusUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CinchoDayWorkStatusUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CinchoDayStatusDayResponse;
import com.fossiles.fossilescorebackend.application.dto.response.CinchoDayStatusEntryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionCinchoDayStatusEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionCinchoDayStatusRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductionCinchoDayStatusService {

    private static final String VENTA_EN_LINEA = "VENTA_EN_LINEA";
    private static final String CLIENTE_KIOSKO = "CLIENTE_KIOSKO";

    private final ProductionCinchoDayStatusRepository statusRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final SecurityUtil securityUtil;

    public CinchoDayStatusDayResponse getStatusesForDate(LocalDate workDate) throws BusinessException {
        if (workDate == null) {
            throw new BusinessException("La fecha de trabajo es obligatoria.");
        }
        List<CinchoDayStatusEntryResponse> statuses = statusRepository.findByWorkDate(workDate).stream()
                .map(this::toEntryResponse)
                .collect(Collectors.toList());
        return CinchoDayStatusDayResponse.builder()
                .workDate(workDate)
                .statuses(statuses)
                .build();
    }

    @Transactional
    public CinchoDayStatusEntryResponse setDelivered(CinchoDayStatusUpdateRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request.getWorkDate() == null) {
            throw new BusinessException("La fecha de trabajo es obligatoria.");
        }
        validateEligibleLine(request.getProductionOrderId(), request.getProductionOrderItemId());

        boolean delivered = Boolean.TRUE.equals(request.getDelivered());
        Long userId = securityUtil.getCurrentUserId();
        LocalDateTime now = delivered ? LocalDateTime.now() : null;

        ProductionCinchoDayStatusEntity entity = findOrCreate(
                request.getWorkDate(),
                request.getProductionOrderId(),
                request.getProductionOrderItemId());

        entity.setDelivered(delivered);
        entity.setDeliveredAt(now);
        entity.setDeliveredBy(delivered ? userId : null);

        return toEntryResponse(statusRepository.save(entity));
    }

    @Transactional
    public CinchoDayStatusEntryResponse setWorkStatus(CinchoDayWorkStatusUpdateRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request.getWorkDate() == null) {
            throw new BusinessException("La fecha de trabajo es obligatoria.");
        }
        validateEligibleLine(request.getProductionOrderId(), request.getProductionOrderItemId());

        String workStatus = normalizeWorkStatus(request.getWorkStatus());

        ProductionCinchoDayStatusEntity entity = findOrCreate(
                request.getWorkDate(),
                request.getProductionOrderId(),
                request.getProductionOrderItemId());

        entity.setWorkStatus(workStatus);

        return toEntryResponse(statusRepository.save(entity));
    }

    private ProductionCinchoDayStatusEntity findOrCreate(
            LocalDate workDate,
            Long productionOrderId,
            Long productionOrderItemId) {
        return statusRepository
                .findByWorkDateAndProductionOrderIdAndProductionOrderItemId(
                        workDate, productionOrderId, productionOrderItemId)
                .orElseGet(() -> ProductionCinchoDayStatusEntity.builder()
                        .workDate(workDate)
                        .productionOrderId(productionOrderId)
                        .productionOrderItemId(productionOrderItemId)
                        .delivered(false)
                        .workStatus(CinchoProductUtils.WORK_STATUS_PENDING)
                        .build());
    }

    private CinchoDayStatusEntryResponse toEntryResponse(ProductionCinchoDayStatusEntity saved) {
        return CinchoDayStatusEntryResponse.builder()
                .productionOrderId(saved.getProductionOrderId())
                .productionOrderItemId(saved.getProductionOrderItemId())
                .delivered(Boolean.TRUE.equals(saved.getDelivered()))
                .workStatus(workStatusOrDefault(saved.getWorkStatus()))
                .build();
    }

    private static String workStatusOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return CinchoProductUtils.WORK_STATUS_PENDING;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (CinchoProductUtils.WORK_STATUS_PENDING.equals(s)
                || CinchoProductUtils.WORK_STATUS_IN_PROGRESS.equals(s)
                || CinchoProductUtils.WORK_STATUS_COMPLETED.equals(s)) {
            return s;
        }
        return CinchoProductUtils.WORK_STATUS_PENDING;
    }

    private static String normalizeWorkStatus(String raw) throws BusinessException {
        if (raw == null || raw.isBlank()) {
            return CinchoProductUtils.WORK_STATUS_PENDING;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (CinchoProductUtils.WORK_STATUS_PENDING.equals(s)
                || CinchoProductUtils.WORK_STATUS_IN_PROGRESS.equals(s)
                || CinchoProductUtils.WORK_STATUS_COMPLETED.equals(s)) {
            return s;
        }
        throw new BusinessException("Estado de trabajo inválido. Use PENDING, IN_PROGRESS o COMPLETED.");
    }

    private void validateEligibleLine(Long productionOrderId, Long productionOrderItemId)
            throws BusinessException, ResourceNotFoundException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));

        if ("CANCELLED".equalsIgnoreCase(String.valueOf(order.getStatus()))) {
            throw new BusinessException("No se puede registrar estado en una orden cancelada.");
        }

        String orderType = order.getOrderType() == null ? "" : order.getOrderType().trim().toUpperCase(Locale.ROOT);
        boolean opcOrder = isOpcOrderType(orderType);
        if (!VENTA_EN_LINEA.equals(orderType) && !CLIENTE_KIOSKO.equals(orderType) && !opcOrder) {
            throw new BusinessException(
                    "Solo líneas de órdenes OPL, OPCK u OPC (cinchos) pueden registrarse en mesa cinchos.");
        }

        ProductionOrderItemEntity item = productionOrderItemRepository.findById(productionOrderItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrderItem", productionOrderItemId));

        if (!productionOrderId.equals(item.getProductionOrderId())) {
            throw new BusinessException("El ítem no pertenece a la orden indicada.");
        }

        if (!opcOrder) {
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            if (!CinchoProductUtils.isMesaCinchosLineForProduction(product)) {
                throw new BusinessException("El ítem no es cincho ni pulsera de mesa cinchos.");
            }
        }
    }

    private static boolean isOpcOrderType(String orderType) {
        return "CINCHOS".equals(orderType)
                || "CINCHOS_FOSSILES".equals(orderType)
                || "CINCHOS_MARCAS".equals(orderType);
    }
}
