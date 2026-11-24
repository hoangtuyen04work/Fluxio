package com.example.fluxio.file.api;

import com.example.fluxio.file.application.DownloadTicketService;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
@ConditionalOnBean(DownloadTicketService.class)
public final class DownloadTicketController {
    private final DownloadTicketService tickets;

    public DownloadTicketController(DownloadTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/{fileId}/download-ticket")
    public DownloadTicket create(Principal principal, @PathVariable UUID fileId) {
        var ticket = tickets.create(principal.getName(), fileId);
        return new DownloadTicket(
                new FileMetadata(ticket.fileId(), ticket.fileName(), ticket.contentType(), ticket.sizeBytes(),
                        ticket.objectVersion(), ticket.createdAt()),
                ticket.url().toString(), ticket.expiresAt(), true);
    }

    public record FileMetadata(
            UUID fileId, String fileName, String contentType, long sizeBytes,
            String objectVersion, Instant createdAt) {}
    public record DownloadTicket(FileMetadata file, String url, Instant expiresAt, boolean rangeSupported) {}
}
