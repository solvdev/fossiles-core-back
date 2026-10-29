package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.InternalShipmentRequestCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.InternalShipmentRequestResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sin @Transactional a nivel de clase: cada llamada al servicio confirma o revierte su propia
 * transacción, que es justo lo que se verifica (una solicitud rechazada no debe quedar guardada).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:internal_shipment_slip;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InternalShipmentRequestSlipIntegrationTest {

    @Autowired InternalShipmentRequestService service;
    @Autowired InternalShipmentRequestRepository requests;
    @Autowired InternalShipmentRequestSlipRepository slips;
    @Autowired ProductRepository products;
    @MockitoBean InternalShipmentRequestAccessGuard guard;

    ProductEntity belt;

    @BeforeEach
    void fixture() {
        slips.deleteAll();
        requests.deleteAll();
        belt = products.save(ProductEntity.builder().code(code("C")).name("Cincho").salePrice(new BigDecimal("200")).build());
    }

    @Test
    void freeSlipCreatesTheRequestOnceAndIsRegisteredAsUsed() throws Exception {
        InternalShipmentRequestResponse created = service.createRequest(request("BLS-E2E-P1"));

        assertThat(created.getStatus()).isEqualTo("PENDIENTE");
        assertThat(created.getSlipNumber()).isEqualTo("BLS-E2E-P1");
        assertThat(requests.findAll()).singleElement()
                .satisfies(saved -> assertThat(saved.getId()).isEqualTo(created.getId()));
        InternalShipmentRequestSlipEntity slip = slips.findBySlipNumber("BLS-E2E-P1").orElseThrow();
        assertThat(slip.getStatus()).isEqualTo("USED");
        assertThat(slip.getRequestId()).isEqualTo(created.getId());
    }

    @Test
    void sameFreeSlipOnASecondRequestIsRejectedAndNothingIsSaved() throws Exception {
        Long first = service.createRequest(request("BLS-00901")).getId();

        assertThatThrownBy(() -> service.createRequest(request("bls-901")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("BLS-00901 ya fue utilizada");

        assertThat(requests.findAll()).extracting(InternalShipmentRequestEntity::getId).containsExactly(first);
        assertThat(slips.findBySlipNumber("BLS-00901").orElseThrow().getRequestId()).isEqualTo(first);
    }

    @Test
    void slipAlreadyOnAnOlderRequestWithoutBookletIsRejectedAndNothingIsSaved() {
        InternalShipmentRequestEntity older = requests.save(InternalShipmentRequestEntity.builder()
                .status("APROBADA").requestType("DEFECTOS").recipientName("Anterior").slipNumber("BLS-00077").build());

        assertThatThrownBy(() -> service.createRequest(request("BLS-00077")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("BLS-00077 ya fue registrada");

        assertThat(requests.findAll()).extracting(InternalShipmentRequestEntity::getId).containsExactly(older.getId());
        assertThat(slips.findBySlipNumber("BLS-00077")).isEmpty();
    }

    @Test
    void bookletSlipMarkedUsedIsRejectedAndNothingIsSaved() {
        slips.save(InternalShipmentRequestSlipEntity.builder().slipNumber("BLS-00012").status("USED").requestId(4321L).build());

        assertThatThrownBy(() -> service.createRequest(request("BLS-00012")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("BLS-00012 ya fue utilizada");

        assertThat(requests.count()).isZero();
        InternalShipmentRequestSlipEntity slip = slips.findBySlipNumber("BLS-00012").orElseThrow();
        assertThat(slip.getStatus()).isEqualTo("USED");
        assertThat(slip.getRequestId()).isEqualTo(4321L);
    }

    private InternalShipmentRequestCreateRequest request(String slipNumber) {
        return InternalShipmentRequestCreateRequest.builder()
                .requestType("DEFECTOS").recipientName("Colaborador de prueba").slipNumber(slipNumber)
                .discountPercent(new BigDecimal("50"))
                .products(List.of(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(belt.getId()).quantity(BigDecimal.ONE).build()))
                .build();
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
