package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.MinorExpenseRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ExpenseCategoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MinorExpenseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MinorExpenseSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class MinorExpenseService {

    private final MinorExpenseRepository minorExpenseRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseNumberRepository purchaseNumberRepository;
    private final com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PurchaseNumberItemRepository purchaseNumberItemRepository;

    public MinorExpenseResponse createMinorExpense(MinorExpenseRequest request) throws BusinessException, ResourceNotFoundException {
        // Validar número de factura único
        if (minorExpenseRepository.existsByInvoiceNumber(request.getInvoiceNumber())) {
            throw new BusinessException("El número de factura ya existe: " + request.getInvoiceNumber());
        }

        BigDecimal companyAmount = request.getCompanyAmount() != null ? request.getCompanyAmount() : BigDecimal.ZERO;
        BigDecimal messengerAmount = request.getMessengerAmount() != null ? request.getMessengerAmount() : BigDecimal.ZERO;
        BigDecimal initialAmountGiven = request.getInitialAmountGiven() != null ? request.getInitialAmountGiven() : BigDecimal.ZERO;
        BigDecimal returnedAmount = request.getReturnedAmount() != null ? request.getReturnedAmount() : BigDecimal.ZERO;

        // Validar según el método de pago inicial
        if ("EMPRESA".equals(request.getInitialPaymentMethod())) {
            // Cuando la empresa paga: companyAmount debe ser igual a totalAmount
            // messengerAmount es el vuelto a recibir (no se suma al total)
            if (companyAmount.compareTo(request.getTotalAmount()) != 0) {
                throw new BusinessException("El monto empresa (" + companyAmount + ") debe ser igual al monto total (" + 
                    request.getTotalAmount() + ") cuando la empresa paga inicialmente");
            }
            
            // Validar y calcular monto devuelto (caja chica) cuando la empresa paga
            if (initialAmountGiven.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal calculatedReturned = initialAmountGiven.subtract(request.getTotalAmount());
                if (calculatedReturned.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("El monto inicial dado (" + initialAmountGiven + ") no puede ser menor al monto total gastado (" + 
                        request.getTotalAmount() + ")");
                }
                // El vuelto a recibir debe ser igual al monto devuelto
                if (messengerAmount.compareTo(calculatedReturned) != 0) {
                    throw new BusinessException("El vuelto a recibir (" + messengerAmount + ") debe ser igual al monto devuelto calculado (" + 
                        calculatedReturned + ") = monto inicial (" + initialAmountGiven + ") - monto total (" + request.getTotalAmount() + ")");
                }
                // Si no se proporciona el monto devuelto, calcularlo automáticamente
                if (returnedAmount.compareTo(BigDecimal.ZERO) == 0) {
                    returnedAmount = calculatedReturned;
                } else {
                    // Validar que el monto devuelto proporcionado sea correcto
                    if (returnedAmount.compareTo(calculatedReturned) != 0) {
                        throw new BusinessException("El monto devuelto (" + returnedAmount + ") debe ser igual a " +
                            "monto inicial dado (" + initialAmountGiven + ") menos monto total (" + request.getTotalAmount() + ") = " + calculatedReturned);
                    }
                }
            }
        } else {
            // Cuando el mensajero paga: companyAmount + messengerAmount = totalAmount
            BigDecimal total = companyAmount.add(messengerAmount);
            if (total.compareTo(request.getTotalAmount()) != 0) {
                throw new BusinessException("La suma de monto empresa (" + companyAmount + ") y monto mensajero (" + 
                    messengerAmount + ") debe ser igual al monto total (" + request.getTotalAmount() + ")");
            }
        }

        // Validar fecha de reembolso
        if (request.getReimbursementDate() != null && request.getPurchaseDate() != null) {
            if (request.getReimbursementDate().isBefore(request.getPurchaseDate())) {
                throw new BusinessException("La fecha de reembolso no puede ser anterior a la fecha de compra");
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();

        // Si viene de un PurchaseNumberItem, obtener el estimatedPrice del item
        BigDecimal estimatedPrice = request.getEstimatedPrice();
        if (request.getPurchaseNumberItemId() != null && estimatedPrice == null) {
            PurchaseNumberItemEntity item = purchaseNumberItemRepository.findById(request.getPurchaseNumberItemId())
                    .orElse(null);
            if (item != null) {
                estimatedPrice = item.getEstimatedPrice();
            }
        }

        MinorExpenseEntity entity = MinorExpenseEntity.builder()
                .invoiceNumber(request.getInvoiceNumber())
                .purchaseDate(request.getPurchaseDate())
                .description(request.getDescription())
                .supplier(request.getSupplier())
                .totalAmount(request.getTotalAmount())
                .purchaserName(request.getPurchaserName())
                .authorizerName(request.getAuthorizerName())
                .companyAmount(companyAmount)
                .messengerAmount(messengerAmount)
                .initialAmountGiven(initialAmountGiven)
                .returnedAmount(returnedAmount)
                .reimbursementStatus(request.getReimbursementStatus() != null ? request.getReimbursementStatus() : "NO_APLICA")
                .reimbursementDate(request.getReimbursementDate())
                .reimbursementPaymentMethod(request.getReimbursementPaymentMethod())
                .reimbursementAdjustment(request.getReimbursementAdjustment())
                .initialPaymentMethod(request.getInitialPaymentMethod())
                .observations(request.getObservations())
                .invoiceFileUrl(request.getInvoiceFileUrl())
                .purchaseNumberId(request.getPurchaseNumberId())
                .purchaseNumberItemId(request.getPurchaseNumberItemId())
                .estimatedPrice(estimatedPrice)
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        MinorExpenseEntity saved = minorExpenseRepository.save(entity);
        
        // Si viene de un PurchaseNumberItem, actualizar el item con el actualPrice y minorExpenseId
        if (request.getPurchaseNumberItemId() != null) {
            PurchaseNumberItemEntity item = purchaseNumberItemRepository.findById(request.getPurchaseNumberItemId())
                    .orElse(null);
            if (item != null) {
                // Calcular precio unitario real (totalAmount / quantity)
                BigDecimal actualPrice = request.getTotalAmount();
                if (item.getQuantity() != null && item.getQuantity() > 0) {
                    actualPrice = request.getTotalAmount().divide(BigDecimal.valueOf(item.getQuantity()), 2, java.math.RoundingMode.HALF_UP);
                }
                item.setActualPrice(actualPrice);
                item.setMinorExpenseId(saved.getId());
                purchaseNumberItemRepository.save(item);
            }
        }
        
        return toResponse(saved);
    }

    public MinorExpenseResponse updateMinorExpense(Long id, MinorExpenseRequest request) throws BusinessException, ResourceNotFoundException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));

        // Validar que no se puede eliminar/editar si tiene reembolso pagado
        if ("PAGADO".equals(entity.getReimbursementStatus())) {
            throw new BusinessException("No se puede modificar un gasto con reembolso ya pagado");
        }

        // Validar número de factura único (excepto el actual)
        if (!entity.getInvoiceNumber().equals(request.getInvoiceNumber()) && 
            minorExpenseRepository.existsByInvoiceNumber(request.getInvoiceNumber())) {
            throw new BusinessException("El número de factura ya existe: " + request.getInvoiceNumber());
        }

        BigDecimal companyAmount = request.getCompanyAmount() != null ? request.getCompanyAmount() : BigDecimal.ZERO;
        BigDecimal messengerAmount = request.getMessengerAmount() != null ? request.getMessengerAmount() : BigDecimal.ZERO;
        BigDecimal initialAmountGiven = request.getInitialAmountGiven() != null ? request.getInitialAmountGiven() : BigDecimal.ZERO;
        BigDecimal returnedAmount = request.getReturnedAmount() != null ? request.getReturnedAmount() : BigDecimal.ZERO;

        // Validar según el método de pago inicial
        if ("EMPRESA".equals(request.getInitialPaymentMethod())) {
            // Cuando la empresa paga: companyAmount debe ser igual a totalAmount
            // messengerAmount es el vuelto a recibir (no se suma al total)
            if (companyAmount.compareTo(request.getTotalAmount()) != 0) {
                throw new BusinessException("El monto empresa (" + companyAmount + ") debe ser igual al monto total (" + 
                    request.getTotalAmount() + ") cuando la empresa paga inicialmente");
            }
            
            // Validar y calcular monto devuelto (caja chica) cuando la empresa paga
            if (initialAmountGiven.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal calculatedReturned = initialAmountGiven.subtract(request.getTotalAmount());
                if (calculatedReturned.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("El monto inicial dado (" + initialAmountGiven + ") no puede ser menor al monto total gastado (" + 
                        request.getTotalAmount() + ")");
                }
                // El vuelto a recibir debe ser igual al monto devuelto
                if (messengerAmount.compareTo(calculatedReturned) != 0) {
                    throw new BusinessException("El vuelto a recibir (" + messengerAmount + ") debe ser igual al monto devuelto calculado (" + 
                        calculatedReturned + ") = monto inicial (" + initialAmountGiven + ") - monto total (" + request.getTotalAmount() + ")");
                }
                // Si no se proporciona el monto devuelto, calcularlo automáticamente
                if (returnedAmount.compareTo(BigDecimal.ZERO) == 0) {
                    returnedAmount = calculatedReturned;
                } else {
                    // Validar que el monto devuelto proporcionado sea correcto
                    if (returnedAmount.compareTo(calculatedReturned) != 0) {
                        throw new BusinessException("El monto devuelto (" + returnedAmount + ") debe ser igual a " +
                            "monto inicial dado (" + initialAmountGiven + ") menos monto total (" + request.getTotalAmount() + ") = " + calculatedReturned);
                    }
                }
            }
        } else {
            // Cuando el mensajero paga: companyAmount + messengerAmount = totalAmount
            BigDecimal total = companyAmount.add(messengerAmount);
            if (total.compareTo(request.getTotalAmount()) != 0) {
                throw new BusinessException("La suma de monto empresa (" + companyAmount + ") y monto mensajero (" + 
                    messengerAmount + ") debe ser igual al monto total (" + request.getTotalAmount() + ")");
            }
        }

        // Validaciones adicionales (mismas que en create)
        if (request.getReimbursementDate() != null && request.getPurchaseDate() != null) {
            if (request.getReimbursementDate().isBefore(request.getPurchaseDate())) {
                throw new BusinessException("La fecha de reembolso no puede ser anterior a la fecha de compra");
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();

        entity.setInvoiceNumber(request.getInvoiceNumber());
        entity.setPurchaseDate(request.getPurchaseDate());
        entity.setDescription(request.getDescription());
        entity.setSupplier(request.getSupplier());
        entity.setTotalAmount(request.getTotalAmount());
        entity.setPurchaserName(request.getPurchaserName());
        entity.setAuthorizerName(request.getAuthorizerName());
        entity.setCompanyAmount(companyAmount);
        entity.setMessengerAmount(messengerAmount);
        entity.setInitialAmountGiven(initialAmountGiven);
        entity.setReturnedAmount(returnedAmount);
        entity.setReimbursementStatus(request.getReimbursementStatus() != null ? request.getReimbursementStatus() : "NO_APLICA");
        entity.setReimbursementDate(request.getReimbursementDate());
        entity.setReimbursementPaymentMethod(request.getReimbursementPaymentMethod());
        entity.setReimbursementAdjustment(request.getReimbursementAdjustment());
        entity.setInitialPaymentMethod(request.getInitialPaymentMethod());
        entity.setObservations(request.getObservations());
        entity.setInvoiceFileUrl(request.getInvoiceFileUrl());
        entity.setPurchaseNumberId(request.getPurchaseNumberId());
        entity.setPurchaseNumberItemId(request.getPurchaseNumberItemId());
        if (request.getEstimatedPrice() != null) {
            entity.setEstimatedPrice(request.getEstimatedPrice());
        }
        entity.setUpdatedBy(currentUserId);
        
        // Si viene de un PurchaseNumberItem, actualizar el item con el actualPrice
        if (request.getPurchaseNumberItemId() != null) {
            PurchaseNumberItemEntity item = purchaseNumberItemRepository.findById(request.getPurchaseNumberItemId())
                    .orElse(null);
            if (item != null) {
                // Calcular precio unitario real (totalAmount / quantity)
                BigDecimal actualPrice = request.getTotalAmount();
                if (item.getQuantity() != null && item.getQuantity() > 0) {
                    actualPrice = request.getTotalAmount().divide(BigDecimal.valueOf(item.getQuantity()), 2, java.math.RoundingMode.HALF_UP);
                }
                item.setActualPrice(actualPrice);
                purchaseNumberItemRepository.save(item);
            }
        }

        MinorExpenseEntity saved = minorExpenseRepository.save(entity);
        return toResponse(saved);
    }

    public void deleteMinorExpense(Long id) throws BusinessException, ResourceNotFoundException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));

        // No permitir eliminar si tiene reembolso pagado
        if ("PAGADO".equals(entity.getReimbursementStatus())) {
            throw new BusinessException("No se puede eliminar un gasto con reembolso ya pagado");
        }

        minorExpenseRepository.delete(entity);
    }

    public MinorExpenseResponse getMinorExpenseById(Long id) throws ResourceNotFoundException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));
        return toResponse(entity);
    }

    public List<MinorExpenseResponse> getAllMinorExpenses(
            LocalDate startDate, LocalDate endDate, String supplier, 
            String purchaserName, String reimbursementStatus,
            String invoiceNumber, String description, Long purchaseNumberId) {
        List<MinorExpenseEntity> entities;
        
        // Si se especifica purchaseNumberId, filtrar por ese
        if (purchaseNumberId != null) {
            entities = minorExpenseRepository.findByPurchaseNumberId(purchaseNumberId);
        } else {
            entities = minorExpenseRepository.findWithFilters(
                    startDate, endDate, supplier, purchaserName, 
                    reimbursementStatus, invoiceNumber, description);
        }
        
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }
    
    public List<MinorExpenseResponse> getExpensesByPurchaseNumberId(Long purchaseNumberId) {
        List<MinorExpenseEntity> entities = minorExpenseRepository.findByPurchaseNumberId(purchaseNumberId);
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<MinorExpenseResponse> getPendingReimbursements() {
        List<MinorExpenseEntity> entities = minorExpenseRepository.findByReimbursementStatus("PENDIENTE");
        return entities.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public MinorExpenseResponse markReimbursementAsPaid(Long id, LocalDate paymentDate, String paymentMethod) 
            throws BusinessException, ResourceNotFoundException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));

        if (!"PENDIENTE".equals(entity.getReimbursementStatus())) {
            throw new BusinessException("Solo se pueden marcar como pagados los reembolsos pendientes");
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        entity.setReimbursementStatus("PAGADO");
        entity.setReimbursementDate(paymentDate != null ? paymentDate : LocalDate.now());
        entity.setReimbursementPaymentMethod(paymentMethod);
        entity.setUpdatedBy(currentUserId);

        MinorExpenseEntity saved = minorExpenseRepository.save(entity);
        return toResponse(saved);
    }

    public List<MinorExpenseResponse> getReimbursementHistoryByPerson(String personName) {
        List<MinorExpenseEntity> entities = minorExpenseRepository.findByPurchaserName(personName);
        return entities.stream()
                .filter(e -> e.getMessengerAmount() != null && e.getMessengerAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public MinorExpenseSummaryResponse getSummary(LocalDate startDate, LocalDate endDate) {
        List<MinorExpenseEntity> expenses;
        if (startDate != null && endDate != null) {
            expenses = minorExpenseRepository.findByPurchaseDateBetween(startDate, endDate);
        } else {
            expenses = minorExpenseRepository.findAll();
        }

        BigDecimal totalExpenses = expenses.stream()
                .map(MinorExpenseEntity::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPendingReimbursements = expenses.stream()
                .filter(e -> "PENDIENTE".equals(e.getReimbursementStatus()))
                .map(MinorExpenseEntity::getMessengerAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Gastos por comprador
        Map<String, BigDecimal> expensesByPurchaser = expenses.stream()
                .collect(Collectors.groupingBy(
                        MinorExpenseEntity::getPurchaserName,
                        Collectors.reducing(BigDecimal.ZERO, MinorExpenseEntity::getTotalAmount, BigDecimal::add)
                ));

        // Gastos por proveedor (top 10)
        Map<String, Long> expensesBySupplier = expenses.stream()
                .collect(Collectors.groupingBy(
                        MinorExpenseEntity::getSupplier,
                        Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        // Recientes (últimos 10)
        List<MinorExpenseResponse> recentExpenses = expenses.stream()
                .sorted((e1, e2) -> e2.getCreatedAt().compareTo(e1.getCreatedAt()))
                .limit(10)
                .map(this::toResponse)
                .collect(Collectors.toList());

        long pendingCount = expenses.stream()
                .filter(e -> "PENDIENTE".equals(e.getReimbursementStatus()))
                .count();

        return MinorExpenseSummaryResponse.builder()
                .totalExpenses(totalExpenses)
                .totalPendingReimbursements(totalPendingReimbursements)
                .totalExpensesCount((long) expenses.size())
                .pendingReimbursementsCount(pendingCount)
                .expensesByPurchaser(expensesByPurchaser)
                .expensesBySupplier(expensesBySupplier)
                .recentExpenses(recentExpenses)
                .build();
    }

    // ========== HELPER METHODS ==========

    private MinorExpenseResponse toResponse(MinorExpenseEntity entity) {
        String createdByName = null;
        if (entity.getCreatedBy() != null) {
            UserEntity creator = userRepository.findById(entity.getCreatedBy()).orElse(null);
            createdByName = creator != null ? creator.getUsername() : null;
        }

        String updatedByName = null;
        if (entity.getUpdatedBy() != null) {
            UserEntity updater = userRepository.findById(entity.getUpdatedBy()).orElse(null);
            updatedByName = updater != null ? updater.getUsername() : null;
        }

        // Obtener número de compra si existe
        String purchaseNumber = null;
        String purchaseNumberDescription = null;
        if (entity.getPurchaseNumberId() != null) {
            var purchaseNumberOpt = purchaseNumberRepository.findById(entity.getPurchaseNumberId());
            if (purchaseNumberOpt.isPresent()) {
                var pn = purchaseNumberOpt.get();
                purchaseNumber = pn.getPurchaseNumber();
                purchaseNumberDescription = pn.getDescription();
            }
        }

        // Calcular monto ajustado de reembolso
        BigDecimal adjustedReimbursementAmount = BigDecimal.ZERO;
        if (entity.getMessengerAmount() != null) {
            adjustedReimbursementAmount = entity.getMessengerAmount();
            if (entity.getReimbursementAdjustment() != null) {
                adjustedReimbursementAmount = adjustedReimbursementAmount.add(entity.getReimbursementAdjustment());
            }
            // Asegurar que no sea negativo
            if (adjustedReimbursementAmount.compareTo(BigDecimal.ZERO) < 0) {
                adjustedReimbursementAmount = BigDecimal.ZERO;
            }
        }

        return MinorExpenseResponse.builder()
                .id(entity.getId())
                .invoiceNumber(entity.getInvoiceNumber())
                .purchaseDate(entity.getPurchaseDate())
                .description(entity.getDescription())
                .supplier(entity.getSupplier())
                .totalAmount(entity.getTotalAmount())
                .purchaserName(entity.getPurchaserName())
                .authorizerName(entity.getAuthorizerName())
                .companyAmount(entity.getCompanyAmount())
                .messengerAmount(entity.getMessengerAmount())
                .initialAmountGiven(entity.getInitialAmountGiven())
                .returnedAmount(entity.getReturnedAmount())
                .reimbursementStatus(entity.getReimbursementStatus())
                .reimbursementDate(entity.getReimbursementDate())
                .reimbursementPaymentMethod(entity.getReimbursementPaymentMethod())
                .reimbursementAdjustment(entity.getReimbursementAdjustment())
                .adjustedReimbursementAmount(adjustedReimbursementAmount)
                .initialPaymentMethod(entity.getInitialPaymentMethod())
                .observations(entity.getObservations())
                .invoiceFileUrl(entity.getInvoiceFileUrl())
                .purchaseNumberId(entity.getPurchaseNumberId())
                .purchaseNumber(purchaseNumber)
                .purchaseNumberDescription(purchaseNumberDescription)
                .purchaseNumberItemId(entity.getPurchaseNumberItemId())
                .estimatedPrice(entity.getEstimatedPrice())
                .fromPurchaseOrder(false) // Siempre false, ya que el nuevo sistema fue eliminado
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByName)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByName)
                .build();
    }
}

