package com.dylan.game;

import com.dylan.store.AuditStore;
import com.dylan.store.GameStore;
import com.dylan.store.blob.InvenItem;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 审计日志异步批量落库器（写入独立的 audit.db，不阻塞游戏线程与 s1a5.db 存档）。
 */
public final class AuditRecorder {
    private static final int QUEUE_CAPACITY = 20_000;
    private static final int BATCH_MAX = 500;
    private static final long POLL_TIMEOUT_MS = 500;

    private sealed interface Event permits RunEvent, TradeEvent, CeraEvent, HackEvent, ChatEvent, EpicDropEvent {
    }

    private record RunEvent(AuditStore.DungeonRunRow row) implements Event {
    }

    private record TradeEvent(AuditStore.TradeRow row) implements Event {
    }

    private record CeraEvent(AuditStore.CeraLogRow row) implements Event {
    }

    private record HackEvent(AuditStore.HackRow row) implements Event {
    }

    private record ChatEvent(AuditStore.ChatLogRow row) implements Event {
    }

    private record EpicDropEvent(AuditStore.EpicDropRow row) implements Event {
    }

    private static volatile AuditRecorder instance;

    private final GameStore store;
    private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile Thread worker;
    private volatile boolean running;

    private AuditRecorder(GameStore store) {
        this.store = store;
    }

    public static synchronized void start(GameStore store) {
        if (instance != null || store == null) {
            return;
        }
        AuditRecorder recorder = new AuditRecorder(store);
        recorder.running = true;
        recorder.worker = Thread.ofVirtual().name("audit-recorder").start(recorder::drainLoop);
        instance = recorder;
    }

