package com.dylan.game;

import com.dylan.net.ServerConfig;
import com.dylan.protocol.body.Noti53CeraBody;
import com.dylan.store.AdminStore;
import com.dylan.store.AuditStore;
import com.dylan.store.GameStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 管理指令轮询（仿 MailPoller）：
 *   1. 每 ADMIN_POLL_SECONDS 秒扫描 admin_command WHERE status = 0（执行 kick 踢线）。
 *   2. 每 10 秒刷新一次 online_snapshot 供外部管理工具读取在线玩家。
 */
public final class AdminCommandPoller {

    private static final long SNAPSHOT_INTERVAL_MS = 10_000L;
    private static final long PAODIAN_CHECK_INTERVAL_MS = 2_000L;

    private AdminCommandPoller() {
    }

    private static ScheduledExecutorService scheduler;
    private static GameStore store;
    private static long lastSnapshotMs;
    private static long lastPaodianCheckMs;

    public static synchronized void start(GameStore gameStore) {
        store = gameStore;
        if (!ServerConfig.ADMIN_POLL || store == null) {
            return;
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "admin-poller");
                t.setDaemon(true);
                return t;
            });
        }
        lastSnapshotMs = 0L;
        lastPaodianCheckMs = 0L;
        long seconds = Math.max(1, ServerConfig.ADMIN_POLL_SECONDS);
        scheduler.scheduleWithFixedDelay(AdminCommandPoller::tick, seconds, seconds, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        store = null;
    }

    private static void tick() {
        GameStore s = store;
        if (s == null) {
            return;
        }
        try {
            processCommands(s);
        } catch (RuntimeException e) {
            System.err.println("[管理指令] 执行失败：" + e.getMessage());
        }
        long now = System.currentTimeMillis();
        if (now - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            lastSnapshotMs = now;
            try {
                refreshOnlineSnapshot(s);
            } catch (RuntimeException ignored) {
            }
        }
        if (now - lastPaodianCheckMs >= PAODIAN_CHECK_INTERVAL_MS) {
            lastPaodianCheckMs = now;
            try {
                processPaodian(s, now);
            } catch (RuntimeException e) {
                System.err.println("[泡点] 轮询异常：" + e.getMessage());
            }
        }
    }

    private static void processPaodian(GameStore s, long now) {
        AuditStore.PaodianConfig cfg;
        try {
            cfg = s.audit().readPaodianConfig();
        } catch (RuntimeException e) {
            System.err.println("[泡点] 读取配置失败：" + e.getMessage());
            return;
        }
        if (!cfg.enabled() || cfg.amount() <= 0 || cfg.intervalSeconds() <= 0) {
            for (GameSession session : SessionRegistry.all()) {
                session.lastPaodianAtMs(0);
            }
            return;
        }
        long intervalMs = Math.max(1, cfg.intervalSeconds()) * 1000L;
        String msgTemplate = cfg.message() == null || cfg.message().isBlank()
                ? "在线泡点福利：恭喜获得 {amount} 点券！（当前点券：{cera}）"
                : cfg.message();
        for (GameSession session : SessionRegistry.all()) {
            if (!session.isActive() || !session.hasCharacter() || session.account() == null) {
                session.lastPaodianAtMs(0);
                continue;
            }
            long last = session.lastPaodianAtMs();
            if (last <= 0) {
                session.lastPaodianAtMs(now);
                continue;
            }
            if (now - last < intervalMs) {
                continue;
            }
            session.lastPaodianAtMs(now);
            long mId = session.account().mId();
            try {
                long after = s.cera().grantCera(mId, cfg.amount());
                AuditRecorder.recordCera(mId, cfg.amount(), after, "paodian", "", "system");
                String notice = msgTemplate
                        .replace("{amount}", String.valueOf(cfg.amount()))
                        .replace("{cera}", String.valueOf(after));
                session.run(() -> {
                    session.cera(after);
                    session.sendNow(Noti53CeraBody.PACKET_ID, Noti53CeraBody.of(after).toBytes());
                    ChatService.system(session, notice);
                    session.flush();
                });
            } catch (RuntimeException e) {
                System.err.println("[泡点] 发放点券失败 m_id=" + mId + "：" + e.getMessage());
            }
        }
    }

    private static void processCommands(GameStore s) {
        List<AdminStore.CommandRow> pending = s.admin().pollPendingCommands(32);
        if (pending.isEmpty()) {
            return;
        }
        for (AdminStore.CommandRow cmd : pending) {
            if ("kick_all".equalsIgnoreCase(cmd.cmd())) {
                String why = cmd.reason() == null || cmd.reason().isBlank() ? "管理员全服踢下线" : cmd.reason();
                int count = 0;
                for (GameSession session : SessionRegistry.all()) {
                    if (session.isActive()) {
                        session.run(() -> session.disconnect("全服踢人：" + why));
                        count++;
                    }
                }
                s.admin().markCommandDone(cmd.seq(), AdminStore.STATUS_DONE,
                        "已全服踢线（" + count + " 人）");
                continue;
            }
            if ("premium_reload".equalsIgnoreCase(cmd.cmd())) {
                int refreshed = 0;
                for (GameSession session : SessionRegistry.all()) {
                    if (session.isActive() && session.account() != null
                            && session.account().mId() == cmd.mId()) {
                        refreshed++;
                        session.run(() -> reloadPremium(session));
                    }
                }
                s.admin().markCommandDone(cmd.seq(), AdminStore.STATUS_DONE,
                        refreshed > 0 ? "已刷新在线会员 " + refreshed + " 个连接" : "账号当前不在线，下次登录生效");
                continue;
            }
            if (!"kick".equalsIgnoreCase(cmd.cmd())) {
                s.admin().markCommandDone(cmd.seq(), AdminStore.STATUS_FAILED,
                        "不支持的命令：" + cmd.cmd());
                continue;
            }
            int kicked = 0;
            String why = cmd.reason() == null || cmd.reason().isBlank()
                    ? "已被管理员强制踢下线"
                    : "已被管理员强制踢下线：" + cmd.reason();
            for (GameSession session : SessionRegistry.all()) {
                if (session.isActive() && session.account() != null
                        && session.account().mId() == cmd.mId()) {
                    kicked++;
                    session.run(() -> session.fail(why));
                }
            }
            s.admin().markCommandDone(cmd.seq(), AdminStore.STATUS_DONE,
                    kicked > 0 ? "已踢下线 " + kicked + " 个连接" : "账号当前不在线");
        }
    }

    /**
     * 外部程序直接改了 member_premium / member_info.black_diamond_end，
     * 这里让在线角色重读会员，并按进世界时的同一布局补发 NOTI 66（kind 2），再刷新本人角色信息。
     */
    private static void reloadPremium(GameSession session) {
        if (session.account() == null || !session.hasCharacter()) {
            return;
        }
        PremiumService premium = session.premium();
        premium.load(session.account().mId());
        long now = java.time.Instant.now().getEpochSecond();
        var row = premium.rows().get(PremiumService.PREMIUM_BLACK_DIAMOND);
        if (row == null || row.end() <= now) {
            return;
        }
        int wireType = premium.wireType(PremiumService.PREMIUM_BLACK_DIAMOND);
        if (wireType <= com.dylan.protocol.body.Noti66SpecialItemBody.MAX_PREMIUM_TYPE) {
            session.sendNow(com.dylan.protocol.body.Noti66SpecialItemBody.PACKET_ID,
                    com.dylan.protocol.body.Noti66SpecialItemBody.premium(
                            A4WorldEntry.LOGIN_PREMIUM_KIND, wireType, row.end() - now).toBytes());
        }
        CharacterService.resendOwnCharacterInfo(session, "外部开通黑钻");
        long days = Math.max(1, (row.end() - now + 86399) / 86400);
        ChatService.system(session, "[黑钻会员]的有效时间还剩" + days + "天");
        session.flush();
    }

    private static void refreshOnlineSnapshot(GameStore s) {
        List<AdminStore.OnlineRow> rows = new ArrayList<>();
        for (GameSession session : SessionRegistry.all()) {
            if (!session.isActive() || session.account() == null) {
                continue;
            }
            rows.add(new AdminStore.OnlineRow(
                    session.account().mId(),
                    session.account().userId(),
                    session.characNo(),
                    session.characName() == null ? "" : session.characName(),
                    session.clientIp() == null ? "" : session.clientIp(),
                    ServerConfig.a4ChannelForPort(session.localPort())));
        }
        s.admin().replaceOnlineSnapshot(rows);
    }
}
