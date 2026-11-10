package com.fossiles.fossilescorebackend.application.service.onlineledger;

import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.BulkUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerResponse;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LedgerRow;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.LockRequest;
import com.fossiles.fossilescorebackend.application.dto.onlineledger.OnlineSaleLedgerDtos.SellerCommission;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationAccessGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleAccountingEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleAccountingRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Libro de ventas en línea para Contabilidad: la misma hoja que llevaban en Excel.
 *
 * <p>Lectura: un rango de fechas se resuelve con tres consultas (ventas, productos y datos contables), sin facturas ni
 * vista de bodega. Escritura: solo los campos de {@link OnlineSaleAccountingEntity}; cualquier otro nombre de campo se
 * rechaza. Hasta «Total con envío» nada se edita; desde ahí todo se puede corregir en el libro (columnas propias de
 * online_sale_accounting: la venta, el inventario y Ventas en línea no cambian).
 */
@Service
@RequiredArgsConstructor
public class OnlineSaleLedgerService {

    public static final String PERM_SALES_VIEW = "VENTAS.VENTAS_ONLINE.VER";

    /** Comisión del vendedor sobre la venta neta (hoja «Cálculo comisiones x vendedor»). */
    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.02");
    public static final BigDecimal VAT_RATE = new BigDecimal("0.12");

    static final int MAX_ROWS = 5000;
    private static final int BATCH = 500;
    private static final int MAX_BULK = 2000;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999.99");

    private static final Map<String, String> PAYMENT_METHOD_DISPLAY = Map.of(
            "TRANSFERENCIA_PENDIENTE", "Transferencia Pendiente",
            "TRANSFERENCIA_LISTA", "Transferencia",
            "VISALINK_PAGADO", "Visalink",
            "VISALINK_PENDIENTE", "Visalink Pendiente",
            "TARJETA_PAGADO", "Tarjeta",
            "DEPOSITO_LISTO", "Depósito",
            "DEPOSITO_PENDIENTE", "Depósito Pendiente",
            "CONTRA_ENTREGA", "Contra Entrega",
            "CONTRA_ENTREGA_DEPOSITO", "Contra Entrega Depósito");

