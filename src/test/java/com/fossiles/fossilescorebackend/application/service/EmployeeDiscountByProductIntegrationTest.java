package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductEmployeeDiscountRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.controller.ProductController;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeChargeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InternalShipmentRequestLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InternalShipmentRequestRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Descuento de colaborador por producto: el propio del producto o, sin él, el 50 % general. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:employee_discount;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class EmployeeDiscountByProductIntegrationTest {
    @Autowired EmployeeChargeService chargeService;
    @Autowired ProductController productController;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired ProductRepository products;
    @Autowired InternalShipmentRequestRepository requests;
    @MockitoBean PayrollAccessGuard guard;

    EmployeeEntity operator;
    ProductEntity belt;
    ProductEntity wallet;

    @BeforeEach
    void fixture() {
        DepartmentEntity production = departments.save(DepartmentEntity.builder().code("P" + UUID.randomUUID().toString().substring(0, 6)).name("Producción").build());
        operator = employees.save(EmployeeEntity.builder().firstName("Ana").lastName("Prueba").salary(new BigDecimal("4000"))
                .payGroup("PLANTA").employmentType("PLANILLA").paymentMethod("CHEQUE").departmentId(production.getId()).status("active").build());
        belt = products.save(ProductEntity.builder().code("C" + UUID.randomUUID().toString().substring(0, 6)).name("Cincho").salePrice(new BigDecimal("200")).build());
        wallet = products.save(ProductEntity.builder().code("B" + UUID.randomUUID().toString().substring(0, 6)).name("Billetera").salePrice(new BigDecimal("100")).build());
    }

    @Test
    void withoutAnOwnDiscountTheGeneralHalfApplies() throws Exception {
        assertThat(EmployeeChargeService.employeeDiscountPercent(belt)).isEqualByComparingTo("50");
        EmployeeChargeEntity charge = purchase(line(belt, "1"), line(wallet, "2"));
        assertThat(charge.getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void eachProductChargesWithItsOwnDiscount() throws Exception {
        belt.setEmployeeDiscountPercent(new BigDecimal("30"));
        wallet.setEmployeeDiscountPercent(new BigDecimal("100"));
        products.saveAll(List.of(belt, wallet));
        // Cincho: 200 × 70 % = 140. Billetera: gratis. Un tercero sin descuento propio cobra el 50 %.
        ProductEntity strap = products.save(ProductEntity.builder().code("S" + UUID.randomUUID().toString().substring(0, 6)).name("Correa").salePrice(new BigDecimal("80")).build());
        EmployeeChargeEntity charge = purchase(line(belt, "1"), line(wallet, "3"), line(strap, "2"));
        assertThat(charge.getAmount()).isEqualByComparingTo("220.00");
    }

    @Test
    void anOwnDiscountOfZeroMeansFullPrice() throws Exception {
        belt.setEmployeeDiscountPercent(BigDecimal.ZERO);
        products.save(belt);
        assertThat(purchase(line(belt, "1")).getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void theEndpointSetsAndClearsTheDiscountAndValidatesTheRange() throws Exception {
        productController.setEmployeeDiscounts(request(item(belt, "25"), item(wallet, null)));
        assertThat(products.findById(belt.getId()).orElseThrow().getEmployeeDiscountPercent()).isEqualByComparingTo("25");
        assertThat(products.findById(wallet.getId()).orElseThrow().getEmployeeDiscountPercent()).isNull();
        assertThat(purchase(line(belt, "1")).getAmount()).isEqualByComparingTo("150.00");

        productController.setEmployeeDiscounts(request(item(belt, null)));
        assertThat(products.findById(belt.getId()).orElseThrow().getEmployeeDiscountPercent()).isNull();

        assertThatThrownBy(() -> productController.setEmployeeDiscounts(request(item(belt, "101"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("entre 0 y 100");
        assertThatThrownBy(() -> productController.setEmployeeDiscounts(request(item(belt, "-1"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("entre 0 y 100");
    }

    private EmployeeChargeEntity purchase(InternalShipmentRequestLineEntity... lines) throws BusinessException {
        InternalShipmentRequestEntity request = InternalShipmentRequestEntity.builder()
                .employeeId(operator.getId()).requestType("PLANILLA").status("APROBADA").recipientName("Prueba")
                .lines(new ArrayList<>()).build();
        int order = 1;
        for (InternalShipmentRequestLineEntity line : lines) {
            line.setRequest(request);
            line.setLineOrder(order++);
            request.getLines().add(line);
        }
        return chargeService.chargeInternalShipment(requests.save(request), "ENVI-PRUEBA");
    }

    private static InternalShipmentRequestLineEntity line(ProductEntity product, String quantity) {
        return InternalShipmentRequestLineEntity.builder().productId(product.getId()).quantity(new BigDecimal(quantity)).build();
    }

    private static ProductEmployeeDiscountRequest.Item item(ProductEntity product, String percent) {
        ProductEmployeeDiscountRequest.Item item = new ProductEmployeeDiscountRequest.Item();
        item.setProductId(product.getId());
        item.setDiscountPercent(percent == null ? null : new BigDecimal(percent));
        return item;
    }

    private static ProductEmployeeDiscountRequest request(ProductEmployeeDiscountRequest.Item... items) {
        ProductEmployeeDiscountRequest request = new ProductEmployeeDiscountRequest();
        request.setItems(List.of(items));
        return request;
    }
}
