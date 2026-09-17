package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Una página de cualquier listado, con la misma forma que el resto de listados paginados
 * del proyecto para que el front no aprenda un formato por pantalla.
 *
 * <p>Es la versión genérica de {@code OrganizerOrderPageResponse}. Aquel se queda como
 * está: lo usa el Organizador y su documentación describe un comportamiento propio (una
 * página suya puede traer menos filas que {@code size} porque descarta cinchos después de
 * recortar). Este no tiene esa salvedad, y aquí {@code content.size()} sí coincide con lo
 * pedido salvo en la última página.
 *
 * <p>Para saber si quedan más páginas se usa {@code last}, nunca restar contadores.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int size;
    private int number;
    private boolean first;
    private boolean last;

    public static <T> PageResponse<T> of(List<T> content,
                                         long totalElements,
                                         int totalPages,
                                         int size,
                                         int number) {
        return PageResponse.<T>builder()
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
