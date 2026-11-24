package com.example.fluxio.transfer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.fluxio.transfer.application.IdempotencyService;
import com.example.fluxio.transfer.application.UploadApplicationService;
import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import java.time.Instant;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class UploadControllerTest {
    private static final String KEY = "key-123456789012";
    private static final UUID UPLOAD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FILE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private UploadApplicationService uploads;
    private IdempotencyService idempotency;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        uploads = mock(UploadApplicationService.class);
        idempotency = mock(IdempotencyService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new UploadController(uploads, idempotency, JsonMapper.builder().build()))
                .setControllerAdvice(new TransferProblemHandler())
                .build();
    }

    @Test
    void createsUploadWithLocationAndContractBody() throws Exception {
        when(idempotency.claim(eq("alice"), eq("CREATE_UPLOAD"), eq(KEY), any(), any()))
                .thenReturn(new IdempotencyService.Claim(IdempotencyService.Status.NEW, null, null));
        when(uploads.create("alice", "report.zip", "application/zip", 10_000_000))
                .thenReturn(activeSession());

        mvc.perform(post("/api/v1/uploads")
                        .principal(() -> "alice")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"report.zip\",\"contentType\":\"application/zip\",\"sizeBytes\":10000000}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/uploads/" + UPLOAD_ID))
                .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID.toString()))
                .andExpect(jsonPath("$.fileId").value(FILE_ID.toString()))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.partSizeBytes").value(8_388_608));
    }

    @Test
    void replaysStoredCreateResponseWithoutCreatingAgain() throws Exception {
        String body = "{\"uploadId\":\"10000000-0000-0000-0000-000000000001\"}";
        when(idempotency.claim(eq("alice"), eq("CREATE_UPLOAD"), eq(KEY), any(), any()))
                .thenReturn(new IdempotencyService.Claim(IdempotencyService.Status.REPLAY, 201, body));

        mvc.perform(post("/api/v1/uploads")
                        .principal(() -> "alice")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"report.zip\",\"sizeBytes\":10000000}"))
                .andExpect(status().isOk())
                .andExpect(content().json(body));
    }

    @Test
    void returnsProblemWhenIdempotencyKeyHasDifferentPayload() throws Exception {
        when(idempotency.claim(eq("alice"), eq("CREATE_UPLOAD"), eq(KEY), any(), any()))
                .thenReturn(new IdempotencyService.Claim(IdempotencyService.Status.CONFLICT, null, null));

        mvc.perform(post("/api/v1/uploads")
                        .principal(() -> "alice")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"other.zip\",\"sizeBytes\":10000000}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void signsRequestedPartsAndReturnsExplicitExpiry() throws Exception {
        Instant expiry = Instant.parse("2026-11-24T09:15:00Z");
        when(uploads.signParts("alice", UPLOAD_ID, List.of(1, 2)))
                .thenReturn(new UploadApplicationService.SignedParts(
                        UPLOAD_ID, expiry, List.of(
                                new UploadApplicationService.SignedPart(1, URI.create("http://storage/part/1")),
                                new UploadApplicationService.SignedPart(2, URI.create("http://storage/part/2")))));

        mvc.perform(post("/api/v1/uploads/{uploadId}/part-urls", UPLOAD_ID)
                        .principal(() -> "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partNumbers\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2026-11-24T09:15:00Z"))
                .andExpect(jsonPath("$.parts[0].partNumber").value(1))
                .andExpect(jsonPath("$.parts[0].url").value("http://storage/part/1"));
    }

    @Test
    void listsStorageAuthoritativeParts() throws Exception {
        when(uploads.listParts("alice", UPLOAD_ID)).thenReturn(List.of(
                new com.example.fluxio.transfer.storage.MultipartObjectStore.UploadedPart(1, "etag-1", 8_388_608)));

        mvc.perform(get("/api/v1/uploads/{uploadId}/parts", UPLOAD_ID)
                        .principal(() -> "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value(UPLOAD_ID.toString()))
                .andExpect(jsonPath("$.parts[0].etag").value("etag-1"))
                .andExpect(jsonPath("$.parts[0].sizeBytes").value(8_388_608));
    }

    private static UploadSessionRecord activeSession() {
        Instant now = Instant.parse("2026-11-24T09:00:00Z");
        return new UploadSessionRecord(
                UPLOAD_ID, FILE_ID, "alice", "owners/alice/files/2", "storage-1", "hash",
                10_000_000, 8_388_608, 2, UploadState.ACTIVE,
                Instant.parse("2026-11-25T09:00:00Z"), now, now);
    }
}
