package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestSlipPrintResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestSlipSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class InternalShipmentRequestSlipService {

    public static final String SLIP_PREFIX = "BLS";
    private static final Pattern SLIP_PATTERN = Pattern.compile("^BLS-(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS_ONLY_PATTERN = Pattern.compile("^(\\d+)$");

    private final InternalShipmentRequestSlipRepository slipRepository;
    private final InternalShipmentRequestRepository requestRepository;
    private final SecurityUtil securityUtil;
    private final InternalShipmentRequestAccessGuard accessGuard;

    /**
     * Imprime/reserva un lote de boletas de solicitud con correlativo continuo (BLS-nnnnn).
     */
    @Transactional
    public synchronized InternalShipmentRequestSlipPrintResponse printBatch(int quantity) throws BusinessException {
        accessGuard.assertCanCreateRequest();
        int safeQuantity = Math.min(Math.max(quantity, 1), 500);

        int currentMax = getCurrentMaxSequence();
        List<InternalShipmentRequestSlipEntity> toSave = new ArrayList<>();
        List<String> slipNumbers = new ArrayList<>();
        Long currentUserId = securityUtil.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 1; i <= safeQuantity; i++) {
            int seq = currentMax + i;
            String number = String.format("%s-%05d", SLIP_PREFIX, seq);
            slipNumbers.add(number);

            InternalShipmentRequestSlipEntity entity = InternalShipmentRequestSlipEntity.builder()
                    .slipNumber(number)
                    .status("PRINTED")
                    .printedAt(now)
                    .printedBy(currentUserId)
                    .build();
            toSave.add(entity);
        }

        slipRepository.saveAll(toSave);

        String fromSlip = slipNumbers.get(0);
        String toSlip = slipNumbers.get(slipNumbers.size() - 1);

        return InternalShipmentRequestSlipPrintResponse.builder()
                .slipNumbers(slipNumbers)
                .quantity(safeQuantity)
                .fromSlip(fromSlip)
                .toSlip(toSlip)
                .printedAt(now)
                .build();
    }

    /**
     * Valida y asocia una boleta de solicitud física al registrar la solicitud en el sistema.
     */
    @Transactional
    public String validateAndUseSlip(String rawSlipNumber, Long requestId) throws BusinessException {
        String normalized = normalizeSlipNumber(rawSlipNumber);
        if (normalized == null || normalized.isBlank()) {
            throw new BusinessException("El número de boleta física de solicitud es obligatorio.");
        }

        var existingSlipOpt = slipRepository.findBySlipNumber(normalized);
        if (existingSlipOpt.isPresent()) {
            InternalShipmentRequestSlipEntity slip = existingSlipOpt.get();
            if ("USED".equalsIgnoreCase(slip.getStatus())) {
                throw new BusinessException("La boleta de solicitud " + normalized + " ya fue utilizada en otra solicitud.");
            }
            if ("VOIDED".equalsIgnoreCase(slip.getStatus())) {
                throw new BusinessException("La boleta de solicitud " + normalized + " fue anulada y no puede utilizarse.");
            }
            slip.setStatus("USED");
            slip.setRequestId(requestId);
            slipRepository.save(slip);
            return normalized;
        }

        // Si no está registrada en talonarios previos, verificar que al menos no colisione con otra solicitud
        if (requestRepository.existsBySlipNumber(normalized)) {
            throw new BusinessException("La boleta de solicitud " + normalized + " ya fue registrada.");
        }

        // Registrar como usada
        InternalShipmentRequestSlipEntity adHocSlip = InternalShipmentRequestSlipEntity.builder()
                .slipNumber(normalized)
                .status("USED")
                .printedAt(LocalDateTime.now())
                .printedBy(securityUtil.getCurrentUserId())
                .requestId(requestId)
                .build();
        slipRepository.save(adHocSlip);
        return normalized;
    }

    /**
     * Resumen de talonarios para información en pantalla.
     */
    @Transactional(readOnly = true)
    public InternalShipmentRequestSlipSummaryResponse getSummary() throws BusinessException {
        accessGuard.assertCanListRequests();
        int maxSeq = getCurrentMaxSequence();
        String nextSlip = String.format("%s-%05d", SLIP_PREFIX, maxSeq + 1);
        String lastPrinted = maxSeq > 0 ? String.format("%s-%05d", SLIP_PREFIX, maxSeq) : null;
        long totalPrinted = slipRepository.count();
        long totalUsed = slipRepository.countByStatus("USED");
        long totalAvailable = slipRepository.countByStatus("PRINTED");

        return InternalShipmentRequestSlipSummaryResponse.builder()
                .nextSlipNumber(nextSlip)
                .lastPrintedSlipNumber(lastPrinted)
                .totalPrinted(totalPrinted)
                .totalAvailable(totalAvailable)
                .totalUsed(totalUsed)
                .build();
    }

    public static String normalizeSlipNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher blsMatcher = SLIP_PATTERN.matcher(trimmed);
        if (blsMatcher.matches()) {
            int seq = Integer.parseInt(blsMatcher.group(1));
            return String.format("%s-%05d", SLIP_PREFIX, seq);
        }
        Matcher digitsMatcher = DIGITS_ONLY_PATTERN.matcher(trimmed);
        if (digitsMatcher.matches()) {
            int seq = Integer.parseInt(digitsMatcher.group(1));
            return String.format("%s-%05d", SLIP_PREFIX, seq);
        }
        return trimmed;
    }

    private int getCurrentMaxSequence() {
        int maxInSlips = maxSequenceFromList(slipRepository.findAllSlipNumbers());
        int maxInRequests = maxSequenceFromList(requestRepository.findAllSlipNumbers());
        return Math.max(maxInSlips, maxInRequests);
    }

    private static int maxSequenceFromList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .map(SLIP_PATTERN::matcher)
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
    }
}
