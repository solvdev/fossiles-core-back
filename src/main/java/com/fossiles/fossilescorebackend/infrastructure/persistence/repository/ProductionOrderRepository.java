package com.fossiles.fossilescorebackend.infrastructure.persistence.repository;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderRepository extends JpaRepository<ProductionOrderEntity, Long> {
    Optional<ProductionOrderEntity> findByCode(String code);
    boolean existsByCode(String code);
    List<ProductionOrderEntity> findByOrderType(String orderType);
    List<ProductionOrderEntity> findByStatus(String status);
    List<ProductionOrderEntity> findByCustomerId(Long customerId);

    @Query("""
            SELECT po FROM ProductionOrderEntity po
            WHERE UPPER(COALESCE(po.sellerName, '')) LIKE '%LUIS FELIPE%'
              AND UPPER(COALESCE(po.orderType, '')) IN ('MARCAS', 'OPV')
              AND UPPER(COALESCE(po.status, '')) <> 'CANCELLED'
            ORDER BY po.deliveryDate DESC NULLS LAST, po.createdAt DESC
            """)
    List<ProductionOrderEntity> findOpvCatalogOrders();

    @Query("""
            SELECT po FROM ProductionOrderEntity po
            WHERE po.customerId IS NOT NULL
              AND UPPER(COALESCE(po.status, '')) <> 'CANCELLED'
            ORDER BY po.deliveryDate DESC NULLS LAST, po.createdAt DESC
            """)
    List<ProductionOrderEntity> findOrdersWithCustomer();
    Optional<ProductionOrderEntity> findByDistributionId(Long distributionId);

    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.status IN :statuses ORDER BY po.deliveryDate ASC, po.createdAt ASC")
    List<ProductionOrderEntity> findByStatusIn(@Param("statuses") List<String> statuses);

    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.status NOT IN ('CANCELLED') ORDER BY po.createdAt DESC")
    List<ProductionOrderEntity> findActiveOrders();

    @Query("SELECT po.vendorShipmentNumber FROM ProductionOrderEntity po WHERE po.vendorShipmentNumber IS NOT NULL")
    List<String> findAllVendorShipmentNumbers();

    boolean existsByVendorShipmentNumber(String vendorShipmentNumber);

    @Query("SELECT COUNT(po) > 0 FROM ProductionOrderEntity po WHERE po.vendorShipmentNumber = :num AND po.id <> :excludeId")
    boolean existsByVendorShipmentNumberAndIdNot(@Param("num") String num, @Param("excludeId") Long excludeId);

    @Query("""
            SELECT DISTINCT po.customerId FROM ProductionOrderEntity po
            WHERE po.customerId IS NOT NULL
              AND (
                LOWER(COALESCE(po.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.vendorShipmentNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            """)
    List<Long> findCustomerIdsByCodeOrVendorShipment(@Param("q") String q);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM ProductionOrderEntity po WHERE po.id = :id")
    Optional<ProductionOrderEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * Búsqueda liviana para filtros de Preparar envíos (sin ítems ni joins pesados).
     * kind: OPV | OPI | OPC | OPCK | OPK
     * Columnas: id, code, customer_name, seller_name, status, order_type, vendor_shipment_number
     */
    @Query(value = """
            SELECT po.id,
                   po.code,
                   po.customer_name,
                   po.seller_name,
                   po.status,
                   po.order_type,
                   po.vendor_shipment_number
            FROM production_order po
            WHERE UPPER(COALESCE(po.status, '')) <> 'CANCELLED'
              AND (
                (:kind = 'OPI' AND UPPER(TRIM(COALESCE(po.order_type, ''))) = 'INTERNA' AND UPPER(COALESCE(po.status, '')) <> 'DRAFT')
                OR (
                  :kind = 'OPCK'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) = 'CLIENTE_KIOSKO'
                    OR UPPER(COALESCE(po.code, '')) LIKE 'OPCK%'
                  )
                )
                OR (
                  :kind = 'OPC'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS')
                    OR UPPER(COALESCE(po.code, '')) ~ '^OPC(F|M)?-'
                  )
                )
                OR (
                  :kind = 'OPV'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) IN ('MARCAS', 'OPV')
                    OR UPPER(COALESCE(po.code, '')) LIKE 'OPV-%'
                    OR (
                      UPPER(COALESCE(po.seller_name, '')) LIKE '%LUIS FELIPE%'
                      AND UPPER(TRIM(COALESCE(po.order_type, ''))) NOT IN (
                        'CINCHOS', 'CINCHOS_FOSSILES', 'CINCHOS_MARCAS', 'INTERNA', 'CLIENTE_KIOSKO'
                      )
                    )
                  )
                )
                OR (
                  :kind = 'OPK'
                  AND (
                    UPPER(TRIM(COALESCE(po.order_type, ''))) = 'NORMAL'
                    OR UPPER(COALESCE(po.code, '')) LIKE 'OPK-%'
                  )
                  AND UPPER(TRIM(COALESCE(po.order_type, ''))) NOT IN ('MARCAS', 'OPV')
                  AND UPPER(COALESCE(po.code, '')) NOT LIKE 'OPV-%'
                  AND UPPER(COALESCE(po.seller_name, '')) NOT LIKE '%LUIS FELIPE%'
                )
              )
              AND (
                :q = ''
                OR LOWER(COALESCE(po.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.customer_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.seller_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.vendor_shipment_number, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.status, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            ORDER BY po.updated_at DESC NULLS LAST, po.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> searchForPrepare(
            @Param("kind") String kind,
            @Param("q") String q,
            @Param("limit") int limit
    );
    // ---------------------------------------------------------------------------------
    // Listado paginado de Órdenes de Producción (pantalla /admin/production-orders).
    //
    // El WHERE y el cálculo de agregados se declaran UNA vez, como constantes, y se
    // concatenan tanto en la consulta de página como en la de conteo. Si se editan, se
    // editan para las dos: un filtro aplicado solo a una de ellas haría que el contador
    // y las filas dijeran cosas distintas.
    // ---------------------------------------------------------------------------------

    /**
     * Cantidad planificada de un ítem. En los ítems de cincho {@code quantity} viene nula
     * —908 de 909 en la copia local— y la cantidad real está dentro del JSON de
     * {@code sizes_data}; se suman las tallas cuando el texto es un objeto JSON válido, y
     * si no se cae a {@code quantity}. {@code pg_input_is_valid} evita que una fila con
     * JSON corrupto tumbe el listado entero, que es lo que haría un cast directo.
     */
    String PLANNED_QTY_EXPR = """
            CASE
              WHEN i.sizes_data IS NOT NULL
               AND btrim(i.sizes_data) NOT IN ('', 'null')
               AND pg_input_is_valid(i.sizes_data, 'jsonb')
               AND jsonb_typeof(i.sizes_data::jsonb) = 'object'
              THEN COALESCE((
                     SELECT SUM(CASE WHEN e.value ~ '^[0-9]+$' THEN e.value::int ELSE 0 END)
                     FROM jsonb_each_text(i.sizes_data::jsonb) AS e(key, value)
                   ), 0)
              ELSE COALESCE(i.quantity, 0)
            END
            """;

    /** Agregados por orden: cuántos ítems, cuánto se planificó y cuánto se recibió. */
    String LIST_AGG_CTE = """
            WITH agg AS (
              SELECT i.production_order_id AS po_id,
                     COUNT(*) AS item_count,
                     COALESCE(SUM(p.planned), 0) AS total_qty,
                     COALESCE(SUM(LEAST(GREATEST(COALESCE(i.warehouse_received_qty, 0), 0),
                                        GREATEST(p.planned, 0))), 0) AS received_qty
              FROM production_order_item i
              CROSS JOIN LATERAL (SELECT (""" + PLANNED_QTY_EXPR + """
              ) AS planned) p
              GROUP BY i.production_order_id
            )
            """;

    /**
     * Familia comercial resuelta en SQL. Espejo exacto de
     * {@code ProductionPlanningConstants.orderFamilyLabel}: si cambia una, cambia la otra.
     * Se calcula aquí para poder filtrar por familia en la base y no traer el histórico
     * entero para descartarlo en memoria.
     */
    String FAMILY_EXPR = """
            CASE UPPER(TRIM(COALESCE(po.order_type, '')))
              WHEN 'VENTA_EN_LINEA' THEN 'OPL'
              WHEN 'NORMAL' THEN 'OPK'
              WHEN 'MARCAS' THEN 'OPV'
              WHEN 'OPV' THEN 'OPV'
              WHEN 'INTERNA' THEN 'OPI'
              WHEN 'CLIENTE_KIOSKO' THEN 'OPCK'
              WHEN 'DISTRIBUTION' THEN 'OPD'
              WHEN 'CINCHOS' THEN 'OPC'
              WHEN 'CINCHOS_FOSSILES' THEN 'OPC'
              WHEN 'CINCHOS_MARCAS' THEN 'OPC'
              ELSE NULLIF(SPLIT_PART(UPPER(COALESCE(po.code, '')), '-', 1), '')
            END
            """;

    /**
     * Origen y filtros. {@code :family}, {@code :status} y {@code :process} aceptan 'ALL';
     * {@code :q} acepta cadena vacía; {@code :fromTs}/{@code :toTs} aceptan null.
     *
     * <p>El filtro de proceso necesita los agregados, por eso va contra la CTE y no se
     * resuelve solo con el estado: una orden COMPLETED está en bodega o lista para
     * despacho según lo que falte por recibir.
     */
    String LIST_FROM_WHERE = """
            FROM production_order po
            LEFT JOIN agg a ON a.po_id = po.id
            LEFT JOIN product_distribution d ON d.id = po.distribution_id
            WHERE (:family = 'ALL' OR (""" + FAMILY_EXPR + """
              ) = :family)
              AND (:status = 'ALL' OR UPPER(TRIM(COALESCE(po.status, ''))) = :status)
              AND (
                :process = 'ALL'
                OR (:process = 'ACTIVE'
                    AND UPPER(TRIM(COALESCE(po.status, ''))) <> 'CANCELLED'
                    AND NOT (UPPER(TRIM(COALESCE(po.status, ''))) = 'COMPLETED'
                             AND COALESCE(a.total_qty, 0) - COALESCE(a.received_qty, 0) <= 0))
                OR (:process = 'PRODUCTION'
                    AND UPPER(TRIM(COALESCE(po.status, ''))) IN ('PENDING', 'IN_PROGRESS', 'IN_QA'))
                OR (:process = 'BODEGA'
                    AND UPPER(TRIM(COALESCE(po.status, ''))) = 'COMPLETED'
                    AND COALESCE(a.total_qty, 0) - COALESCE(a.received_qty, 0) > 0)
                OR (:process = 'READY'
                    AND UPPER(TRIM(COALESCE(po.status, ''))) = 'COMPLETED'
                    AND COALESCE(a.total_qty, 0) - COALESCE(a.received_qty, 0) <= 0)
                OR (:process = 'CANCELLED'
                    AND UPPER(TRIM(COALESCE(po.status, ''))) = 'CANCELLED')
              )
              AND (
                :q = ''
                OR LOWER(COALESCE(po.code, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.customer_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.seller_name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(po.vendor_shipment_number, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(d.distribution_number, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
              AND (CAST(:fromTs AS timestamp) IS NULL OR po.created_at >= CAST(:fromTs AS timestamp))
              AND (CAST(:toTs AS timestamp) IS NULL OR po.created_at < CAST(:toTs AS timestamp))
            """;

    /**
     * Una página del listado, de la más nueva a la más vieja por fecha de creación.
     *
     * <p>{@code NULLS LAST} es defensivo: hoy ninguna orden tiene {@code created_at} nulo,
     * pero la columna lo admite. El desempate por {@code id} evita que dos órdenes creadas
     * en el mismo instante bailen entre páginas.
     *
     * <p>Columnas: id, code, order_type, family, customer_name, seller_name,
     * distribution_number, start_date, delivery_date, created_at, status, observations,
     * total_qty, received_qty, item_count.
     */
    @Query(value = LIST_AGG_CTE + """
            SELECT po.id,
                   po.code,
                   po.order_type,
                   (""" + FAMILY_EXPR + """
                   ) AS family,
                   po.customer_name,
                   po.seller_name,
                   d.distribution_number,
                   po.start_date,
                   po.delivery_date,
                   po.created_at,
                   po.status,
                   po.observations,
                   COALESCE(a.total_qty, 0) AS total_qty,
                   COALESCE(a.received_qty, 0) AS received_qty,
                   COALESCE(a.item_count, 0) AS item_count
            """ + LIST_FROM_WHERE + """
            ORDER BY po.created_at DESC NULLS LAST, po.id DESC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findListPage(
            @Param("family") String family,
            @Param("status") String status,
            @Param("process") String process,
            @Param("q") String q,
            @Param("fromTs") LocalDateTime fromTs,
            @Param("toTs") LocalDateTime toTs,
            @Param("size") int size,
            @Param("offset") int offset
    );

    /** Cuántas órdenes pasan el mismo filtro. Mismo WHERE que {@link #findListPage}. */
    @Query(value = LIST_AGG_CTE + """
            SELECT COUNT(*)
            """ + LIST_FROM_WHERE, nativeQuery = true)
    long countListPage(
            @Param("family") String family,
            @Param("status") String status,
            @Param("process") String process,
            @Param("q") String q,
            @Param("fromTs") LocalDateTime fromTs,
            @Param("toTs") LocalDateTime toTs
    );
}
