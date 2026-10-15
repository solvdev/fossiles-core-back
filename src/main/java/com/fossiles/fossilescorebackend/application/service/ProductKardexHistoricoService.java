package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductKardexHistoricoResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
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
