package com.fossiles.fossilescorebackend.application.util;

/**
 * Hoja de envío de Entrecueros: el kiosco anota un número en papel y queda en la venta.
 * Con factura: {@code A45-241 - 123}. Sin factura: solo el número de hoja.
 */
public final class EntrecuerosShippingSheet {

    public static final int MAX_LENGTH = 40;

    private EntrecuerosShippingSheet() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().replaceAll("\\s+", " ");
        if (value.length() > MAX_LENGTH) {
            return value.substring(0, MAX_LENGTH);
        }
        return value;
    }

    public static String composeInternalNumber(String invoiceInternalNumber, String shippingSheetNumber) {
        String sheet = normalize(shippingSheetNumber);
        String invoice = invoiceInternalNumber == null ? "" : invoiceInternalNumber.trim();
        if (sheet == null) {
            return invoice.isBlank() ? null : invoice;
        }
        if (invoice.isBlank()) {
            return sheet;
        }
        if (invoice.contains(sheet)) {
            return invoice;
        }
        return invoice + " - " + sheet;
    }
}
