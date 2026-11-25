package com.dylan.game;

import com.dylan.net.ServerConfig;
import com.dylan.protocol.body.ChatType;
import com.dylan.protocol.body.Cmd132A4ChatRequest;
import com.dylan.protocol.body.Cmd132CreatureScriptMessageRequest;
import com.dylan.protocol.body.Cmd17SendMessageRequest;
import com.dylan.protocol.body.Cmd65ReplyBody;
import com.dylan.protocol.body.Noti131CreatureScriptMessageBody;
import com.dylan.protocol.body.Noti12MessageBody;
import com.dylan.protocol.body.Noti65WhisperBody;

public final class ChatService {
    private ChatService() {
    }

    public static int onMessage(GameSession s, Cmd17SendMessageRequest request) {
        if (!s.hasCharacter()) {
            return 0;
        }
        if (ChatType.isServerOnly(request.msgType())) {
            s.fail("角色 " + s.characNo() + "（" + s.characName() + "）发来下行专用的频道号 "
                    + ChatType.name(request.msgType()) + "，这条包不是原版客户端发的");
            return 0;
        }

        String text = Noti12MessageBody.fit(Noti12MessageBody.sanitize(request.text()));
        if (text.isBlank()) {
            return 0;
        }

        int type = request.msgType();
        if (looksLikeGmInput(s, text)) {
            return gmCommand(s, text);
        }
        if (ChatType.isWhisper(type)) {
            // A4 密语发包点 008F7FAC：频道固定 0x21，接着 u16、u32、两段 00436B60 字符串（正文、名字）。
            // 回包用 NOTI 65（00422354），见 Noti65WhisperBody。
            if (ServerConfig.A4) {
                return whisper(s, type, text, request);
            }
            AuditRecorder.recordChat(s, "私聊", request.targetName(), text);
            system(s, "单人版没有这条聊天频道");
            return 0;
        }
        if (ServerConfig.A4 && type == ChatType.GUILD) {
            return guildChat(s, type, text);
        }
        if (ServerConfig.A4 && type == ChatType.MEMBER) {
            return mentorChat(s, type, text);
        }
        AuditRecorder.recordChat(s, channelLabel(type), "", text);
        if (ServerConfig.A4 && ChatType.spendsItem(type)) {
            return megaphone(s, type, text, request.itemSlot());
        }
        if (ChatType.spendsItem(type) || type == ChatType.MEMBER || type == ChatType.GUILD) {
            // 频道号写出来：师徒 / 附近这些页签发的号还没对上（CMD 17 发包点在 VMP 段 011001CE，静态看不到页签映射）
            system(s, "这条聊天频道（" + type + "）服务端还没接");
            System.out.println("[聊天] " + s.characName() + " 发了没接的频道 " + type + "（" + ChatType.name(type) + "）");
            return 0;
        }
        if (type == ChatType.PARTY) {
            return broadcastToParty(s, Noti12MessageBody.PACKET_ID, type, text);
        }
        return broadcastToArea(s, Noti12MessageBody.PACKET_ID, type, text);
    }

    /**
     * A4 公会聊天走 CMD 17 频道 6（2026-10-28 测试服实抓 {@code 06 0E 00 …}），以前落进「单人版没有」那支。
     * 回包用 NOTI 65（00422354，带发送者名字）：成员可能在别的区域，NOTI 12 只带 uid，客户端 009393BD 在本区域找不到人。
     * A21 ChatHandler.SendGuildMessageAsync 同样用带名字的 MESSAGE_OTHER_CHANNEL 发给全体在线成员。
     */
    private static int guildChat(GameSession s, int type, String text) {
        AuditRecorder.recordChat(s, "公会", "", text);
        if (GuildService.idOf(s) == 0) {
            system(s, "『公会聊天只能在加入公会后才可使用』");
            return 0;
        }
        java.util.LinkedHashSet<GameSession> to = new java.util.LinkedHashSet<>();
        to.add(s);
        to.addAll(GuildService.chatRecipients(s));
        byte[] body = new Noti65WhisperBody(type, 0, s.characName(), text).toBytes();
        for (GameSession o : to) {
            o.sendNow(Noti65WhisperBody.PACKET_ID, body);
        }
        return to.size();
    }

