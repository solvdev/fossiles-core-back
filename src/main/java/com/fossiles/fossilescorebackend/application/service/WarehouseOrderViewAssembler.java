package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarehouseOrderViewAssembler {

    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final TaskRepository taskRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final ProductDistributionRepository distributionRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final LocationRepository locationRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductCategoryRepository productCategoryRepository;

    public WarehouseOrderViewResponse toWarehouseView(ProductionOrderEntity po) {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(po.getId());
        List<TaskEntity> tasks = taskRepository.findByProductionOrderId(po.getId());

        int completedTasks = (int) tasks.stream().filter(t -> "COMPLETED".equals(t.getStatus())).count();
        int totalQuantity = items.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();

        WarehouseOrderViewResponse.WarehouseOrderViewResponseBuilder builder = WarehouseOrderViewResponse.builder()
                .productionOrderId(po.getId())
                .productionOrderCode(po.getCode())
                .orderType(po.getOrderType())
                .status(po.getStatus())
                .startDate(po.getStartDate())
                .deliveryDate(po.getDeliveryDate())
                .observations(po.getObservations())
                .createdAt(po.getCreatedAt())
                .warehouseReceiptClosedAt(po.getWarehouseReceiptClosedAt())
                .totalItems(items.size())
                .totalQuantity(totalQuantity)
                .completedTasks(completedTasks)
                .totalTasks(tasks.size());

        List<ProductionOrderItemResponse> itemResponses = items.stream()
                .map(item -> {
                    ProductEntity product = item.getProductId() != null
                            ? productRepository.findById(item.getProductId()).orElse(null) : null;
                    ColorEntity color = item.getColorId() != null
                            ? colorRepository.findById(item.getColorId()).orElse(null) : null;

                    return ProductionOrderItemResponse.builder()
                            .id(item.getId())
                            .productionOrderId(item.getProductionOrderId())
                            .onlineSaleId(item.getOnlineSaleId())
                            .productId(item.getProductId())
                            .productName(product != null ? product.getName() : null)
                            .productCode(product != null ? product.getCode() : null)
                            .colorId(item.getColorId())
                            .colorName(color != null ? color.getName() : null)
                            .quantity(item.getQuantity())
                            .warehouseReceivedQty(item.getWarehouseReceivedQty())
                            .sizes(parseItemSizes(item.getSizesData()))
                            .observations(item.getObservations())
                            .build();
                })
                .collect(Collectors.toList());
        builder.items(itemResponses);

        if ("VENTA_EN_LINEA".equals(po.getOrderType())) {
            builder.dispatchType("CUSTOMER_SHIPMENTS");

            List<Long> saleIds = productionOrderItemRepository
                    .findDistinctOnlineSaleIdsByProductionOrderId(po.getId());

            List<CustomerShipmentResponse> customerShipments = saleIds.stream()
                    .map(saleId -> {
                        OnlineSaleEntity sale = onlineSaleRepository.findById(saleId).orElse(null);
                        if (sale == null) return null;

                        List<CustomerShipmentResponse.ShipmentItem> shipItems = items.stream()
                                .filter(i -> saleId.equals(i.getOnlineSaleId()))
                                .map(i -> {
                                    ProductEntity p = i.getProductId() != null
                                            ? productRepository.findById(i.getProductId()).orElse(null) : null;
                                    ColorEntity c = i.getColorId() != null
                                            ? colorRepository.findById(i.getColorId()).orElse(null) : null;
                                    return CustomerShipmentResponse.ShipmentItem.builder()
                                            .productionOrderItemId(i.getId())
                                            .productId(i.getProductId())
                                            .productCode(p != null ? p.getCode() : null)
                                            .productName(p != null ? p.getName() : null)
                                            .colorId(i.getColorId())
                                            .colorName(c != null ? c.getName() : null)
                                            .quantity(i.getQuantity())
                                            .build();
                                })
                                .collect(Collectors.toList());

                        return CustomerShipmentResponse.builder()
                                .onlineSaleId(sale.getId())
                                .saleNumber(sale.getSaleNumber())
                                .customerName(sale.getCustomerName())
                                .address(sale.getAddress())
                                .phone(sale.getPhone())
                                .shipmentNumber(sale.getShipmentNumber())
                                .shippingCarrier(sale.getShippingCarrier())
                                .guideNumber(sale.getGuideNumber())
                                .paymentMethod(sale.getPaymentMethod())
                                .saleStatus(sale.getStatus())
                                .saleDate(sale.getSaleDate())
                                .totalAmount(sale.getTotalAmount())
                                .shippingCost(sale.getShippingCost())
                                .packaging(sale.getPackaging())
                                .items(shipItems)
                                .build();
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            builder.customerShipments(customerShipments);

        } else if ("DISTRIBUTION".equals(po.getOrderType()) && po.getDistributionId() != null) {
            builder.dispatchType("KIOSK_DISTRIBUTION");
            builder.distributionId(po.getDistributionId());

            distributionRepository.findById(po.getDistributionId()).ifPresent(dist -> {
                builder.distributionNumber(dist.getDistributionNumber());

                List<ProductShipmentResponse> kioskShipments = shipmentRepository
                        .findByDistributionId(dist.getId()).stream()
                        .map(this::toShipmentResponse)
                        .collect(Collectors.toList());
                builder.kioskShipments(kioskShipments);
            });
        } else {
            builder.dispatchType("DIRECT");
        }

        return builder.build();
    }

    public ProductShipmentResponse toShipmentResponse(ProductShipmentEntity shipment) {
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
        LocationEntity location = locationRepository.findById(shipment.getLocationId()).orElse(null);
        ProductionOrderEntity linkedPo = shipment.getProductionOrderId() == null
                ? null
                : productionOrderRepository.findById(shipment.getProductionOrderId()).orElse(null);

        return ProductShipmentResponse.builder()
                .id(shipment.getId())
                .distributionId(shipment.getDistributionId())
                .productionOrderId(shipment.getProductionOrderId())
                .productionOrderCode(linkedPo != null ? linkedPo.getCode() : null)
                .shipmentNumber(shipment.getShipmentNumber())
                .locationId(shipment.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .status(shipment.getStatus())
                .notes(shipment.getNotes())
                .sentAt(shipment.getSentAt())
                .receivedAt(shipment.getReceivedAt())
                .receivedBy(shipment.getReceivedBy())
                .products(details.stream().map(detail -> {
                    ProductEntity product = detail.getProductId() == null
                            ? null
                            : productRepository.findById(detail.getProductId()).orElse(null);
                    String colorName = null;
                    if (detail.getColorId() != null) {
                        ColorEntity color = colorRepository.findById(detail.getColorId()).orElse(null);
                        colorName = color != null ? color.getName() : null;
                    }
                    Long categoryId = product != null ? product.getCategoryId() : null;
                    String categoryName = categoryId == null
                            ? null
                            : productCategoryRepository.findById(categoryId)
                                    .map(ProductCategoryEntity::getName)
                                    .orElse(null);
                    return ProductShipmentDetailResponse.builder()
                            .id(detail.getId())
                            .shipmentId(detail.getShipmentId())
                            .productId(detail.getProductId())
                            .productCode(product != null ? product.getCode() : null)
                            .productName(product != null ? product.getName() : null)
                            .productImageUrl(product != null ? product.getImageUrl() : null)
                            .categoryId(categoryId)
                            .categoryName(categoryName)
                            .colorId(detail.getColorId())
                            .colorName(colorName)
                            .size(detail.getSizeLabel())
                            .quantity(detail.getQuantity())
                            .unitPrice(detail.getUnitPrice())
                            .quantityReceived(detail.getQuantityReceived())
                            .build();
                }).collect(Collectors.toList()))
                .build();
    }

    private Map<String, Integer> parseItemSizes(String sizesData) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ProductInventorySizesJson.parse(sizesData).forEach((k, v) -> {
            if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                result.put(k, v.intValue());
            }
        });
        return result.isEmpty() ? null : result;
    }
}
