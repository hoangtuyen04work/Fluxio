package com.example.fluxio.transfer.storage;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

public final class S3MultipartObjectStore implements MultipartObjectStore, AutoCloseable {
    private final StorageProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3MultipartObjectStore(StorageProperties properties) {
        this.properties = properties;
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        var service = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(service)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(properties.apiTimeout())
                        .apiCallAttemptTimeout(properties.apiTimeout())
                        .build())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(properties.publicEndpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(service)
                .build();
    }

    @Override
    public String createMultipart(String objectKey, String contentType) {
        return client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(properties.bucket()).key(objectKey).contentType(contentType).build())
                .uploadId();
    }

    @Override
    public URI presignUploadPart(String objectKey, String uploadId, int partNumber) {
        var request = UploadPartRequest.builder().bucket(properties.bucket()).key(objectKey)
                .uploadId(uploadId).partNumber(partNumber).build();
        return URI.create(presigner.presignUploadPart(UploadPartPresignRequest.builder()
                        .signatureDuration(properties.presignDuration()).uploadPartRequest(request).build())
                .url().toString());
    }

    @Override
    public List<UploadedPart> listParts(String objectKey, String uploadId) {
        return client.listPartsPaginator(ListPartsRequest.builder().bucket(properties.bucket())
                        .key(objectKey).uploadId(uploadId).build()).parts().stream()
                .map(part -> new UploadedPart(part.partNumber(), stripQuotes(part.eTag()), part.size()))
                .sorted(Comparator.comparingInt(UploadedPart::partNumber)).toList();
    }

    @Override
    public CompletedObject complete(String objectKey, String uploadId, List<CompletedPart> parts) {
        var sdkParts = parts.stream().map(part -> software.amazon.awssdk.services.s3.model.CompletedPart
                        .builder().partNumber(part.partNumber()).eTag(part.etag()).build()).toList();
        var response = client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(properties.bucket()).key(objectKey).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(sdkParts).build()).build());
        return new CompletedObject(stripQuotes(response.eTag()), response.versionId());
    }

    @Override
    public void abort(String objectKey, String uploadId) {
        client.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(properties.bucket())
                .key(objectKey).uploadId(uploadId).build());
    }

    @Override
    public ObjectMetadata headObject(String objectKey) {
        var response = client.headObject(HeadObjectRequest.builder().bucket(properties.bucket())
                .key(objectKey).build());
        return new ObjectMetadata(response.contentLength(), stripQuotes(response.eTag()), response.versionId());
    }

    @Override
    public boolean multipartExists(String objectKey, String uploadId) {
        return client.listMultipartUploads(ListMultipartUploadsRequest.builder()
                        .bucket(properties.bucket()).prefix(objectKey).build()).uploads().stream()
                .anyMatch(upload -> upload.key().equals(objectKey) && upload.uploadId().equals(uploadId));
    }

    @Override
    public URI presignDownload(String objectKey) {
        var request = GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.presignDuration()).getObjectRequest(request).build()).url().toString());
    }

    private static String stripQuotes(String value) {
        return value == null ? null : value.replace("\"", "");
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
