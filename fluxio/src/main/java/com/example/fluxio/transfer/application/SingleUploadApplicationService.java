package com.example.fluxio.transfer.application;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.CreateSingleUploadRecord;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.persistence.UploadStrategy;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public final class SingleUploadApplicationService {
    private static final Duration ACTIVE_LIFETIME = Duration.ofHours(24);
    private static final Duration URL_LIFETIME = Duration.ofMinutes(15);

    private final UploadRepository uploads;
    private final MultipartObjectStore storage;
    private final Clock clock;

    public SingleUploadApplicationService(UploadRepository uploads, MultipartObjectStore storage, Clock clock) {
        this.uploads = uploads;
        this.storage = storage;
        this.clock = clock;
    }

    public Created create(String ownerId, String fileName, String contentType, long sizeBytes) {
        UUID uploadId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        Instant expiresAt = clock.instant().plus(ACTIVE_LIFETIME);
        var session = uploads.reserveAndCreateSinglePut(new CreateSingleUploadRecord(
                uploadId, fileId, ownerId, objectKey(ownerId, fileId),
                requestHash(fileName, contentType, sizeBytes), fileName, contentType, sizeBytes, expiresAt));
        return new Created(session, storage.presignPut(session.objectKey(), session.contentType()),
                clock.instant().plus(URL_LIFETIME));
    }

    public PutTicket refreshUrl(String ownerId, UUID uploadId) {
        UploadSessionRecord session = activeSession(ownerId, uploadId);
        return new PutTicket(storage.presignPut(session.objectKey(), session.contentType()),
                clock.instant().plus(URL_LIFETIME));
    }

    public StoredFileRecord complete(String ownerId, UUID uploadId) {
        var existing = uploads.findFileByUpload(uploadId, ownerId);
        if (existing.isPresent()) return existing.get();
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        requireSinglePut(session);
        if (session.state() != UploadState.ACTIVE && session.state() != UploadState.COMPLETING) {
            throw new InvalidUploadStateException();
        }
        var object = storage.headObject(session.objectKey());
        if (object.sizeBytes() != session.declaredSizeBytes()) throw new InvalidCompletionException();
        if (session.state() == UploadState.ACTIVE && !uploads.claimCompletion(uploadId, ownerId)) {
            return uploads.findFileByUpload(uploadId, ownerId)
                    .orElseThrow(InvalidUploadStateException::new);
        }
        String version = object.versionId() == null ? object.etag() : object.versionId();
        return uploads.finalizeCompletion(uploadId, object.etag(), version);
    }

    public void abort(String ownerId, UUID uploadId) {
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        requireSinglePut(session);
        if (session.state() == UploadState.ABORTED || session.state() == UploadState.EXPIRED) return;
        if (!uploads.claimAbort(uploadId, ownerId)) throw new InvalidUploadStateException();
        storage.deleteObject(session.objectKey());
    }

    private UploadSessionRecord activeSession(String ownerId, UUID uploadId) {
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        requireSinglePut(session);
        if (!session.expiresAt().isAfter(clock.instant())) throw new UploadExpiredException();
        if (session.state() != UploadState.ACTIVE) throw new InvalidUploadStateException();
        return session;
    }

    private static void requireSinglePut(UploadSessionRecord session) {
        if (session.strategy() != UploadStrategy.SINGLE_PUT) throw new InvalidUploadStateException();
    }

    private static String objectKey(String ownerId, UUID fileId) {
        String owner = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ownerId.getBytes(StandardCharsets.UTF_8));
        return "owners/" + owner + "/files/" + fileId;
    }

    private static String requestHash(String fileName, String contentType, long sizeBytes) {
        try {
            byte[] canonical = (fileName + "\n" + contentType + "\n" + sizeBytes)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Created(UploadSessionRecord session, URI url, Instant expiresAt) {}
    public record PutTicket(URI url, Instant expiresAt) {}
}
