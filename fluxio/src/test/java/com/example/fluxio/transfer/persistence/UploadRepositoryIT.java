package com.example.fluxio.transfer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadRepositoryIT {

    private static final long FIVE_GIB = 5_368_709_120L;
    private static final long TWENTY_GIB = 21_474_836_480L;
    private static final long PART_SIZE = 8L * 1024 * 1024;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("fluxio")
                    .withUsername("fluxio")
                    .withPassword("fluxio");

    private JdbcTemplate jdbc;
    private UploadRepository repository;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcUploadRepository(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void reservesTheExactFiveGiBBoundary() {
        var session = repository.reserveAndCreateInitializing(command("owner-boundary", FIVE_GIB));

        assertThat(session.declaredSizeBytes()).isEqualTo(FIVE_GIB);
        assertUsage("owner-boundary", 0, FIVE_GIB);
    }

    @Test
    void rejectsFilesLargerThanFiveGiBWithoutCreatingUsage() {
        assertThatThrownBy(() -> repository.reserveAndCreateInitializing(
                        command("owner-too-large", FIVE_GIB + 1)))
                .isInstanceOf(InvalidUploadSizeException.class);

        assertThat(jdbc.queryForObject(
                        "select count(*) from account_storage_usage where owner_id = ?",
                        Long.class,
                        "owner-too-large"))
                .isZero();
    }

    @Test
    void rejectsReservationThatWouldExceedTwentyGiB() {
        for (int index = 0; index < 4; index++) {
            repository.reserveAndCreateInitializing(command("owner-quota", FIVE_GIB));
        }

        assertThatThrownBy(() -> repository.reserveAndCreateInitializing(
                        command("owner-quota", 1)))
                .isInstanceOf(QuotaExceededException.class);
        assertUsage("owner-quota", 0, TWENTY_GIB);
    }

    @Test
    void serializesConcurrentReservationsNearQuota() throws Exception {
        repository.reserveAndCreateInitializing(command("owner-race", FIVE_GIB));
        repository.reserveAndCreateInitializing(command("owner-race", FIVE_GIB));
        repository.reserveAndCreateInitializing(command("owner-race", FIVE_GIB));
        repository.reserveAndCreateInitializing(command("owner-race", FIVE_GIB - 1));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> first = executor.submit(() -> reserveAfterBarrier("owner-race", ready, start));
            Future<Boolean> second = executor.submit(() -> reserveAfterBarrier("owner-race", ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get() ^ second.get()).isTrue();
        }

        assertUsage("owner-race", 0, TWENTY_GIB);
    }

    @Test
    void terminalTransitionsChangeAccountingExactlyOnce() {
        var completed = repository.reserveAndCreateInitializing(command("owner-terminal", 100));
        var aborted = repository.reserveAndCreateInitializing(command("owner-terminal", 200));
        repository.activate(completed.id(), "storage-completed", Instant.parse("2026-11-25T00:00:00Z"));
        repository.activate(aborted.id(), "storage-aborted", Instant.parse("2026-11-25T00:00:00Z"));

        assertThat(repository.convertReservationToActive(completed.id())).isTrue();
        assertThat(repository.convertReservationToActive(completed.id())).isFalse();
        assertThat(repository.releaseReservation(aborted.id())).isTrue();
        assertThat(repository.releaseReservation(aborted.id())).isFalse();

        assertUsage("owner-terminal", 100, 0);
    }

    @Test
    void ownerScopedLookupDoesNotExposeForeignUploads() {
        var session = repository.reserveAndCreateInitializing(command("owner-alice", 100));

        assertThat(repository.findByOwner(session.id(), "owner-alice")).contains(session);
        assertThat(repository.findByOwner(session.id(), "owner-bob")).isEmpty();
        assertThat(repository.findByOwner(UUID.randomUUID(), "owner-alice")).isEmpty();
    }

    @Test
    void createsActiveSinglePutSessionWithoutMultipartFields() {
        UUID uploadId = UUID.randomUUID();
        var session = repository.reserveAndCreateSinglePut(new CreateSingleUploadRecord(
                uploadId,
                UUID.randomUUID(),
                "owner-single",
                "owners/owner-single/files/" + uploadId,
                "request-" + uploadId,
                "fixture.bin",
                "application/octet-stream",
                100,
                Instant.parse("2026-11-25T00:00:00Z")));

        assertThat(session.strategy()).isEqualTo(UploadStrategy.SINGLE_PUT);
        assertThat(session.state()).isEqualTo(com.example.fluxio.transfer.domain.UploadState.ACTIVE);
        assertThat(session.storageUploadId()).isNull();
        assertThat(session.partSizeBytes()).isNull();
        assertThat(session.partCount()).isNull();
        assertUsage("owner-single", 0, 100);
    }

    private boolean reserveAfterBarrier(String ownerId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            repository.reserveAndCreateInitializing(command(ownerId, 1));
            return true;
        } catch (QuotaExceededException exception) {
            return false;
        }
    }

    private CreateUploadRecord command(String ownerId, long sizeBytes) {
        UUID uploadId = UUID.randomUUID();
        return new CreateUploadRecord(
                uploadId,
                UUID.randomUUID(),
                ownerId,
                "owners/" + ownerId + "/uploads/" + uploadId,
                "request-" + uploadId,
                "fixture.bin",
                "application/octet-stream",
                sizeBytes,
                PART_SIZE,
                Math.toIntExact((sizeBytes + PART_SIZE - 1) / PART_SIZE),
                Instant.parse("2026-11-25T00:00:00Z"));
    }

    private void assertUsage(String ownerId, long activeBytes, long reservedBytes) {
        var usage = jdbc.queryForMap(
                "select active_bytes, reserved_bytes from account_storage_usage where owner_id = ?",
                ownerId);
        assertThat(usage.get("active_bytes")).isEqualTo(activeBytes);
        assertThat(usage.get("reserved_bytes")).isEqualTo(reservedBytes);
    }
}
