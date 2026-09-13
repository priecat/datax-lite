-- DataX Console 系统库（SQLite）

PRAGMA journal_mode = WAL;

-- 系统用户
CREATE TABLE IF NOT EXISTS sys_user (
    id          TEXT PRIMARY KEY,
    account     TEXT NOT NULL,
    name        TEXT NOT NULL,
    password    TEXT NOT NULL,
    email       TEXT,
    status      TEXT DEFAULT '1',
    create_by   TEXT,
    create_date INTEGER,
    update_by   TEXT,
    update_date INTEGER,
    del_flag    TEXT DEFAULT '0'
);

-- 数据源（type 决定使用哪个 Connector：mysql / postgresql / oceanbase / dm ...）
CREATE TABLE IF NOT EXISTS sys_datasource (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    type        TEXT DEFAULT 'mysql',
    host        TEXT NOT NULL,
    port        INTEGER DEFAULT 3306,
    username    TEXT,
    password    TEXT,
    extra_params TEXT,
    -- JDBC 驱动实现类（空 = 品牌默认；驱动 jar 由 jdbc/ 目录提供）
    driver_class TEXT,
    -- 连接锚点库：PG/达梦必填
    default_db   TEXT,
    -- 类型特有字段的 JSON 载体（如 compatMode）
    props       TEXT,
    create_by   TEXT,
    create_date INTEGER,
    update_by   TEXT,
    update_date INTEGER,
    del_flag    TEXT DEFAULT '0'
);

-- 同步任务
CREATE TABLE IF NOT EXISTS sync_task (
    id                   TEXT PRIMARY KEY,
    name                 TEXT NOT NULL,
    source_datasource_id TEXT NOT NULL,
    source_database      TEXT NOT NULL,
    target_datasource_id TEXT NOT NULL,
    target_database      TEXT NOT NULL,
    config               TEXT,
    notify_config        TEXT,
    description          TEXT,
    create_by            TEXT,
    create_date          INTEGER,
    update_by            TEXT,
    update_date          INTEGER,
    del_flag             TEXT DEFAULT '0'
);

-- 执行记录（手动执行与定时执行共用）
CREATE TABLE IF NOT EXISTS sync_task_log (
    id             TEXT PRIMARY KEY,
    task_id        TEXT NOT NULL,
    task_name      TEXT,
    trigger_type   TEXT DEFAULT 'manual',
    schedule_id    TEXT,
    state          TEXT DEFAULT 'RUNNING',
    start_time     INTEGER,
    end_time       INTEGER,
    duration_ms    INTEGER,
    read_records   INTEGER DEFAULT 0,
    write_records  INTEGER DEFAULT 0,
    error_records  INTEGER DEFAULT 0,
    read_bytes     INTEGER DEFAULT 0,
    speed_record   TEXT,
    speed_byte     TEXT,
    message        TEXT,
    log_file       TEXT
);

-- 定时任务（基于 sys_schedule 表结构，适配 SQLite；新增 task_id 关联同步任务）
CREATE TABLE IF NOT EXISTS sys_schedule (
    id          TEXT PRIMARY KEY,
    name        TEXT,
    t_group     TEXT,
    expression  TEXT,
    status      TEXT DEFAULT '1',
    is_info     TEXT,
    classname   TEXT,
    task_id     TEXT,
    description TEXT,
    create_by   TEXT,
    create_date INTEGER,
    update_by   TEXT,
    update_date INTEGER,
    del_flag    TEXT DEFAULT '0'
);

-- ─────────────────────────────────────────────────────────────
-- 字段类型字典（三表）
--   sys_dict_type  : 字典类型，code 约定为 db_{brand}_field（如 db_mysql_field）
--   sys_dict_value : 字典 KV —— label 为前端显示名，value 为目标 DDL 类型
--   sys_type_map   : 类型映射规则，group_name = "{源品牌}->{目标品牌}"，
--                    source_type 为源类型基名（小写、去括号），target_value 为目标 DDL 类型
-- 系统预设随本脚本灌入（INSERT OR IGNORE 幂等）；用户可在前端继续增改。
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS sys_dict_type (
    id          TEXT PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    remark      TEXT,
    is_system   INTEGER NOT NULL DEFAULT 0,
    create_date INTEGER,
    update_date INTEGER,
    del_flag    TEXT DEFAULT '0'
);

