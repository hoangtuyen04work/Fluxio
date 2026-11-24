package com.example.fluxio.transfer.domain;

public enum UploadState {
    INITIALIZING,
    ACTIVE,
    COMPLETING,
    COMPLETED,
    ABORTED,
    EXPIRED
}
