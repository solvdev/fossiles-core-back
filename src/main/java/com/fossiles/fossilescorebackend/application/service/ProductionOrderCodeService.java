package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProductionOrderCodeService {

    private final ProductionOrderRepository productionOrderRepository;

    /** Prefijos históricos de cinchos; el correlativo OPC-* debe continuar sin chocar con OPCF/OPCM existentes. */
    private static final List<String> CINCHO_CODE_PREFIXES = Arrays.asList("OPC", "OPCF", "OPCM");

    public String generateNextCode(String orderType) throws BusinessException {
        return generateNextCode(orderType, null);
    }

    public String generateNextCode(String orderType, String sellerName) throws BusinessException {
        String prefix = getOrderCodePrefix(orderType, sellerName);
        if (prefix == null) {
            throw new BusinessException("No existe prefijo configurado para el tipo de orden: " + orderType);
        }
        int next = "OPC".equals(prefix)
                ? getNextCorrelativeAcrossPrefixes(CINCHO_CODE_PREFIXES)
                : getNextCorrelative(prefix);
        return prefix + "-" + next;
    }

    public String getOrderCodePrefix(String orderType) {
        return getOrderCodePrefix(orderType, null);
    }

    public String getOrderCodePrefix(String orderType, String sellerName) {
        String normalizedType = String.valueOf(orderType == null ? "" : orderType).trim().toUpperCase();
        String normalizedSeller = String.valueOf(sellerName == null ? "" : sellerName).trim().toUpperCase();
        if ("NORMAL".equals(normalizedType) && normalizedSeller.contains("LUIS FELIPE")) {
            return "OPV";
        }
        switch (normalizedType) {
            case "NORMAL":
                return "OPK";
            case "MARCAS":
            case "OPV":
                return "OPV";
            case "INTERNA":
                return "OPI";
            case "CINCHOS":
            case "CINCHOS_FOSSILES":
            case "CINCHOS_MARCAS":
                return "OPC";
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
        return maxNumericSuffixForPrefix(prefix) + 1;
    }

    private int maxNumericSuffixForPrefix(String prefix) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(prefix) + "-(\\d+)$");
        return productionOrderRepository.findAll().stream()
                .map(ProductionOrderEntity::getCode)
                .filter(Objects::nonNull)
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                .max()
                .orElse(0);
    }

    private int getNextCorrelativeAcrossPrefixes(List<String> prefixes) {
        int max = 0;
        for (String p : prefixes) {
            max = Math.max(max, maxNumericSuffixForPrefix(p));
        }
        return max + 1;
    }
}
