package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PayrollCalculatorTest {

    static final PayrollParameters PARAMS = new PayrollParameters(
            bd("4.83"), bd("10.67"), bd("1"), bd("1"), bd("250"),
            bd("48000"), bd("300000"), bd("5"), bd("7"), bd("15000"));
    static final LocalDate FIRST_HALF_START = LocalDate.of(2026, 10, 1);
    static final LocalDate FIRST_HALF_END = LocalDate.of(2026, 10, 15);

    @Test
    void fullQuincenaOnPlanillaWithDefaultBonificationAndNoIsr() {
        var r = calc("PLANILLA", "4000", null, null, null, FIRST_HALF_START, FIRST_HALF_END, "0");
        assertThat(r.daysPaid()).isEqualByComparingTo("15");
        assertThat(r.baseSalary()).isEqualByComparingTo("2000.00");
        assertThat(r.bonification()).isEqualByComparingTo("125.00");
        assertThat(r.igssEmployee()).isEqualByComparingTo("96.60");
        assertThat(r.isr()).isEqualByComparingTo("0");
        assertThat(r.netPay()).isEqualByComparingTo("2028.40");
        assertThat(r.igssEmployer()).isEqualByComparingTo("213.40");
        assertThat(r.irtra()).isEqualByComparingTo("20.00");
        assertThat(r.intecap()).isEqualByComparingTo("20.00");
    }

    @Test
    void isrIsOneTwentyFourthOfTheProjectedAnnualTax() {
        // 144,000 menos 48,000 de deducción y 6,955.20 de IGSS = 89,044.80; al 5 % son 4,452.24 al año.
        var r = calc("PLANILLA", "12000", "250", null, null, FIRST_HALF_START, FIRST_HALF_END, "0");
        assertThat(r.igssEmployee()).isEqualByComparingTo("289.80");
        assertThat(r.isr()).isEqualByComparingTo("185.51");
        assertThat(r.netPay()).isEqualByComparingTo("5649.69");
        assertThat(PayrollIsrCalculator.annualTax(bd("400000"), PARAMS)).isEqualByComparingTo("22000.00");
    }

    @Test
    void serviciosOnlyGetsTheFeeMinusEmployeePurchases() {
        var r = calc("SERVICIOS", "3000", "250", null, null, FIRST_HALF_START, FIRST_HALF_END, "200");
        assertThat(r.baseSalary()).isEqualByComparingTo("1500.00");
        assertThat(r.bonification()).isEqualByComparingTo("0");
        assertThat(r.igssEmployee()).isEqualByComparingTo("0");
        assertThat(r.isr()).isEqualByComparingTo("0");
        assertThat(r.igssEmployer()).isEqualByComparingTo("0");
        assertThat(r.opiDiscount()).isEqualByComparingTo("200.00");
        assertThat(r.netPay()).isEqualByComparingTo("1300.00");
    }

    @Test
    void hireOrTerminationInsideThePeriodPaysProportionalDays() {
        var hired = calc("PLANILLA", "4000", null, LocalDate.of(2026, 10, 6), null, FIRST_HALF_START, FIRST_HALF_END, "0");
        assertThat(hired.daysPaid()).isEqualByComparingTo("10.00");
        assertThat(hired.baseSalary()).isEqualByComparingTo("1333.33");
        assertThat(hired.bonification()).isEqualByComparingTo("83.33");
        var left = calc("PLANILLA", "4000", null, null, LocalDate.of(2026, 10, 23),
                LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 31), "0");
        assertThat(left.daysPaid()).isEqualByComparingTo("7.50");
        var notYet = calc("PLANILLA", "4000", null, LocalDate.of(2026, 10, 20), null, FIRST_HALF_START, FIRST_HALF_END, "0");
        assertThat(notYet.daysPaid()).isEqualByComparingTo("0");
        assertThat(notYet.netPay()).isEqualByComparingTo("0");
    }

    @Test
    void purchasesNeverLeaveANegativeNetAndBonificationComesFromTheFile() {
        var r = calc("PLANILLA", "4000", "500", null, null, FIRST_HALF_START, FIRST_HALF_END, "5000");
        assertThat(r.bonification()).isEqualByComparingTo("250.00");
        assertThat(r.opiDiscount()).isEqualByComparingTo("2153.40");
        assertThat(r.netPay()).isEqualByComparingTo("0");
        assertThat(r.gross().subtract(r.deductions())).isEqualByComparingTo(r.netPay());
    }

    private static PayrollCalculator.Result calc(String type, String salary, String bonification, LocalDate hire,
                                                 LocalDate termination, LocalDate start, LocalDate end, String opi) {
        return PayrollCalculator.calculate(new PayrollCalculator.Input(type, bd(salary),
                bonification == null ? null : bd(bonification), hire, termination, start, end, bd(opi)), PARAMS);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
