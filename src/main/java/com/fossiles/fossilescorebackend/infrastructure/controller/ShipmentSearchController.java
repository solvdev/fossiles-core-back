package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ShipmentSearchRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentSearchOptionsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentSearchResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ShipmentSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Consulta «Envíos»: búsqueda paginada de todos los envíos por procedencia. */
@RestController
@RequestMapping("/api/product-distributions/shipments")
@RequiredArgsConstructor
public class ShipmentSearchController {

    private final ShipmentSearchService shipmentSearchService;

    /**
     * Todos los filtros son opcionales y se combinan. Listas separadas por coma (kioskIds=1,2). Sin rango de fechas
     * se usan los últimos 90 días; page es base 0 y size llega hasta 500 (para exportar el listado).
     */
    @GetMapping("/search")
    public ResponseEntity<ShipmentSearchResponse> search(
            @RequestParam(required = false) String dateField,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> kioskIds,
            @RequestParam(required = false) List<String> provenances,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) String shipmentNumber,
            @RequestParam(required = false) String distributionNumber,
            @RequestParam(required = false) String productionOrderCode,
            @RequestParam(required = false) String productionOrderType,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) String dispatchOrigin,
            @RequestParam(required = false) Boolean receiptDifference,
            @RequestParam(required = false) Boolean pendingProduction,
            @RequestParam(required = false) String person,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) throws BusinessException {
        return ResponseEntity.ok(shipmentSearchService.search(ShipmentSearchRequest.builder()
                .dateField(dateField).from(from).to(to)
                .kioskIds(kioskIds).provenances(provenances).statuses(statuses)
                .shipmentNumber(shipmentNumber).distributionNumber(distributionNumber)
                .productionOrderCode(productionOrderCode).productionOrderType(productionOrderType)
                .product(product).dispatchOrigin(dispatchOrigin)
                .receiptDifference(receiptDifference).pendingProduction(pendingProduction)
                .person(person).notes(notes).order(order).page(page).size(size)
                .build()));
    }

    /** Kioscos (y supervisoras) que el usuario puede elegir en el filtro. */
    @GetMapping("/search/options")
    public ResponseEntity<ShipmentSearchOptionsResponse> options() throws BusinessException {
        return ResponseEntity.ok(shipmentSearchService.options());
    }
}
