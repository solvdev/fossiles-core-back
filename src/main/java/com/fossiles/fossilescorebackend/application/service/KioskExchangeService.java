package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeRejectRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeCompleteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangePreviewRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSimpleReturnRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeCompleteResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangePreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeSlipResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KioskExchangeService {

    private static final String SLIP_TYPE_EXCHANGE = "EXCHANGE";
    private static final String SLIP_TYPE_RETURN = "RETURN";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PENDING_REINTEGRO = "PENDING_REINTEGRO";
    private static final String STATUS_PENDING_AUTHORIZATION = "PENDING_AUTHORIZATION";
    private static final String STATUS_REJECTED = "REJECTED";

    private final KioskExchangeSlipRepository exchangeSlipRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final KioskSaleRepository kioskSaleRepository;
    private final KioskSaleItemRepository kioskSaleItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final LocationRepository locationRepository;
    private final UserRepository userRepository;
    private final KioscoInventoryService kioscoInventoryService;
    private final KioskPosService kioskPosService;
    private final KioskExchangeAuthorizationGuard exchangeAuthorizationGuard;
    private final SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public List<KioskExchangeSlipResponse> listExchanges(Long kioskLocationId) throws BusinessException {
        AccessContext ctx = resolveAccessContext(kioskLocationId, true);
        List<KioskExchangeSlipEntity> slips;
        if (kioskLocationId != null) {
            slips = exchangeSlipRepository.findByKioskLocationIdOrderByCreatedAtDesc(ctx.kiosk().getId());
        } else {
            slips = exchangeSlipRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(slip -> ctx.availableKiosks().stream()
                            .anyMatch(k -> Objects.equals(k.getId(), slip.getKioskLocationId())))
                    .collect(Collectors.toList());
        }
        return slips.stream().map(slip -> toSlipResponse(slip, ctx)).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioskExchangeSlipResponse> listPendingReintegros(Long kioskLocationId) throws BusinessException {
        AccessContext ctx = resolveAccessContext(kioskLocationId, true);
        List<KioskExchangeSlipEntity> slips;
        if (kioskLocationId != null) {
            slips = exchangeSlipRepository.findByKioskLocationIdAndStatusOrderByCreatedAtDesc(
                    ctx.kiosk().getId(), STATUS_PENDING_REINTEGRO);
        } else {
            slips = exchangeSlipRepository.findByStatusOrderByCreatedAtDesc(STATUS_PENDING_REINTEGRO).stream()
                    .filter(slip -> ctx.availableKiosks().stream()
                            .anyMatch(k -> Objects.equals(k.getId(), slip.getKioskLocationId())))
                    .collect(Collectors.toList());
        }
        return slips.stream().map(slip -> toSlipResponse(slip, ctx)).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioskExchangeSlipResponse> listPendingAuthorizations(Long kioskLocationId) throws BusinessException {
        exchangeAuthorizationGuard.assertCanViewPendingAuthorizations();
        AccessContext ctx = resolveAccessContext(kioskLocationId, true);
        List<KioskExchangeSlipEntity> slips = exchangeSlipRepository.findByStatusOrderByCreatedAtDesc(STATUS_PENDING_AUTHORIZATION);
        return slips.stream()
                .filter(slip -> SLIP_TYPE_EXCHANGE.equalsIgnoreCase(safeTrim(slip.getSlipType())))
                .filter(slip -> ctx.availableKiosks().stream()
                        .anyMatch(k -> Objects.equals(k.getId(), slip.getKioskLocationId())))
                .filter(slip -> kioskLocationId == null || Objects.equals(slip.getKioskLocationId(), kioskLocationId))
                .map(slip -> toSlipResponse(slip, ctx))
                .collect(Collectors.toList());
    }

    @Transactional
    public KioskExchangeSlipResponse authorizeExchange(Long slipId, Long kioskLocationId)
            throws BusinessException, ResourceNotFoundException {
        exchangeAuthorizationGuard.assertCanApproveOrReject();
        AccessContext ctx = resolveAccessContext(kioskLocationId, kioskLocationId == null);
        KioskExchangeSlipEntity slip = findAccessibleSlip(slipId, ctx);
        if (!SLIP_TYPE_EXCHANGE.equalsIgnoreCase(safeTrim(slip.getSlipType()))) {
            throw new BusinessException("Solo se pueden autorizar boletas de cambio.");
        }
        if (!STATUS_PENDING_AUTHORIZATION.equalsIgnoreCase(safeTrim(slip.getStatus()))) {
            throw new BusinessException("Esta solicitud no está pendiente de autorización.");
        }

        int quantity = slip.getReturnedQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact();
        String cambioReason = buildExchangeMovementReason(slip);

        KioscoInventoryService.CambioResult cambio = kioscoInventoryService.registrarCambio(
                slip.getKioskLocationId(),
                slip.getReturnedProductId(),
                slip.getReturnedColorId(),
                slip.getGivenProductId(),
                slip.getGivenColorId(),
                quantity,
                slip.getId(),
                cambioReason,
                ctx.user().getId(),
                slip.getSlipNumber(),
                slip.getReturnedSize(),
                slip.getGivenSize()
        );

        slip.setReturnMovementId(cambio.getReturnedMovementId());
        slip.setGivenMovementId(cambio.getGivenMovementId());
        slip.setStatus(STATUS_COMPLETED);
        slip.setAuthorizedBy(ctx.user().getId());
        slip.setAuthorizedAt(GuatemalaDateTime.now());
        slip.setCompletedAt(slip.getAuthorizedAt());
        slip = exchangeSlipRepository.save(slip);
        return toSlipResponse(slip, ctx);
    }

    @Transactional
    public KioskExchangeSlipResponse rejectExchange(Long slipId, Long kioskLocationId, KioskExchangeRejectRequest request)
            throws BusinessException, ResourceNotFoundException {
        exchangeAuthorizationGuard.assertCanApproveOrReject();
        if (request == null || safeTrim(request.getReason()).isEmpty()) {
            throw new BusinessException("Debes indicar el motivo del rechazo.");
        }
        AccessContext ctx = resolveAccessContext(kioskLocationId, kioskLocationId == null);
        KioskExchangeSlipEntity slip = findAccessibleSlip(slipId, ctx);
        if (!SLIP_TYPE_EXCHANGE.equalsIgnoreCase(safeTrim(slip.getSlipType()))) {
            throw new BusinessException("Solo se pueden rechazar boletas de cambio.");
        }
        if (!STATUS_PENDING_AUTHORIZATION.equalsIgnoreCase(safeTrim(slip.getStatus()))) {
            throw new BusinessException("Esta solicitud no está pendiente de autorización.");
        }

        slip.setStatus(STATUS_REJECTED);
        slip.setRejectionReason(safeTrim(request.getReason()));
        slip.setAuthorizedBy(ctx.user().getId());
        slip.setAuthorizedAt(GuatemalaDateTime.now());
        slip = exchangeSlipRepository.save(slip);
        return toSlipResponse(slip, ctx);
    }

    @Transactional(readOnly = true)
    public KioskExchangeSlipResponse getExchangeById(Long id, Long kioskLocationId)
            throws BusinessException, ResourceNotFoundException {
        AccessContext ctx = resolveAccessContext(kioskLocationId, kioskLocationId == null);
        KioskExchangeSlipEntity slip = findAccessibleSlip(id, ctx);
        return toSlipResponse(slip, ctx);
    }

    @Transactional(readOnly = true)
    public KioskPosSaleResponse lookupSale(Long kioskLocationId, String query)
            throws BusinessException, ResourceNotFoundException {
        if (query == null || query.isBlank()) {
            throw new BusinessException("Indica el número de venta a buscar.");
        }
        AccessContext ctx = resolveAccessContext(kioskLocationId);
        KioskSaleEntity sale = findSaleByQuery(ctx.kiosk().getId(), query.trim());
        validateOriginalSale(sale);
        return kioskPosService.getSaleById(sale.getId(), ctx.kiosk().getId());
    }

    @Transactional(readOnly = true)
    public KioskExchangePreviewResponse previewExchange(KioskExchangePreviewRequest request)
            throws BusinessException, ResourceNotFoundException {
        ExchangeContext exchange = buildExchangeContext(request, false);
        return exchange.preview();
    }

    @Transactional
    public KioskExchangeCompleteResponse completeExchange(KioskExchangeCompleteRequest request)
            throws BusinessException, ResourceNotFoundException {
        ExchangeContext exchange = buildExchangeContext(request, true);
        KioskExchangePreviewResponse preview = exchange.preview();
        String slipNumber = requireAvailablePhysicalSlipNumber(request.getPhysicalSlipNumber());

        if (preview.getDifferenceAmount() == null
                || preview.getDifferenceAmount().compareTo(BigDecimal.ZERO) == 0) {
            return submitZeroDifferenceExchange(request, exchange, preview, slipNumber);
        }

        UserEntity user = exchange.access().user();
        LocationEntity kiosk = exchange.access().kiosk();

        kioscoInventoryService.registrarDevolucionCliente(
                kiosk.getId(),
                preview.getReturned().getProductId(),
                preview.getReturned().getColorId(),
                preview.getReturned().getQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact(),
                preview.getOriginalSaleId(),
                true,
                user.getId(),
                preview.getReturned().getSize(),
                slipNumber
        );

        KioskPosSaleRequest saleRequest = KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .customerTaxId(request.getCustomerTaxId())
                .customerName(request.getCustomerName())
                .address(request.getAddress())
                .phone(request.getPhone())
                .email(request.getEmail())
                .paymentMethod(request.getPaymentMethod())
                .amountReceived(request.getAmountReceived())
                .cashAmount(request.getCashAmount())
                .cardAmount(request.getCardAmount())
                .cardAuthNumber(request.getCardAuthNumber())
                .cardLast4(request.getCardLast4())
                .notes(joinNotes(request.getNotes(), request.getReason()))
                .comments(request.getComments())
                .requestInvoice(request.getRequestInvoice())
                .exchangeCreditAmount(preview.getReturnedAmount())
                .items(List.of(KioskPosSaleRequest.ItemRequest.builder()
                        .productId(preview.getGiven().getProductId())
                        .colorId(preview.getGiven().getColorId())
                        .size(preview.getGiven().getSize())
                        .quantity(preview.getGiven().getQuantity())
                        .build()))
                .build();

        KioskPosSaleResponse sale = kioskPosService.createExchangeSale(saleRequest, slipNumber);

        KioskExchangeSlipEntity slip = buildExchangeSlipEntity(
                slipNumber,
                preview,
                request,
                kiosk.getId(),
                user.getId(),
                sale.getId(),
                STATUS_COMPLETED,
                GuatemalaDateTime.now()
        );
        linkExchangeMovementIds(slip, slipNumber);
        slip = exchangeSlipRepository.save(slip);

        return KioskExchangeCompleteResponse.builder()
                .slip(toSlipResponse(slip, exchange.access()))
                .sale(sale)
                .build();
    }

    private KioskExchangeCompleteResponse submitZeroDifferenceExchange(
            KioskExchangeCompleteRequest request,
            ExchangeContext exchange,
            KioskExchangePreviewResponse preview,
            String slipNumber
    ) throws BusinessException {
        if (safeTrim(request.getReason()).isEmpty()) {
            throw new BusinessException("Indica el motivo del cambio.");
        }
        UserEntity user = exchange.access().user();
        LocationEntity kiosk = exchange.access().kiosk();

        KioskExchangeSlipEntity slip = buildExchangeSlipEntity(
                slipNumber,
                preview,
                request,
                kiosk.getId(),
                user.getId(),
                null,
                STATUS_PENDING_AUTHORIZATION,
                null
        );
        slip = exchangeSlipRepository.save(slip);

        return KioskExchangeCompleteResponse.builder()
                .slip(toSlipResponse(slip, exchange.access()))
                .sale(null)
                .build();
    }

    private KioskExchangeSlipEntity buildExchangeSlipEntity(
            String slipNumber,
            KioskExchangePreviewResponse preview,
            KioskExchangeCompleteRequest request,
            Long kioskLocationId,
            Long createdBy,
            Long newSaleId,
            String status,
            LocalDateTime completedAt
    ) {
        return KioskExchangeSlipEntity.builder()
                .slipNumber(slipNumber)
                .slipType(SLIP_TYPE_EXCHANGE)
                .kioskLocationId(kioskLocationId)
                .originalSaleId(preview.getOriginalSaleId())
                .originalSaleItemId(preview.getOriginalSaleItemId())
                .returnedProductId(preview.getReturned().getProductId())
                .returnedColorId(preview.getReturned().getColorId())
                .returnedSize(preview.getReturned().getSize())
                .returnedQuantity(preview.getReturned().getQuantity())
                .returnedAmount(preview.getReturnedAmount())
                .givenProductId(preview.getGiven().getProductId())
                .givenColorId(preview.getGiven().getColorId())
                .givenSize(preview.getGiven().getSize())
                .givenQuantity(preview.getGiven().getQuantity())
                .givenAmount(preview.getGivenAmount())
                .differenceAmount(preview.getDifferenceAmount())
                .newSaleId(newSaleId)
                .apto(true)
                .status(status)
                .reason(safeTrim(request.getReason()))
                .observations(safeTrim(request.getObservations()))
                .createdBy(createdBy)
                .completedAt(completedAt)
                .build();
    }

    private void linkExchangeMovementIds(KioskExchangeSlipEntity slip, String slipNumber) {
        List<KioscoMovementEntity> movements = kioscoMovementRepository.findByPhysicalSlipNumber(slipNumber);
        for (KioscoMovementEntity movement : movements) {
            if (movement.getMovementType() == KioscoMovementType.DEVOLUCION_CLIENTE) {
                slip.setReturnMovementId(movement.getId());
            } else if (movement.getMovementType() == KioscoMovementType.VENTA) {
                slip.setGivenMovementId(movement.getId());
            }
        }
    }

    private String buildExchangeMovementReason(KioskExchangeSlipEntity slip) {
        String slipNumber = safeTrim(slip.getSlipNumber());
        String reason = safeTrim(slip.getReason());
        if (reason.isBlank()) {
            return "Boleta de cambio " + slipNumber;
        }
        return "Boleta de cambio " + slipNumber + " · " + reason;
    }

    @Transactional
    public KioskExchangeSlipResponse completeSimpleReturn(KioskSimpleReturnRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null) {
            throw new BusinessException("Debes indicar los datos de la devolución.");
        }
        AccessContext ctx = resolveAccessContext(request.getKioskLocationId());
        KioskSaleEntity sale = kioskSaleRepository.findById(request.getOriginalSaleId())
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", request.getOriginalSaleId()));
        if (!Objects.equals(sale.getKioskLocationId(), ctx.kiosk().getId())) {
            throw new BusinessException("La venta no pertenece al kiosko seleccionado.");
        }
        validateOriginalSale(sale);
        KioskSaleItemEntity item = kioskSaleItemRepository.findByIdAndKioskSale_Id(
                        request.getOriginalSaleItemId(), sale.getId())
                .orElseThrow(() -> new BusinessException("La línea seleccionada no pertenece a la venta original."));

        BigDecimal returnedQty = normalizeQuantity(request.getReturnedQuantity(), item.getQuantity());
        BigDecimal returnedAmount = item.getUnitPrice().multiply(returnedQty).setScale(2, RoundingMode.HALF_UP);
        String returnedSize = extractSizeFromProductName(item.getProductName());
        String slipNumber = requireAvailablePhysicalSlipNumber(request.getPhysicalSlipNumber());

        kioscoInventoryService.registrarDevolucionCliente(
                ctx.kiosk().getId(),
                item.getProductId(),
                item.getColorId(),
                returnedQty.setScale(0, RoundingMode.HALF_UP).intValueExact(),
                sale.getId(),
                request.getApto(),
                ctx.user().getId(),
                returnedSize,
                slipNumber
        );

        String status = STATUS_COMPLETED;
        KioskExchangeSlipEntity slip = KioskExchangeSlipEntity.builder()
                .slipNumber(slipNumber)
                .slipType(SLIP_TYPE_RETURN)
                .kioskLocationId(ctx.kiosk().getId())
                .originalSaleId(sale.getId())
                .originalSaleItemId(item.getId())
                .returnedProductId(item.getProductId())
                .returnedColorId(item.getColorId())
                .returnedSize(returnedSize)
                .returnedQuantity(returnedQty)
                .returnedAmount(returnedAmount)
                .differenceAmount(returnedAmount.negate())
                .apto(request.getApto())
                .status(status)
                .reason(safeTrim(request.getReason()))
                .observations(safeTrim(request.getObservations()))
                .createdBy(ctx.user().getId())
                .completedAt(GuatemalaDateTime.now())
                .build();
        slip = exchangeSlipRepository.save(slip);
        return toSlipResponse(slip, ctx);
    }

    @Transactional
    public KioskExchangeSlipResponse reintegrate(Long slipId, Long kioskLocationId)
            throws BusinessException, ResourceNotFoundException {
        AccessContext ctx = resolveAccessContext(kioskLocationId);
        KioskExchangeSlipEntity slip = findAccessibleSlip(slipId, ctx);
        if (!SLIP_TYPE_RETURN.equalsIgnoreCase(safeTrim(slip.getSlipType()))) {
            throw new BusinessException("Solo se pueden reintegrar devoluciones simples.");
        }
        if (!STATUS_PENDING_REINTEGRO.equalsIgnoreCase(safeTrim(slip.getStatus()))) {
            throw new BusinessException("Esta devolución no está pendiente de reintegro.");
        }
        if (!Boolean.TRUE.equals(slip.getApto())) {
            throw new BusinessException("Solo productos aptos pueden reintegrarse a bodega.");
        }

        kioscoInventoryService.registrarDevolucionDeposito(
                slip.getKioskLocationId(),
                slip.getReturnedProductId(),
                slip.getReturnedColorId(),
                slip.getReturnedQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact(),
                slip.getId(),
                ctx.user().getId(),
                slip.getReturnedSize()
        );

        slip.setStatus("REINTEGRATED");
        slip.setReintegratedAt(GuatemalaDateTime.now());
        slip.setReintegratedBy(ctx.user().getId());
        if (slip.getCompletedAt() == null) {
            slip.setCompletedAt(slip.getReintegratedAt());
        }
        slip = exchangeSlipRepository.save(slip);
        return toSlipResponse(slip, ctx);
    }

    private ExchangeContext buildExchangeContext(KioskExchangePreviewRequest request, boolean requireGiven)
            throws BusinessException, ResourceNotFoundException {
        if (request == null) {
            throw new BusinessException("Debes indicar los datos del cambio.");
        }
        AccessContext access = resolveAccessContext(request.getKioskLocationId());
        KioskSaleEntity sale = kioskSaleRepository.findById(request.getOriginalSaleId())
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", request.getOriginalSaleId()));
        if (!Objects.equals(sale.getKioskLocationId(), access.kiosk().getId())) {
            throw new BusinessException("La venta no pertenece al kiosko seleccionado.");
        }
        validateOriginalSale(sale);
        KioskSaleItemEntity item = kioskSaleItemRepository.findByIdAndKioskSale_Id(
                        request.getOriginalSaleItemId(), sale.getId())
                .orElseThrow(() -> new BusinessException("La línea seleccionada no pertenece a la venta original."));

        BigDecimal returnedQty = normalizeQuantity(request.getReturnedQuantity(), item.getQuantity());
        BigDecimal givenQty = requireGiven
                ? normalizeQuantity(request.getGivenQuantity(), returnedQty)
                : normalizeQuantity(request.getGivenQuantity(), BigDecimal.ONE);
        if (requireGiven && request.getGivenProductId() == null) {
            throw new BusinessException("Debes seleccionar el producto nuevo.");
        }
        if (!requireGiven && request.getGivenProductId() == null) {
            throw new BusinessException("Debes seleccionar el producto nuevo.");
        }

        ProductEntity returnedProduct = productRepository.findById(item.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", item.getProductId()));
        ProductEntity givenProduct = productRepository.findById(request.getGivenProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.getGivenProductId()));
        ColorEntity givenColor = null;
        if (request.getGivenColorId() != null) {
            givenColor = colorRepository.findById(request.getGivenColorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Color", request.getGivenColorId()));
        }
        String givenSize = ProductInventorySizesJson.normalizeKey(request.getGivenSize());
        if (givenSize.isEmpty()) {
            givenSize = null;
        }

        return new ExchangeContext(
                access,
                sale,
                item,
                returnedProduct,
                returnedQty,
                givenProduct,
                givenColor,
                givenSize,
                givenQty
        );
    }

    private KioskSaleEntity findSaleByQuery(Long kioskLocationId, String query) throws ResourceNotFoundException {
        if (query.matches("\\d+")) {
            Long saleId = Long.parseLong(query);
            return kioskSaleRepository.findById(saleId)
                    .filter(sale -> Objects.equals(sale.getKioskLocationId(), kioskLocationId))
                    .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        }
        return kioskSaleRepository.findByKioskLocationIdAndSaleNumberIgnoreCase(kioskLocationId, query)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", query));
    }

    private void validateOriginalSale(KioskSaleEntity sale) throws BusinessException {
        if (sale == null) {
            throw new BusinessException("Venta no encontrada.");
        }
        if ("VOID".equalsIgnoreCase(safeTrim(sale.getStatus()))) {
            throw new BusinessException("La venta está anulada.");
        }
        if (!"COMPLETED".equalsIgnoreCase(safeTrim(sale.getStatus()))) {
            throw new BusinessException("Solo se pueden usar ventas completadas.");
        }
    }

    private BigDecimal normalizeQuantity(BigDecimal requested, BigDecimal fallback) throws BusinessException {
        BigDecimal qty = requested != null ? requested : fallback;
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("La cantidad debe ser mayor a cero.");
        }
        if (fallback != null && qty.compareTo(fallback) > 0) {
            throw new BusinessException("La cantidad no puede superar la vendida originalmente.");
        }
        return qty.setScale(3, RoundingMode.HALF_UP);
    }

    private String requireAvailablePhysicalSlipNumber(String raw) throws BusinessException {
        String normalized = safeTrim(raw);
        if (normalized.isEmpty()) {
            throw new BusinessException("Debes indicar el número de boleta física.");
        }
        if (normalized.length() > 60) {
            throw new BusinessException("El número de boleta física no puede superar 60 caracteres.");
        }
        if (exchangeSlipRepository.existsBySlipNumber(normalized)) {
            throw new BusinessException("El número de boleta física ya fue registrado.");
        }
        if (kioscoMovementRepository.existsByPhysicalSlipNumber(normalized)) {
            throw new BusinessException("El número de boleta física ya fue registrado en inventario kiosko.");
        }
        return normalized;
    }

    private KioskExchangeSlipResponse toSlipResponse(KioskExchangeSlipEntity slip, AccessContext ctx) {
        LocationEntity kiosk = ctx.availableKiosks().stream()
                .filter(item -> Objects.equals(item.getId(), slip.getKioskLocationId()))
                .findFirst()
                .orElse(null);
        KioskSaleEntity originalSale = slip.getOriginalSaleId() != null
                ? kioskSaleRepository.findById(slip.getOriginalSaleId()).orElse(null)
                : null;
        KioskSaleEntity newSale = slip.getNewSaleId() != null
                ? kioskSaleRepository.findById(slip.getNewSaleId()).orElse(null)
                : null;
        ProductEntity returnedProduct = slip.getReturnedProductId() != null
                ? productRepository.findById(slip.getReturnedProductId()).orElse(null)
                : null;
        ProductEntity givenProduct = slip.getGivenProductId() != null
                ? productRepository.findById(slip.getGivenProductId()).orElse(null)
                : null;
        ColorEntity returnedColor = slip.getReturnedColorId() != null
                ? colorRepository.findById(slip.getReturnedColorId()).orElse(null)
                : null;
        ColorEntity givenColor = slip.getGivenColorId() != null
                ? colorRepository.findById(slip.getGivenColorId()).orElse(null)
                : null;
        UserEntity createdBy = slip.getCreatedBy() != null ? userRepository.findById(slip.getCreatedBy()).orElse(null) : null;
        UserEntity reintegratedBy = slip.getReintegratedBy() != null
                ? userRepository.findById(slip.getReintegratedBy()).orElse(null)
                : null;
        UserEntity authorizedBy = slip.getAuthorizedBy() != null
                ? userRepository.findById(slip.getAuthorizedBy()).orElse(null)
                : null;

        return KioskExchangeSlipResponse.builder()
                .id(slip.getId())
                .slipNumber(slip.getSlipNumber())
                .slipType(slip.getSlipType())
                .kioskLocationId(slip.getKioskLocationId())
                .kioskCode(kiosk != null ? kiosk.getCode() : null)
                .kioskName(kiosk != null ? kiosk.getName() : null)
                .originalSaleId(slip.getOriginalSaleId())
                .originalSaleNumber(originalSale != null ? originalSale.getSaleNumber() : null)
                .originalSaleItemId(slip.getOriginalSaleItemId())
                .returnedProductId(slip.getReturnedProductId())
                .returnedProductCode(returnedProduct != null ? returnedProduct.getCode() : null)
                .returnedProductName(returnedProduct != null ? returnedProduct.getName() : null)
                .returnedColorId(slip.getReturnedColorId())
                .returnedColorName(returnedColor != null ? returnedColor.getName() : null)
                .returnedSize(slip.getReturnedSize())
                .returnedQuantity(slip.getReturnedQuantity())
                .returnedAmount(slip.getReturnedAmount())
                .givenProductId(slip.getGivenProductId())
                .givenProductCode(givenProduct != null ? givenProduct.getCode() : null)
                .givenProductName(givenProduct != null ? givenProduct.getName() : null)
                .givenColorId(slip.getGivenColorId())
                .givenColorName(givenColor != null ? givenColor.getName() : null)
                .givenSize(slip.getGivenSize())
                .givenQuantity(slip.getGivenQuantity())
                .givenAmount(slip.getGivenAmount())
                .differenceAmount(slip.getDifferenceAmount())
                .newSaleId(slip.getNewSaleId())
                .newSaleNumber(newSale != null ? newSale.getSaleNumber() : null)
                .apto(slip.getApto())
                .status(slip.getStatus())
                .reason(slip.getReason())
                .observations(slip.getObservations())
                .createdByUserId(slip.getCreatedBy())
                .createdByName(formatUserName(createdBy))
                .createdAt(slip.getCreatedAt())
                .completedAt(slip.getCompletedAt())
                .reintegratedAt(slip.getReintegratedAt())
                .reintegratedByUserId(slip.getReintegratedBy())
                .reintegratedByName(formatUserName(reintegratedBy))
                .authorizedByUserId(slip.getAuthorizedBy())
                .authorizedByName(formatUserName(authorizedBy))
                .authorizedAt(slip.getAuthorizedAt())
                .rejectionReason(slip.getRejectionReason())
                .returnMovementId(slip.getReturnMovementId())
                .givenMovementId(slip.getGivenMovementId())
                .build();
    }

    private KioskExchangeSlipEntity findAccessibleSlip(Long id, AccessContext ctx)
            throws ResourceNotFoundException, BusinessException {
        KioskExchangeSlipEntity slip = exchangeSlipRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioskExchangeSlip", id));
        if (ctx.kiosk() != null && !Objects.equals(slip.getKioskLocationId(), ctx.kiosk().getId())) {
            throw new BusinessException("No tienes acceso a esta boleta.");
        }
        if (ctx.kiosk() == null && ctx.availableKiosks().stream()
                .noneMatch(k -> Objects.equals(k.getId(), slip.getKioskLocationId()))) {
            throw new BusinessException("No tienes acceso a esta boleta.");
        }
        return slip;
    }

    private AccessContext resolveAccessContext(Long kioskLocationId) throws BusinessException {
        return resolveAccessContext(kioskLocationId, false);
    }

    private AccessContext resolveAccessContext(Long kioskLocationId, boolean allowNullKiosk) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk;
        if (kioskLocationId == null && allowNullKiosk) {
            kiosk = null;
        } else {
            kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        }
        return new AccessContext(user, availableKiosks, kiosk);
    }

    private UserEntity getCurrentUserOrThrow() throws BusinessException {
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException("No se encontró el usuario autenticado."));
    }

    private List<LocationEntity> resolveAvailableKiosks(UserEntity user, boolean admin) throws BusinessException {
        List<LocationEntity> kiosks;
        if (admin) {
            kiosks = locationRepository.findAll().stream()
                    .filter(this::isKioskLocation)
                    .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
            if (kiosks.isEmpty()) {
                kiosks = locationRepository.findAll().stream()
                        .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                        .collect(Collectors.toList());
            }
        } else {
            kiosks = locationRepository.findByEncargadoIdOrderByNameAsc(user.getId()).stream()
                    .filter(this::isKioskLocation)
                    .collect(Collectors.toList());
            if (kiosks.isEmpty()) {
                kiosks = locationRepository.findByEncargadoIdOrderByNameAsc(user.getId());
            }
        }
        if (kiosks == null || kiosks.isEmpty()) {
            throw new BusinessException("Tu usuario no tiene kiosko asignado. Configura el encargado del kiosko.");
        }
        return kiosks;
    }

    private LocationEntity resolveTargetKiosk(List<LocationEntity> availableKiosks, Long kioskLocationId)
            throws BusinessException {
        if (availableKiosks == null || availableKiosks.isEmpty()) {
            throw new BusinessException("No hay kioskos disponibles para este usuario.");
        }
        if (kioskLocationId == null) {
            return availableKiosks.get(0);
        }
        return availableKiosks.stream()
                .filter(item -> Objects.equals(item.getId(), kioskLocationId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("No tienes acceso al kiosko seleccionado."));
    }

    private boolean isKioskLocation(LocationEntity location) {
        String categoria = normalizeText(location != null ? location.getCategoria() : null);
        String name = normalizeText(location != null ? location.getName() : null);
        String code = normalizeText(location != null ? location.getCode() : null);
        return categoria.contains("KIOS") || name.contains("KIOS") || code.startsWith("K");
    }

    private String normalizeText(String value) {
        return safeTrim(value)
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }

    private static BigDecimal resolveCatalogUnitPrice(ProductEntity product) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        if (product.getSalePrice() != null && product.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
            return product.getSalePrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (product.getDiscountedPrice() != null && product.getDiscountedPrice().compareTo(BigDecimal.ZERO) > 0) {
            return product.getDiscountedPrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (product.getSellerPrice() != null && product.getSellerPrice().compareTo(BigDecimal.ZERO) > 0) {
            return product.getSellerPrice().setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private static String extractSizeFromProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            return null;
        }
        int idx = productName.lastIndexOf(" T.");
        if (idx < 0) {
            return null;
        }
        String size = productName.substring(idx + 3).trim();
        return size.isBlank() ? null : size;
    }

    private static String stripSizeFromProductName(String productName) {
        if (productName == null) {
            return null;
        }
        int idx = productName.lastIndexOf(" T.");
        if (idx < 0) {
            return productName;
        }
        return productName.substring(0, idx).trim();
    }

    private static String joinNotes(String notes, String reason) {
        String left = safeTrim(notes);
        String right = safeTrim(reason);
        if (left.isBlank()) {
            return right.isBlank() ? null : right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + " | " + right;
    }

    private static String formatUserName(UserEntity user) {
        if (user == null) {
            return null;
        }
        String first = safeTrim(user.getFirstName());
        String last = safeTrim(user.getLastName());
        String full = (first + " " + last).trim();
        if (!full.isBlank()) {
            return full;
        }
        return safeTrim(user.getUsername());
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private record AccessContext(UserEntity user, List<LocationEntity> availableKiosks, LocationEntity kiosk) {
    }

    private record ExchangeContext(
            AccessContext access,
            KioskSaleEntity sale,
            KioskSaleItemEntity item,
            ProductEntity returnedProduct,
            BigDecimal returnedQty,
            ProductEntity givenProduct,
            ColorEntity givenColor,
            String givenSize,
            BigDecimal givenQty
    ) {
        KioskExchangePreviewResponse preview() throws BusinessException {
            return buildPreview();
        }

        private KioskExchangePreviewResponse buildPreview() throws BusinessException {
            BigDecimal returnedUnitPaid = computeEffectivePaidUnitPrice(sale, item);
            BigDecimal returnedAmount = returnedUnitPaid.multiply(returnedQty).setScale(2, RoundingMode.HALF_UP);

            BigDecimal givenUnitPrice;
            if (shouldPreservePaidPriceOnExchange(item, returnedProduct, givenProduct)) {
                givenUnitPrice = returnedUnitPaid;
            } else {
                givenUnitPrice = resolveCatalogUnitPrice(givenProduct);
                if (givenUnitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("El producto nuevo no tiene precio de catálogo configurado.");
                }
            }
            BigDecimal givenAmount = givenUnitPrice.multiply(givenQty).setScale(2, RoundingMode.HALF_UP);
            BigDecimal difference = givenAmount.subtract(returnedAmount).setScale(2, RoundingMode.HALF_UP);

            String returnedSize = extractSizeFromProductName(item.getProductName());
            KioskExchangePreviewResponse.ProductLine returnedLine = KioskExchangePreviewResponse.ProductLine.builder()
                    .productId(item.getProductId())
                    .productCode(item.getProductCode())
                    .productName(stripSizeFromProductName(item.getProductName()))
                    .colorId(item.getColorId())
                    .colorName(item.getColorName())
                    .size(returnedSize)
                    .quantity(returnedQty)
                    .unitPrice(returnedUnitPaid)
                    .lineTotal(returnedAmount)
                    .build();

            KioskExchangePreviewResponse.ProductLine givenLine = KioskExchangePreviewResponse.ProductLine.builder()
                    .productId(givenProduct.getId())
                    .productCode(givenProduct.getCode())
                    .productName(givenProduct.getName())
                    .colorId(givenColor != null ? givenColor.getId() : null)
                    .colorName(givenColor != null ? givenColor.getName() : null)
                    .size(givenSize)
                    .quantity(givenQty)
                    .unitPrice(givenUnitPrice)
                    .lineTotal(givenAmount)
                    .build();

            return KioskExchangePreviewResponse.builder()
                    .originalSaleId(sale.getId())
                    .originalSaleNumber(sale.getSaleNumber())
                    .originalSaleDate(sale.getSaleDate())
                    .originalSaleItemId(item.getId())
                    .returned(returnedLine)
                    .given(givenLine)
                    .returnedAmount(returnedAmount)
                    .givenAmount(givenAmount)
                    .differenceAmount(difference)
                    .build();
        }
    }

    /**
     * Precio unitario realmente pagado en la venta original (reparte descuento de la venta).
     */
    static BigDecimal computeEffectivePaidUnitPrice(KioskSaleEntity sale, KioskSaleItemEntity item) {
        if (item == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal qty = item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? item.getQuantity()
                : BigDecimal.ONE;
        BigDecimal lineSubtotal = item.getLineTotal() != null
                ? item.getLineTotal()
                : safeAmount(item.getUnitPrice()).multiply(qty);
        BigDecimal saleSubtotal = sale != null && sale.getSubtotal() != null
                && sale.getSubtotal().compareTo(BigDecimal.ZERO) > 0
                ? sale.getSubtotal()
                : lineSubtotal;
        BigDecimal discount = sale != null && sale.getDiscountAmount() != null
                ? sale.getDiscountAmount()
                : BigDecimal.ZERO;
        if (saleSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return safeAmount(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal lineDiscountShare = discount.multiply(lineSubtotal)
                .divide(saleSubtotal, 6, RoundingMode.HALF_UP);
        BigDecimal linePaid = lineSubtotal.subtract(lineDiscountShare).max(BigDecimal.ZERO);
        return linePaid.divide(qty, 2, RoundingMode.HALF_UP);
    }

    /**
     * Mismo producto (fallo/defecto) o cambio de cincho por talla: conservar precio pagado, sin diferencia por descuento.
     */
    static boolean shouldPreservePaidPriceOnExchange(
            KioskSaleItemEntity item,
            ProductEntity returnedProduct,
            ProductEntity givenProduct
    ) {
        if (item == null || givenProduct == null) {
            return false;
        }
        if (Objects.equals(item.getProductId(), givenProduct.getId())) {
            return true;
        }
        if (!CinchoProductUtils.isFossCinchoProduct(returnedProduct)
                || !CinchoProductUtils.isFossCinchoProduct(givenProduct)) {
            return false;
        }
        String returnedBase = normalizeCinchoBaseName(returnedProduct, item.getProductName());
        String givenBase = normalizeCinchoBaseName(givenProduct, givenProduct.getName());
        return !returnedBase.isBlank() && returnedBase.equals(givenBase);
    }

    private static String normalizeCinchoBaseName(ProductEntity product, String fallbackName) {
        String name = stripSizeFromProductName(fallbackName != null ? fallbackName : product.getName());
        if (name != null && !name.isBlank()) {
            return safeTrim(name).toUpperCase(Locale.ROOT);
        }
        return safeTrim(product != null ? product.getCode() : null).toUpperCase(Locale.ROOT);
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
