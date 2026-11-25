package com.dylan.store;

final class AuditSchema {

    private AuditSchema() {
    }

    static final String[] TABLES = {
            """
            CREATE TABLE IF NOT EXISTS hack_log (
              seq        INTEGER           NOT NULL PRIMARY KEY AUTOINCREMENT,
              m_id       INT               NOT NULL DEFAULT 0,
              charac_no  INT               NOT NULL DEFAULT 0,
              hack_type  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              parameter1 INT               NOT NULL DEFAULT 0,
              parameter2 INT               NOT NULL DEFAULT 0,
              cnt        INT UNSIGNED      NOT NULL DEFAULT 0,
              detail     VARCHAR(255)      NOT NULL DEFAULT '',
              client_ip  VARCHAR(45)       NOT NULL DEFAULT '',
              source     VARCHAR(16)       NOT NULL DEFAULT 'server',
              occ_time   DATETIME          NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS item_history (
              seq            INTEGER           NOT NULL PRIMARY KEY AUTOINCREMENT,
              occ_time       DATETIME(3)       NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now','localtime')),
              charac_no      INT               NOT NULL DEFAULT 0,
              charac_name    VARCHAR(64)       NOT NULL DEFAULT '',
              reason         SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              inven_type     TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              slot_no        SMALLINT          NOT NULL DEFAULT -1,
              item_id        INT UNSIGNED      NOT NULL DEFAULT 0,
              item_count     INT               NOT NULL DEFAULT 0,
              add_info       INT UNSIGNED      NOT NULL DEFAULT 0,
              endurance      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              upgrade        TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              enchant        INT UNSIGNED      NOT NULL DEFAULT 0,
              amplify_option TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              amplify_value  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              seal_cnt       TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              random_option  VARCHAR(32)       NOT NULL DEFAULT '',
              guid           VARCHAR(32)       NOT NULL DEFAULT '',
              item_x_pos     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              item_y_pos     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              gold_after     BIGINT UNSIGNED   NOT NULL DEFAULT 0,
              coin_col       TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              coin_after     INT UNSIGNED      NOT NULL DEFAULT 0
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS abnormal_players (
              m_id             INTEGER      NOT NULL PRIMARY KEY,
              user_id          VARCHAR(30)  NOT NULL DEFAULT '',
              last_charac_no   INT          NOT NULL DEFAULT 0,
              last_charac_name VARCHAR(64)  NOT NULL DEFAULT '',
              total_points     INT          NOT NULL DEFAULT 0,
              hit_count        INT          NOT NULL DEFAULT 0,
              last_hack_type   INT          NOT NULL DEFAULT 0,
              last_detail      VARCHAR(255) NOT NULL DEFAULT '',
              last_ip          VARCHAR(45)  NOT NULL DEFAULT '',
              first_occ_time   DATETIME     NOT NULL DEFAULT (datetime('now','localtime')),
              last_occ_time    DATETIME     NOT NULL DEFAULT (datetime('now','localtime')),
              status           TINYINT      NOT NULL DEFAULT 0,
              reviewer         VARCHAR(32)  NOT NULL DEFAULT '',
              review_note      VARCHAR(255) NOT NULL DEFAULT '',
              reviewed_at      DATETIME     DEFAULT NULL
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS dungeon_run_log (
              seq              INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
              occ_time         DATETIME     NOT NULL DEFAULT (datetime('now','localtime')),
              m_id             INTEGER      NOT NULL DEFAULT 0,
              charac_no        INT          NOT NULL DEFAULT 0,
              charac_name      VARCHAR(64)  NOT NULL DEFAULT '',
              dungeon_id       INT          NOT NULL DEFAULT 0,
              dungeon_name     VARCHAR(128) NOT NULL DEFAULT '',
              difficulty       INT          NOT NULL DEFAULT 0,
              result           VARCHAR(16)  NOT NULL DEFAULT '',
              clear_rank       VARCHAR(8)   NOT NULL DEFAULT '',
              duration_ms      BIGINT       NOT NULL DEFAULT 0,
              rooms_entered    INT          NOT NULL DEFAULT 0,
              monsters_spawned INT          NOT NULL DEFAULT 0,
              monsters_killed  INT          NOT NULL DEFAULT 0,
              kills_by_type    VARCHAR(64)  NOT NULL DEFAULT '',
              exp_gained       BIGINT       NOT NULL DEFAULT 0,
              max_damage       INT          NOT NULL DEFAULT 0,
              hack_points      INT          NOT NULL DEFAULT 0,
              clear_cubes_used INT          NOT NULL DEFAULT 0,
              is_hell          INTEGER      NOT NULL DEFAULT 0,
              hell_difficulty  INT          NOT NULL DEFAULT 0,
              enter_time       VARCHAR(32)  NOT NULL DEFAULT '',
              leave_time       VARCHAR(32)  NOT NULL DEFAULT '',
              kill_detail_json TEXT         NOT NULL DEFAULT '[]',
              room_trace_json  TEXT         NOT NULL DEFAULT '[]',
              behavior_json    TEXT         NOT NULL DEFAULT '{}',
              party_size       INT          NOT NULL DEFAULT 1,
              party_members    VARCHAR(128) NOT NULL DEFAULT '',
              client_ip        VARCHAR(45)  NOT NULL DEFAULT ''
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS trade_log (
              seq           INTEGER     NOT NULL PRIMARY KEY AUTOINCREMENT,
              occ_time      DATETIME    NOT NULL DEFAULT (datetime('now','localtime')),
              a_m_id        INTEGER     NOT NULL DEFAULT 0,
              a_charac_no   INT         NOT NULL DEFAULT 0,
              a_charac_name VARCHAR(64) NOT NULL DEFAULT '',
              a_gold        BIGINT      NOT NULL DEFAULT 0,
              a_items_json  TEXT        NOT NULL DEFAULT '[]',
              b_m_id        INTEGER     NOT NULL DEFAULT 0,
              b_charac_no   INT         NOT NULL DEFAULT 0,
              b_charac_name VARCHAR(64) NOT NULL DEFAULT '',
              b_gold        BIGINT      NOT NULL DEFAULT 0,
              b_items_json  TEXT        NOT NULL DEFAULT '[]'
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS cera_log (
              seq        INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
              occ_time   DATETIME     NOT NULL DEFAULT (datetime('now','localtime')),
              m_id       INTEGER      NOT NULL DEFAULT 0,
              delta      BIGINT       NOT NULL DEFAULT 0,
              after_cera BIGINT       NOT NULL DEFAULT 0,
              source     VARCHAR(24)  NOT NULL DEFAULT '',
              ref_id     VARCHAR(64)  NOT NULL DEFAULT '',
              operator   VARCHAR(64)  NOT NULL DEFAULT ''
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS chat_log (
              seq         INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
              occ_time    DATETIME     NOT NULL DEFAULT (datetime('now','localtime')),
              m_id        INTEGER      NOT NULL DEFAULT 0,
              user_id     VARCHAR(64)  NOT NULL DEFAULT '',
              charac_no   INT          NOT NULL DEFAULT 0,
              charac_name VARCHAR(64)  NOT NULL DEFAULT '',
              channel     VARCHAR(32)  NOT NULL DEFAULT '',
              target_name VARCHAR(64)  NOT NULL DEFAULT '',
              content     TEXT         NOT NULL DEFAULT '',
              client_ip   VARCHAR(45)  NOT NULL DEFAULT ''
            )
            """,
    };

