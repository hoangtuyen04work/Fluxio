package com.example.fluxio.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class IdempotencyServiceIT {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("fluxio").withUsername("fluxio").withPassword("fluxio");

    private IdempotencyService service;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        service = new IdempotencyService(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void claimsCompletesAndReplaysTheStoredResponse() {
        Instant expiresAt = Instant.parse("2026-11-25T00:00:00Z");

        assertThat(service.claim("alice", "CREATE_UPLOAD", "key-123456789012", "hash-a", expiresAt).status())
                .isEqualTo(IdempotencyService.Status.NEW);
        assertThat(service.claim("alice", "CREATE_UPLOAD", "key-123456789012", "hash-a", expiresAt).status())
                .isEqualTo(IdempotencyService.Status.IN_PROGRESS);

        service.complete("alice", "CREATE_UPLOAD", "key-123456789012", 201, "{\"uploadId\":\"u1\"}");

        var replay = service.claim("alice", "CREATE_UPLOAD", "key-123456789012", "hash-a", expiresAt);
        assertThat(replay.status()).isEqualTo(IdempotencyService.Status.REPLAY);
        assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.responseBody()).isEqualTo("{\"uploadId\": \"u1\"}");
    }

    @Test
    void rejectsReuseWithDifferentRequestHashButScopesKeysByOwnerAndOperation() {
        Instant expiresAt = Instant.parse("2026-11-25T00:00:00Z");
        service.claim("alice", "CREATE_UPLOAD", "shared-key-123456", "hash-a", expiresAt);

        assertThat(service.claim("alice", "CREATE_UPLOAD", "shared-key-123456", "hash-b", expiresAt).status())
                .isEqualTo(IdempotencyService.Status.CONFLICT);
        assertThat(service.claim("bob", "CREATE_UPLOAD", "shared-key-123456", "hash-b", expiresAt).status())
                .isEqualTo(IdempotencyService.Status.NEW);
        assertThat(service.claim("alice", "COMPLETE_UPLOAD", "shared-key-123456", "hash-b", expiresAt).status())
                .isEqualTo(IdempotencyService.Status.NEW);
    }

    @Test
    void abandonsAnUnfinishedClaimSoTheRequestCanRetry() {
        Instant expiresAt = Instant.parse("2026-11-25T00:00:00Z");
        service.claim("alice", "CREATE_UPLOAD", "retry-key-123456", "hash-a", expiresAt);

        service.abandon("alice", "CREATE_UPLOAD", "retry-key-123456");

        assertThat(service.claim("alice", "CREATE_UPLOAD", "retry-key-123456", "hash-a", expiresAt).status())
                .isEqualTo(IdempotencyService.Status.NEW);
    }
}
