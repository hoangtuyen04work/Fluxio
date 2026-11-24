package com.example.fluxio.transfer.storage;

import java.time.Duration;

public record StorageProperties(
        String endpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        Duration apiTimeout,
        Duration presignDuration) {
    public StorageProperties(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String bucket,
            Duration apiTimeout,
            Duration presignDuration) {
        this(endpoint, endpoint, region, accessKey, secretKey, bucket, apiTimeout, presignDuration);
    }
}
