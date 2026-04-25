package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OnlineSaleShipmentNumberService {

    private static final String SHIPMENT_PREFIX = "ENVL";
    private static final Pattern SHIPMENT_PATTERN = Pattern.compile("^ENVL-(\\d+)$");

    private final OnlineSaleRepository onlineSaleRepository;

    /** Asigna ENVL-nnnnn si la venta aún no tiene número de envío. */
    public void assignIfMissing(OnlineSaleEntity sale) {
        if (sale.getShipmentNumber() != null && !sale.getShipmentNumber().isBlank()) {
            return;
        }
        sale.setShipmentNumber(nextNumber());
    }

    public String nextNumber() {
        int maxSeq = onlineSaleRepository.findAllShipmentNumbers().stream()
                .map(SHIPMENT_PATTERN::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
        return String.format("%s-%05d", SHIPMENT_PREFIX, maxSeq + 1);
    }
}
