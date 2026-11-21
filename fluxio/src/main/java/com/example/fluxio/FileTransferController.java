package com.example.fluxio;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.Http.Method;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/mvp/files")
class FileTransferController {
    private final MinioClient storage;
    private final String bucket;

    FileTransferController(@Value("${fluxio.storage.endpoint}") String endpoint,
                           @Value("${fluxio.storage.access-key}") String accessKey,
                           @Value("${fluxio.storage.secret-key}") String secretKey,
                           @Value("${fluxio.storage.bucket}") String bucket) {
        this.storage = MinioClient.builder().endpoint(endpoint).region("us-east-1")
                .credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @PostMapping
    Transfer initiateUpload(JwtAuthenticationToken account) {
        return sign(Method.PUT, account.getName(), UUID.randomUUID());
    }

    @GetMapping("/{fileId}")
    Transfer initiateDownload(JwtAuthenticationToken account, @PathVariable UUID fileId) {
        return sign(Method.GET, account.getName(), fileId);
    }

    private Transfer sign(Method method, String owner, UUID fileId) {
        String prefix = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(owner.getBytes(StandardCharsets.UTF_8));
        try {
            String url = storage.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method).bucket(bucket).object(prefix + "/" + fileId)
                    .expiry(30 * 60).build());
            return new Transfer(fileId, url);
        } catch (MinioException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not sign storage request", e);
        }
    }

    record Transfer(UUID fileId, String url) {}
}
