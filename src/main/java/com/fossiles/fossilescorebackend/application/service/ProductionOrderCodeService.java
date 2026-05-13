package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductionOrderCodeService {

    private final ProductionOrderRepository productionOrderRepository;

    public String generateNextCode(String orderType) throws BusinessException {
        String prefix = getOrderCodePrefix(orderType);
        if (prefix == null) {
            throw new BusinessException("No existe prefijo configurado para el tipo de orden: " + orderType);
        }
        int next = getNextCorrelative(prefix);
        return prefix + "-" + next;
    }

    public String getOrderCodePrefix(String orderType) {
        String normalizedType = String.valueOf(orderType == null ? "" : orderType).trim().toUpperCase();
        switch (normalizedType) {
            case "NORMAL":
                return "OPK";
            case "MARCAS":
            case "OPV":
                return "OPV";
            case "INTERNA":
                return "OPI";
            case "CINCHOS":
                return "OPC";
            case "CINCHOS_FOSSILES":
                return "OPCF";
            case "CINCHOS_MARCAS":
                return "OPCM";
            case "DISTRIBUTION":
                return "OPD";
            case "VENTA_EN_LINEA":
                return "OPL";
            case "CLIENTE_KIOSKO":
                return "OPCK";
            default:
                return null;
        }
    }

    public int getNextCorrelative(String prefix) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(prefix) + "-(\\d+)$");
        return productionOrderRepository.findAll().stream()
                .map(ProductionOrderEntity::getCode)
                .filter(Objects::nonNull)
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElse(0) + 1;
    }
}
