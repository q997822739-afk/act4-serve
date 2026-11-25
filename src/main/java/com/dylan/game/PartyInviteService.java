package com.dylan.game;

import com.dylan.protocol.body.Cmd2ReplyBody;
import com.dylan.protocol.body.Cmd2SetUdpIpPortRequest;
import com.dylan.protocol.body.Noti9PartyInfoBody;
import com.dylan.protocol.body.TradePackets;
import com.dylan.protocol.io.PacketWriter;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 城镇组队（A4 CMD 10/11 kind 0、12、13、14；NOTI 7/8/9）。官方 DF60ACT4 没有组队，流程照 A21 PartyHandler，
 * 布局按 DNF.exe 读包函数，见 07_协议检修/社交/组队.md。
 *
 * 和 A5 的 {@link Party}（副本实例）分开：这里只管队伍栏、队伍聊天。
 * 队长进本时 {@link DungeonService} 会把这里的队员并进同一支副本 Party。
 * CMD 2 上报 UDP 端点后，NOTI 11 改用这份地址重发。技能和坐标仍是客户端之间的 UDP，服务端不转发。
 */
public final class PartyInviteService {
    private PartyInviteService() {
    }

    /** REPLY 10 失败码（00411275：cl=错误，al=kind 0）：3 对方已退出、4 队伍已满、0x12 对方已有队伍。 */
    static final int E_GONE = 3;
    static final int E_FULL = 4;
    static final int E_HAS_PARTY = 0x12;

    static final int MAX = Noti9PartyInfoBody.MAX_MEMBERS;
    /** NOTI 9 头段 titleKind 非 0 = 不带标题串（004210DA）。 */
    static final int TITLE_PRESET = 1;

    static final class TownParty {
        final int index;
        final List<GameSession> members = new ArrayList<>();
        /**
         * 队长。原版 CParty 的 m_manager 和 m_member[4] 是分开的：委任队长（change_manager）只改 m_manager，
         * 队员槽位不动。原来这里拿「名单第 0 位」当队长，委任时把两人互换位置 → 每个客户端里自己的槽号都变了。
         */
        GameSession manager;
        /** CMD 12 设的队伍信息（原版 CParty m_titleIndex / m_title / m_userMax）。titleIndex 0 = 自定义名 title。 */
        int titleIndex = TITLE_PRESET;
        String title = "";
        int userMax = MAX;

        TownParty(int index) {
            this.index = index;
        }
    }

    private static final AtomicInteger NEXT_INDEX = new AtomicInteger(100);
    private static final Map<GameSession, TownParty> PARTIES = new ConcurrentHashMap<>();
    private static final Map<GameSession, GameSession> INVITES = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    /** s 所在城镇队伍的队长；没有队伍返回 null。 */
    public static GameSession manager(GameSession s) {
        TownParty p = PARTIES.get(s);
        if (p == null) {
            return null;
        }
        synchronized (LOCK) {
            return p.manager;
        }
    }

    private static int managerSlot(TownParty p) {
        int i = p.members.indexOf(p.manager);
        return Math.max(0, i);
    }

    public static List<GameSession> members(GameSession s) {
        TownParty p = PARTIES.get(s);
        if (p == null) {
            return List.of();
        }
        synchronized (LOCK) {
            return List.copyOf(p.members);
        }
    }

    public static void onPeer(GameSession s, int packetId, TradePackets.PeerRequest req) {
        if (packetId == 10) {
            invite(s, req);
        } else {
            respond(s, req);
        }
    }

    // ---- CMD 10 kind 0 ----
    private static void invite(GameSession s, TradePackets.PeerRequest req) {
        GameSession target = SessionRegistry.findInWorld(req.uid());
        if (target == null || target == s || !target.hasCharacter()) {
            s.reply(10, TradePackets.fail(E_GONE, TradePackets.KIND_PARTY));
            return;
        }
        if (BlacklistService.blocks(target, s)) {
            // 台服 case 0：target->isBlackUser(user) → 0x4d
            s.reply(10, TradePackets.fail(BlacklistService.E_BLOCKED, TradePackets.KIND_PARTY));
            return;
        }
        // 台服 DisPatcher_ReqPeer::dispatch_sig case 0 只有「双方都有队伍」才回 0x12，自己没队伍、对方有队伍 =
        // 申请加入对方队伍。但 A4 客户端右键只有"邀请组队"，对方看到的确认框（NOTI 7 kind 0 → 窗口 0x27）也不分
        // 邀请 / 申请，同意后反而是邀请人进了对方的队。用户决定：对方有队伍就直接回 0x12「对方已有队伍」。
        TownParty mine = PARTIES.get(s);
        if (PARTIES.containsKey(target)) {
            s.reply(10, TradePackets.fail(E_HAS_PARTY, TradePackets.KIND_PARTY));
            return;
        }
        if (mine != null && members(s).size() >= mine.userMax) {
            s.reply(10, TradePackets.fail(E_FULL, TradePackets.KIND_PARTY));
            return;
        }
        INVITES.put(target, s);
        s.reply(10, TradePackets.ok());
        // NOTI 7 kind 0（0042096C → 00420A40）：u16 邀请人, u8 0, u32 peer, u16 剩余疲劳, u16 体力。
        // 客户端把这两个值写到邀请人对象的 +0x168 / +0x16C 和 +0x118（队伍头像红蓝条）。台服
        // DisPatcher_ReqPeer case 0 填 TotalMaxFatigue-TotalFatigue 和 stamina；以前填 0 → 被邀请人
        // 一看到邀请，邀请人的血蓝条就变空（B 服截图）。
        int fatigueLeft = Math.max(0, Math.min(0xFFFF, s.fatigue().remaining()));
        target.sendNow(TradePackets.NOTI_REQUEST_PEER, new PacketWriter(13).u16(s.uniqueId())
                .u8(TradePackets.KIND_PARTY).u32(req.peer()).u16(fatigueLeft).u16(staminaOf(s)).u16(0).toArray());
    }