    /**
     * 喇叭（CMD 17 频道 13/14/15/34）。B 服实抓 {@code 0D 0000 <u32 背包槽> <u32 长度> 正文}：
     * u32 是背包里喇叭所在的格子（0x2A/0x30/0x36/0x41，都在消耗品页 41..72）。
     * 扣 1 个喇叭 → 全服在线角色收 NOTI 65（带名字；NOTI 12 只带 uid，别的区域的人客户端找不到名字）。
     */
    private static int megaphone(GameSession s, int type, String text, int slot) {
        var inv = s.inventory();
        var items = inv == null ? null : inv.inventory();
        if (items == null || slot < BagLayout.FIRST_ITEM_SLOT || slot >= items.inventory().slots()
                || items.inventory().get(slot).isEmpty()) {
            system(s, "没有找到喇叭道具");
            return 0;
        }
        int itemId = (int) items.inventory().get(slot).itemId();
        if (inv.deleteItem(com.dylan.protocol.body.ItemListType.LIST_MAIN, slot, itemId, 1) <= 0) {
            system(s, "喇叭道具扣除失败");
            return 0;
        }
        inv.sendItemUpdate(com.dylan.protocol.body.ItemListType.LIST_MAIN, "用喇叭", slot);
        byte[] body = new Noti65WhisperBody(type, 0, s.characName(), text).toBytes();
        int[] sent = {0};
        SessionRegistry.forEachInWorld((o, presence) -> {
            if (o.hasCharacter() && o.isActive()) {
                o.sendNow(Noti65WhisperBody.PACKET_ID, body);
                sent[0]++;
            }
        });
        System.out.println("[喇叭] " + s.characName() + " 频道 " + type + " 物品 " + itemId
                + " 槽 " + slot + " → " + sent[0] + " 人：" + text);
        return sent[0];
    }

    /**
     * 师徒频道（CMD 17 频道 8）。台服 send_chat_msg case 8 → monitor 按 memberkey 发给整个师门，
     * 再由 Inter_MonitorNoticeMemberChatMsg 给每人一条 NOTI 65 频道 8。师门 = 师父 + 他的全部徒弟
     * （本服不允许三层，见 checkPair）；自己也收一条（客户端不本地回显这个频道）。
     * 以前落进「服务端还没接」，师徒之间完全不能聊天。
     */
    private static int mentorChat(GameSession s, int type, String text) {
        AuditRecorder.recordChat(s, "师徒", "", text);
        var store = s.store() == null ? null : s.store().mentor();
        if (store == null) {
            return 0;
        }
        int me = s.characNo();
        int head = store.masterOf(me).map(com.dylan.store.MentorStore.Person::characNo).orElse(me);
        java.util.List<com.dylan.store.MentorStore.Person> group = new java.util.ArrayList<>();
        store.byId(head).ifPresent(group::add);
        group.addAll(store.apprenticesOf(head));
        if (group.size() < 2) {
            system(s, "没有师徒关系，无法使用师徒聊天");
            return 0;
        }
        java.util.LinkedHashSet<GameSession> to = new java.util.LinkedHashSet<>();
        to.add(s);
        for (var p : group) {
            GameSession o = SessionRegistry.findByName(p.name());
            if (o != null && o.hasCharacter() && o.isActive()) {
                to.add(o);
            }
        }
        byte[] body = new Noti65WhisperBody(type, 0, s.characName(), text).toBytes();
        for (GameSession o : to) {
            o.sendNow(Noti65WhisperBody.PACKET_ID, body);
        }
        return to.size();
    }

    private static int whisper(GameSession s, int type, String text, Cmd17SendMessageRequest request) {
        // 名字优先：台服按 u16 找人是因为台服客户端这里填的是对方 uid；A4 客户端这个 u16 不可靠
        // （1:1 窗口填 0、师徒频道填 2），按 u16 找会投错人甚至投给自己。只有没带名字（旧包）才用 u16。
        String name = request.targetName() == null ? "" : request.targetName().trim();
        GameSession target = !name.isEmpty()
                ? SessionRegistry.findByName(name)
                : request.targetUserId() != 0 ? SessionRegistry.findByUniqueId(request.targetUserId()) : null;
        if (target == null || !target.hasCharacter() || !target.isActive()) {
            AuditRecorder.recordChat(s, "私聊",
                    request.targetName() == null ? "" : request.targetName().trim(), text);
            system(s, "对方不在线");
            return 0;
        }
        AuditRecorder.recordChat(s, "私聊", target.characName(), text);
        if (BlacklistService.blockedEitherWay(s, target)) {
            // 台服 send_chat_msg / check_peerTypemsg_sameWorldUser：任一方拉黑了另一方，不送达，
            // 回发送方 NOTI 65 结果 0x4d（名字 = 对方）。
            s.sendNow(Noti65WhisperBody.PACKET_ID,
                    new Noti65WhisperBody(type, BlacklistService.E_BLOCKED, target.characName(), text).toBytes());
            return 0;
        }
        // 密语必须走 NOTI 65（带名字）；NOTI 12 名字为 NULL，客户端 008F6BE0 崩溃。
        // 只发给对方：发送方客户端自己会本地回显（"我: xxx"）。以前再给发送方回一条
        // "对方名字: xxx"，密语窗口里每句话都出现两遍、第二遍还挂在对方名下（B 服反馈"窗口聊天重复"）。
        target.sendNow(Noti65WhisperBody.PACKET_ID,
                new Noti65WhisperBody(type, 0, s.characName(), text).toBytes());
        return 1;
    }

