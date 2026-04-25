package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PurchaseCompensationRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseCompensationResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MinorExpenseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseCompensationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseNumberEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MinorExpenseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseCompensationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseNumberRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PurchaseCompensationService {

    private final PurchaseCompensationRepository compensationRepository;
    private final PurchaseNumberRepository purchaseNumberRepository;
    private final MinorExpenseRepository minorExpenseRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    /**
     * Crea una compensación: transfiere sobrante de una compra (source) a otra (target).
     *
     * Reglas contables:
     * - La compra origen debe tener saldo disponible (sobrante neto > 0)
     * - El monto no puede exceder el sobrante disponible de la compra origen
     * - No se puede compensar una compra consigo misma
     */
    public PurchaseCompensationResponse createCompensation(PurchaseCompensationRequest request)
            throws BusinessException, ResourceNotFoundException {

        // Validar que ambas compras existen
        PurchaseNumberEntity source = purchaseNumberRepository.findById(request.getSourcePurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Compra origen", request.getSourcePurchaseId()));
        PurchaseNumberEntity target = purchaseNumberRepository.findById(request.getTargetPurchaseId())
                .orElseThrow(() -> new ResourceNotFoundException("Compra destino", request.getTargetPurchaseId()));

        // Validar que no son la misma compra
        if (source.getId().equals(target.getId())) {
            throw new BusinessException("No se puede compensar una compra consigo misma");
        }

        // Calcular sobrante disponible de la compra origen
        BigDecimal availableSurplus = calculateAvailableSurplus(source.getId());
        if (availableSurplus.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("La compra " + source.getPurchaseNumber() +
                    " no tiene sobrante disponible para compensar (saldo: Q" + availableSurplus + ")");
        }

        // Validar que el monto no excede el sobrante disponible
        if (request.getAmount().compareTo(availableSurplus) > 0) {
            throw new BusinessException("El monto a compensar (Q" + request.getAmount() +
                    ") excede el sobrante disponible de " + source.getPurchaseNumber() +
                    " (Q" + availableSurplus + ")");
        }

        Long currentUserId = securityUtil.getCurrentUserId();

        PurchaseCompensationEntity entity = PurchaseCompensationEntity.builder()
                .sourcePurchaseId(request.getSourcePurchaseId())
                .targetPurchaseId(request.getTargetPurchaseId())
                .amount(request.getAmount())
                .description(request.getDescription())
                .createdBy(currentUserId)
                .build();

        PurchaseCompensationEntity saved = compensationRepository.save(entity);
        return toResponse(saved);
    }

    /**
     * Elimina una compensación (revierte la transferencia).
     */
    public void deleteCompensation(Long id) throws ResourceNotFoundException {
        PurchaseCompensationEntity entity = compensationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compensación", id));
        compensationRepository.delete(entity);
    }

    /**
     * Obtiene todas las compensaciones relacionadas con una compra.
     */
    public List<PurchaseCompensationResponse> getCompensationsByPurchaseId(Long purchaseId) {
        return compensationRepository.findByPurchaseId(purchaseId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todas las compensaciones del sistema.
     */
    public List<PurchaseCompensationResponse> getAllCompensations() {
        return compensationRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Calcula el sobrante disponible de una compra (lo que puede ceder a otra).
     * Sobrante disponible = (totalAmount - totalSpent) - compensaciones ya cedidas + compensaciones recibidas
     * Solo es positivo si la compra tiene sobrante neto.
     */
    public BigDecimal calculateAvailableSurplus(Long purchaseId) {
        PurchaseNumberEntity purchase = purchaseNumberRepository.findById(purchaseId).orElse(null);
        if (purchase == null || purchase.getTotalAmount() == null) return BigDecimal.ZERO;

        BigDecimal totalAmount = purchase.getTotalAmount();

        // Total gastado real
        BigDecimal totalSpent = minorExpenseRepository.findByPurchaseNumberId(purchaseId).stream()
                .map(MinorExpenseEntity::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal rawBalance = totalAmount.subtract(totalSpent);

        // Compensaciones ya cedidas
        BigDecimal given = compensationRepository.sumCompensationsGiven(purchaseId);
        // Compensaciones recibidas
        BigDecimal received = compensationRepository.sumCompensationsReceived(purchaseId);

        // Sobrante neto disponible
        BigDecimal netBalance = rawBalance.subtract(given).add(received);
        return netBalance.max(BigDecimal.ZERO); // No puede ser negativo para ceder
    }

    /**
     * Obtiene compras con sobrante disponible para compensar.
     */
    public List<PurchaseNumberEntity> getPurchasesWithSurplus() {
        return purchaseNumberRepository.findAll().stream()
                .filter(p -> calculateAvailableSurplus(p.getId()).compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
    }

    private PurchaseCompensationResponse toResponse(PurchaseCompensationEntity entity) {
        PurchaseNumberEntity source = purchaseNumberRepository.findById(entity.getSourcePurchaseId()).orElse(null);
        PurchaseNumberEntity target = purchaseNumberRepository.findById(entity.getTargetPurchaseId()).orElse(null);
        UserEntity createdByUser = entity.getCreatedBy() != null
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) : null;

        return PurchaseCompensationResponse.builder()
                .id(entity.getId())
                .sourcePurchaseId(entity.getSourcePurchaseId())
                .sourcePurchaseNumber(source != null ? source.getPurchaseNumber() : null)
                .sourcePurchaseDescription(source != null ? source.getDescription() : null)
                .targetPurchaseId(entity.getTargetPurchaseId())
                .targetPurchaseNumber(target != null ? target.getPurchaseNumber() : null)
                .targetPurchaseDescription(target != null ? target.getDescription() : null)
                .amount(entity.getAmount())
                .description(entity.getDescription())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}

