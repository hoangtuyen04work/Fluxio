package com.example.fluxio.file.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.fluxio.file.application.DownloadTicketService;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DownloadTicketControllerTest {
    @Test
    void returnsOwnerScopedRangedDownloadTicket() throws Exception {
        UUID fileId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var service = mock(DownloadTicketService.class);
        when(service.create("alice", fileId)).thenReturn(new DownloadTicketService.Ticket(
                fileId, "report.zip", "application/zip", 10, "version-1",
                Instant.parse("2026-11-24T08:00:00Z"),
                URI.create("http://storage/object?signature=x"), Instant.parse("2026-11-24T09:15:00Z")));
        var mvc = MockMvcBuilders.standaloneSetup(new DownloadTicketController(service)).build();

        mvc.perform(post("/api/v1/files/{fileId}/download-ticket", fileId)
                        .principal(() -> "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file.fileId").value(fileId.toString()))
                .andExpect(jsonPath("$.file.objectVersion").value("version-1"))
                .andExpect(jsonPath("$.rangeSupported").value(true))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-24T09:15:00Z"));
    }
}
