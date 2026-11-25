package com.dylan.store;

import com.dylan.net.ServerConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class GameStore implements Closeable {
    private final HikariDataSource dataSource;
    private final HikariDataSource auditDataSource;

    private final MailStore mail;
    private final DeathTowerStore deathTower;
    private final EventStore event;
    private final QuestStore quest;
    private final PremiumStore premium;
    private final CreatureStore creature;
    private final AvatarStore avatar;
    private final GmStore gm;
    private final SecurityStore security;
    private final AdminStore admin;
    private final AuditStore audit;
    private final SkillStore skill;
    private final PvpStore pvp;
    private final DungeonClearStore dungeonClear;
    private final GameOptionStore gameOption;
    private final CeraStore cera;
    private final InventoryStore inventory;
    private final AccountStore account;
    private final CharacterStore character;
    private final GrowthTableStore growthTable;
    private final DailyGrantStore dailyGrant;
    private final GuildStore guild;
    private final MentorStore mentor;
    private final FriendStore friend;
    private final ElixirStore elixir;
    private final ContinuousItemStore continuousItem;
    private final ExpertJobStore expertJob;
    private final EmotionStore emotion;
    private final ItemLockStore itemLock;
    private final SlangStore slang;
    private final StaminaStore stamina;

    private GameStore(HikariDataSource dataSource, HikariDataSource auditDataSource, boolean auditMysql) {
        this.dataSource = dataSource;
        this.auditDataSource = auditDataSource;
        HikariDataSource auditTarget = auditDataSource != null ? auditDataSource : dataSource;
        this.mail = new MailStore(dataSource);
        this.deathTower = new DeathTowerStore(dataSource);
        this.event = new EventStore(dataSource);
        this.quest = new QuestStore(dataSource);
        this.premium = new PremiumStore(dataSource);
        this.creature = new CreatureStore(dataSource);
        this.avatar = new AvatarStore(dataSource);
        this.gm = new GmStore(dataSource);
        this.security = new SecurityStore(auditTarget);
        this.admin = new AdminStore(dataSource);
        this.audit = new AuditStore(auditTarget, auditMysql);
        this.skill = new SkillStore(dataSource);
        this.pvp = new PvpStore(dataSource);
        this.dungeonClear = new DungeonClearStore(dataSource);
        this.gameOption = new GameOptionStore(dataSource);
        this.cera = new CeraStore(dataSource);
        this.inventory = new InventoryStore(dataSource);
        this.account = new AccountStore(dataSource);
        this.character = new CharacterStore(dataSource);
        this.growthTable = new GrowthTableStore(dataSource);
        this.dailyGrant = new DailyGrantStore(dataSource);
        this.guild = new GuildStore(dataSource);
        this.mentor = new MentorStore(dataSource);
        this.friend = new FriendStore(dataSource);
        this.elixir = new ElixirStore(dataSource);
        this.continuousItem = new ContinuousItemStore(dataSource);
        this.expertJob = new ExpertJobStore(dataSource);
        this.emotion = new EmotionStore(dataSource);
        this.itemLock = new ItemLockStore(dataSource);
        this.slang = new SlangStore(dataSource);
        this.stamina = new StaminaStore(dataSource);
    }

    public EventStore event() {
        return event;
    }

    public MailStore mail() {
        return mail;
    }

    public DeathTowerStore deathTower() {
        return deathTower;
    }

    public QuestStore quest() {
        return quest;
    }

    public PremiumStore premium() {
        return premium;
    }

    public CreatureStore creature() {
        return creature;
    }

    public AvatarStore avatar() {
        return avatar;
    }

    public GmStore gm() {
        return gm;
    }

    public SecurityStore security() {
        return security;
    }

    public AdminStore admin() {
        return admin;
    }

    public AuditStore audit() {
        return audit;
    }

    public SkillStore skill() {
        return skill;
    }

    public PvpStore pvp() {
        return pvp;
    }

    public DungeonClearStore dungeonClear() {
        return dungeonClear;
    }

    public GameOptionStore gameOption() {
        return gameOption;
    }

    public CeraStore cera() {
        return cera;
    }

    public InventoryStore inventory() {
        return inventory;
    }

    public AccountStore account() {
        return account;
    }

    public CharacterStore character() {
        return character;
    }

    public GrowthTableStore growthTable() {
        return growthTable;
    }

    public DailyGrantStore dailyGrant() {
        return dailyGrant;
    }

    public GuildStore guild() {
        return guild;
    }

    public FriendStore friend() {
        return friend;
    }

    public MentorStore mentor() {
        return mentor;
    }

    public ElixirStore elixir() {
        return elixir;
    }

    public ContinuousItemStore continuousItem() {
        return continuousItem;
    }

    public ExpertJobStore expertJob() {
        return expertJob;
    }

    public EmotionStore emotion() {
        return emotion;
    }

    public StaminaStore stamina() {
        return stamina;
    }

    public ItemLockStore itemLock() {
        return itemLock;
    }

    public SlangStore slang() {
        return slang;
    }

    public static GameStore open(String url) {
        return open(url, ServerConfig.AUDIT_DB_URL);
    }

    public static GameStore open(String url, String auditUrl) {
        HikariConfig config = new HikariConfig();
        String mainJdbc = normalizeSqliteUrl(url);
        config.setJdbcUrl(mainJdbc);
        config.setPoolName("S1A5");
        config.setMaximumPoolSize(ServerConfig.DB_POOL_SIZE);
        config.setConnectionTimeout(ServerConfig.DB_CONNECTION_TIMEOUT_MS);
        config.setLeakDetectionThreshold(ServerConfig.DB_LEAK_DETECTION_MS);
        config.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON");
        HikariDataSource ds = new HikariDataSource(config);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String ddl : Schema.STATEMENTS) {
                s.execute(ddl);
            }
            for (String ddl : Schema.INDEXES) {
                s.execute(ddl);
            }
            GrowthTableStore.seedMonsterReward(c);
            GrowthTableStore.seedSpReward(c);
            EventStore.seedEventInfo(c);
            SlangStore.seedSlangList(c);
            SlangStore.seedSlangListName(c);
        } catch (SQLException e) {
            ds.close();
            throw new StoreException("建表失败：" + e.getMessage(), e);
        }

        String auditJdbc = (auditUrl == null || auditUrl.isBlank()) ? mainJdbc : normalizeSqliteUrl(auditUrl);
        boolean auditMysql = auditJdbc.startsWith("jdbc:mysql:");
        HikariDataSource auditDs = null;
        if (!auditJdbc.equals(mainJdbc)) {
            HikariConfig auditConfig = new HikariConfig();
            auditConfig.setJdbcUrl(auditJdbc);
            auditConfig.setPoolName("S1A5-Audit");
            auditConfig.setConnectionTimeout(ServerConfig.DB_CONNECTION_TIMEOUT_MS);
            auditConfig.setLeakDetectionThreshold(ServerConfig.DB_LEAK_DETECTION_MS);
            if (auditMysql) {
                if (!ServerConfig.AUDIT_DB_USER.isEmpty()) {
                    auditConfig.setUsername(ServerConfig.AUDIT_DB_USER);
                    auditConfig.setPassword(ServerConfig.AUDIT_DB_PASS);
                }
                auditConfig.setMaximumPoolSize(4);
            } else {
                auditConfig.setMaximumPoolSize(1);
                auditConfig.setConnectionInitSql(
                        "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000");
            }
            auditDs = new HikariDataSource(auditConfig);
        }
        HikariDataSource schemaTarget = auditDs != null ? auditDs : ds;
        try (Connection c = schemaTarget.getConnection(); Statement s = c.createStatement()) {
            if (auditMysql) {
                for (String ddl : AuditSchema.MYSQL_TABLES) {
                    s.execute(ddl);
                }
            } else {
                for (String ddl : AuditSchema.TABLES) {
                    s.execute(ddl);
                }
                for (String ddl : AuditSchema.INDEXES) {
                    s.execute(ddl);
                }
            }
            ensureDungeonRunColumns(c, s, auditMysql);
        } catch (SQLException e) {
            if (auditDs != null) {
                auditDs.close();
            }
            ds.close();
            throw new StoreException("审计库建表失败：" + e.getMessage(), e);
        }
        return new GameStore(ds, auditDs, auditMysql);
    }

    private static void ensureDungeonRunColumns(Connection c, Statement s, boolean mysql)
            throws SQLException {
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (java.sql.ResultSet rs = s.executeQuery("SELECT * FROM dungeon_run_log WHERE 1 = 0")) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                existing.add(md.getColumnName(i).toLowerCase(java.util.Locale.ROOT));
            }
        }
        String[][] migrations = mysql
                ? AuditSchema.DUNGEON_RUN_MYSQL_MIGRATIONS
                : AuditSchema.DUNGEON_RUN_SQLITE_MIGRATIONS;
        for (String[] col : migrations) {
            if (!existing.contains(col[0].toLowerCase(java.util.Locale.ROOT))) {
                s.execute("ALTER TABLE dungeon_run_log ADD COLUMN " + col[0] + " " + col[1]);
            }
        }
    }

    private static String normalizeSqliteUrl(String url) {
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix) || url.startsWith(prefix + ":memory:")) {
            return url;
        }
        String path = url.substring(prefix.length()).replace('\\', '/');
        java.nio.file.Path parent = java.nio.file.Path.of(path).getParent();
        if (parent != null) {
            try {
                java.nio.file.Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new StoreException("建不出库文件的目录 " + parent + "：" + e.getMessage(), e);
            }
        }
        return prefix + path;
    }

    @Override
    public void close() {
        if (auditDataSource != null) {
            auditDataSource.close();
        }
        dataSource.close();
    }
}
