package com.dylan.game;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 在线每日重置。官方 TimerFatigueReset 每天到疲劳恢复点（m_fatigueTime）对 m_UserInWorld 里所有人
 * 跑 GameWorld::ResetAllFatigue / ReselectDailyQuest / AddDailyItem / ResetOneADayItemShop……，
 * 挂机跨过 6 点不用小退就刷新。原来我们只在登录/进图/扣疲劳时才检查，挂机过 6 点后登出还会把
 * 旧疲劳带新时间戳存回去（2026-11-24 A 服疲劳不刷新）。
 *
 * 跟官方一样是一次性定时器：算出下一个恢复点，到点只跑一次，跑完再预约下一天（TimerFatigueReset::
 * RegistNextTimer），平时不轮询。到点给所有在线角色：
 * 疲劳 + 每日任务重置并推 NOTI 36 / 可接任务列表、补每日券、推免费复活币可领取。
 * 每日次数类（疲劳药、商城限购）用 {@link #dayKey()}，与疲劳同一个恢复点换日。
 */
public final class DailyResetService {

    /** 到点后多等 1 秒再跑，保证 periodStart() 已经换到新的一天。 */
    private static final long FIRE_DELAY_MS = 1000;

    private static ScheduledExecutorService scheduler;

    private static Instant lastPeriod;

    private DailyResetService() {
    }

    /** 当前"游戏日"的起点（服务器时区的 fatigueResetHour 点，默认 6 点）。 */
    public static Instant periodStart() {
        return FatigueService.periodStartFor(Instant.now(), java.time.ZoneId.systemDefault(),
                com.dylan.net.ServerConfig.FATIGUE_RESET_HOUR);
    }

    /** 每日次数类的日期键（yyyyMMdd），6 点前算前一天，跟疲劳一起换日。 */
    public static String dayKey() {
        return periodStart().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        lastPeriod = periodStart();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-reset");
            t.setDaemon(true);
            return t;
        });
        registNextTimer();
    }

    /** 预约下一个恢复点（官方 RegistNextTimer）。 */
    private static synchronized void registNextTimer() {
        if (scheduler == null) {
            return;
        }
        Instant next = periodStart().plus(java.time.Duration.ofDays(1));
        long delay = Math.max(0, next.toEpochMilli() - System.currentTimeMillis()) + FIRE_DELAY_MS;
        scheduler.schedule(DailyResetService::fire, delay, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static void fire() {
        try {
            Instant now = periodStart();
            // 系统时钟被往回拨过时可能提前醒：还没换日就只重新预约
            if (now.isAfter(lastPeriod)) {
                lastPeriod = now;
                int n = resetAllOnline();
                System.out.println("[每日重置] 跨过恢复点 " + now + "，在线角色 " + n + " 个已重置");
            }
        } catch (RuntimeException e) {
            System.err.println("[每日重置] 失败：" + e);
        } finally {
            registNextTimer();
        }
    }

    /** 给所有在线（已选角色）的玩家跑一次每日重置；返回投递的人数。 */
    public static int resetAllOnline() {
        int n = 0;
        for (GameSession s : SessionRegistry.playing()) {
            if (!s.isActive()) {
                continue;
            }
            n++;
            s.run(() -> {
                if (!s.isActive() || !s.hasCharacter()) {
                    return;
                }
                try {
                    onNewDay(s);
                } catch (RuntimeException e) {
                    System.err.println("[每日重置] charac=" + s.characNo() + " 失败：" + e);
                }
                s.flush();
            });
        }
        return n;
    }

    static void onNewDay(GameSession s) {
        // 疲劳：rollover 会清疲劳/已用疲劳、重置每日任务、落库并推 NOTI 36
        if (s.fatigue().rolloverNow()) {
            QuestService.sendAcceptableQuests(s, "每日重置");
        }
        DailyGrantService.onNewDay(s, s.characNo());
    }
}
