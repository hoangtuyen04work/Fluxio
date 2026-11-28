package com.example.fluxio.transfer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.fluxio.transfer.application.IdempotencyService;
import com.example.fluxio.transfer.application.SingleUploadApplicationService;
import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.persistence.UploadStrategy;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class SingleUploadControllerTest {
    private static final String KEY = "key-123456789012";
    private static final UUID UPLOAD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private SingleUploadApplicationService uploads;
    private IdempotencyService idempotency;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        uploads = mock(SingleUploadApplicationService.class);
        idempotency = mock(IdempotencyService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new SingleUploadController(uploads, idempotency, JsonMapper.builder().build()))
                .setControllerAdvice(new TransferProblemHandler()).build();
    }

    @Test
    void createsSingleUploadWithPutTicket() throws Exception {
        when(idempotency.claim(eq("alice"), eq("CREATE_SINGLE_UPLOAD"), eq(KEY), any(), any()))
                .thenReturn(new IdempotencyService.Claim(IdempotencyService.Status.NEW, null, null));
        when(uploads.create("alice", "report.bin", "application/octet-stream", 100))
                .thenReturn(new SingleUploadApplicationService.Created(session(),
                        URI.create("http://storage/whole"), Instant.parse("2026-11-28T00:15:00Z")));

        mvc.perform(post("/api/v1/single-uploads").principal(() -> "alice")
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"report.bin\",\"sizeBytes\":100}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/single-uploads/" + UPLOAD_ID))
                .andExpect(jsonPath("$.put.url").value("http://storage/whole"));
    }

    @Test
    void replayRefreshesExpiredPutCapability() throws Exception {
        String stored = "{\"uploadId\":\"" + UPLOAD_ID + "\",\"fileId\":\"" + FILE_ID
                + "\",\"state\":\"ACTIVE\",\"sizeBytes\":100,\"put\":{\"url\":\"http://storage/stale\","
                + "\"expiresAt\":\"2026-11-28T00:01:00Z\",\"requiredHeaders\":{}}}";
        when(idempotency.claim(eq("alice"), eq("CREATE_SINGLE_UPLOAD"), eq(KEY), any(), any()))
                .thenReturn(new IdempotencyService.Claim(IdempotencyService.Status.REPLAY, 201, stored));
        when(uploads.refreshUrl("alice", UPLOAD_ID)).thenReturn(new SingleUploadApplicationService.PutTicket(
                URI.create("http://storage/fresh"), Instant.parse("2026-11-28T00:15:00Z")));

        mvc.perform(post("/api/v1/single-uploads").principal(() -> "alice")
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"report.bin\",\"sizeBytes\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.put.url").value("http://storage/fresh"));
    }

    @Test
    void refreshesWholePutUrlAndAborts() throws Exception {
        when(uploads.refreshUrl("alice", UPLOAD_ID)).thenReturn(
                new SingleUploadApplicationService.PutTicket(
                        URI.create("http://storage/fresh"), Instant.parse("2026-11-28T00:15:00Z")));
        mvc.perform(post("/api/v1/single-uploads/{id}/url", UPLOAD_ID).principal(() -> "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").value("http://storage/fresh"));
        mvc.perform(delete("/api/v1/single-uploads/{id}", UPLOAD_ID).principal(() -> "alice"))
                .andExpect(status().isNoContent());
    }

    @Test
    void completesSingleUploadThroughIdempotencyBoundary() throws Exception {
        when(idempotency.claim(eq("alice"), eq("COMPLETE_SINGLE_UPLOAD"), eq(KEY), any(), any()))
                .thenReturn(new IdempotencyService.Claim(IdempotencyService.Status.NEW, null, null));
        when(uploads.complete("alice", UPLOAD_ID)).thenReturn(new StoredFileRecord(
                FILE_ID, UPLOAD_ID, "alice", "object", "report.bin", "application/octet-stream",
                100, "version", "etag", Instant.parse("2026-11-28T00:00:00Z")));

        mvc.perform(post("/api/v1/single-uploads/{id}/complete", UPLOAD_ID).principal(() -> "alice")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fileId").value(FILE_ID.toString()));

        verify(idempotency).complete(eq("alice"), eq("COMPLETE_SINGLE_UPLOAD"), eq(KEY), eq(200), any());
    }

    private static UploadSessionRecord session() {
        Instant now = Instant.parse("2026-11-28T00:00:00Z");
        return new UploadSessionRecord(UPLOAD_ID, FILE_ID, "alice", "owners/alice/files/2", null,
                "hash", "report.bin", "application/octet-stream", 100, null, null,
                UploadState.ACTIVE, Instant.parse("2026-11-29T00:00:00Z"), now, now,
                UploadStrategy.SINGLE_PUT);
    }
}