    /**
     * A4 主聊天框（CMD 132）。收件人照 A21 ChatHandler.ResolveRecipients：发送者自己也收
     * （客户端不本地回显，NOTI 12 显示函数 DNF.exe 009392D0 按 uid 找名字），
     * 普通 = 同区域（NeighborService 那套 inArea），队伍 = 队员，其余频道（5/9/10，单机版没有的服务）只回给自己。
     * NOTI 12 布局 u8 频道, u16 uid, string（DNF.exe 00421F2D）和 A5 一致。
     */
    /** A4 公会聊天：页签 6 → 线上频道 9（007E133F）。 */
    static final int A4_GUILD = 9;

    public static int onA4Chat(GameSession s, Cmd132A4ChatRequest request) {
        if (!s.hasCharacter()) {
            return 0;
        }
        String text = Noti12MessageBody.fit(Noti12MessageBody.sanitize(request.text()));
        if (text.isBlank()) {
            return 0;
        }
        if (looksLikeGmInput(s, text)) {
            return gmCommand(s, text);
        }
        int type = request.msgType();
        String chLabel = type == A4_GUILD ? "公会" : channelLabel(type);
        AuditRecorder.recordChat(s, chLabel, "", text);
        java.util.LinkedHashSet<GameSession> to = new java.util.LinkedHashSet<>();
        to.add(s);
        SessionRegistry.Presence p = SessionRegistry.presenceOf(s);
        boolean inTown = p != null && p.inTown();
        if (type == ChatType.PARTY || (type == ChatType.NORMAL && !inTown)) {
            // 城镇队伍（PartyInviteService）优先；没有就是 A5 的单人副本队伍
            java.util.List<GameSession> town = PartyInviteService.members(s);
            to.addAll(town.isEmpty() ? s.party().chatRecipients() : town);
        } else if (type == ChatType.NORMAL) {
            to.addAll(SessionRegistry.inArea(p.town(), p.area(), s));
        } else if (type == A4_GUILD) {
            if (GuildService.idOf(s) == 0) {
                system(s, "『公会聊天只能在加入公会后才可使用』");
                return 0;
            }
            to.addAll(GuildService.chatRecipients(s));
        }
        byte[] body = Noti12MessageBody.from(type, s.uniqueId(), text).toBytes();
        for (GameSession o : to) {
            o.sendNow(Noti12MessageBody.PACKET_ID, body);
        }
        return to.size();
    }

    /**
     * A4 的 CMD 132 优先判断是否像宠物台词（带宠物 + 长度自洽）；
     * 像则按宠物台词广播 NOTI 131，否则返回 false 让调用方按普通聊天处理。
     */
    public static boolean onA4CreatureScript(GameSession s, byte[] plain) {
        if (s == null || plain == null || plain.length < 11) {
            return false;
        }
        if (!s.hasCharacter() || s.inventory() == null
                || s.inventory().equippedCreature() == null) {
            return false;
        }
        int len = (plain[7] & 0xFF) | ((plain[8] & 0xFF) << 8)
                | ((plain[9] & 0xFF) << 16) | ((plain[10] & 0xFF) << 24);
        if (len <= 0 || len >= 256 || 11 + len > plain.length) {
            return false;
        }
        Cmd132CreatureScriptMessageRequest request;
        try {
            request = Cmd132CreatureScriptMessageRequest.parse(plain);
        } catch (RuntimeException e) {
            return false;
        }
        onCreatureScript(s, request);
        return true;
    }

