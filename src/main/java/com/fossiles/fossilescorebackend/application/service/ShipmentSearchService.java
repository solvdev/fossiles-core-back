package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ShipmentSearchRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentSearchOptionsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentSearchResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DistributionOrigin;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PermissionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductDistributionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductDistributionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Consulta «Envíos»: cualquier envío del sistema por procedencia, con filtros combinables, paginado.
 * <p>
 * La base filtra rango de fechas, kiosco, estado y número; lo demás (procedencia, producto, OP, origen de descarga,
 * personas, notas) se calcula en memoria con datos cargados en lote (sin consultas por fila) sobre a lo más
 * {@link #MAX_SCANNED} envíos. Sin rango se usan los últimos {@link #DEFAULT_DAYS} días.
 * <p>
 * Permisos: administración y quien prepara envíos o distribuciones ven todo; una encargada (o supervisora) solo los
 * envíos de sus kioscos, igual que GET /shipments/by-kiosk.
 */
@Service
@RequiredArgsConstructor
public class ShipmentSearchService {

    static final int DEFAULT_DAYS = 90;
    static final int MAX_SCANNED = 3000;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 500;
    /** La fecha del documento vive en las notas: se buscan candidatos creados en un margen alrededor del rango. */
    static final int DOC_DATE_MARGIN_DAYS = 60;

    static final String PERM_PREPARE_VIEW = "DISTRIBUCION.PREPARAR_ENVIOS.VER";
    static final String PERM_DISTRIBUTIONS_VIEW = "DISTRIBUCION.DISTRIBUCIONES.VER";

    public static final String P_DIRECTO = "DIRECTO";
    public static final String P_DISTRIBUCION = "DISTRIBUCION";
    public static final String P_REPOSICION = "REPOSICION";
    public static final String P_OP = "OP";
    public static final String P_PARCIAL = "PARCIAL";
    public static final String P_ENVP = "ENVP";
    public static final String P_ENVI = "ENVI";
    public static final String P_PENDIENTE = "PENDIENTE";
    public static final String P_HISTORICO = "HISTORICO";
    public static final String P_OTRO = "OTRO";
    static final List<String> PROVENANCES = List.of(P_DIRECTO, P_DISTRIBUCION, P_REPOSICION, P_OP, P_PARCIAL,
            P_ENVP, P_ENVI, P_PENDIENTE, P_HISTORICO, P_OTRO);
    static final List<String> STATUSES = List.of("DRAFT", "CONFIRMED", "SENT", "DELIVERED", "CANCELLED");

    public static final String ORIGIN_RETURNS = "DEVOLUCIONES";
    public static final String ORIGIN_PT = "BODEGA_PT";
    public static final String ORIGIN_MIXED = "MIXTO";

    private static final Pattern DOC_DATE = Pattern.compile("DOCUMENT_DATE:(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern OP_PREFIX = Pattern.compile("^(OP[A-Z]*)-", Pattern.CASE_INSENSITIVE);

    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductDistributionRepository distributionRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductInventoryKardexRepository kardexRepository;
    private final LocationRepository locationRepository;
    private final UserRepository userRepository;
    private final ProductInventoryService productInventoryService;
    private final ProductDistributionService productDistributionService;
    private final KioskSupervisorAssignmentReader supervisorReader;
    private final SecurityUtil securityUtil;

    // ------------------------------------------------------------------ permisos

    /** null = ve todo; si no, los kioscos (location id) que puede ver. */
    Set<Long> resolveScope() throws BusinessException {
        Long userId = securityUtil.getCurrentUserId();
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        if (isAdmin(user) || hasPermission(user, PERM_PREPARE_VIEW) || hasPermission(user, PERM_DISTRIBUTIONS_VIEW)) {
            return null;
        }
        Set<Long> scope = new HashSet<>();
        locationRepository.findByEncargadoIdOrderByNameAsc(userId).stream()
                .map(LocationEntity::getId).filter(Objects::nonNull).forEach(scope::add);
        supervisorReader.supervisors().stream()
                .filter(s -> userId.equals(s.userId()))
                .forEach(s -> scope.addAll(s.kioskLocationIds()));
        if (scope.isEmpty()) {
            throw new BusinessException("Tu usuario no tiene kioscos asignados para consultar envíos.");
        }
        return scope;
    }

    private static boolean isAdmin(UserEntity user) {
        return user.getRoles() != null && user.getRoles().stream()
                .anyMatch(r -> r != null && r.getName() != null && r.getName().toUpperCase(Locale.ROOT).contains("ADMIN"));
    }

    private static boolean hasPermission(UserEntity user, String code) {
        if (user.getRoles() == null) {
            return false;
        }
        for (RoleEntity role : user.getRoles()) {
            if (role == null || role.getPermissions() == null) {
                continue;
            }
            for (PermissionEntity permission : role.getPermissions()) {
                if (permission != null && code.equals(permission.getCode())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ opciones

    @Transactional(readOnly = true)
    public ShipmentSearchOptionsResponse options() throws BusinessException {
        Set<Long> scope = resolveScope();
        List<ShipmentSearchOptionsResponse.Kiosk> kiosks = locationRepository.findAll().stream()
                .filter(ShipmentSearchService::isKiosk)
                .filter(l -> scope == null || scope.contains(l.getId()))
                .sorted(Comparator.comparing(l -> safe(l.getName()), String.CASE_INSENSITIVE_ORDER))
                .map(l -> ShipmentSearchOptionsResponse.Kiosk.builder()
                        .id(l.getId()).code(l.getCode()).name(l.getName()).departamento(l.getDepartamento()).build())
                .collect(Collectors.toList());
        Set<Long> visible = kiosks.stream().map(ShipmentSearchOptionsResponse.Kiosk::getId).collect(Collectors.toSet());
        List<ShipmentSearchOptionsResponse.Supervisor> supervisors = supervisorReader.supervisors().stream()
                .map(s -> ShipmentSearchOptionsResponse.Supervisor.builder()
                        .userId(s.userId())
                        .name(s.name())
                        .kioskIds(s.kioskLocationIds().stream().filter(visible::contains).sorted().collect(Collectors.toList()))
                        .build())
                .filter(s -> !s.getKioskIds().isEmpty())
                .collect(Collectors.toList());
        return ShipmentSearchOptionsResponse.builder()
                .allKiosks(scope == null)
                .kiosks(kiosks)
                .supervisors(supervisors)
                .build();
    }

    private static boolean isKiosk(LocationEntity location) {
        String category = location.getCategoria() == null ? "" : location.getCategoria().trim().toUpperCase(Locale.ROOT);
        return category.contains("KIOSKO") || "KIOSK".equals(category);
    }

    // ------------------------------------------------------------------ búsqueda

    /** Datos de un candidato ya cargados en lote, para filtrar y ordenar sin volver a consultar. */
    private record Row(ProductShipmentEntity shipment,
                       String provenance,
                       String opType,
                       ProductDistributionEntity distribution,
                       ProductionOrderEntity order,
                       List<String> lineTexts,
                       LocalDate documentDate,
                       BigDecimal units,
                       BigDecimal received,
                       boolean difference) {
    }

    @Transactional(readOnly = true)
    public ShipmentSearchResponse search(ShipmentSearchRequest request) throws BusinessException {
        ShipmentSearchRequest q = request == null ? new ShipmentSearchRequest() : request;
        Set<Long> scope = resolveScope();

        String dateField = normalizeDateField(q.getDateField());
        boolean defaultRange = q.getFrom() == null && q.getTo() == null;
        LocalDate to = q.getTo() != null ? q.getTo() : LocalDate.now();
        LocalDate from = q.getFrom() != null ? q.getFrom() : to.minusDays(DEFAULT_DAYS);
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final.");
        }

        // Kioscos: lo pedido, recortado a lo que el usuario puede ver.
        Set<Long> locations = null;
        if (q.getKioskIds() != null && !q.getKioskIds().isEmpty()) {
            locations = new HashSet<>(q.getKioskIds());
            if (scope != null) {
                locations.retainAll(scope);
            }
        } else if (scope != null) {
            locations = new HashSet<>(scope);
        }
        Set<String> statuses = upperSet(q.getStatuses());
        String number = safe(q.getShipmentNumber()).toUpperCase(Locale.ROOT);

        List<ProductShipmentEntity> candidates;
        if (locations != null && locations.isEmpty()) {
            candidates = List.of();
        } else {
            boolean docDate = "doc".equals(dateField);
            LocalDateTime start = (docDate ? from.minusDays(DOC_DATE_MARGIN_DAYS) : from).atStartOfDay();
            LocalDateTime end = (docDate ? to.plusDays(DOC_DATE_MARGIN_DAYS) : to).plusDays(1).atStartOfDay();
            candidates = shipmentRepository.searchCandidates(
                    docDate ? "created" : dateField,
                    start,
                    end,
                    locations == null,
                    locations == null || locations.isEmpty() ? List.of(-1L) : locations,
                    // El estado se filtra en memoria para poder contar los demás estados en los chips.
                    true,
                    List.of("-"),
                    number.isEmpty() ? "" : "%" + number + "%",
                    PageRequest.of(0, MAX_SCANNED + 1));
        }
        boolean truncated = candidates.size() > MAX_SCANNED;
        if (truncated) {
            candidates = candidates.subList(0, MAX_SCANNED);
        }

        List<Row> rows = loadRows(candidates);
        Map<Long, String> userNames = loadUserNames(candidates);
        Set<String> provenances = upperSet(q.getProvenances());
        String originFilter = safe(q.getDispatchOrigin()).toUpperCase(Locale.ROOT);

        // Filtros en memoria, menos procedencia y estado (se usan para los conteos de los chips).
        LocalDate docFrom = from;
        LocalDate docTo = to;
        List<Row> base = new ArrayList<>();
        for (Row row : rows) {
            if ("doc".equals(dateField)) {
                LocalDate d = row.documentDate();
                if (d.isBefore(docFrom) || d.isAfter(docTo)) {
                    continue;
                }
            }
            if (!matchesText(row, q, userNames)) {
                continue;
            }
            base.add(row);
        }
        Map<Long, String> origins = new HashMap<>();
        if (!originFilter.isEmpty()) {
            origins.putAll(loadDispatchOrigins(base.stream().map(r -> r.shipment().getId()).collect(Collectors.toSet())));
            base.removeIf(r -> !originFilter.equals(origins.get(r.shipment().getId())));
        }

        Map<String, Long> countsByProvenance = new LinkedHashMap<>();
        PROVENANCES.forEach(p -> countsByProvenance.put(p, 0L));
        Map<String, Long> countsByStatus = new LinkedHashMap<>();
        STATUSES.forEach(s -> countsByStatus.put(s, 0L));
        List<Row> matched = new ArrayList<>();
        // Cada grupo de chips cuenta con todos los filtros menos el suyo.
        for (Row row : base) {
            String status = safe(row.shipment().getStatus()).toUpperCase(Locale.ROOT);
            boolean statusOk = statuses.isEmpty() || statuses.contains(status);
            boolean provOk = provenances.isEmpty() || provenances.contains(row.provenance());
            if (statusOk) {
                countsByProvenance.merge(row.provenance(), 1L, Long::sum);
            }
            if (provOk) {
                countsByStatus.merge(status, 1L, Long::sum);
            }
            if (statusOk && provOk) {
                matched.add(row);
            }
        }

        matched.sort(comparator(normalizeOrder(q.getOrder()), dateField));

        int size = q.getSize() == null || q.getSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(q.getSize(), MAX_PAGE_SIZE);
        int page = q.getPage() == null || q.getPage() < 0 ? 0 : q.getPage();
        int fromIdx = Math.min(page * size, matched.size());
        int toIdx = Math.min(fromIdx + size, matched.size());
        List<Row> pageRows = matched.subList(fromIdx, toIdx);

        Set<Long> pageIds = pageRows.stream().map(r -> r.shipment().getId()).collect(Collectors.toSet());
        Map<Long, String> pageOrigins = new HashMap<>();
        for (Long id : pageIds) {
            if (origins.containsKey(id)) {
                pageOrigins.put(id, origins.get(id));
            }
        }
        if (originFilter.isEmpty()) {
            pageOrigins.putAll(loadDispatchOrigins(pageIds));
        }
        Map<Long, LocationEntity> locationsById = loadById(
                pageRows.stream().map(r -> r.shipment().getLocationId()).filter(Objects::nonNull).collect(Collectors.toSet()),
                locationRepository::findAllById, LocationEntity::getId);

        List<ProductShipmentResponse> mapped = productDistributionService.toShipmentResponses(
                pageRows.stream().map(Row::shipment).collect(Collectors.toList()));
        List<ShipmentSearchResponse.Item> items = new ArrayList<>(pageRows.size());
        for (int i = 0; i < pageRows.size(); i++) {
            Row row = pageRows.get(i);
            ProductShipmentEntity s = row.shipment();
            LocationEntity loc = s.getLocationId() == null ? null : locationsById.get(s.getLocationId());
            items.add(ShipmentSearchResponse.Item.builder()
                    .shipment(mapped.get(i))
                    .provenance(row.provenance())
                    .productionOrderType(row.opType())
                    .dispatchOrigin(pageOrigins.get(s.getId()))
                    .documentDate(row.documentDate())
                    .totalUnits(row.units())
                    .totalReceived(row.received())
                    .receiptDifference(row.difference())
                    .sentByName(s.getSentBy() == null ? null : userNames.get(s.getSentBy()))
                    .receivedByName(s.getReceivedBy() == null ? null : userNames.get(s.getReceivedBy()))
                    .locationDepartamento(loc != null ? loc.getDepartamento() : null)
                    .build());
        }

        return ShipmentSearchResponse.builder()
                .items(items)
                .total(matched.size())
                .totalUnits(matched.stream().map(Row::units).reduce(BigDecimal.ZERO, BigDecimal::add))
                .page(page)
                .size(size)
                .dateField(dateField)
                .from(from)
                .to(to)
                .defaultRangeApplied(defaultRange)
                .truncated(truncated)
                .maxScanned(MAX_SCANNED)
                .countsByProvenance(countsByProvenance)
                .countsByStatus(countsByStatus)
                .build();
    }

    private boolean matchesText(Row row, ShipmentSearchRequest q, Map<Long, String> userNames) {
        ProductShipmentEntity s = row.shipment();
        String dist = safe(q.getDistributionNumber()).toUpperCase(Locale.ROOT);
        if (!dist.isEmpty() && (row.distribution() == null
                || !safe(row.distribution().getDistributionNumber()).toUpperCase(Locale.ROOT).contains(dist))) {
            return false;
        }
        String opCode = safe(q.getProductionOrderCode()).toUpperCase(Locale.ROOT);
        if (!opCode.isEmpty() && (row.order() == null
                || !safe(row.order().getCode()).toUpperCase(Locale.ROOT).contains(opCode))) {
            return false;
        }
        String opType = safe(q.getProductionOrderType()).toUpperCase(Locale.ROOT);
        if (!opType.isEmpty() && !opType.equals(row.opType())) {
            return false;
        }
        if (Boolean.TRUE.equals(q.getReceiptDifference()) && !row.difference()) {
            return false;
        }
        if (Boolean.TRUE.equals(q.getPendingProduction()) && !P_PENDIENTE.equals(row.provenance())) {
            return false;
        }
        String notes = safe(q.getNotes()).toLowerCase(Locale.ROOT);
        if (!notes.isEmpty() && !(safe(s.getNotes()) + " " + safe(s.getReceivedNotes())).toLowerCase(Locale.ROOT).contains(notes)) {
            return false;
        }
        String person = safe(q.getPerson()).toLowerCase(Locale.ROOT);
        if (!person.isEmpty()) {
            boolean any = java.util.stream.Stream.of(s.getCreatedBy(), s.getSentBy(), s.getReceivedBy())
                    .filter(Objects::nonNull)
                    .map(userNames::get)
                    .anyMatch(n -> n != null && n.toLowerCase(Locale.ROOT).contains(person));
            if (!any) {
                return false;
            }
        }
        String product = safe(q.getProduct()).toLowerCase(Locale.ROOT);
        if (!product.isEmpty()) {
            String[] terms = product.split("[\\s,]+");
            boolean any = row.lineTexts().stream().anyMatch(text -> {
                for (String t : terms) {
                    if (!t.isEmpty() && !text.contains(t)) {
                        return false;
                    }
                }
                return true;
            });
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private List<Row> loadRows(List<ProductShipmentEntity> shipments) {
        if (shipments.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = shipments.stream().map(ProductShipmentEntity::getId).collect(Collectors.toSet());
        Map<Long, List<ProductShipmentDetailEntity>> detailsByShipment = new HashMap<>();
        shipmentDetailRepository.findByShipmentIdIn(ids).stream()
                .sorted(Comparator.comparing(ProductShipmentDetailEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(d -> detailsByShipment.computeIfAbsent(d.getShipmentId(), k -> new ArrayList<>()).add(d));
        Set<Long> productIds = new HashSet<>();
        Set<Long> colorIds = new HashSet<>();
        detailsByShipment.values().forEach(list -> list.forEach(d -> {
            if (d.getProductId() != null) productIds.add(d.getProductId());
            if (d.getColorId() != null) colorIds.add(d.getColorId());
        }));
        Map<Long, ProductEntity> products = loadById(productIds, productRepository::findAllById, ProductEntity::getId);
        Map<Long, ColorEntity> colors = loadById(colorIds, colorRepository::findAllById, ColorEntity::getId);
        Map<Long, ProductDistributionEntity> distributions = loadById(
                shipments.stream().map(ProductShipmentEntity::getDistributionId).filter(Objects::nonNull).collect(Collectors.toSet()),
                distributionRepository::findAllById, ProductDistributionEntity::getId);
        Map<Long, ProductionOrderEntity> orders = loadById(
                shipments.stream().map(ProductShipmentEntity::getProductionOrderId).filter(Objects::nonNull).collect(Collectors.toSet()),
                productionOrderRepository::findAllById, ProductionOrderEntity::getId);

        List<Row> rows = new ArrayList<>(shipments.size());
        for (ProductShipmentEntity s : shipments) {
            List<ProductShipmentDetailEntity> details = detailsByShipment.getOrDefault(s.getId(), List.of());
            ProductDistributionEntity dist = s.getDistributionId() == null ? null : distributions.get(s.getDistributionId());
            ProductionOrderEntity order = s.getProductionOrderId() == null ? null : orders.get(s.getProductionOrderId());
            BigDecimal units = BigDecimal.ZERO;
            BigDecimal received = BigDecimal.ZERO;
            boolean difference = false;
            List<String> lineTexts = new ArrayList<>(details.size());
            for (ProductShipmentDetailEntity d : details) {
                // Texto de búsqueda de la línea: código, nombre, color y talla.
                ProductEntity p = d.getProductId() == null ? null : products.get(d.getProductId());
                ColorEntity c = d.getColorId() == null ? null : colors.get(d.getColorId());
                lineTexts.add((safe(p != null ? p.getCode() : null) + " " + safe(p != null ? p.getName() : null)
                        + " " + safe(c != null ? c.getName() : null) + " " + safe(d.getSizeLabel())).toLowerCase(Locale.ROOT));
                BigDecimal qty = d.getQuantity() == null ? BigDecimal.ZERO : d.getQuantity();
                units = units.add(qty);
                if (d.getQuantityReceived() != null) {
                    received = received.add(d.getQuantityReceived());
                    if (d.getQuantityReceived().compareTo(qty) != 0) {
                        difference = true;
                    }
                }
            }
            rows.add(new Row(s, provenanceOf(s, dist, order), opTypeOf(order), dist, order, lineTexts,
                    documentDateOf(s), units, received, difference));
        }
        return rows;
    }

    /**
     * Procedencia derivada de campos que ya existen. El orden importa: un histórico o un pendiente de producción
     * se muestran como tales aunque vivan en una distribución.
     */
    static String provenanceOf(ProductShipmentEntity s, ProductDistributionEntity dist, ProductionOrderEntity order) {
        String number = safe(s.getShipmentNumber()).toUpperCase(Locale.ROOT);
        if (CustomerAccountService.isHistoricalShipment(s)) {
            return P_HISTORICO;
        }
        if (ProductDistributionService.isPendingProductionShipment(s)) {
            return P_PENDIENTE;
        }
        boolean internalOrder = order != null && "INTERNA".equalsIgnoreCase(safe(order.getOrderType()));
        if (number.startsWith("ENVI-") || ProductDistributionService.isStandaloneInternalEnviShipment(s) || internalOrder) {
            return P_ENVI;
        }
        if (s.getPartialReleaseId() != null) {
            return P_PARCIAL;
        }
        if (number.startsWith("ENVP-")) {
            return P_ENVP;
        }
        boolean legacyDirect = dist != null && safe(dist.getDescription())
                .startsWith(ProductDistributionService.DIRECT_KIOSK_DISTRIBUTION_DESCRIPTION);
        if (s.getLocationId() != null && s.getProductionOrderId() == null
                && (s.getDistributionId() == null || legacyDirect)) {
            return P_DIRECTO;
        }
        if (s.getProductionOrderId() != null) {
            return P_OP;
        }
        if (dist != null && DistributionOrigin.REPOSICION.equals(DistributionOrigin.normalize(dist.getOrigin()))) {
            return P_REPOSICION;
        }
        if (s.getDistributionId() != null) {
            return P_DISTRIBUCION;
        }
        return P_OTRO;
    }

    /** OPK, OPCK, OPC, OPV, OPL, OPI… según el prefijo del código; si no lo tiene, según el tipo de orden. */
    static String opTypeOf(ProductionOrderEntity order) {
        if (order == null) {
            return null;
        }
        Matcher m = OP_PREFIX.matcher(safe(order.getCode()));
        if (m.find()) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        String type = safe(order.getOrderType()).toUpperCase(Locale.ROOT);
        return switch (type) {
            case "INTERNA" -> "OPI";
            case "CLIENTE_KIOSKO" -> "OPCK";
            case "CINCHOS", "CINCHOS_FOSSILES", "CINCHOS_MARCAS" -> "OPC";
            case "NORMAL" -> "OPK";
            default -> type.isEmpty() ? null : type;
        };
    }

    /** Fecha del documento (línea DOCUMENT_DATE de las notas); si no tiene, la de creación. */
    static LocalDate documentDateOf(ProductShipmentEntity s) {
        Matcher m = DOC_DATE.matcher(safe(s.getNotes()));
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(1));
            } catch (DateTimeParseException ignored) {
                // cae a la fecha de creación
            }
        }
        return s.getCreatedAt() != null ? s.getCreatedAt().toLocalDate() : LocalDate.now();
    }

    /** Origen de descarga según las salidas del kardex del envío (una consulta para todos). */
    private Map<Long, String> loadDispatchOrigins(Set<Long> shipmentIds) {
        Map<Long, String> result = new HashMap<>();
        if (shipmentIds.isEmpty()) {
            return result;
        }
        Long ptId = null;
        Set<Long> dispatchIds = new HashSet<>();
        try {
            for (LocationEntity loc : productInventoryService.getDispatchSourceWarehouses()) {
                dispatchIds.add(loc.getId());
                if ("BODEGA_PT".equalsIgnoreCase(safe(loc.getCode()))) {
                    ptId = loc.getId();
                }
            }
        } catch (BusinessException ex) {
            return result;
        }
        Map<Long, Set<String>> seen = new HashMap<>();
        for (ProductInventoryKardex k : kardexRepository.findByReferenceTypeAndReferenceIdIn("SHIPMENT", shipmentIds)) {
            if (k.getQuantity() == null || k.getQuantity().signum() >= 0 || !dispatchIds.contains(k.getLocationId())) {
                continue;
            }
            seen.computeIfAbsent(k.getReferenceId(), x -> new HashSet<>())
                    .add(Objects.equals(k.getLocationId(), ptId) ? ORIGIN_PT : ORIGIN_RETURNS);
        }
        seen.forEach((id, set) -> result.put(id, set.size() > 1 ? ORIGIN_MIXED : set.iterator().next()));
        return result;
    }

    private Map<Long, String> loadUserNames(List<ProductShipmentEntity> shipments) {
        Set<Long> ids = new HashSet<>();
        shipments.forEach(s -> {
            if (s.getCreatedBy() != null) ids.add(s.getCreatedBy());
            if (s.getSentBy() != null) ids.add(s.getSentBy());
            if (s.getReceivedBy() != null) ids.add(s.getReceivedBy());
        });
        Map<Long, String> names = new HashMap<>();
        loadById(ids, userRepository::findAllById, UserEntity::getId).forEach((id, u) -> {
            String full = (safe(u.getFirstName()) + " " + safe(u.getLastName())).trim();
            names.put(id, full.isEmpty() ? safe(u.getUsername()) : full);
        });
        return names;
    }

    private static Comparator<Row> comparator(String order, String dateField) {
        Function<Row, LocalDateTime> date = r -> {
            ProductShipmentEntity s = r.shipment();
            return switch (dateField) {
                case "sent" -> s.getSentAt();
                case "received" -> s.getReceivedAt();
                case "doc" -> r.documentDate().atStartOfDay();
                default -> s.getCreatedAt();
            };
        };
        Comparator<Row> byId = Comparator.comparing(r -> r.shipment().getId(), Comparator.nullsLast(Comparator.reverseOrder()));
        return switch (order) {
            case "date-asc" -> Comparator.comparing(date, Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())).thenComparing(byId);
            // El número de un envío a kiosco empieza por el código del kiosco; los que no tienen kiosco van al final.
            case "kiosk" -> Comparator.comparing((Row r) -> r.shipment().getLocationId() == null)
                    .thenComparing(r -> safe(r.shipment().getShipmentNumber())).thenComparing(byId);
            case "units" -> Comparator.comparing(Row::units, Comparator.reverseOrder()).thenComparing(byId);
            case "number" -> Comparator.comparing((Row r) -> safe(r.shipment().getShipmentNumber())).thenComparing(byId);
            default -> Comparator.comparing(date, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())).thenComparing(byId);
        };
    }

    private static String normalizeDateField(String raw) {
        String v = safe(raw).toLowerCase(Locale.ROOT);
        return switch (v) {
            case "sent", "received", "doc" -> v;
            default -> "created";
        };
    }

    private static String normalizeOrder(String raw) {
        String v = safe(raw).toLowerCase(Locale.ROOT);
        return switch (v) {
            case "date-asc", "kiosk", "units", "number" -> v;
            default -> "date-desc";
        };
    }

    private static Set<String> upperSet(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().filter(Objects::nonNull).map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(v -> !v.isEmpty()).collect(Collectors.toCollection(HashSet::new));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> Map<Long, T> loadById(Set<Long> ids, Function<Set<Long>, ? extends Iterable<T>> loader,
                                             Function<T, Long> idGetter) {
        Map<Long, T> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        loader.apply(ids).forEach(e -> map.put(idGetter.apply(e), e));
        return map;
    }
}
