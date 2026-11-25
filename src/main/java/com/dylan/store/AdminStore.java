package com.dylan.store;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class AdminStore extends DomainStore {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_FAILED = 2;

    AdminStore(DataSource dataSource) {
        super(dataSource);
    }

    public record CommandRow(long seq, String cmd, long mId, String reason) {
    }

    public record OnlineRow(long mId, String userId, int characNo, String characName,
                            String clientIp, int channel) {
    }

    public List<CommandRow> pollPendingCommands(int limit) {
        String sql = "SELECT seq, cmd, m_id, reason FROM admin_command"
                + " WHERE status = 0 ORDER BY seq ASC LIMIT ?";
        List<CommandRow> out = new ArrayList<>();
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CommandRow(
                            rs.getLong(1),
                            rs.getString(2),
                            rs.getLong(3),
                            rs.getString(4)));
                }
            }
            return out;
        } catch (SQLException e) {
            return out;
        }
    }

    public void markCommandDone(long seq, int status, String result) {
        String sql = "UPDATE admin_command"
                + " SET status = ?, done_at = datetime('now','localtime'), result = ?"
                + " WHERE seq = ?";
        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, status);
            ps.setString(2, fit(result, 255));
            ps.setLong(3, seq);
            ps.executeUpdate();
        } catch (SQLException e) {
        }
    }

    public void replaceOnlineSnapshot(List<OnlineRow> rows) {
        String del = "DELETE FROM online_snapshot";
        String ins = "INSERT OR REPLACE INTO online_snapshot"
                + " (m_id, user_id, charac_no, charac_name, client_ip, channel, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, datetime('now','localtime'))";
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement pd = c.prepareStatement(del);
                 PreparedStatement pi = c.prepareStatement(ins)) {
                pd.executeUpdate();
                if (rows != null) {
                    for (OnlineRow row : rows) {
                        pi.setLong(1, row.mId());
                        pi.setString(2, fit(row.userId(), 30));
                        pi.setInt(3, row.characNo());
                        pi.setString(4, fit(row.characName(), 64));
                        pi.setString(5, fit(row.clientIp(), 45));
                        pi.setInt(6, row.channel());
                        pi.addBatch();
                    }
                    pi.executeBatch();
                }
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
