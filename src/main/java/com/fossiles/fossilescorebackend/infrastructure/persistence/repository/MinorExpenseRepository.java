package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MinorExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MinorExpenseRepository extends JpaRepository<MinorExpenseEntity, Long> {
    Optional<MinorExpenseEntity> findByInvoiceNumber(String invoiceNumber);
    boolean existsByInvoiceNumber(String invoiceNumber);
    
    List<MinorExpenseEntity> findByPurchaseDateBetween(LocalDate startDate, LocalDate endDate);
    List<MinorExpenseEntity> findBySupplierContainingIgnoreCase(String supplier);
    List<MinorExpenseEntity> findByPurchaserName(String purchaserName);
    List<MinorExpenseEntity> findByReimbursementStatus(String reimbursementStatus);
    List<MinorExpenseEntity> findByPurchaseNumberId(Long purchaseNumberId);
    long countByPurchaseNumberId(Long purchaseNumberId);
    
    @Query(value = "SELECT id, invoice_number, purchase_date, description, supplier, total_amount, " +
           "purchaser_name, authorizer_name, company_amount, messenger_amount, initial_amount_given, " +
           "returned_amount, reimbursement_status, reimbursement_date, reimbursement_payment_method, " +
           "reimbursement_adjustment, initial_payment_method, observations, invoice_file_url, purchase_number_id, " +
           "purchase_number_item_id, estimated_price, created_by, created_at, updated_by, updated_at " +
           "FROM minor_expense WHERE " +
           "(:startDate IS NULL OR purchase_date >= :startDate) AND " +
           "(:endDate IS NULL OR purchase_date <= :endDate) AND " +
           "(:supplier IS NULL OR :supplier = '' OR LOWER(CAST(supplier AS VARCHAR)) LIKE LOWER('%' || :supplier || '%')) AND " +
           "(:purchaserName IS NULL OR :purchaserName = '' OR LOWER(CAST(purchaser_name AS VARCHAR)) LIKE LOWER('%' || :purchaserName || '%')) AND " +
           "(:reimbursementStatus IS NULL OR :reimbursementStatus = '' OR reimbursement_status = :reimbursementStatus) AND " +
           "(:invoiceNumber IS NULL OR :invoiceNumber = '' OR LOWER(CAST(invoice_number AS VARCHAR)) LIKE LOWER('%' || :invoiceNumber || '%')) AND " +
           "(:description IS NULL OR :description = '' OR LOWER(CAST(description AS VARCHAR)) LIKE LOWER('%' || :description || '%'))",
           nativeQuery = true)
    List<MinorExpenseEntity> findWithFilters(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("supplier") String supplier,
        @Param("purchaserName") String purchaserName,
        @Param("reimbursementStatus") String reimbursementStatus,
        @Param("invoiceNumber") String invoiceNumber,
        @Param("description") String description
    );
}

