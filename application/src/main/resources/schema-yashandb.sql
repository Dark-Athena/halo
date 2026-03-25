create table if not exists extensions
(
    name    varchar2(255) not null,
    data    blob,
    version number,
    constraint pk_extensions primary key (name)
);
