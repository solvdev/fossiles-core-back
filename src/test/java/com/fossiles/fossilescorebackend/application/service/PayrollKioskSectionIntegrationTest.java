package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.PayrollRunCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PayrollRunResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DepartmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.EmployeeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayrollParameterEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.DepartmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.EmployeeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayrollParameterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Administración y kioscos cobran en las mismas fechas pero se ven por separado: cada línea y ficha dice si es de kiosco. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:payroll_kiosk;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class PayrollKioskSectionIntegrationTest {
    @Autowired PayrollRunService runs;
    @Autowired PayrollProfileService profiles;
    @Autowired EmployeeRepository employees;
    @Autowired DepartmentRepository departments;
    @Autowired PayrollParameterRepository parameters;
    @MockitoBean PayrollAccessGuard guard;

    @Test
    void runLinesAndProfilesTellWhoWorksInAKiosk() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        parameters.saveAll(List.of(
                param(PayrollParameters.IGSS_LABORAL_PCT, "4.83", from), param(PayrollParameters.IGSS_PATRONAL_PCT, "10.67", from),
                param(PayrollParameters.IRTRA_PCT, "1", from), param(PayrollParameters.INTECAP_PCT, "1", from),
                param(PayrollParameters.BONIFICACION_INCENTIVO_MENSUAL, "250", from),
                param(PayrollParameters.ISR_DEDUCCION_PERSONAL, "48000", from), param(PayrollParameters.ISR_TRAMO_LIMITE, "300000", from),
                param(PayrollParameters.ISR_TASA_TRAMO1_PCT, "5", from), param(PayrollParameters.ISR_TASA_TRAMO2_PCT, "7", from),
                param(PayrollParameters.ISR_CUOTA_FIJA_TRAMO2, "15000", from)));
        DepartmentEntity kiosk = departments.save(DepartmentEntity.builder().code(code("K")).name("Kiosco Miraflores").build());
        DepartmentEntity office = departments.save(DepartmentEntity.builder().code(code("A")).name("Contabilidad").build());
        EmployeeEntity seller = employee("Vera", kiosk);
        EmployeeEntity accountant = employee("Carla", office);

        PayrollRunCreateRequest request = new PayrollRunCreateRequest();
        request.setPayGroup("ADMINISTRACION");
        request.setScheduledDate(LocalDate.of(2026, 10, 15));
        PayrollRunResponse run = runs.create(request);

        PayrollRunResponse.Line sellerLine = run.getLines().stream().filter(l -> l.getEmployeeId().equals(seller.getId())).findFirst().orElseThrow();
        PayrollRunResponse.Line accountantLine = run.getLines().stream().filter(l -> l.getEmployeeId().equals(accountant.getId())).findFirst().orElseThrow();
        assertThat(sellerLine.getKiosk()).isTrue();
        assertThat(sellerLine.getDepartmentName()).isEqualTo("Kiosco Miraflores");
        assertThat(accountantLine.getKiosk()).isFalse();
        assertThat(accountantLine.getDepartmentName()).isEqualTo("Contabilidad");

        List<PayrollProfileResponse> all = profiles.list();
        assertThat(all.stream().filter(p -> p.getId().equals(seller.getId())).findFirst().orElseThrow().getKiosk()).isTrue();
        assertThat(all.stream().filter(p -> p.getId().equals(accountant.getId())).findFirst().orElseThrow().getKiosk()).isFalse();
    }

    private EmployeeEntity employee(String firstName, DepartmentEntity department) {
        return employees.save(EmployeeEntity.builder()
                .firstName(firstName).lastName("Prueba").salary(new BigDecimal("4000"))
                .payGroup("ADMINISTRACION").employmentType("PLANILLA").paymentMethod("CHEQUE")
                .departmentId(department.getId()).status("active").build());
    }

    private static PayrollParameterEntity param(String code, String value, LocalDate from) {
        return PayrollParameterEntity.builder().code(code).value(new BigDecimal(value)).effectiveFrom(from).build();
    }

    private static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