    // ---- CMD 11 kind 0：接受（拒绝时多一个 u16 0x85，A21 同样不回） ----
    private static void respond(GameSession s, TradePackets.PeerRequest req) {
        GameSession inviter = INVITES.remove(s);
        if (inviter == null || inviter.uniqueId() != req.uid() || !inviter.isActive() || req.refuse() != 0) {
            return;
        }
        // 台服 DisPatcher_ResPeer::dispatch_sig case 0（s = 应答方，inviter = 发起方）：
        //   都没队伍 → 新队伍，发起方当队长，应答方加入；
        //   发起方有队伍、应答方没有 → 应答方加入发起方的队伍；
        //   应答方有队伍 → REPLY 11 `00 12 00`（台服是发起方加入应答方的队，A4 用户决定不要）；满员 → `00 04 00`。
        TownParty p;
        int err = 0;
        synchronized (LOCK) {
            TownParty mineP = PARTIES.get(s);
            TownParty theirs = PARTIES.get(inviter);
            GameSession joiner;
            // 应答方已有队伍（邀请发出后才进的队）也拒绝，和 invite 的规则一致，不把邀请人拉进应答方的队
            if (mineP != null) {
                p = null;
                joiner = null;
                err = E_HAS_PARTY;
            } else if (theirs != null) {
                p = theirs;
                joiner = s;
            } else {
                p = new TownParty(NEXT_INDEX.getAndIncrement());
                p.members.add(inviter);
                p.manager = inviter;
                PARTIES.put(inviter, p);
                joiner = s;
            }
            if (p != null && p.members.size() >= p.userMax) {
                p = null;
                err = E_FULL;
            }
            if (p != null) {
                p.members.add(joiner);
                PARTIES.put(joiner, p);
            }
        }
        if (p == null) {
            s.reply(11, new byte[]{0, (byte) err, 0});
            return;
        }
        // 顺序照原版 df_game_r DisPatcher_ResPeer case 0 → JoinAssaultParty（台服源码
        // 3_服务端尝试/台服源码_zip/.../packet_dispatcher_res_peer.cpp:95-102）：
        //   send_party_realtime_info（A4 NOTI 165）→ NOTI 8 给邀请人 → send_party_ipinfo（NOTI 11）→ party_info（NOTI 9）。
        // 原版接受成功**不回 REPLY 11**（只有失败回 SendResponseError），以前这里多回了一个。
        List<GameSession> now;
        synchronized (LOCK) {
            now = List.copyOf(p.members);
        }
        byte[] realtime = realtimeBody(now);
        for (GameSession m : now) {
            m.sendNow(NOTI_REALTIME, realtime);
        }
        // NOTI 8 kind 0（00420BCE："[%s]已接受组队请求"）：u16 接受方, u8 0, u32 邀请人 uid（原版 SendAssaultJoinNotice 同）
        inviter.sendNow(TradePackets.NOTI_RESPONSE_PEER, new PacketWriter(7).u16(s.uniqueId())
                .u8(TradePackets.KIND_PARTY).u32(inviter.uniqueId()).toArray());
        sendIpInfo(now);
        broadcast(p);
    }

    static final int NOTI_IPINFO = 11;

    /** CMD 2 上报的端点。断线时丢掉。 */
    private static final Map<GameSession, Cmd2SetUdpIpPortRequest> UDP = new ConcurrentHashMap<>();

