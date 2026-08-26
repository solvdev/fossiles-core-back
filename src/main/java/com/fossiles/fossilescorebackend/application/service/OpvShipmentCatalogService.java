package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OpvShipmentCatalogRowResponse;
import com.fossiles.fossilescorebackend.application.util.CinchoSizePricing;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemPricing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpvShipmentCatalogService {

    private static final String OPV_PACKING_TAG = "__OPV_PACKING__:";
    private static final String OPV_SHIPPING_TAG = "__OPV_SHIPPING__:";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderPartialReleaseRepository partialReleaseRepository;
    private final ProductShipmentRepository productShipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final MaterialRepository materialRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAccountEntryRepository customerAccountEntryRepository;
    private final CustomerAccountService customerAccountService;
    private final ObjectMapper objectMapper;

    public List<OpvShipmentCatalogRowResponse> search(
            String search,
            String orderStatus,
            String shipmentStatus,
            Long customerId,
            LocalDate from,
            LocalDate to,
            Boolean hasShipment,
            int limit) {
        int maxResults = limit > 0 ? Math.min(limit, 500) : 300;
        String searchNorm = search != null ? search.trim().toLowerCase(Locale.ROOT) : "";
        String orderStatusFilter = orderStatus != null && !orderStatus.isBlank()
                ? orderStatus.trim().toUpperCase(Locale.ROOT)
                : null;
        String shipmentStatusFilter = shipmentStatus != null && !shipmentStatus.isBlank()
                ? shipmentStatus.trim().toUpperCase(Locale.ROOT)
                : null;

        Map<Long, CustomerEntity> customersById = customerRepository.findAll().stream()
                .collect(Collectors.toMap(CustomerEntity::getId, c -> c, (a, b) -> a));
        Map<Long, List<CustomerAccountEntryEntity>> entriesByCustomer = customerAccountEntryRepository.findAll().stream()
                .filter(e -> STATUS_ACTIVE.equalsIgnoreCase(e.getStatus()))
                .collect(Collectors.groupingBy(CustomerAccountEntryEntity::getCustomerId));

        List<OpvShipmentCatalogRowResponse> rows = new ArrayList<>();

        for (ProductionOrderEntity order : productionOrderRepository.findOpvCatalogOrders()) {
            if (customerId != null && !customerId.equals(order.getCustomerId())) {
                continue;
            }
            if (orderStatusFilter != null && !orderStatusFilter.equalsIgnoreCase(order.getStatus())) {
                continue;
            }
            if (from != null || to != null) {
                LocalDate ref = order.getDeliveryDate() != null ? order.getDeliveryDate() : order.getStartDate();
                if (ref == null) {
                    continue;
                }
                if (from != null && ref.isBefore(from)) {
                    continue;
                }
                if (to != null && ref.isAfter(to)) {
                    continue;
                }
            }

            CustomerEntity customer = order.getCustomerId() != null
                    ? customersById.get(order.getCustomerId())
                    : null;
            List<CustomerAccountEntryEntity> accountEntries = order.getCustomerId() != null
                    ? entriesByCustomer.getOrDefault(order.getCustomerId(), List.of())
                    : List.of();

            List<ProductionOrderItemEntity> orderItems =
                    productionOrderItemRepository.findByProductionOrderId(order.getId());
            OrderPricing pricing = buildOrderPricing(order, orderItems);
            List<ProductionOrderPartialReleaseEntity> releases =
                    partialReleaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(order.getId());

            List<ProductShipmentEntity> allShipments = productShipmentRepository.findByProductionOrderId(order.getId());
            boolean orderHasShipments = !allShipments.isEmpty();

            if (hasShipment != null) {
                if (hasShipment && !orderHasShipments) {
                    continue;
                }
                if (!hasShipment && orderHasShipments) {
                    continue;
                }
            }

            if (orderHasShipments) {
                Map<Long, ProductionOrderPartialReleaseEntity> releaseById = releases.stream()
                        .collect(Collectors.toMap(ProductionOrderPartialReleaseEntity::getId, r -> r, (a, b) -> a));
                for (ProductShipmentEntity shipment : allShipments) {
                    if (shipmentStatusFilter != null
                            && !shipmentStatusFilter.equalsIgnoreCase(shipment.getStatus())) {
                        continue;
                    }
                    ProductionOrderPartialReleaseEntity release = shipment.getPartialReleaseId() != null
                            ? releaseById.get(shipment.getPartialReleaseId())
                            : null;
                    OpvShipmentCatalogRowResponse row = buildShipmentRow(
                            order, customer, release, shipment, orderItems, pricing, accountEntries);
                    if (matchesSearch(row, searchNorm) && rows.size() < maxResults) {
                        rows.add(row);
                    }
                }
            } else {
                OpvShipmentCatalogRowResponse row = buildOrderRow(
                        order, customer, orderItems, pricing, accountEntries);
                if (matchesSearch(row, searchNorm) && rows.size() < maxResults) {
                    rows.add(row);
                }
            }
        }

        rows.sort(Comparator
                .comparing(OpvShipmentCatalogRowResponse::getDeliveryDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OpvShipmentCatalogRowResponse::getShipmentNumber, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(OpvShipmentCatalogRowResponse::getProductionOrderCode, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return rows;
    }

    private OpvShipmentCatalogRowResponse buildOrderRow(
            ProductionOrderEntity order,
            CustomerEntity customer,
            List<ProductionOrderItemEntity> orderItems,
            OrderPricing pricing,
            List<CustomerAccountEntryEntity> accountEntries) {
        ChargeInfo charge = resolveChargeInfo(accountEntries, order.getId(), null, null);
        List<OpvShipmentCatalogLineResponse> lines = buildProductLines(orderItems, null);
        lines.addAll(buildPackingLines(pricing.packingItems));

        return OpvShipmentCatalogRowResponse.builder()
                .productionOrderId(order.getId())
                .productionOrderCode(order.getCode())
                .orderStatus(order.getStatus())
                .customerId(order.getCustomerId())
                .customerName(firstNonBlank(
                        order.getCustomerName(),
                        customer != null ? customer.getName() : null))
                .customerLegacyCode(customer != null ? customer.getLegacyCode() : null)
                .vendorShipmentNumber(order.getVendorShipmentNumber())
                .vendorShipmentVoided(order.getVendorShipmentVoidedAt() != null)
                .startDate(order.getStartDate())
                .deliveryDate(order.getDeliveryDate())
                .shipmentNumber(order.getVendorShipmentNumber())
                .documentLevel("ORDER")
                .itemsSubtotal(pricing.itemsSubtotal)
                .packingSubtotal(pricing.packingSubtotal)
                .shippingCost(pricing.shippingCost)
                .estimatedTotal(pricing.estimatedTotal)
                .chargeStatus(charge.status)
                .hasCharge(charge.hasCharge)
                .lines(lines)
                .build();
    }

    private OpvShipmentCatalogRowResponse buildShipmentRow(
            ProductionOrderEntity order,
            CustomerEntity customer,
            ProductionOrderPartialReleaseEntity release,
            ProductShipmentEntity shipment,
            List<ProductionOrderItemEntity> orderItems,
            OrderPricing pricing,
            List<CustomerAccountEntryEntity> accountEntries) {
        ChargeInfo charge = resolveChargeInfo(
                accountEntries, order.getId(),
                release != null ? release.getId() : null,
                shipment.getId());

        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
        List<OpvShipmentCatalogLineResponse> lines = details.isEmpty()
                ? buildProductLines(orderItems, null)
                : buildShipmentDetailLines(details, orderItems);
        if (release == null || release.getSequenceNum() == null || release.getSequenceNum() == 1) {
            lines.addAll(buildPackingLines(pricing.packingItems));
        }

        BigDecimal itemsSubtotal = sumLineTotals(lines.stream()
                .filter(l -> "PRODUCT".equals(l.getLineType()))
                .collect(Collectors.toList()));
        BigDecimal packingSubtotal = sumLineTotals(lines.stream()
                .filter(l -> "PACKING".equals(l.getLineType()))
                .collect(Collectors.toList()));
        BigDecimal shipping = (release == null || release.getSequenceNum() == null || release.getSequenceNum() == 1)
                ? pricing.shippingCost
                : BigDecimal.ZERO;
        BigDecimal estimated = itemsSubtotal.add(packingSubtotal).add(shipping).setScale(2, RoundingMode.HALF_UP);

        return OpvShipmentCatalogRowResponse.builder()
                .productionOrderId(order.getId())
                .productionOrderCode(order.getCode())
                .orderStatus(order.getStatus())
                .customerId(order.getCustomerId())
                .customerName(firstNonBlank(
                        order.getCustomerName(),
                        customer != null ? customer.getName() : null))
                .customerLegacyCode(customer != null ? customer.getLegacyCode() : null)
                .vendorShipmentNumber(order.getVendorShipmentNumber())
                .vendorShipmentVoided(order.getVendorShipmentVoidedAt() != null)
                .startDate(order.getStartDate())
                .deliveryDate(order.getDeliveryDate())
                .partialReleaseId(release != null ? release.getId() : null)
                .partialReleaseLabel(release != null
                        ? firstNonBlank(release.getLabel(), release.getSequenceNum() != null
                                ? "Parcial " + release.getSequenceNum()
                                : null)
                        : null)
                .productShipmentId(shipment.getId())
                .shipmentNumber(shipment.getShipmentNumber())
                .shipmentStatus(shipment.getStatus())
                .documentLevel("SHIPMENT")
                .itemsSubtotal(itemsSubtotal)
                .packingSubtotal(packingSubtotal)
                .shippingCost(shipping)
                .estimatedTotal(estimated)
                .chargeStatus(charge.status)
                .hasCharge(charge.hasCharge)
                .lines(lines)
                .build();
    }

    private List<OpvShipmentCatalogLineResponse> buildShipmentDetailLines(
            List<ProductShipmentDetailEntity> details,
            List<ProductionOrderItemEntity> orderItems) {
        List<OpvShipmentCatalogLineResponse> lines = new ArrayList<>();
        for (ProductShipmentDetailEntity detail : details) {
            ProductEntity product = detail.getProductId() != null
                    ? productRepository.findById(detail.getProductId()).orElse(null)
                    : null;
            ColorEntity color = detail.getColorId() != null
                    ? colorRepository.findById(detail.getColorId()).orElse(null)
                    : null;
            BigDecimal unitPrice = resolveShipmentDetailUnitPrice(detail, orderItems);
            int qty = detail.getQuantity() != null ? detail.getQuantity().intValue() : 0;
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            lines.add(OpvShipmentCatalogLineResponse.builder()
                    .lineType("PRODUCT")
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorName(color != null ? color.getName() : null)
                    .sizeLabel(detail.getSizeLabel())
                    .quantity(qty)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build());
        }
        return lines;
    }

    private List<OpvShipmentCatalogLineResponse> buildProductLines(
            List<ProductionOrderItemEntity> orderItems,
            Set<Long> productIdsFilter) {
        List<OpvShipmentCatalogLineResponse> lines = new ArrayList<>();
        for (ProductionOrderItemEntity item : orderItems) {
            if (productIdsFilter != null && item.getProductId() != null
                    && !productIdsFilter.contains(item.getProductId())) {
                continue;
            }
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            ColorEntity color = item.getColorId() != null
                    ? colorRepository.findById(item.getColorId()).orElse(null)
                    : null;
            Map<String, Integer> sizes = parseSizes(item.getSizesData());
            if (!sizes.isEmpty()) {
                for (Map.Entry<String, Integer> entry : sizes.entrySet()) {
                    int qty = entry.getValue() != null ? entry.getValue() : 0;
                    if (qty <= 0) continue;
                    BigDecimal unitPrice = resolveUnitPriceForSize(item, entry.getKey());
                    lines.add(productLine(product, color, entry.getKey(), qty, unitPrice));
                }
            } else {
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                if (qty > 0) {
                    lines.add(productLine(product, color, null, qty, resolveUnitPrice(item)));
                }
            }
        }
        return lines;
    }

    private OpvShipmentCatalogLineResponse productLine(
            ProductEntity product,
            ColorEntity color,
            String sizeLabel,
            int qty,
            BigDecimal unitPrice) {
        BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        return OpvShipmentCatalogLineResponse.builder()
                .lineType("PRODUCT")
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorName(color != null ? color.getName() : null)
                .sizeLabel(sizeLabel)
                .quantity(qty)
                .unitPrice(unitPrice)
                .lineTotal(lineTotal)
                .build();
    }

    private List<OpvShipmentCatalogLineResponse> buildPackingLines(List<PackingMeta> packingItems) {
        List<OpvShipmentCatalogLineResponse> lines = new ArrayList<>();
        for (PackingMeta packing : packingItems) {
            MaterialEntity material = packing.materialId != null
                    ? materialRepository.findById(packing.materialId).orElse(null)
                    : null;
            BigDecimal lineTotal = packing.unitPrice.multiply(packing.quantity).setScale(2, RoundingMode.HALF_UP);
            lines.add(OpvShipmentCatalogLineResponse.builder()
                    .lineType("PACKING")
                    .materialName(material != null ? material.getName() : ("Material #" + packing.materialId))
                    .quantity(packing.quantity.intValue())
                    .unitPrice(packing.unitPrice)
                    .lineTotal(lineTotal)
                    .build());
        }
        return lines;
    }

    private OrderPricing buildOrderPricing(ProductionOrderEntity order, List<ProductionOrderItemEntity> items) {
        BigDecimal itemsSubtotal = BigDecimal.ZERO;
        for (ProductionOrderItemEntity item : items) {
            itemsSubtotal = itemsSubtotal.add(ProductionOrderItemPricing.itemSubtotal(
                    item,
                    productId -> productRepository.findById(productId)
                            .map(ProductEntity::getSellerPrice)
                            .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) >= 0)
                            .orElse(BigDecimal.ZERO)));
        }
        OrderMeta meta = parseOrderMeta(order.getObservations());
        BigDecimal packingSubtotal = meta.packingItems.stream()
                .map(p -> p.unitPrice.multiply(p.quantity))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shipping = meta.shippingCost != null ? meta.shippingCost : BigDecimal.ZERO;
        BigDecimal estimated = customerAccountService.estimateVendorOrderTotal(order);
        return new OrderPricing(
                itemsSubtotal.setScale(2, RoundingMode.HALF_UP),
                packingSubtotal.setScale(2, RoundingMode.HALF_UP),
                shipping.setScale(2, RoundingMode.HALF_UP),
                estimated,
                meta.packingItems);
    }

    private ChargeInfo resolveChargeInfo(
            List<CustomerAccountEntryEntity> entries,
            Long productionOrderId,
            Long partialReleaseId,
            Long productShipmentId) {
        if (entries == null || entries.isEmpty()) {
            return new ChargeInfo("NONE", false);
        }
        Optional<CustomerAccountEntryEntity> charge = customerAccountEntryRepository.findActiveCharge(
                entries.get(0).getCustomerId(),
                productionOrderId,
                partialReleaseId,
                productShipmentId);
        if (charge.isEmpty()) {
            return new ChargeInfo("NONE", false);
        }
        BigDecimal balanceDue = computeChargeBalanceDue(charge.get(), entries);
        String status;
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            status = "PAID";
        } else if (balanceDue.compareTo(charge.get().getAmount()) < 0) {
            status = "PARTIAL";
        } else {
            status = "CHARGED";
        }
        return new ChargeInfo(status, true);
    }

    private BigDecimal computeChargeBalanceDue(
            CustomerAccountEntryEntity charge,
            List<CustomerAccountEntryEntity> entries) {
        BigDecimal applied = entries.stream()
                .filter(e -> STATUS_ACTIVE.equalsIgnoreCase(e.getStatus()))
                .filter(e -> charge.getId().equals(e.getAppliedToEntryId()))
                .map(this::resolveAppliedCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return charge.getAmount().subtract(applied).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveAppliedCreditAmount(CustomerAccountEntryEntity entry) {
        if (entry.getGrossCollectedAmount() != null && entry.getGrossCollectedAmount().compareTo(BigDecimal.ZERO) > 0) {
            return entry.getGrossCollectedAmount().setScale(2, RoundingMode.HALF_UP);
        }
        return entry.getAmount() != null ? entry.getAmount().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private BigDecimal resolveUnitPrice(ProductionOrderItemEntity item) {
        return resolveUnitPriceForSize(item, null);
    }

    private BigDecimal resolveUnitPriceForSize(ProductionOrderItemEntity item, String sizeLabel) {
        return ProductionOrderItemPricing.resolveForSize(
                item,
                sizeLabel,
                productId -> productRepository.findById(productId)
                        .map(ProductEntity::getSellerPrice)
                        .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) >= 0)
                        .orElse(BigDecimal.ZERO));
    }

    private BigDecimal resolveShipmentDetailUnitPrice(
            ProductShipmentDetailEntity detail,
            List<ProductionOrderItemEntity> orderItems) {
        ProductionOrderItemEntity matched = null;
        for (ProductionOrderItemEntity item : orderItems) {
            if (item.getProductId() == null || !item.getProductId().equals(detail.getProductId())) {
                continue;
            }
            if (detail.getColorId() == null && item.getColorId() == null) {
                matched = item;
                break;
            }
            if (detail.getColorId() != null && detail.getColorId().equals(item.getColorId())) {
                matched = item;
                break;
            }
        }
        // 1) Precio congelado en el envío
        if (detail.getUnitPrice() != null && detail.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            return detail.getUnitPrice();
        }
        // 2) Precio de la OP (sin catálogo)
        if (matched != null) {
            BigDecimal opPrice = ProductionOrderItemPricing.resolveForSize(matched, detail.getSizeLabel(), null);
            if (opPrice.compareTo(BigDecimal.ZERO) > 0) {
                return opPrice;
            }
        }
        // 3) Catálogo solo si no hubo precio congelado
        if (matched != null) {
            return resolveUnitPriceForSize(matched, detail.getSizeLabel());
        }
        if (detail.getProductId() != null) {
            return productRepository.findById(detail.getProductId())
                    .map(ProductEntity::getSellerPrice)
                    .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                    .map(p -> CinchoSizePricing.applySurcharge(p, detail.getSizeLabel()))
                    .orElse(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    private int resolveItemQuantity(ProductionOrderItemEntity item) {
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(item.getSizesData());
        if (!sizes.isEmpty()) {
            return sizes.values().stream().filter(Objects::nonNull).mapToInt(BigDecimal::intValue).sum();
        }
        return item.getQuantity() != null ? item.getQuantity() : 0;
    }

    private Map<String, Integer> parseSizes(String sizesData) {
        Map<String, Integer> result = new LinkedHashMap<>();
        ProductInventorySizesJson.parse(sizesData).forEach((k, v) -> {
            if (v != null && v.compareTo(BigDecimal.ZERO) > 0) {
                result.put(k, v.intValue());
            }
        });
        return result;
    }

    private OrderMeta parseOrderMeta(String rawObservations) {
        List<String> lines = String.valueOf(rawObservations == null ? "" : rawObservations).lines().collect(Collectors.toList());
        String packingRaw = "";
        String shippingRaw = "";
        for (String line : lines) {
            if (line.startsWith(OPV_PACKING_TAG)) {
                packingRaw = line.substring(OPV_PACKING_TAG.length()).trim();
            } else if (line.startsWith(OPV_SHIPPING_TAG)) {
                shippingRaw = line.substring(OPV_SHIPPING_TAG.length()).trim();
            }
        }
        List<PackingMeta> packingItems = new ArrayList<>();
        if (!packingRaw.isEmpty()) {
            try {
                List<Map<String, Object>> parsed = objectMapper.readValue(packingRaw, new TypeReference<>() {});
                for (Map<String, Object> item : parsed) {
                    Long materialId = item.get("materialId") == null ? null : Long.valueOf(String.valueOf(item.get("materialId")));
                    BigDecimal quantity = item.get("quantity") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(item.get("quantity")));
                    BigDecimal unitPrice = item.get("unitPrice") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(item.get("unitPrice")));
                    if (materialId != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
                        packingItems.add(new PackingMeta(materialId, quantity, unitPrice));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        BigDecimal shippingCost = BigDecimal.ZERO;
        if (!shippingRaw.isEmpty()) {
            try {
                shippingCost = new BigDecimal(shippingRaw);
            } catch (Exception ignored) {
                shippingCost = BigDecimal.ZERO;
            }
        }
        return new OrderMeta(shippingCost, packingItems);
    }

    private static boolean matchesSearch(OpvShipmentCatalogRowResponse row, String searchNorm) {
        if (searchNorm.isEmpty()) {
            return true;
        }
        return contains(row.getCustomerName(), searchNorm)
                || contains(row.getCustomerLegacyCode(), searchNorm)
                || contains(row.getProductionOrderCode(), searchNorm)
                || contains(row.getVendorShipmentNumber(), searchNorm)
                || contains(row.getShipmentNumber(), searchNorm)
                || contains(row.getPartialReleaseLabel(), searchNorm);
    }

    private static boolean contains(String value, String searchNorm) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(searchNorm);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static BigDecimal sumLineTotals(List<OpvShipmentCatalogLineResponse> lines) {
        return lines.stream()
                .map(OpvShipmentCatalogLineResponse::getLineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private record OrderPricing(
            BigDecimal itemsSubtotal,
            BigDecimal packingSubtotal,
            BigDecimal shippingCost,
            BigDecimal estimatedTotal,
            List<PackingMeta> packingItems) {}

    private record OrderMeta(BigDecimal shippingCost, List<PackingMeta> packingItems) {}

    private record PackingMeta(Long materialId, BigDecimal quantity, BigDecimal unitPrice) {}

    private record ChargeInfo(String status, boolean hasCharge) {}
}
