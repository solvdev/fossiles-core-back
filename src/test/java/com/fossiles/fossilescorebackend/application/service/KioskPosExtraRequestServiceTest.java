package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeRejectRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosExtraRequestResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPosExtraRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPosExtraRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KioskPosExtraRequestServiceTest {

    private static final long KIOSK = 7L;
    private static final long PRODUCT = 100L;
    private static final long COLOR = 5L;

    @Mock private KioskPosExtraRequestRepository requestRepository;
    @Mock private KioskExchangeAuthorizationGuard authorizationGuard;
    @Mock private SecurityUtil securityUtil;
    @Mock private UserRepository userRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;

    private KioskPosExtraRequestService service;

    @BeforeEach
    void setUp() {
        service = new KioskPosExtraRequestService(
                requestRepository, authorizationGuard, securityUtil, userRepository,
                locationRepository, productRepository, colorRepository);
        UserEntity admin = new UserEntity();
        admin.setId(1L);
        when(securityUtil.getCurrentUserId()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(requestRepository.save(any(KioskPosExtraRequestEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(locationRepository.findAllById(any())).thenReturn(List.of());
    }

    private KioskPosExtraRequestEntity request(String status) {
        return KioskPosExtraRequestEntity.builder()
                .id(12L)
                .kioskLocationId(KIOSK)
                .productId(PRODUCT)
                .colorId(COLOR)
                .size(null)
                .hardwareCondition("NUEVO")
                .quantity(new BigDecimal("2"))
                .extraAmount(new BigDecimal("15.00"))
                .description("Grabado con iniciales")
                .status(status)
                .requestDate(GuatemalaDateTime.today())
                .requestedBy(2L)
                .build();
    }

    private void stubLock(KioskPosExtraRequestEntity row) {
        when(requestRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    }

    private KioskPosExtraRequestEntity lockForSale(KioskPosExtraRequestEntity row, BigDecimal qty) throws Exception {
        stubLock(row);
        return service.lockApprovedForSale(row.getId(), KIOSK, PRODUCT, COLOR, null, "NUEVO", qty);
    }

    @Test
    void lockApprovedForSale_acceptsApprovedRequestMatchingLine() throws Exception {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_APPROVED);
        assertEquals(row, lockForSale(row, new BigDecimal("2")));
    }

    @Test
    void lockApprovedForSale_rejectsPendingRejectedUsedAndCancelled() {
        for (String status : List.of("PENDING", "REJECTED", "USED", "CANCELLED")) {
            KioskPosExtraRequestEntity row = request(status);
            assertThrows(BusinessException.class, () -> lockForSale(row, new BigDecimal("2")), status);
        }
    }

    @Test
    void lockApprovedForSale_rejectsDifferentQuantityProductOrKiosk() {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_APPROVED);
        assertThrows(BusinessException.class, () -> lockForSale(row, new BigDecimal("3")));
        stubLock(row);
        assertThrows(BusinessException.class, () ->
                service.lockApprovedForSale(12L, KIOSK, PRODUCT + 1, COLOR, null, "NUEVO", new BigDecimal("2")));
        assertThrows(BusinessException.class, () ->
                service.lockApprovedForSale(12L, KIOSK + 1, PRODUCT, COLOR, null, "NUEVO", new BigDecimal("2")));
        assertThrows(BusinessException.class, () ->
                service.lockApprovedForSale(12L, KIOSK, PRODUCT, COLOR, null, "VIEJO", new BigDecimal("2")));
    }

    @Test
    void lockApprovedForSale_rejectsRequestFromAnotherDay() {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_APPROVED);
        row.setRequestDate(LocalDate.now().minusDays(2));
        BusinessException ex = assertThrows(BusinessException.class, () -> lockForSale(row, new BigDecimal("2")));
        assertTrue(ex.getMessage().contains("venció"));
    }

    @Test
    void authorize_movesPendingToApprovedAndChecksGuard() throws Exception {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_PENDING);
        stubLock(row);
        KioskPosExtraRequestResponse response = service.authorize(row.getId());
        verify(authorizationGuard).assertCanApproveOrReject();
        assertEquals("APPROVED", response.getStatus());
        assertEquals(new BigDecimal("30.00"), response.getExtraTotal());
        assertEquals(1L, row.getAuthorizedBy());
    }

    @Test
    void authorize_failsWhenGuardDenies() throws Exception {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_PENDING);
        stubLock(row);
        doThrow(new BusinessException("Solo administración o logística"))
                .when(authorizationGuard).assertCanApproveOrReject();
        assertThrows(BusinessException.class, () -> service.authorize(row.getId()));
        assertEquals("PENDING", row.getStatus());
    }

    @Test
    void reject_requiresReasonAndOnlyPending() throws Exception {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_PENDING);
        stubLock(row);
        assertThrows(BusinessException.class, () -> service.reject(row.getId(), new KioskExchangeRejectRequest(" ")));
        KioskPosExtraRequestResponse response =
                service.reject(row.getId(), new KioskExchangeRejectRequest("Precio no autorizado"));
        assertEquals("REJECTED", response.getStatus());
        assertEquals("Precio no autorizado", row.getRejectionReason());
        assertThrows(BusinessException.class,
                () -> service.reject(row.getId(), new KioskExchangeRejectRequest("otra vez")));
    }

    @Test
    void markUsed_setsUsedAndSale() {
        KioskPosExtraRequestEntity row = request(KioskPosExtraRequestEntity.STATUS_APPROVED);
        service.markUsed(row, 99L);
        assertEquals("USED", row.getStatus());
        assertEquals(99L, row.getSaleId());
    }

    @Test
    void requestNumber_isPadded() {
        assertEquals("CEX-00012", KioskPosExtraRequestService.requestNumber(request("PENDING")));
    }
}
