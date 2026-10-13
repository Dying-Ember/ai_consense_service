package com.consense.service.sources;

import org.springframework.http.HttpStatus;

public final class CanonicalEvidenceException extends RuntimeException {
    private final HttpStatus status;
    public CanonicalEvidenceException(HttpStatus status,String reason) {super(reason);this.status=status;}
    public HttpStatus getStatus(){return status;}
}
