package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomerShipmentDispatchService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;

    @Transactional
    public Map<String, Object> dispatchCustomerShipment(
            long productionOrderId,
            long onlineSaleId,
            Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {

        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        if (!"VENTA_EN_LINEA".equals(po.getOrderType())) {
            throw new BusinessException("Solo se pueden despachar envíos a cliente en órdenes de tipo VENTA_EN_LINEA");
        }

        List<Long> linkedSaleIds = productionOrderItemRepository
                .findDistinctOnlineSaleIdsByProductionOrderId(productionOrderId);
        if (!linkedSaleIds.contains(onlineSaleId)) {
            throw new BusinessException("La venta no pertenece a esta orden de producción");
        }

        OnlineSaleEntity sale = onlineSaleRepository.findById(onlineSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", onlineSaleId));

        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("Esta venta ya fue despachada. Estado actual: " + sale.getStatus());
        }

        onlineSaleShipmentNumberService.assignIfMissing(sale);
        String shipmentNumber = sale.getShipmentNumber();
        sale.setStatus("ENVIADO");
        if (body != null && body.get("guideNumber") != null) {
            sale.setGuideNumber(body.get("guideNumber"));
        }
        if (body != null && body.get("shippingCarrier") != null) {
            sale.setShippingCarrier(body.get("shippingCarrier"));
        }
        onlineSaleRepository.save(sale);

        List<Long> saleIds = productionOrderItemRepository.findDistinctOnlineSaleIdsByProductionOrderId(productionOrderId);
        boolean allDispatched = saleIds.stream().allMatch(sId -> {
            OnlineSaleEntity s = onlineSaleRepository.findById(sId).orElse(null);
            return s != null && ("ENVIADO".equals(s.getStatus()) || "ENTREGADO".equals(s.getStatus()));
        });

        if (allDispatched && !"COMPLETED".equals(po.getStatus())) {
            po.setStatus("COMPLETED");
            productionOrderRepository.save(po);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "Envío " + shipmentNumber + " despachado para " + sale.getCustomerName());
        result.put("shipmentNumber", shipmentNumber);
        result.put("saleStatus", sale.getStatus());
        result.put("allDispatched", allDispatched);
        return result;
    }
}
