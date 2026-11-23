# File transfer tickets

## Purpose

Define the current control-plane contract that authorizes direct encrypted-object transfer between the authenticated Android client and MinIO.

## Requirements

### Requirement: Upload ticket creation

The service SHALL expose `POST /api/v1/mvp/files` to authenticated clients and return a new UUID plus a presigned MinIO PUT URL that expires after 30 minutes.

#### Scenario: Create an upload ticket

- **WHEN** an authenticated client requests an upload ticket
- **THEN** the service returns JSON containing a new `fileId` and a presigned `url`

### Requirement: Download ticket creation

The service SHALL expose `GET /api/v1/mvp/files/{fileId}` to authenticated clients and return the requested UUID plus a presigned MinIO GET URL that expires after 30 minutes.

#### Scenario: Create a download ticket

- **WHEN** an authenticated client supplies a syntactically valid file UUID
- **THEN** the service returns JSON containing that `fileId` and a presigned `url`

#### Scenario: Invalid file identifier

- **WHEN** the path value cannot be bound as a UUID
- **THEN** the service rejects the request as a client error and does not issue a URL

### Requirement: Subject-scoped object key

The object key SHALL be `<base64url-without-padding(subject)>/<fileId>`, where `subject` is taken from the authenticated token. A client SHALL NOT supply or override the owner prefix.

#### Scenario: Ticket object ownership

- **WHEN** the service signs either an upload or download ticket
- **THEN** the signed object key uses the authenticated subject and requested or generated file UUID

### Requirement: Signing failure contract

The service SHALL return HTTP 502 with a storage-signing error when MinIO cannot create a presigned URL.

#### Scenario: MinIO signing failure

- **WHEN** URL signing fails in the storage SDK
- **THEN** the client receives HTTP 502 and no usable transfer URL

### Requirement: Direct data plane

Encrypted file bytes SHALL transfer directly between Android and MinIO by using the presigned URL; the backend SHALL NOT proxy the file body.

#### Scenario: Upload encrypted object

- **WHEN** the Android client receives an upload ticket
- **THEN** it sends the encrypted object directly to the presigned MinIO URL
