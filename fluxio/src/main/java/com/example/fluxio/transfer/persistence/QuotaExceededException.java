package com.example.fluxio.transfer.persistence;

public final class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String ownerId) {
        super("Storage quota exceeded for owner: " + ownerId);
    }
}
