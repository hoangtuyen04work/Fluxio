# Account authentication

## Purpose

Define the observable MVP authentication contract used by the Android client to obtain a short-lived bearer credential for protected transfer-ticket operations.

## Requirements

### Requirement: Configured account login

The service SHALL accept credentials at `POST /api/v1/auth/login` and return an access token only when the submitted username and password match the configured account and the configured server password is non-empty.

#### Scenario: Valid credentials

- **WHEN** the client submits the configured username and password
- **THEN** the service returns a successful JSON response containing `accessToken`

#### Scenario: Invalid or disabled credentials

- **WHEN** the username or password does not match, the password is absent, or the server password is empty
- **THEN** the service returns HTTP 401 and does not issue a token

### Requirement: Short-lived stateless session

The issued access token SHALL use the authenticated username as its subject and SHALL expire one hour after issuance. Protected API requests MUST present it as a bearer token.

#### Scenario: Protected request with a valid token

- **WHEN** the client calls a transfer-ticket endpoint with a valid unexpired bearer token
- **THEN** the service authenticates the request as the token subject

#### Scenario: Missing or invalid token

- **WHEN** a client calls a protected endpoint without a valid bearer token
- **THEN** the service rejects the request as unauthorized

### Requirement: Restart invalidates sessions

The MVP SHALL treat tokens issued before a backend restart as invalid because the signing secret is process-local.

#### Scenario: Token reused after restart

- **WHEN** a client presents a token issued by a previous backend process
- **THEN** the protected request is rejected and the user must sign in again
