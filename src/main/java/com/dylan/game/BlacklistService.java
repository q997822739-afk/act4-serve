package com.dylan.game;

import com.dylan.protocol.io.PacketReader;
import com.dylan.protocol.io.PacketWriter;
import com.dylan.store.FriendStore;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 黑名单（A4）。命令号比台服 +7：台服 Dispatcher_RegisterToBlackList 121 / DeleteToBlackList 122 /
 * RequestBlackList 123 → A4 CMD 128 / 129 / 130（客户端发包点 sub_9B2750 / sub_9B2810 / sub_9B25C0）。
 * 台服结果由 monitor 服务器回（InterDispatcher Inter_Monitor_*BlackListResult），这里直接查库回。
 * <ul>
 * <li>CMD 128 加：u32 长度 + 名字。REPLY 128（DNF.exe {@code 004176FD}）成功 {@code 01} + str 名字
 *     （客户端用本机时间当加入日期，提示 0x1296）；失败 {@code 00} + u32 码 + str 名字（台服 dispatch_sig_89 布局），
 *     客户端只读第一个字节：0x4A（已在名单 / 满了）、0x4C（找不到角色，同好友的 809）、0x4E、0x5A。</li>
 * <li>CMD 129 删：u32 长度 + 名字。REPLY 129（{@code 00417815}）成功 {@code 01} + str 名字；失败 {@code 00 4B}。</li>
 * <li>CMD 130 查（空包，进城时和打开黑名单窗口时发）。REPLY 130（{@code 0041787B}）：{@code 01}, u8 n(≤10),
 *     n × {str 名字, u8 年-1900, u8 月-1, u8 日}（台服 dispatch_sig_91 localtime 的 tm_year/tm_mon/tm_mday）。</li>
 * </ul>
 * 拉黑后的效果照台服 isBlackUser 的调用点：密语双向拦截（check_peerTypemsg / send_chat_msg 回发送方 NOTI 65
 * 结果 0x4D），组队邀请 / 交易请求对方拉黑了自己回 0x4D（DisPatcher_ReqPeer）。
 */
public final class BlacklistService {
    private BlacklistService() {
    }

    static final int MAX = 10;
    static final int E_FULL_OR_DUP = 0x4A;
    static final int E_NOT_FOUND = 0x4C;
    static final int E_NOT_IN_LIST = 0x4B;
    /** 被对方拉黑（台服 PEER_ERR 0x4d / 密语结果 0x4d）。 */
    public static final int E_BLOCKED = 0x4D;

    private static FriendStore db(GameSession s) {
        return s.store().friend();
    }

    private static String name(byte[] body) {
        PacketReader r = new PacketReader(body);
        return r.remaining() < 4 ? "" : r.string(0x20, "黑名单名字").trim();
    }

    private static byte[] withName(PacketWriter w, String text) {
        byte[] b = text == null ? new byte[0] : text.getBytes(PacketWriter.DEFAULT_CHARSET);
        return w.u32(b.length).bytes(b).toArray();
    }

    private static byte[] fail(int code, String name) {
        return withName(new PacketWriter(16).u8(0).u32(code), name);
    }

    public static void add(GameSession s, byte[] body) {
        String target = name(body);
        Optional<FriendStore.Person> p = target.isEmpty() ? Optional.empty() : db(s).byName(target);
        if (p.isEmpty()) {
            s.reply(128, fail(E_NOT_FOUND, target));
            return;
        }
        int me = (int) s.characNo();
        int other = p.get().characNo();
        List<FriendStore.Black> list = db(s).blacklistOf(me);
        if (other == me || list.size() >= MAX || list.stream().anyMatch(b -> b.characNo() == other)) {
            s.reply(128, fail(E_FULL_OR_DUP, target));
            return;
        }
        db(s).addBlack(me, other, Instant.now().getEpochSecond());
        s.reply(128, withName(new PacketWriter(8).u8(1), p.get().name()));
        System.out.println("[黑名单] " + s.characName() + " 拉黑 " + p.get().name());
    }

    public static void remove(GameSession s, byte[] body) {
        String target = name(body);
        int me = (int) s.characNo();
        FriendStore.Black hit = db(s).blacklistOf(me).stream()
                .filter(b -> b.name().equals(target)).findFirst().orElse(null);
        if (hit == null) {
            s.reply(129, fail(E_NOT_IN_LIST, target));
            return;
        }
        db(s).removeBlack(me, hit.characNo());
        s.reply(129, withName(new PacketWriter(8).u8(1), hit.name()));
        System.out.println("[黑名单] " + s.characName() + " 解除 " + hit.name());
    }

    /** REPLY 130 包体。没角色 / 库不可用时就是空名单 {@code 01 00}（原来固定回的那两个字节）。 */
    public static byte[] listBody(GameSession s) {
        List<FriendStore.Black> list = s.hasCharacter() && s.store() != null
                ? db(s).blacklistOf((int) s.characNo()) : List.of();
        if (list.size() > MAX) {
            list = list.subList(0, MAX);
        }
        PacketWriter w = new PacketWriter(2 + list.size() * 40).u8(1).u8(list.size());
        for (FriendStore.Black b : list) {
            byte[] n = b.name().getBytes(PacketWriter.DEFAULT_CHARSET);
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochSecond(b.regTime()), ZoneId.systemDefault());
            w.u32(n.length).bytes(n).u8(t.getYear() - 1900).u8(t.getMonthValue() - 1).u8(t.getDayOfMonth());
        }
        return w.toArray();
    }

    /** a 和 b 之间有没有一方拉黑了另一方（台服密语用双向判断）。 */
    public static boolean blockedEitherWay(GameSession a, GameSession b) {
        return blocks(a, b) || blocks(b, a);
    }

    /** owner 拉黑了 other。 */
    public static boolean blocks(GameSession owner, GameSession other) {
        if (owner == null || other == null || !owner.hasCharacter() || !other.hasCharacter()
                || owner.store() == null) {
            return false;
        }
        return db(owner).isBlack((int) owner.characNo(), (int) other.characNo());
    }
}
