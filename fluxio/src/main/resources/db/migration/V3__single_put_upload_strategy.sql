alter table upload_session
    add column strategy varchar(16) not null default 'MULTIPART'
        check (strategy in ('MULTIPART', 'SINGLE_PUT'));

alter table upload_session alter column part_size_bytes drop not null;
alter table upload_session alter column part_count drop not null;

alter table upload_session add constraint upload_session_strategy_fields_check check (
    (strategy = 'MULTIPART' and part_size_bytes is not null and part_count is not null)
    or
    (strategy = 'SINGLE_PUT' and storage_upload_id is null
        and part_size_bytes is null and part_count is null)
);
