package com.example.fluxio.transfer.api;

import com.example.fluxio.transfer.application.UploadApplicationService;
import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransferDtos {
    private TransferDtos() {}

    public record CreateUploadRequest(String fileName, String contentType, Long sizeBytes) {
        String normalizedContentType() {
            return contentType == null || contentType.isBlank()
                    ? "application/octet-stream"
                    : contentType;
        }
    }

    public record UploadSession(
            UUID uploadId,
            UUID fileId,
            UploadState state,
            long sizeBytes,
            long partSizeBytes,
            int partCount,
            Instant expiresAt) {
        static UploadSession from(UploadSessionRecord session) {
            return new UploadSession(
                    session.id(), session.fileId(), session.state(), session.declaredSizeBytes(),
                    session.partSizeBytes(), session.partCount(), session.expiresAt());
        }
    }

    public record SignPartsRequest(List<Integer> partNumbers) {}

    public record SignedPart(int partNumber, URI url, java.util.Map<String, String> requiredHeaders) {}

    public record SignedParts(UUID uploadId, Instant expiresAt, List<SignedPart> parts) {
        static SignedParts from(UploadApplicationService.SignedParts signed) {
            return new SignedParts(
                    signed.uploadId(), signed.expiresAt(), signed.parts().stream()
                            .map(part -> new SignedPart(part.partNumber(), part.url(), java.util.Map.of()))
                            .toList());
        }
    }

    public record UploadedPart(int partNumber, String etag, long sizeBytes) {}

    public record UploadedParts(UUID uploadId, List<UploadedPart> parts) {
        static UploadedParts from(
                UUID uploadId,
                List<com.example.fluxio.transfer.storage.MultipartObjectStore.UploadedPart> parts) {
            return new UploadedParts(uploadId, parts.stream()
                    .map(part -> new UploadedPart(part.partNumber(), part.etag(), part.sizeBytes()))
                    .toList());
        }
    }
}
