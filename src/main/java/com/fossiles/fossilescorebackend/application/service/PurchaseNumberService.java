package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PurchaseNumberRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PurchaseNumberItemRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseNumberResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PurchaseNumberItemResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MinorExpenseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseNumberEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PurchaseNumberItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MinorExpenseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseCompensationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseNumberRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseNumberItemRepository;
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
public class PurchaseNumberService {

    private final PurchaseNumberRepository purchaseNumberRepository;
    private final PurchaseNumberItemRepository purchaseNumberItemRepository;
    private final MinorExpenseRepository minorExpenseRepository;
    private final PurchaseCompensationRepository purchaseCompensationRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    /**
     * Genera el siguiente número de compra automáticamente
     */
    private String generateNextPurchaseNumber() {
        List<PurchaseNumberEntity> lastNumbers = purchaseNumberRepository.findLastByPrefix("COMP-");
        if (lastNumbers.isEmpty()) {
            return "COMP-00001";
        }
        
        PurchaseNumberEntity last = lastNumbers.get(0);
        String lastNumber = last.getPurchaseNumber();
        try {
            String numberPart = lastNumber.substring(5); // "COMP-00001" -> "00001"
            int nextNumber = Integer.parseInt(numberPart) + 1;
            return String.format("COMP-%05d", nextNumber);
        } catch (Exception e) {
            // Si hay error, buscar el máximo ID
            Long maxId = purchaseNumberRepository.findAll().stream()
                .mapToLong(PurchaseNumberEntity::getId)
                .max()
                .orElse(0L);
            return String.format("COMP-%05d", maxId + 1);
        }
    }

