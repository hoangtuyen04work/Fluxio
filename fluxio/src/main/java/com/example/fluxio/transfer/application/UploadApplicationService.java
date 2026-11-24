package com.example.fluxio.transfer.application;

import com.example.fluxio.transfer.persistence.CreateUploadRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
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

    private UploadSessionRecord activeSession(String ownerId, UUID uploadId) {
        UploadSessionRecord session = uploads.findByOwner(uploadId, ownerId)
                .orElseThrow(UploadNotFoundException::new);
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new UploadExpiredException();
        }
        if (session.state() != com.example.fluxio.transfer.domain.UploadState.ACTIVE) {
            throw new InvalidUploadStateException();
        }
        return session;
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
}
