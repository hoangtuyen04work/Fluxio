package com.example.fluxio.transfer.api;

import com.example.fluxio.transfer.application.IdempotencyService;
import com.example.fluxio.transfer.application.SingleUploadApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/single-uploads")
@ConditionalOnProperty(name = "spring.datasource.url")
public final class SingleUploadController {
    private static final Duration IDEMPOTENCY_LIFETIME = Duration.ofHours(24);
    private final SingleUploadApplicationService uploads;
    private final IdempotencyService idempotency;
    private final JsonMapper json;

    public SingleUploadController(SingleUploadApplicationService uploads, IdempotencyService idempotency, JsonMapper json) {
        this.uploads = uploads;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @RequestBody TransferDtos.CreateUploadRequest request) throws Exception {
        validate(key, request);
        String contentType = request.normalizedContentType();
        String hash = hash(request.fileName() + "\n" + contentType + "\n" + request.sizeBytes());
        var claim = idempotency.claim(principal.getName(), "CREATE_SINGLE_UPLOAD", key, hash,
                Instant.now().plus(IDEMPOTENCY_LIFETIME));
        if (claim.status() == IdempotencyService.Status.CONFLICT) {
            throw new TransferContractException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used with a different request");
        }
        if (claim.status() == IdempotencyService.Status.IN_PROGRESS) {
            throw new TransferContractException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "The idempotent request is still in progress");
        }
        if (claim.status() == IdempotencyService.Status.REPLAY) {
            var previous = json.readValue(claim.responseBody(), TransferDtos.SingleUploadSession.class);
            var refreshed = TransferDtos.SinglePutTicket.from(
                    uploads.refreshUrl(principal.getName(), previous.uploadId()));
            var replay = new TransferDtos.SingleUploadSession(previous.uploadId(), previous.fileId(),
                    previous.state(), previous.sizeBytes(), refreshed);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(replay));
        }
        try {
            var created = uploads.create(principal.getName(), request.fileName(), contentType, request.sizeBytes());
            String body = json.writeValueAsString(TransferDtos.SingleUploadSession.from(created));
            idempotency.complete(principal.getName(), "CREATE_SINGLE_UPLOAD", key, 201, body);
            return ResponseEntity.created(java.net.URI.create("/api/v1/single-uploads/" + created.session().id()))
                    .contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (RuntimeException failure) {
            idempotency.abandon(principal.getName(), "CREATE_SINGLE_UPLOAD", key);
            throw failure;
        }
    }

    @PostMapping(path = "/{uploadId}/url", produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferDtos.SinglePutTicket refresh(Principal principal, @PathVariable UUID uploadId) {
        return TransferDtos.SinglePutTicket.from(uploads.refreshUrl(principal.getName(), uploadId));
    }

    @PostMapping(path = "/{uploadId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> complete(Principal principal, @PathVariable UUID uploadId,
            @RequestHeader("Idempotency-Key") String key) throws Exception {
        if (key == null || key.length() < 16 || key.length() > 128) {
            throw new TransferContractException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid idempotency key");
        }
        var claim = idempotency.claim(principal.getName(), "COMPLETE_SINGLE_UPLOAD", key,
                hash(uploadId.toString()), Instant.now().plus(IDEMPOTENCY_LIFETIME));
        if (claim.status() == IdempotencyService.Status.CONFLICT) {
            throw new TransferContractException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used with a different request");
        }
        if (claim.status() == IdempotencyService.Status.IN_PROGRESS) {
            throw new TransferContractException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "The idempotent request is still in progress");
        }
        if (claim.status() == IdempotencyService.Status.REPLAY) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(claim.responseBody());
        }
        try {
            String body = json.writeValueAsString(
                    TransferDtos.FileMetadata.from(uploads.complete(principal.getName(), uploadId)));
            idempotency.complete(principal.getName(), "COMPLETE_SINGLE_UPLOAD", key, 200, body);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (RuntimeException failure) {
            idempotency.abandon(principal.getName(), "COMPLETE_SINGLE_UPLOAD", key);
            throw failure;
        }
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> abort(Principal principal, @PathVariable UUID uploadId) {
        uploads.abort(principal.getName(), uploadId);
        return ResponseEntity.noContent().build();
    }

    private static void validate(String key, TransferDtos.CreateUploadRequest request) {
        if (key == null || key.length() < 16 || key.length() > 128 || request.fileName() == null
                || request.fileName().isBlank() || request.fileName().length() > 255
                || request.sizeBytes() == null || request.sizeBytes() < 1) {
            throw new TransferContractException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid create request");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
