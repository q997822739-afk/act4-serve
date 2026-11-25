package com.dylan.store;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

public final class AuditStore extends DomainStore {
    private final boolean mysql;

    AuditStore(DataSource dataSource, boolean mysql) {
        super(dataSource);
        this.mysql = mysql;
    }

    public record DungeonRunRow(
            long mId,
            int characNo,
            String characName,
            int dungeonId,
            String dungeonName,
            int difficulty,
            String result,
            String clearRank,
            long durationMs,
            int roomsEntered,
            int monstersSpawned,
            int monstersKilled,
            String killsByType,
            long expGained,
            int maxDamage,
            int hackPoints,
            int clearCubesUsed,
            boolean isHell,
            int hellDifficulty,
            String enterTime,
            String leaveTime,
            String killDetailJson,
            String roomTraceJson,
            String behaviorJson,
            int partySize,
            String partyMembers,
            String clientIp) {
    }

    public record ChatLogRow(
            long mId,
            String userId,
            int characNo,
            String characName,
            String channel,
            String targetName,
            String content,
            String clientIp) {
    }

    public record TradeRow(
            long aMId,
            int aCharacNo,
            String aCharacName,
            long aGold,
            String aItemsJson,
            long bMId,
            int bCharacNo,
            String bCharacName,
            long bGold,
            String bItemsJson) {
    }

    public record CeraLogRow(
            long mId,
            long delta,
            long afterCera,
            String source,
            String refId,
            String operator) {
    }

    public record HackRow(
            long mId,
            String userId,
            int characNo,
            String characName,
            int hackType,
            int parameter1,
            int parameter2,
            int cnt,
            int totalPoints,
            String detail,
            String clientIp) {
    }