    static final String[] INDEXES = {
            "CREATE INDEX IF NOT EXISTS hack_log_idx_m_id ON hack_log (m_id, occ_time)",
            "CREATE INDEX IF NOT EXISTS hack_log_idx_charac ON hack_log (charac_no, occ_time)",
            "CREATE INDEX IF NOT EXISTS hack_log_idx_type ON hack_log (hack_type, occ_time)",
            "CREATE INDEX IF NOT EXISTS hack_log_idx_ip ON hack_log (client_ip, occ_time)",
            "CREATE INDEX IF NOT EXISTS item_history_idx_charac ON item_history (charac_no, occ_time)",
            "CREATE INDEX IF NOT EXISTS item_history_idx_item ON item_history (item_id, occ_time)",
            "CREATE INDEX IF NOT EXISTS item_history_idx_reason ON item_history (reason, occ_time)",
            "CREATE INDEX IF NOT EXISTS abnormal_players_idx_status ON abnormal_players (status, total_points DESC)",
            "CREATE INDEX IF NOT EXISTS dungeon_run_idx_charac ON dungeon_run_log (charac_no, occ_time)",
            "CREATE INDEX IF NOT EXISTS dungeon_run_idx_m_id ON dungeon_run_log (m_id, occ_time)",
            "CREATE INDEX IF NOT EXISTS dungeon_run_idx_dungeon ON dungeon_run_log (dungeon_id, occ_time)",
            "CREATE INDEX IF NOT EXISTS dungeon_run_idx_result ON dungeon_run_log (result, occ_time)",
            "CREATE INDEX IF NOT EXISTS chat_log_idx_time ON chat_log (occ_time)",
            "CREATE INDEX IF NOT EXISTS chat_log_idx_m_id ON chat_log (m_id, occ_time)",
            "CREATE INDEX IF NOT EXISTS chat_log_idx_charac ON chat_log (charac_no, occ_time)",
            "CREATE INDEX IF NOT EXISTS chat_log_idx_channel ON chat_log (channel, occ_time)",
            "CREATE INDEX IF NOT EXISTS trade_log_idx_a ON trade_log (a_charac_no, occ_time)",
            "CREATE INDEX IF NOT EXISTS trade_log_idx_b ON trade_log (b_charac_no, occ_time)",
            "CREATE INDEX IF NOT EXISTS cera_log_idx_m_id ON cera_log (m_id, occ_time)",
            "CREATE INDEX IF NOT EXISTS cera_log_idx_source ON cera_log (source, occ_time)",
    };

