package com.example.fluxio.transfer.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class IdempotencyService {
    public enum Status {
        NEW,
        IN_PROGRESS,
        REPLAY,
        CONFLICT
    }

    public record Claim(Status status, Integer responseStatus, String responseBody) {
        private static Claim newClaim() {
            return new Claim(Status.NEW, null, null);
        }

        private static Claim inProgress() {
            return new Claim(Status.IN_PROGRESS, null, null);
        }

        private static Claim conflict() {
            return new Claim(Status.CONFLICT, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public Claim claim(
            String ownerId,
            String operation,
            String idempotencyKey,
            String requestHash,
            Instant expiresAt) {
        return transactions.execute(status -> claimInTransaction(
                ownerId, operation, idempotencyKey, requestHash, expiresAt));
    }

    public void complete(
            String ownerId,
            String operation,
            String idempotencyKey,
            int responseStatus,
            String responseBody) {
        int updated = jdbc.update(
                """
                update idempotency_record
                   set response_status = ?, response_body = cast(? as jsonb)
                 where owner_id = ?
                   and operation = ?
                   and idempotency_key = ?
                   and response_status is null
                """,
                responseStatus,
                responseBody,
                ownerId,
                operation,
                idempotencyKey);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim is missing or already completed");
        }
    }

    private Claim claimInTransaction(
            String ownerId,
            String operation,
            String idempotencyKey,
            String requestHash,
            Instant expiresAt) {
        int inserted = jdbc.update(
                """
                insert into idempotency_record (
                    id, owner_id, operation, idempotency_key,
                    request_hash, expires_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (owner_id, operation, idempotency_key) do nothing
                """,
                UUID.randomUUID(),
                ownerId,
                operation,
                idempotencyKey,
                requestHash,
                Timestamp.from(expiresAt),
                Timestamp.from(Instant.now()));
        if (inserted == 1) {
            return Claim.newClaim();
        }

        StoredClaim stored = jdbc.queryForObject(
                """
                select request_hash, response_status, response_body::text
                  from idempotency_record
                 where owner_id = ? and operation = ? and idempotency_key = ?
                 for update
                """,
                IdempotencyService::mapStoredClaim,
                ownerId,
                operation,
                idempotencyKey);
        if (!stored.requestHash().equals(requestHash)) {
            return Claim.conflict();
        }
        if (stored.responseStatus() == null) {
            return Claim.inProgress();
        }
        return new Claim(Status.REPLAY, stored.responseStatus(), stored.responseBody());
    }

    private static StoredClaim mapStoredClaim(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredClaim(
                resultSet.getString("request_hash"),
                resultSet.getObject("response_status", Integer.class),
                resultSet.getString("response_body"));
    }

    private record StoredClaim(String requestHash, Integer responseStatus, String responseBody) {}
}
