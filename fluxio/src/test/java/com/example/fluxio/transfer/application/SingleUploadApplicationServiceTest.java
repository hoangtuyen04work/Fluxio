package com.example.fluxio.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fluxio.transfer.domain.UploadState;
import com.example.fluxio.transfer.persistence.CreateSingleUploadRecord;
import com.example.fluxio.transfer.persistence.StoredFileRecord;
import com.example.fluxio.transfer.persistence.UploadRepository;
import com.example.fluxio.transfer.persistence.UploadSessionRecord;
import com.example.fluxio.transfer.persistence.UploadStrategy;
import com.example.fluxio.transfer.storage.MultipartObjectStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SingleUploadApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

    @Test
    void createsActiveSessionWithDirectPutTicket() {
        var repository = new FakeRepository();
        var storage = new FakeStorage();
        var service = new SingleUploadApplicationService(repository, storage, fixedClock());

        var result = service.create("alice", "report.bin", "application/octet-stream", 100);

        assertThat(result.session().strategy()).isEqualTo(UploadStrategy.SINGLE_PUT);
        assertThat(result.session().state()).isEqualTo(UploadState.ACTIVE);
        assertThat(result.url()).isEqualTo(URI.create("http://storage/whole"));
    }

    @Test
    void completionRejectsObjectWithDifferentSize() {
        var repository = new FakeRepository();
        var storage = new FakeStorage();
        var service = new SingleUploadApplicationService(repository, storage, fixedClock());
        var created = service.create("alice", "report.bin", "application/octet-stream", 100);
        storage.metadata = new MultipartObjectStore.ObjectMetadata(99, "etag", "version");

        assertThatThrownBy(() -> service.complete("alice", created.session().id()))
                .isInstanceOf(InvalidCompletionException.class);
        assertThat(repository.session.state()).isEqualTo(UploadState.ACTIVE);
    }

    @Test
    void completesExactSizeObjectExactlyOnce() {
        var repository = new FakeRepository();
        var storage = new FakeStorage();
        var service = new SingleUploadApplicationService(repository, storage, fixedClock());
        var created = service.create("alice", "report.bin", "application/octet-stream", 100);

        var first = service.complete("alice", created.session().id());
        var replay = service.complete("alice", created.session().id());

        assertThat(replay).isEqualTo(first);
        assertThat(repository.finalizeCalls).isEqualTo(1);
    }

    @Test
    void abortDeletesObjectAndReleasesReservation() {
        var repository = new FakeRepository();
        var storage = new FakeStorage();
        var service = new SingleUploadApplicationService(repository, storage, fixedClock());
        var created = service.create("alice", "report.bin", "application/octet-stream", 100);

        service.abort("alice", created.session().id());

        assertThat(storage.deleted).isTrue();
        assertThat(repository.session.state()).isEqualTo(UploadState.ABORTED);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class FakeRepository implements UploadRepository {
        UploadSessionRecord session;
        StoredFileRecord file;
        int finalizeCalls;

        @Override public UploadSessionRecord reserveAndCreateInitializing(
                com.example.fluxio.transfer.persistence.CreateUploadRecord command) {
            throw new UnsupportedOperationException();
        }

        @Override public UploadSessionRecord reserveAndCreateSinglePut(CreateSingleUploadRecord command) {
            session = new UploadSessionRecord(command.id(), command.fileId(), command.ownerId(),
                    command.objectKey(), null, command.requestHash(), command.fileName(), command.contentType(),
                    command.declaredSizeBytes(), null, null, UploadState.ACTIVE, command.expiresAt(), NOW, NOW,
                    UploadStrategy.SINGLE_PUT);
            return session;
        }

        @Override public boolean activate(UUID id, String storageId, Instant expiresAt) { return false; }
        @Override public boolean convertReservationToActive(UUID id) { return false; }
        @Override public boolean releaseReservation(UUID id) { return false; }
        @Override public Optional<UploadSessionRecord> findByOwner(UUID id, String owner) {
            return session != null && session.id().equals(id) && session.ownerId().equals(owner)
                    ? Optional.of(session) : Optional.empty();
        }
        @Override public boolean claimCompletion(UUID id, String owner) {
            if (session.state() != UploadState.ACTIVE) return false;
            session = copy(UploadState.COMPLETING);
            return true;
        }
        @Override public StoredFileRecord finalizeCompletion(UUID id, String etag, String version) {
            finalizeCalls++;
            file = new StoredFileRecord(session.fileId(), session.id(), session.ownerId(), session.objectKey(),
                    session.fileName(), session.contentType(), session.declaredSizeBytes(), version, etag, NOW);
            session = copy(UploadState.COMPLETED);
            return file;
        }
        @Override public Optional<StoredFileRecord> findFileByUpload(UUID id, String owner) {
            return Optional.ofNullable(file);
        }
        @Override public boolean claimAbort(UUID id, String owner) {
            if (session.state() != UploadState.ACTIVE) return false;
            session = copy(UploadState.ABORTED);
            return true;
        }
        private UploadSessionRecord copy(UploadState state) {
            return new UploadSessionRecord(session.id(), session.fileId(), session.ownerId(), session.objectKey(),
                    null, session.requestHash(), session.fileName(), session.contentType(), session.declaredSizeBytes(),
                    null, null, state, session.expiresAt(), session.createdAt(), NOW, UploadStrategy.SINGLE_PUT);
        }
    }

    private static final class FakeStorage implements MultipartObjectStore {
        ObjectMetadata metadata = new ObjectMetadata(100, "etag", "version");
        boolean deleted;
        @Override public URI presignPut(String key, String contentType) { return URI.create("http://storage/whole"); }
        @Override public void deleteObject(String key) { deleted = true; }
        @Override public ObjectMetadata headObject(String key) { return metadata; }
        @Override public String createMultipart(String key, String type) { throw new UnsupportedOperationException(); }
        @Override public URI presignUploadPart(String key, String id, int part) { throw new UnsupportedOperationException(); }
        @Override public List<UploadedPart> listParts(String key, String id) { return List.of(); }
        @Override public CompletedObject complete(String key, String id, List<CompletedPart> parts) { throw new UnsupportedOperationException(); }
        @Override public void abort(String key, String id) { throw new UnsupportedOperationException(); }
        @Override public boolean multipartExists(String key, String id) { return false; }
    }
}
