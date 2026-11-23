# Spec Delta

## MODIFIED Requirements

### Requirement: Direct data plane

File bytes SHALL transfer directly between Android and private MinIO storage through short-lived presigned URLs; the backend SHALL NOT proxy file bodies.

#### Scenario: Upload encrypted object

- **WHEN** the Android client receives an authorized upload or download URL
- **THEN** it transfers the requested bytes directly with MinIO

## ADDED Requirements

### Requirement: Multipart transfer tickets

The service SHALL create an upload session, issue presigned URLs only for authorized part numbers, report confirmed parts, complete an ordered part set, and abort incomplete sessions.

#### Scenario: Request URLs for missing parts

- **WHEN** an authenticated owner requests URLs for valid missing part numbers in an active session
- **THEN** the service returns short-lived presigned PUT URLs scoped to those parts

#### Scenario: Complete an upload

- **WHEN** all required consecutive parts are confirmed and completion is requested
- **THEN** the service commits the multipart upload and creates the completed file metadata record exactly once
