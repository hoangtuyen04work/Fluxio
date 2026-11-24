package com.example.fluxio.transfer.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3MultipartObjectStoreIT {
    private static final String ACCESS_KEY = "local-access";
    private static final String SECRET_KEY = "local-secret";
    private static final String BUCKET = "fluxio-test";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
                    "bitnamilegacy/minio:latest")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(9000);

    private MultipartObjectStore store;

    @BeforeAll
    void createBucket() {
        var properties = new StorageProperties(
                endpoint(), Region.US_EAST_1.id(), ACCESS_KEY, SECRET_KEY, BUCKET,
                Duration.ofSeconds(10), Duration.ofMinutes(15));
        try (S3Client setup = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build()) {
            setup.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
        store = new S3MultipartObjectStore(properties);
    }

    @Test
    void createsUploadsPartsListsAndCompletesMultipartObject() throws Exception {
        String key = "owners/alice/upload.bin";
        String uploadId = store.createMultipart(key, "application/octet-stream");
        byte[] first = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(first, (byte) 'a');
        byte[] second = "second-part".getBytes(StandardCharsets.UTF_8);

        String firstEtag = put(store.presignUploadPart(key, uploadId, 1), first);
        String secondEtag = put(store.presignUploadPart(key, uploadId, 2), second);

        assertThat(store.listParts(key, uploadId))
                .extracting(MultipartObjectStore.UploadedPart::partNumber)
                .containsExactly(1, 2);

        store.complete(key, uploadId, List.of(
                new MultipartObjectStore.CompletedPart(1, firstEtag),
                new MultipartObjectStore.CompletedPart(2, secondEtag)));

        var object = store.headObject(key);
        assertThat(object.sizeBytes()).isEqualTo(first.length + second.length);
        assertThat(object.etag()).isNotBlank();
    }

    @Test
    void abortRemovesMultipartUpload() {
        String key = "owners/alice/aborted.bin";
        String uploadId = store.createMultipart(key, "application/octet-stream");

        store.abort(key, uploadId);

        assertThat(store.multipartExists(key, uploadId)).isFalse();
    }

    @Test
    void signsDataPlaneUrlsWithPublicEndpoint() {
        var properties = new StorageProperties(
                endpoint(), "http://10.0.2.2:9000", Region.US_EAST_1.id(),
                ACCESS_KEY, SECRET_KEY, BUCKET,
                Duration.ofSeconds(10), Duration.ofMinutes(15));
        try (var publicStore = new S3MultipartObjectStore(properties)) {
            String key = "owners/alice/public-url.bin";
            String uploadId = publicStore.createMultipart(key, "application/octet-stream");

            assertThat(publicStore.presignUploadPart(key, uploadId, 1))
                    .hasHost("10.0.2.2").hasPort(9000);
            assertThat(publicStore.presignDownload(key))
                    .hasHost("10.0.2.2").hasPort(9000);

            publicStore.abort(key, uploadId);
        }
    }

    private String put(URI signedUrl, byte[] body) throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(signedUrl)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("etag").orElseThrow().replace("\"", "");
    }

    private String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
