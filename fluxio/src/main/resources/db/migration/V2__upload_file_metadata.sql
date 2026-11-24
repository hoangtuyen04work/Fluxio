alter table upload_session add column file_name varchar(255);
alter table upload_session add column content_type varchar(255);

update upload_session
set file_name = 'unknown', content_type = 'application/octet-stream'
where file_name is null or content_type is null;

alter table upload_session alter column file_name set not null;
alter table upload_session alter column content_type set not null;