    static final String[] MYSQL_TABLES = {
            """
            CREATE TABLE IF NOT EXISTS hack_log (
              seq        BIGINT            NOT NULL AUTO_INCREMENT PRIMARY KEY,
              m_id       BIGINT            NOT NULL DEFAULT 0,
              charac_no  INT               NOT NULL DEFAULT 0,
              hack_type  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              parameter1 INT               NOT NULL DEFAULT 0,
              parameter2 INT               NOT NULL DEFAULT 0,
              cnt        INT UNSIGNED      NOT NULL DEFAULT 0,
              detail     VARCHAR(255)      NOT NULL DEFAULT '',
              client_ip  VARCHAR(45)       NOT NULL DEFAULT '',
              source     VARCHAR(16)       NOT NULL DEFAULT 'server',
              occ_time   DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
              KEY hack_log_idx_m_id (m_id, occ_time),
              KEY hack_log_idx_charac (charac_no, occ_time),
              KEY hack_log_idx_type (hack_type, occ_time),
              KEY hack_log_idx_ip (client_ip, occ_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            """
            CREATE TABLE IF NOT EXISTS item_history (
              seq            BIGINT            NOT NULL AUTO_INCREMENT PRIMARY KEY,
              occ_time       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              charac_no      INT               NOT NULL DEFAULT 0,
              charac_name    VARCHAR(64)       NOT NULL DEFAULT '',
              reason         SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              inven_type     TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              slot_no        SMALLINT          NOT NULL DEFAULT -1,
              item_id        INT UNSIGNED      NOT NULL DEFAULT 0,
              item_count     INT               NOT NULL DEFAULT 0,
              add_info       INT UNSIGNED      NOT NULL DEFAULT 0,
              endurance      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              upgrade        TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              enchant        INT UNSIGNED      NOT NULL DEFAULT 0,
              amplify_option TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              amplify_value  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              seal_cnt       TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              random_option  VARCHAR(32)       NOT NULL DEFAULT '',
              guid           VARCHAR(32)       NOT NULL DEFAULT '',
              item_x_pos     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              item_y_pos     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
              gold_after     BIGINT UNSIGNED   NOT NULL DEFAULT 0,
              coin_col       TINYINT UNSIGNED  NOT NULL DEFAULT 0,
              coin_after     INT UNSIGNED      NOT NULL DEFAULT 0,
              KEY item_history_idx_charac (charac_no, occ_time),
              KEY item_history_idx_item (item_id, occ_time),
              KEY item_history_idx_reason (reason, occ_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            """
            CREATE TABLE IF NOT EXISTS abnormal_players (
              m_id             BIGINT       NOT NULL PRIMARY KEY,
              user_id          VARCHAR(30)  NOT NULL DEFAULT '',
              last_charac_no   INT          NOT NULL DEFAULT 0,
              last_charac_name VARCHAR(64)  NOT NULL DEFAULT '',
              total_points     INT          NOT NULL DEFAULT 0,
              hit_count        INT          NOT NULL DEFAULT 0,
              last_hack_type   INT          NOT NULL DEFAULT 0,
              last_detail      VARCHAR(255) NOT NULL DEFAULT '',
              last_ip          VARCHAR(45)  NOT NULL DEFAULT '',
              first_occ_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              last_occ_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              status           TINYINT      NOT NULL DEFAULT 0,
              reviewer         VARCHAR(32)  NOT NULL DEFAULT '',
              review_note      VARCHAR(255) NOT NULL DEFAULT '',
              reviewed_at      DATETIME     DEFAULT NULL,
              KEY abnormal_players_idx_status (status, total_points)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            """
            CREATE TABLE IF NOT EXISTS dungeon_run_log (
              seq              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              occ_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              m_id             BIGINT       NOT NULL DEFAULT 0,
              charac_no        INT          NOT NULL DEFAULT 0,
              charac_name      VARCHAR(64)  NOT NULL DEFAULT '',
              dungeon_id       INT          NOT NULL DEFAULT 0,
              dungeon_name     VARCHAR(128) NOT NULL DEFAULT '',
              difficulty       INT          NOT NULL DEFAULT 0,
              result           VARCHAR(16)  NOT NULL DEFAULT '',
              clear_rank       VARCHAR(8)   NOT NULL DEFAULT '',
              duration_ms      BIGINT       NOT NULL DEFAULT 0,
              rooms_entered    INT          NOT NULL DEFAULT 0,
              monsters_spawned INT          NOT NULL DEFAULT 0,
              monsters_killed  INT          NOT NULL DEFAULT 0,
              kills_by_type    VARCHAR(64)  NOT NULL DEFAULT '',
              exp_gained       BIGINT       NOT NULL DEFAULT 0,
              max_damage       INT          NOT NULL DEFAULT 0,
              hack_points      INT          NOT NULL DEFAULT 0,
              clear_cubes_used INT          NOT NULL DEFAULT 0,
              is_hell          TINYINT      NOT NULL DEFAULT 0,
              hell_difficulty  INT          NOT NULL DEFAULT 0,
              enter_time       VARCHAR(32)  NOT NULL DEFAULT '',
              leave_time       VARCHAR(32)  NOT NULL DEFAULT '',
              kill_detail_json LONGTEXT     NOT NULL,
              room_trace_json  LONGTEXT     NULL,
              behavior_json    LONGTEXT     NULL,
              party_size       INT          NOT NULL DEFAULT 1,
              party_members    VARCHAR(128) NOT NULL DEFAULT '',
              client_ip        VARCHAR(45)  NOT NULL DEFAULT '',
              KEY dungeon_run_idx_charac (charac_no, occ_time),
              KEY dungeon_run_idx_m_id (m_id, occ_time),
              KEY dungeon_run_idx_dungeon (dungeon_id, occ_time),
              KEY dungeon_run_idx_result (result, occ_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            """
            CREATE TABLE IF NOT EXISTS trade_log (
              seq           BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
              occ_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              a_m_id        BIGINT      NOT NULL DEFAULT 0,
              a_charac_no   INT         NOT NULL DEFAULT 0,
              a_charac_name VARCHAR(64) NOT NULL DEFAULT '',
              a_gold        BIGINT      NOT NULL DEFAULT 0,
              a_items_json  LONGTEXT    NOT NULL,
              b_m_id        BIGINT      NOT NULL DEFAULT 0,
              b_charac_no   INT         NOT NULL DEFAULT 0,
              b_charac_name VARCHAR(64) NOT NULL DEFAULT '',
              b_gold        BIGINT      NOT NULL DEFAULT 0,
              b_items_json  LONGTEXT    NOT NULL,
              KEY trade_log_idx_a (a_charac_no, occ_time),
              KEY trade_log_idx_b (b_charac_no, occ_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            """
            CREATE TABLE IF NOT EXISTS cera_log (
              seq        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              occ_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              m_id       BIGINT       NOT NULL DEFAULT 0,
              delta      BIGINT       NOT NULL DEFAULT 0,
              after_cera BIGINT       NOT NULL DEFAULT 0,
              source     VARCHAR(24)  NOT NULL DEFAULT '',
              ref_id     VARCHAR(64)  NOT NULL DEFAULT '',
              operator   VARCHAR(64)  NOT NULL DEFAULT '',
              KEY cera_log_idx_m_id (m_id, occ_time),
              KEY cera_log_idx_source (source, occ_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,

            """
            CREATE TABLE IF NOT EXISTS chat_log (
              seq         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              occ_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              m_id        BIGINT       NOT NULL DEFAULT 0,
              user_id     VARCHAR(64)  NOT NULL DEFAULT '',
              charac_no   INT          NOT NULL DEFAULT 0,
              charac_name VARCHAR(64)  NOT NULL DEFAULT '',
              channel     VARCHAR(32)  NOT NULL DEFAULT '',
              target_name VARCHAR(64)  NOT NULL DEFAULT '',
              content     LONGTEXT     NOT NULL,
              client_ip   VARCHAR(45)  NOT NULL DEFAULT '',
              KEY chat_log_idx_time (occ_time),
              KEY chat_log_idx_m_id (m_id, occ_time),
              KEY chat_log_idx_charac (charac_no, occ_time),
              KEY chat_log_idx_channel (channel, occ_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
    };

    static final String[][] DUNGEON_RUN_SQLITE_MIGRATIONS = {
            {"dungeon_name", "VARCHAR(128) NOT NULL DEFAULT ''"},
            {"clear_cubes_used", "INT NOT NULL DEFAULT 0"},
            {"is_hell", "INTEGER NOT NULL DEFAULT 0"},
            {"hell_difficulty", "INT NOT NULL DEFAULT 0"},
            {"enter_time", "VARCHAR(32) NOT NULL DEFAULT ''"},
            {"leave_time", "VARCHAR(32) NOT NULL DEFAULT ''"},
            {"room_trace_json", "TEXT NOT NULL DEFAULT '[]'"},
            {"behavior_json", "TEXT NOT NULL DEFAULT '{}'"},
    };

    static final String[][] DUNGEON_RUN_MYSQL_MIGRATIONS = {
            {"dungeon_name", "VARCHAR(128) NOT NULL DEFAULT ''"},
            {"clear_cubes_used", "INT NOT NULL DEFAULT 0"},
            {"is_hell", "TINYINT NOT NULL DEFAULT 0"},
            {"hell_difficulty", "INT NOT NULL DEFAULT 0"},
            {"enter_time", "VARCHAR(32) NOT NULL DEFAULT ''"},
            {"leave_time", "VARCHAR(32) NOT NULL DEFAULT ''"},
            {"room_trace_json", "LONGTEXT NULL"},
            {"behavior_json", "LONGTEXT NULL"},
    };
}