    /** Campos que Contabilidad puede cambiar. Lo que no está aquí no se puede editar desde el libro. */
    private static final Map<String, FieldWriter> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("realShippingCost", money(OnlineSaleAccountingEntity::getRealShippingCost,
                OnlineSaleAccountingEntity::setRealShippingCost, false, "Envío real"));
        FIELDS.put("carrierCommission", money(OnlineSaleAccountingEntity::getCarrierCommission,
                OnlineSaleAccountingEntity::setCarrierCommission, false, "Comisión"));
        FIELDS.put("realDepositAmount", money(OnlineSaleAccountingEntity::getRealDepositAmount,
                OnlineSaleAccountingEntity::setRealDepositAmount, false, "Valor real depósito"));
        FIELDS.put("shortageAmount", money(OnlineSaleAccountingEntity::getShortageAmount,
                OnlineSaleAccountingEntity::setShortageAmount, true, "Faltante / sobrante"));
        FIELDS.put("sentDate", date(OnlineSaleAccountingEntity::getSentDate,
                OnlineSaleAccountingEntity::setSentDate, "Fecha de envío"));
        FIELDS.put("receivedDate", date(OnlineSaleAccountingEntity::getReceivedDate,
                OnlineSaleAccountingEntity::setReceivedDate, "Fecha de recepción"));
        FIELDS.put("liquidationDate", date(OnlineSaleAccountingEntity::getLiquidationDate,
                OnlineSaleAccountingEntity::setLiquidationDate, "Fecha de liquidación"));
        FIELDS.put("accountingNote", text(OnlineSaleAccountingEntity::getAccountingNote,
                OnlineSaleAccountingEntity::setAccountingNote, 1000, "Observación"));
        FIELDS.put("accountingAuthorization", text(OnlineSaleAccountingEntity::getPaymentAuthorization,
                OnlineSaleAccountingEntity::setPaymentAuthorization, 200, "Autorización"));
        FIELDS.put("paymentForm", text(OnlineSaleAccountingEntity::getPaymentForm,
                OnlineSaleAccountingEntity::setPaymentForm, 100, "Forma de pago"));
        FIELDS.put("depositSlipNumber", text(OnlineSaleAccountingEntity::getDepositSlipNumber,
                OnlineSaleAccountingEntity::setDepositSlipNumber, 100, "No. de boleta"));
        FIELDS.put("deliveryNote", text(OnlineSaleAccountingEntity::getDeliveryNote,
                OnlineSaleAccountingEntity::setDeliveryNote, 500, "Observación de entrega"));
        FIELDS.put("depositNote", text(OnlineSaleAccountingEntity::getDepositNote,
                OnlineSaleAccountingEntity::setDepositNote, 500, "Observación del depósito"));
        FIELDS.put("shortageNote", text(OnlineSaleAccountingEntity::getShortageNote,
                OnlineSaleAccountingEntity::setShortageNote, 500, "Detalle del faltante"));
        // Desde «Total con envío»: correcciones del libro a datos de la venta (vacío = el dato de la venta).
        FIELDS.put("accountingTotal", money(OnlineSaleAccountingEntity::getTotalAmount,
                OnlineSaleAccountingEntity::setTotalAmount, false, "Total con envío"));
        FIELDS.put("accountingShipping", money(OnlineSaleAccountingEntity::getShippingCost,
                OnlineSaleAccountingEntity::setShippingCost, false, "Envío"));
        FIELDS.put("accountingNet", money(OnlineSaleAccountingEntity::getNetAmount,
                OnlineSaleAccountingEntity::setNetAmount, true, "Sin envío"));
        FIELDS.put("accountingCarrier", choice(OnlineSaleAccountingEntity::getCarrier,
                OnlineSaleAccountingEntity::setCarrier, 50, "Transporte", OnlineSaleLedgerService::normalizeCarrier));
        FIELDS.put("accountingSaleDate", date(OnlineSaleAccountingEntity::getSaleDate,
                OnlineSaleAccountingEntity::setSaleDate, "Fecha"));
        FIELDS.put("accountingSocialNetwork", choice(OnlineSaleAccountingEntity::getSocialNetwork,
                OnlineSaleAccountingEntity::setSocialNetwork, 50, "Red social", OnlineSaleLedgerService::normalizeSocial));
        FIELDS.put("accountingEmail", text(OnlineSaleAccountingEntity::getEmail,
                OnlineSaleAccountingEntity::setEmail, 200, "Correo"));
        FIELDS.put("accountingSalesperson", text(OnlineSaleAccountingEntity::getSalesperson,
                OnlineSaleAccountingEntity::setSalesperson, 150, "Vendedor"));
        FIELDS.put("accountingGuideNumber", text(OnlineSaleAccountingEntity::getGuideNumber,
                OnlineSaleAccountingEntity::setGuideNumber, 100, "Guía"));
        FIELDS.put("accountingShipmentNumber", text(OnlineSaleAccountingEntity::getShipmentNumber,
                OnlineSaleAccountingEntity::setShipmentNumber, 100, "No. de envío"));
        FIELDS.put("accountingStatus", choice(OnlineSaleAccountingEntity::getStatus,
                OnlineSaleAccountingEntity::setStatus, 30, "Estado", OnlineSaleLedgerService::normalizeStatus));
        FIELDS.put("reviewed", new FieldWriter() {
            @Override
            public boolean isEmpty(OnlineSaleAccountingEntity a) {
                return !Boolean.TRUE.equals(a.getReviewed());
            }

            @Override
            public void write(OnlineSaleAccountingEntity a, Object raw) {
                a.setReviewed(raw instanceof Boolean b ? b : raw != null && "true".equalsIgnoreCase(raw.toString().trim()));
            }
        });
    }

    private final OnlineSaleRepository saleRepository;
    private final OnlineSaleItemRepository itemRepository;
    private final OnlineSaleAccountingRepository accountingRepository;
    private final ReconciliationAccessGuard guard;

    @PersistenceContext
    private EntityManager em;

    // ───────── permisos ─────────

    public boolean canView() {
        return guard.hasAny(PERM_SALES_VIEW, ReconciliationAccessGuard.PERM_VIEW);
    }

    /** Editar el libro es de Administración y Contabilidad (mismo permiso que conciliar). */
    public boolean canEdit() {
        return guard.hasAny(ReconciliationAccessGuard.PERM_RECONCILE);
    }

    /** Bloquear líneas revisadas: quien puede editar el libro. */
    public boolean canLock() {
        return canEdit();
    }

    /**
     * Desbloquear es solo de administradores: si quien edita pudiera desbloquear, el bloqueo no impediría volver a
     * modificar lo ya revisado.
     */
    public boolean canUnlock() {
        return guard.isAdmin();
    }

    public void assertCanView() throws BusinessException {
        if (!canView()) {
            throw new BusinessException("No tiene permiso para ver el libro de ventas en línea.");
        }
    }

    public void assertCanEdit() throws BusinessException {
        if (!canEdit()) {
            throw new BusinessException("Solo Administración y Contabilidad pueden editar el libro de ventas en línea.");
        }
    }

    // ───────── lectura ─────────

    /**
     * Ventas del rango (por fecha de venta). Con {@code q} busca cliente, teléfono, envío, guía, autorización,
     * dirección o producto; si no se dan fechas, la búsqueda recorre todo el historial.
     */
    @Transactional(readOnly = true)
    public LedgerResponse search(LocalDate from, LocalDate to, String q) throws BusinessException {
        String query = q == null ? "" : q.replace("%", "").replace("_", " ").trim();
        if (query.isEmpty() && (from == null || to == null)) {
            throw new BusinessException("Indique el rango de fechas o un texto para buscar.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final.");
        }
        if (!query.isEmpty() && query.length() < 2) {
            throw new BusinessException("Escriba al menos 2 caracteres para buscar.");
        }

        StringBuilder jpql = new StringBuilder("SELECT s FROM OnlineSaleEntity s WHERE 1 = 1");
        if (from != null) jpql.append(" AND s.saleDate >= :from");
        if (to != null) jpql.append(" AND s.saleDate <= :to");
        if (!query.isEmpty()) {
            jpql.append(" AND (LOWER(s.customerName) LIKE :q OR s.phone LIKE :q OR s.phone2 LIKE :q")
                    .append(" OR LOWER(s.shipmentNumber) LIKE :q OR LOWER(s.guideNumber) LIKE :q")
                    .append(" OR LOWER(s.paymentAuthorization) LIKE :q OR LOWER(s.address) LIKE :q")
                    .append(" OR EXISTS (SELECT 1 FROM OnlineSaleAccountingEntity ac WHERE ac.onlineSaleId = s.id")
                    .append(" AND LOWER(ac.paymentAuthorization) LIKE :q)")
                    .append(" OR LOWER(s.productName) LIKE :q OR s.saleNumber = :exact")
                    .append(" OR EXISTS (SELECT 1 FROM OnlineSaleItemEntity i WHERE i.onlineSaleId = s.id")
                    .append(" AND LOWER(i.productName) LIKE :q))");
        }
        jpql.append(" ORDER BY s.saleDate ASC, s.id ASC");

        TypedQuery<OnlineSaleEntity> typed = em.createQuery(jpql.toString(), OnlineSaleEntity.class);
        if (from != null) typed.setParameter("from", from);
        if (to != null) typed.setParameter("to", to);
        if (!query.isEmpty()) {
            typed.setParameter("q", "%" + query.toLowerCase(Locale.ROOT) + "%");
            typed.setParameter("exact", query);
        }
        typed.setMaxResults(MAX_ROWS + 1);
        List<OnlineSaleEntity> sales = typed.getResultList();
        boolean truncated = sales.size() > MAX_ROWS;
        if (truncated) {
            sales = sales.subList(0, MAX_ROWS);
        }

        return LedgerResponse.builder()
                .from(from).to(to).query(query.isEmpty() ? null : query)
                .rows(toRows(sales))
                .truncated(truncated)
                .canEdit(canEdit())
                .canLock(canLock())
                .canUnlock(canUnlock())
                .commissionRate(COMMISSION_RATE)
                .vatRate(VAT_RATE)
                .build();
    }

    private List<LedgerRow> toRows(List<OnlineSaleEntity> sales) {
        if (sales.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = sales.stream().map(OnlineSaleEntity::getId).toList();
        Map<Long, List<OnlineSaleItemEntity>> itemsBySale = new HashMap<>();
        Map<Long, OnlineSaleAccountingEntity> accountingBySale = new HashMap<>();
        for (int i = 0; i < ids.size(); i += BATCH) {
            List<Long> batch = ids.subList(i, Math.min(ids.size(), i + BATCH));
            for (OnlineSaleItemEntity item : itemRepository.findByOnlineSaleIdInOrderByIdAsc(batch)) {
                itemsBySale.computeIfAbsent(item.getOnlineSaleId(), k -> new ArrayList<>()).add(item);
            }
            for (OnlineSaleAccountingEntity a : accountingRepository.findByOnlineSaleIdIn(batch)) {
                accountingBySale.put(a.getOnlineSaleId(), a);
            }
        }
        List<LedgerRow> rows = new ArrayList<>(sales.size());
        for (OnlineSaleEntity s : sales) {
            rows.add(toRow(s, itemsBySale.getOrDefault(s.getId(), List.of()), accountingBySale.get(s.getId())));
        }
        return rows;
    }

    private LedgerRow toRow(OnlineSaleEntity s, List<OnlineSaleItemEntity> items, OnlineSaleAccountingEntity a) {
        String products;
        String colors;
        String sizes;
        if (!items.isEmpty()) {
            List<String> p = new ArrayList<>();
            List<String> c = new ArrayList<>();
            List<String> z = new ArrayList<>();
            for (OnlineSaleItemEntity it : items) {
                String name = clean(it.getProductName());
                if (name.isEmpty()) name = clean(it.getProductCode());
                int qty = it.getQuantity() == null ? 1 : it.getQuantity();
                p.add(qty > 1 ? qty + " " + name : name);
                if (!clean(it.getColorName()).isEmpty()) c.add(clean(it.getColorName()));
                if (!clean(it.getSize()).isEmpty()) z.add(clean(it.getSize()));
            }
            products = String.join(" / ", p);
            colors = String.join(" / ", c);
            sizes = String.join(" / ", z);
        } else {
            String name = clean(s.getProductName());
            int qty = s.getQuantity() == null ? 1 : s.getQuantity();
            products = qty > 1 && !name.isEmpty() ? qty + " " + name : name;
            colors = clean(s.getColorName());
            sizes = clean(s.getSize());
        }
        String phone = clean(s.getPhone());
        if (!clean(s.getPhone2()).isEmpty()) {
            phone = phone.isEmpty() ? clean(s.getPhone2()) : phone + " / " + clean(s.getPhone2());
        }
        BigDecimal total = s.getTotalAmount();
        BigDecimal shipping = s.getShippingCost();
        BigDecimal net = s.getNetAmount();
        if (net == null && total != null) {
            net = total.subtract(shipping == null ? BigDecimal.ZERO : shipping);
        }

        LedgerRow.LedgerRowBuilder row = LedgerRow.builder()
                .id(s.getId())
                .saleNumber(s.getSaleNumber())
                .customerName(s.getCustomerName())
                .address(s.getAddress())
                .phone(phone)
                .packaging(s.getPackaging())
                .paymentMethod(s.getPaymentMethod())
                .paymentMethodDisplay(paymentLabel(s.getPaymentMethod()))
                .invoiceTaxId(s.getInvoiceTaxId())
                .products(products).colors(colors).sizes(sizes)
                .totalAmount(total).shippingCost(shipping).netAmount(net)
                .carrier(s.getShippingCarrier())
                .saleDate(s.getSaleDate())
                .socialNetwork(s.getSocialNetwork())
                .email(s.getEmail())
                .salesperson(s.getSalesperson())
                .guideNumber(s.getGuideNumber())
                .shipmentNumber(s.getShipmentNumber())
                .paymentAuthorization(s.getPaymentAuthorization())
                .status(s.getStatus())
                .observations(s.getObservations())
                .dispatchedDate(s.getDispatchedAt() == null ? null : s.getDispatchedAt().toLocalDate())
                .reviewed(false)
                .locked(false);
        if (a != null) {
            row.realShippingCost(a.getRealShippingCost())
                    .carrierCommission(a.getCarrierCommission())
                    .accountingNote(a.getAccountingNote())
                    .accountingAuthorization(a.getPaymentAuthorization())
                    .sentDate(a.getSentDate())
                    .receivedDate(a.getReceivedDate())
                    .paymentForm(a.getPaymentForm())
                    .liquidationDate(a.getLiquidationDate())
                    .depositSlipNumber(a.getDepositSlipNumber())
                    .deliveryNote(a.getDeliveryNote())
                    .realDepositAmount(a.getRealDepositAmount())
                    .depositNote(a.getDepositNote())
                    .shortageAmount(a.getShortageAmount())
                    .shortageNote(a.getShortageNote())
                    .reviewed(Boolean.TRUE.equals(a.getReviewed()))
                    .accountingUpdatedAt(a.getUpdatedAt())
                    .locked(Boolean.TRUE.equals(a.getLocked()))
                    .lockedAt(a.getLockedAt())
                    .lockedByName(a.getLockedByName())
                    .accountingTotal(a.getTotalAmount())
                    .accountingShipping(a.getShippingCost())
                    .accountingNet(a.getNetAmount())
                    .accountingCarrier(a.getCarrier())
                    .accountingSaleDate(a.getSaleDate())
                    .accountingSocialNetwork(a.getSocialNetwork())
                    .accountingEmail(a.getEmail())
                    .accountingSalesperson(a.getSalesperson())
                    .accountingGuideNumber(a.getGuideNumber())
                    .accountingShipmentNumber(a.getShipmentNumber())
                    .accountingStatus(a.getStatus());
        }
        LedgerRow built = row.build();
        if (a != null) {
            applyOverrides(built, a);
        }
        return built;
    }

    /**
     * Desde «Total con envío» manda lo corregido en el libro: los campos de la fila pasan a ser el valor efectivo
     * (así comisiones, totales y Excel usan lo corregido) y el dato de la venta queda en {@code saleOriginal}.
     */
    private static void applyOverrides(LedgerRow row, OnlineSaleAccountingEntity a) {
        Map<String, Object> original = new LinkedHashMap<>();
        boolean amountsChanged = false;
        if (a.getTotalAmount() != null) {
            original.put("totalAmount", row.getTotalAmount());
            row.setTotalAmount(a.getTotalAmount());
            amountsChanged = true;
        }
        if (a.getShippingCost() != null) {
            original.put("shippingCost", row.getShippingCost());
            row.setShippingCost(a.getShippingCost());
            amountsChanged = true;
        }
        if (a.getNetAmount() != null) {
            original.put("netAmount", row.getNetAmount());
            row.setNetAmount(a.getNetAmount());
        } else if (amountsChanged) {
            // Sin envío no anotado: es la fórmula total − envío con los montos ya corregidos.
            original.put("netAmount", row.getNetAmount());
            row.setNetAmount(nz(row.getTotalAmount()).subtract(nz(row.getShippingCost())));
        }
        if (a.getCarrier() != null) {
            original.put("carrier", row.getCarrier());
            row.setCarrier(a.getCarrier());
        }
        if (a.getSaleDate() != null) {
            original.put("saleDate", row.getSaleDate());
            row.setSaleDate(a.getSaleDate());
        }
        if (a.getSocialNetwork() != null) {
            original.put("socialNetwork", row.getSocialNetwork());
            row.setSocialNetwork(a.getSocialNetwork());
        }
        if (a.getEmail() != null) {
            original.put("email", row.getEmail());
            row.setEmail(a.getEmail());
        }
        if (a.getSalesperson() != null) {
            original.put("salesperson", row.getSalesperson());
            row.setSalesperson(a.getSalesperson());
        }
        if (a.getGuideNumber() != null) {
            original.put("guideNumber", row.getGuideNumber());
            row.setGuideNumber(a.getGuideNumber());
        }
        if (a.getShipmentNumber() != null) {
            original.put("shipmentNumber", row.getShipmentNumber());
            row.setShipmentNumber(a.getShipmentNumber());
        }
        if (a.getStatus() != null) {
            original.put("status", row.getStatus());
            row.setStatus(a.getStatus());
        }
        if (!original.isEmpty()) {
            row.setSaleOriginal(original);
        }
    }

    // ───────── valores con lista ─────────

    /** Texto sin tildes, en mayúsculas y con espacios simples, para reconocer lo que se escribe a mano o se pega. */
    static String foldUpper(String value) {
        String t = java.text.Normalizer.normalize(clean(value), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return t.toUpperCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    static String normalizeCarrier(String raw) {
        String t = foldUpper(raw);
        if (t.startsWith("MOTO")) return "MOTORISTA";
        if (t.startsWith("FORZA")) return "FORZA";
        if (t.startsWith("GUATEX")) return "GUATEX";
        return t.replace(' ', '_');
    }

    static String normalizeSocial(String raw) {
        String t = foldUpper(raw);
        if (t.startsWith("WHATS") || t.equals("WA") || t.equals("WSP")) return "WHATSAPP";
        if (t.startsWith("FACE") || t.equals("FB")) return "FACEBOOK";
        if (t.startsWith("INSTA") || t.equals("IG")) return "INSTAGRAM";
        if (t.startsWith("TIK")) return "TIKTOK";
        if (t.contains("WEB") || t.startsWith("PAGINA")) return "WEB";
        return t.replace(' ', '_');
    }

    private static final Map<String, String> STATUS_ALIASES = Map.ofEntries(
            Map.entry("PENDIENTE", "PENDIENTE"),
            Map.entry("EN PRODUCCION", "EN_PRODUCCION"),
            Map.entry("PRODUCCION", "EN_PRODUCCION"),
            Map.entry("PRODUCIDO", "PRODUCIDO"),
            Map.entry("ENVIADO", "ENVIADO"),
            Map.entry("ENTREGADO", "ENTREGADO"),
            Map.entry("CANCELADO", "CANCELADO"),
            Map.entry("CANCELADA", "CANCELADO"),
            Map.entry("ANULADO", "ANULADA"),
            Map.entry("ANULADA", "ANULADA"),
            Map.entry("DEVOLUCION", "DEVOLUCION"),
            Map.entry("DEVUELTO", "DEVOLUCION"));

    static String normalizeStatus(String raw) {
        String code = STATUS_ALIASES.get(foldUpper(raw));
        if (code == null) {
            throw new IllegalArgumentException("Estado: «" + raw + "» no es un estado. Use Pendiente, En producción, "
                    + "Producido, Enviado, Entregado, Cancelado, Anulado o Devolución.");
        }
        return code;
    }

    public static String paymentLabel(String paymentMethod) {
        if (paymentMethod == null) return "";
        return PAYMENT_METHOD_DISPLAY.getOrDefault(paymentMethod, paymentMethod);
    }

    // ───────── escritura ─────────

    /** Cambia campos contables de una venta. {@code changes}: nombre de campo → valor (null o vacío borra). */
    public LedgerRow update(Long saleId, Map<String, Object> changes)
            throws BusinessException, ResourceNotFoundException {
        validateChanges(changes);
        OnlineSaleEntity sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta en línea", saleId));
        Long userId = guard.currentUserId();
        OnlineSaleAccountingEntity saved = upsert(saleId, changes, false, userId);
        return toRow(sale, itemRepository.findByOnlineSaleIdOrderByIdAsc(saleId), saved);
    }

    /** Aplica los mismos campos a varias ventas (por ejemplo, el envío real de todo lo de Guatex). */
    public List<LedgerRow> bulkUpdate(BulkUpdateRequest request) throws BusinessException {
        if (request == null || request.getSaleIds() == null || request.getSaleIds().isEmpty()) {
            throw new BusinessException("Seleccione al menos una venta.");
        }
        validateChanges(request.getChanges());
        List<Long> ids = request.getSaleIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_BULK) {
            throw new BusinessException("Máximo " + MAX_BULK + " ventas por cambio en lote.");
        }
        List<OnlineSaleEntity> sales = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += BATCH) {
            sales.addAll(saleRepository.findAllById(ids.subList(i, Math.min(ids.size(), i + BATCH))));
        }
        if (sales.size() != ids.size()) {
            throw new BusinessException("Alguna de las ventas seleccionadas ya no existe. Actualice la vista.");
        }
        // Se revisa antes de escribir nada: con una sola línea bloqueada no se aplica el cambio a ninguna.
        long lockedCount = 0;
        for (int i = 0; i < ids.size(); i += BATCH) {
            lockedCount += accountingRepository.findByOnlineSaleIdIn(ids.subList(i, Math.min(ids.size(), i + BATCH)))
                    .stream().filter(a -> Boolean.TRUE.equals(a.getLocked())).count();
        }
        if (lockedCount > 0) {
            throw new BusinessException(lockedCount == 1
                    ? "Una de las ventas seleccionadas está bloqueada y no se puede modificar. Quítela de la selección."
                    : lockedCount + " de las ventas seleccionadas están bloqueadas y no se pueden modificar. "
                    + "Quítelas de la selección.");
        }
        Long userId = guard.currentUserId();
        for (OnlineSaleEntity sale : sales) {
            upsert(sale.getId(), request.getChanges(), request.isOnlyEmpty(), userId);
        }
        sales.sort(Comparator.comparing(OnlineSaleEntity::getSaleDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OnlineSaleEntity::getId));
        return toRows(sales);
    }

    /**
     * Bloquea o desbloquea líneas. Bloquear una línea sin datos contables crea su fila (queda bloqueada tal cual,
     * con las celdas vacías). Lo que ya está en el estado pedido no se toca: conserva quién y cuándo la bloqueó.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<LedgerRow> setLocked(LockRequest request) throws BusinessException {
        if (request == null || request.getSaleIds() == null || request.getSaleIds().isEmpty()) {
            throw new BusinessException("Seleccione al menos una venta.");
        }
        boolean lock = request.isLocked();
        if (lock && !canLock()) {
            throw new BusinessException("Solo Administración y Contabilidad pueden bloquear líneas del libro.");
        }
        if (!lock && !canUnlock()) {
            throw new BusinessException("Solo un administrador puede desbloquear líneas del libro.");
        }
        List<Long> ids = request.getSaleIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_ROWS) {
            throw new BusinessException("Máximo " + MAX_ROWS + " ventas por bloqueo.");
        }
        List<OnlineSaleEntity> sales = new ArrayList<>();
        Map<Long, OnlineSaleAccountingEntity> existing = new HashMap<>();
        for (int i = 0; i < ids.size(); i += BATCH) {
            List<Long> batch = ids.subList(i, Math.min(ids.size(), i + BATCH));
            sales.addAll(saleRepository.findAllById(batch));
            for (OnlineSaleAccountingEntity a : accountingRepository.findByOnlineSaleIdIn(batch)) {
                existing.put(a.getOnlineSaleId(), a);
            }
        }
        if (sales.size() != ids.size()) {
            throw new BusinessException("Alguna de las ventas seleccionadas ya no existe. Actualice la vista.");
        }
        Long userId = guard.currentUserId();
        String username = guard.currentUsername();
        LocalDateTime now = GuatemalaDateTime.now();
        List<OnlineSaleAccountingEntity> toSave = new ArrayList<>();
        for (Long id : ids) {
            OnlineSaleAccountingEntity a = existing.get(id);
            if (a == null) {
                if (!lock) {
                    continue;
                }
                a = OnlineSaleAccountingEntity.builder().onlineSaleId(id).reviewed(false).locked(false).build();
            }
            if (Boolean.TRUE.equals(a.getLocked()) == lock) {
                continue;
            }
            a.setLocked(lock);
            a.setLockedAt(lock ? now : null);
            a.setLockedBy(lock ? userId : null);
            a.setLockedByName(lock ? username : null);
            a.setUpdatedBy(userId);
            toSave.add(a);
        }
        try {
            accountingRepository.saveAllAndFlush(toSave);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("Otra persona estaba guardando en esas ventas al mismo tiempo. Intente de nuevo.");
        }
        sales.sort(Comparator.comparing(OnlineSaleEntity::getSaleDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(OnlineSaleEntity::getId));
        return toRows(sales);
    }

    private void validateChanges(Map<String, Object> changes) throws BusinessException {
        if (changes == null || changes.isEmpty()) {
            throw new BusinessException("No hay cambios para guardar.");
        }
        OnlineSaleAccountingEntity probe = new OnlineSaleAccountingEntity();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            FieldWriter writer = FIELDS.get(change.getKey());
            if (writer == null) {
                throw new BusinessException("El campo «" + change.getKey()
                        + "» es de la venta y no se puede cambiar desde el libro.");
            }
            try {
                writer.write(probe, change.getValue());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(e.getMessage());
            }
        }
    }

    /**
     * Crea o actualiza la fila contable. Dos celdas de la misma venta guardadas a la vez pueden intentar crear la
     * fila al mismo tiempo: la restricción única rechaza la segunda y aquí se reintenta sobre la fila ya creada.
     */
    private OnlineSaleAccountingEntity upsert(Long saleId, Map<String, Object> changes, boolean onlyEmpty, Long userId)
            throws BusinessException {
        try {
            return applyAndSave(saleId, changes, onlyEmpty, userId);
        } catch (DataIntegrityViolationException e) {
            return applyAndSave(saleId, changes, onlyEmpty, userId);
        }
    }

    private OnlineSaleAccountingEntity applyAndSave(Long saleId, Map<String, Object> changes, boolean onlyEmpty, Long userId)
            throws BusinessException {
        OnlineSaleAccountingEntity a = accountingRepository.findByOnlineSaleId(saleId)
                .orElseGet(() -> OnlineSaleAccountingEntity.builder().onlineSaleId(saleId).reviewed(false).build());
        if (Boolean.TRUE.equals(a.getLocked())) {
            throw new BusinessException("Esta línea está bloqueada"
                    + (a.getLockedByName() == null ? "" : " por " + a.getLockedByName())
                    + " y no se puede modificar. Un administrador puede desbloquearla.");
        }
        boolean touched = false;
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            FieldWriter writer = FIELDS.get(change.getKey());
            if (onlyEmpty && !writer.isEmpty(a)) {
                continue;
            }
            writer.write(a, change.getValue());
            touched = true;
        }
        if (!touched && a.getId() != null) {
            return a;
        }
        a.setUpdatedBy(userId);
        return accountingRepository.saveAndFlush(a);
    }

    // ───────── comisiones ─────────

    /**
     * Comisión por vendedor, igual que la hoja de Excel:
     * ventas − devoluciones − anuladas − envío real − comisiones del transporte = subtotal;
     * subtotal − IVA = ventas netas; 2% de las netas − faltantes = total a pagar.
     */
    public static List<SellerCommission> commissions(List<LedgerRow> rows) {
        Map<String, Acc> bySeller = new LinkedHashMap<>();
        for (LedgerRow r : rows) {
            String label = clean(r.getSalesperson()).isEmpty() ? "Sin vendedor" : clean(r.getSalesperson());
            Acc acc = bySeller.computeIfAbsent(label.toUpperCase(Locale.ROOT), k -> new Acc(label));
            BigDecimal total = nz(r.getTotalAmount());
            acc.count++;
            acc.sales = acc.sales.add(total);
            if (isReturn(r.getStatus())) {
                acc.returnsCount++;
                acc.returns = acc.returns.add(total);
            } else if (isVoided(r.getStatus())) {
                acc.voidedCount++;
                acc.voided = acc.voided.add(total);
            }
            acc.realShipping = acc.realShipping.add(nz(r.getRealShippingCost()));
            acc.carrierCommissions = acc.carrierCommissions.add(nz(r.getCarrierCommission()));
            acc.shortages = acc.shortages.add(nz(r.getShortageAmount()));
        }
        List<SellerCommission> out = new ArrayList<>();
        for (Acc acc : bySeller.values()) {
            BigDecimal subtotal = acc.sales.subtract(acc.returns).subtract(acc.voided)
                    .subtract(acc.realShipping).subtract(acc.carrierCommissions);
            BigDecimal vat = subtotal.divide(BigDecimal.ONE.add(VAT_RATE), 10, RoundingMode.HALF_UP)
                    .multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = subtotal.subtract(vat);
            BigDecimal commission = net.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
            out.add(SellerCommission.builder()
                    .seller(acc.label)
                    .salesCount(acc.count).sales(acc.sales)
                    .returnsCount(acc.returnsCount).returns(acc.returns)
                    .voidedCount(acc.voidedCount).voided(acc.voided)
                    .realShipping(acc.realShipping)
                    .carrierCommissions(acc.carrierCommissions)
                    .subtotal(subtotal).vat(vat).netSales(net)
                    .commission(commission)
                    .shortages(acc.shortages)
                    .totalToPay(commission.subtract(acc.shortages))
                    .build());
        }
        out.sort(Comparator.comparing(SellerCommission::getSales).reversed());
        return out;
    }

    public static boolean isReturn(String status) {
        return "DEVOLUCION".equalsIgnoreCase(status);
    }

    public static boolean isVoided(String status) {
        return "ANULADA".equalsIgnoreCase(status) || "CANCELADO".equalsIgnoreCase(status);
    }

    private static final class Acc {
        final String label;
        int count;
        int returnsCount;
        int voidedCount;
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal returns = BigDecimal.ZERO;
        BigDecimal voided = BigDecimal.ZERO;
        BigDecimal realShipping = BigDecimal.ZERO;
        BigDecimal carrierCommissions = BigDecimal.ZERO;
        BigDecimal shortages = BigDecimal.ZERO;

        Acc(String label) {
            this.label = label;
        }
    }

    // ───────── Excel ─────────

    public record ExportFile(String fileName, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public ExportFile export(LocalDate from, LocalDate to) throws BusinessException {
        if (from == null || to == null) {
            throw new BusinessException("Indique el rango de fechas para descargar el Excel.");
        }
        LedgerResponse data = search(from, to, null);
        byte[] bytes = OnlineSaleLedgerExcelExporter.build(from, to, data.getRows(), commissions(data.getRows()));
        return new ExportFile("ventas-en-linea-" + from + "_" + to + ".xlsx", bytes);
    }

    // ───────── conversión de valores ─────────

    private interface FieldWriter {
        boolean isEmpty(OnlineSaleAccountingEntity a);

        /** @throws IllegalArgumentException con el mensaje para el usuario si el valor no sirve. */
        void write(OnlineSaleAccountingEntity a, Object raw);
    }

    private static FieldWriter money(Function<OnlineSaleAccountingEntity, BigDecimal> getter,
                                     BiConsumer<OnlineSaleAccountingEntity, BigDecimal> setter,
                                     boolean allowNegative, String label) {
        return new FieldWriter() {
            @Override
            public boolean isEmpty(OnlineSaleAccountingEntity a) {
                return getter.apply(a) == null;
            }

            @Override
            public void write(OnlineSaleAccountingEntity a, Object raw) {
                String text = raw == null ? "" : raw.toString().replace(",", "").replace("Q", "").trim();
                if (text.isEmpty()) {
                    setter.accept(a, null);
                    return;
                }
                BigDecimal value;
                try {
                    value = new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(label + ": «" + raw + "» no es un monto válido.");
                }
                if (!allowNegative && value.signum() < 0) {
                    throw new IllegalArgumentException(label + ": no puede ser negativo.");
                }
                if (value.abs().compareTo(MAX_AMOUNT) > 0) {
                    throw new IllegalArgumentException(label + ": el monto es demasiado grande.");
                }
                setter.accept(a, value);
            }
        };
    }

    private static FieldWriter date(Function<OnlineSaleAccountingEntity, LocalDate> getter,
                                    BiConsumer<OnlineSaleAccountingEntity, LocalDate> setter, String label) {
        return new FieldWriter() {
            @Override
            public boolean isEmpty(OnlineSaleAccountingEntity a) {
                return getter.apply(a) == null;
            }

            @Override
            public void write(OnlineSaleAccountingEntity a, Object raw) {
                String text = raw == null ? "" : raw.toString().trim();
                if (text.isEmpty()) {
                    setter.accept(a, null);
                    return;
                }
                try {
                    setter.accept(a, LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(label + ": «" + raw + "» no es una fecha válida.");
                }
            }
        };
    }

    private static FieldWriter text(Function<OnlineSaleAccountingEntity, String> getter,
                                    BiConsumer<OnlineSaleAccountingEntity, String> setter, int max, String label) {
        return new FieldWriter() {
            @Override
            public boolean isEmpty(OnlineSaleAccountingEntity a) {
                String current = getter.apply(a);
                return current == null || current.isBlank();
            }

            @Override
            public void write(OnlineSaleAccountingEntity a, Object raw) {
                String text = raw == null ? "" : raw.toString().trim();
                if (text.length() > max) {
                    throw new IllegalArgumentException(label + ": máximo " + max + " caracteres.");
                }
                setter.accept(a, text.isEmpty() ? null : text);
            }
        };
    }

    /** Texto de una lista (transporte, red, estado): se guarda ya normalizado. */
    private static FieldWriter choice(Function<OnlineSaleAccountingEntity, String> getter,
                                      BiConsumer<OnlineSaleAccountingEntity, String> setter, int max, String label,
                                      Function<String, String> normalizer) {
        return new FieldWriter() {
            @Override
            public boolean isEmpty(OnlineSaleAccountingEntity a) {
                String current = getter.apply(a);
                return current == null || current.isBlank();
            }

            @Override
            public void write(OnlineSaleAccountingEntity a, Object raw) {
                String text = raw == null ? "" : raw.toString().trim();
                if (text.isEmpty()) {
                    setter.accept(a, null);
                    return;
                }
                String value = normalizer.apply(text);
                if (value.length() > max) {
                    throw new IllegalArgumentException(label + ": máximo " + max + " caracteres.");
                }
                setter.accept(a, value);
            }
        };
    }

    static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
