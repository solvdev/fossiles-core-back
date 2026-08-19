package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskQuantityChunkerTest {

    @Test
    void defaultIsTwoWhenNullOrInvalid() {
        assertThat(TaskQuantityChunker.resolveUnitsPerTask(null)).isEqualTo(2);
        assertThat(TaskQuantityChunker.resolveUnitsPerTask(0)).isEqualTo(2);
        assertThat(TaskQuantityChunker.resolveUnitsPerTask(-3)).isEqualTo(2);
        assertThat(TaskQuantityChunker.resolveUnitsPerTask(4)).isEqualTo(4);
    }

    @Test
    void splitsFiveWithDefaultTwo() {
        assertThat(TaskQuantityChunker.splitQuantity(5, 2)).containsExactly(2, 2, 1);
        assertThat(TaskQuantityChunker.splitQuantity(5, null)).containsExactly(2, 2, 1);
    }

    @Test
    void splitsNineWithFour() {
        assertThat(TaskQuantityChunker.splitQuantity(9, 4)).containsExactly(4, 4, 1);
    }

    @Test
    void emptyWhenNothingRemaining() {
        assertThat(TaskQuantityChunker.splitQuantity(0, 2)).isEmpty();
        assertThat(TaskQuantityChunker.splitQuantity(-1, 2)).isEmpty();
    }
}
