package com.example.fluxio.transfer.api;

import com.example.fluxio.transfer.application.IdempotencyService;
import com.example.fluxio.transfer.application.UploadApplicationService;
import com.example.fluxio.transfer.application.InvalidUploadStateException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/uploads")
@ConditionalOnBean(UploadApplicationService.class)
public final class UploadController {
    private static final Duration IDEMPOTENCY_LIFETIME = Duration.ofHours(24);

    private final UploadApplicationService uploads;
    private final IdempotencyService idempotency;
    private final JsonMapper json;

    public UploadController(
            UploadApplicationService uploads,
            IdempotencyService idempotency,
            JsonMapper json) {
        this.uploads = uploads;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(
            Principal principal,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody TransferDtos.CreateUploadRequest request) throws Exception {
        validate(key, request);
        String contentType = request.normalizedContentType();
        String requestHash = hash(request.fileName(), contentType, request.sizeBytes());
        var claim = idempotency.claim(
                principal.getName(), "CREATE_UPLOAD", key, requestHash,
                Instant.now().plus(IDEMPOTENCY_LIFETIME));

        if (claim.status() == IdempotencyService.Status.CONFLICT) {
            throw new TransferContractException(
                    HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used with a different request");
        }
        if (claim.status() == IdempotencyService.Status.IN_PROGRESS) {
            throw new TransferContractException(
                    HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "The idempotent request is still in progress");
        }
        if (claim.status() == IdempotencyService.Status.REPLAY) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(claim.responseBody());
        }

        try {
            var session = uploads.create(
                    principal.getName(), request.fileName(), contentType, request.sizeBytes());
            String responseBody = json.writeValueAsString(TransferDtos.UploadSession.from(session));
            idempotency.complete(principal.getName(), "CREATE_UPLOAD", key, 201, responseBody);
            return ResponseEntity.created(java.net.URI.create("/api/v1/uploads/" + session.id()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(responseBody);
        } catch (RuntimeException exception) {
            idempotency.abandon(principal.getName(), "CREATE_UPLOAD", key);
            throw exception;
        }
    }

    @PostMapping(
            path = "/{uploadId}/part-urls",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferDtos.SignedParts signParts(
            Principal principal,
            @PathVariable UUID uploadId,
            @RequestBody TransferDtos.SignPartsRequest request) {
        return TransferDtos.SignedParts.from(
                uploads.signParts(principal.getName(), uploadId, request.partNumbers()));
    }

    @GetMapping(path = "/{uploadId}/parts", produces = MediaType.APPLICATION_JSON_VALUE)
    public TransferDtos.UploadedParts listParts(Principal principal, @PathVariable UUID uploadId) {
        return TransferDtos.UploadedParts.from(
                uploadId, uploads.listParts(principal.getName(), uploadId));
    }

    @PostMapping(
            path = "/{uploadId}/complete",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> complete(
            Principal principal,
            @PathVariable UUID uploadId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody TransferDtos.CompleteUploadRequest request) throws Exception {
        if (key == null || key.length() < 16 || key.length() > 128
                || request.parts() == null || request.parts().isEmpty()) {
            throw new TransferContractException(
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The completion request is invalid");
        }
        String canonical = json.writeValueAsString(request);
        var claim = idempotency.claim(
                principal.getName(), "COMPLETE_UPLOAD", key, hash(canonical, "", 0),
                Instant.now().plus(IDEMPOTENCY_LIFETIME));
        if (claim.status() == IdempotencyService.Status.CONFLICT) {
            throw new TransferContractException(
                    HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used with a different request");
        }
        if (claim.status() == IdempotencyService.Status.REPLAY) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(claim.responseBody());
        }
        try {
            var file = uploads.complete(principal.getName(), uploadId,
                    request.parts().stream().map(TransferDtos.CompletionPart::toApplication).toList());
            String body = json.writeValueAsString(TransferDtos.FileMetadata.from(file));
            idempotency.complete(principal.getName(), "COMPLETE_UPLOAD", key, 200, body);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (InvalidUploadStateException exception) {
            throw new TransferContractException(
                    HttpStatus.CONFLICT, "UPLOAD_COMPLETION_IN_PROGRESS", "Upload completion is in progress");
        } catch (RuntimeException exception) {
            idempotency.abandon(principal.getName(), "COMPLETE_UPLOAD", key);
            throw exception;
        }
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> abort(Principal principal, @PathVariable UUID uploadId) {
        uploads.abort(principal.getName(), uploadId);
        return ResponseEntity.noContent().build();
    }

    private static void validate(String key, TransferDtos.CreateUploadRequest request) {
        if (key == null || key.length() < 16 || key.length() > 128
                || request.fileName() == null || request.fileName().isBlank()
                || request.fileName().length() > 255 || request.sizeBytes() == null
                || request.sizeBytes() < 1) {
            throw new TransferContractException(
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The create-upload request is invalid");
        }
    }

    private static String hash(String fileName, String contentType, long sizeBytes) {
        try {
            byte[] canonical = (fileName + "\n" + contentType + "\n" + sizeBytes)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
