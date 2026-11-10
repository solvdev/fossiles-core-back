package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeRejectRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosExtraRequestCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosExtraRequestResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.util.ProductHardwareCondition;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPosExtraRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosExtraRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Complemento extra en el POS de kioscos: el cajero pide cobrar un monto adicional por pieza
 * (descripción libre) y admin/logística lo autoriza igual que los cambios sin diferencia.
 * La solicitud aprobada se consume al cobrar ({@link #lockApprovedForSale}); el precio lo calcula el servidor.
 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class KioskPosExtraRequestService {

    private static final int DESCRIPTION_MAX = 300;

    private final KioskPosExtraRequestRepository requestRepository;
    private final KioskExchangeAuthorizationGuard authorizationGuard;
    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;

    public KioskPosExtraRequestResponse create(KioskPosExtraRequestCreateRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null) {
            throw new BusinessException("Solicitud inválida.");
        }
        String description = safeTrim(request.getDescription());
        if (description.isEmpty()) {
            throw new BusinessException("Describe el complemento.");
        }
        if (description.length() > DESCRIPTION_MAX) {
            throw new BusinessException("La descripción del complemento admite máximo " + DESCRIPTION_MAX + " caracteres.");
        }
        BigDecimal extra = request.getExtraAmount() == null
                ? BigDecimal.ZERO
                : request.getExtraAmount().setScale(2, RoundingMode.HALF_UP);
        if (extra.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto extra debe ser mayor a cero.");
        }
        BigDecimal quantity = request.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("La cantidad debe ser mayor a cero.");
        }

        UserEntity user = getCurrentUserOrThrow();
        LocationEntity kiosk = resolveKiosk(user, request.getKioskLocationId());
        ProductEntity product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.getProductId()));
        ColorEntity color = null;
        if (request.getColorId() != null) {
            color = colorRepository.findById(request.getColorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Color", request.getColorId()));
        }
        String size = ProductInventorySizesJson.normalizeKey(request.getSize());

        KioskPosExtraRequestEntity saved = requestRepository.save(KioskPosExtraRequestEntity.builder()
                .kioskLocationId(kiosk.getId())
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .colorId(color != null ? color.getId() : null)
                .colorName(color != null ? color.getName() : "")
                .size(size.isEmpty() ? null : size)
                .hardwareCondition(ProductHardwareCondition.normalizeStockDimension(request.getHardwareCondition()))
                .quantity(quantity)
                .extraAmount(extra)
                .catalogUnitPrice(request.getCatalogUnitPrice() != null
                        ? request.getCatalogUnitPrice().setScale(2, RoundingMode.HALF_UP)
                        : null)
                .description(description)
                .status(KioskPosExtraRequestEntity.STATUS_PENDING)
                .requestDate(GuatemalaDateTime.today())
                .requestedBy(user.getId())
                .requestedAt(GuatemalaDateTime.now())
                .build());
        return toResponses(List.of(saved)).get(0);
    }

    /** Solicitudes del día del kiosko (pendientes, aprobadas y rechazadas aún sin descartar). */
    @Transactional(readOnly = true)
    public List<KioskPosExtraRequestResponse> listForKiosk(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        LocationEntity kiosk = resolveKiosk(user, kioskLocationId);
        List<KioskPosExtraRequestEntity> rows = requestRepository
                .findByKioskLocationIdAndRequestDateAndStatusInOrderByRequestedAtAsc(
                        kiosk.getId(),
                        GuatemalaDateTime.today(),
                        List.of(
                                KioskPosExtraRequestEntity.STATUS_PENDING,
                                KioskPosExtraRequestEntity.STATUS_APPROVED,
                                KioskPosExtraRequestEntity.STATUS_REJECTED));
        return toResponses(rows);
    }

    @Transactional(readOnly = true)
    public List<KioskPosExtraRequestResponse> listPendingAuthorizations(Long kioskLocationId) throws BusinessException {
        authorizationGuard.assertCanViewPendingAuthorizations();
        List<KioskPosExtraRequestEntity> rows = requestRepository
                .findByStatusOrderByRequestedAtAsc(KioskPosExtraRequestEntity.STATUS_PENDING).stream()
                .filter(row -> kioskLocationId == null || Objects.equals(row.getKioskLocationId(), kioskLocationId))
                .collect(Collectors.toList());
        return toResponses(rows);
    }

    public KioskPosExtraRequestResponse authorize(Long id) throws BusinessException, ResourceNotFoundException {
        authorizationGuard.assertCanApproveOrReject();
        KioskPosExtraRequestEntity row = lockOrThrow(id);
        requireStatus(row, KioskPosExtraRequestEntity.STATUS_PENDING, "Esta solicitud no está pendiente de autorización.");
        UserEntity user = getCurrentUserOrThrow();
        row.setStatus(KioskPosExtraRequestEntity.STATUS_APPROVED);
        row.setAuthorizedBy(user.getId());
        row.setAuthorizedAt(GuatemalaDateTime.now());
        return toResponses(List.of(requestRepository.save(row))).get(0);
    }

    public KioskPosExtraRequestResponse reject(Long id, KioskExchangeRejectRequest request)
            throws BusinessException, ResourceNotFoundException {
        authorizationGuard.assertCanApproveOrReject();
        if (request == null || safeTrim(request.getReason()).isEmpty()) {
            throw new BusinessException("Debes indicar el motivo del rechazo.");
        }
        KioskPosExtraRequestEntity row = lockOrThrow(id);
        requireStatus(row, KioskPosExtraRequestEntity.STATUS_PENDING, "Esta solicitud no está pendiente de autorización.");
        UserEntity user = getCurrentUserOrThrow();
        row.setStatus(KioskPosExtraRequestEntity.STATUS_REJECTED);
        row.setRejectionReason(safeTrim(request.getReason()));
        row.setAuthorizedBy(user.getId());
        row.setAuthorizedAt(GuatemalaDateTime.now());
        return toResponses(List.of(requestRepository.save(row))).get(0);
    }

    /** El cajero retira su solicitud (quitó la línea del carrito o descarta un rechazo). */
    public KioskPosExtraRequestResponse cancel(Long id) throws BusinessException, ResourceNotFoundException {
        KioskPosExtraRequestEntity row = lockOrThrow(id);
        UserEntity user = getCurrentUserOrThrow();
        // Mismo alcance que el POS: encargada del kiosko o admin/logística.
        resolveKiosk(user, row.getKioskLocationId());
        String status = safeTrim(row.getStatus());
        if (KioskPosExtraRequestEntity.STATUS_USED.equalsIgnoreCase(status)) {
            throw new BusinessException("Esta solicitud ya se usó en una venta.");
        }
        if (!KioskPosExtraRequestEntity.STATUS_CANCELLED.equalsIgnoreCase(status)) {
            row.setStatus(KioskPosExtraRequestEntity.STATUS_CANCELLED);
            row = requestRepository.save(row);
        }
        return toResponses(List.of(row)).get(0);
    }

    /**
     * Valida y bloquea la solicitud aprobada que respalda una línea de venta. Debe llamarse dentro de la
     * transacción de la venta; luego {@link #markUsed} la marca como usada.
     */
    KioskPosExtraRequestEntity lockApprovedForSale(
            Long requestId,
            Long kioskLocationId,
            Long productId,
            Long colorId,
            String size,
            String hardwareCondition,
            BigDecimal quantity
    ) throws BusinessException {
        KioskPosExtraRequestEntity row = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException("La solicitud de complemento no existe."));
        String status = safeTrim(row.getStatus());
        if (KioskPosExtraRequestEntity.STATUS_USED.equalsIgnoreCase(status)) {
            throw new BusinessException("El complemento " + requestNumber(row) + " ya se usó en otra venta.");
        }
        if (KioskPosExtraRequestEntity.STATUS_PENDING.equalsIgnoreCase(status)) {
            throw new BusinessException("El complemento " + requestNumber(row) + " aún no está autorizado.");
        }
        if (KioskPosExtraRequestEntity.STATUS_REJECTED.equalsIgnoreCase(status)) {
            throw new BusinessException("El complemento " + requestNumber(row) + " fue rechazado.");
        }
        if (!KioskPosExtraRequestEntity.STATUS_APPROVED.equalsIgnoreCase(status)) {
            throw new BusinessException("El complemento " + requestNumber(row) + " fue cancelado.");
        }
        if (!Objects.equals(row.getKioskLocationId(), kioskLocationId)) {
            throw new BusinessException("El complemento " + requestNumber(row) + " es de otro kiosko.");
        }
        if (!GuatemalaDateTime.today().equals(row.getRequestDate())) {
            throw new BusinessException("El complemento " + requestNumber(row) + " venció (solo vale el día de la solicitud). Solicítalo de nuevo.");
        }
        String lineSize = ProductInventorySizesJson.normalizeKey(size);
        String rowSize = ProductInventorySizesJson.normalizeKey(row.getSize());
        boolean sameLine = Objects.equals(row.getProductId(), productId)
                && Objects.equals(row.getColorId(), colorId)
                && rowSize.equalsIgnoreCase(lineSize)
                && safeTrim(row.getHardwareCondition()).equalsIgnoreCase(
                        ProductHardwareCondition.normalizeStockDimension(hardwareCondition))
                && quantity != null
                && row.getQuantity().compareTo(quantity) == 0;
        if (!sameLine) {
            throw new BusinessException("El complemento " + requestNumber(row)
                    + " no corresponde a este producto/cantidad. Quita la línea y solicítalo de nuevo.");
        }
        return row;
    }

    void markUsed(KioskPosExtraRequestEntity row, Long saleId) {
        row.setStatus(KioskPosExtraRequestEntity.STATUS_USED);
        row.setSaleId(saleId);
        row.setUsedAt(GuatemalaDateTime.now());
        requestRepository.save(row);
    }

    public static String requestNumber(KioskPosExtraRequestEntity row) {
        return "CEX-" + String.format("%05d", row.getId() == null ? 0 : row.getId());
    }

    private KioskPosExtraRequestEntity lockOrThrow(Long id) throws ResourceNotFoundException {
        return requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitud de complemento", id));
    }

    private void requireStatus(KioskPosExtraRequestEntity row, String expected, String message) throws BusinessException {
        if (!expected.equalsIgnoreCase(safeTrim(row.getStatus()))) {
            throw new BusinessException(message);
        }
    }

    private List<KioskPosExtraRequestResponse> toResponses(List<KioskPosExtraRequestEntity> rows) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> kioskIds = new HashSet<>();
        for (KioskPosExtraRequestEntity row : rows) {
            if (row.getRequestedBy() != null) {
                userIds.add(row.getRequestedBy());
            }
            if (row.getAuthorizedBy() != null) {
                userIds.add(row.getAuthorizedBy());
            }
            if (row.getKioskLocationId() != null) {
                kioskIds.add(row.getKioskLocationId());
            }
        }
        Map<Long, UserEntity> users = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), u));
        Map<Long, LocationEntity> kiosks = new HashMap<>();
        locationRepository.findAllById(kioskIds).forEach(k -> kiosks.put(k.getId(), k));

        return rows.stream().map(row -> {
            LocationEntity kiosk = kiosks.get(row.getKioskLocationId());
            BigDecimal quantity = row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO;
            BigDecimal extraTotal = row.getExtraAmount().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            return KioskPosExtraRequestResponse.builder()
                    .id(row.getId())
                    .requestNumber(requestNumber(row))
                    .kioskLocationId(row.getKioskLocationId())
                    .kioskName(kiosk != null ? kiosk.getName() : null)
                    .kioskCode(kiosk != null ? kiosk.getCode() : null)
                    .productId(row.getProductId())
                    .productCode(row.getProductCode())
                    .productName(row.getProductName())
                    .colorId(row.getColorId())
                    .colorName(row.getColorName())
                    .size(row.getSize())
                    .hardwareCondition(row.getHardwareCondition())
                    .quantity(quantity)
                    .extraAmount(row.getExtraAmount())
                    .extraTotal(extraTotal)
                    .catalogUnitPrice(row.getCatalogUnitPrice())
                    .resultingUnitPrice(row.getCatalogUnitPrice() != null
                            ? row.getCatalogUnitPrice().add(row.getExtraAmount())
                            : null)
                    .description(row.getDescription())
                    .status(row.getStatus())
                    .requestDate(row.getRequestDate())
                    .requestedByUserId(row.getRequestedBy())
                    .requestedByName(formatUserName(users.get(row.getRequestedBy())))
                    .requestedAt(row.getRequestedAt())
                    .authorizedByUserId(row.getAuthorizedBy())
                    .authorizedByName(formatUserName(users.get(row.getAuthorizedBy())))
                    .authorizedAt(row.getAuthorizedAt())
                    .rejectionReason(row.getRejectionReason())
                    .saleId(row.getSaleId())
                    .build();
        }).collect(Collectors.toList());
    }

    UserEntity getCurrentUserOrThrow() throws BusinessException {
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException("No se encontró el usuario autenticado."));
    }

    /** Kiosko al que el usuario tiene acceso: admin/logística cualquiera; encargada solo los suyos. */
    LocationEntity resolveKiosk(UserEntity user, Long kioskLocationId) throws BusinessException {
        if (KioskAccessHelper.hasAllKiosksAccess(user)) {
            if (kioskLocationId == null) {
                throw new BusinessException("Indica el kiosko.");
            }
            return locationRepository.findById(kioskLocationId)
                    .orElseThrow(() -> new BusinessException("No se encontró el kiosko seleccionado."));
        }
        List<LocationEntity> own = locationRepository.findByEncargadoIdOrderByNameAsc(user.getId());
        if (own == null || own.isEmpty()) {
            throw new BusinessException("Tu usuario no tiene kiosko asignado. Configura el encargado del kiosko.");
        }
        if (kioskLocationId == null) {
            return own.get(0);
        }
        return own.stream()
                .filter(item -> Objects.equals(item.getId(), kioskLocationId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("No tienes acceso al kiosko seleccionado."));
    }

    static String formatUserName(UserEntity user) {
        if (user == null) {
            return null;
        }
        String full = (safeTrim(user.getFirstName()) + " " + safeTrim(user.getLastName())).trim();
        return full.isBlank() ? safeTrim(user.getUsername()) : full;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
