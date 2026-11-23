# Encrypted file transfer

## Purpose

Define the observable Android behavior and compatibility constraints for selecting, encrypting, uploading, downloading, authenticating, and restoring MVP files.

## Requirements

### Requirement: Secure server endpoint validation

The Android client MUST require HTTPS for configured server URLs, except that a debuggable emulator build MAY use `http://10.0.2.2`. URLs containing embedded credentials, query parameters, or fragments MUST be rejected.

#### Scenario: Insecure production endpoint

- **WHEN** a non-debug client is given an HTTP server URL
- **THEN** sign-in is blocked with a user-visible validation error

### Requirement: Authenticated local encryption

Before upload, the Android client SHALL encrypt the selected file with a fresh random 256-bit data key and AES-GCM authenticated frames. The data key SHALL be wrapped by the installation-bound Android Keystore key.

#### Scenario: Encrypt selected file

- **WHEN** the user selects a file for upload
- **THEN** the client creates an FXM1 encrypted object whose frame authentication binds the file UUID, frame index, and plaintext size

### Requirement: FXM1 compatibility

The current MVP encrypted object SHALL use magic `FXM1`, 1 MiB data frames, a maximum of 4096 data frames, and a mandatory authenticated zero-length terminal frame. The maximum plaintext size SHALL be 4 GiB.

#### Scenario: File exceeds the format limit

- **WHEN** encryption would require more than 4096 non-empty frames
- **THEN** the upload fails before producing a valid oversized object

#### Scenario: Missing terminal frame or trailing data

- **WHEN** a downloaded object lacks the authenticated terminal frame or contains bytes after it
- **THEN** restoration fails as an invalid encrypted object

### Requirement: Verify before destination write

The Android client MUST authenticate the entire downloaded FXM1 object before opening and writing plaintext to the user-selected destination.

#### Scenario: Tampered encrypted object

- **WHEN** any wrapped key, frame ciphertext, tag, size, order, UUID binding, or terminal framing fails authentication
- **THEN** no plaintext is written to the destination document and the user sees an operation failure

### Requirement: Installation-bound restore

A file encrypted by the MVP SHALL be restorable only by an installation that retains the Android Keystore key used to wrap its data key.

#### Scenario: Restore after application key loss

- **WHEN** the encrypted object is downloaded by an installation without the required wrapping key
- **THEN** restoration fails without creating a replacement wrapping key

### Requirement: Transfer history and single-operation state

After a successful upload, the client SHALL remember the most recent file UUID for the server URL and account pair. The UI SHALL execute at most one login or transfer task at a time and expose progress or error text.

#### Scenario: Upload completes

- **WHEN** encrypted upload succeeds
- **THEN** the returned UUID becomes the current file ID and is persisted for that server/account pair

#### Scenario: Operation already running

- **WHEN** the user attempts another operation while one is in progress
- **THEN** the client does not start a concurrent task
