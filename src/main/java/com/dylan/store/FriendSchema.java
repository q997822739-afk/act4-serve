package com.dylan.store;

/** 好友（单向：A 加了 B，只记在 A 的名单里，和客户端 REPLY 63 直接入表一致）。 */
final class FriendSchema {

    private FriendSchema() {
    }

    static final String[] TABLES = {
            """
            CREATE TABLE IF NOT EXISTS friend_link (
              owner_no  INT NOT NULL,
              friend_no INT NOT NULL,
              PRIMARY KEY (owner_no, friend_no)
            )
            """,
            """
            CREATE INDEX IF NOT EXISTS idx_friend_friend ON friend_link (friend_no)
            """,
            // 黑名单（台服由 monitor 服务器存：角色号 + 加入时间，每人最多 10 个）
            """
            CREATE TABLE IF NOT EXISTS charac_blacklist (
              owner_no  INT NOT NULL,
              black_no  INT NOT NULL,
              reg_time  INT NOT NULL,
              PRIMARY KEY (owner_no, black_no)
            )
            """,
    };
}
