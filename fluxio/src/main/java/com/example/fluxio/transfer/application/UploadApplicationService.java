package com.example.fluxio.transfer.application;

import com.example.fluxio.transfer.persistence.CreateUploadRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.persistence.UploadStrategy;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class UploadApplicationService {
    private static final Duration INITIALIZATION_DEADLINE = Duration.ofMinutes(5);
    private static final Duration ACTIVE_LIFETIME = Duration.ofHours(24);
    private static final Duration PART_URL_LIFETIME = Duration.ofMinutes(15);
    private static final int MAX_SIGNED_PARTS = 32;

    private final UploadRepository uploads;
    private final MultipartObjectStore storage;
    private final Clock clock;
    private final long partSizeBytes;

    public UploadApplicationService(
            UploadRepository uploads,
            MultipartObjectStore storage,
            Clock clock,
            long partSizeBytes) {
        this.uploads = uploads;
        this.storage = storage;
        this.clock = clock;
        this.partSizeBytes = partSizeBytes;
    }

    public UploadSessionRecord create(
            String ownerId,
            String fileName,
            String contentType,
            long sizeBytes) {
        UUID uploadId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        String objectKey = objectKey(ownerId, fileId);
        Instant now = clock.instant();
        int partCount = Math.toIntExact((sizeBytes + partSizeBytes - 1) / partSizeBytes);
        var command = new CreateUploadRecord(
                uploadId,
                fileId,
                ownerId,
                objectKey,
                requestHash(fileName, contentType, sizeBytes),
                fileName,
                contentType,
                sizeBytes,
                partSizeBytes,
                partCount,
                now.plus(INITIALIZATION_DEADLINE));
        UploadSessionRecord initializing = uploads.reserveAndCreateInitializing(command);

        String storageUploadId = null;
        try {
            storageUploadId = storage.createMultipart(objectKey, contentType);
            Instant activeExpiry = now.plus(ACTIVE_LIFETIME);
            if (!uploads.activate(uploadId, storageUploadId, activeExpiry)) {
                throw new IllegalStateException("Upload left INITIALIZING before activation");
            }
            return uploads.findByOwner(uploadId, ownerId).orElseThrow();
        } catch (RuntimeException exception) {
            if (storageUploadId != null) {
                try {
                    storage.abort(objectKey, storageUploadId);
                } catch (RuntimeException abortFailure) {
                    exception.addSuppressed(abortFailure);
                }
            }
            uploads.releaseReservation(initializing.id());
            throw new StorageInitializationException("Could not initialize multipart storage", exception);
        }
    }

    public SignedParts signParts(String ownerId, UUID uploadId, List<Integer> partNumbers) {
        UploadSessionRecord session = activeSession(ownerId, uploadId);
        if (partNumbers == null || partNumbers.isEmpty() || partNumbers.size() > MAX_SIGNED_PARTS
                || new HashSet<>(partNumbers).size() != partNumbers.size()
                || partNumbers.stream().anyMatch(part -> part == null || part < 1 || part > session.partCount())) {
            throw new InvalidPartRequestException();
        }

        List<SignedPart> parts = partNumbers.stream()
                .map(part -> new SignedPart(
                        part,
                        storage.presignUploadPart(session.objectKey(), session.storageUploadId(), part)))
                .toList();
        return new SignedParts(uploadId, clock.instant().plus(PART_URL_LIFETIME), parts);
    }

    public List<MultipartObjectStore.UploadedPart> listParts(String ownerId, UUID uploadId) {
        UploadSessionRecord session = activeSession(ownerId, uploadId);
        return storage.listParts(session.objectKey(), session.storageUploadId());
    }

    public com.example.fluxio.transfer.persistence.StoredFileRecord complete(
            String ownerId, UUID uploadId, List<CompletionPart> requestedParts) {
        var existing = uploads.findFileByUpload(uploadId, ownerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        requireMultipart(session);
        if (session.state() == com.example.fluxio.transfer.domain.UploadState.COMPLETING) {
            var object = storage.headObject(session.objectKey());
            if (object.sizeBytes() != session.declaredSizeBytes()) {
                throw new InvalidCompletionException();
            }
            String version = object.versionId() == null ? object.etag() : object.versionId();
            return uploads.finalizeCompletion(uploadId, object.etag(), version);
        }
        validateCompletion(session, requestedParts);
        if (!uploads.claimCompletion(uploadId, ownerId)) {
            return uploads.findFileByUpload(uploadId, ownerId)
                    .orElseThrow(InvalidUploadStateException::new);
        }
        var completed = storage.complete(
                session.objectKey(), session.storageUploadId(), requestedParts.stream()
                        .map(part -> new MultipartObjectStore.CompletedPart(part.partNumber(), part.etag()))
                        .toList());
        String version = completed.versionId() == null ? completed.etag() : completed.versionId();
        return uploads.finalizeCompletion(uploadId, completed.etag(), version);
    }

    public void abort(String ownerId, UUID uploadId) {
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        requireMultipart(session);
        if (session.state() == com.example.fluxio.transfer.domain.UploadState.ABORTED
                || session.state() == com.example.fluxio.transfer.domain.UploadState.EXPIRED) {
            return;
        }
        if (session.state() == com.example.fluxio.transfer.domain.UploadState.COMPLETING) {
            throw new InvalidUploadStateException();
        }
        if (!uploads.claimAbort(uploadId, ownerId)) {
            throw new InvalidUploadStateException();
        }
        if (session.storageUploadId() != null) {
            storage.abort(session.objectKey(), session.storageUploadId());
        }
    }

    private void validateCompletion(UploadSessionRecord session, List<CompletionPart> requestedParts) {
        if (session.state() != com.example.fluxio.transfer.domain.UploadState.ACTIVE
                || requestedParts == null || requestedParts.size() != session.partCount()) {
            throw new InvalidCompletionException();
        }
        long total = 0;
        for (int index = 0; index < requestedParts.size(); index++) {
            CompletionPart requested = requestedParts.get(index);
            if (requested.partNumber() != index + 1 || requested.sizeBytes() < 1) {
                throw new InvalidCompletionException();
            }
            total += requested.sizeBytes();
        }
        if (total != session.declaredSizeBytes()) {
            throw new InvalidCompletionException();
        }
        List<MultipartObjectStore.UploadedPart> stored = storage.listParts(
                session.objectKey(), session.storageUploadId());
        if (stored.size() != requestedParts.size()) {
            throw new InvalidCompletionException();
        }
        for (int index = 0; index < stored.size(); index++) {
            var actual = stored.get(index);
            var requested = requestedParts.get(index);
            if (actual.partNumber() != requested.partNumber()
                    || !actual.etag().equals(requested.etag())
                    || actual.sizeBytes() != requested.sizeBytes()) {
                throw new InvalidCompletionException();
            }
        }
    }

    private UploadSessionRecord activeSession(String ownerId, UUID uploadId) {
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        requireMultipart(session);
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new UploadExpiredException();
        }
        if (session.state() != com.example.fluxio.transfer.domain.UploadState.ACTIVE) {
            throw new InvalidUploadStateException();
        }
        return session;
    }

    private static void requireMultipart(UploadSessionRecord session) {
        if (session.strategy() != UploadStrategy.MULTIPART) throw new InvalidUploadStateException();
    }

    private static String objectKey(String ownerId, UUID fileId) {
        String owner = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ownerId.getBytes(StandardCharsets.UTF_8));
        return "owners/" + owner + "/files/" + fileId;
    }

    private static String requestHash(String fileName, String contentType, long sizeBytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] canonical = (fileName + "\n" + contentType + "\n" + sizeBytes)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record SignedPart(int partNumber, java.net.URI url) {}

    public record SignedParts(UUID uploadId, Instant expiresAt, List<SignedPart> parts) {}

    public record CompletionPart(int partNumber, String etag, long sizeBytes, String checksumSha256) {}
}
