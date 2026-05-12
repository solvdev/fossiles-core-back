package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OnlineSaleRepository extends JpaRepository<OnlineSaleEntity, Long> {

    List<OnlineSaleEntity> findBySaleDateOrderByIdAsc(LocalDate saleDate);

    List<OnlineSaleEntity> findBySalespersonOrderBySaleDateDesc(String salesperson);

    List<OnlineSaleEntity> findBySaleDateAndSalespersonOrderByIdAsc(LocalDate saleDate, String salesperson);

    List<OnlineSaleEntity> findByStatusOrderBySaleDateDesc(String status);

    List<OnlineSaleEntity> findByPaymentMethodOrderBySaleDateDesc(String paymentMethod);

    List<OnlineSaleEntity> findBySocialNetworkOrderBySaleDateDesc(String socialNetwork);

    List<OnlineSaleEntity> findBySaleDateBetweenOrderBySaleDateDesc(LocalDate startDate, LocalDate endDate);

    /** Ventas elegibles para orden de producción (pagadas y no asignadas aún) */
    @Query("SELECT s FROM OnlineSaleEntity s WHERE s.inProductionOrder = false " +
           "AND s.status NOT IN ('EN_PRODUCCION', 'PRODUCIDO', 'ENVIADO', 'ENTREGADO', 'ANULADA', 'CANCELADO', 'DEVOLUCION') " +
           "AND (s.paymentMethod = 'CONTRA_ENTREGA' " +
           "OR s.paymentMethod = 'CONTRA_ENTREGA_DEPOSITO' " +
           "OR s.paymentMethod = 'TRANSFERENCIA_LISTA' " +
           "OR s.paymentMethod = 'VISALINK_PAGADO' " +
           "OR s.paymentMethod = 'TARJETA_PAGADO' " +
           "OR s.paymentMethod = 'DEPOSITO_LISTO') " +
           "ORDER BY s.saleDate ASC, s.id ASC")
    List<OnlineSaleEntity> findEligibleForProduction();

    /** Ventas elegibles para producción filtradas por rango de fechas */
    @Query("SELECT s FROM OnlineSaleEntity s WHERE s.inProductionOrder = false " +
           "AND s.status NOT IN ('EN_PRODUCCION', 'PRODUCIDO', 'ENVIADO', 'ENTREGADO', 'ANULADA', 'CANCELADO', 'DEVOLUCION') " +
           "AND (s.paymentMethod = 'CONTRA_ENTREGA' " +
           "OR s.paymentMethod = 'CONTRA_ENTREGA_DEPOSITO' " +
           "OR s.paymentMethod = 'TRANSFERENCIA_LISTA' " +
           "OR s.paymentMethod = 'VISALINK_PAGADO' " +
           "OR s.paymentMethod = 'TARJETA_PAGADO' " +
           "OR s.paymentMethod = 'DEPOSITO_LISTO') " +
           "AND s.saleDate BETWEEN :startDate AND :endDate " +
           "ORDER BY s.saleDate ASC, s.id ASC")
    List<OnlineSaleEntity> findEligibleForProductionByDateRange(@Param("startDate") LocalDate startDate,
                                                                 @Param("endDate") LocalDate endDate);

    /** Contar ventas del día para generar número correlativo */
    @Query("SELECT COUNT(s) FROM OnlineSaleEntity s WHERE s.saleDate = :date")
    long countBySaleDate(@Param("date") LocalDate date);

    /** Verificar si ya existe una venta con el mismo número y fecha (deduplicación CSV) */
    boolean existsBySaleNumberAndSaleDate(String saleNumber, LocalDate saleDate);

    /** Total de ventas por fecha (para resumen) */
    @Query("SELECT COALESCE(SUM(s.totalAmount), 0) FROM OnlineSaleEntity s WHERE s.saleDate = :date")
    java.math.BigDecimal sumTotalAmountBySaleDate(@Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(s.netAmount), 0) FROM OnlineSaleEntity s WHERE s.saleDate = :date")
    java.math.BigDecimal sumNetAmountBySaleDate(@Param("date") LocalDate date);

    List<OnlineSaleEntity> findByProductionOrderId(Long productionOrderId);

    @Query("SELECT s.shipmentNumber FROM OnlineSaleEntity s WHERE s.shipmentNumber IS NOT NULL ORDER BY s.id DESC")
    List<String> findAllShipmentNumbers();
}

