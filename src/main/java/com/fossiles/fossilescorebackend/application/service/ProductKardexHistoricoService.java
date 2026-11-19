package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductKardexHistoricoResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardexHistorico;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexHistoricoRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consulta del kardex de producto archivado por limpiezas de inventario. Solo lectura.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductKardexHistoricoService {

    private static final int MAX_PAGE_SIZE = 500;

    private final ProductInventoryKardexHistoricoRepository historicoRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final ColorRepository colorRepository;

    public ProductKardexHistoricoResponse.Page search(
            Long locationId,
            Long productId,
            String movementType,
            String archiveReference,
            LocalDate from,
            LocalDate to,
            String reference,
            int page,
            int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.desc("movementDate"), Sort.Order.desc("id")));
        LocalDateTime fromTs = from != null ? from.atStartOfDay() : null;
        LocalDateTime toTs = to != null ? to.plusDays(1).atStartOfDay() : null;

        Page<ProductInventoryKardexHistorico> result = historicoRepository.search(
                locationId, productId, blankToEmpty(movementType), blankToEmpty(archiveReference),
                fromTs, toTs, likePattern(reference), pageable);

        List<ProductInventoryKardexHistorico> rows = result.getContent();
        Map<Long, ProductEntity> products = indexById(productRepository.findAllById(
                ids(rows, ProductInventoryKardexHistorico::getProductId)), ProductEntity::getId);
        Map<Long, LocationEntity> locations = indexById(locationRepository.findAllById(
                ids(rows, ProductInventoryKardexHistorico::getLocationId)), LocationEntity::getId);
        Map<Long, ColorEntity> colors = indexById(colorRepository.findAllById(
                ids(rows, ProductInventoryKardexHistorico::getColorId)), ColorEntity::getId);

        List<ProductKardexHistoricoResponse.Row> content = rows.stream()
                .map(h -> toRow(h, products.get(h.getProductId()), locations.get(h.getLocationId()),
                        h.getColorId() != null ? colors.get(h.getColorId()) : null))
                .collect(Collectors.toList());

        return ProductKardexHistoricoResponse.Page.builder()
                .content(content)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .size(result.getSize())
                .number(result.getNumber())
                .build();
    }

    public ProductKardexHistoricoResponse.Filters filters() {
        List<ProductKardexHistoricoResponse.Archive> archives = historicoRepository.summarizeArchives().stream()
                .map(r -> ProductKardexHistoricoResponse.Archive.builder()
                        .archiveReference((String) r[0])
                        .archivedAt((LocalDateTime) r[1])
                        .movements(((Number) r[2]).longValue())
                        .build())
                .collect(Collectors.toList());
        return ProductKardexHistoricoResponse.Filters.builder()
                .archives(archives)
                .movementTypes(historicoRepository.findDistinctMovementTypes())
                .build();
    }

    /** Tipo de la línea que explica el salto de saldo en la limpieza (la bodega quedó en 0). */
    public static final String CUT_MOVEMENT_TYPE = "CORTE_INVENTARIO";

    /**
     * Movimientos archivados por limpiezas como filas del kardex activo (objetos sueltos, nunca se
     * guardan), para mostrarlos junto a él en Inventario de productos. Solo lectura: no toca kardex
     * activo, stock ni lotes FIFO. Por cada producto/ubicación/color con saldo al archivarse se agrega
     * una línea de corte (saldo → 0, id negativo) para que el saldo cuadre con los ajustes que
     * recargaron el conteo físico. Orden: más reciente primero.
     */
    public List<ProductInventoryKardex> archivedAsEntities(Long productId, Long locationId) {
        List<ProductInventoryKardexHistorico> rows;
        if (productId != null && locationId != null) {
            rows = historicoRepository.findByProductIdAndLocationId(productId, locationId);
        } else if (productId != null) {
            rows = historicoRepository.findByProductId(productId);
        } else if (locationId != null) {
            rows = historicoRepository.findByLocationId(locationId);
        } else {
            return List.of();
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        Comparator<ProductInventoryKardexHistorico> chronological = Comparator
                .comparing(ProductInventoryKardexHistorico::getMovementDate)
                .thenComparing(ProductInventoryKardexHistorico::getId);
        List<ProductInventoryKardex> out = new ArrayList<>(rows.size() + 16);
        Map<String, ProductInventoryKardexHistorico> lastByLine = new LinkedHashMap<>();
        for (ProductInventoryKardexHistorico h : rows) {
            out.add(ProductInventoryKardex.builder()
                    .id(h.getId())
                    .productId(h.getProductId())
                    .locationId(h.getLocationId())
                    .colorId(h.getColorId())
                    .sizeLabel(h.getSizeLabel())
                    .movementType(h.getMovementType())
                    .quantity(h.getQuantity())
                    .quantityBefore(h.getQuantityBefore())
                    .quantityAfter(h.getQuantityAfter())
                    .unitCost(h.getUnitCost())
                    .totalCost(h.getTotalCost())
                    .referenceType(h.getReferenceType())
                    .referenceId(h.getReferenceId())
                    .referenceNumber(h.getReferenceNumber())
                    .referenceLineId(h.getReferenceLineId())
                    .description(h.getDescription())
                    .movementDate(h.getMovementDate())
                    .createdAt(h.getCreatedAt())
                    .createdBy(h.getCreatedBy())
                    .build());
            String key = h.getProductId() + "|" + h.getLocationId() + "|" + h.getColorId()
                    + "|" + h.getArchiveReference();
            lastByLine.merge(key, h, (a, b) -> chronological.compare(a, b) >= 0 ? a : b);
        }

        for (ProductInventoryKardexHistorico last : lastByLine.values()) {
            BigDecimal saldo = last.getQuantityAfter();
            if (saldo == null || saldo.signum() == 0) {
                continue;
            }
            LocalDateTime cutAt = last.getArchivedAt() != null && last.getArchivedAt().isAfter(last.getMovementDate())
                    ? last.getArchivedAt()
                    : last.getMovementDate().plusSeconds(1);
            out.add(ProductInventoryKardex.builder()
                    .id(-last.getId())
                    .productId(last.getProductId())
                    .locationId(last.getLocationId())
                    .colorId(last.getColorId())
                    .movementType(CUT_MOVEMENT_TYPE)
                    .quantity(saldo.negate())
                    .quantityBefore(saldo)
                    .quantityAfter(BigDecimal.ZERO)
                    .referenceType("LIMPIEZA")
                    .referenceNumber(last.getArchiveReference())
                    .description("Corte de inventario: la bodega quedó en 0 y se volvió a cargar con el conteo físico.")
                    .movementDate(cutAt)
                    .createdAt(cutAt)
                    .build());
        }
        out.sort(Comparator.comparing(ProductInventoryKardex::getMovementDate)
                .thenComparing(ProductInventoryKardex::getId)
                .reversed());
        return out;
    }

    /** Lo mismo que {@link #archivedAsEntities} con la forma de respuesta del kardex de producto. */
    public List<ProductInventoryKardexResponse> archivedAsKardex(Long productId, Long locationId) {
        List<ProductInventoryKardex> rows = archivedAsEntities(productId, locationId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductEntity> products = indexById(productRepository.findAllById(
                idsOf(rows, ProductInventoryKardex::getProductId)), ProductEntity::getId);
        Map<Long, LocationEntity> locations = indexById(locationRepository.findAllById(
                idsOf(rows, ProductInventoryKardex::getLocationId)), LocationEntity::getId);
        Map<Long, ColorEntity> colors = indexById(colorRepository.findAllById(
                idsOf(rows, ProductInventoryKardex::getColorId)), ColorEntity::getId);
        return rows.stream().map(k -> {
            ProductEntity product = products.get(k.getProductId());
            LocationEntity location = locations.get(k.getLocationId());
            ColorEntity color = k.getColorId() != null ? colors.get(k.getColorId()) : null;
            BigDecimal quantity = k.getQuantity();
            boolean entering = quantity != null && quantity.signum() >= 0;
            return ProductInventoryKardexResponse.builder()
                    .id(k.getId())
                    .productId(k.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .locationId(k.getLocationId())
                    .locationCode(location != null ? location.getCode() : null)
                    .locationName(location != null ? location.getName() : null)
                    .colorId(k.getColorId())
                    .colorName(color != null ? color.getName() : null)
                    .movementType(k.getMovementType())
                    .quantity(quantity)
                    .quantityBefore(k.getQuantityBefore())
                    .quantityAfter(k.getQuantityAfter())
                    .unitCost(k.getUnitCost())
                    .totalCost(k.getTotalCost())
                    .referenceType(k.getReferenceType())
                    .referenceId(k.getReferenceId())
                    .referenceNumber(k.getReferenceNumber())
                    .description(k.getDescription())
                    .movementDate(k.getMovementDate())
                    .createdAt(k.getCreatedAt())
                    .createdBy(k.getCreatedBy())
                    .cantidadEntrada(entering ? quantity : null)
                    .costoUnitarioEntrada(entering ? k.getUnitCost() : null)
                    .totalEntrada(entering ? k.getTotalCost() : null)
                    .cantidadSalida(!entering && quantity != null ? quantity.abs() : null)
                    .costoUnitarioSalida(!entering ? k.getUnitCost() : null)
                    .totalSalida(!entering ? k.getTotalCost() : null)
                    .lotesFifo(List.of())
                    .build();
        }).collect(Collectors.toList());
    }

    private static Set<Long> idsOf(List<ProductInventoryKardex> rows, Function<ProductInventoryKardex, Long> getter) {
        return rows.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private ProductKardexHistoricoResponse.Row toRow(
            ProductInventoryKardexHistorico h, ProductEntity product, LocationEntity location, ColorEntity color) {
        return ProductKardexHistoricoResponse.Row.builder()
                .id(h.getId())
                .movementDate(h.getMovementDate())
                .locationId(h.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(h.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(h.getColorId())
                .colorName(color != null ? color.getName() : null)
                .sizeLabel(h.getSizeLabel())
                .movementType(h.getMovementType())
                .quantity(h.getQuantity())
                .quantityBefore(h.getQuantityBefore())
                .quantityAfter(h.getQuantityAfter())
                .unitCost(h.getUnitCost())
                .totalCost(h.getTotalCost())
                .referenceType(h.getReferenceType())
                .referenceId(h.getReferenceId())
                .referenceNumber(h.getReferenceNumber())
                .description(h.getDescription())
                .archiveReference(h.getArchiveReference())
                .archivedAt(h.getArchivedAt())
                .build();
    }

    private static Set<Long> ids(List<ProductInventoryKardexHistorico> rows,
            Function<ProductInventoryKardexHistorico, Long> getter) {
        return rows.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> indexById(List<T> entities, Function<T, Long> idGetter) {
        return entities.stream().collect(Collectors.toMap(idGetter, e -> e, (a, b) -> a));
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String likePattern(String value) {
        String v = blankToEmpty(value).toLowerCase(java.util.Locale.ROOT);
        return v.isEmpty() ? "" : "%" + v + "%";
    }
}
