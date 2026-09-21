package com.fossiles.fossilescorebackend.infrastructure.util;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Jornada del centro de producción: lunes a viernes, 07:00–17:00, con almuerzo
 * de 13:00 a 14:00. Nueve horas efectivas al día.
 *
 * Existe porque el tiempo real de una tarea se calculaba restando fechas, y eso
 * cuenta las horas en que nadie trabaja. Los operarios no pausan la tarea al
 * irse: la dejan abierta y la retoman al día siguiente, así que de las 14:00 de
 * un día a las 09:00 del siguiente salían 19 horas cuando el trabajo fueron 5.
 *
 * Tiene un espejo en el frontend (`src/utils/productionTimeHelper.js`). Si
 * cambia el horario hay que tocar los dos.
 */
public final class ProductionShift {

    public static final LocalTime SHIFT_START = LocalTime.of(7, 0);
    public static final LocalTime SHIFT_END = LocalTime.of(17, 0);
    public static final LocalTime LUNCH_START = LocalTime.of(13, 0);
    public static final LocalTime LUNCH_END = LocalTime.of(14, 0);

    /** Tope de días recorridos: protege de fechas corruptas que harían un bucle largo. */
    private static final int MAX_DIAS = 400;

    private ProductionShift() {}

    /**
     * Sábado y domingo no son jornada. Mismo criterio de fin de semana que
     * {@link ProductionPlanningConstants#isWorkday}, pero al revés ante un nulo:
     * allí una fecha vacía significa "sin asignar" y se acepta; aquí no hay día que
     * medir, así que no aporta minutos.
     */
    public static boolean isWorkday(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * Minutos de jornada transcurridos entre dos momentos: suma solo lo que cae
     * dentro de los tramos trabajables y descarta noches, almuerzos y fines de
     * semana. Devuelve 0 si el fin no es posterior al inicio.
     *
     * Ojo: mide de punta a punta. Una tarea que queda abierta sin que nadie la
     * trabaje suma igual, porque no se guardan tramos de trabajo. Y no hay estado
     * de pausa: devolver la tarea a PENDING borra `startedAt`, así que lo hecho
     * antes se pierde en vez de sumarse.
     */
    public static long workingMinutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0L;
        }

        // Se acumulan segundos y se convierte una sola vez al final: truncar tramo a
        // tramo desviaba el total respecto al espejo de JavaScript.
        long totalSegundos = 0L;
        LocalDate dia = from.toLocalDate();
        LocalDate ultimo = to.toLocalDate();
        int vueltas = 0;

        while (!dia.isAfter(ultimo) && vueltas++ < MAX_DIAS) {
            if (isWorkday(dia)) {
                totalSegundos += segundosDelTramo(dia, SHIFT_START, LUNCH_START, from, to);
                totalSegundos += segundosDelTramo(dia, LUNCH_END, SHIFT_END, from, to);
            }
            dia = dia.plusDays(1);
        }

        return totalSegundos / 60L;
    }

    /** Solape en segundos entre [from, to] y el tramo [inicio, fin] de ese día. */
    private static long segundosDelTramo(LocalDate dia, LocalTime inicio, LocalTime fin,
                                         LocalDateTime from, LocalDateTime to) {
        LocalDateTime tramoIni = LocalDateTime.of(dia, inicio);
        LocalDateTime tramoFin = LocalDateTime.of(dia, fin);

        LocalDateTime desde = from.isAfter(tramoIni) ? from : tramoIni;
        LocalDateTime hasta = to.isBefore(tramoFin) ? to : tramoFin;

        if (!hasta.isAfter(desde)) {
            return 0L;
        }
        return Duration.between(desde, hasta).getSeconds();
    }
}
