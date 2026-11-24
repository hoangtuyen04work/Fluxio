package com.example.fluxio.transfer.storage;

import java.time.Duration;

public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        Duration apiTimeout,
        Duration presignDuration) {}
