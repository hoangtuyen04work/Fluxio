package com.example.fluxio.transfer.api;

import org.springframework.http.HttpStatus;

final class TransferContractException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    TransferContractException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
