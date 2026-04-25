package com.fossiles.fossilescorebackend.application.exception;

public class ResourceNotFoundException extends Exception {
    public ResourceNotFoundException(String message) {
        super(message);
    }
    
    public ResourceNotFoundException(String resource, Long id) {
        super(String.format("%s with id %d not found", resource, id));
    }
    
    public ResourceNotFoundException(String resource, String field, String value) {
        super(String.format("%s with %s '%s' not found", resource, field, value));
    }

    public ResourceNotFoundException(String documentSeries, String s) {
    }
}