CREATE TABLE IF NOT EXISTS sys_dict_value (
    id          TEXT PRIMARY KEY,
    dict_code   TEXT NOT NULL,
    label       TEXT NOT NULL,
    value       TEXT NOT NULL,
    sort        INTEGER DEFAULT 0,
    remark      TEXT,
    is_system   INTEGER NOT NULL DEFAULT 0,
    create_date INTEGER,
    update_date INTEGER,
    del_flag    TEXT DEFAULT '0'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dict_value ON sys_dict_value(dict_code, label);

CREATE TABLE IF NOT EXISTS sys_type_map (
    id          TEXT PRIMARY KEY,
    group_name  TEXT NOT NULL,
    source_type TEXT NOT NULL,
    target_value TEXT NOT NULL,
    target_label TEXT,
    remark      TEXT,
    is_system   INTEGER NOT NULL DEFAULT 0,
    create_date INTEGER,
    update_date INTEGER,
    del_flag    TEXT DEFAULT '0'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_type_map ON sys_type_map(group_name, source_type);

-- ── 预设：MySQL 字段类型字典 ──
INSERT OR IGNORE INTO sys_dict_type(id, code, name, is_system, create_date)
VALUES ('dict-mysql-field', 'db_mysql_field', 'MySQL 字段类型', 1, CAST(strftime('%s','now') AS INTEGER)*1000);
INSERT OR IGNORE INTO sys_dict_value(id, dict_code, label, value, sort, is_system, create_date) VALUES
    ('dvmf-01', 'db_mysql_field', 'int',       '',       1,  1, 0),
    ('dvmf-02', 'db_mysql_field', 'bigint',    '',       2,  1, 0),
    ('dvmf-03', 'db_mysql_field', 'smallint',  '',       3,  1, 0),
    ('dvmf-04', 'db_mysql_field', 'tinyint',   '',       4,  1, 0),
    ('dvmf-05', 'db_mysql_field', 'decimal',   '38,18',  5,  1, 0),
    ('dvmf-06', 'db_mysql_field', 'float',     '',       6,  1, 0),
    ('dvmf-07', 'db_mysql_field', 'double',    '',       7,  1, 0),
    ('dvmf-08', 'db_mysql_field', 'varchar',   '255',    8,  1, 0),
    ('dvmf-09', 'db_mysql_field', 'char',      '255',    9,  1, 0),
    ('dvmf-10', 'db_mysql_field', 'text',      '',      10,  1, 0),
    ('dvmf-11', 'db_mysql_field', 'date',      '',      11,  1, 0),
    ('dvmf-12', 'db_mysql_field', 'datetime',  '',      12,  1, 0),
    ('dvmf-13', 'db_mysql_field', 'timestamp', '',      13,  1, 0),
    ('dvmf-14', 'db_mysql_field', 'json',      '',      14,  1, 0),
    ('dvmf-15', 'db_mysql_field', 'blob',      '',      15,  1, 0);

-- ── 预设：PostgreSQL 字段类型字典（key=基础类型, value=默认长度，可空） ──
INSERT OR IGNORE INTO sys_dict_type(id, code, name, is_system, create_date)
VALUES ('dict-pg-field', 'db_postgresql_field', 'PostgreSQL 字段类型', 1, CAST(strftime('%s','now') AS INTEGER)*1000);
INSERT OR IGNORE INTO sys_dict_value(id, dict_code, label, value, sort, is_system, create_date) VALUES
    ('dvpf-01', 'db_postgresql_field', 'integer',                     '',      1,  1, 0),
    ('dvpf-02', 'db_postgresql_field', 'bigint',                      '',      2,  1, 0),
    ('dvpf-03', 'db_postgresql_field', 'smallint',                    '',      3,  1, 0),
    ('dvpf-04', 'db_postgresql_field', 'boolean',                     '',      4,  1, 0),
    ('dvpf-05', 'db_postgresql_field', 'numeric',                     '38,18', 5,  1, 0),
    ('dvpf-06', 'db_postgresql_field', 'real',                        '',      6,  1, 0),
    ('dvpf-07', 'db_postgresql_field', 'double precision',            '',      7,  1, 0),
    ('dvpf-08', 'db_postgresql_field', 'character varying',           '255',   8,  1, 0),
    ('dvpf-09', 'db_postgresql_field', 'text',                        '',      9,  1, 0),
    ('dvpf-10', 'db_postgresql_field', 'date',                        '',     10,  1, 0),
    ('dvpf-11', 'db_postgresql_field', 'timestamp without time zone', '',     11,  1, 0),
    ('dvpf-12', 'db_postgresql_field', 'timestamp with time zone',    '',     12,  1, 0),
    ('dvpf-13', 'db_postgresql_field', 'jsonb',                       '',     13,  1, 0),
    ('dvpf-14', 'db_postgresql_field', 'uuid',                        '',     14,  1, 0),
    ('dvpf-15', 'db_postgresql_field', 'bytea',                       '',     15,  1, 0);

-- ── 预设：postgresql -> mysql 类型映射 ──
INSERT OR IGNORE INTO sys_type_map(id, group_name, source_type, target_value, is_system, create_date) VALUES
    ('tm-pm-01', 'postgresql->mysql', 'smallint',                  'smallint',   1, 0),
    ('tm-pm-02', 'postgresql->mysql', 'integer',                   'int',        1, 0),
    ('tm-pm-03', 'postgresql->mysql', 'bigint',                    'bigint',     1, 0),
    ('tm-pm-04', 'postgresql->mysql', 'boolean',                   'tinyint(1)', 1, 0),
    ('tm-pm-05', 'postgresql->mysql', 'numeric',                   'decimal(38,18)', 1, 0),
    ('tm-pm-06', 'postgresql->mysql', 'real',                      'float',      1, 0),
    ('tm-pm-07', 'postgresql->mysql', 'double precision',          'double',     1, 0),
    ('tm-pm-08', 'postgresql->mysql', 'character varying',         'varchar(255)', 1, 0),
    ('tm-pm-09', 'postgresql->mysql', 'character',                 'char(255)',  1, 0),
    ('tm-pm-10', 'postgresql->mysql', 'text',                      'text',       1, 0),
    ('tm-pm-11', 'postgresql->mysql', 'date',                      'date',       1, 0),
    ('tm-pm-12', 'postgresql->mysql', 'time without time zone',    'time',       1, 0),
    ('tm-pm-13', 'postgresql->mysql', 'timestamp without time zone', 'datetime', 1, 0),
    ('tm-pm-14', 'postgresql->mysql', 'timestamp with time zone',  'datetime',   1, 0),
    ('tm-pm-15', 'postgresql->mysql', 'json',                      'json',       1, 0),
    ('tm-pm-16', 'postgresql->mysql', 'jsonb',                     'json',       1, 0),
    ('tm-pm-17', 'postgresql->mysql', 'uuid',                      'varchar(36)', 1, 0),
    ('tm-pm-18', 'postgresql->mysql', 'bytea',                     'blob',       1, 0);

-- ── 预设：mysql -> postgresql 类型映射 ──
INSERT OR IGNORE INTO sys_type_map(id, group_name, source_type, target_value, is_system, create_date) VALUES
    ('tm-mp-01', 'mysql->postgresql', 'tinyint',                 'smallint',                    1, 0),
    ('tm-mp-02', 'mysql->postgresql', 'smallint',                'smallint',                    1, 0),
    ('tm-mp-03', 'mysql->postgresql', 'mediumint',               'integer',                     1, 0),
    ('tm-mp-04', 'mysql->postgresql', 'int',                     'integer',                     1, 0),
    ('tm-mp-05', 'mysql->postgresql', 'integer',                 'integer',                     1, 0),
    ('tm-mp-06', 'mysql->postgresql', 'bigint',                  'bigint',                      1, 0),
    ('tm-mp-07', 'mysql->postgresql', 'bit',                     'smallint',                    1, 0),
    ('tm-mp-08', 'mysql->postgresql', 'year',                    'smallint',                    1, 0),
    ('tm-mp-09', 'mysql->postgresql', 'decimal',                 'numeric',                     1, 0),
    ('tm-mp-10', 'mysql->postgresql', 'numeric',                 'numeric',                     1, 0),
    ('tm-mp-11', 'mysql->postgresql', 'float',                   'real',                        1, 0),
    ('tm-mp-12', 'mysql->postgresql', 'double',                  'double precision',            1, 0),
    ('tm-mp-13', 'mysql->postgresql', 'real',                    'real',                        1, 0),
    ('tm-mp-14', 'mysql->postgresql', 'char',                    'character',                   1, 0),
    ('tm-mp-15', 'mysql->postgresql', 'varchar',                 'character varying',           1, 0),
    ('tm-mp-16', 'mysql->postgresql', 'tinytext',                'text',                        1, 0),
    ('tm-mp-17', 'mysql->postgresql', 'text',                    'text',                        1, 0),
    ('tm-mp-18', 'mysql->postgresql', 'mediumtext',              'text',                        1, 0),
    ('tm-mp-19', 'mysql->postgresql', 'longtext',                'text',                        1, 0),
    ('tm-mp-20', 'mysql->postgresql', 'date',                    'date',                        1, 0),
    ('tm-mp-21', 'mysql->postgresql', 'time',                    'time without time zone',      1, 0),
    ('tm-mp-22', 'mysql->postgresql', 'datetime',                'timestamp without time zone', 1, 0),
    ('tm-mp-23', 'mysql->postgresql', 'timestamp',               'timestamp without time zone', 1, 0),
    ('tm-mp-24', 'mysql->postgresql', 'json',                    'jsonb',                       1, 0),
    ('tm-mp-25', 'mysql->postgresql', 'binary',                  'bytea',                       1, 0),
    ('tm-mp-26', 'mysql->postgresql', 'varbinary',               'bytea',                       1, 0),
    ('tm-mp-27', 'mysql->postgresql', 'blob',                    'bytea',                       1, 0),
    ('tm-mp-28', 'mysql->postgresql', 'tinyblob',                'bytea',                       1, 0),
    ('tm-mp-29', 'mysql->postgresql', 'mediumblob',              'bytea',                       1, 0),
    ('tm-mp-30', 'mysql->postgresql', 'longblob',                'bytea',                       1, 0),
    ('tm-mp-31', 'mysql->postgresql', 'enum',                    'varchar(255)',                1, 0),
    ('tm-mp-32', 'mysql->postgresql', 'set',                     'varchar(255)',                1, 0);