    /**
     * A4 CMD 2（0085F59F）：u8 探测类型、u32 内网、u32 外网、u16 端口（网络序）、u32 最大包、再一段字符串。
     * 回包 0041AD38：结果非 0 就结束，不再读。收到后按新地址重发 NOTI 11，进队时那份端口还是 0。
     */
    public static void noteUdp(GameSession s, byte[] plain) {
        try {
            Cmd2SetUdpIpPortRequest req = Cmd2SetUdpIpPortRequest.parse(plain);
            if (req.endpoint().getPort() > 0) {
                UDP.put(s, req);
                // 预置中转表：对方先发第一包时也能立刻转过去（等不到对方也发过就丢第一包）。
                // 注意 CMD 2 的外网 ip 那一格是「主机序 dword 的小端字节」= 真实地址反序，
                // 客户端打印时才转回来（0085FE15 ntohl → 00421E20 存 → sub_538FB0 按 HIBYTE 起打印），
                // 所以这里要把它翻回真实地址再进中转表，不然会去往一个镜像地址发。
                if (s.account() != null) {
                    UdpPartyRelay.note((int) s.account().mId(), wanEndpoint(req));
                }
                refreshIp(s);
            }
        } catch (RuntimeException ignored) {
            // 解析失败也要回成功，不然客户端停在这一包上。
        }
        s.reply(Cmd2ReplyBody.PACKET_ID, Cmd2ReplyBody.ok().toBytes());
        s.flush();
    }

