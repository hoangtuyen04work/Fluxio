package com.example.fluxio.transfer.application;

import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;

public final class UploadCleanupJob {
    private final UploadRepository uploads;
    private final MultipartObjectStore storage;
    private final Clock clock;
    private final int batchSize;

    public UploadCleanupJob(UploadRepository uploads, MultipartObjectStore storage, Clock clock, int batchSize) {
        this.uploads = uploads;
        this.storage = storage;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public int runBatch() {
        int expired = 0;
        for (var session : uploads.findExpired(clock.instant(), batchSize)) {
            if (!uploads.claimExpiry(session.id())) {
                continue;
            }
            if (session.storageUploadId() != null) {
                storage.abort(session.objectKey(), session.storageUploadId());
            }
            expired++;
        }
        return expired;
    }

    @Scheduled(fixedDelayString = "${fluxio.transfer.cleanup-delay-ms:60000}")
    public void scheduledCleanup() {
        runBatch();
    }
}
