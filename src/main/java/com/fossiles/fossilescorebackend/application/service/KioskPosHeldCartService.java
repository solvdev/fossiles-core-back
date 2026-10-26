package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskPosHeldCartRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosHeldCartResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPosHeldCartEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosHeldCartRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Carritos del POS en espera (p. ej. mientras llega la autorización de un complemento extra).
 * Guardan solo el JSON que arma el front: no son ventas (sin correlativo, stock ni caja) y valen el día.
 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class KioskPosHeldCartService {

    private final KioskPosHeldCartRepository heldCartRepository;
    private final KioskPosExtraRequestService accessHelper;
    private final UserRepository userRepository;

    public KioskPosHeldCartResponse hold(KioskPosHeldCartRequest request) throws BusinessException {
        if (request == null || request.getCartJson() == null || request.getCartJson().isBlank()) {
            throw new BusinessException("El carrito está vacío.");
        }
        UserEntity user = accessHelper.getCurrentUserOrThrow();
        LocationEntity kiosk = accessHelper.resolveKiosk(user, request.getKioskLocationId());
        KioskPosHeldCartEntity saved = heldCartRepository.save(KioskPosHeldCartEntity.builder()
                .kioskLocationId(kiosk.getId())
                .createdBy(user.getId())
                .label(request.getLabel() == null ? null : request.getLabel().trim())
                .cartJson(request.getCartJson())
                .heldDate(GuatemalaDateTime.today())
                .build());
        return toResponses(List.of(saved)).get(0);
    }

    @Transactional(readOnly = true)
    public List<KioskPosHeldCartResponse> list(Long kioskLocationId) throws BusinessException {
        UserEntity user = accessHelper.getCurrentUserOrThrow();
        LocationEntity kiosk = accessHelper.resolveKiosk(user, kioskLocationId);
        return toResponses(heldCartRepository.findByKioskLocationIdAndHeldDateOrderByCreatedAtAsc(
                kiosk.getId(), GuatemalaDateTime.today()));
    }

    /** Devuelve el carrito y lo elimina de la espera; si otro cajero ya lo retomó responde error. */
    public KioskPosHeldCartResponse resume(Long id) throws BusinessException {
        UserEntity user = accessHelper.getCurrentUserOrThrow();
        KioskPosHeldCartEntity row = heldCartRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException("Esta venta en espera ya fue retomada o descartada."));
        accessHelper.resolveKiosk(user, row.getKioskLocationId());
        KioskPosHeldCartResponse response = toResponses(List.of(row)).get(0);
        heldCartRepository.delete(row);
        return response;
    }

    public void discard(Long id) throws BusinessException {
        UserEntity user = accessHelper.getCurrentUserOrThrow();
        Optional<KioskPosHeldCartEntity> found = heldCartRepository.findByIdForUpdate(id);
        if (found.isEmpty()) {
            return;
        }
        accessHelper.resolveKiosk(user, found.get().getKioskLocationId());
        heldCartRepository.delete(found.get());
    }

    private List<KioskPosHeldCartResponse> toResponses(List<KioskPosHeldCartEntity> rows) {
        Map<Long, UserEntity> users = new HashMap<>();
        userRepository.findAllById(rows.stream().map(KioskPosHeldCartEntity::getCreatedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(u -> users.put(u.getId(), u));
        return rows.stream().map(row -> KioskPosHeldCartResponse.builder()
                .id(row.getId())
                .kioskLocationId(row.getKioskLocationId())
                .label(row.getLabel())
                .cartJson(row.getCartJson())
                .heldDate(row.getHeldDate())
                .createdByUserId(row.getCreatedBy())
                .createdByName(KioskPosExtraRequestService.formatUserName(users.get(row.getCreatedBy())))
                .createdAt(row.getCreatedAt())
                .build()).collect(Collectors.toList());
    }
}
