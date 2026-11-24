create table account_storage_usage (
    owner_id varchar(255) primary key,
    active_bytes bigint not null default 0 check (active_bytes >= 0),
    reserved_bytes bigint not null default 0 check (reserved_bytes >= 0),
    version bigint not null default 0,
    updated_at timestamptz not null,
    constraint account_storage_usage_quota_check
        check (active_bytes + reserved_bytes <= 21474836480)
);

create table upload_session (
    id uuid primary key,
    file_id uuid not null unique,
    owner_id varchar(255) not null references account_storage_usage(owner_id),
    object_key varchar(1024) not null unique,
    storage_upload_id varchar(1024) unique,
    request_hash varchar(128) not null,
    declared_size_bytes bigint not null check (declared_size_bytes between 1 and 5368709120),
    part_size_bytes bigint not null check (part_size_bytes > 0),
    part_count integer not null check (part_count > 0),
    state varchar(32) not null check (state in (
        'INITIALIZING', 'ACTIVE', 'COMPLETING', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    completed_object_version varchar(1024),
    expires_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index upload_session_cleanup_idx on upload_session(state, expires_at);
create index upload_session_owner_idx on upload_session(owner_id, state, created_at desc, id);

create table stored_file (
    id uuid primary key,
    source_upload_id uuid not null unique references upload_session(id),
    owner_id varchar(255) not null references account_storage_usage(owner_id),
    object_key varchar(1024) not null unique,
    file_name varchar(1024) not null,
    content_type varchar(255) not null,
    size_bytes bigint not null check (size_bytes between 1 and 5368709120),
    object_version varchar(1024) not null,
    object_etag varchar(1024) not null,
    state varchar(16) not null check (state in ('ACTIVE', 'DELETED')),
    created_at timestamptz not null,
    deleted_at timestamptz
);

create index stored_file_catalog_idx on stored_file(owner_id, state, created_at desc, id);

create table idempotency_record (
    id uuid primary key,
    upload_id uuid references upload_session(id),
    owner_id varchar(255) not null,
    operation varchar(64) not null,
    idempotency_key varchar(255) not null,
    request_hash varchar(128) not null,
    response_status integer,
    response_body jsonb,
    expires_at timestamptz not null,
    created_at timestamptz not null,
    constraint idempotency_record_scope_uk unique (owner_id, operation, idempotency_key)
);

create index idempotency_record_expiry_idx on idempotency_record(expires_at);
