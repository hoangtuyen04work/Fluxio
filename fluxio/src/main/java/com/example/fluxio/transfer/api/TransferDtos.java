package com.example.fluxio.transfer.api;

import com.example.fluxio.transfer.application.UploadApplicationService;
import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
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

    public record SinglePutTicket(URI url, Instant expiresAt, java.util.Map<String, String> requiredHeaders) {
        static SinglePutTicket from(
                com.example.fluxio.transfer.application.SingleUploadApplicationService.PutTicket ticket) {
            return new SinglePutTicket(ticket.url(), ticket.expiresAt(), java.util.Map.of());
        }
    }

    public record SingleUploadSession(
            UUID uploadId, UUID fileId, UploadState state, long sizeBytes, SinglePutTicket put) {
        static SingleUploadSession from(
                com.example.fluxio.transfer.application.SingleUploadApplicationService.Created created) {
            var session = created.session();
            return new SingleUploadSession(session.id(), session.fileId(), session.state(),
                    session.declaredSizeBytes(), new SinglePutTicket(
                    created.url(), created.expiresAt(), java.util.Map.of()));
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

    public record CompletionPart(int partNumber, String etag, long sizeBytes, String checksumSha256) {
        UploadApplicationService.CompletionPart toApplication() {
            return new UploadApplicationService.CompletionPart(partNumber, etag, sizeBytes, checksumSha256);
        }
    }

    public record CompleteUploadRequest(List<CompletionPart> parts) {}

    public record FileMetadata(
            UUID fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            String objectVersion,
            Instant createdAt) {
        static FileMetadata from(StoredFileRecord file) {
            return new FileMetadata(file.fileId(), file.fileName(), file.contentType(), file.sizeBytes(),
                    file.objectVersion(), file.createdAt());
        }
    }
}
