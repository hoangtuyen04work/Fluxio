package com.example.fluxio.transfer.persistence;

import com.example.fluxio.transfer.domain.UploadState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcUploadRepository implements UploadRepository {
    static final long MAX_FILE_BYTES = 5_368_709_120L;
    static final long QUOTA_BYTES = 21_474_836_480L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcUploadRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public UploadSessionRecord reserveAndCreateInitializing(CreateUploadRecord command) {
        if (command.declaredSizeBytes() < 1 || command.declaredSizeBytes() > MAX_FILE_BYTES) {
            throw new InvalidUploadSizeException(command.declaredSizeBytes());
        }
        return transactions.execute(status -> reserve(command));
    }

    private UploadSessionRecord reserve(CreateUploadRecord command) {
        jdbc.update(
                """
                insert into account_storage_usage(owner_id, active_bytes, reserved_bytes, version, updated_at)
                values (?, 0, 0, 0, now())
                on conflict (owner_id) do nothing
                """,
                command.ownerId());

        var usage = jdbc.queryForMap(
                "select active_bytes, reserved_bytes from account_storage_usage where owner_id = ? for update",
                command.ownerId());
        long activeBytes = ((Number) usage.get("active_bytes")).longValue();
        long reservedBytes = ((Number) usage.get("reserved_bytes")).longValue();
        if (activeBytes + reservedBytes > QUOTA_BYTES - command.declaredSizeBytes()) {
            throw new QuotaExceededException(command.ownerId());
        }

        Instant now = Instant.now();
        jdbc.update(
                """
                insert into upload_session(
                    id, file_id, owner_id, object_key, request_hash, declared_size_bytes,
                    part_size_bytes, part_count, state, expires_at, created_at, updated_at,
                    file_name, content_type)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'INITIALIZING', ?, ?, ?, ?, ?)
                """,
                command.id(), command.fileId(), command.ownerId(), command.objectKey(),
                command.requestHash(), command.declaredSizeBytes(), command.partSizeBytes(),
                command.partCount(), Timestamp.from(command.expiresAt()), Timestamp.from(now),
                Timestamp.from(now), command.fileName(), command.contentType());
        jdbc.update(
                """
                update account_storage_usage
                set reserved_bytes = reserved_bytes + ?, version = version + 1, updated_at = now()
                where owner_id = ?
                """,
                command.declaredSizeBytes(), command.ownerId());
        return find(command.id());
    }

    @Override
    public boolean activate(UUID uploadId, String storageUploadId, Instant expiresAt) {
        return jdbc.update(
                        """
                        update upload_session
                        set state = 'ACTIVE', storage_upload_id = ?, expires_at = ?, updated_at = now()
                        where id = ? and state = 'INITIALIZING'
                        """,
                        storageUploadId, Timestamp.from(expiresAt), uploadId)
                == 1;
    }

    @Override
    public boolean convertReservationToActive(UUID uploadId) {
        Boolean changed = transactions.execute(status -> {
            var session = lock(uploadId);
            if (session == null || session.state() != UploadState.ACTIVE) {
                return false;
            }
            int claimed = jdbc.update(
                    "update upload_session set state = 'COMPLETED', updated_at = now() where id = ? and state = 'ACTIVE'",
                    uploadId);
            if (claimed != 1) {
                return false;
            }
            jdbc.update(
                    """
                    update account_storage_usage
                    set reserved_bytes = reserved_bytes - ?, active_bytes = active_bytes + ?,
                        version = version + 1, updated_at = now()
                    where owner_id = ?
                    """,
                    session.declaredSizeBytes(), session.declaredSizeBytes(), session.ownerId());
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    @Override
    public boolean releaseReservation(UUID uploadId) {
        Boolean changed = transactions.execute(status -> {
            var session = lock(uploadId);
            if (session == null || (session.state() != UploadState.INITIALIZING
                    && session.state() != UploadState.ACTIVE)) {
                return false;
            }
            int claimed = jdbc.update(
                    """
                    update upload_session set state = 'ABORTED', updated_at = now()
                    where id = ? and state in ('INITIALIZING', 'ACTIVE')
                    """,
                    uploadId);
            if (claimed != 1) {
                return false;
            }
            jdbc.update(
                    """
                    update account_storage_usage
                    set reserved_bytes = reserved_bytes - ?, version = version + 1, updated_at = now()
                    where owner_id = ?
                    """,
                    session.declaredSizeBytes(), session.ownerId());
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    @Override
    public Optional<UploadSessionRecord> findByOwner(UUID uploadId, String ownerId) {
        return jdbc.query(
                        "select * from upload_session where id = ? and owner_id = ?",
                        this::map,
                        uploadId,
                        ownerId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean claimCompletion(UUID uploadId, String ownerId) {
        return jdbc.update(
                """
                update upload_session set state = 'COMPLETING', updated_at = now()
                where id = ? and owner_id = ? and state = 'ACTIVE'
                """, uploadId, ownerId) == 1;
    }

    @Override
    public StoredFileRecord finalizeCompletion(UUID uploadId, String objectEtag, String objectVersion) {
        return transactions.execute(status -> {
            UploadSessionRecord session = lock(uploadId);
            if (session == null) {
                throw new IllegalStateException("Upload does not exist");
            }
            var existing = findFileByUpload(uploadId, session.ownerId());
            if (existing.isPresent()) {
                return existing.get();
            }
            if (session.state() != UploadState.COMPLETING) {
                throw new IllegalStateException("Upload is not completing");
            }
            Instant now = Instant.now();
            jdbc.update(
                    """
                    insert into stored_file(
                        id, source_upload_id, owner_id, object_key, file_name, content_type,
                        size_bytes, object_version, object_etag, state, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """,
                    session.fileId(), session.id(), session.ownerId(), session.objectKey(),
                    session.fileName(), session.contentType(), session.declaredSizeBytes(),
                    objectVersion, objectEtag, Timestamp.from(now));
            jdbc.update(
                    """
                    update account_storage_usage
                    set reserved_bytes = reserved_bytes - ?, active_bytes = active_bytes + ?,
                        version = version + 1, updated_at = now()
                    where owner_id = ?
                    """,
                    session.declaredSizeBytes(), session.declaredSizeBytes(), session.ownerId());
            jdbc.update(
                    """
                    update upload_session
                    set state = 'COMPLETED', completed_object_version = ?, updated_at = now()
                    where id = ? and state = 'COMPLETING'
                    """, objectVersion, uploadId);
            return findFileByUpload(uploadId, session.ownerId()).orElseThrow();
        });
    }

    @Override
    public Optional<StoredFileRecord> findFileByUpload(UUID uploadId, String ownerId) {
        return jdbc.query(
                """
                select * from stored_file
                where source_upload_id = ? and owner_id = ? and state = 'ACTIVE'
                """,
                (result, row) -> new StoredFileRecord(
                        result.getObject("id", UUID.class),
                        result.getObject("source_upload_id", UUID.class),
                        result.getString("owner_id"), result.getString("object_key"),
                        result.getString("file_name"), result.getString("content_type"),
                        result.getLong("size_bytes"), result.getString("object_version"),
                        result.getString("object_etag"), result.getTimestamp("created_at").toInstant()),
                uploadId, ownerId).stream().findFirst();
    }

    @Override
    public Optional<StoredFileRecord> findFileById(UUID fileId, String ownerId) {
        return jdbc.query(
                "select * from stored_file where id = ? and owner_id = ? and state = 'ACTIVE'",
                (result, row) -> new StoredFileRecord(
                        result.getObject("id", UUID.class), result.getObject("source_upload_id", UUID.class),
                        result.getString("owner_id"), result.getString("object_key"),
                        result.getString("file_name"), result.getString("content_type"),
                        result.getLong("size_bytes"), result.getString("object_version"),
                        result.getString("object_etag"), result.getTimestamp("created_at").toInstant()),
                fileId, ownerId).stream().findFirst();
    }

    @Override
    public boolean claimAbort(UUID uploadId, String ownerId) {
        Boolean changed = transactions.execute(status -> {
            UploadSessionRecord session = lock(uploadId);
            if (session == null || !session.ownerId().equals(ownerId)
                    || (session.state() != UploadState.ACTIVE && session.state() != UploadState.INITIALIZING)) {
                return false;
            }
            if (jdbc.update(
                    """
                    update upload_session set state = 'ABORTED', updated_at = now()
                    where id = ? and owner_id = ? and state in ('ACTIVE', 'INITIALIZING')
                    """, uploadId, ownerId) != 1) {
                return false;
            }
            jdbc.update(
                    """
                    update account_storage_usage
                    set reserved_bytes = reserved_bytes - ?, version = version + 1, updated_at = now()
                    where owner_id = ?
                    """, session.declaredSizeBytes(), ownerId);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    @Override
    public List<UploadSessionRecord> findExpired(Instant now, int limit) {
        return jdbc.query(
                """
                select * from upload_session
                where state in ('INITIALIZING', 'ACTIVE') and expires_at <= ?
                order by expires_at, id
                limit ?
                """, this::map, Timestamp.from(now), limit);
    }

    @Override
    public boolean claimExpiry(UUID uploadId) {
        Boolean changed = transactions.execute(status -> {
            UploadSessionRecord session = lock(uploadId);
            if (session == null || (session.state() != UploadState.ACTIVE
                    && session.state() != UploadState.INITIALIZING)) {
                return false;
            }
            if (jdbc.update(
                    """
                    update upload_session set state = 'EXPIRED', updated_at = now()
                    where id = ? and state in ('ACTIVE', 'INITIALIZING')
                    """, uploadId) != 1) {
                return false;
            }
            jdbc.update(
                    """
                    update account_storage_usage
                    set reserved_bytes = reserved_bytes - ?, version = version + 1, updated_at = now()
                    where owner_id = ?
                    """, session.declaredSizeBytes(), session.ownerId());
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private UploadSessionRecord lock(UUID uploadId) {
        return jdbc.query(
                        "select * from upload_session where id = ? for update",
                        this::map,
                        uploadId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private UploadSessionRecord find(UUID uploadId) {
        return jdbc.queryForObject("select * from upload_session where id = ?", this::map, uploadId);
    }

    private UploadSessionRecord map(ResultSet result, int rowNumber) throws SQLException {
        Timestamp expiresAt = result.getTimestamp("expires_at");
        return new UploadSessionRecord(
                result.getObject("id", UUID.class),
                result.getObject("file_id", UUID.class),
                result.getString("owner_id"),
                result.getString("object_key"),
                result.getString("storage_upload_id"),
                result.getString("request_hash"),
                result.getString("file_name"),
                result.getString("content_type"),
                result.getLong("declared_size_bytes"),
                result.getLong("part_size_bytes"),
                result.getInt("part_count"),
                UploadState.valueOf(result.getString("state")),
                expiresAt == null ? null : expiresAt.toInstant(),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }
}
