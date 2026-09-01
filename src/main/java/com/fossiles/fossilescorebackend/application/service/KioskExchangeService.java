package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeRejectRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeCompleteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeGivenItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangePreviewRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSimpleReturnRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeCompleteResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangePreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeSlipResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.CinchoSizePricing;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipGivenItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipGivenItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.application.util.ProductHardwareCondition;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KioskExchangeService {

    private static final String SLIP_TYPE_EXCHANGE = "EXCHANGE";
    private static final String SLIP_TYPE_RETURN = "RETURN";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PENDING_REINTEGRO = "PENDING_REINTEGRO";
    private static final String STATUS_REINTEGRATED = "REINTEGRATED";
    private static final String STATUS_PENDING_AUTHORIZATION = "PENDING_AUTHORIZATION";
    private static final String STATUS_REJECTED = "REJECTED";
    /** Solo Miraflores puede editar precios unitarios del cambio para empatar cobro POS. */
    private static final String EXCHANGE_PRICE_EDIT_KIOSK_CODE = "A15";

    private final KioskExchangeSlipRepository exchangeSlipRepository;
    private final KioskExchangeSlipGivenItemRepository exchangeSlipGivenItemRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final KioscoPhysicalCountRepository physicalCountRepository;
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

    @Transactional(rollbackFor = Exception.class)
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

        int returnedQty = slip.getReturnedQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact();
        String cambioReason = buildExchangeMovementReason(slip);

        List<KioscoInventoryService.CambioGivenLine> givenLines = resolveGivenLinesForStock(slip);
        KioscoInventoryService.CambioResult cambio = kioscoInventoryService.registrarCambioMulti(
                slip.getKioskLocationId(),
                slip.getReturnedProductId(),
                slip.getReturnedColorId(),
                returnedQty,
                slip.getReturnedSize(),
                null,
                givenLines,
                slip.getId(),
                cambioReason,
                ctx.user().getId(),
                slip.getSlipNumber()
        );

        slip.setReturnMovementId(cambio.getReturnedMovementId());
        slip.setGivenMovementId(cambio.getGivenMovementId());
        slip.setStatus(STATUS_COMPLETED);
        slip.setAuthorizedBy(ctx.user().getId());
        slip.setAuthorizedAt(GuatemalaDateTime.now());
        slip.setCompletedAt(slip.getAuthorizedAt());
        slip = exchangeSlipRepository.save(slip);
        linkGivenItemMovements(slip.getId(), cambio.getGivenMovementIds());
        return toSlipResponse(slip, ctx);
    }

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
    public KioskExchangeCompleteResponse completeExchange(KioskExchangeCompleteRequest request)
            throws BusinessException, ResourceNotFoundException {
        ExchangeContext exchange = buildExchangeContext(request, true);
        KioskExchangePreviewResponse preview = exchange.preview();
        LocationEntity kioskForSlip = exchange.access().kiosk();
        String slipNumber = requireAvailablePhysicalSlipNumber(request.getPhysicalSlipNumber(), kioskForSlip);

        if (preview.getDifferenceAmount() == null
                || preview.getDifferenceAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return submitZeroDifferenceExchange(request, exchange, preview, slipNumber);
        }

        UserEntity user = exchange.access().user();
        LocationEntity kiosk = exchange.access().kiosk();

        List<KioskPosSaleRequest.ItemRequest> saleItems = previewGivenLines(preview).stream()
                .map(line -> KioskPosSaleRequest.ItemRequest.builder()
                        .productId(line.getProductId())
                        .colorId(line.getColorId())
                        .size(line.getSize())
                        .hardwareCondition(line.getHardwareCondition())
                        .quantity(line.getQuantity())
                        .unitPrice(line.getUnitPrice())
                        .build())
                .collect(Collectors.toList());

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
                .cardBrand(request.getCardBrand())
                .cardVoucherAmount(request.getCardVoucherAmount())
                .notes(joinNotes(request.getNotes(), request.getReason()))
                .comments(request.getComments())
                .requestInvoice(request.getRequestInvoice())
                .exchangeCreditAmount(preview.getReturnedAmount())
                .items(saleItems)
                .build();

        // Factura/caja de la diferencia sin VENTA de stock; el egreso va como CAMBIO (−).
        KioskPosSaleResponse sale = kioskPosService.createExchangeSale(saleRequest, slipNumber);

        return finalizeExchangeWithStock(
                request,
                exchange,
                preview,
                slipNumber,
                sale.getId(),
                STATUS_COMPLETED,
                GuatemalaDateTime.now()
        );
    }

    /**
     * Sin cobro: diferencia cero o saldo a favor del cliente (sin reembolso).
     * Queda pendiente de autorización; el inventario se mueve al aprobar.
     */
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
                kiosk,
                user.getId(),
                null,
                STATUS_PENDING_AUTHORIZATION,
                null
        );
        slip = exchangeSlipRepository.save(slip);
        saveGivenItemRows(slip.getId(), preview, null);

        return KioskExchangeCompleteResponse.builder()
                .slip(toSlipResponse(slip, exchange.access()))
                .sale(null)
                .build();
    }

    private KioskExchangeCompleteResponse finalizeExchangeWithStock(
            KioskExchangeCompleteRequest request,
            ExchangeContext exchange,
            KioskExchangePreviewResponse preview,
            String slipNumber,
            Long newSaleId,
            String status,
            LocalDateTime completedAt
    ) throws BusinessException, ResourceNotFoundException {
        UserEntity user = exchange.access().user();
        LocationEntity kiosk = exchange.access().kiosk();

        int returnedQty = preview.getReturned().getQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact();
        String cambioReason = "Boleta de cambio " + slipNumber
                + (safeTrim(request.getReason()).isEmpty() ? "" : " · " + safeTrim(request.getReason()));

        KioskExchangeSlipEntity slip = buildExchangeSlipEntity(
                slipNumber,
                preview,
                request,
                kiosk,
                user.getId(),
                newSaleId,
                status,
                completedAt
        );
        slip = exchangeSlipRepository.save(slip);
        saveGivenItemRows(slip.getId(), preview, null);

        List<KioscoInventoryService.CambioGivenLine> givenLines = previewGivenLines(preview).stream()
                .map(line -> KioscoInventoryService.CambioGivenLine.builder()
                        .productId(line.getProductId())
                        .colorId(line.getColorId())
                        .quantity(line.getQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact())
                        .sizeKey(line.getSize())
                        .hardwareCondition(line.getHardwareCondition())
                        .build())
                .collect(Collectors.toList());

        KioscoInventoryService.CambioResult cambio = kioscoInventoryService.registrarCambioMulti(
                kiosk.getId(),
                preview.getReturned().getProductId(),
                preview.getReturned().getColorId(),
                returnedQty,
                preview.getReturned().getSize(),
                null,
                givenLines,
                slip.getId(),
                cambioReason,
                user.getId(),
                slipNumber
        );
        slip.setReturnMovementId(cambio.getReturnedMovementId());
        slip.setGivenMovementId(cambio.getGivenMovementId());
        slip = exchangeSlipRepository.save(slip);
        linkGivenItemMovements(slip.getId(), cambio.getGivenMovementIds());

        return KioskExchangeCompleteResponse.builder()
                .slip(toSlipResponse(slip, exchange.access()))
                .sale(newSaleId != null ? kioskPosService.getSaleById(newSaleId, kiosk.getId()) : null)
                .build();
    }

    private KioskExchangeSlipEntity buildExchangeSlipEntity(
            String slipNumber,
            KioskExchangePreviewResponse preview,
            KioskExchangeCompleteRequest request,
            LocationEntity kiosk,
            Long createdBy,
            Long newSaleId,
            String status,
            LocalDateTime completedAt
    ) {
        KioskExchangePreviewResponse.ProductLine primaryGiven = previewGivenLines(preview).get(0);
        BigDecimal totalGivenQty = previewGivenLines(preview).stream()
                .map(KioskExchangePreviewResponse.ProductLine::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return KioskExchangeSlipEntity.builder()
                .slipNumber(slipNumber)
                .seriesCode(resolveSeriesCode(kiosk))
                .slipType(SLIP_TYPE_EXCHANGE)
                .kioskLocationId(kiosk.getId())
                .originalSaleId(preview.getOriginalSaleId())
                .originalSaleItemId(preview.getOriginalSaleItemId())
                .returnedProductId(preview.getReturned().getProductId())
                .returnedColorId(preview.getReturned().getColorId())
                .returnedSize(preview.getReturned().getSize())
                .returnedQuantity(preview.getReturned().getQuantity())
                .returnedAmount(preview.getReturnedAmount())
                .givenProductId(primaryGiven.getProductId())
                .givenColorId(primaryGiven.getColorId())
                .givenSize(primaryGiven.getSize())
                .givenHardwareCondition(primaryGiven.getHardwareCondition())
                .givenQuantity(totalGivenQty)
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

    private void linkExchangeMovementIds(KioskExchangeSlipEntity slip, String slipNumber, String seriesCode) {
        List<Long> locationIds = resolveLocationIdsForSeries(seriesCode, slip.getKioskLocationId());
        List<KioscoMovementEntity> movements = kioscoMovementRepository
                .findByPhysicalSlipNumberAndKioscoStock_LocationIdIn(slipNumber, locationIds);
        for (KioscoMovementEntity movement : movements) {
            if (movement.getMovementType() == KioscoMovementType.CAMBIO) {
                boolean egress = movement.getStockAfter() != null
                        && movement.getStockBefore() != null
                        && movement.getStockAfter() < movement.getStockBefore();
                if (egress) {
                    slip.setGivenMovementId(movement.getId());
                } else {
                    slip.setReturnMovementId(movement.getId());
                }
            } else if (movement.getMovementType() == KioscoMovementType.DEVOLUCION_CLIENTE) {
                slip.setReturnMovementId(movement.getId());
            } else if (movement.getMovementType() == KioscoMovementType.DEVOLUCION_A_CLIENTE
                    || movement.getMovementType() == KioscoMovementType.VENTA) {
                // Legado: egresos de cambio previos a tipificar ambos como CAMBIO.
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

    @Transactional(rollbackFor = Exception.class)
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
        String slipNumber = requireAvailablePhysicalSlipNumber(request.getPhysicalSlipNumber(), ctx.kiosk());
        Long physicalCountId = resolvePhysicalCountIdForReturns(request.getPhysicalCountId(), ctx.kiosk().getId());

        kioscoInventoryService.registrarDevolucionCliente(
                ctx.kiosk().getId(),
                item.getProductId(),
                item.getColorId(),
                returnedQty.setScale(0, RoundingMode.HALF_UP).intValueExact(),
                sale.getId(),
                request.getApto(),
                ctx.user().getId(),
                returnedSize,
                slipNumber,
                physicalCountId
        );

        String status = Boolean.TRUE.equals(request.getApto()) ? STATUS_PENDING_REINTEGRO : STATUS_COMPLETED;
        String seriesCode = resolveSeriesCode(ctx.kiosk());
        KioskExchangeSlipEntity slip = KioskExchangeSlipEntity.builder()
                .slipNumber(slipNumber)
                .seriesCode(seriesCode)
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
                .physicalCountId(physicalCountId)
                .build();
        slip = exchangeSlipRepository.save(slip);
        linkExchangeMovementIds(slip, slipNumber, seriesCode);
        slip = exchangeSlipRepository.save(slip);
        return toSlipResponse(slip, ctx);
    }

    @Transactional(rollbackFor = Exception.class)
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

        KioscoInventoryService.KioscoMovementWithStock movementResult =
                kioscoInventoryService.registrarDevolucionDepositoWithMovement(
                slip.getKioskLocationId(),
                slip.getReturnedProductId(),
                slip.getReturnedColorId(),
                slip.getReturnedQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact(),
                slip.getId(),
                ctx.user().getId(),
                slip.getReturnedSize(),
                slip.getSlipNumber(),
                slip.getReason(),
                slip.getPhysicalCountId()
        );

        slip.setReintegroMovementId(movementResult.movement().getId());
        slip.setStatus(STATUS_REINTEGRATED);
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
        boolean hasOriginalSale = request.getOriginalSaleId() != null || request.getOriginalSaleItemId() != null;
        if (hasOriginalSale && (request.getOriginalSaleId() == null || request.getOriginalSaleItemId() == null)) {
            throw new BusinessException("Indica la venta y la línea original, o registra el cambio libre.");
        }

        KioskSaleEntity sale = null;
        KioskSaleItemEntity item = null;
        ProductEntity returnedProduct;
        ColorEntity returnedColor = null;
        String returnedSize;
        BigDecimal returnedQty;

        if (hasOriginalSale) {
            sale = kioskSaleRepository.findById(request.getOriginalSaleId())
                    .orElseThrow(() -> new ResourceNotFoundException("KioskSale", request.getOriginalSaleId()));
            if (!Objects.equals(sale.getKioskLocationId(), access.kiosk().getId())) {
                throw new BusinessException("La venta no pertenece al kiosko seleccionado.");
            }
            validateOriginalSale(sale);
            item = kioskSaleItemRepository.findByIdAndKioskSale_Id(
                            request.getOriginalSaleItemId(), sale.getId())
                    .orElseThrow(() -> new BusinessException("La línea seleccionada no pertenece a la venta original."));
            Long returnedProductId = item.getProductId();
            returnedProduct = productRepository.findById(returnedProductId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", returnedProductId));
            assertExchangeableProduct(returnedProduct, "devolver");
            returnedQty = normalizeQuantity(request.getReturnedQuantity(), item.getQuantity());
            returnedSize = extractSizeFromProductName(item.getProductName());
        } else {
            if (request.getReturnedProductId() == null) {
                throw new BusinessException("Debes seleccionar el producto que ingresa al kiosko.");
            }
            returnedProduct = productRepository.findById(request.getReturnedProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", request.getReturnedProductId()));
            assertExchangeableProduct(returnedProduct, "ingresar");
            if (request.getReturnedColorId() != null) {
                returnedColor = colorRepository.findById(request.getReturnedColorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Color", request.getReturnedColorId()));
            }
            returnedSize = ProductInventorySizesJson.normalizeKey(request.getReturnedSize());
            returnedSize = returnedSize.isEmpty() ? null : returnedSize;
            returnedQty = normalizeQuantity(request.getReturnedQuantity(), BigDecimal.ONE);
        }

        BigDecimal givenQty = requireGiven
                ? normalizeQuantity(request.getGivenQuantity(), returnedQty)
                : normalizeQuantity(request.getGivenQuantity(), BigDecimal.ONE);

        boolean allowPriceOverride = allowsExchangePriceEdit(access.kiosk());
        BigDecimal returnedUnitOverride = resolveReturnedUnitPriceOverride(
                request, returnedProduct, returnedSize, allowPriceOverride);

        List<ResolvedGivenLine> givenLines = resolveGivenLines(request, returnedQty, requireGiven, allowPriceOverride);

        List<KioskSaleItemEntity> saleItems = loadSaleItems(sale);
        PackagingAllocation packaging = allocatePackagingCredit(saleItems, item, returnedQty);

        return new ExchangeContext(
                access,
                sale,
                item,
                saleItems,
                returnedProduct,
                returnedColor,
                returnedSize,
                returnedQty,
                givenLines,
                returnedUnitOverride,
                packaging
        );
    }

    private List<ResolvedGivenLine> resolveGivenLines(
            KioskExchangePreviewRequest request,
            BigDecimal returnedQty,
            boolean requireGiven,
            boolean allowPriceOverride
    ) throws BusinessException, ResourceNotFoundException {
        List<KioskExchangeGivenItemRequest> rawItems = request.getGivenItems();
        List<ResolvedGivenLine> resolved = new ArrayList<>();

        if (rawItems != null && !rawItems.isEmpty()) {
            for (KioskExchangeGivenItemRequest raw : rawItems) {
                if (raw == null || raw.getProductId() == null) {
                    throw new BusinessException("Cada producto entregado debe indicar productId.");
                }
                ProductEntity givenProduct = productRepository.findById(raw.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product", raw.getProductId()));
                assertExchangeableProduct(givenProduct, "entregar");
                ColorEntity givenColor = null;
                if (raw.getColorId() != null) {
                    givenColor = colorRepository.findById(raw.getColorId())
                            .orElseThrow(() -> new ResourceNotFoundException("Color", raw.getColorId()));
                }
                String givenSize = ProductInventorySizesJson.normalizeKey(raw.getSize());
                if (givenSize.isEmpty()) {
                    givenSize = null;
                }
                String givenHardware = ProductHardwareCondition.normalize(raw.getHardwareCondition());
                BigDecimal qty = normalizeQuantity(raw.getQuantity(), returnedQty);
                BigDecimal unitOverride = normalizePriceOverride(raw.getUnitPrice());
                if (unitOverride != null && !allowPriceOverride) {
                    throw new BusinessException(
                            "Solo el kiosko Miraflores (A15) puede editar precios del cambio.");
                }
                resolved.add(new ResolvedGivenLine(
                        givenProduct, givenColor, givenSize, givenHardware, qty, unitOverride));
            }
            return resolved;
        }

        if (request.getGivenProductId() == null) {
            if (requireGiven) {
                throw new BusinessException("Debes seleccionar el producto nuevo.");
            }
            throw new BusinessException("Debes seleccionar el producto nuevo.");
        }

        ProductEntity givenProduct = productRepository.findById(request.getGivenProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.getGivenProductId()));
        assertExchangeableProduct(givenProduct, "entregar");
        ColorEntity givenColor = null;
        if (request.getGivenColorId() != null) {
            givenColor = colorRepository.findById(request.getGivenColorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Color", request.getGivenColorId()));
        }
        String givenSize = ProductInventorySizesJson.normalizeKey(request.getGivenSize());
        if (givenSize.isEmpty()) {
            givenSize = null;
        }
        String givenHardware = ProductHardwareCondition.normalize(request.getGivenHardwareCondition());
        BigDecimal givenQty = requireGiven
                ? normalizeQuantity(request.getGivenQuantity(), returnedQty)
                : normalizeQuantity(request.getGivenQuantity(), BigDecimal.ONE);
        BigDecimal givenUnitOverride = normalizePriceOverride(request.getGivenUnitPrice());
        if (givenUnitOverride != null && !allowPriceOverride) {
            throw new BusinessException(
                    "Solo el kiosko Miraflores (A15) puede editar precios del cambio.");
        }
        resolved.add(new ResolvedGivenLine(
                givenProduct, givenColor, givenSize, givenHardware, givenQty, givenUnitOverride));
        return resolved;
    }

    private List<KioskSaleItemEntity> loadSaleItems(KioskSaleEntity sale) {
        if (sale == null || sale.getId() == null) {
            return List.of();
        }
        return kioskSaleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());
    }

    /**
     * Empaques SUM de la venta original: crédito potencial (precio de factura, sin descuento).
     * Se aplica a la liquidación solo si hay diferencia de precio entre productos.
     * No van en el egreso ni en movimiento de stock.
     */
    private PackagingAllocation allocatePackagingCredit(
            List<KioskSaleItemEntity> saleItems,
            KioskSaleItemEntity exchangedItem,
            BigDecimal returnedQty
    ) {
        if (exchangedItem == null || saleItems == null || saleItems.isEmpty()) {
            return PackagingAllocation.empty();
        }
        List<KioskSaleItemEntity> packagingItems = saleItems.stream()
                .filter(i -> ProductCinchoType.isPackagingProductCode(i.getProductCode()))
                .toList();
        if (packagingItems.isEmpty()) {
            return PackagingAllocation.empty();
        }

        BigDecimal productQtyTotal = saleItems.stream()
                .filter(i -> !ProductCinchoType.isPackagingProductCode(i.getProductCode()))
                .map(i -> i.getQuantity() != null && i.getQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? i.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (productQtyTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return PackagingAllocation.empty();
        }

        BigDecimal exchangedQty = exchangedItem.getQuantity() != null
                && exchangedItem.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? exchangedItem.getQuantity()
                : BigDecimal.ONE;
        BigDecimal returnRatio = returnedQty.divide(exchangedQty, 6, RoundingMode.HALF_UP);
        BigDecimal lineShare = exchangedQty.divide(productQtyTotal, 6, RoundingMode.HALF_UP);

        BigDecimal packagingInvoiceTotal = BigDecimal.ZERO;
        for (KioskSaleItemEntity packItem : packagingItems) {
            packagingInvoiceTotal = packagingInvoiceTotal.add(packagingLineAmountNoDiscount(packItem));
        }
        BigDecimal returnedCredit = packagingInvoiceTotal
                .multiply(lineShare)
                .multiply(returnRatio)
                .setScale(2, RoundingMode.HALF_UP);
        return new PackagingAllocation(returnedCredit);
    }

    /** Precio de empaque según factura original: sin repartir descuento de la venta. */
    static BigDecimal packagingLineAmountNoDiscount(KioskSaleItemEntity item) {
        if (item == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal qty = item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? item.getQuantity()
                : BigDecimal.ONE;
        if (item.getLineTotal() != null && item.getLineTotal().compareTo(BigDecimal.ZERO) > 0) {
            return item.getLineTotal().setScale(2, RoundingMode.HALF_UP);
        }
        return safeAmount(item.getUnitPrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    private record PackagingAllocation(BigDecimal returnedCredit) {
        static PackagingAllocation empty() {
            return new PackagingAllocation(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private static void assertExchangeableProduct(ProductEntity product, String action)
            throws BusinessException {
        if (product != null && ProductCinchoType.isPackagingProductCode(product.getCode())) {
            throw new BusinessException(
                    "Los empaques SUM no se cambian de stock. Solo puedes " + action + " productos.");
        }
    }

    private static boolean allowsExchangePriceEdit(LocationEntity kiosk) {
        if (kiosk == null) {
            return false;
        }
        if (EXCHANGE_PRICE_EDIT_KIOSK_CODE.equalsIgnoreCase(safeTrim(kiosk.getCode()))) {
            return true;
        }
        String name = safeTrim(kiosk.getName()).toUpperCase(Locale.ROOT);
        return name.contains("MIRAFLORES");
    }

    private static BigDecimal normalizePriceOverride(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Precio unitario del producto que ingresa:
     * - returnedUnitPrice manual solo Miraflores (A15);
     * - con toggle de descuento → salePrice × (1 − %/100).
     */
    private static BigDecimal resolveReturnedUnitPriceOverride(
            KioskExchangePreviewRequest request,
            ProductEntity returnedProduct,
            String returnedSize,
            boolean allowPriceOverride
    ) throws BusinessException {
        BigDecimal manual = normalizePriceOverride(request.getReturnedUnitPrice());
        if (manual != null) {
            if (!allowPriceOverride) {
                throw new BusinessException(
                        "Solo el kiosko Miraflores (A15) puede editar precios del cambio.");
            }
            return manual;
        }
        if (request.getReturnedSoldWithDiscount() != null || request.getReturnedDiscountPercent() != null) {
            BigDecimal catalog = resolveFullSaleUnitPrice(returnedProduct, returnedSize);
            if (catalog.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("El producto que ingresa no tiene precio de venta configurado.");
            }
            boolean withDiscount = Boolean.TRUE.equals(request.getReturnedSoldWithDiscount());
            BigDecimal percent = request.getReturnedDiscountPercent() != null
                    ? request.getReturnedDiscountPercent()
                    : BigDecimal.ZERO;
            if (!withDiscount || percent.compareTo(BigDecimal.ZERO) <= 0) {
                return catalog;
            }
            if (percent.compareTo(new BigDecimal("100")) >= 0) {
                throw new BusinessException("El porcentaje de descuento debe ser menor a 100.");
            }
            BigDecimal factor = BigDecimal.ONE.subtract(
                    percent.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            return catalog.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    private KioskSaleEntity findSaleByQuery(Long kioskLocationId, String query) throws ResourceNotFoundException {
        String normalized = normalizeInternalNumberQuery(query);
        if (normalized.matches("\\d+")) {
            Long saleId = Long.parseLong(normalized);
            return kioskSaleRepository.findById(saleId)
                    .filter(sale -> Objects.equals(sale.getKioskLocationId(), kioskLocationId))
                    .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        }
        // Preferir serie-correlativo de establecimiento (A45-241).
        Optional<KioskSaleEntity> byInternal = kioskSaleRepository
                .findByKioskLocationIdAndInvoiceInternalNumber(kioskLocationId, normalized);
        if (byInternal.isPresent()) {
            return byInternal.get();
        }
        // Compatibilidad interna con saleNumber POS-… (no se muestra en UI).
        return kioskSaleRepository.findByKioskLocationIdAndSaleNumberIgnoreCase(kioskLocationId, query.trim())
                .or(() -> kioskSaleRepository.findByKioskLocationIdAndSaleNumberIgnoreCase(kioskLocationId, normalized))
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", query));
    }

    /** Normaliza A45-241 / A45 241 → A45-241 (sin espacios). */
    private static String normalizeInternalNumberQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
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

    private String requireAvailablePhysicalSlipNumber(String raw, LocationEntity kiosk) throws BusinessException {
        String normalized = safeTrim(raw);
        if (normalized.isEmpty()) {
            throw new BusinessException("Debes indicar el número de boleta física.");
        }
        if (normalized.length() > 60) {
            throw new BusinessException("El número de boleta física no puede superar 60 caracteres.");
        }
        String seriesCode = resolveSeriesCode(kiosk);
        List<Long> locationIds = resolveLocationIdsForSeries(seriesCode, kiosk.getId());
        if (exchangeSlipRepository.existsBySeriesCodeAndSlipNumber(seriesCode, normalized)) {
            throw new BusinessException("El número de boleta física ya fue registrado en esta serie.");
        }
        if (kioscoMovementRepository.existsByPhysicalSlipNumberAndKioscoStock_LocationIdIn(normalized, locationIds)) {
            throw new BusinessException(
                    "El número de boleta física ya fue registrado en inventario kiosko para esta serie.");
        }
        return normalized;
    }

    /** Serie del kiosko o {@code K{locationId}} si no tiene {@code internal_series_code}. */
    private String resolveSeriesCode(LocationEntity kiosk) {
        String series = safeTrim(kiosk.getInternalSeriesCode());
        if (!series.isEmpty()) {
            return series.toUpperCase(Locale.ROOT);
        }
        return "K" + kiosk.getId();
    }

    /**
     * Ubicaciones que comparten la serie (para acotar chequeo de movimientos).
     * Claves fallback {@code K{id}} solo incluyen ese kiosko.
     */
    private List<Long> resolveLocationIdsForSeries(String seriesCode, Long fallbackLocationId) {
        String normalized = safeTrim(seriesCode).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.of(fallbackLocationId);
        }
        if (normalized.length() > 1
                && normalized.charAt(0) == 'K'
                && normalized.substring(1).chars().allMatch(Character::isDigit)) {
            try {
                return List.of(Long.parseLong(normalized.substring(1)));
            } catch (NumberFormatException ignored) {
                // continue with lookup by series
            }
        }
        List<LocationEntity> locations = locationRepository.findByInternalSeriesCodeIgnoreCase(normalized);
        if (locations == null || locations.isEmpty()) {
            return List.of(fallbackLocationId);
        }
        return locations.stream()
                .map(LocationEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
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
                .givenHardwareCondition(slip.getGivenHardwareCondition())
                .givenQuantity(slip.getGivenQuantity())
                .givenAmount(slip.getGivenAmount())
                .givenItems(mapSlipGivenItems(slip))
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

    private List<KioskExchangePreviewResponse.ProductLine> mapSlipGivenItems(KioskExchangeSlipEntity slip) {
        List<KioskExchangeSlipGivenItemEntity> stored =
                exchangeSlipGivenItemRepository.findByExchangeSlipIdOrderByLineNoAsc(slip.getId());
        if (!stored.isEmpty()) {
            return stored.stream().map(item -> {
                ProductEntity product = productRepository.findById(item.getProductId()).orElse(null);
                ColorEntity color = item.getColorId() != null
                        ? colorRepository.findById(item.getColorId()).orElse(null)
                        : null;
                return KioskExchangePreviewResponse.ProductLine.builder()
                        .productId(item.getProductId())
                        .productCode(product != null ? product.getCode() : null)
                        .productName(product != null ? product.getName() : null)
                        .colorId(item.getColorId())
                        .colorName(color != null ? color.getName() : null)
                        .size(item.getSize())
                        .hardwareCondition(item.getHardwareCondition())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .lineTotal(item.getLineTotal())
                        .build();
            }).collect(Collectors.toList());
        }
        if (slip.getGivenProductId() == null) {
            return List.of();
        }
        ProductEntity givenProduct = productRepository.findById(slip.getGivenProductId()).orElse(null);
        ColorEntity givenColor = slip.getGivenColorId() != null
                ? colorRepository.findById(slip.getGivenColorId()).orElse(null)
                : null;
        return List.of(KioskExchangePreviewResponse.ProductLine.builder()
                .productId(slip.getGivenProductId())
                .productCode(givenProduct != null ? givenProduct.getCode() : null)
                .productName(givenProduct != null ? givenProduct.getName() : null)
                .colorId(slip.getGivenColorId())
                .colorName(givenColor != null ? givenColor.getName() : null)
                .size(slip.getGivenSize())
                .hardwareCondition(slip.getGivenHardwareCondition())
                .quantity(slip.getGivenQuantity())
                .unitPrice(slip.getGivenAmount() != null && slip.getGivenQuantity() != null
                        && slip.getGivenQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? slip.getGivenAmount().divide(slip.getGivenQuantity(), 2, RoundingMode.HALF_UP)
                        : null)
                .lineTotal(slip.getGivenAmount())
                .build());
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

    /** Precio de venta de catálogo (sin descuento ni promo) + recargo por talla. Usado en egreso y crédito por %. */
    private static BigDecimal resolveFullSaleUnitPrice(ProductEntity product) {
        return resolveFullSaleUnitPrice(product, null);
    }

    private static BigDecimal resolveFullSaleUnitPrice(ProductEntity product, String size) {
        if (product == null || product.getSalePrice() == null) {
            return BigDecimal.ZERO;
        }
        if (product.getSalePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = product.getSalePrice().setScale(2, RoundingMode.HALF_UP);
        return CinchoSizePricing.applySurcharge(base, size);
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

    private record ResolvedGivenLine(
            ProductEntity product,
            ColorEntity color,
            String size,
            String hardwareCondition,
            BigDecimal quantity,
            BigDecimal unitPriceOverride
    ) {
    }

    private record ExchangeContext(
            AccessContext access,
            KioskSaleEntity sale,
            KioskSaleItemEntity item,
            List<KioskSaleItemEntity> saleItems,
            ProductEntity returnedProduct,
            ColorEntity returnedColor,
            String returnedSize,
            BigDecimal returnedQty,
            List<ResolvedGivenLine> givenLines,
            BigDecimal returnedUnitPriceOverride,
            PackagingAllocation packaging
    ) {
        KioskExchangePreviewResponse preview() throws BusinessException {
            return buildPreview();
        }

        private KioskExchangePreviewResponse buildPreview() throws BusinessException {
            BigDecimal returnedUnitPaid = returnedUnitPriceOverride != null
                    ? returnedUnitPriceOverride
                    : (item != null
                            ? computeEffectivePaidUnitPrice(sale, item, saleItems)
                            : resolveFullSaleUnitPrice(returnedProduct, returnedSize));
            if (returnedUnitPaid.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("El producto que ingresa no tiene precio de venta configurado.");
            }
            BigDecimal productReturnedAmount = returnedUnitPaid.multiply(returnedQty).setScale(2, RoundingMode.HALF_UP);

            if (givenLines == null || givenLines.isEmpty()) {
                throw new BusinessException("Debes seleccionar al menos un producto a entregar.");
            }

            List<KioskExchangePreviewResponse.ProductLine> givenProductLines = new ArrayList<>();
            BigDecimal productGivenAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            for (ResolvedGivenLine given : givenLines) {
                BigDecimal givenUnitPrice;
                if (given.unitPriceOverride() != null) {
                    givenUnitPrice = given.unitPriceOverride();
                } else if (shouldPreservePaidPriceOnExchange(item, returnedProduct, given.product())) {
                    givenUnitPrice = returnedUnitPaid;
                } else {
                    givenUnitPrice = resolveFullSaleUnitPrice(given.product(), given.size());
                    if (givenUnitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BusinessException(
                                "El producto " + safeTrim(given.product().getCode())
                                        + " no tiene precio de venta configurado.");
                    }
                }
                BigDecimal lineTotal = givenUnitPrice.multiply(given.quantity()).setScale(2, RoundingMode.HALF_UP);
                productGivenAmount = productGivenAmount.add(lineTotal);
                givenProductLines.add(KioskExchangePreviewResponse.ProductLine.builder()
                        .productId(given.product().getId())
                        .productCode(given.product().getCode())
                        .productName(given.product().getName())
                        .colorId(given.color() != null ? given.color().getId() : null)
                        .colorName(given.color() != null ? given.color().getName() : null)
                        .size(given.size())
                        .hardwareCondition(given.hardwareCondition())
                        .quantity(given.quantity())
                        .unitPrice(givenUnitPrice)
                        .lineTotal(lineTotal)
                        .build());
            }

            PackagingAllocation pack = packaging != null ? packaging : PackagingAllocation.empty();
            BigDecimal packagingCredit = safeAmount(pack.returnedCredit()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal packagingReturned = appliedPackagingCredit(
                    productGivenAmount, productReturnedAmount, packagingCredit);
            BigDecimal returnedAmount = productReturnedAmount.add(packagingReturned).setScale(2, RoundingMode.HALF_UP);
            BigDecimal givenAmount = productGivenAmount;
            BigDecimal difference = givenAmount.subtract(returnedAmount).setScale(2, RoundingMode.HALF_UP);

            KioskExchangePreviewResponse.ProductLine returnedLine = KioskExchangePreviewResponse.ProductLine.builder()
                    .productId(returnedProduct.getId())
                    .productCode(returnedProduct.getCode())
                    .productName(item != null ? stripSizeFromProductName(item.getProductName()) : returnedProduct.getName())
                    .colorId(item != null ? item.getColorId() : returnedColor != null ? returnedColor.getId() : null)
                    .colorName(item != null ? item.getColorName() : returnedColor != null ? returnedColor.getName() : null)
                    .size(returnedSize)
                    .quantity(returnedQty)
                    .unitPrice(returnedUnitPaid)
                    .lineTotal(productReturnedAmount)
                    .build();

            KioskExchangePreviewResponse.ProductLine primaryGiven = givenProductLines.get(0);

            return KioskExchangePreviewResponse.builder()
                    .originalSaleId(sale != null ? sale.getId() : null)
                    .originalSaleNumber(sale != null ? sale.getSaleNumber() : null)
                    .originalSaleDate(sale != null ? sale.getSaleDate() : null)
                    .originalSaleItemId(item != null ? item.getId() : null)
                    .returned(returnedLine)
                    .given(primaryGiven)
                    .givenItems(givenProductLines)
                    .returnedAmount(returnedAmount)
                    .givenAmount(givenAmount)
                    .differenceAmount(difference)
                    .packagingReturnedAmount(packagingReturned)
                    .packagingCreditAmount(packagingCredit)
                    .packagingGivenAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .build();
        }
    }

    private static List<KioskExchangePreviewResponse.ProductLine> previewGivenLines(
            KioskExchangePreviewResponse preview
    ) {
        if (preview == null) {
            return List.of();
        }
        if (preview.getGivenItems() != null && !preview.getGivenItems().isEmpty()) {
            return preview.getGivenItems();
        }
        if (preview.getGiven() != null) {
            return List.of(preview.getGiven());
        }
        return List.of();
    }

    private void saveGivenItemRows(
            Long slipId,
            KioskExchangePreviewResponse preview,
            List<Long> givenMovementIds
    ) {
        if (slipId == null || preview == null) {
            return;
        }
        exchangeSlipGivenItemRepository.deleteByExchangeSlipId(slipId);
        List<KioskExchangePreviewResponse.ProductLine> lines = previewGivenLines(preview);
        List<KioskExchangeSlipGivenItemEntity> entities = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            KioskExchangePreviewResponse.ProductLine line = lines.get(i);
            Long movementId = givenMovementIds != null && i < givenMovementIds.size()
                    ? givenMovementIds.get(i)
                    : null;
            entities.add(KioskExchangeSlipGivenItemEntity.builder()
                    .exchangeSlipId(slipId)
                    .lineNo(i + 1)
                    .productId(line.getProductId())
                    .colorId(line.getColorId())
                    .size(line.getSize())
                    .hardwareCondition(line.getHardwareCondition())
                    .quantity(line.getQuantity())
                    .unitPrice(line.getUnitPrice())
                    .lineTotal(line.getLineTotal())
                    .givenMovementId(movementId)
                    .build());
        }
        exchangeSlipGivenItemRepository.saveAll(entities);
    }

    private void linkGivenItemMovements(Long slipId, List<Long> givenMovementIds) {
        if (slipId == null || givenMovementIds == null || givenMovementIds.isEmpty()) {
            return;
        }
        List<KioskExchangeSlipGivenItemEntity> items =
                exchangeSlipGivenItemRepository.findByExchangeSlipIdOrderByLineNoAsc(slipId);
        for (int i = 0; i < items.size() && i < givenMovementIds.size(); i++) {
            items.get(i).setGivenMovementId(givenMovementIds.get(i));
        }
        exchangeSlipGivenItemRepository.saveAll(items);
    }

    private List<KioscoInventoryService.CambioGivenLine> resolveGivenLinesForStock(KioskExchangeSlipEntity slip) {
        List<KioskExchangeSlipGivenItemEntity> stored =
                exchangeSlipGivenItemRepository.findByExchangeSlipIdOrderByLineNoAsc(slip.getId());
        if (!stored.isEmpty()) {
            return stored.stream()
                    .map(item -> KioscoInventoryService.CambioGivenLine.builder()
                            .productId(item.getProductId())
                            .colorId(item.getColorId())
                            .quantity(item.getQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact())
                            .sizeKey(item.getSize())
                            .hardwareCondition(item.getHardwareCondition())
                            .build())
                    .collect(Collectors.toList());
        }
        int givenQty = slip.getGivenQuantity() != null
                ? slip.getGivenQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact()
                : slip.getReturnedQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact();
        return List.of(KioscoInventoryService.CambioGivenLine.builder()
                .productId(slip.getGivenProductId())
                .colorId(slip.getGivenColorId())
                .quantity(givenQty)
                .sizeKey(slip.getGivenSize())
                .hardwareCondition(slip.getGivenHardwareCondition())
                .build());
    }

    /**
     * El empaque de la factura original solo entra cuando hay diferencia de precio
     * entre el producto que ingresa y el que se entrega. Sin diferencia de producto, se ignora.
     */
    static BigDecimal appliedPackagingCredit(
            BigDecimal productGivenAmount,
            BigDecimal productReturnedAmount,
            BigDecimal allocatedCredit
    ) {
        BigDecimal given = safeAmount(productGivenAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal returned = safeAmount(productReturnedAmount).setScale(2, RoundingMode.HALF_UP);
        if (given.compareTo(returned) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return safeAmount(allocatedCredit).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Precio unitario realmente pagado en la venta original.
     * El descuento de la venta se reparte solo entre productos (empaques SUM sin descuento).
     */
    static BigDecimal computeEffectivePaidUnitPrice(
            KioskSaleEntity sale,
            KioskSaleItemEntity item,
            List<KioskSaleItemEntity> allItems
    ) {
        if (item == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal qty = item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? item.getQuantity()
                : BigDecimal.ONE;
        if (ProductCinchoType.isPackagingProductCode(item.getProductCode())) {
            return packagingLineAmountNoDiscount(item).divide(qty, 2, RoundingMode.HALF_UP);
        }
        BigDecimal lineSubtotal = item.getLineTotal() != null
                ? item.getLineTotal()
                : safeAmount(item.getUnitPrice()).multiply(qty);
        BigDecimal discount = sale != null && sale.getDiscountAmount() != null
                ? sale.getDiscountAmount()
                : BigDecimal.ZERO;
        List<KioskSaleItemEntity> items = allItems != null && !allItems.isEmpty()
                ? allItems
                : (sale != null && sale.getItems() != null ? List.copyOf(sale.getItems()) : List.of());
        BigDecimal discountBase = BigDecimal.ZERO;
        for (KioskSaleItemEntity saleItem : items) {
            if (saleItem == null || ProductCinchoType.isPackagingProductCode(saleItem.getProductCode())) {
                continue;
            }
            BigDecimal itemQty = saleItem.getQuantity() != null && saleItem.getQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? saleItem.getQuantity() : BigDecimal.ONE;
            BigDecimal itemSub = saleItem.getLineTotal() != null
                    ? saleItem.getLineTotal()
                    : safeAmount(saleItem.getUnitPrice()).multiply(itemQty);
            discountBase = discountBase.add(itemSub);
        }
        if (discountBase.compareTo(BigDecimal.ZERO) <= 0) {
            discountBase = lineSubtotal;
        }
        if (discountBase.compareTo(BigDecimal.ZERO) <= 0) {
            return safeAmount(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal lineDiscountShare = discount.multiply(lineSubtotal)
                .divide(discountBase, 6, RoundingMode.HALF_UP);
        BigDecimal linePaid = lineSubtotal.subtract(lineDiscountShare).max(BigDecimal.ZERO);
        return linePaid.divide(qty, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal computeEffectivePaidUnitPrice(KioskSaleEntity sale, KioskSaleItemEntity item) {
        return computeEffectivePaidUnitPrice(sale, item, null);
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

    private Long resolvePhysicalCountIdForReturns(Long physicalCountId, Long kioskLocationId)
            throws BusinessException {
        if (physicalCountId == null) {
            return null;
        }
        KioscoPhysicalCountEntity count = physicalCountRepository.findById(physicalCountId)
                .orElseThrow(() -> new BusinessException("Conteo físico no encontrado."));
        if (!Objects.equals(count.getLocationId(), kioskLocationId)) {
            throw new BusinessException("El conteo físico no pertenece al kiosko seleccionado.");
        }
        if (count.getStatus() != KioscoPhysicalCountStatus.DRAFT
                && count.getStatus() != KioscoPhysicalCountStatus.CONTADO) {
            throw new BusinessException("Solo puedes asociar devoluciones a un conteo en borrador o contado.");
        }
        return count.getId();
    }
}
