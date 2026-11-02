package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaxInvoiceRepository extends JpaRepository<TaxInvoiceEntity, Long>, TaxInvoiceRepositoryCustom {

    Optional<TaxInvoiceEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    Optional<TaxInvoiceEntity> findFirstByFelTransactionIdIgnoreCase(String felTransactionId);

    List<TaxInvoiceEntity> findBySourceTypeAndSourceIdOrderByIdDesc(String sourceType, Long sourceId);

    /** Facturas de varias fuentes en una sola consulta (listados de ventas en linea). */
    List<TaxInvoiceEntity> findBySourceTypeAndSourceIdInOrderByIdDesc(
            String sourceType, java.util.Collection<Long> sourceIds);

    boolean existsBySourceTypeAndSourceIdAndStatus(String sourceType, Long sourceId, String status);

    /** Notas NCRE/NDEB ligadas a una factura. */
    List<TaxInvoiceEntity> findByOriginInvoiceIdOrderByIdAsc(Long originInvoiceId);

    /** Bloquea la factura origen mientras se valida el saldo y se emite una nota. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaxInvoiceEntity t WHERE t.id = :id")
    Optional<TaxInvoiceEntity> findWithLockById(@Param("id") Long id);
}
