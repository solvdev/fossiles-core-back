package com.fossiles.fossilescorebackend.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPlanningLockTest {

    /**
     * El candado vive y muere con la transacción. Pedirlo sin una es pedir algo que
     * se suelta en el acto: parecería que protege y no protegería nada, que es el
     * defecto original. Mejor que reviente donde se pueda ver.
     */
    @Test
    void seNiegaAOperarSinTransaccion() {
        ProductionPlanningLock candado = new ProductionPlanningLock();

        assertThatThrownBy(candado::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transacción");
    }
}
