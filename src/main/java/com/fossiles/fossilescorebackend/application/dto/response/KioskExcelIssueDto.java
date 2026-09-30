package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Incidencia detectada al leer/validar un Excel de ventas por kiosco. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExcelIssueDto {

    public static final String BLOCKING = "BLOCKING";
    public static final String WARNING = "WARNING";
    public static final String INFO = "INFO";

    public static final String NON_NUMERIC_CELL = "NON_NUMERIC_CELL";
    public static final String NEGATIVE_VALUE = "NEGATIVE_VALUE";
    public static final String UNMATCHED_COLUMN = "UNMATCHED_COLUMN";
    public static final String DUPLICATE_COLUMN = "DUPLICATE_COLUMN";
    public static final String TOTAL_MISMATCH = "TOTAL_MISMATCH";
    public static final String OUT_OF_MONTH_VALUE = "OUT_OF_MONTH_VALUE";
    public static final String OUTLIER = "OUTLIER";
    public static final String MISSING_COSTS = "MISSING_COSTS";
    public static final String MISSING_GOAL = "MISSING_GOAL";
    public static final String OVERLAPS_POS = "OVERLAPS_POS";
    public static final String DUPLICATE_FILE = "DUPLICATE_FILE";
    public static final String LAYOUT_ASSUMPTION = "LAYOUT_ASSUMPTION";
    public static final String FILE_ERROR = "FILE_ERROR";

    /** Identificador estable dentro del archivo ("i1", "i2", ...). Las resoluciones del commit lo referencian. */
    private String id;
    /** BLOCKING | WARNING | INFO */
    private String severity;
    private String code;
    private String message;
    private String excelName;
    private LocalDate date;
    /** Referencia A1 de la celda (p. ej. "R29"). */
    private String cell;
    private String rawValue;
    /** Valor sugerido (sólo si la celda es reparable). */
    private BigDecimal suggestion;
}
