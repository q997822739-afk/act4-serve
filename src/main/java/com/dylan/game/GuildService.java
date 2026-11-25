package com.dylan.game;

import com.dylan.protocol.body.ItemListType;
import com.dylan.protocol.io.PacketReader;
import com.dylan.protocol.io.PacketWriter;
import com.dylan.store.GuildStore;

import com.dylan.store.StoreException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 公会（A4）。创建 / 信息 / 公告 / 升级 / 查重 / 退出 / 解散的回包照官方 DF60ACT4 实抓（probe_guild.py），
 * 邀请入会（CMD 162/163，NOTI 159/160/58）官方没有，照 A4 客户端读包函数。见 07_协议检修/社交/公会.md。
 */
public final class GuildService {
    private GuildService() {
    }

    // 错误码（官方实抓）
    static final int E_NOT_CHECKED = 0x02;
    static final int E_NO_GOLD = 0x1f;
    static final int E_NO_RIGHT = 0x22;
    static final int E_NO_EXP = 0x54;
    static final int E_MASTER_CANNOT_LEAVE = 0x57;
    static final int E_NAME_LENGTH = 0x6a;
    static final int E_NAME_TAKEN = 0x6b;
    static final int E_NAME_CHARS = 0x6c;
    static final int E_DOMAIN_LENGTH = 0x6e;
    static final int E_DOMAIN_TAKEN = 0x6f;
    static final int E_DOMAIN_CHARS = 0x70;

