package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.KioskPosMode;

/**
 * Dimensión de fila de stock según el kiosco: herraje, PARA, marca o sintético+marca.
 */
public final class KioscoStockDimension {

    public enum Kind {
        NONE,
        HERRAJE,
        PARA,
        MARCA,
        WALLET
    }

    private KioscoStockDimension() {
    }

    public static Kind kind(LocationEntity location, ProductEntity product) {
        if (KioscoInventoryInitRules.isPackagingProduct(product)) {
            return Kind.NONE;
        }
        if (!KioskPosMode.isEntrecueros(location)) {
            return Kind.HERRAJE;
        }
        if (KioscoInventoryInitRules.isCinchoProduct(product)) {
            return Kind.PARA;
        }
        if (KioscoInventoryInitRules.isWalletProduct(product)) {
            return Kind.WALLET;
        }
        return Kind.MARCA;
    }

    /**
     * @param allowResidual en egresos de Entrecueros permite NUEVO/VIEJO residual.
     */
    public static String resolve(
            LocationEntity location,
            ProductEntity product,
            String raw,
            boolean allowResidual
    ) throws BusinessException {
        Kind kind = kind(location, product);
        if (kind == Kind.NONE) {
            return ProductHardwareCondition.NUEVO;
        }
        if (kind == Kind.HERRAJE) {
            String hardware = ProductHardwareCondition.normalize(raw);
            if (hardware == null) {
                hardware = ProductHardwareCondition.NUEVO;
            }
            if (!ProductHardwareCondition.NUEVO.equals(hardware)
                    && !ProductHardwareCondition.VIEJO.equals(hardware)) {
                throw new BusinessException("Herraje inválido: use NUEVO o VIEJO.");
            }
            return hardware;
        }
        String residual = ProductHardwareCondition.normalize(raw);
        if (allowResidual && residual != null) {
            return residual;
        }
        if (kind == Kind.PARA) {
            String audience = ProductCinchoAudience.normalize(raw);
            if (audience == null) {
                throw new BusinessException("En Entre Cueros indique si el cincho es Niño o Dama.");
            }
            return audience;
        }
        if (kind == Kind.WALLET) {
            String wallet = ProductHardwareCondition.resolveWalletDimension(raw);
            if (wallet == null) {
                throw new BusinessException(
                        "En Entre Cueros indique la marca de la billetera. Si es sintética use SINTETICO:MARCA.");
            }
            return wallet;
        }
        String brand = ProductBrandNames.normalize(raw);
        if (brand == null) {
            throw new BusinessException(
                    "En Entre Cueros indique la marca (LEVIS, NAUTICA, TOMMY HILFIGER, LACOSTE o ABERCROMBIE).");
        }
        return brand;
    }

    /** Destino del traslado: Entrecueros exige variante; si sale de Entrecueros a otro kiosco entra NUEVO. */
    public static String remapTrasladoDestination(
            LocationEntity origin,
            LocationEntity dest,
            ProductEntity product,
            String originHardware,
            String destinationHardware
    ) throws BusinessException {
        if (KioskPosMode.isEntrecueros(dest)) {
            return resolve(dest, product, destinationHardware, false);
        }
        if (KioskPosMode.isEntrecueros(origin)) {
            return ProductHardwareCondition.NUEVO;
        }
        return resolve(dest, product, originHardware, true);
    }
}
