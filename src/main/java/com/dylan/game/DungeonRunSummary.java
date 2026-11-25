package com.dylan.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DungeonRunSummary {
    public static final int MONSTER_TYPE_COUNT = 4;
    private static final int MAX_ROOMS_TRACE = 120;
    private static final int MAX_KILLS_PER_ROOM = 200;
    private static final int MAX_PICKUPS_PER_ROOM = 100;

    public record SpawnInfo(int monsterId, String monsterName, int wireType, boolean hell) {
    }

    public record RoomKill(int monsterId, String monsterName, int wireType, boolean hell,
                           int damage, int hitCount, long killedAtMs) {
    }

    public record RoomPickup(int itemId, String itemName, int count, long pickedAtMs) {
    }

    public static final class PickedItemStat {
        private final int itemId;
        private String itemName;
        private int count;

        PickedItemStat(int itemId, String itemName) {
            this.itemId = itemId;
            this.itemName = itemName == null ? "" : itemName;
        }

        void add(String name, int amount) {
            if ((this.itemName == null || this.itemName.isBlank()) && name != null && !name.isBlank()) {
                this.itemName = name;
            }
            if (amount > 0) {
                this.count += amount;
            }
        }

        public int itemId() {
            return itemId;
        }

        public String itemName() {
            return itemName;
        }

        public int count() {
            return count;
        }
    }

    public static final class RoomTrace {
        private final int order;
        private final int x;
        private final int y;
        private final int mapId;
        private final boolean revisit;
        private final long enteredAtMs;
        private long leftAtMs;
        private final List<SpawnInfo> spawned;
        private final List<RoomKill> kills = new ArrayList<>();
        private final List<RoomPickup> pickups = new ArrayList<>();
        private final Map<Integer, Integer> skills = new LinkedHashMap<>();
        private int cubesUsed;
        private int coinsUsed;
        private long goldGained;

        RoomTrace(int order, int x, int y, int mapId, boolean revisit, long enteredAtMs,
                  List<SpawnInfo> spawned) {
            this.order = order;
            this.x = x;
            this.y = y;
            this.mapId = mapId;
            this.revisit = revisit;
            this.enteredAtMs = enteredAtMs;
            this.spawned = spawned == null ? List.of() : List.copyOf(spawned);
        }

        public int order() { return order; }
        public int x() { return x; }
        public int y() { return y; }
        public int mapId() { return mapId; }
        public boolean revisit() { return revisit; }
        public long enteredAtMs() { return enteredAtMs; }
        public long leftAtMs() { return leftAtMs; }
        public List<SpawnInfo> spawned() { return spawned; }
        public List<RoomKill> kills() { return List.copyOf(kills); }
        public List<RoomPickup> pickups() { return List.copyOf(pickups); }
        public Map<Integer, Integer> skills() { return Map.copyOf(skills); }
        public int cubesUsed() { return cubesUsed; }
        public int coinsUsed() { return coinsUsed; }
        public long goldGained() { return goldGained; }
    }

    public static final class MonsterKillStat {
        private final int monsterId;
        private final int wireType;
        private String monsterName = "";
        private int count;
        private int maxDamage;
        private int totalHits;

        MonsterKillStat(int monsterId, int wireType) {
            this.monsterId = monsterId;
            this.wireType = wireType;
        }

        void add(String name, int damage, int hitCount) {
            if ((this.monsterName == null || this.monsterName.isBlank()) && name != null && !name.isBlank()) {
                this.monsterName = name;
            }
            this.count++;
            if (damage > this.maxDamage) {
                this.maxDamage = damage;
            }
            if (hitCount > 0) {
                this.totalHits += hitCount;
            }
        }

        public int monsterId() {
            return monsterId;
        }

        public int wireType() {
            return wireType;
        }

        public String monsterName() {
            return monsterName;
        }

        public int count() {
            return count;
        }

        public int maxDamage() {
            return maxDamage;
        }

        public int totalHits() {
            return totalHits;
        }
    }

    private long experienceBefore;

    private long experienceGained;

    private int monstersKilled;

    private final int[] killsByType = new int[MONSTER_TYPE_COUNT];

    private final Map<Integer, MonsterKillStat> killStats = new LinkedHashMap<>();

    private int monstersSpawned;

    private int roomsEntered;

    private int maxDamage;

    private int dungeonId;

    private String dungeonName = "";

    private int difficulty;

    private int partySize = 1;

    private String partyMembers = "";

    private boolean hellAppeared;

    private int hellDifficulty;

    private long enterTimeMs;

    private long leaveTimeMs;

    private int clearCubesUsed;

    private int coinsUsed;

    private int fatigueSpent;

    private long goldGained;

    private final Map<Integer, Integer> skillsUsed = new LinkedHashMap<>();

    private final Map<Integer, PickedItemStat> itemsPicked = new LinkedHashMap<>();

    private final List<RoomTrace> roomTrace = new ArrayList<>();

    private RoomTrace currentRoom;

    private long startedAtNanos;

    private long elapsedMillis;

    private boolean clockStopped;

    private boolean running;

    private boolean logged;

    public void begin(long currentExperience) {
        experienceBefore = currentExperience;
        experienceGained = 0;
        monstersKilled = 0;
        java.util.Arrays.fill(killsByType, 0);
        killStats.clear();
        monstersSpawned = 0;
        roomsEntered = 0;
        maxDamage = 0;
        dungeonId = 0;
        dungeonName = "";
        difficulty = 0;
        partySize = 1;
        partyMembers = "";
        hellAppeared = false;
        hellDifficulty = 0;
        enterTimeMs = System.currentTimeMillis();
        leaveTimeMs = 0L;
        clearCubesUsed = 0;
        coinsUsed = 0;
        fatigueSpent = 0;
        goldGained = 0L;
        skillsUsed.clear();
        itemsPicked.clear();
        roomTrace.clear();
        currentRoom = null;
        startedAtNanos = System.nanoTime();
        elapsedMillis = 0;
        clockStopped = false;
        running = true;
        logged = false;
    }

    public void configureContext(int dungeonId, int difficulty, int partySize, String partyMembers) {
        this.dungeonId = dungeonId;
        this.difficulty = difficulty;
        this.partySize = Math.max(1, partySize);
        this.partyMembers = partyMembers == null ? "" : partyMembers;
    }

    public void configureRunDetails(String dungeonName, boolean hellAppeared, int hellDifficulty) {
        this.dungeonName = dungeonName == null ? "" : dungeonName;
        this.hellAppeared = hellAppeared;
        this.hellDifficulty = hellDifficulty;
    }

    public void end() {
        closeCurrentRoom(System.currentTimeMillis());
        running = false;
    }

    public boolean running() {
        return running;
    }

    public boolean logged() {
        return logged;
    }

    public void markLogged() {
        this.logged = true;
    }

    public void addExperience(int amount) {
        if (running && amount > 0) {
            experienceGained += amount;
        }
    }

    public void countKill(int wireType) {
        if (!running) {
            return;
        }
        monstersKilled++;
        if (wireType >= 0 && wireType < MONSTER_TYPE_COUNT) {
            killsByType[wireType]++;
        }
    }

    public void recordKillDetail(int monsterId, int wireType, int damage, int hitCount) {
        recordKill(monsterId, "", wireType, false, damage, hitCount);
    }

    public void recordKill(int monsterId, String monsterName, int wireType, boolean hell,
                           int damage, int hitCount) {
        if (!running) {
            return;
        }
        if (damage > maxDamage) {
            maxDamage = damage;
        }
        killStats.computeIfAbsent(monsterId, id -> new MonsterKillStat(id, wireType))
                .add(monsterName, Math.max(0, damage), Math.max(0, hitCount));
        if (currentRoom != null && currentRoom.kills.size() < MAX_KILLS_PER_ROOM) {
            currentRoom.kills.add(new RoomKill(monsterId,
                    monsterName == null ? "" : monsterName,
                    wireType, hell, Math.max(0, damage), Math.max(0, hitCount),
                    System.currentTimeMillis()));
        }
    }

    public void enterRoom(int x, int y, int mapId, boolean revisit, int clearableCount,
                          List<SpawnInfo> spawned) {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        closeCurrentRoom(now);
        if (!revisit) {
            roomsEntered++;
            monstersSpawned += Math.max(0, clearableCount);
        }
        RoomTrace room = new RoomTrace(roomTrace.size() + 1, x, y, mapId, revisit, now, spawned);
        if (roomTrace.size() < MAX_ROOMS_TRACE) {
            roomTrace.add(room);
        }
        currentRoom = room;
    }

    private void closeCurrentRoom(long now) {
        if (currentRoom != null && currentRoom.leftAtMs == 0L) {
            currentRoom.leftAtMs = now;
        }
    }

    public void recordClearCube(int count) {
        if (!running || count <= 0) {
            return;
        }
        clearCubesUsed += count;
        if (currentRoom != null) {
            currentRoom.cubesUsed += count;
        }
    }

    public void recordSkill(int skillId) {
        if (!running || skillId <= 0) {
            return;
        }
        skillsUsed.merge(skillId, 1, Integer::sum);
        if (currentRoom != null) {
            currentRoom.skills.merge(skillId, 1, Integer::sum);
        }
    }

    public void recordPickup(int itemId, String itemName, int count) {
        if (!running || count <= 0) {
            return;
        }
        itemsPicked.computeIfAbsent(itemId, id -> new PickedItemStat(id, itemName))
                .add(itemName, count);
        if (currentRoom != null && currentRoom.pickups.size() < MAX_PICKUPS_PER_ROOM) {
            currentRoom.pickups.add(new RoomPickup(itemId,
                    itemName == null ? "" : itemName, count, System.currentTimeMillis()));
        }
    }

    public void recordGold(long amount) {
        if (!running || amount <= 0) {
            return;
        }
        goldGained += amount;
        if (currentRoom != null) {
            currentRoom.goldGained += amount;
        }
    }

    public void recordCoinUsed() {
        if (!running) {
            return;
        }
        coinsUsed++;
        if (currentRoom != null) {
            currentRoom.coinsUsed++;
        }
    }

    public void recordFatigueSpent(int amount) {
        if (running && amount > 0) {
            fatigueSpent += amount;
        }
    }

    public int maxDamage() {
        return maxDamage;
    }

    public int dungeonId() {
        return dungeonId;
    }

    public String dungeonName() {
        return dungeonName;
    }

    public int difficulty() {
        return difficulty;
    }

    public int partySize() {
        return partySize;
    }

    public String partyMembers() {
        return partyMembers;
    }

    public boolean hellAppeared() {
        return hellAppeared;
    }

    public int hellDifficulty() {
        return hellDifficulty;
    }

    public long enterTimeMs() {
        return enterTimeMs;
    }

    public long leaveTimeMs() {
        return leaveTimeMs > 0 ? leaveTimeMs : System.currentTimeMillis();
    }

    public int clearCubesUsed() {
        return clearCubesUsed;
    }

    public int coinsUsed() {
        return coinsUsed;
    }

    public int fatigueSpent() {
        return fatigueSpent;
    }

    public long goldGained() {
        return goldGained;
    }

    public Map<Integer, MonsterKillStat> killStatsSnapshot() {
        return Map.copyOf(killStats);
    }

    public Map<Integer, Integer> skillsUsedSnapshot() {
        return Map.copyOf(skillsUsed);
    }

    public Map<Integer, PickedItemStat> itemsPickedSnapshot() {
        return Map.copyOf(itemsPicked);
    }

    public List<RoomTrace> roomTraceSnapshot() {
        return List.copyOf(roomTrace);
    }

    public int killsOfType(int wireType) {
        return wireType >= 0 && wireType < MONSTER_TYPE_COUNT ? killsByType[wireType] : 0;
    }

    public void countRoom(int spawned) {
        enterRoom(0, 0, 0, false, spawned, List.of());
    }

    public void stopClock() {
        if (running && !clockStopped && startedAtNanos != 0) {
            elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            leaveTimeMs = System.currentTimeMillis();
            closeCurrentRoom(leaveTimeMs);
            clockStopped = true;
        }
    }

    public long experienceGained() {
        return experienceGained;
    }

    public long experienceAfter() {
        return experienceBefore + experienceGained;
    }

    public int monstersKilled() {
        return monstersKilled;
    }

    public int monstersSpawned() {
        return monstersSpawned;
    }

    public int roomsEntered() {
        return roomsEntered;
    }

    public long elapsedMillis() {
        if (clockStopped) {
            return elapsedMillis;
        }
        return startedAtNanos == 0 ? 0 : (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    public boolean clockStarted() {
        return startedAtNanos != 0;
    }

    public static List<SpawnInfo> describeSpawns(com.dylan.pvf.GameScripts scripts,
                                                 java.util.Collection<RoomMonster> monsters) {
        if (monsters == null || monsters.isEmpty()) {
            return List.of();
        }
        List<SpawnInfo> out = new ArrayList<>(monsters.size());
        for (RoomMonster m : monsters) {
            String name = scripts == null ? ("怪物#" + m.monsterId())
                    : scripts.monsterName(m.monsterId());
            out.add(new SpawnInfo(m.monsterId(), name, m.type(), m.isHellParty()));
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return "本局击杀 " + monstersKilled + "/" + monstersSpawned + " 只、走过 "
                + roomsEntered + " 个房间、用时 " + elapsedMillis() / 1000 + " 秒、获得经验 "
                + experienceGained + "（" + experienceBefore + " → " + experienceAfter() + "）";
    }
}