    public static synchronized void stop() {
        AuditRecorder recorder = instance;
        if (recorder == null) {
            return;
        }
        recorder.running = false;
        Thread w = recorder.worker;
        if (w != null) {
            w.interrupt();
            try {
                w.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        recorder.flushAll();
        instance = null;
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static void recordDungeonRun(GameSession session, String result, String clearRank) {
        AuditRecorder recorder = instance;
        if (recorder == null || session == null) {
            return;
        }
        DungeonRunSummary summary = session.runSummary();
        if (summary == null || !summary.clockStarted() || summary.logged()) {
            return;
        }
        summary.markLogged();
        long mId = session.account() == null ? 0L : session.account().mId();
        String characName = session.characName() == null ? "" : session.characName();
        String killsByType = summary.killsOfType(0) + "," + summary.killsOfType(1)
                + "," + summary.killsOfType(2) + "," + summary.killsOfType(3);
        String dungeonName = summary.dungeonName();
        if ((dungeonName == null || dungeonName.isBlank()) && session.scripts() != null) {
            dungeonName = session.scripts().dungeonName(summary.dungeonId());
        }
        if (dungeonName == null || dungeonName.isBlank()) {
            dungeonName = "副本#" + summary.dungeonId();
        }
        String killDetailJson = encodeKillDetail(summary.killStatsSnapshot());
        String roomTraceJson = encodeRoomTrace(summary.roomTraceSnapshot());
        String behaviorJson = encodeBehavior(summary, dungeonName);
        String enterTime = summary.enterTimeMs() > 0 ? TS_FMT.format(Instant.ofEpochMilli(summary.enterTimeMs())) : "";
        String leaveTime = summary.leaveTimeMs() > 0 ? TS_FMT.format(Instant.ofEpochMilli(summary.leaveTimeMs())) : "";
        int hackPoints = session.hack() == null ? 0 : session.hack().points();
        recorder.offer(new RunEvent(new AuditStore.DungeonRunRow(
                mId,
                session.characNo(),
                characName,
                summary.dungeonId(),
                dungeonName,
                summary.difficulty(),
                result,
                clearRank == null ? "" : clearRank,
                summary.elapsedMillis(),
                summary.roomsEntered(),
                summary.monstersSpawned(),
                summary.monstersKilled(),
                killsByType,
                summary.experienceGained(),
                summary.maxDamage(),
                hackPoints,
                summary.clearCubesUsed(),
                summary.hellAppeared(),
                summary.hellDifficulty(),
                enterTime,
                leaveTime,
                killDetailJson,
                roomTraceJson,
                behaviorJson,
                summary.partySize(),
                summary.partyMembers(),
                session.clientIp() == null ? "" : session.clientIp())));
    }

    public static void recordEpicDrop(GameSession session, int dungeonId, String dungeonName,
                                      int itemId, String itemName, int rarity) {
        AuditRecorder recorder = instance;
        if (recorder == null || session == null) {
            return;
        }
        long mId = session.account() == null ? 0L : session.account().mId();
        recorder.offer(new EpicDropEvent(new AuditStore.EpicDropRow(
                mId,
                session.characNo(),
                session.characName() == null ? "" : session.characName(),
                dungeonId,
                dungeonName == null ? "" : dungeonName,
                itemId,
                itemName == null ? "" : itemName,
                rarity,
                session.clientIp() == null ? "" : session.clientIp())));
    }

    public static void recordChat(GameSession session, String channel, String targetName, String content) {
        AuditRecorder recorder = instance;
        if (recorder == null || session == null || content == null) {
            return;
        }
        long mId = session.account() == null ? 0L : session.account().mId();
        String userId = session.account() == null || session.account().userId() == null
                ? "" : session.account().userId();
        String characName = session.characName() == null ? "" : session.characName();
        String ip = session.clientIp() == null ? "" : session.clientIp();
        recorder.offer(new ChatEvent(new AuditStore.ChatLogRow(
                mId,
                userId,
                session.characNo(),
                characName,
                channel == null ? "normal" : channel,
                targetName == null ? "" : targetName,
                content,
                ip)));
    }

    public static void recordTrade(GameSession a, long aGold, List<InvenItem> aItems,
                                   GameSession b, long bGold, List<InvenItem> bItems) {
        AuditRecorder recorder = instance;
        if (recorder == null || a == null || b == null) {
            return;
        }
        long aMId = a.account() == null ? 0L : a.account().mId();
        long bMId = b.account() == null ? 0L : b.account().mId();
        recorder.offer(new TradeEvent(new AuditStore.TradeRow(
                aMId,
                a.characNo(),
                a.characName() == null ? "" : a.characName(),
                aGold,
                encodeItems(aItems),
                bMId,
                b.characNo(),
                b.characName() == null ? "" : b.characName(),
                bGold,
                encodeItems(bItems))));
    }

    public static void recordCera(long mId, long delta, long afterCera,
                                  String source, String refId, String operator) {
        AuditRecorder recorder = instance;
        if (recorder == null || mId <= 0) {
            return;
        }
        recorder.offer(new CeraEvent(new AuditStore.CeraLogRow(
                mId,
                delta,
                afterCera,
                source == null ? "" : source,
                refId == null ? "" : refId,
                operator == null ? "" : operator)));
    }

    public static void recordHack(GameSession session, int code, int param1, int param2,
                                  int cnt, int totalPoints, String detail) {
        if (session == null) {
            return;
        }
        long mId = session.account() == null ? 0L : session.account().mId();
        String userId = session.account() == null ? "" : session.account().userId();
        String characName = session.characName() == null ? "" : session.characName();
        String ip = session.clientIp() == null ? "" : session.clientIp();
        AuditStore.HackRow row = new AuditStore.HackRow(
                mId,
                userId,
                session.characNo(),
                characName,
                code,
                param1,
                param2,
                cnt,
                totalPoints,
                detail == null ? "" : detail,
                ip);
        AuditRecorder recorder = instance;
        if (recorder != null) {
            recorder.offer(new HackEvent(row));
        } else if (session.store() != null) {
            session.store().audit().insertHackLogsAndAbnormal(List.of(row));
        }
    }

    private void offer(Event event) {
        queue.offer(event);
    }

    private void drainLoop() {
        List<Event> batch = new ArrayList<>(BATCH_MAX);
        while (running) {
            try {
                Event first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                flushBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } catch (RuntimeException e) {
                batch.clear();
            }
        }
    }

    private void flushAll() {
        List<Event> batch = new ArrayList<>(BATCH_MAX);
        while (!queue.isEmpty()) {
            queue.drainTo(batch, BATCH_MAX);
            if (batch.isEmpty()) {
                break;
            }
            try {
                flushBatch(batch);
            } catch (RuntimeException ignored) {
            }
            batch.clear();
        }
    }

    private void flushBatch(List<Event> batch) {
        List<AuditStore.DungeonRunRow> runs = new ArrayList<>();
        List<AuditStore.TradeRow> trades = new ArrayList<>();
        List<AuditStore.CeraLogRow> ceras = new ArrayList<>();
        List<AuditStore.HackRow> hacks = new ArrayList<>();
        List<AuditStore.ChatLogRow> chats = new ArrayList<>();
        List<AuditStore.EpicDropRow> epics = new ArrayList<>();
        for (Event e : batch) {
            switch (e) {
                case RunEvent r -> runs.add(r.row());
                case TradeEvent t -> trades.add(t.row());
                case CeraEvent c -> ceras.add(c.row());
                case HackEvent h -> hacks.add(h.row());
                case ChatEvent ch -> chats.add(ch.row());
                case EpicDropEvent ep -> epics.add(ep.row());
            }
        }
        if (!runs.isEmpty()) {
            store.audit().insertDungeonRuns(runs);
        }
        if (!chats.isEmpty()) {
            store.audit().insertChatLogs(chats);
        }
        if (!epics.isEmpty()) {
            store.audit().insertEpicDrops(epics);
        }
        if (!trades.isEmpty()) {
            store.audit().insertTrades(trades);
        }
        if (!ceras.isEmpty()) {
            store.audit().insertCeraLogs(ceras);
        }
        if (!hacks.isEmpty()) {
            store.audit().insertHackLogsAndAbnormal(hacks);
        }
    }

    private static String encodeKillDetail(Map<Integer, DungeonRunSummary.MonsterKillStat> stats) {
        if (stats == null || stats.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DungeonRunSummary.MonsterKillStat st : stats.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":").append(st.monsterId())
                    .append(",\"name\":\"").append(jsonEsc(st.monsterName())).append('"')
                    .append(",\"type\":").append(st.wireType())
                    .append(",\"cnt\":").append(st.count())
                    .append(",\"maxDmg\":").append(st.maxDamage())
                    .append(",\"hits\":").append(st.totalHits())
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String encodeRoomTrace(List<DungeonRunSummary.RoomTrace> rooms) {
        if (rooms == null || rooms.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DungeonRunSummary.RoomTrace r : rooms) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            long dur = r.leftAtMs() > r.enteredAtMs() ? r.leftAtMs() - r.enteredAtMs() : 0L;
            sb.append("{\"ord\":").append(r.order())
                    .append(",\"x\":").append(r.x())
                    .append(",\"y\":").append(r.y())
                    .append(",\"map\":").append(r.mapId())
                    .append(",\"rev\":").append(r.revisit() ? 1 : 0)
                    .append(",\"durMs\":").append(dur)
                    .append(",\"cubes\":").append(r.cubesUsed())
                    .append(",\"coins\":").append(r.coinsUsed())
                    .append(",\"gold\":").append(r.goldGained())
                    .append(",\"spawns\":[");
            boolean firstSpawn = true;
            for (DungeonRunSummary.SpawnInfo sp : r.spawned()) {
                if (!firstSpawn) sb.append(',');
                firstSpawn = false;
                sb.append("{\"id\":").append(sp.monsterId())
                        .append(",\"name\":\"").append(jsonEsc(sp.monsterName())).append('"')
                        .append(",\"type\":").append(sp.wireType())
                        .append(",\"hell\":").append(sp.hell() ? 1 : 0)
                        .append('}');
            }
            sb.append("],\"kills\":[");
            boolean firstKill = true;
            for (DungeonRunSummary.RoomKill k : r.kills()) {
                if (!firstKill) sb.append(',');
                firstKill = false;
                sb.append("{\"id\":").append(k.monsterId())
                        .append(",\"name\":\"").append(jsonEsc(k.monsterName())).append('"')
                        .append(",\"type\":").append(k.wireType())
                        .append(",\"hell\":").append(k.hell() ? 1 : 0)
                        .append(",\"dmg\":").append(k.damage())
                        .append(",\"hits\":").append(k.hitCount())
                        .append('}');
            }
            sb.append("],\"pickups\":[");
            boolean firstPick = true;
            for (DungeonRunSummary.RoomPickup p : r.pickups()) {
                if (!firstPick) sb.append(',');
                firstPick = false;
                sb.append("{\"id\":").append(p.itemId())
                        .append(",\"name\":\"").append(jsonEsc(p.itemName())).append('"')
                        .append(",\"cnt\":").append(p.count())
                        .append('}');
            }
            sb.append("],\"skills\":{");
            boolean firstSkill = true;
            for (var e : r.skills().entrySet()) {
                if (!firstSkill) sb.append(',');
                firstSkill = false;
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            sb.append("}}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String encodeBehavior(DungeonRunSummary s, String dungeonName) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"dungeonName\":\"").append(jsonEsc(dungeonName)).append('"')
                .append(",\"hell\":").append(s.hellAppeared() ? 1 : 0)
                .append(",\"hellDiff\":").append(s.hellDifficulty())
                .append(",\"cubes\":").append(s.clearCubesUsed())
                .append(",\"coins\":").append(s.coinsUsed())
                .append(",\"fatigue\":").append(s.fatigueSpent())
                .append(",\"gold\":").append(s.goldGained())
                .append(",\"exp\":").append(s.experienceGained())
                .append(",\"skills\":{");
        boolean firstSk = true;
        for (var e : s.skillsUsedSnapshot().entrySet()) {
            if (!firstSk) sb.append(',');
            firstSk = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("},\"items\":[");
        boolean firstIt = true;
        for (var it : s.itemsPickedSnapshot().values()) {
            if (!firstIt) sb.append(',');
            firstIt = false;
            sb.append("{\"id\":").append(it.itemId())
                    .append(",\"name\":\"").append(jsonEsc(it.itemName())).append('"')
                    .append(",\"cnt\":").append(it.count())
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEsc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String encodeItems(List<InvenItem> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (InvenItem it : items) {
            if (it == null || it.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            long count = it.equipable() ? 1 : Math.max(1L, it.stackCount());
            sb.append("{\"id\":").append(it.itemId())
                    .append(",\"cnt\":").append(count)
                    .append(",\"upg\":").append(it.upgrade())
                    .append(",\"ampType\":").append(it.amplify() == null ? 0 : it.amplify().rawTypeByte() & 0xFF)
                    .append(",\"ampVal\":").append(it.amplify() == null ? 0 : it.amplify().abilityValue())
                    .append(",\"ench\":").append(it.enchant())
                    .append('}');
        }
        sb.append(']');
        return sb.toString();
    }
}
