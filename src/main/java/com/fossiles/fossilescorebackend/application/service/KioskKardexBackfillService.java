package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskKardexBackfillResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class KioskKardexBackfillService {

    private final KioskSaleRepository kioskSaleRepository;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final ProductInventoryService productInventoryService;

    public KioskKardexBackfillResponse backfill(LocalDate startDate, LocalDate endDate) {
        LocalDate from = startDate != null ? startDate : LocalDate.of(2000, 1, 1);
        LocalDate to = endDate != null ? endDate : LocalDate.now();

        List<KioskSaleEntity> sales = kioskSaleRepository.findBySaleDateBetweenOrderBySoldAtDesc(from, to);

        int salesScanned = 0;
        int itemsScanned = 0;
        int inserted = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        for (KioskSaleEntity sale : sales) {
            salesScanned++;
            List<KioskSaleItemEntity> items = kioskSaleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());
            if (items == null || items.isEmpty()) continue;

            // Ya existe kardex para esta venta? si sí, evitamos duplicar (granularidad: por venta)
            List<ProductInventoryKardex> existing = productInventoryKardexRepository
                    .findByReferenceTypeAndReferenceId("KIOSK_SALE", sale.getId());
            if (existing != null && !existing.isEmpty()) {
                skipped += items.size();
                continue;
            }

            for (KioskSaleItemEntity item : items) {
                itemsScanned++;
                try {
                    // Backfill: no podemos reconstruir quantityBefore/After sin replay completo.
                    // Insertamos movimiento con before/after null y fecha del evento.
                    productInventoryService.recordMovement(
                            item.getProductId(),
                            sale.getKioskLocationId(),
                            item.getColorId(),
                            "KIOSK_SALE",
                            (item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO).negate(),
                            null,
                            null,
                            null,
                            "KIOSK_SALE",
                            sale.getId(),
                            sale.getSaleNumber(),
                            "BACKFILL: Venta POS en kiosko"
                    );

                    // Ajustar movementDate al momento real de la venta (si existe)
                    // recordMovement guarda movementDate=now por defecto; actualizamos el último insert.
                    // (Tomamos el más reciente por reference, product y location)
                    List<ProductInventoryKardex> last = productInventoryKardexRepository
                            .findByReferenceTypeAndReferenceId("KIOSK_SALE", sale.getId());
                    if (last != null && !last.isEmpty()) {
                        ProductInventoryKardex k = last.get(0);
                        LocalDateTime soldAt = sale.getSoldAt() != null ? sale.getSoldAt() : (sale.getSaleDate() != null ? sale.getSaleDate().atStartOfDay() : null);
                        if (soldAt != null) {
                            k.setMovementDate(soldAt);
                            productInventoryKardexRepository.save(k);
                        }
                    }

                    inserted++;
                } catch (Exception e) {
                    warnings.add("Venta " + sale.getId() + " item " + item.getId() + ": " + e.getMessage());
                }
            }
        }

        return KioskKardexBackfillResponse.builder()
                .salesScanned(salesScanned)
                .itemsScanned(itemsScanned)
                .kardexInserted(inserted)
                .kardexSkipped(skipped)
                .warnings(warnings.size() > 30 ? warnings.subList(0, 30) : warnings)
                .build();
    }
}