    public void insertDungeonRuns(List<DungeonRunRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO dungeon_run_log ("
                + "m_id, charac_no, charac_name, dungeon_id, dungeon_name, difficulty, result, clear_rank,"
                + " duration_ms, rooms_entered, monsters_spawned, monsters_killed, kills_by_type,"
                + " exp_gained, max_damage, hack_points, clear_cubes_used, is_hell, hell_difficulty,"
                + " enter_time, leave_time, kill_detail_json, room_trace_json, behavior_json,"
                + " party_size, party_members, client_ip)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (DungeonRunRow r : rows) {
                    ps.setLong(1, r.mId());
                    ps.setInt(2, r.characNo());
                    ps.setString(3, fit(r.characName(), 64));
                    ps.setInt(4, r.dungeonId());
                    ps.setString(5, fit(r.dungeonName(), 128));
                    ps.setInt(6, r.difficulty());
                    ps.setString(7, fit(r.result(), 16));
                    ps.setString(8, fit(r.clearRank(), 8));
                    ps.setLong(9, Math.max(0L, r.durationMs()));
                    ps.setInt(10, Math.max(0, r.roomsEntered()));
                    ps.setInt(11, Math.max(0, r.monstersSpawned()));
                    ps.setInt(12, Math.max(0, r.monstersKilled()));
                    ps.setString(13, fit(r.killsByType(), 64));
                    ps.setLong(14, Math.max(0L, r.expGained()));
                    ps.setInt(15, Math.max(0, r.maxDamage()));
                    ps.setInt(16, Math.max(0, r.hackPoints()));
                    ps.setInt(17, Math.max(0, r.clearCubesUsed()));
                    ps.setInt(18, r.isHell() ? 1 : 0);
                    ps.setInt(19, Math.max(0, r.hellDifficulty()));
                    ps.setString(20, fit(r.enterTime(), 32));
                    ps.setString(21, fit(r.leaveTime(), 32));
                    ps.setString(22, r.killDetailJson() == null ? "[]" : r.killDetailJson());
                    ps.setString(23, r.roomTraceJson() == null ? "[]" : r.roomTraceJson());
                    ps.setString(24, r.behaviorJson() == null ? "{}" : r.behaviorJson());
                    ps.setInt(25, Math.max(1, r.partySize()));
                    ps.setString(26, fit(r.partyMembers(), 128));
                    ps.setString(27, fit(r.clientIp(), 45));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertChatLogs(List<ChatLogRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO chat_log ("
                + "m_id, user_id, charac_no, charac_name, channel, target_name, content, client_ip)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        withTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (ChatLogRow r : rows) {
                    ps.setLong(1, r.mId());
                    ps.setString(2, fit(r.userId(), 64));
                    ps.setInt(3, r.characNo());
                    ps.setString(4, fit(r.characName(), 64));
                    ps.setString(5, fit(r.channel(), 32));
                    ps.setString(6, fit(r.targetName(), 64));
                    ps.setString(7, r.content() == null ? "" : r.content());
                    ps.setString(8, fit(r.clientIp(), 45));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertTrades(List<TradeRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO trade_log ("
                + "a_m_id, a_charac_no, a_charac_name, a_gold, a_items_json,"
                + " b_m_id, b_charac_no, b_charac_name, b_gold, b_items_json)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (TradeRow r : rows) {
                    ps.setLong(1, r.aMId());
                    ps.setInt(2, r.aCharacNo());
                    ps.setString(3, fit(r.aCharacName(), 64));
                    ps.setLong(4, Math.max(0L, r.aGold()));
                    ps.setString(5, r.aItemsJson() == null ? "[]" : r.aItemsJson());
                    ps.setLong(6, r.bMId());
                    ps.setInt(7, r.bCharacNo());
                    ps.setString(8, fit(r.bCharacName(), 64));
                    ps.setLong(9, Math.max(0L, r.bGold()));
                    ps.setString(10, r.bItemsJson() == null ? "[]" : r.bItemsJson());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertCeraLogs(List<CeraLogRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO cera_log (m_id, delta, after_cera, source, ref_id, operator)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        withTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (CeraLogRow r : rows) {
                    ps.setLong(1, r.mId());
                    ps.setLong(2, r.delta());
                    ps.setLong(3, Math.max(0L, r.afterCera()));
                    ps.setString(4, fit(r.source(), 24));
                    ps.setString(5, fit(r.refId(), 64));
                    ps.setString(6, fit(r.operator(), 64));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void insertHackLogsAndAbnormal(List<HackRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String logSql = "INSERT INTO hack_log"
                + " (m_id, charac_no, hack_type, parameter1, parameter2, cnt, detail, client_ip, source)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'server')";
        String abnormalSql = mysql
                ? "INSERT INTO abnormal_players ("
                        + " m_id, user_id, last_charac_no, last_charac_name, total_points, hit_count,"
                        + " last_hack_type, last_detail, last_ip, first_occ_time, last_occ_time, status)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(), NOW(), 0)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "  user_id = IF(VALUES(user_id) <> '', VALUES(user_id), user_id),"
                        + "  last_charac_no = VALUES(last_charac_no),"
                        + "  last_charac_name = IF(VALUES(last_charac_name) <> '', VALUES(last_charac_name), last_charac_name),"
                        + "  total_points = GREATEST(total_points, VALUES(total_points)),"
                        + "  hit_count = hit_count + 1,"
                        + "  last_hack_type = VALUES(last_hack_type),"
                        + "  last_detail = VALUES(last_detail),"
                        + "  last_ip = VALUES(last_ip),"
                        + "  last_occ_time = NOW(),"
                        + "  status = IF(status = 1, 0, status)"
                : "INSERT INTO abnormal_players ("
                        + " m_id, user_id, last_charac_no, last_charac_name, total_points, hit_count,"
                        + " last_hack_type, last_detail, last_ip, first_occ_time, last_occ_time, status)"
                        + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, datetime('now','localtime'), datetime('now','localtime'), 0)"
                        + " ON CONFLICT(m_id) DO UPDATE SET"
                        + "  user_id = CASE WHEN excluded.user_id <> '' THEN excluded.user_id ELSE abnormal_players.user_id END,"
                        + "  last_charac_no = excluded.last_charac_no,"
                        + "  last_charac_name = CASE WHEN excluded.last_charac_name <> '' THEN excluded.last_charac_name ELSE abnormal_players.last_charac_name END,"
                        + "  total_points = MAX(abnormal_players.total_points, excluded.total_points),"
                        + "  hit_count = abnormal_players.hit_count + 1,"
                        + "  last_hack_type = excluded.last_hack_type,"
                        + "  last_detail = excluded.last_detail,"
                        + "  last_ip = excluded.last_ip,"
                        + "  last_occ_time = datetime('now','localtime'),"
                        + "  status = CASE WHEN abnormal_players.status = 1 THEN 0 ELSE abnormal_players.status END";
        withTx(c -> {
            try (PreparedStatement logPs = c.prepareStatement(logSql);
                 PreparedStatement abPs = c.prepareStatement(abnormalSql)) {
                boolean hasAbnormal = false;
                for (HackRow r : rows) {
                    logPs.setLong(1, r.mId());
                    logPs.setInt(2, r.characNo());
                    logPs.setInt(3, Math.clamp(r.hackType(), 0, 0xFFFF));
                    logPs.setInt(4, r.parameter1());
                    logPs.setInt(5, r.parameter2());
                    logPs.setInt(6, Math.max(0, r.cnt()));
                    logPs.setString(7, fit(r.detail(), 255));
                    logPs.setString(8, fit(r.clientIp(), 45));
                    logPs.addBatch();
                    if (r.mId() > 0) {
                        hasAbnormal = true;
                        abPs.setLong(1, r.mId());
                        abPs.setString(2, fit(r.userId(), 30));
                        abPs.setInt(3, r.characNo());
                        abPs.setString(4, fit(r.characName(), 64));
                        abPs.setInt(5, Math.max(0, r.totalPoints()));
                        abPs.setInt(6, Math.clamp(r.hackType(), 0, 0xFFFF));
                        abPs.setString(7, fit(r.detail(), 255));
                        abPs.setString(8, fit(r.clientIp(), 45));
                        abPs.addBatch();
                    }
                }
                logPs.executeBatch();
                if (hasAbnormal) {
                    abPs.executeBatch();
                }
            }
        });
    }

    public record PaodianConfig(
            boolean enabled,
            String type,
            int intervalSeconds,
            long amount,
            String message) {
    }

    public PaodianConfig readPaodianConfig() {
        if (!mysql) {
            return new PaodianConfig(false, "点券", 60, 0, "");
        }
        String sql = "SELECT `key`, `value` FROM s1a5_gateway.config WHERE `key` LIKE 'paodian_%' OR `key` LIKE 'bubble%'";
        boolean hasLegacyEnabled = false;
        boolean legacyEnabled = false;
        String type = "点券";
        int intervalSec = 60;
        long amount = 0;
        boolean hasBubbleCount = false;
        String msg = "在线泡点福利：恭喜获得 {amount} 点券！";
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String k = rs.getString(1);
                String v = rs.getString(2);
                if (v == null) v = "";
                v = v.trim();
                switch (k) {
                    case "bubbleType" -> { if (!v.isEmpty()) type = v; }
                    case "bubbleTime" -> {
                        try { intervalSec = Math.max(0, Integer.parseInt(v)); } catch (NumberFormatException ignored) {}
                    }
                    case "bubbleCount" -> {
                        try {
                            amount = Math.max(0, Long.parseLong(v));
                            hasBubbleCount = true;
                        } catch (NumberFormatException ignored) {}
                    }
                    case "paodian_enabled" -> {
                        hasLegacyEnabled = true;
                        legacyEnabled = "1".equals(v);
                    }
                    case "paodian_type" -> { if (!v.isEmpty()) type = v; }
                    case "paodian_interval" -> {
                        if (!hasBubbleCount) {
                            try { intervalSec = Math.max(1, Integer.parseInt(v)) * 60; } catch (NumberFormatException ignored) {}
                        }
                    }
                    case "paodian_amount" -> {
                        if (!hasBubbleCount) {
                            try { amount = Math.max(0, Long.parseLong(v)); } catch (NumberFormatException ignored) {}
                        }
                    }
                    case "paodian_msg" -> { if (!v.isEmpty()) msg = v; }
                }
            }
        } catch (SQLException e) {
            System.err.println("[泡点] 查询配置表失败：" + e.getMessage());
            return new PaodianConfig(false, "点券", 60, 0, "");
        }
        boolean enabled = hasBubbleCount ? (amount > 0 && intervalSec > 0) : (hasLegacyEnabled ? legacyEnabled : amount > 0);
        return new PaodianConfig(enabled, type, intervalSec, amount, msg);
    }

    // ---------- 商城限购（shop_limit 由外部写入，这里只读；购买次数记 shop_limit_count） ----------

    private volatile boolean shopLimitTablesReady;

    /** 两张表外部也会建；这里再建一次，防止表还没建就有人买东西。 */
    private void ensureShopLimitTables(Connection c) throws SQLException {
        if (shopLimitTablesReady) {
            return;
        }
        try (java.sql.Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS s1a5_gateway.shop_limit (
                      item_id     INT          NOT NULL PRIMARY KEY,
                      daily_limit INT          NOT NULL DEFAULT 0,
                      note        VARCHAR(128) NOT NULL DEFAULT '',
                      updated_at  BIGINT       NOT NULL DEFAULT 0
                    ) DEFAULT CHARSET=utf8mb4""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS s1a5_gateway.shop_limit_count (
                      m_id    BIGINT  NOT NULL,
                      item_id INT     NOT NULL,
                      day     CHAR(8) NOT NULL,
                      bought  INT     NOT NULL DEFAULT 0,
                      PRIMARY KEY (m_id, item_id)
                    ) DEFAULT CHARSET=utf8mb4""");
        }
        shopLimitTablesReady = true;
    }

    /** 该物品每日限购次数；0 = 不限购（没配、或审计库不是 MySQL）。 */
    public int shopDailyLimit(int itemId) {
        if (!mysql) {
            return 0;
        }
        try (Connection c = connection()) {
            ensureShopLimitTables(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT daily_limit FROM s1a5_gateway.shop_limit WHERE item_id = ?")) {
                ps.setInt(1, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读商城限购配置失败：" + e.getMessage(), e);
        }
    }

    /** 账号今天（day = yyyyMMdd）已经买了这个物品几次。 */
    public int shopBoughtToday(long mId, int itemId, String day) {
        try (Connection c = connection()) {
            ensureShopLimitTables(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT day, bought FROM s1a5_gateway.shop_limit_count WHERE m_id = ? AND item_id = ?")) {
                ps.setLong(1, mId);
                ps.setInt(2, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() && day.equals(rs.getString(1)) ? rs.getInt(2) : 0;
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读商城限购次数失败：" + e.getMessage(), e);
        }
    }

    /** 购买成功后 +1；跨天自动从 1 重新计。 */
    public void shopAddBought(long mId, int itemId, String day) {
        // MySQL 的 UPDATE 子句从左到右赋值：先按旧 day 算 bought，再改 day
        String sql = "INSERT INTO s1a5_gateway.shop_limit_count (m_id, item_id, day, bought) VALUES (?, ?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE bought = IF(day = VALUES(day), bought + 1, 1), day = VALUES(day)";
        try (Connection c = connection()) {
            ensureShopLimitTables(c);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, mId);
                ps.setInt(2, itemId);
                ps.setString(3, day);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StoreException("记商城限购次数失败：" + e.getMessage(), e);
        }
    }

    // ---------- 物品每日使用次数限制（s1a5_gateway.item_use_limit 配次数，按角色记 item_use_count） ----------

    private volatile boolean useLimitTablesReady;

    private void ensureUseLimitTables(Connection c) throws SQLException {
        if (useLimitTablesReady) {
            return;
        }
        try (java.sql.Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS s1a5_gateway.item_use_limit (
                      item_id     INT          NOT NULL PRIMARY KEY,
                      daily_limit INT          NOT NULL DEFAULT 0,
                      note        VARCHAR(128) NOT NULL DEFAULT '',
                      updated_at  BIGINT       NOT NULL DEFAULT 0
                    ) DEFAULT CHARSET=utf8mb4""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS s1a5_gateway.item_use_count (
                      charac_no INT     NOT NULL,
                      item_id   INT     NOT NULL,
                      day       CHAR(8) NOT NULL,
                      used      INT     NOT NULL DEFAULT 0,
                      PRIMARY KEY (charac_no, item_id)
                    ) DEFAULT CHARSET=utf8mb4""");
        }
        useLimitTablesReady = true;
    }

    /** 该物品每个角色每日可用次数；0 = 不限（没配、或审计库不是 MySQL）。 */
    public int itemUseDailyLimit(int itemId) {
        if (!mysql) {
            return 0;
        }
        try (Connection c = connection()) {
            ensureUseLimitTables(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT daily_limit FROM s1a5_gateway.item_use_limit WHERE item_id = ?")) {
                ps.setInt(1, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读物品使用限制失败：" + e.getMessage(), e);
        }
    }

    /** 角色今天（day = yyyyMMdd）已经用了这个物品几次。 */
    public int itemUsedToday(int characNo, int itemId, String day) {
        try (Connection c = connection()) {
            ensureUseLimitTables(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT day, used FROM s1a5_gateway.item_use_count WHERE charac_no = ? AND item_id = ?")) {
                ps.setInt(1, characNo);
                ps.setInt(2, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    return rs.next() && day.equals(rs.getString(1)) ? rs.getInt(2) : 0;
                }
            }
        } catch (SQLException e) {
            throw new StoreException("读物品使用次数失败：" + e.getMessage(), e);
        }
    }

    /** 使用成功后 +1；跨天自动从 1 重新计。 */
    public void itemAddUsed(int characNo, int itemId, String day) {
        String sql = "INSERT INTO s1a5_gateway.item_use_count (charac_no, item_id, day, used) VALUES (?, ?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE used = IF(day = VALUES(day), used + 1, 1), day = VALUES(day)";
        try (Connection c = connection()) {
            ensureUseLimitTables(c);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, characNo);
                ps.setInt(2, itemId);
                ps.setString(3, day);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StoreException("记物品使用次数失败：" + e.getMessage(), e);
        }
    }

    // ---------- SS 史诗掉落记录（s1a5_audit.epic_drop_log） ----------

    public record EpicDropRow(
            long mId,
            int characNo,
            String characName,
            int dungeonId,
            String dungeonName,
            int itemId,
            String itemName,
            int rarity,
            String clientIp) {
    }

    private volatile boolean epicDropTableReady;

    public void insertEpicDrops(List<EpicDropRow> rows) {
        if (rows == null || rows.isEmpty() || !mysql) {
            return;
        }
        String sql = "INSERT INTO s1a5_audit.epic_drop_log ("
                + "m_id, charac_no, charac_name, dungeon_id, dungeon_name, item_id, item_name, rarity, client_ip)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        withTx(c -> {
            if (!epicDropTableReady) {
                try (java.sql.Statement s = c.createStatement()) {
                    s.execute("""
                            CREATE TABLE IF NOT EXISTS s1a5_audit.epic_drop_log (
                              id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                              occ_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              m_id         BIGINT       NOT NULL DEFAULT 0,
                              charac_no    INT          NOT NULL DEFAULT 0,
                              charac_name  VARCHAR(64)  NOT NULL DEFAULT '',
                              dungeon_id   INT          NOT NULL DEFAULT 0,
                              dungeon_name VARCHAR(128) NOT NULL DEFAULT '',
                              item_id      INT          NOT NULL DEFAULT 0,
                              item_name    VARCHAR(128) NOT NULL DEFAULT '',
                              rarity       INT          NOT NULL DEFAULT 0,
                              client_ip    VARCHAR(45)  NOT NULL DEFAULT '',
                              KEY epic_drop_idx_time (occ_time),
                              KEY epic_drop_idx_charac (charac_no, occ_time),
                              KEY epic_drop_idx_item (item_id, occ_time)
                            ) DEFAULT CHARSET=utf8mb4""");
                }
                epicDropTableReady = true;
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (EpicDropRow r : rows) {
                    ps.setLong(1, r.mId());
                    ps.setInt(2, r.characNo());
                    ps.setString(3, fit(r.characName(), 64));
                    ps.setInt(4, r.dungeonId());
                    ps.setString(5, fit(r.dungeonName(), 128));
                    ps.setInt(6, r.itemId());
                    ps.setString(7, fit(r.itemName(), 128));
                    ps.setInt(8, r.rarity());
                    ps.setString(9, fit(r.clientIp(), 45));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    private void withTx(SqlWork work) {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
            } finally {
                try {
                    c.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
        }
    }
}
