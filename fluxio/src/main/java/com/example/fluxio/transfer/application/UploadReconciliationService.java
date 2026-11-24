package com.example.fluxio.transfer.application;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.util.UUID;

public final class UploadReconciliationService {
    private final UploadRepository uploads;
    private final MultipartObjectStore storage;

    public UploadReconciliationService(UploadRepository uploads, MultipartObjectStore storage) {
        this.uploads = uploads;
        this.storage = storage;
    }

    public StoredFileRecord reconcile(UUID uploadId, String ownerId) {
        var existing = uploads.findFileByUpload(uploadId, ownerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var session = uploads.findByOwner(uploadId, ownerId).orElseThrow(UploadNotFoundException::new);
        if (session.state() != UploadState.COMPLETING) {
            throw new InvalidUploadStateException();
        }
        var object = storage.headObject(session.objectKey());
        if (object.sizeBytes() != session.declaredSizeBytes()) {
            throw new InvalidCompletionException();
        }
        String version = object.versionId() == null ? object.etag() : object.versionId();
        return uploads.finalizeCompletion(uploadId, object.etag(), version);
    }
}
