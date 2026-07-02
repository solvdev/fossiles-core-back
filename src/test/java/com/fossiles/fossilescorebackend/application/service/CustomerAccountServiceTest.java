package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountDocumentSettlementRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CustomerAccountEntryRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CustomerAccountDocumentSettlementResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerAccountEntryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerAccountServiceTest {

    @Mock private CustomerAccountEntryRepository entryRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductionOrderPartialReleaseRepository partialReleaseRepository;
    @Mock private ProductShipmentRepository productShipmentRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private SecurityUtil securityUtil;

    @InjectMocks
    private CustomerAccountService service;

    private final Long customerId = 1L;
    private final Long chargeId = 100L;
    private final AtomicLong idSeq = new AtomicLong(200);

    @BeforeEach
    void setUp() {
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(
                CustomerEntity.builder().id(customerId).name("Cliente Test").build()));
        when(securityUtil.getCurrentUserId()).thenReturn(99L);
        when(entryRepository.findByCustomerIdAndStatusOrderByEntryDateAscIdAsc(customerId, "ACTIVE"))
                .thenAnswer(inv -> new ArrayList<>(storedEntries));
    }

    private final List<CustomerAccountEntryEntity> storedEntries = new ArrayList<>();

    @Test
    void createDocumentSettlement_discountThenPartialPayment_closesBalance() throws Exception {
        CustomerAccountEntryEntity charge = CustomerAccountEntryEntity.builder()
                .id(chargeId)
                .customerId(customerId)
                .entryType("CHARGE")
                .status("ACTIVE")
                .amount(new BigDecimal("6500.00"))
                .entryDate(LocalDate.now())
                .build();
        storedEntries.clear();
        storedEntries.add(charge);

        when(entryRepository.findById(chargeId)).thenReturn(Optional.of(charge));
        when(entryRepository.save(any(CustomerAccountEntryEntity.class))).thenAnswer(inv -> {
            CustomerAccountEntryEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(idSeq.getAndIncrement());
            }
            storedEntries.add(e);
            return e;
        });

        CustomerAccountDocumentSettlementRequest req = new CustomerAccountDocumentSettlementRequest();
        req.setAppliedToEntryId(chargeId);
        req.setEntryDate(LocalDate.now());
        req.setCollectionDate(LocalDate.now());
        req.setDiscountAmount(new BigDecimal("1500.00"));
        req.setPaymentGross(new BigDecimal("5000.00"));
        req.setReceiptNumber("RC-001");
        req.setPaymentMethod("EFECTIVO");

        CustomerAccountDocumentSettlementResponse result = service.createDocumentSettlement(customerId, req);

        assertThat(result.getInitialBalance()).isEqualByComparingTo("6500.00");
        assertThat(result.getCommercialDiscount()).isEqualByComparingTo("1500.00");
        assertThat(result.getPaymentGross()).isEqualByComparingTo("5000.00");
        assertThat(result.getFinalBalance()).isEqualByComparingTo("0.00");
        assertThat(result.getEntries()).hasSize(2);

        ArgumentCaptor<CustomerAccountEntryEntity> captor = ArgumentCaptor.forClass(CustomerAccountEntryEntity.class);
        // verify credit note + payment saved
        assertThat(storedEntries.stream().filter(e -> "CREDIT_NOTE".equals(e.getEntryType())).count()).isEqualTo(1);
        assertThat(storedEntries.stream().filter(e -> "PAYMENT".equals(e.getEntryType())).count()).isEqualTo(1);
    }

    @Test
    void createDocumentSettlement_exceedsBalance_throws() {
        CustomerAccountEntryEntity charge = CustomerAccountEntryEntity.builder()
                .id(chargeId)
                .customerId(customerId)
                .entryType("CHARGE")
                .status("ACTIVE")
                .amount(new BigDecimal("6500.00"))
                .entryDate(LocalDate.now())
                .build();
        storedEntries.clear();
        storedEntries.add(charge);
        when(entryRepository.findById(chargeId)).thenReturn(Optional.of(charge));

        CustomerAccountDocumentSettlementRequest req = new CustomerAccountDocumentSettlementRequest();
        req.setAppliedToEntryId(chargeId);
        req.setEntryDate(LocalDate.now());
        req.setDiscountAmount(new BigDecimal("2000.00"));
        req.setPaymentGross(new BigDecimal("5000.00"));

        assertThatThrownBy(() -> service.createDocumentSettlement(customerId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceden el saldo");
    }
}
