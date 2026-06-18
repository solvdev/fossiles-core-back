package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, Object> errors = new HashMap<>();
        Map<String, String> fieldErrors = new HashMap<>();
        
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });
        
        errors.put("timestamp", LocalDateTime.now());
        errors.put("status", HttpStatus.BAD_REQUEST.value());
        errors.put("errors", fieldErrors);
        errors.put("message", "Validation failed");
        
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFoundException(ResourceNotFoundException ex) {
        Map<String, Object> errors = new HashMap<>();
        errors.put("timestamp", LocalDateTime.now());
        errors.put("status", HttpStatus.NOT_FOUND.value());
        errors.put("message", ex.getMessage());
        
        return new ResponseEntity<>(errors, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        Map<String, Object> errors = new HashMap<>();
        errors.put("timestamp", LocalDateTime.now());
        errors.put("status", HttpStatus.BAD_REQUEST.value());
        errors.put("message", ex.getMessage());
        
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolationException(DataIntegrityViolationException ex) {
        Map<String, Object> errors = new HashMap<>();
        errors.put("timestamp", LocalDateTime.now());
        errors.put("status", HttpStatus.BAD_REQUEST.value());
        
        String message = ex.getMessage();
        if (message != null && message.contains("foreign key constraint")) {
            if (message.contains("bom_product_id_fkey")) {
                errors.put("message", "No se puede eliminar el producto porque tiene BOM(s) asociada(s). Elimine primero las BOMs asociadas antes de eliminar el producto.");
            } else if (message.contains("bom_item_bom_id_fkey")) {
                errors.put("message", "No se puede eliminar la BOM porque tiene items asociados. Elimine primero los items de la BOM.");
            } else {
                errors.put("message", "No se puede realizar esta operación porque existen registros relacionados. Elimine primero los registros asociados.");
            }
        } else if (message != null && message.contains("location_id") && message.contains("product_shipment")) {
            errors.put("message",
                    "La base de datos exige kiosko en envíos, pero esta orden va solo con dirección. "
                            + "Ejecute en PostgreSQL: ALTER TABLE product_shipment ALTER COLUMN location_id DROP NOT NULL; "
                            + "(script: scripts/migration-product-shipment-opi-null-location.sql)");
        } else if (message != null && message.contains("shipment_number")) {
            errors.put("message",
                    "Ya existe un envío con ese número en el sistema. "
                            + "Recargue la página y vuelva a generar; el envío físico usará un correlativo distinto al ENVP del documento.");
        } else if (message != null && message.contains("product_shipment_detail")) {
            errors.put("message",
                    "Hay líneas duplicadas en el envío (mismo producto/color/talla). Revise los ítems de la orden de producción.");
        } else if (message != null && message.contains("product_shipment")) {
            errors.put("message", "No se pudo guardar el envío por un conflicto en la base de datos. Recargue e intente de nuevo.");
        } else {
            errors.put("message", "No se puede realizar esta operación debido a restricciones de integridad de datos.");
        }
        
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        Map<String, Object> errors = new HashMap<>();
        errors.put("timestamp", LocalDateTime.now());
        errors.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        errors.put("message", ex.getMessage());
        
        return new ResponseEntity<>(errors, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

