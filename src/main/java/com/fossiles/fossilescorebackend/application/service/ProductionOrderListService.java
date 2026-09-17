package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.PageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderListItemResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * El listado de Órdenes de Producción: filtra, ordena y pagina en la base de datos.
 *
 * <p>Va en un servicio propio y no en {@code ProductionOrderController} (2.172 líneas) ni en
 * {@code ProductionOrderService} (un esqueleto con el cuerpo comentado).
 *
 * <p><b>Es de solo lectura, y eso es deliberado.</b> El listado antiguo
 * ({@code GET /api/production-orders}) asignaba correlativos de envío mientras respondía:
 * abrir cualquiera de las diez pantallas que lo consumen escribía en la base, sin tomar
 * ningún bloqueo. Este servicio nunca replicó ese comportamiento, y aquel ya dejó de
 * hacerlo: la asignación vive donde corresponde, al crear y al actualizar la orden.
 *
 * <p>{@code ProductionOrderController.getById} la conserva como última red mientras el
 * relleno de las órdenes antiguas ({@code scripts/backfill-opv-vendor-shipment-number.sql})
 * no haya corrido en producción.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderListService {

    private static final int MAX_PAGE_SIZE = 200;

    /** Las siete familias más 'ALL'. Espejo de {@code ProductionPlanningConstants.orderFamilyLabel}. */
    private static final Set<String> FAMILIAS_VALIDAS =
            Set.of("ALL", "OPL", "OPK", "OPV", "OPI", "OPCK", "OPD", "OPC");

    private static final Set<String> PROCESOS_VALIDOS =
            Set.of("ALL", "ACTIVE", "PRODUCTION", "BODEGA", "READY", "CANCELLED");

    /** Incluye DRAFT e IN_QA: existen en los datos y el desplegable no los ofrecía. */
    private static final Set<String> ESTADOS_VALIDOS =
            Set.of("ALL", "DRAFT", "PENDING", "IN_PROGRESS", "IN_QA", "COMPLETED", "CANCELLED");

    private final ProductionOrderRepository productionOrderRepository;

    /**
     * Una página del listado, de la más nueva a la más vieja por fecha de creación.
     *
     * <p>Un valor desconocido en cualquier filtro es un error, no «sin filtro»: devolver el
     * catálogo entero ante una errata es justo lo que esconde el problema al usuario.
     *
     * @param from primer día incluido (por fecha de creación); {@code null} = sin límite
     * @param to   último día incluido, completo hasta las 23:59; {@code null} = sin límite
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductionOrderListItemResponse> list(String family,
                                                              String status,
                                                              String process,
                                                              String search,
                                                              LocalDate from,
                                                              LocalDate to,
                                                              int page,
                                                              int size) throws BusinessException {
        String normalizedFamily = normalize(family, "ALL");
        String normalizedStatus = normalize(status, "ALL");
        String normalizedProcess = normalize(process, "ALL");

        if (!FAMILIAS_VALIDAS.contains(normalizedFamily)) {
            throw new BusinessException("Familia de orden no válida: " + family
                    + ". Valores aceptados: " + String.join(", ", FAMILIAS_VALIDAS));
        }
        if (!ESTADOS_VALIDOS.contains(normalizedStatus)) {
            throw new BusinessException("Estado no válido: " + status
                    + ". Valores aceptados: " + String.join(", ", ESTADOS_VALIDOS));
        }
        if (!PROCESOS_VALIDOS.contains(normalizedProcess)) {
            throw new BusinessException("Proceso no válido: " + process
                    + ". Valores aceptados: " + String.join(", ", PROCESOS_VALIDOS));
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("El rango de fechas está invertido: "
                    + from + " es posterior a " + to + ".");
        }

        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);

        // 'to' es un día inclusivo para quien filtra, así que el corte va al inicio del
        // día siguiente: con '<= to' a medianoche se perderían las órdenes de ese mismo día.
        LocalDateTime fromTs = from == null ? null : from.atStartOfDay();
        LocalDateTime toTs = to == null ? null : to.plusDays(1).atStartOfDay();

        long totalElements = productionOrderRepository.countListPage(
                normalizedFamily, normalizedStatus, normalizedProcess, normalizedSearch, fromTs, toTs);

        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);

        List<Object[]> rows = productionOrderRepository.findListPage(
                normalizedFamily, normalizedStatus, normalizedProcess, normalizedSearch, fromTs, toTs,
                pageSize, pageIndex * pageSize);

        List<ProductionOrderListItemResponse> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(toItem(r));
        }

        return PageResponse.of(content, totalElements, totalPages, pageSize, pageIndex);
    }

    private static ProductionOrderListItemResponse toItem(Object[] r) {
        long totalQty = asLong(r[12]);
        long receivedQty = asLong(r[13]);
        long pendingQty = Math.max(totalQty - receivedQty, 0L);
        int progressPct = totalQty > 0 ? (int) Math.round((receivedQty * 100.0) / totalQty) : 0;
        String status = asString(r[10]);

        return ProductionOrderListItemResponse.builder()
                .id(asLong(r[0]))
                .code(asString(r[1]))
                .orderType(asString(r[2]))
                .family(asString(r[3]))
                .customerName(asString(r[4]))
                .sellerName(asString(r[5]))
                .distributionNumber(asString(r[6]))
                .startDate(asLocalDate(r[7]))
                .deliveryDate(asLocalDate(r[8]))
                .createdAt(asLocalDateTime(r[9]))
                .status(status)
                .observations(asString(r[11]))
                .totalQty(totalQty)
                .receivedQty(receivedQty)
                .pendingQty(pendingQty)
                .progressPct(progressPct)
                .itemCount((int) asLong(r[14]))
                .processStage(resolveStage(status, pendingQty))
                .build();
    }

    /**
     * La etapa que se ve en la columna «Proceso».
     *
     * <p>Mismas etiquetas que construía el navegador, con una corrección: {@code IN_QA} no
     * tenía rama, así que la columna mostraba el literal en inglés mientras la de Estado, en
     * la misma fila, decía «En Progreso». Ahora tiene la suya y cuenta como producción, que
     * es donde sigue estando el trabajo.
     */
    private static ProductionOrderListItemResponse.ProcessStage resolveStage(String status, long pendingQty) {
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return switch (st) {
            case "CANCELLED" -> stage("CANCELLED", "Cancelada", "danger");
            case "DRAFT" -> stage("DRAFT", "Borrador (pend. autorización)", "secondary");
            case "PENDING" -> stage("PENDING_PRODUCTION", "Pendiente en Producción", "warning");
            case "IN_PROGRESS" -> stage("IN_PRODUCTION", "En Producción", "info");
            case "IN_QA" -> stage("IN_QA", "En Control de Calidad", "info");
            case "COMPLETED" -> pendingQty > 0
                    ? stage("IN_BODEGA", "Pendiente en Bodega PT", "primary")
                    : stage("READY_DISPATCH", "Lista para Despacho", "success");
            default -> stage("OTHER", st.isEmpty() ? "Sin estado" : status, "secondary");
        };
    }

    private static ProductionOrderListItemResponse.ProcessStage stage(String key, String label, String color) {
        return ProductionOrderListItemResponse.ProcessStage.builder()
                .key(key).label(label).color(color).build();
    }

    private static String normalize(String v, String fallback) {
        String s = v == null ? "" : v.trim();
        return s.isEmpty() ? fallback : s.toUpperCase(Locale.ROOT);
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDate asLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        return null;
    }

    private static LocalDateTime asLocalDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime d) return d;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        if (o instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        return null;
    }
}
