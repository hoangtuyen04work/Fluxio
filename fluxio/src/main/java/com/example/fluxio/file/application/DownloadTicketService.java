package com.example.fluxio.file.application;

import com.example.fluxio.transfer.application.UploadNotFoundException;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class DownloadTicketService {
    private static final Duration TICKET_LIFETIME = Duration.ofMinutes(15);
    private final UploadRepository files;
    private final MultipartObjectStore storage;
    private final Clock clock;

    public DownloadTicketService(UploadRepository files, MultipartObjectStore storage, Clock clock) {
        this.files = files;
        this.storage = storage;
        this.clock = clock;
    }

    public Ticket create(String ownerId, UUID fileId) {
        var file = files.findFileById(fileId, ownerId).orElseThrow(UploadNotFoundException::new);
        return new Ticket(file.fileId(), file.fileName(), file.contentType(), file.sizeBytes(),
                file.objectVersion(), file.createdAt(), storage.presignDownload(file.objectKey()),
                clock.instant().plus(TICKET_LIFETIME));
    }

    public record Ticket(
            UUID fileId, String fileName, String contentType, long sizeBytes, String objectVersion,
            Instant createdAt, URI url, Instant expiresAt) {}
}
