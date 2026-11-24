package com.example.fluxio.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.CreateUploadRecord;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final UUID UPLOAD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void completesConsecutiveAuthoritativePartsAndFinalizesFileExactlyOnce() {
        var repository = new LifecycleRepository();
        var storage = new LifecycleStore();
        var service = service(repository, storage);

        var file = service.complete("alice", UPLOAD_ID, List.of(
                new UploadApplicationService.CompletionPart(1, "etag-1", 8, null),
                new UploadApplicationService.CompletionPart(2, "etag-2", 2, null)));

        assertThat(file.fileId()).isEqualTo(repository.session.fileId());
        assertThat(file.fileName()).isEqualTo("report.zip");
        assertThat(repository.finalizeCalls).isEqualTo(1);
        assertThat(service.complete("alice", UPLOAD_ID, List.of(
                new UploadApplicationService.CompletionPart(1, "etag-1", 8, null),
                new UploadApplicationService.CompletionPart(2, "etag-2", 2, null))))
                .isEqualTo(file);
        assertThat(repository.finalizeCalls).isEqualTo(1);
    }

    @Test
    void rejectsNonConsecutiveOrStorageMismatchedCompletion() {
        var service = service(new LifecycleRepository(), new LifecycleStore());

        assertThatThrownBy(() -> service.complete("alice", UPLOAD_ID, List.of(
                new UploadApplicationService.CompletionPart(2, "etag-2", 10, null))))
                .isInstanceOf(InvalidCompletionException.class);
        assertThatThrownBy(() -> service.complete("alice", UPLOAD_ID, List.of(
                new UploadApplicationService.CompletionPart(1, "wrong", 8, null),
                new UploadApplicationService.CompletionPart(2, "etag-2", 2, null))))
                .isInstanceOf(InvalidCompletionException.class);
    }

    @Test
    void abortsStorageAndReleasesReservationOnlyOnce() {
        var repository = new LifecycleRepository();
        var storage = new LifecycleStore();
        var service = service(repository, storage);

        service.abort("alice", UPLOAD_ID);
        service.abort("alice", UPLOAD_ID);

        assertThat(storage.abortCalls).isEqualTo(1);
        assertThat(repository.abortCalls).isEqualTo(1);
    }

    private static UploadApplicationService service(LifecycleRepository repository, LifecycleStore storage) {
        return new UploadApplicationService(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC), 8);
    }

    private static final class LifecycleRepository implements UploadRepository {
        private UploadSessionRecord session = new UploadSessionRecord(
                UPLOAD_ID, UUID.fromString("20000000-0000-0000-0000-000000000002"), "alice",
                "owners/alice/files/2", "storage-1", "hash", "report.zip", "application/zip",
                10, 8, 2, UploadState.ACTIVE, NOW.plusSeconds(3600), NOW, NOW);
        private StoredFileRecord file;
        private int finalizeCalls;
        private int abortCalls;

        @Override public boolean claimCompletion(UUID id, String owner) {
            if (session.state() != UploadState.ACTIVE) return false;
            session = withState(UploadState.COMPLETING);
            return true;
        }
        @Override public StoredFileRecord finalizeCompletion(UUID id, String etag, String version) {
            finalizeCalls++;
            file = new StoredFileRecord(session.fileId(), session.id(), session.ownerId(), session.objectKey(),
                    session.fileName(), session.contentType(), session.declaredSizeBytes(), version, etag, NOW);
            session = withState(UploadState.COMPLETED);
            return file;
        }
        @Override public Optional<StoredFileRecord> findFileByUpload(UUID id, String owner) {
            return file != null && owner.equals(session.ownerId()) ? Optional.of(file) : Optional.empty();
        }
        @Override public boolean claimAbort(UUID id, String owner) {
            if (session.state() != UploadState.ACTIVE) return false;
            abortCalls++;
            session = withState(UploadState.ABORTED);
            return true;
        }
        private UploadSessionRecord withState(UploadState state) {
            return new UploadSessionRecord(session.id(), session.fileId(), session.ownerId(), session.objectKey(),
                    session.storageUploadId(), session.requestHash(), session.fileName(), session.contentType(),
                    session.declaredSizeBytes(), session.partSizeBytes(), session.partCount(), state,
                    session.expiresAt(), session.createdAt(), NOW);
        }
        @Override public UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command) { throw new UnsupportedOperationException(); }
        @Override public boolean activate(UUID id, String storageId, Instant expiry) { return false; }
        @Override public boolean convertReservationToActive(UUID id) { return false; }
        @Override public boolean releaseReservation(UUID id) { return false; }
        @Override public Optional<UploadSessionRecord> findByOwner(UUID id, String owner) {
            return id.equals(session.id()) && owner.equals(session.ownerId()) ? Optional.of(session) : Optional.empty();
        }
    }

    private static final class LifecycleStore implements MultipartObjectStore {
        private int abortCalls;
        @Override public List<UploadedPart> listParts(String key, String id) {
            return List.of(new UploadedPart(1, "etag-1", 8), new UploadedPart(2, "etag-2", 2));
        }
        @Override public CompletedObject complete(String key, String id, List<CompletedPart> parts) {
            return new CompletedObject("object-etag", "version-1");
        }
        @Override public void abort(String key, String id) { abortCalls++; }
        @Override public String createMultipart(String key, String type) { throw new UnsupportedOperationException(); }
        @Override public URI presignUploadPart(String key, String id, int part) { throw new UnsupportedOperationException(); }
        @Override public ObjectMetadata headObject(String key) { return new ObjectMetadata(10, "object-etag", "version-1"); }
        @Override public boolean multipartExists(String key, String id) { return true; }
    }
}
