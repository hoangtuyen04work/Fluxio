package com.example.fluxio.transfer.persistence;

public final class InvalidUploadSizeException extends RuntimeException {
    public InvalidUploadSizeException(long sizeBytes) {
        super("Upload size must be between 1 and 5 GiB: " + sizeBytes);
    }
}
