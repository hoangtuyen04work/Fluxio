package com.example.fluxio.transfer.storage;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public interface MultipartObjectStore {
    String createMultipart(String objectKey, String contentType);
    URI presignUploadPart(String objectKey, String uploadId, int partNumber);
    List<UploadedPart> listParts(String objectKey, String uploadId);
    CompletedObject complete(String objectKey, String uploadId, List<CompletedPart> parts);
    void abort(String objectKey, String uploadId);
    ObjectMetadata headObject(String objectKey);
    boolean multipartExists(String objectKey, String uploadId);
    default URI presignDownload(String objectKey) { throw new UnsupportedOperationException(); }
    default URI presignPut(String objectKey, String contentType) { throw new UnsupportedOperationException(); }
    default void deleteObject(String objectKey) { throw new UnsupportedOperationException(); }
    default Optional<ObjectMetadata> findObject(String objectKey) { return Optional.of(headObject(objectKey)); }

    record UploadedPart(int partNumber, String etag, long sizeBytes) {}
    record CompletedPart(int partNumber, String etag) {}
    record CompletedObject(String etag, String versionId) {}
    record ObjectMetadata(long sizeBytes, String etag, String versionId) {}
}