    public static int onCreatureScript(GameSession s, Cmd132CreatureScriptMessageRequest request) {
        if (!s.hasCharacter()) {
            return 0;
        }
        if (s.inventory().equippedCreature() == null) {
            return 0;
        }
        if (ChatType.isServerOnly(request.msgType())) {
            return 0;
        }
        if (ChatType.isWhisper(request.msgType())) {
            return 0;
        }

        String text = Noti12MessageBody.fit(Noti12MessageBody.sanitize(request.text()));
        if (text.isBlank()) {
            return 0;
        }

        int type = request.msgType();
        AuditRecorder.recordChat(s, "宠物", "", text);
        int notiId = Noti131CreatureScriptMessageBody.PACKET_ID;
        if (type == ChatType.PARTY) {
            return broadcastToParty(s, notiId, type, text);
        }
        return broadcastToArea(s, notiId, type, text);
    }

    private static String channelLabel(int type) {
        if (type == ChatType.NORMAL) {
            return "普通";
        }
        if (type == ChatType.PARTY) {
            return "组队";
        }
        if (type == ChatType.GUILD || type == A4_GUILD) {
            return "公会";
        }
        if (ChatType.isWhisper(type)) {
            return "私聊";
        }
        String name = ChatType.name(type);
        return (name == null || name.isBlank()) ? ("频道#" + type) : name;
    }

    private static boolean looksLikeGmInput(GameSession s, String text) {
        if (!GmCommandParser.hasCommandPrefix(text)) {
            return false;
        }
        return GmManager.isGm(s.account() == null ? 0L : s.account().mId());
    }

    private static int gmCommand(GameSession s, String text) {
        GmCommandParser.Parsed parsed = GmCommandParser.parse(text);
        if (!parsed.ok()) {
            system(s, "「" + parsed.name() + "」：" + parsed.problem() + "。");
            return 1;
        }
        byte[] reply = GmService.onCommand(s, parsed.request(), ServerConfig.GM_CHAT_REPLY);
        sendGmReplyIfEnabled(s, text, reply);
        return 1;
    }

    private static void sendGmReplyIfEnabled(GameSession s, String text, byte[] reply) {
        boolean done = reply.length > 1 && reply[0] != 0;
        if (!ServerConfig.GM_CHAT_REPLY || !done) {
            return;
        }
        s.replyNow(Cmd65ReplyBody.PACKET_ID, reply);
    }

    private static int broadcastToArea(GameSession s, int notiId, int type, String text) {
        if (ChatType.isWhisper(type)) {
            throw new IllegalStateException("密语频道 " + ChatType.name(type)
                    + " 不能按区域广播 —— 它得走 NOTI 65，见 ChatType.WHISPER_MESSENGER");
        }
        byte[] body = chatBody(notiId, type, s.uniqueId(), text);
        s.sendNow(notiId, body);
        if (!com.dylan.net.ServerConfig.A4) {
            return 1;
        }
        // A4：CMD 17（qol 聊天框从 011001CE 发）原来只回给自己 → 同区域的人也发（和 onA4Chat 的 NORMAL 一样）
        SessionRegistry.Presence p = SessionRegistry.presenceOf(s);
        if (p == null || !p.inTown()) {
            return 1;
        }
        int sent = 1;
        for (GameSession o : SessionRegistry.inArea(p.town(), p.area(), s)) {
            o.sendNow(notiId, body);
            sent++;
        }
        return sent;
    }

    private static byte[] chatBody(int notiId, int type, int userId, String text) {
        return notiId == Noti131CreatureScriptMessageBody.PACKET_ID
                ? Noti131CreatureScriptMessageBody.from(type, userId, text).toBytes()
                : Noti12MessageBody.from(type, userId, text).toBytes();
    }

    private static int broadcastToParty(GameSession s, int notiId, int type, String text) {
        // 街道上组的队伍（PartyInviteService）优先；没有再用副本队伍
        java.util.List<GameSession> members = PartyInviteService.members(s);
        if (members.isEmpty()) {
            members = s.party().chatRecipients();
        }
        if (members.isEmpty()) {
            if (notiId == Noti12MessageBody.PACKET_ID) {
                system(s, "你还没有队伍。");
            }
            return 0;
        }
        byte[] body = chatBody(notiId, type, s.uniqueId(), text);
        for (GameSession member : members) {
            member.sendNow(notiId, body);
        }
        return members.size();
    }

    public static void system(GameSession s, String text) {
        String fitted = Noti12MessageBody.fit(Noti12MessageBody.sanitize(text));
        if (fitted.isEmpty()) {
            return;
        }
        s.sendNow(Noti12MessageBody.PACKET_ID, Noti12MessageBody.system(fitted).toBytes());
    }
}
