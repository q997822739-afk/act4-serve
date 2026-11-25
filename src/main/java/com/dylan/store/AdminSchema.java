package com.dylan.store;

final class AdminSchema {

    private AdminSchema() {
    }

    static final String[] TABLES = {
            """
            CREATE TABLE IF NOT EXISTS admin_command (
              seq        INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
              created_at DATETIME     NOT NULL DEFAULT (datetime('now','localtime')),
              cmd        VARCHAR(32)  NOT NULL DEFAULT '',
              m_id       INTEGER      NOT NULL DEFAULT 0,
              reason     VARCHAR(255) NOT NULL DEFAULT '',
              status     TINYINT      NOT NULL DEFAULT 0,
              done_at    DATETIME     DEFAULT NULL,
              result     VARCHAR(255) NOT NULL DEFAULT ''
            )
            """,

            """
            CREATE TABLE IF NOT EXISTS online_snapshot (
              m_id        INTEGER     NOT NULL PRIMARY KEY,
              user_id     VARCHAR(30) NOT NULL DEFAULT '',
              charac_no   INT         NOT NULL DEFAULT 0,
              charac_name VARCHAR(64) NOT NULL DEFAULT '',
              client_ip   VARCHAR(45) NOT NULL DEFAULT '',
              channel     INT         NOT NULL DEFAULT 0,
              updated_at  DATETIME    NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,
    };

    static final String[] INDEXES = {
            "CREATE INDEX IF NOT EXISTS admin_command_idx_status ON admin_command (status, seq)",
    };
}
