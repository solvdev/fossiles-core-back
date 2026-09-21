package com.fossiles.fossilescorebackend.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turno único para repartir mesas.
 *
 * <p>Tres sitios reparten mesas y los tres hacen lo mismo: leen cuántas horas
 * tiene encima cada mesa cada día, deciden dónde cabe la tarea y escriben.
 * Si dos corren a la vez, los dos leen la carga vieja y los dos meten trabajo en
 * la misma mesa: esa mesa termina con el doble del cupo, o la misma línea de OP
 * sale planificada dos veces y se fabrica de más.
 *
 * <p>Un {@code ReentrantLock} no alcanza. Se suelta al salir del método, pero
 * las tareas recién creadas todavía no están confirmadas: el segundo hilo entra,
 * lee y no las ve. El candado tiene que durar hasta el commit, y además el
 * planificador casi siempre corre dentro de la transacción de quien lo llamó
 * (crear una OP, entregar cuero, autorizar un envío), así que no es él quien
 * decide cuándo se confirma.
 *
 * <p>{@code pg_advisory_xact_lock} encaja justo ahí: se toma sobre la transacción
 * en curso, sea de quien sea, y Postgres lo suelta solo al confirmar o revertir.
 * Al vivir en la base y no en el proceso, sigue sirviendo si algún día corre más
 * de una instancia del backend. No necesita tabla ni migración: es una función
 * de Postgres y la llave es un número acordado entre quienes la usan.
 */
@Component
public class ProductionPlanningLock {

    /**
     * Llave del candado. Es un número arbitrario pero fijo: lo único que importa
     * es que nadie más en la base use el mismo para otra cosa.
     */
    private static final long PLANNING_LOCK_KEY = 8_251_071_001L;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Espera el turno para repartir mesas y lo conserva hasta que la transacción
     * en curso termine.
     *
     * @throws IllegalStateException si no hay transacción. Sin ella el candado se
     *         soltaría en el acto y quedaría de adorno, que es exactamente el
     *         defecto que esto viene a corregir; vale más que se note.
     */
    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Repartir mesas exige una transacción abierta: el candado de planificación "
                            + "se libera con ella y sin transacción no protege nada.");
        }
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(:llave)")
                .setParameter("llave", PLANNING_LOCK_KEY)
                .getSingleResult();
    }
}