    /** CMD 2 的外网端点按真实地址返回（客户端那一格是反序字节，见 {@link #noteUdp}）。 */
    private static java.net.InetSocketAddress wanEndpoint(Cmd2SetUdpIpPortRequest req) {
        byte[] raw = req.endpoint().getAddress() == null
                ? new byte[] {0, 0, 0, 0} : req.endpoint().getAddress().getAddress();
        if (raw.length != 4) {
            return req.endpoint();
        }
        byte[] real = {raw[3], raw[2], raw[1], raw[0]};
        try {
            return new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(real),
                    req.endpoint().getPort());
        } catch (java.net.UnknownHostException e) {
            return req.endpoint();
        }
    }

    private static void refreshIp(GameSession s) {
        List<GameSession> now = members(s);
        if (now.size() < 2) {
            com.dylan.game.Party party = s.party().current();
            if (party != null) {
                now = party.view().members();
            }
        }
        boolean pvp = false;
        if (now.size() < 2) {
            now = PvpLobby.roomMates(s);
            pvp = now.size() >= 2;
        }
        if (now.size() < 2) {
            return;
        }
        if (pvp) {
            sendPvpIpInfo(now);
        } else {
            sendIpInfo(now);
        }
    }

    /**
     * 决斗场 PvP 的 NOTI 11：按收件人分别构建；自己的端点照 CMD 2 上报的填，
     * 其他每个座位指向「收件人 → 该座位」的独立中转端口（{@link UdpPairRelay}）。
     *
     * <p>这是照 A21 {@code PvpPeerInfoBuilder.BuildForRelay} 做的：PvP 客户端把原始 UDP
     * 直接发到 NOTI 11 这一行的 ip:port，包体里没有 MTUPD 的收件人字段，所以只能靠端口区分收件人。
     */
    static void sendPvpIpInfo(List<GameSession> now) {
        if (!UdpPairRelay.ENABLED) {
            // 本机 / 没配公网地址：退回原来那条路（局域网直连本来就不需要中转）
            sendIpInfo(now);
            return;
        }
        for (GameSession to : now) {
            byte[] body = pvpIpInfoBody(now, to);
            if (body != null) {
                to.sendNow(NOTI_IPINFO, body);
            }
        }
    }

    /** 端口在 NOTI 11 里是网络序（客户端 00421E3F 调 ntohs），不能写 PacketWriter.u16 的小端。 */
    private static void writePortBe(PacketWriter w, int port) {
        w.u8((port >>> 8) & 0xFF).u8(port & 0xFF);
    }

    private static byte[] pvpIpInfoBody(List<GameSession> now, GameSession recipient) {
        if (recipient == null) {
            return null;
        }
        int self = -1;
        for (int i = 0; i < now.size(); i++) {
            if (now.get(i) == recipient) {
                self = i;
                break;
            }
        }
        if (self < 0) {
            return null;
        }
        PacketWriter w = new PacketWriter(1 + 22 * now.size()).u8(now.size());
        for (GameSession m : now) {
            int acc = m.account() == null ? 0 : (int) m.account().mId();
            w.u16(m.uniqueId());
            if (m == recipient) {
                // 自己那行保持 CMD 2 的真实端点，客户端用它给对端回包 / 诊断
                byte[] lan = new byte[] {127, 0, 0, 1};
                byte[] wan = lan;
                int port = 0;
                int mtu = 1500;
                int nat = 0;
                Cmd2SetUdpIpPortRequest peer = UDP.get(m);
                if (peer != null && peer.endpoint().getPort() > 0) {
                    byte[] local = peer.localIp().getAddress();
                    byte[] pub = peer.endpoint().getAddress().getAddress();
                    if (local.length == 4) {
                        lan = local;
                    }
                    wan = pub.length == 4 && !peer.endpoint().getAddress().isAnyLocalAddress() ? pub : lan;
                    port = peer.endpoint().getPort();
                    nat = peer.navType();
                    if (peer.maxDatagram() > 0) {
                        mtu = peer.maxDatagram();
                    }
                }
                for (byte b : lan) {
                    w.u8(b);
                }
                for (byte b : wan) {
                    w.u8(b);
                }
                writePortBe(w, port);
                w.u32(acc).u8(nat).u32(mtu).u8(staminaOf(m));
                continue;
            }
            int relayPort = UdpPairRelay.portFor(recipient, m);
            if (relayPort <= 0) {
                // 端口用完：退回单端口 MTUPD 中转，至少还能试一把
                byte[] ip = UdpPartyRelay.relayIp();
                for (byte b : ip) {
                    w.u8(b);
                }
                for (int i = 3; i >= 0; i--) {
                    w.u8(ip[i]);
                }
                writePortBe(w, UdpPartyRelay.port());
                w.u32(acc).u8(Cmd2SetUdpIpPortRequest.NAV_DIRECT).u32(1500).u8(staminaOf(m));
                continue;
            }
            byte[] ip = UdpPairRelay.relayIp();
            // 内网 / 外网两格都写中转地址：客户端用哪一格由它自己决定
            for (byte b : ip) {
                w.u8(b);
            }
            for (int i = 3; i >= 0; i--) {
                w.u8(ip[i]);
            }
            writePortBe(w, relayPort);
            // A21 的 PvP relay 行 nat=0（未知）；不要让客户端以为这是公网直连
            w.u32(acc).u8(Cmd2SetUdpIpPortRequest.NAV_UNKNOWN).u32(1500).u8(staminaOf(m));
        }
        return w.toArray();
    }

    /** 供 PvP 每对腿在对端还没发首包时兜底。 */
    static InetSocketAddress udpEndpointOf(GameSession s) {
        Cmd2SetUdpIpPortRequest req = UDP.get(s);
        if (req == null || req.endpoint().getPort() <= 0) {
            return null;
        }
        return wanEndpoint(req);
    }

    /**
     * NOTI 11 发给 now 里每个人。外网（s1a5.peer.host 不是回环）按收件人分别构建：自己那行照 CMD 2 上报的填，
     * 队友那行指向 {@link UdpPartyRelay} 的<b>同一个</b>端口（客户端按 MTUPD 头里的 peer 身份自己找收件人）。
     */
    static void sendIpInfo(List<GameSession> now) {
        if (!UdpPartyRelay.ENABLED || UdpPartyRelay.port() <= 0) {
            byte[] body = ipInfoBody(now, null);
            for (GameSession m : now) {
                m.sendNow(NOTI_IPINFO, body);
            }
            return;
        }
        for (GameSession to : now) {
            to.sendNow(NOTI_IPINFO, ipInfoBody(now, to));
        }
    }

    /**
     * NOTI 11 = 原版 CParty::send_party_ipinfo（cparty_ipinfo_runtime.cpp）：u8 人数，每人
     * u16 uid, u32 内网 ip, u32 外网 ip, u16 端口, u32 acc_id, u8 nat, u32 mtu, u8 疲劳/char_attr（22 字节）。
     * A4 读包 00421CFF 同序（u16 u32 u32 u16 u32 u8 u32 u8），最后一个 u8 写 +0x118。
     * 还没收到 CMD 2 时，ip 按本机 127.0.0.1、端口 0 填。
     * 最后一字节 = 体力 m_stamina（见 staminaOf）：+0x118 是城镇帧函数 009CE140 画血蓝条的值，不满 100 时客户端每 30 秒发 CMD 177。
     */
    static byte[] ipInfoBody(List<GameSession> now, GameSession recipient) {
        PacketWriter w = new PacketWriter(1 + 22 * now.size()).u8(now.size());
        for (GameSession m : now) {
            if (recipient != null && m != recipient) {
                int relayPort = UdpPartyRelay.port();
                if (relayPort > 0) {
                    writeRelayRow(w, m, relayPort);
                    continue;
                }
            }
            int acc = m.account() == null ? 0 : (int) m.account().mId();
            byte[] lan = new byte[] {127, 0, 0, 1};
            byte[] wan = lan;
            int port = 0;
            int mtu = 1500;
            int nat = 0;
            Cmd2SetUdpIpPortRequest peer = UDP.get(m);
            if (peer != null && peer.endpoint().getPort() > 0) {
                byte[] local = peer.localIp().getAddress();
                byte[] pub = peer.endpoint().getAddress().getAddress();
                if (local.length == 4) {
                    lan = local;
                }
                wan = pub.length == 4 && !peer.endpoint().getAddress().isAnyLocalAddress() ? pub : lan;
                port = peer.endpoint().getPort();
                nat = peer.navType();
                if (peer.maxDatagram() > 0) {
                    mtu = peer.maxDatagram();
                }
            }
            w.u16(m.uniqueId());
            for (byte b : lan) {
                w.u8(b);
            }
            for (byte b : wan) {
                w.u8(b);
            }
            // 端口是网络序：客户端 00421E3F 调 ntohs。原来写小端，局域网靠 LAN ip:5063 直连看不出来，
            // 外网决斗场客户端按这一行发包时端口是反的（7001 变成 22811），必坏。
            writePortBe(w, port);
            w.u32(acc).u8(nat).u32(mtu).u8(staminaOf(m));
        }
        return w.toArray();
    }

    /**
     * 队友那行改成服务器中转端点（单端口，见 {@link UdpPartyRelay}）。字节序照客户端自己：
     * 内网 ip 是 gethostbyname 的网络序（0085F56E ← +8），外网 ip / 端口是探测回包 htonl / htons 后的主机序
     * （0085FE15 / 0085FE2E），NOTI 11 读包 00421E20 原样存、端口再 ntohs（00421E3F）；客户端取用它们时
     * 走 sub_538FB0 的固定格式串 {@code "%d.%d.%d.%d"}（printf 取 HIBYTE 在前 → 内层 dword 要按小端读），
     * 所以外网 ip 那一格写反序字节才会被拼成正确地址，内网那格照原样写。
     * nat 填 1（real，不在 NAT 后）：中转端口谁发都收，相当于公网直连。
     */
    private static void writeRelayRow(PacketWriter w, GameSession m, int relayPort) {
        byte[] ip = UdpPartyRelay.relayIp();
        int acc = m.account() == null ? 0 : (int) m.account().mId();
        w.u16(m.uniqueId());
        for (byte b : ip) {
            w.u8(b);
        }
        for (int i = 3; i >= 0; i--) {
            w.u8(ip[i]);
        }
        writePortBe(w, relayPort);
        w.u32(acc).u8(Cmd2SetUdpIpPortRequest.NAV_DIRECT).u32(1500).u8(staminaOf(m));
    }

    // ---- CMD 13 退队 / CMD 14 踢人 / CMD 12 改设置 ----
    public static void leave(GameSession s) {
        if (!depart(s)) {
            s.reply(13, new byte[]{0, 0x12});
            return;
        }
        s.reply(13, new byte[]{1});
    }

    public static void kick(GameSession s, int slot) {
        TownParty p = PARTIES.get(s);
        GameSession victim = null;
        synchronized (LOCK) {
            if (p != null && p.manager == s && slot >= 0 && slot < p.members.size()
                    && p.members.get(slot) != s) {
                victim = p.members.get(slot);
            }
        }
        if (victim == null) {
            s.reply(14, new byte[]{0, 0x15});
            return;
        }
        depart(victim);
        s.reply(14, new byte[]{1});
    }

    /**
     * CMD 12 SET_PARTY_INFO = 台服 DisPatcher_SetPartyInfo（read/process/check_error）。
     * 包：u8 titleIndex；titleIndex==0 时 u32 len + 名字（GBK，容量 0x20）；u8 userMax；u16 dungIndex；u8 dungDiffi。
     * B 服实抓：预设名 {@code 05 04 …}，自定义名 {@code 00 08000000 D3C4B0B5C3DCC1D6 04 …}（"幽暗密林"）。
     * 原来不读包、NOTI 9 标题恒发预设 1 → 改队伍名没反应。
     * 没队伍：建队 + SetPartyInfoUI + send_party_info_to_all(party, 0)；
     * 有队伍：不是队长回 8，队长 SetPartyInfoUI + send_party_info_to_all(party, 1)（只发头段）。
     * 名字含屏蔽词回 0x9f（check_error）。
     */
    public static void setInfo(GameSession s, byte[] plain) {
        int titleIndex = TITLE_PRESET;
        String title = "";
        int userMax = MAX;
        try {
            com.dylan.protocol.io.PacketReader r = new com.dylan.protocol.io.PacketReader(plain);
            titleIndex = r.u8();
            if (titleIndex == 0) {
                title = r.string(0x20, "队伍名");
            }
            userMax = r.u8();
        } catch (RuntimeException e) {
            System.out.println("[组队] CMD 12 包解析失败，按默认处理：" + e.getMessage());
        }
        if (titleIndex == 0 && NameFilter.hasSlang(title)) {
            s.reply(12, new byte[]{0, (byte) 0x9f});
            return;
        }
        if (userMax < 2 || userMax > MAX) {
            userMax = MAX;
        }
        TownParty p;
        boolean created = false;
        synchronized (LOCK) {
            p = PARTIES.get(s);
            if (p == null) {
                p = new TownParty(NEXT_INDEX.getAndIncrement());
                p.members.add(s);
                p.manager = s;
                PARTIES.put(s, p);
                created = true;
            } else if (p.manager != s) {
                p = null;
            }
            if (p != null) {
                p.titleIndex = titleIndex;
                p.title = titleIndex == 0 ? title : "";
                p.userMax = userMax;
            }
        }
        if (p == null) {
            s.reply(12, new byte[]{0, 8});
            return;
        }
        s.reply(12, new byte[]{1});
        if (created) {
            broadcast(p);
        } else {
            broadcastHeader(p);
        }
    }

    /** NOTI 9 类型 1（只带头段）= 原版 send_party_info_to_all(party, 1)，队长改队伍名/人数后发。 */
    private static void broadcastHeader(TownParty p) {
        List<GameSession> now;
        Noti9PartyInfoBody.Header h;
        synchronized (LOCK) {
            now = List.copyOf(p.members);
            h = header(p);
        }
        byte[] body = Noti9PartyInfoBody.of(new Noti9PartyInfoBody.Entry(p.index,
                Noti9PartyInfoBody.HEADER_ONLY, h, null)).toBytes();
        for (GameSession m : now) {
            m.sendNow(Noti9PartyInfoBody.PACKET_ID, body);
        }
    }

    private static Noti9PartyInfoBody.Header header(TownParty p) {
        return p.titleIndex == 0
                ? Noti9PartyInfoBody.Header.named(p.title, p.userMax)
                : new Noti9PartyInfoBody.Header(p.titleIndex, "", p.userMax);
    }

    static final int CMD_REALTIME = 177;
    static final int NOTI_REALTIME = 165;

    /**
     * CMD 177（空包，= A21 0xA6 CALL_PARTY_MEMBER_REALTIME_INFO）：客户端城镇帧函数 009CE140 看到哪个队员的
     * +0x118（HP 百分比）不是 100，就每 30 秒发一次。不回 → 组队窗口一直"加载"。
     * 回 REPLY 177 `01`（0041BCA9 只读 u8 结果），再给他发 NOTI 165
     * （0042FE6C：u8 n, n × {u16 uid, u8 HP%, u8 isHelpAbuse, u8 队员槽}，HP 写 +0x118；A21 PartyRealtimeInfoBuilder 同布局）。
     * HP% 这一格是体力 m_stamina（见 staminaOf）。
     */
    public static void realtime(GameSession s) {
        // 原版 Dispatcher_CallPartyMemberRealtimeInfo::send：有队伍只发 send_party_realtime_info（全队），不回 REPLY；
        // 没有队伍回 REPLY `00 13`（19）。
        List<GameSession> now = members(s);
        if (now.isEmpty()) {
            s.reply(CMD_REALTIME, new byte[]{0, 19});
            return;
        }
        byte[] body = realtimeBody(now);
        for (GameSession m : now) {
            m.sendNow(NOTI_REALTIME, body);
        }
    }

    /**
     * 原版 get_party_realtime_info / send_party_ipinfo 这一字节填的是 m_stamina（CUserCharacInfo +0x75，0..100），
     * 不是常量 100。客户端写到 +0x118，城镇组队窗口按它画队友血蓝条；不是 100 时每 30 秒发 CMD 177 刷新。
     * 原来恒填 100 → 队友副本失败/放弃（体力罚到 10）回城后，别人看他仍是满血蓝（B 服反馈 #2）。
     */
    static int staminaOf(GameSession m) {
        if (m == null || m.characNo() == 0) {
            return 0;
        }
        return Math.clamp(m.stamina().current(), 0, StaminaService.FULL);
    }

    /** 换区域 / 回城：给城镇队伍推一次 NOTI 165（见 NeighborService.enterArea）。 */
    public static void onAreaChanged(GameSession s) {
        onStaminaChanged(s);
    }

    /** 体力变化（罚体力 / 每分钟恢复 / 交任务回满）：给他所在的城镇队伍推一次 NOTI 165，队友不用等 30 秒轮询。 */
    public static void onStaminaChanged(GameSession s) {
        List<GameSession> now = members(s);
        if (now.size() < 2) {
            return;
        }
        byte[] body = realtimeBody(now);
        for (GameSession m : now) {
            m.sendNow(NOTI_REALTIME, body);
        }
    }

    private static byte[] realtimeBody(List<GameSession> now) {
        PacketWriter w = new PacketWriter(1 + 5 * now.size()).u8(now.size());
        for (int i = 0; i < now.size(); i++) {
            w.u16(now.get(i).uniqueId()).u8(staminaOf(now.get(i))).u8(0).u8(i);
        }
        return w.toArray();
    }

    /** 断线 / 回选角：退队、清掉挂着的邀请。 */
    public static void onLeaveWorld(GameSession s, String why) {
        INVITES.remove(s);
        INVITES.values().removeIf(v -> v == s);
        UDP.remove(s);
        depart(s);
        UdpPartyRelay.forget(accOf(s));
    }

    /** s 离队：s 收 NOTI 9 类型 3；剩 1 人就解散（也收类型 3），否则其余人收新名单（第一个人当队长）。 */
    private static boolean depart(GameSession s) {
        TownParty p;
        List<GameSession> rest;
        synchronized (LOCK) {
            p = PARTIES.remove(s);
            if (p == null) {
                return false;
            }
            p.members.remove(s);
            rest = List.copyOf(p.members);
            if (p.manager == s) {
                // 原版 change_manager()：队长走了，按槽位顺序第一个有效队员接任
                p.manager = rest.isEmpty() ? null : rest.get(0);
            }
            if (rest.size() <= 1) {
                for (GameSession o : rest) {
                    PARTIES.remove(o);
                }
                p.members.clear();
            }
        }
        byte[] clear = Noti9PartyInfoBody.disband(p.index).toBytes();
        if (s.isActive()) {
            s.sendNow(Noti9PartyInfoBody.PACKET_ID, clear);
        }
        leaveTownDungeonParty(s);
        if (rest.size() <= 1) {
            for (GameSession o : rest) {
                o.sendNow(Noti9PartyInfoBody.PACKET_ID, clear);
                leaveTownDungeonParty(o);
            }
        } else {
            broadcast(p);
            sendHostInfo(p);
        }
        UdpPartyRelay.forget(accOf(s));
        return true;
    }

    /**
     * 副本里有人中途离开（PartyService.depart，副本 Party 已经把他从槽位里拿掉、换好队长）。
     * 照台服 CParty::leave_user(user, PARTY_INFO_TYPE 2)：
     * <ul>
     * <li>他退出城镇队伍，收 NOTI 9 解散（= 自己的队伍栏清空）；</li>
     * <li>剩下的人收 NOTI 9 类型 2：名单按副本 Party 的槽位发，空出来的槽填 FFFF，别人的槽号不变
     *     （副本里 P2P 按槽号认人，挪位会乱）；队长换了再发 NOTI 26 主机槽；再补 NOTI 165。</li>
     * <li>只剩一个人也不解散（台服 count 按离开前算，剩 1 人的队伍照样留着），他自己打完。</li>
     * </ul>
     * 城镇队伍列表照旧去掉这个人；剩下的人回城时由 {@link #resync} 按新顺序重发一次完整名单。
     */
    public static void onLeftDungeonParty(GameSession leaver, Party.Departure gone) {
        TownParty p;
        synchronized (LOCK) {
            p = PARTIES.remove(leaver);
            if (p != null) {
                p.members.remove(leaver);
                int ms = gone.view().managerSlot();
                GameSession newManager = ms >= 0 && ms < gone.view().slots().size() ? gone.view().slots().get(ms) : null;
                if (p.manager == leaver || newManager != null) {
                    p.manager = newManager != null && p.members.contains(newManager)
                            ? newManager : (p.members.isEmpty() ? null : p.members.get(0));
                }
                if (p.members.isEmpty()) {
                    p = null;
                }
            }
        }
        int index = p != null ? p.index : gone.view().partyIndex();
        if (leaver.isActive()) {
            leaver.sendNow(Noti9PartyInfoBody.PACKET_ID, Noti9PartyInfoBody.disband(index).toBytes());
        }
        UdpPartyRelay.forget(accOf(leaver));
        List<Noti9PartyInfoBody.Member> roster = new ArrayList<>(MAX);
        List<GameSession> rest = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            GameSession m = i < gone.view().slots().size() ? gone.view().slots().get(i) : null;
            if (m == null) {
                roster.add(Noti9PartyInfoBody.Member.empty());
            } else {
                SessionRegistry.Presence pr = SessionRegistry.presenceOf(m);
                roster.add(Noti9PartyInfoBody.Member.of(m.uniqueId(), pr == null ? -1 : pr.sex()));
                rest.add(m);
            }
        }
        int managerSlot = Math.max(0, gone.view().managerSlot());
        byte[] body = Noti9PartyInfoBody.of(new Noti9PartyInfoBody.Entry(index,
                Noti9PartyInfoBody.ROSTER_ONLY, null, new Noti9PartyInfoBody.Roster(roster, managerSlot))).toBytes();
        byte[] host = new com.dylan.protocol.body.Noti26Body(managerSlot).toBytes();
        byte[] realtime = rest.isEmpty() ? null : realtimeBody(rest);
        for (GameSession m : rest) {
            m.sendNow(Noti9PartyInfoBody.PACKET_ID, body);
            if (gone.managerChanged()) {
                m.sendNow(com.dylan.protocol.body.Noti26Body.PACKET_ID, host);
            }
            m.sendNow(NOTI_REALTIME, realtime);
            m.flush();
        }
        System.out.println("[组队] " + leaver.characName() + " 中途离开副本，剩 " + rest.size() + " 人"
                + (gone.managerChanged() ? "，队长换到槽 " + managerSlot : ""));
    }

    /** 整队回城后按城镇队伍的当前顺序重发一次完整名单（副本里有人走过，客户端那份名单有空槽）。 */
    public static void resync(GameSession anyMember) {
        TownParty p = PARTIES.get(anyMember);
        if (p == null) {
            return;
        }
        broadcast(p);
        sendHostInfo(p);
    }

    /**
     * 进过本的城镇队伍会在 PartyRegistry 里留一支多人副本 Party（DungeonService.adoptTownParty）。
     * 退队/被踢只清了城镇队伍，这支 Party 还挂着原来的队员 → 下次单人进本走 checkMemberArea，
     * 别人不在同区域就一直进不去（B 服反馈"队伍里被踢了后无法再进副本"）。人在城镇时一并退掉。
     */
    private static void leaveTownDungeonParty(GameSession s) {
        Party old = PartyRegistry.partyOf(s);
        if (old == null || old.singlePlay() || old.state() != DungeonInstance.STATE_TOWN) {
            return;
        }
        s.party().onLeaveDungeon("退出城镇队伍");
    }

    // ---- CMD 131 CHANGE_HOST 委任队长：u8 目标队员槽 ----
    /**
     * 照原版 Dispatcher_ChangeHost → CParty::change_manager(user, slot)：
     * 槽号越界 / 空槽回错误 0x15，不是队长回 8，委任给自己回 0x12（SendCmdErrorPacket = REPLY 131 `00 码`）；
     * 成功只改队长、队员位置不动 → send_host_info（NOTI 26 = 新主机槽号）→ send_party_info_to_all(type 2 名单)，不回 REPLY。
     */
    public static void changeHost(GameSession s, int slot) {
        TownParty p = PARTIES.get(s);
        if (p == null) {
            s.reply(131, new byte[]{0, 0x13});
            return;
        }
        int error = 0;
        synchronized (LOCK) {
            if (slot < 0 || slot >= p.members.size()) {
                error = 0x15;
            } else if (p.manager != s) {
                error = 8;
            } else if (p.members.get(slot) == s) {
                error = 0x12;
            } else {
                p.manager = p.members.get(slot);
            }
        }
        if (error != 0) {
            s.reply(131, new byte[]{0, (byte) error});
            return;
        }
        // 进过本的城镇队伍挂着一支副本 Party（队长是旧的），退掉，下次进本按新队长重建
        for (GameSession m : members(s)) {
            leaveTownDungeonParty(m);
        }
        sendHostInfo(p);
        broadcastRoster(p);
    }

    /** NOTI 26 = 原版 send_host_info（header 0x1a）：u8 主机（= 队长）所在槽号，发全队。 */
    private static void sendHostInfo(TownParty p) {
        List<GameSession> now;
        int slot;
        synchronized (LOCK) {
            now = List.copyOf(p.members);
            slot = managerSlot(p);
        }
        byte[] body = new com.dylan.protocol.body.Noti26Body(slot).toBytes();
        for (GameSession m : now) {
            m.sendNow(com.dylan.protocol.body.Noti26Body.PACKET_ID, body);
        }
    }

    private static int accOf(GameSession s) {
        return s.account() == null ? 0 : (int) s.account().mId();
    }

    /** NOTI 9 类型 0：头段（titleKind 1 不带串, 容量 4）+ 4 格名单（u16 uid, u8 sex）+ 队长槽。 */
    /** NOTI 9 类型 2（只带名单 + 队长槽）= 原版 send_party_info_to_all(party, 2)，委任队长后发。 */
    private static void broadcastRoster(TownParty p) {
        List<GameSession> now;
        int mgr;
        synchronized (LOCK) {
            now = List.copyOf(p.members);
            mgr = managerSlot(p);
        }
        byte[] body = Noti9PartyInfoBody.of(new Noti9PartyInfoBody.Entry(p.index,
                Noti9PartyInfoBody.ROSTER_ONLY, null, roster(now, mgr))).toBytes();
        for (GameSession m : now) {
            m.sendNow(Noti9PartyInfoBody.PACKET_ID, body);
        }
    }

    private static Noti9PartyInfoBody.Roster roster(List<GameSession> now, int managerSlot) {
        List<Noti9PartyInfoBody.Member> list = new ArrayList<>(now.size());
        for (GameSession m : now) {
            SessionRegistry.Presence pr = SessionRegistry.presenceOf(m);
            list.add(Noti9PartyInfoBody.Member.of(m.uniqueId(), pr == null ? -1 : pr.sex()));
        }
        return Noti9PartyInfoBody.Roster.of(list, managerSlot);
    }

    private static void broadcast(TownParty p) {
        List<GameSession> now;
        int mgr;
        Noti9PartyInfoBody.Header h;
        synchronized (LOCK) {
            now = List.copyOf(p.members);
            mgr = managerSlot(p);
            h = header(p);
        }
        List<Noti9PartyInfoBody.Member> roster = new ArrayList<>(now.size());
        for (GameSession m : now) {
            SessionRegistry.Presence pr = SessionRegistry.presenceOf(m);
            roster.add(Noti9PartyInfoBody.Member.of(m.uniqueId(), pr == null ? -1 : pr.sex()));
        }
        byte[] body = Noti9PartyInfoBody.full(p.index,
                h,
                Noti9PartyInfoBody.Roster.of(roster, mgr)).toBytes();
        // 名单之后马上补 NOTI 165（各人体力），客户端就不用等 30 秒再发 CMD 177 才显示队员（A21 也是名单后再发 0x99）
        byte[] realtime = realtimeBody(now);
        for (GameSession m : now) {
            m.sendNow(Noti9PartyInfoBody.PACKET_ID, body);
            m.sendNow(NOTI_REALTIME, realtime);
        }
    }
}
