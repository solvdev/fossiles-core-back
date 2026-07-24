package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.MinorExpenseRequest;
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
    private final PurchaseNumberRepository purchaseNumberRepository;
    private final PurchaseNumberItemRepository purchaseNumberItemRepository;

    public MinorExpenseResponse createMinorExpense(MinorExpenseRequest request) throws BusinessException, ResourceNotFoundException {
        assertPurchaseAcceptsExpenseChanges(request.getPurchaseNumberId());

        if (minorExpenseRepository.existsByInvoiceNumber(request.getInvoiceNumber())) {
            throw new BusinessException("El número de factura ya existe: " + request.getInvoiceNumber());
        }

        ResolvedAmounts amounts = resolveAndValidateAmounts(request);

        if (request.getReimbursementDate() != null && request.getPurchaseDate() != null) {
            if (request.getReimbursementDate().isBefore(request.getPurchaseDate())) {
                throw new BusinessException("La fecha de reembolso no puede ser anterior a la fecha de compra");
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();

        BigDecimal estimatedPrice = request.getEstimatedPrice();
        if (request.getPurchaseNumberItemId() != null && estimatedPrice == null) {
            PurchaseNumberItemEntity item = purchaseNumberItemRepository.findById(request.getPurchaseNumberItemId())
                    .orElse(null);
            if (item != null) {
                estimatedPrice = item.getEstimatedPrice();
            }
        }

        String reimbursementStatus = resolveReimbursementStatus(request, amounts);

        MinorExpenseEntity entity = MinorExpenseEntity.builder()
                .invoiceNumber(request.getInvoiceNumber())
                .purchaseDate(request.getPurchaseDate())
                .description(request.getDescription())
                .supplier(request.getSupplier())
                .totalAmount(request.getTotalAmount())
                .purchaserName(request.getPurchaserName())
                .authorizerName(request.getAuthorizerName())
                .companyAmount(amounts.companyAmount)
                .messengerAmount(amounts.legacyMessengerAmount)
                .reimbursementAmount(amounts.reimbursementAmount)
                .initialAmountGiven(amounts.initialAmountGiven)
                .returnedAmount(amounts.returnedAmount)
                .reimbursementStatus(reimbursementStatus)
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

        if (request.getPurchaseNumberItemId() != null) {
            linkItemToExpense(request.getPurchaseNumberItemId(), saved.getId(), request.getTotalAmount());
        }

        return toResponse(saved);
    }

    public MinorExpenseResponse updateMinorExpense(Long id, MinorExpenseRequest request) throws BusinessException, ResourceNotFoundException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));

        Long purchaseNumberId = request.getPurchaseNumberId() != null
                ? request.getPurchaseNumberId()
                : entity.getPurchaseNumberId();
        assertPurchaseAcceptsExpenseChanges(purchaseNumberId);

        if ("PAGADO".equals(entity.getReimbursementStatus())) {
            throw new BusinessException("No se puede modificar un gasto con reembolso ya pagado");
        }

        if (!entity.getInvoiceNumber().equals(request.getInvoiceNumber()) &&
            minorExpenseRepository.existsByInvoiceNumber(request.getInvoiceNumber())) {
            throw new BusinessException("El número de factura ya existe: " + request.getInvoiceNumber());
        }

        ResolvedAmounts amounts = resolveAndValidateAmounts(request);

        if (request.getReimbursementDate() != null && request.getPurchaseDate() != null) {
            if (request.getReimbursementDate().isBefore(request.getPurchaseDate())) {
                throw new BusinessException("La fecha de reembolso no puede ser anterior a la fecha de compra");
            }
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        String reimbursementStatus = resolveReimbursementStatus(request, amounts);

        entity.setInvoiceNumber(request.getInvoiceNumber());
        entity.setPurchaseDate(request.getPurchaseDate());
        entity.setDescription(request.getDescription());
        entity.setSupplier(request.getSupplier());
        entity.setTotalAmount(request.getTotalAmount());
        entity.setPurchaserName(request.getPurchaserName());
        entity.setAuthorizerName(request.getAuthorizerName());
        entity.setCompanyAmount(amounts.companyAmount);
        entity.setMessengerAmount(amounts.legacyMessengerAmount);
        entity.setReimbursementAmount(amounts.reimbursementAmount);
        entity.setInitialAmountGiven(amounts.initialAmountGiven);
        entity.setReturnedAmount(amounts.returnedAmount);
        entity.setReimbursementStatus(reimbursementStatus);
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

        if (request.getPurchaseNumberItemId() != null) {
            linkItemToExpense(request.getPurchaseNumberItemId(), entity.getId(), request.getTotalAmount());
        }

        MinorExpenseEntity saved = minorExpenseRepository.save(entity);
        return toResponse(saved);
    }

    /**
     * Actualiza solo la URL de la factura, sin tocar vínculos ni montos.
     */
    public MinorExpenseResponse updateInvoiceFileUrl(Long id, String invoiceFileUrl)
            throws ResourceNotFoundException, BusinessException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));

        assertPurchaseAcceptsExpenseChanges(entity.getPurchaseNumberId());

        entity.setInvoiceFileUrl(invoiceFileUrl);
        entity.setUpdatedBy(securityUtil.getCurrentUserId());
        return toResponse(minorExpenseRepository.save(entity));
    }

    public void deleteMinorExpense(Long id) throws BusinessException, ResourceNotFoundException {
        MinorExpenseEntity entity = minorExpenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Minor Expense", id));

        if ("PAGADO".equals(entity.getReimbursementStatus())) {
            throw new BusinessException("No se puede eliminar un gasto con reembolso ya pagado");
        }

        if (entity.getPurchaseNumberItemId() != null) {
            purchaseNumberItemRepository.findById(entity.getPurchaseNumberItemId()).ifPresent(item -> {
                item.setMinorExpenseId(null);
                item.setActualPrice(null);
                purchaseNumberItemRepository.save(item);
            });
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
                .filter(e -> {
                    String status = e.getReimbursementStatus();
                    return "PENDIENTE".equals(status) || "PAGADO".equals(status);
                })
                .filter(e -> resolveReimbursementAmount(e).compareTo(BigDecimal.ZERO) > 0)
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
                .map(this::computeAdjustedReimbursement)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, BigDecimal> expensesByPurchaser = expenses.stream()
                .collect(Collectors.groupingBy(
                        MinorExpenseEntity::getPurchaserName,
                        Collectors.reducing(BigDecimal.ZERO, MinorExpenseEntity::getTotalAmount, BigDecimal::add)
                ));

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

    private static final class ResolvedAmounts {
        final BigDecimal companyAmount;
        final BigDecimal reimbursementAmount;
        final BigDecimal returnedAmount;
        final BigDecimal initialAmountGiven;
        /** Compat: MENSAJERO → reimbursement; EMPRESA → returned. */
        final BigDecimal legacyMessengerAmount;

        ResolvedAmounts(BigDecimal companyAmount, BigDecimal reimbursementAmount,
                        BigDecimal returnedAmount, BigDecimal initialAmountGiven,
                        BigDecimal legacyMessengerAmount) {
            this.companyAmount = companyAmount;
            this.reimbursementAmount = reimbursementAmount;
            this.returnedAmount = returnedAmount;
            this.initialAmountGiven = initialAmountGiven;
            this.legacyMessengerAmount = legacyMessengerAmount;
        }
    }

    private ResolvedAmounts resolveAndValidateAmounts(MinorExpenseRequest request) throws BusinessException {
        BigDecimal companyAmount = nz(request.getCompanyAmount());
        BigDecimal initialAmountGiven = nz(request.getInitialAmountGiven());
        BigDecimal returnedAmount = nz(request.getReturnedAmount());

        // Prefer reimbursementAmount; fall back to messengerAmount for older clients
        BigDecimal reimbursementAmount = request.getReimbursementAmount() != null
                ? request.getReimbursementAmount()
                : nz(request.getMessengerAmount());

        if ("EMPRESA".equals(request.getInitialPaymentMethod())) {
            if (companyAmount.compareTo(request.getTotalAmount()) != 0) {
                throw new BusinessException("El monto empresa (" + companyAmount + ") debe ser igual al monto total (" +
                    request.getTotalAmount() + ") cuando la empresa paga inicialmente");
            }

            reimbursementAmount = BigDecimal.ZERO;

            if (initialAmountGiven.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal calculatedReturned = initialAmountGiven.subtract(request.getTotalAmount());
                if (calculatedReturned.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("El monto inicial dado (" + initialAmountGiven + ") no puede ser menor al monto total gastado (" +
                        request.getTotalAmount() + ")");
                }
                if (returnedAmount.compareTo(BigDecimal.ZERO) == 0) {
                    returnedAmount = calculatedReturned;
                } else if (returnedAmount.compareTo(calculatedReturned) != 0) {
                    throw new BusinessException("El monto devuelto (" + returnedAmount + ") debe ser igual a " +
                        "monto inicial dado (" + initialAmountGiven + ") menos monto total (" + request.getTotalAmount() + ") = " + calculatedReturned);
                }
            } else {
                returnedAmount = BigDecimal.ZERO;
            }

            return new ResolvedAmounts(companyAmount, reimbursementAmount, returnedAmount,
                    initialAmountGiven, returnedAmount);
        }

        // MENSAJERO: company + reimbursement = total
        // If client still sends messengerAmount as the split and reimbursementAmount is null, already handled above.
        // If both company and reimbursement are 0 but messengerAmount was the full total, already in reimbursementAmount.
        if (request.getReimbursementAmount() == null && request.getMessengerAmount() == null
                && companyAmount.compareTo(BigDecimal.ZERO) == 0) {
            reimbursementAmount = request.getTotalAmount();
        }

        BigDecimal total = companyAmount.add(reimbursementAmount);
        if (total.compareTo(request.getTotalAmount()) != 0) {
            throw new BusinessException("La suma de monto empresa (" + companyAmount + ") y monto a reembolsar (" +
                reimbursementAmount + ") debe ser igual al monto total (" + request.getTotalAmount() + ")");
        }

        return new ResolvedAmounts(companyAmount, reimbursementAmount, BigDecimal.ZERO,
                BigDecimal.ZERO, reimbursementAmount);
    }

    private String resolveReimbursementStatus(MinorExpenseRequest request, ResolvedAmounts amounts) {
        if ("EMPRESA".equals(request.getInitialPaymentMethod())) {
            return "NO_APLICA";
        }
        if (request.getReimbursementStatus() != null && !request.getReimbursementStatus().isBlank()) {
            return request.getReimbursementStatus();
        }
        return amounts.reimbursementAmount.compareTo(BigDecimal.ZERO) > 0 ? "PENDIENTE" : "NO_APLICA";
    }

    private void linkItemToExpense(Long itemId, Long expenseId, BigDecimal totalAmount) {
        PurchaseNumberItemEntity item = purchaseNumberItemRepository.findById(itemId).orElse(null);
        if (item == null) {
            return;
        }
        BigDecimal actualPrice = totalAmount;
        if (item.getQuantity() != null && item.getQuantity() > 0) {
            actualPrice = totalAmount.divide(BigDecimal.valueOf(item.getQuantity()), 2, java.math.RoundingMode.HALF_UP);
        }
        item.setActualPrice(actualPrice);
        item.setMinorExpenseId(expenseId);
        purchaseNumberItemRepository.save(item);
    }

    private BigDecimal resolveReimbursementAmount(MinorExpenseEntity entity) {
        if (entity.getReimbursementAmount() != null) {
            return entity.getReimbursementAmount();
        }
        // Legacy: MENSAJERO stored amount in messengerAmount
        if ("MENSAJERO".equals(entity.getInitialPaymentMethod()) && entity.getMessengerAmount() != null) {
            return entity.getMessengerAmount();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal computeAdjustedReimbursement(MinorExpenseEntity entity) {
        BigDecimal base = resolveReimbursementAmount(entity);
        if (entity.getReimbursementAdjustment() != null) {
            base = base.add(entity.getReimbursementAdjustment());
        }
        return base.max(BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

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

        BigDecimal reimbursementAmount = resolveReimbursementAmount(entity);
        BigDecimal adjustedReimbursementAmount = computeAdjustedReimbursement(entity);

        // Compat: messengerAmount mirrors the meaningful amount for each mode
        BigDecimal legacyMessenger = "EMPRESA".equals(entity.getInitialPaymentMethod())
                ? nz(entity.getReturnedAmount())
                : reimbursementAmount;

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
                .messengerAmount(legacyMessenger)
                .reimbursementAmount(reimbursementAmount)
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
                .fromPurchaseOrder(false)
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByName)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByName)
                .build();
    }

    private void assertPurchaseAcceptsExpenseChanges(Long purchaseNumberId)
            throws BusinessException, ResourceNotFoundException {
        if (purchaseNumberId == null) {
            return;
        }
        PurchaseNumberEntity purchaseNumber = purchaseNumberRepository.findById(purchaseNumberId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Number", purchaseNumberId));
        if ("PAGADO".equals(purchaseNumber.getStatus())) {
            throw new BusinessException("La compra está finalizada y no se pueden modificar gastos");
        }
    }

    /**
     * True when every expense that requires reimbursement is already PAGADO.
     * NO_APLICA expenses do not count. If none require reimbursement, returns false (not locked by reimbursements).
     */
    public static boolean allApplicableReimbursementsPaid(List<MinorExpenseEntity> expenses) {
        List<MinorExpenseEntity> applicable = expenses.stream()
                .filter(e -> !"NO_APLICA".equals(e.getReimbursementStatus()))
                .filter(e -> "PENDIENTE".equals(e.getReimbursementStatus()) || "PAGADO".equals(e.getReimbursementStatus()))
                .collect(Collectors.toList());
        if (applicable.isEmpty()) {
            return false;
        }
        return applicable.stream().allMatch(e -> "PAGADO".equals(e.getReimbursementStatus()));
    }
}
