-- YashanDB does not support CREATE TABLE IF NOT EXISTS (it is silently ignored as
-- a no-op). Use plain CREATE TABLE instead and rely on spring.sql.init.continue-on-error=true
-- to suppress "table already exists" errors on subsequent application restarts.
create table extensions
(
    name    varchar2(255) not null,
    data    blob,
    version number,
    constraint pk_extensions primary key (name)
);

-- r2dbc-migrate internal tables.  These are normally created by r2dbc-migrate itself, but
-- its createInternalTables() batch also uses CREATE TABLE IF NOT EXISTS which fails silently
-- on YashanDB (same driver limitation).  We pre-create them here so r2dbc-migrate's
-- ensureInternals() step only needs to run the idempotent lock-row INSERT.
create table migrations
(
    id          number primary key,
    description varchar2(4000)
);

create table migrations_lock
(
    id     number not null,
    locked number(1) not null,
    constraint pk_migrations_lock primary key (id)
);