    /** 官方建公会扣 300000 金币（S2：50000000 → 49700000）。 */
    static final long CREATE_COST = 300_000;
    // 公会升级的经验 / 费用读 PVF etc/guild.etc（GuildParameter），不再写死。

    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9_]{3,12}");
    /** 自动域名不用下划线：客户端本地校验 {@code 008DE750} 只放行 a-z 和 0x30..0x57。 */
    private static final char[] DOMAIN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /** 查重通过的名字 / 域名，CMD 71 用（官方 CMD 71 空包，用最近一次 167/168 通过的）。 */
    private record Pending(String name, String domain) {
    }

    private static final Map<GameSession, Pending> PENDING = new ConcurrentHashMap<>();

    private static GuildStore db(GameSession s) {
        return s.store().guild();
    }

    static Optional<GuildStore.Guild> guildOf(GameSession s) {
        return s.hasCharacter() && s.store() != null ? db(s).guildOf(s.characNo()) : Optional.empty();
    }

    public static long idOf(GameSession s) {
        return guildOf(s).map(GuildStore.Guild::id).orElse(0);
    }

    public static String nameOf(GameSession s) {
        return guildOf(s).map(GuildStore.Guild::name).orElse("");
    }

    /** NOTI 2 公会名前的 u8：公会等级 - 1（没公会 0）。 */
    public static int levelByteOf(GameSession s) {
        return guildOf(s).map(g -> Math.max(0, g.level() - 1)).orElse(0);
    }

    private static com.dylan.pvf.script.GuildParameter param(GameSession s) {
        var scripts = s.scripts();
        return scripts == null ? com.dylan.pvf.script.GuildParameter.defaults() : scripts.guildParameter();
    }

    // ---- 公会经验（台服 CUser::FatigueUp → add_guild_temp_exp；CParty::clear_reward → add_guild_exp） ----
    private static final Map<GameSession, Integer> TEMP_EXP = new ConcurrentHashMap<>();

    /** 每用 1 点疲劳给临时公会经验 weight(角色等级)×疲劳÷100。 */
    public static void onFatigueUp(GameSession s, int spend) {
        if (spend <= 0 || idOf(s) == 0) {
            return;
        }
        int add = param(s).expWeight(s.level()) * spend / 100;
        if (add > 0) {
            TEMP_EXP.merge(s, add, Integer::sum);
        }
    }

    /**
     * 通关时把临时公会经验记到公会上（台服 clear_reward：add_guild_exp 后 reset_guild_temp_exp）。
     * A4 的 guild.etc 没有台服按公会人数的那张权重表，按 1 倍算。
     */
    public static void onDungeonClear(GameSession s) {
        Integer temp = TEMP_EXP.remove(s);
        if (temp == null || temp <= 0) {
            return;
        }
        long id = idOf(s);
        if (id == 0) {
            return;
        }
        db(s).addExp((int) id, temp);
        System.out.println("[公会] " + s.characName() + " 通关，公会 " + id + " 经验 +" + temp);
    }

    static String str(byte[] body) {
        PacketReader r = new PacketReader(body);
        return r.remaining() < 4 ? "" : r.string(256, "公会文本").trim();
    }

    private static byte[] ok() {
        return new byte[]{1};
    }

    /**
     * REPLY 164 成功（00418F81）：结果 1、u8 种类、两段 009E5C90（最长 0x31）。
     * 00401D40 按 snprintf(dest, size, fmt, 第二段, 第一段) 吃。
     * 种类 1 格式化 0x1A2C（自己退），种类 2 格式化 0x1A2D（踢人）。只回 {@code 01} 时客户端会把下一包的字节当成种类。
     */
    private static byte[] secedeOk(int kind, String secondIsFirstFormatArg, String firstIsSecondFormatArg) {
        PacketWriter w = new PacketWriter(48);
        w.u8(1).u8(kind);
        putFit(w, firstIsSecondFormatArg);
        putFit(w, secondIsFirstFormatArg);
        return w.toArray();
    }

    private static void putFit(PacketWriter w, String text) {
        byte[] raw = text == null ? new byte[0] : text.getBytes(PacketWriter.DEFAULT_CHARSET);
        if (raw.length >= 0x32) {
            raw = java.util.Arrays.copyOf(raw, 0x31);
        }
        w.u32(raw.length).bytes(raw);
    }

    private static byte[] fail(int code) {
        return new byte[]{0, (byte) code};
    }

    // ---- CMD 167 / 168 查重 ----
    public static void checkName(GameSession s, byte[] body) {
        String name = str(body);
        int bytes = name.getBytes(PacketWriter.DEFAULT_CHARSET).length;
        int err = bytes < 2 || bytes > 14 ? E_NAME_LENGTH
                : name.contains(" ") || name.contains("_") ? E_NAME_CHARS
                : db(s).nameTaken(name) ? E_NAME_TAKEN : 0;
        if (err != 0) {
            s.reply(167, fail(err));
            return;
        }
        Pending prev = PENDING.get(s);
        String domain = prev == null ? null : prev.domain();
        PENDING.put(s, new Pending(name, domain));
        s.reply(167, ok());
        // 创建按钮（008DF160）要名字标志 d7f2db 和网址标志 d7f2dc 都是 1，
        // 而且输入框文本还得等于点查重时抄下来的那份。d7f2dc 只有 REPLY 168 成功
        // （00418AF8）才置 1。网址框客户端不可编辑，玩家发不出 168。
        // 名字通过且还没有域名时补一发 01：保存的域名（0089BCDA 清成 0）和空输入框相等，按钮才会发 CMD 71。
        if (domain == null) {
            s.reply(168, ok());
        }
    }

    public static void checkDomain(GameSession s, byte[] body) {
        String d = str(body);
        int err = d.length() < 3 || d.length() > 12 ? E_DOMAIN_LENGTH
                : !DOMAIN.matcher(d).matches() ? E_DOMAIN_CHARS
                : db(s).domainTaken(d) ? E_DOMAIN_TAKEN : 0;
        if (err == 0) {
            PENDING.merge(s, new Pending(null, d), (a, b) -> new Pending(a.name(), d));
        }
        s.reply(168, err == 0 ? ok() : fail(err));
    }

    /** 8 位 [a-z0-9]。3..12 且不含下划线，和 CMD 168 的长度、字符规则一致。 */
    private static String randomDomain(GuildStore db) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 32; attempt++) {
            char[] chars = new char[8];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = DOMAIN_ALPHABET[random.nextInt(DOMAIN_ALPHABET.length)];
            }
            String domain = new String(chars);
            if (!db.domainTaken(domain)) {
                return domain;
            }
        }
        return null;
    }

    // ---- CMD 71 建公会 ----
    public static void create(GameSession s) {
        Pending p = PENDING.get(s);
        if (guildOf(s).isPresent() || p == null || p.name() == null || db(s).nameTaken(p.name())) {
            s.reply(71, fail(E_NOT_CHECKED));
            return;
        }
        String domain = p.domain();
        if (domain == null || domain.isEmpty() || db(s).domainTaken(domain)) {
            domain = randomDomain(db(s));
        }
        if (domain == null) {
            s.reply(71, fail(E_NOT_CHECKED));
            return;
        }
        if (s.inventory().money() < CREATE_COST) {
            s.reply(71, fail(E_NO_GOLD));
            return;
        }
        long before = s.inventory().money();
        s.inventory().setMoney(before - CREATE_COST);
        s.inventory().save();
        int id;
        try {
            id = db(s).create(p.name(), domain, s.characNo());
        } catch (StoreException e) {
            s.inventory().setMoney(before);
            s.inventory().save();
            s.reply(71, fail(E_NOT_CHECKED));
            return;
        }
        PENDING.remove(s);
        // 官方顺序：REPLY 71 → NOTI 13/0 → NOTI 192 → NOTI 71 → NOTI 2/0 → NOTI 70 → NOTI 139 01
        s.reply(71, ok());
        s.inventory().sendItemList(ItemListType.LIST_MAIN, "建公会扣金币");
        byte[] name = p.name().getBytes(PacketWriter.DEFAULT_CHARSET);
        s.send(71, new PacketWriter(8 + name.length).u32(id).u32(name.length).bytes(name).toArray());
        CharacterService.resendOwnCharacterInfo(s, "建公会");
        s.send(70, noti70(s, db(s).guildOf(s.characNo()).orElseThrow()));
        s.send(139, new byte[]{1});
        s.flush();
    }

    /**
     * NOTI 70 公会信息（DNF.exe 0042BBA2，读到公会管理器 dword_D7F290）：
     * str 公会名(≤0x32), u8 →+0x1C, u8 等级-1 →+0x3C(+1), u8, u16 会员数 →+0x48, u8 →+0x20, u32 →+0x24,
     * u8 →+0x2C, u32 公会经验 →+0x50, u16 公会点数 →+0x58, u8 n →+0x60 + n×{u32, u8}, str 公告(≤0x100) →+0x14,
     * str(≤0x11)。官方建会时名字 6 字节共 36 字节 = 名字 + 26 字节，和这个布局一致。
     * 客户端收到后在聊天框打一行"公会公告"（0x1A46），所以只在建会 / 进世界 / 升级 / 刷新时发。
     * 公会经验决定博肯菜单有没有"公会升级"（0x945A4D：职位 1/2、经验 ≥ guild.etc 当前等级那一行）。
     */
    private static byte[] noti70(GameSession s, GuildStore.Guild g) {
        byte[] n = g.name().getBytes(PacketWriter.DEFAULT_CHARSET);
        byte[] notice = g.notice() == null ? new byte[0] : g.notice().getBytes(PacketWriter.DEFAULT_CHARSET);
        if (notice.length >= 0x100) {
            notice = java.util.Arrays.copyOf(notice, 0xFF);
        }
        int members = db(s).members(g.id()).size();
        return new PacketWriter(40 + n.length + notice.length)
                .u32(n.length).bytes(n)
                .u8(0).u8(Math.max(0, g.level() - 1)).u8(0).u16(members)
                .u8(0).u32(0).u8(0)
                .u32(Math.max(0, g.exp())).u16(0)
                .u8(0)
                .u32(notice.length).bytes(notice)
                .u32(0)
                .toArray();
    }

    // ---- CMD 70 / 150 公会信息 ----
    public static void info(GameSession s, int cmd) {
        Optional<GuildStore.Guild> g = guildOf(s);
        if (g.isEmpty()) {
            s.reply(cmd, new byte[]{0});
            return;
        }
        if (cmd == 150) {
            // 刷新按钮：顺带重发 NOTI 70，公会经验 / 等级 / 点数跟着更新（客户端只从 NOTI 70 读这些）
            s.send(70, noti70(s, g.get()));
        }
        List<GuildStore.Member> all = db(s).members(g.get().id());
        // 公会窗口"表示不在线人员"勾选框（0x8D6350 → sub_9B1DE0 → sub_9B2100）：勾上发 CMD 150，不勾发 CMD 70，
        // 包体都是空的。所以 CMD 70 只回在线成员，CMD 150 回全部。以前两个都回全部 → 勾不勾一样。
        List<GuildStore.Member> members = cmd == 150 ? all
                : all.stream().filter(m -> SessionRegistry.findByName(m.name()) != null).toList();
        byte[] name = g.get().name().getBytes(PacketWriter.DEFAULT_CHARSET);
        PacketWriter w = new PacketWriter(16 + name.length + members.size() * 30);
        // REPLY 70（00418796）：u8 1, u32 公会号, str 名, u32 0, u16 会员总数, u8 本包成员数, n × 成员
        w.u8(1).u32(g.get().id()).u32(name.length).bytes(name).u32(0).u16(all.size()).u8(members.size());
        for (GuildStore.Member m : members) {
            byte[] mn = m.name().getBytes(PacketWriter.DEFAULT_CHARSET);
            GameSession on = SessionRegistry.findByName(m.name());
            boolean online = on != null;
            // 成员：str 名, str 空, u16 等级, u8 职业, u8 转职, u8 频道(ff = 不在线), u8 0, u8 0, u8 职位, u32 角色号
            // 第 4 个字节客户端 004189B9 存到成员 +0x30，0xFF 存成 -1 = 不在线（和好友名单的频道字节一样），
            // 列表里"在线 / N小时之前"按它画。官方实抓里只有看的人自己在线，所以看起来像"是不是自己"；
            // 以前照那个填，别的在线会员全显示成离线（B 服截图：已连接 2 人，列表只有自己在线）。
            // 倒数第 2 个 u8 存成员 +0xBC（sub_9B3420 → sub_9B2B30 第 10 参）。列表画名字（0x8D8AC0）：
            // +0x38（客户端按名字找到的角色对象，= 同屏能看到的人）非空且 +0xBC == 0 才用亮色 0xFFB4CCD9，
            // 否则灰色 0xFF777777；排序（0x8D7AC0）也把 +0xBC == 1 的人排到最后。以前这里填"在线 1" → 全部灰色。
            // 不同屏的在线会员 +0x38 为空，客户端本身就画灰色、靠"在线"文字区分。
            w.u32(mn.length).bytes(mn).u32(0).u16(m.level()).u8(m.job()).u8(m.growType())
                    .u8(online ? FriendService.channelOf(on) : 0xFF).u8(0).u8(0)
                    .u8(listGrade(g.get(), m)).u32(m.characNo());
        }
        s.reply(cmd, w.toArray());
    }

    /**
     * REPLY 70 成员最后的 u8 = 职位，客户端存成员 +0xC0（sub_9B2B30），公会列表"等级"列按它显示
     * （0x8D8AC0）：0 新进级公会成员（文字 15）、1 会长（11，图标 350）、2/3/4（文字 12/13/14，2 带图标 351）。
     * 以前只发会长 1 / 其他 0，CMD 136 设的职位在列表里看不出来。
     */
    private static int listGrade(GuildStore.Guild g, GuildStore.Member m) {
        if (m.characNo() == g.master() || m.grade() == GuildStore.GRADE_MASTER) {
            return 1;
        }
        return m.grade() >= 2 && m.grade() <= 4 ? m.grade() : 0;
    }

    // ---- CMD 165 公告 ----
    public static void setNotice(GameSession s, byte[] body) {
        Optional<GuildStore.Guild> g = guildOf(s);
        if (g.isEmpty() || g.get().master() != s.characNo()) {
            s.reply(165, fail(E_NO_RIGHT));
            return;
        }
        String notice = str(body);
        db(s).setNotice(g.get().id(), notice);
        s.reply(165, ok());
        byte[] n = notice.getBytes(PacketWriter.DEFAULT_CHARSET);
        byte[] noti = new PacketWriter(4 + n.length).u32(n.length).bytes(n).toArray();
        for (GameSession o : online(g.get().id(), s)) {
            o.sendNow(153, noti);
        }
    }

    // ---- CMD 76 升级 ----
    /**
     * CMD 76 公会升级。条件照客户端博肯菜单 0x945A4D + 确认窗口 0x8DE500：职位 1/2；
     * 公会累计经验 ≥ guild.etc [guild levelup cost] 当前等级那一行的经验；扣那一行的费用。最高 10 级
     * （客户端菜单要求 NOTI 2 的等级字节 < 9）。成功：REPLY 76 01（客户端自己把等级 +1、提示 0x33A）→ 扣金币 →
     * 在线会员重发 NOTI 2 + NOTI 70。
     */
    public static void levelUp(GameSession s) {
        Optional<GuildStore.Guild> g = guildOf(s);
        int grade = g.isEmpty() ? CLIENT_GRADE_MEMBER : clientGrade(s, g.get());
        if (g.isEmpty() || (grade != 1 && grade != 2)) {
            s.reply(76, fail(E_NO_RIGHT));
            return;
        }
        com.dylan.pvf.script.GuildParameter.LevelCost cost = param(s).levelCost(g.get().level());
        if (cost == null || g.get().level() >= 10 || g.get().exp() < cost.exp()) {
            s.reply(76, fail(E_NO_EXP));
            return;
        }
        if (s.inventory().money() < cost.price()) {
            s.reply(76, fail(E_NO_GOLD));
            return;
        }
        s.inventory().setMoney(s.inventory().money() - cost.price());
        s.inventory().save();
        db(s).setLevel(g.get().id(), g.get().level() + 1);
        s.reply(76, ok());
        s.inventory().sendItemList(ItemListType.LIST_MAIN, "公会升级扣金币");
        System.out.println("[公会] " + s.characName() + " 把公会 " + g.get().name() + " 升到 "
                + (g.get().level() + 1) + " 级，扣 " + cost.price());
        GuildStore.Guild now = db(s).guildOf(s.characNo()).orElse(g.get());
        for (GameSession o : online(g.get().id(), s)) {
            CharacterService.resendOwnCharacterInfo(o, "公会升级");
            o.send(70, noti70(o, now));
            o.flush();
        }
        s.flush();
    }

    /** 公会在线成员（含 self）。 */
    static List<GameSession> online(int guildId, GameSession self) {
        List<GameSession> out = new java.util.ArrayList<>();
        for (GameSession o : SessionRegistry.playing()) {
            if (o == self || (o.isActive() && idOf(o) == guildId)) {
                out.add(o);
            }
        }
        return out;
    }

    // ---- CMD 164 退出 / 踢人（str 名字 = 踢人，4 字节 0 = 自己退；官方会长退、踢人都回 57） ----
    public static void secede(GameSession s, byte[] body) {
        Optional<GuildStore.Guild> g = guildOf(s);
        if (g.isEmpty()) {
            s.reply(164, fail(E_NO_RIGHT));
            return;
        }
        String target = str(body);
        boolean master = g.get().master() == s.characNo();
        if (target.isEmpty() || target.equals(s.characName())) {
            if (master) {
                s.reply(164, fail(E_MASTER_CANNOT_LEAVE));
                return;
            }
            db(s).leave(s.characNo());
            // 第二段是名字（格式串第一个 %s），第一段是公会名。
            s.reply(164, secedeOk(1, s.characName(), g.get().name()));
            CharacterService.resendOwnCharacterInfo(s, "退出公会");
            for (GameSession o : online(g.get().id(), s)) {
                if (o != s) {
                    ChatService.system(o, s.characName() + " 离开了公会");
                }
            }
            s.flush();
            return;
        }
        GuildStore.Member victim = db(s).members(g.get().id()).stream()
                .filter(m -> m.name().equals(target)).findFirst().orElse(null);
        if (!master || victim == null || victim.characNo() == s.characNo()) {
            // 0x57 的提示是「会长不能退」。名字不在会里不要用这个码。
            // 错误码 0：减 3 之后 00419075 的无符号比较落在表外，客户端丢掉、不读穿。
            s.reply(164, fail(0));
            return;
        }
        db(s).leave(victim.characNo());
        // 0x1A2D「玩家[%s]已被强行退出[%s]公会」：第一段公会名，第二段被踢的人（00418FB0 / 00418FC9 / 00419003）。
        s.reply(164, secedeOk(2, victim.name(), g.get().name()));
        CharacterService.resendOwnCharacterInfo(s, "请出成员");
        GameSession v = SessionRegistry.findByName(target);
        if (v != null) {
            CharacterService.resendOwnCharacterInfo(v, "被请出公会");
            ChatService.system(v, "你被请出了公会");
            v.flush();
        }
        for (GameSession o : online(g.get().id(), s)) {
            if (o != s && o != v) {
                ChatService.system(o, victim.name() + " 被请出了公会");
            }
        }
        s.flush();
    }

    // ---- CMD 166 转让会长 ----
    public static void appoint(GameSession s, byte[] body) {
        Optional<GuildStore.Guild> g = guildOf(s);
        String target = str(body);
        GuildStore.Member to = g.isEmpty() ? null : db(s).members(g.get().id()).stream()
                .filter(m -> m.name().equals(target)).findFirst().orElse(null);
        if (g.isEmpty() || g.get().master() != s.characNo() || to == null || to.characNo() == s.characNo()) {
            s.reply(166, fail(E_NO_RIGHT));
            return;
        }
        db(s).setMaster(g.get().id(), s.characNo(), to.characNo());
        // REPLY 166 成功（004190AD）只有结果 1，提示 0x1A2E。失败才再读一个 u8（0x22 / 0x24 / 0x56）。
        s.reply(166, ok());
        CharacterService.resendOwnCharacterInfo(s, "转让会长");
        GameSession next = SessionRegistry.findByName(target);
        if (next != null) {
            CharacterService.resendOwnCharacterInfo(next, "成为会长");
            next.send(139, new byte[]{1});
            ChatService.system(next, "你成为了会长");
            next.flush();
        }
        s.flush();
    }

    // ---- CMD 194 解散：NOTI 188 00 00 00 00 01 00 → NOTI 2/0 → NOTI 139 05；没公会 NOTI 188 01 00 00 00 ----
    public static void dismiss(GameSession s) {
        Optional<GuildStore.Guild> g = guildOf(s);
        if (g.isEmpty() || g.get().master() != s.characNo()) {
            s.sendNow(188, new byte[]{1, 0, 0, 0});
            return;
        }
        List<GameSession> members = online(g.get().id(), s);
        db(s).dismiss(g.get().id());
        s.send(188, new byte[]{0, 0, 0, 0, 1, 0});
        // 每个成员重发自己的 NOTI 2/0（公会号清 0），announceIdentity 会发给同区域所有人
        for (GameSession o : members) {
            CharacterService.resendOwnCharacterInfo(o, "公会解散");
            o.flush();
        }
        s.send(139, new byte[]{5});
        s.flush();
    }

    // ---- 邀请入会（官方没有；CMD 162 → NOTI 159 → CMD 163 → NOTI 160 + NOTI 58） ----
    private static final Map<GameSession, GameSession> INVITES = new ConcurrentHashMap<>();

    /** REPLY 162 失败码（004191A5 跳表 0041db54）：3 不在游戏中 0x19b1、0x24 无权限 0x19af、0x27 已有公会 0x19b3。 */
    static final int E_INVITE_OFFLINE = 3;
    static final int E_INVITE_NO_RIGHT = 0x24;
    static final int E_INVITE_HAS_GUILD = 0x27;

    public static void invite(GameSession s, byte[] body) {
        Optional<GuildStore.Guild> g = guildOf(s);
        GameSession target = SessionRegistry.findByName(str(body));
        if (g.isEmpty() || !canInvite(s, g.get())) {
            s.reply(162, fail(E_INVITE_NO_RIGHT));
            return;
        }
        if (target == null || target == s) {
            s.reply(162, fail(E_INVITE_OFFLINE));
            return;
        }
        if (guildOf(target).isPresent()) {
            s.reply(162, fail(E_INVITE_HAS_GUILD));
            return;
        }
        INVITES.put(target, s);
        s.reply(162, ok());
        // NOTI 159（0042BFFB）：str 公会名（容量 0x32）, str 邀请人名（容量 0x80）。
        // 0x19AA「玩家[%s]邀请您加入[%s]公会…[%s]」的参数是 第二段, 第一段, 第一段（0042C053..0042C07E）。
        byte[] who = s.characName().getBytes(PacketWriter.DEFAULT_CHARSET);
        byte[] gn = g.get().name().getBytes(PacketWriter.DEFAULT_CHARSET);
        target.sendNow(159, new PacketWriter(8 + who.length + gn.length)
                .u32(gn.length).bytes(gn).u32(who.length).bytes(who).toArray());
    }

    public static void answer(GameSession s, byte[] body) {
        GameSession inviter = INVITES.remove(s);
        boolean yes = body.length > 0 && body[0] == 1;
        Optional<GuildStore.Guild> g = inviter == null ? Optional.empty() : guildOf(inviter);
        if (inviter == null || !inviter.isActive() || g.isEmpty()) {
            return;
        }
        byte[] me = s.characName().getBytes(PacketWriter.DEFAULT_CHARSET);
        if (!yes || guildOf(s).isPresent()) {
            // NOTI 160（0042C095）：u8 0, str 名字 → 0x19ab"玩家[%s]拒绝了您的公会邀请"
            inviter.sendNow(160, new PacketWriter(5 + me.length).u8(0).u32(me.length).bytes(me).toArray());
            return;
        }
        db(s).join(s.characNo(), g.get().id());
        inviter.sendNow(160, new PacketWriter(5 + me.length).u8(1).u32(me.length).bytes(me).toArray());
        // NOTI 58（0042B65D）：u32 公会号, u32 角色号, str 名字, str 公会名 → "[%s]已加入[%s]公会"
        byte[] gn = g.get().name().getBytes(PacketWriter.DEFAULT_CHARSET);
        byte[] noti58 = new PacketWriter(16 + me.length + gn.length).u32(g.get().id()).u32(s.characNo())
                .u32(me.length).bytes(me).u32(gn.length).bytes(gn).toArray();
        for (GameSession o : online(g.get().id(), s)) {
            o.sendNow(58, noti58);
        }
        CharacterService.resendOwnCharacterInfo(s, "加入公会");
        s.flush();
    }

    /** 公会聊天收件人：CMD 132 频道 9（页签 6）。 */
    public static List<GameSession> chatRecipients(GameSession s) {
        long id = idOf(s);
        return id == 0 ? List.of() : online((int) id, s);
    }

    /**
     * NOTI 139（0042B8B0 case 139）：u8 → sub_41E390 写客户端 [DA368C+0x2640] 公会职位。
     * 右键菜单「公会邀请」（9455C0，菜单项 9）只在职位为 1/2/3 时出现；客户端默认 5（4BEFEA）。
     * 原来只有建公会发 139 01，重登 / 导入的老公会会长拿不到职位 → 菜单没有邀请，CMD 162 发不出来。
     * 进世界补发：会长 01。
     */
    public static void sendGradeOnEnterWorld(GameSession s) {
        Optional<GuildStore.Guild> g = guildOf(s);
        if (g.isEmpty()) {
            return;
        }
        int grade = clientGrade(s, g.get());
        if (grade != CLIENT_GRADE_MEMBER) {
            s.send(139, new byte[]{(byte) grade});
        }
        // 原来只有建会时发 NOTI 70，重登后客户端公会等级 / 经验都是 0：公会窗口显示 Lv0、
        // 经验条拿表外的值比出"需要提升公会"，博肯菜单永远没有"公会升级"。
        s.send(70, noti70(s, g.get()));
    }

    /** 客户端职位：1 会长、2..4 副会长等（右键"公会邀请"只在 1/2/3 出现）、5 普通成员（4BEFEA 默认值）。 */
    static final int CLIENT_GRADE_MEMBER = 5;

    static int clientGrade(GameSession s, GuildStore.Guild g) {
        if (g.master() == s.characNo()) {
            return 1;
        }
        int stored = db(s).members(g.id()).stream().filter(m -> m.characNo() == s.characNo())
                .mapToInt(GuildStore.Member::grade).findFirst().orElse(GuildStore.GRADE_MEMBER);
        return stored >= 2 && stored <= 4 ? stored : CLIENT_GRADE_MEMBER;
    }

    private static boolean canInvite(GameSession s, GuildStore.Guild g) {
        int grade = clientGrade(s, g);
        return grade >= 1 && grade <= 3;
    }

    /**
     * CMD 136 SET_SUB_GUILD_MASTER（会长给成员设职位）。B 服实抓：u32 名字长度 + 名字（GBK）+ 职位
     * （{@code 04000000 BADABCAC 02 …}，职位 2/3）。原来没接、不回包 → "创了公会给不了权限"。
     * 回包照 A21 GuildManagementPacketBuilder.RankChanged：01 00 + 名字 + u8 职位；对方在线补 NOTI 139 新职位。
     */
    public static void setGrade(GameSession s, byte[] body) {
        Optional<GuildStore.Guild> g = guildOf(s);
        PacketReader r = new PacketReader(body);
        String target = r.remaining() < 4 ? "" : r.string(256, "成员名").trim();
        int grade = r.remaining() >= 1 ? r.u8() : 0;
        GuildStore.Member to = g.isEmpty() ? null : db(s).members(g.get().id()).stream()
                .filter(m -> m.name().equals(target)).findFirst().orElse(null);
        if (g.isEmpty() || g.get().master() != s.characNo() || to == null
                || to.characNo() == s.characNo() || grade < 2 || grade > CLIENT_GRADE_MEMBER) {
            s.reply(136, fail(E_NO_RIGHT));
            s.flush();
            return;
        }
        db(s).setGrade(to.characNo(), grade == CLIENT_GRADE_MEMBER ? GuildStore.GRADE_MEMBER : grade);
        byte[] name = to.name().getBytes(PacketWriter.DEFAULT_CHARSET);
        s.reply(136, new PacketWriter(7 + name.length).u8(1).u8(0)
                .u32(name.length).bytes(name).u8(grade).toArray());
        GameSession online = SessionRegistry.findByName(to.name());
        if (online != null) {
            online.send(139, new byte[]{(byte) grade});
            online.flush();
        }
        System.out.println("[公会] " + s.characName() + " 把 " + to.name() + " 设为职位 " + grade);
        s.flush();
    }

    public static void onLeaveWorld(GameSession s) {
        TEMP_EXP.remove(s);
        PENDING.remove(s);
        INVITES.remove(s);
        INVITES.values().removeIf(v -> v == s);
    }
}
