package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Lo que necesita la vista de novedades: a quién puede registrar el usuario, los feriados y los recargos vigentes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollIncidentWorkspaceResponse {
    /** Secciones que el usuario puede registrar: PLANTA, KIOSCOS y/o ADMINISTRACION. */
    private List<String> sections;
    private List<Person> people;
    private List<Holiday> holidays;
    /** Recargo de las horas extra (1.5 = una vez y media). */
    private BigDecimal overtimeFactor;
    /** Múltiplo del valor de la hora que se paga por cada hora trabajada en feriado. */
    private BigDecimal holidayFactor;
    private BigDecimal workdayHours;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Person {
        private Long id;
        private String fullName;
        private String position;
        private String departmentName;
        private String section;
        private Long kioskLocationId;
        private String kioskName;
        private LocalDate hireDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Holiday {
        private LocalDate date;
        private String name;
        private boolean halfDay;
        private boolean cityOnly;
    }
}