    public PurchaseNumberResponse createPurchaseNumber(PurchaseNumberRequest request) throws BusinessException {
        String purchaseNumber = request.getPurchaseNumber();
        
        // Si no se proporciona, generar automáticamente
        if (purchaseNumber == null || purchaseNumber.trim().isEmpty()) {
            purchaseNumber = generateNextPurchaseNumber();
        } else {
            // Validar que no exista
            if (purchaseNumberRepository.existsByPurchaseNumber(purchaseNumber)) {
                throw new BusinessException("El número de compra ya existe: " + purchaseNumber);
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        String status = request.getStatus() != null ? request.getStatus() : "PENDIENTE";

        PurchaseNumberEntity entity = PurchaseNumberEntity.builder()
                .purchaseNumber(purchaseNumber)
                .status(status)
                .description(request.getDescription())
                .totalAmount(request.getTotalAmount()) // Puede ser null, se calculará desde items
                .createdBy(currentUserId)
                .build();

        PurchaseNumberEntity saved = purchaseNumberRepository.save(entity);
        
        // Si no se proporciona totalAmount, calcularlo desde items (si hay)
        // Por ahora se deja como está, se actualizará cuando se agreguen items
        return toResponse(saved);
    }

    public PurchaseNumberResponse updatePurchaseNumber(Long id, PurchaseNumberRequest request) 
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberEntity entity = purchaseNumberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number", id));

        // Validar que todas las facturas no estén aprobadas (PAGADO)
        List<MinorExpenseEntity> expenses = minorExpenseRepository.findByPurchaseNumberId(id);
        
        if (!expenses.isEmpty()) {
            boolean allApproved = expenses.stream()
                    .allMatch(exp -> "PAGADO".equals(exp.getReimbursementStatus()));
            
            if (allApproved) {
                throw new BusinessException("No se puede editar el número de compra porque todas sus facturas ya han sido aprobadas (reembolsos pagados)");
            }
        }

        // Si se cambia el número, validar que no exista
        if (request.getPurchaseNumber() != null && !request.getPurchaseNumber().equals(entity.getPurchaseNumber())) {
            if (purchaseNumberRepository.existsByPurchaseNumber(request.getPurchaseNumber())) {
                throw new BusinessException("El número de compra ya existe: " + request.getPurchaseNumber());
            }
            entity.setPurchaseNumber(request.getPurchaseNumber());
        }

        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        // Si se proporciona totalAmount manualmente, usarlo
        // Si no, calcularlo desde los items
        if (request.getTotalAmount() != null) {
            entity.setTotalAmount(request.getTotalAmount());
        } else {
            // Calcular totalAmount desde items
            BigDecimal calculatedTotal = calculateTotalFromItems(entity.getId());
            if (calculatedTotal != null) {
                entity.setTotalAmount(calculatedTotal);
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        entity.setUpdatedBy(currentUserId);

        PurchaseNumberEntity saved = purchaseNumberRepository.save(entity);
        return toResponse(saved);
    }

    public PurchaseNumberResponse getPurchaseNumberById(Long id) throws ResourceNotFoundException {
        PurchaseNumberEntity entity = purchaseNumberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number", id));
        return toResponse(entity);
    }

    public List<PurchaseNumberResponse> getAllPurchaseNumbers() {
        return purchaseNumberRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<PurchaseNumberResponse> getAvailablePurchaseNumbers() {
        return purchaseNumberRepository.findAvailablePurchaseNumbers().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<PurchaseNumberResponse> getPurchaseNumbersByStatus(String status) {
        return purchaseNumberRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public void deletePurchaseNumber(Long id) throws ResourceNotFoundException, BusinessException {
        PurchaseNumberEntity entity = purchaseNumberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number", id));

        // Verificar si tiene gastos asociados
        long expenseCount = minorExpenseRepository.countByPurchaseNumberId(id);
        if (expenseCount > 0) {
            throw new BusinessException("No se puede eliminar el número de compra porque tiene " + 
                expenseCount + " gasto(s) asociado(s)");
        }

        // Eliminar items asociados
        purchaseNumberItemRepository.deleteByPurchaseNumberId(id);

        purchaseNumberRepository.delete(entity);
    }

    // ========== PURCHASE NUMBER ITEMS ==========

    public PurchaseNumberItemResponse createPurchaseNumberItem(Long purchaseNumberId, PurchaseNumberItemRequest request) 
            throws ResourceNotFoundException, BusinessException {
        // Validar que la compra existe
        PurchaseNumberEntity purchaseNumber = purchaseNumberRepository.findById(purchaseNumberId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number", purchaseNumberId));

        // Validar que la compra sea editable
        List<MinorExpenseEntity> expenses = minorExpenseRepository.findByPurchaseNumberId(purchaseNumberId);
        if (!expenses.isEmpty()) {
            boolean allApproved = expenses.stream()
                    .allMatch(exp -> "PAGADO".equals(exp.getReimbursementStatus()));
            if (allApproved) {
                throw new BusinessException("No se puede agregar artículos porque todas las facturas ya han sido aprobadas");
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();

        PurchaseNumberItemEntity item = PurchaseNumberItemEntity.builder()
                .purchaseNumberId(purchaseNumberId)
                .itemName(request.getItemName())
                .description(request.getDescription())
                .supplier(normalizeItemSupplier(request.getSupplier()))
                .estimatedPrice(request.getEstimatedPrice())
                .quantity(request.getQuantity())
                .createdBy(currentUserId)
                .build();

        PurchaseNumberItemEntity saved = purchaseNumberItemRepository.save(item);

        // Actualizar totalAmount de la compra
        updatePurchaseNumberTotalAmount(purchaseNumberId);

        return toItemResponse(saved);
    }

    public PurchaseNumberItemResponse updatePurchaseNumberItem(Long purchaseNumberId, Long itemId, PurchaseNumberItemRequest request) 
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberItemEntity item = purchaseNumberItemRepository.findByIdAndPurchaseNumberId(itemId, purchaseNumberId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number Item", itemId));

        // Validar que no tenga un gasto asociado
        if (item.getMinorExpenseId() != null) {
            throw new BusinessException("No se puede editar el artículo porque ya tiene un gasto asociado");
        }

        item.setItemName(request.getItemName());
        item.setDescription(request.getDescription());
        item.setSupplier(normalizeItemSupplier(request.getSupplier()));
        item.setEstimatedPrice(request.getEstimatedPrice());
        item.setQuantity(request.getQuantity());
        
        Long currentUserId = securityUtil.getCurrentUserId();
        item.setUpdatedBy(currentUserId);

        PurchaseNumberItemEntity saved = purchaseNumberItemRepository.save(item);

        // Actualizar totalAmount de la compra
        updatePurchaseNumberTotalAmount(purchaseNumberId);

        return toItemResponse(saved);
    }

    public void deletePurchaseNumberItem(Long purchaseNumberId, Long itemId) 
            throws ResourceNotFoundException, BusinessException {
        PurchaseNumberItemEntity item = purchaseNumberItemRepository.findByIdAndPurchaseNumberId(itemId, purchaseNumberId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number Item", itemId));

        // Validar que no tenga un gasto asociado
        if (item.getMinorExpenseId() != null) {
            throw new BusinessException("No se puede eliminar el artículo porque ya tiene un gasto asociado");
        }

        purchaseNumberItemRepository.delete(item);

        // Actualizar totalAmount de la compra
        updatePurchaseNumberTotalAmount(purchaseNumberId);
    }

    public List<PurchaseNumberItemResponse> getPurchaseNumberItems(Long purchaseNumberId) {
        return purchaseNumberItemRepository.findByPurchaseNumberId(purchaseNumberId).stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());
    }

    public PurchaseNumberItemResponse getPurchaseNumberItemById(Long purchaseNumberId, Long itemId) 
            throws ResourceNotFoundException {
        PurchaseNumberItemEntity item = purchaseNumberItemRepository.findByIdAndPurchaseNumberId(itemId, purchaseNumberId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number Item", itemId));
        return toItemResponse(item);
    }

    /**
     * Calcula el totalAmount de una compra desde sus items
     */
    private BigDecimal calculateTotalFromItems(Long purchaseNumberId) {
        List<PurchaseNumberItemEntity> items = purchaseNumberItemRepository.findByPurchaseNumberId(purchaseNumberId);
        if (items.isEmpty()) {
            return null;
        }
        return items.stream()
                .map(PurchaseNumberItemEntity::getEstimatedTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Actualiza el totalAmount de una compra basándose en sus items
     */
    private void updatePurchaseNumberTotalAmount(Long purchaseNumberId) {
        PurchaseNumberEntity purchaseNumber = purchaseNumberRepository.findById(purchaseNumberId)
                .orElse(null);
        if (purchaseNumber != null) {
            BigDecimal calculatedTotal = calculateTotalFromItems(purchaseNumberId);
            if (calculatedTotal != null) {
                purchaseNumber.setTotalAmount(calculatedTotal);
                purchaseNumberRepository.save(purchaseNumber);
            }
        }
    }

    private PurchaseNumberItemResponse toItemResponse(PurchaseNumberItemEntity entity) {
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;

        // Calcular diferencia de precio
        BigDecimal priceDifference = null;
        if (entity.getActualPrice() != null && entity.getEstimatedPrice() != null) {
            priceDifference = entity.getActualPrice().subtract(entity.getEstimatedPrice());
        }

        return PurchaseNumberItemResponse.builder()
                .id(entity.getId())
                .purchaseNumberId(entity.getPurchaseNumberId())
                .itemName(entity.getItemName())
                .description(entity.getDescription())
                .supplier(entity.getSupplier())
                .estimatedPrice(entity.getEstimatedPrice())
                .quantity(entity.getQuantity())
                .estimatedTotal(entity.getEstimatedTotal())
                .actualPrice(entity.getActualPrice())
                .minorExpenseId(entity.getMinorExpenseId())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .priceDifference(priceDifference)
                .isPurchased(entity.getMinorExpenseId() != null)
                .build();
    }

    private PurchaseNumberResponse toResponse(PurchaseNumberEntity entity) {
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;

        // Contar y obtener gastos asociados
        List<MinorExpenseEntity> expenses = minorExpenseRepository.findByPurchaseNumberId(entity.getId());
        long expenseCount = expenses.size();

        // Verificar si es editable
        boolean isEditable = expenses.isEmpty() || expenses.stream()
                .noneMatch(exp -> "PAGADO".equals(exp.getReimbursementStatus()));

        // ====== Calcular balance contable ======
        BigDecimal totalAmount = entity.getTotalAmount() != null ? entity.getTotalAmount() : BigDecimal.ZERO;

        // Total gastado real (suma de totalAmount de cada factura/gasto)
        BigDecimal totalSpent = expenses.stream()
                .map(MinorExpenseEntity::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Balance bruto: positivo = sobrante, negativo = faltante
        BigDecimal rawBalance = totalAmount.subtract(totalSpent);

        // Compensaciones
        BigDecimal compensationsGiven = purchaseCompensationRepository.sumCompensationsGiven(entity.getId());
        BigDecimal compensationsReceived = purchaseCompensationRepository.sumCompensationsReceived(entity.getId());

        // Balance neto = rawBalance - lo que cedió + lo que recibió
        BigDecimal netBalance = rawBalance.subtract(compensationsGiven).add(compensationsReceived);

        return PurchaseNumberResponse.builder()
                .id(entity.getId())
                .purchaseNumber(entity.getPurchaseNumber())
                .status(entity.getStatus())
                .description(entity.getDescription())
                .totalAmount(totalAmount)
                .totalSpent(totalSpent)
                .rawBalance(rawBalance)
                .compensationsGiven(compensationsGiven)
                .compensationsReceived(compensationsReceived)
                .netBalance(netBalance)
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .expenseCount(expenseCount)
                .editable(isEditable)
                .build();
    }

    private String normalizeItemSupplier(String supplier) {
        if (supplier == null || supplier.isBlank()) {
            return "Pendiente";
        }
        return supplier.trim();
    }
}

