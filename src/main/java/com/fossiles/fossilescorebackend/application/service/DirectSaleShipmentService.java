package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.DirectSaleShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DirectSaleShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerAccountEntryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Envío directo a un cliente de Luis Felipe: sale de stock (Bodega PT o Devoluciones) sin orden de producción,
 * sin recepción en bodega ni cajas. Se crea confirmado; al enviarlo (PUT /shipments/{id}/send, el mismo de Preparar
 * envíos) descarga la bodega y genera el cargo en cuentas por cobrar del cliente con los precios de sus líneas.
 * Anular o regresar el envío devuelve el inventario y anula el cargo, como en cualquier envío.
 */
@Service
@RequiredArgsConstructor
public class DirectSaleShipmentService {

    public static final String KIND_OPV = "OPV";
    public static final String KIND_OPC = "OPC";
    public static final String SOURCE_PT = "PT";
    public static final String SOURCE_RETURNS = "RETURNS";
    static final String DESTINO_PREFIX = "DESTINO:";

    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAccountEntryRepository entryRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;
    private final ProductDistributionService distributionService;
    private final OpvVendorShipmentNumberService opvVendorShipmentNumberService;
    private final SecurityUtil securityUtil;

    public static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if ("GCF".equals(k)) {
            return KIND_OPC;
        }
        return KIND_OPV.equals(k) || KIND_OPC.equals(k) ? k : null;
    }

    public static String normalizeSource(String source) {
        String s = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if ("DEVOLUCIONES".equals(s) || "RETURNS_ONLY".equals(s)) {
            return SOURCE_RETURNS;
        }
        if ("BODEGA_PT".equals(s) || "PT_ONLY".equals(s)) {
            return SOURCE_PT;
        }
        return SOURCE_PT.equals(s) || SOURCE_RETURNS.equals(s) ? s : null;
    }

    public static String sourceLabel(String source) {
        return SOURCE_RETURNS.equals(source)
                ? ProductDistributionService.RETURNS_WAREHOUSE_LABEL
                : ProductDistributionService.PT_WAREHOUSE_LABEL;
    }

    /** Crea el envío confirmado (todavía no mueve inventario ni genera cargo: eso pasa al enviarlo). */
    @Transactional(rollbackFor = Exception.class)
    public DirectSaleShipmentResponse create(DirectSaleShipmentRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getCustomerId() == null) {
            throw new BusinessException("Elija el cliente al que se le envía.");
        }
        CustomerEntity customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.getCustomerId()));
        String kind = normalizeKind(request.getKind());
        if (kind == null) {
            throw new BusinessException("Elija la cartera del envío: OPV (Fossiles) u OPC (GCF).");
        }
        String source = normalizeSource(request.getSource());
        if (source == null) {
            throw new BusinessException("Elija de qué bodega sale: Bodega PT o Devoluciones.");
        }
        BigDecimal shippingCost = request.getShippingCost();
        if (shippingCost != null && shippingCost.signum() < 0) {
            throw new BusinessException("El costo de envío no puede ser negativo.");
        }
        if (shippingCost != null && shippingCost.signum() == 0) {
            shippingCost = null;
        }

        List<ProductShipmentRequest.ProductShipmentDetailRequest> lines = new ArrayList<>();
        for (DirectSaleShipmentRequest.Line line : request.getLines() == null
                ? List.<DirectSaleShipmentRequest.Line>of() : request.getLines()) {
            if (line == null || line.getProductId() == null) {
                continue;
            }
            BigDecimal qty = line.getQuantity();
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            if (qty.stripTrailingZeros().scale() > 0) {
                throw new BusinessException("Las cantidades deben ser unidades enteras.");
            }
            ProductEntity product = productRepository.findById(line.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.getProductId()));
            BigDecimal price = line.getUnitPrice() != null && line.getUnitPrice().signum() > 0
                    ? line.getUnitPrice()
                    : defaultUnitPrice(product);
            if (price == null || price.signum() <= 0) {
                throw new BusinessException(product.getCode() + " no tiene precio: escriba el precio de la línea.");
            }
            lines.add(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                    .productId(product.getId())
                    .colorId(line.getColorId())
                    .size(line.getSize() == null ? null : line.getSize().trim())
                    .quantity(qty)
                    .unitPrice(price.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }
        if (lines.isEmpty()) {
            throw new BusinessException("Agregue al menos un producto con cantidad.");
        }

        Long userId = securityUtil.getCurrentUserId();
        String extraNotes = request.getNotes() == null ? "" : request.getNotes().trim();
        String notes = DESTINO_PREFIX + " " + customer.getName() + (extraNotes.isEmpty() ? "" : "\n" + extraNotes);
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .withoutDistribution(true)
                .shipmentNumber(opvVendorShipmentNumberService.nextNumber())
                .directSaleCustomerId(customer.getId())
                .directSaleKind(kind)
                .directSaleSource(source)
                .shippingCost(shippingCost == null ? null : shippingCost.setScale(2, RoundingMode.HALF_UP))
                .status("DRAFT")
                .notes(notes)
                .createdBy(userId)
                .updatedBy(userId)
                .build());
        distributionService.updateShipmentProducts(shipment.getId(), lines);
        distributionService.confirmShipmentDraft(shipment.getId());
        return toResponse(shipmentRepository.findById(shipment.getId()).orElse(shipment), true);
    }

    @Transactional(readOnly = true)
    public List<DirectSaleShipmentResponse> list(Long customerId, Integer days) {
        int window = days == null || days <= 0 ? 60 : Math.min(days, 730);
        LocalDateTime from = LocalDate.now().minusDays(window).atStartOfDay();
        List<DirectSaleShipmentResponse> result = new ArrayList<>();
        for (ProductShipmentEntity shipment : shipmentRepository.findDirectSaleShipments(
                customerId, from, PageRequest.of(0, 300))) {
            result.add(toResponse(shipment, false));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public DirectSaleShipmentResponse get(Long shipmentId) throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        if (shipment.getDirectSaleCustomerId() == null) {
            throw new BusinessException("El envío " + shipment.getShipmentNumber() + " no es un envío directo.");
        }
        return toResponse(shipment, true);
    }

    /** Precio de vendedor (el de Luis Felipe); si no tiene, el de venta. */
    static BigDecimal defaultUnitPrice(ProductEntity product) {
        if (product.getSellerPrice() != null && product.getSellerPrice().signum() > 0) {
            return product.getSellerPrice();
        }
        return product.getSalePrice();
    }

    private DirectSaleShipmentResponse toResponse(ProductShipmentEntity shipment, boolean withLines) {
        CustomerEntity customer = customerRepository.findById(shipment.getDirectSaleCustomerId()).orElse(null);
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
        BigDecimal total = BigDecimal.ZERO;
        int units = 0;
        List<DirectSaleShipmentResponse.Line> lines = new ArrayList<>();
        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal qty = detail.getQuantity() == null ? BigDecimal.ZERO : detail.getQuantity();
            BigDecimal price = detail.getUnitPrice() == null ? BigDecimal.ZERO : detail.getUnitPrice();
            BigDecimal subtotal = price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
            total = total.add(subtotal);
            units += qty.intValue();
            if (withLines) {
                ProductEntity product = productRepository.findById(detail.getProductId()).orElse(null);
                ColorEntity color = detail.getColorId() == null ? null
                        : colorRepository.findById(detail.getColorId()).orElse(null);
                lines.add(DirectSaleShipmentResponse.Line.builder()
                        .detailId(detail.getId())
                        .productId(detail.getProductId())
                        .productCode(product != null ? product.getCode() : null)
                        .productName(product != null ? product.getName() : null)
                        .colorId(detail.getColorId())
                        .colorName(color != null ? color.getName() : null)
                        .size(detail.getSizeLabel())
                        .quantity(qty)
                        .unitPrice(price)
                        .subtotal(subtotal)
                        .build());
            }
        }
        CustomerAccountEntryEntity charge = entryRepository.findNonVoidChargesByProductShipmentId(shipment.getId())
                .stream().findFirst().orElse(null);
        String createdBy = shipment.getCreatedBy() == null ? null : userRepository.findById(shipment.getCreatedBy())
                .map(u -> {
                    String full = ((u.getFirstName() == null ? "" : u.getFirstName().trim()) + " "
                            + (u.getLastName() == null ? "" : u.getLastName().trim())).trim();
                    return full.isEmpty() ? u.getUsername() : full;
                })
                .orElse(null);
        return DirectSaleShipmentResponse.builder()
                .id(shipment.getId())
                .shipmentNumber(shipment.getShipmentNumber())
                .status(shipment.getStatus())
                .customerId(shipment.getDirectSaleCustomerId())
                .customerName(customer != null ? customer.getName() : null)
                .kind(shipment.getDirectSaleKind())
                .source(shipment.getDirectSaleSource())
                .sourceLabel(sourceLabel(shipment.getDirectSaleSource()))
                .shippingCost(shipment.getShippingCost())
                .notes(stripDestination(shipment.getNotes()))
                .productsTotal(total.setScale(2, RoundingMode.HALF_UP))
                .units(units)
                .chargeEntryId(charge != null ? charge.getId() : null)
                .chargeAmount(charge != null ? charge.getAmount() : null)
                .createdAt(shipment.getCreatedAt())
                .sentAt(shipment.getSentAt())
                .createdByName(createdBy)
                .lines(withLines ? lines : null)
                .build();
    }

    private static String stripDestination(String notes) {
        if (notes == null) {
            return null;
        }
        String rest = notes.lines()
                .filter(l -> !l.trim().toUpperCase(Locale.ROOT).startsWith(DESTINO_PREFIX))
                .collect(Collectors.joining("\n"))
                .trim();
        return rest.isEmpty() ? null : rest;
    }
}
