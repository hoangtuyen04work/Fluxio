package com.example.fluxio.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.CreateUploadRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");

    @Test
    void createsStorageUploadAndActivatesReservedSession() {
        var repository = new RecordingRepository();
        var storage = new RecordingStore();
        var service = service(repository, storage);

        var session = service.create("alice", "report.zip", "application/zip", 10_000_000);

        assertThat(session.state()).isEqualTo(UploadState.ACTIVE);
        assertThat(session.storageUploadId()).isEqualTo("storage-upload-1");
        assertThat(storage.createdKeys).containsExactly(session.objectKey());
        assertThat(repository.released).isEmpty();
    }

    @Test
    void releasesReservationWhenStorageInitializationFails() {
        var repository = new RecordingRepository();
        var storage = new RecordingStore();
        storage.failCreate = true;
        var service = service(repository, storage);

        assertThatThrownBy(() -> service.create(
                        "alice", "report.zip", "application/zip", 10_000_000))
                .isInstanceOf(StorageInitializationException.class);

        assertThat(repository.released).containsExactly(repository.created.id());
    }

    @Test
    void signsAtMostDistinctInRangePartsForAnOwnedActiveUpload() {
        var repository = new RecordingRepository();
        var storage = new RecordingStore();
        var service = service(repository, storage);
        var session = service.create("alice", "report.zip", "application/zip", 20_000_000);

        var signed = service.signParts("alice", session.id(), List.of(1, 3));

        assertThat(signed.uploadId()).isEqualTo(session.id());
        assertThat(signed.expiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
        assertThat(signed.parts()).extracting(UploadApplicationService.SignedPart::partNumber)
                .containsExactly(1, 3);
        assertThat(signed.parts()).extracting(part -> part.url().toString())
                .containsExactly("http://storage/part/1", "http://storage/part/3");

        assertThatThrownBy(() -> service.signParts("alice", session.id(), List.of(1, 1)))
                .isInstanceOf(InvalidPartRequestException.class);
        assertThatThrownBy(() -> service.signParts("alice", session.id(), List.of(4)))
                .isInstanceOf(InvalidPartRequestException.class);
        assertThatThrownBy(() -> service.signParts(
                        "alice", session.id(), java.util.stream.IntStream.rangeClosed(1, 33).boxed().toList()))
                .isInstanceOf(InvalidPartRequestException.class);
    }

    @Test
    void listsStoragePartsAsAuthorityAndConcealsForeignUploads() {
        var repository = new RecordingRepository();
        var storage = new RecordingStore();
        var service = service(repository, storage);
        var session = service.create("alice", "report.zip", "application/zip", 20_000_000);
        storage.uploadedParts = List.of(new MultipartObjectStore.UploadedPart(2, "etag-2", 3_222_784));

        var listed = service.listParts("alice", session.id());

        assertThat(listed).containsExactlyElementsOf(storage.uploadedParts);
        assertThatThrownBy(() -> service.listParts("bob", session.id()))
                .isInstanceOf(UploadNotFoundException.class);
    }

    private UploadApplicationService service(RecordingRepository repository, RecordingStore storage) {
        return new UploadApplicationService(
                repository,
                storage,
                Clock.fixed(NOW, ZoneOffset.UTC),
                8L * 1024 * 1024);
    }

    private static final class RecordingRepository implements UploadRepository {
        private UploadSessionRecord created;
        private final List<UUID> released = new ArrayList<>();

        @Override
        public UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command) {
            created = new UploadSessionRecord(
                    command.id(), command.fileId(), command.ownerId(), command.objectKey(), null,
                    command.requestHash(), command.fileName(), command.contentType(),
                    command.declaredSizeBytes(), command.partSizeBytes(),
                    command.partCount(), UploadState.INITIALIZING, command.expiresAt(), NOW, NOW);
            return created;
        }

        @Override
        public boolean activate(UUID uploadId, String storageUploadId, Instant expiresAt) {
            created = new UploadSessionRecord(
                    created.id(), created.fileId(), created.ownerId(), created.objectKey(), storageUploadId,
                    created.requestHash(), created.fileName(), created.contentType(),
                    created.declaredSizeBytes(), created.partSizeBytes(),
                    created.partCount(), UploadState.ACTIVE, expiresAt, created.createdAt(), NOW);
            return true;
        }

        @Override public boolean convertReservationToActive(UUID uploadId) { return false; }
        @Override public boolean releaseReservation(UUID uploadId) { released.add(uploadId); return true; }
        @Override public Optional<UploadSessionRecord> findByOwner(UUID uploadId, String ownerId) {
            return created != null && created.id().equals(uploadId) && created.ownerId().equals(ownerId)
                    ? Optional.of(created) : Optional.empty();
        }
    }

    private static final class RecordingStore implements MultipartObjectStore {
        private final List<String> createdKeys = new ArrayList<>();
        private boolean failCreate;
        private List<UploadedPart> uploadedParts = List.of();

        @Override public String createMultipart(String objectKey, String contentType) {
            createdKeys.add(objectKey);
            if (failCreate) throw new IllegalStateException("storage unavailable");
            return "storage-upload-1";
        }
        @Override public URI presignUploadPart(String key, String id, int part) {
            return URI.create("http://storage/part/" + part);
        }
        @Override public List<UploadedPart> listParts(String key, String id) { return uploadedParts; }
        @Override public CompletedObject complete(String key, String id, List<CompletedPart> parts) { throw new UnsupportedOperationException(); }
        @Override public void abort(String key, String id) {}
        @Override public ObjectMetadata headObject(String key) { throw new UnsupportedOperationException(); }
        @Override public boolean multipartExists(String key, String id) { return false; }
    }
}
