package com.example.fluxio.transfer.application;

import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import com.example.fluxio.transfer.persistence.UploadStrategy;
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
            if (session.strategy() == UploadStrategy.SINGLE_PUT) {
                var found = storage.findObject(session.objectKey());
                if (found.isPresent()) {
                    var object = found.get();
                    if (object.sizeBytes() == session.declaredSizeBytes()
                            && uploads.claimCompletion(session.id(), session.ownerId())) {
                        String version = object.versionId() == null ? object.etag() : object.versionId();
                        uploads.finalizeCompletion(session.id(), object.etag(), version);
                        expired++;
                        continue;
                    }
                    if (uploads.claimExpiry(session.id())) {
                        storage.deleteObject(session.objectKey());
                        expired++;
                    }
                } else if (uploads.claimExpiry(session.id())) expired++;
                continue;
            }
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
