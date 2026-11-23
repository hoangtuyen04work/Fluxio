# Spec Delta

## MODIFIED Requirements

### Requirement: Transfer history and single-operation state

After a successful upload, the client SHALL remember the transfer and resulting file identifier for the server/account pair. The UI SHALL prevent duplicate execution of the same transfer action and expose progress, retry, completion, cancellation, or error state.

#### Scenario: Upload completes

- **WHEN** multipart completion succeeds
- **THEN** the returned file identifier and completed transfer state are persisted locally

#### Scenario: Operation already running

- **WHEN** the same transfer is already active
- **THEN** the client reuses or foregrounds the existing work instead of starting a duplicate transfer

## REMOVED Requirements

### Requirement: Authenticated local encryption

**Reason:** Client-side encryption is outside the performance-first scope and prevents isolated measurement of transfer architecture.

**Migration:** Upload ordinary file bytes through authenticated presigned URLs; restore encryption only through a future dedicated change.

### Requirement: FXM1 compatibility

**Reason:** FXM1 is not the target wire format for the performance branch.

**Migration:** Existing FXM1 objects are test data and SHALL be deleted before the performance release. No legacy reader or data migration is required.

### Requirement: Verify before destination write

**Reason:** GCM verification belongs to the deferred encrypted-file workflow.

**Migration:** Use transport/object checksums and object version validation for transfer correctness; do not claim cryptographic authenticity.

### Requirement: Installation-bound restore

**Reason:** New performance-branch objects are not wrapped by an installation-bound Android Keystore key.

**Migration:** New files become accessible from authorized devices. Existing installation-bound FXM1 test objects are deleted before release.
