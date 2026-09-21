package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Una página del listado del Organizador.
 *
 * <p>Misma forma que el resto de listados paginados del proyecto, para que el front no
 * tenga que aprenderse un formato distinto por pantalla.
 *
 * <p>Ojo con {@code totalElements}: cuenta las órdenes que pasaron el filtro, pero una
 * orden cuyas líneas sean todas de cincho se descarta después de recortar la página, así
 * que una página puede traer menos filas que {@code size}. Para saber si quedan más,
 * usar {@code last}, nunca restar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizerOrderPageResponse {

    private List<OrganizerProductionOrderResponse> content;
    private long totalElements;
    private int totalPages;
    private int size;
    private int number;
    private boolean first;
    private boolean last;

    public static OrganizerOrderPageResponse of(List<OrganizerProductionOrderResponse> content,
                                                long totalElements,
                                                int totalPages,
                                                int size,
                                                int number) {
        return OrganizerOrderPageResponse.builder()
                .content(content)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .size(size)
                .number(number)
                .first(number == 0)
                .last(number >= totalPages - 1)
                .build();
    }
}
