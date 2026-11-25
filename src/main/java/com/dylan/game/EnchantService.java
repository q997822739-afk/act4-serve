package com.dylan.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

import com.dylan.protocol.body.Cmd178AgreeEnchantReplyBody;
import com.dylan.protocol.body.Cmd178AgreeEnchantRequest;
import com.dylan.protocol.body.Cmd179TryEnchantReplyBody;
import com.dylan.protocol.body.Cmd179TryEnchantRequest;
import com.dylan.protocol.body.Cmd180PutItemForEnchantReplyBody;
import com.dylan.protocol.body.Cmd180PutItemForEnchantRequest;
import com.dylan.protocol.body.EnchantError;
import com.dylan.protocol.body.ItemListType;
import com.dylan.protocol.body.Noti168TryEnchantBody;
import com.dylan.protocol.body.Noti169PutItemForEnchantBody;
import com.dylan.protocol.body.Noti170CancelEnchantBody;
import com.dylan.pvf.script.ExpertJobScript;
import com.dylan.pvf.script.ItemScript;
import com.dylan.pvf.script.RecipeInfo;
import com.dylan.store.blob.InvenItem;
import com.dylan.store.blob.ItemArray;
import com.dylan.protocol.body.TradePackets;
import com.dylan.protocol.io.PacketWriter;

public final class EnchantService {

    private static final int MONEY_REASON_ENCHANT_GAIN = 16;

    private static final int MONEY_REASON_ENCHANT_USE = 23;

    private static final long MONEY_LIMIT = 0xFFFF_FFFFL;

    private final GameSession session;

    private RandomGenerator random = ThreadLocalRandom.current();

    public EnchantService(GameSession session) {
        this.session = session;
    }

    void random(RandomGenerator value) {
        this.random = value == null ? ThreadLocalRandom.current() : value;
    }

    public boolean busy() {
        return EnchantRegistry.busy(session);
    }

    public void onTryEnchant(Cmd179TryEnchantRequest request) {
        if (!session.hasCharacter()) {
            return;
        }
        int type = request.type();
        EnchantMission mission = EnchantRegistry.of(session);

        switch (type) {
            case Cmd179TryEnchantRequest.TYPE_OTHER -> {
                if (mission == null) {
                    replyTry(Cmd179TryEnchantReplyBody.rejected(
                            EnchantError.NO_SESSION_OR_NO_MONEY));
                    return;
                }
            }
            case Cmd179TryEnchantRequest.TYPE_CANCEL -> {
                if (mission == null) {
                    if (com.dylan.net.ServerConfig.A4) {
                        // 依据：A4 官方实抓（sweep_town CMD 179，包体 02000000… = type 2 = 取消，但根本没开附魔）
                        // 回 REPLY 179 `00 01`；客户端 0041BF6D 只读一个 u8 错误码。
                        // 原版 A5 这里静默 return，客户端取消附魔的按钮会等。只补回包，会话状态没动。
                        replyTry(Cmd179TryEnchantReplyBody.rejected(EnchantError.NO_SESSION));
                    }
                    return;
                }
            }
            case Cmd179TryEnchantRequest.TYPE_MINE -> {
                if (mission != null) {
                    closeMission(mission, session, CLOSE_LEFT);
                }
                mission = EnchantRegistry.openSolo(session);
            }
            default -> {
                return;
            }
        }

        if (type != Cmd179TryEnchantRequest.TYPE_CANCEL) {
            mission.recipeItemId(request.recipeItemId());
        }

        int error = tryEnchant(mission, request, type);
        if (error != EnchantError.NONE) {
            replyTry(Cmd179TryEnchantReplyBody.rejected(error));
        }
    }

    private int tryEnchant(EnchantMission mission, Cmd179TryEnchantRequest request, int type) {
        switch (type) {
            case Cmd179TryEnchantRequest.TYPE_OTHER -> {
                int error = tryEnchantOther(mission);
                if (error != EnchantError.NONE) {
                    mission.resetCard();
                }
                return error;
            }
            case Cmd179TryEnchantRequest.TYPE_CANCEL -> {
                closeMission(mission, session, CLOSE_CANCELLED);
                return EnchantError.NONE;
            }
            default -> {
                int error = tryEnchantMine(mission, request);
                if (error != EnchantError.NONE) {
                    closeMission(mission, session, CLOSE_DONE);
                }
                return error;
            }
        }
    }

    private int tryEnchantMine(EnchantMission mission, Cmd179TryEnchantRequest request) {
        return tryEnchantMine(mission, request.itemSpace(), request.slot());
    }

    private int tryEnchantMine(EnchantMission mission, int equipSpace, int equipSlot) {
        int error = setData(mission, true, session, equipSpace, equipSlot);
        if (error != EnchantError.NONE) {
            return error;
        }

        ItemScript recipe = itemScript(mission.recipeItemId());
        int cardItemId = findMonsterCard(recipe);
        if (cardItemId == 0) {
            return EnchantError.WRONG_MATERIAL;
        }
        int cardSlot = findSlotOf(session, cardItemId);
        if (cardSlot < 0) {
            return EnchantError.WRONG_MATERIAL;
        }
        error = setData(mission, false, session, ItemListType.LIST_MAIN, cardSlot);
        if (error != EnchantError.NONE) {
            return error;
        }
        error = checkCommon(mission, recipe);
        if (error != EnchantError.NONE) {
            return error;
        }
        doEnchant(mission, Cmd179TryEnchantRequest.TYPE_MINE, session);
        return EnchantError.NONE;
    }

    private int tryEnchantOther(EnchantMission mission) {
        if (mission.equipment().isEmpty()) {
            return EnchantError.NOT_IN_INVENTORY;
        }
        ItemScript recipe = itemScript(mission.recipeItemId());
        int error = checkRecipe(mission, recipe);
        if (error != EnchantError.NONE) {
            return error;
        }
        if (mission.card().isEmpty()) {
            return EnchantError.SLOT_TAKEN;
        }
        error = checkCardAndSlot(mission, recipe);
        if (error != EnchantError.NONE) {
            return error;
        }

        var rate = successRateOf(mission.enchanter(), recipe);
        if (rate == null) {
            return EnchantError.WRONG_TYPE;
        }
        int percent = Math.min(rate.rate(), Noti168TryEnchantBody.MAX_RATE);
        GameSession beneficiary = mission.beneficiary();
        beneficiary.sendNow(Noti168TryEnchantBody.PACKET_ID,
                Noti168TryEnchantBody.of(percent, mission.recipeItemId()).toBytes());
        return EnchantError.NONE;
    }

    public void onAgreeEnchant(Cmd178AgreeEnchantRequest request) {
        if (!session.hasCharacter()) {
            return;
        }
        EnchantMission mission = EnchantRegistry.of(session);
        if (mission == null) {
            return;
        }
        if (session != mission.beneficiary()) {
            replyAgree(mission, EnchantError.NOT_YOURS);
            return;
        }

        switch (request.type()) {
            case Cmd178AgreeEnchantRequest.TYPE_AGREE ->
                    doEnchant(mission, Cmd178AgreeEnchantRequest.TYPE_AGREE, session);
            case Cmd178AgreeEnchantRequest.TYPE_CANCEL ->
                    closeMission(mission, session, CLOSE_CANCELLED);
            case Cmd178AgreeEnchantRequest.TYPE_REFUSE -> {
                if (mission.card().isEmpty()) {
                    replyAgree(mission, EnchantError.NOT_IN_INVENTORY);
                    return;
                }
                mission.resetCard();
                GameSession enchanter = mission.enchanter();
                enchanter.replyNow(Cmd179TryEnchantReplyBody.PACKET_ID,
                        Cmd179TryEnchantReplyBody.refused().toBytes());
            }
            default -> {
            }
        }
    }

    /** A4 单人附魔窗确认包（sub_8706C0 → sub_91C490）：u8 mode 0, u8 件数 2, 2 × (u8 容器, u16 槽), u32 金币, u32 0。 */
    public static boolean isSoloPut(byte[] plain) {
        return com.dylan.net.ServerConfig.A4 && plain != null && plain.length >= 8
                && plain[0] == 0 && plain[1] == 2;
    }

    /**
     * 给自己附魔。A4 单人窗点确定只发 CMD 180（第 1 组 = 配方卷轴、第 2 组 = 装备），不发 CMD 179。
     * 事务和 CMD 179 给自己附魔同一条；结果回 REPLY 180：01 00 成败 u32 熟练度 u32 金币 u8 0
     * （0x41BD03，成败分别弹 19012 / 19013），失败回 00 错误码（0x41BEE0）。
     */
    public void onSoloPut(byte[] plain) {
        if (!session.hasCharacter()) {
            return;
        }
        int recipeSpace = plain[2] & 0xFF;
        int recipeSlot = (plain[3] & 0xFF) | (plain[4] & 0xFF) << 8;
        int equipSpace = plain[5] & 0xFF;
        int equipSlot = (plain[6] & 0xFF) | (plain[7] & 0xFF) << 8;
        EnchantMission old = EnchantRegistry.of(session);
        if (old != null) {
            closeMission(old, session, CLOSE_LEFT);
        }
        InvenItem recipeItem = EnchantMission.itemAt(session, recipeSpace, recipeSlot);
        if (recipeSpace != ItemListType.LIST_MAIN || recipeItem == null || recipeItem.isEmpty()) {
            replySolo180(Cmd180PutItemForEnchantReplyBody.rejected(EnchantError.NOT_IN_INVENTORY).toBytes());
            return;
        }
        EnchantMission mission = EnchantRegistry.openSolo(session);
        mission.replyOn180(true);
        mission.recipeItemId(recipeItem.itemId());
        int error = tryEnchantMine(mission, equipSpace, equipSlot);
        if (error != EnchantError.NONE) {
            closeMission(mission, session, CLOSE_DONE);
            replySolo180(Cmd180PutItemForEnchantReplyBody.rejected(error).toBytes());
        }
    }

    private void replySolo180(byte[] body) {
        session.reply(Cmd180PutItemForEnchantReplyBody.PACKET_ID, body);
        session.flush();
    }

    // ---- A4 双人附魔（右键菜单「邀请附魔」） ----
    //
    // 包序（kind 7 = 附魔师邀请客户，kind 6 = 客户邀请附魔师）：
    //  CMD 10 kind 6/7 → 对方 NOTI 7 {u16 邀请人, u8 kind, u32 echo}
    //  CMD 11 接受 → 接受方 REPLY 11 {01, u16 邀请人, u8 kind}，邀请人 NOTI 8 {u16 接受方, u8 kind, u32 echo}，双方开附魔窗
    //  客户 CMD 181 {00, u32 手续费} / {01, u8 容器, u16 装备槽} → 附魔师 NOTI 169 {0, 手续费} / {1, 装备号}，不回包
    //  附魔师 CMD 180 {01, 01, 00, u16 卷轴槽} → 客户 NOTI 168 {01, u16 成功率, 01, u32 卷轴号}（确认框），不回包；
    //      校验失败附魔师 REPLY 180 {00, 错误码}
    //  客户 CMD 179 {01} → 结算：客户 REPLY 179 {01, 成败}，附魔师 REPLY 180 {01, 01, 成败, u32 熟练度, u32 金币, 00}
    //  取消：客户 CMD 179 {00 / 02}、附魔师 CMD 180 {02} → 双方 NOTI 170，发送方回 {00, 01}

    public static final int KIND_CUSTOMER_INVITES = 6;

    public static final int KIND_ENCHANTER_INVITES = 7;

    /** character/expertjob.lst：1 = 附魔师。 */
    static final int EXPERT_JOB_ENCHANTER = 1;

    private static final int E_PEER_BUSY = 0x13;

    private record Invite(GameSession inviter, int kind, int echo) {
    }

    private static final java.util.Map<GameSession, Invite> INVITES =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isInviteKind(int kind) {
        return kind == KIND_CUSTOMER_INVITES || kind == KIND_ENCHANTER_INVITES;
    }

    public static void onInvitePeer(GameSession s, int packetId, TradePackets.PeerRequest req) {
        int kind = req.kind();
        if (packetId == 10) {
            GameSession target = SessionRegistry.findInWorld(req.uid());
            if (target == null || target == s || !target.hasCharacter()) {
                s.reply(10, TradePackets.fail(TradePackets.E_GONE, kind));
                s.flush();
                return;
            }
            GameSession enchanter = kind == KIND_ENCHANTER_INVITES ? s : target;
            if (enchanter.expertJob().type() != EXPERT_JOB_ENCHANTER
                    || EnchantRegistry.busy(s) || EnchantRegistry.busy(target)
                    || TradeService.trading(s) || TradeService.trading(target)) {
                s.reply(10, TradePackets.fail(E_PEER_BUSY, kind));
                s.flush();
                return;
            }
            if (BlacklistService.blocks(target, s)) {
                s.reply(10, TradePackets.fail(BlacklistService.E_BLOCKED, kind));
                s.flush();
                return;
            }
            INVITES.put(target, new Invite(s, kind, req.peer()));
            s.reply(10, TradePackets.ok());
            s.flush();
            target.sendNow(TradePackets.NOTI_REQUEST_PEER,
                    new PacketWriter(7).u16(s.uniqueId()).u8(kind).u32(req.peer()).toArray());
            return;
        }
        Invite invite = INVITES.remove(s);
        if (invite == null || invite.inviter().uniqueId() != req.uid()
                || invite.kind() != kind || !invite.inviter().isActive()) {
            s.reply(11, TradePackets.fail(TradePackets.E_GONE, kind));
            s.flush();
            return;
        }
        GameSession inviter = invite.inviter();
        if (req.refuse() != 0) {
            inviter.replyNow(11, TradePackets.fail(TradePackets.E_REFUSED, kind));
            return;
        }
        GameSession enchanter = kind == KIND_ENCHANTER_INVITES ? inviter : s;
        GameSession beneficiary = enchanter == s ? inviter : s;
        EnchantMission mission = TradeService.trading(s) || TradeService.trading(inviter)
                ? null : EnchantRegistry.open(enchanter, beneficiary, false);
        if (mission == null) {
            s.reply(11, TradePackets.fail(E_PEER_BUSY, kind));
            s.flush();
            return;
        }
        mission.a4Invite(true);
        // REPLY 11（0x4114AD）：01 后读 u16 对方、u8 kind
        s.reply(11, new PacketWriter(4).u8(1).u16(inviter.uniqueId()).u8(kind).toArray());
        s.flush();
        inviter.sendNow(TradePackets.NOTI_RESPONSE_PEER,
                new PacketWriter(7).u16(s.uniqueId()).u8(kind).u32(invite.echo()).toArray());
    }

    /** 是不是 A4 双人附魔会话里的包（是就由 onInvite179 / onInvite180 处理）。 */
    public boolean inA4Invite() {
        EnchantMission mission = EnchantRegistry.of(session);
        return com.dylan.net.ServerConfig.A4 && mission != null && mission.a4Invite();
    }

    /** CMD 181（客户发，发送点 0x91C440 / 0x91C3E0）：手续费 / 放装备。客户端没有 REPLY 181 读端，不回包。 */
    public void onInvite181(byte[] plain) {
        EnchantMission mission = EnchantRegistry.of(session);
        if (mission == null || !mission.a4Invite() || plain == null || plain.length < 1) {
            return;
        }
        GameSession peer = mission.other(session);
        if (peer == null) {
            return;
        }
        int type = plain[0] & 0xFF;
        if (type == 0 && plain.length >= 5) {
            long fee = (plain[1] & 0xFFL) | (plain[2] & 0xFFL) << 8 | (plain[3] & 0xFFL) << 16
                    | (plain[4] & 0xFFL) << 24;
            if (session != mission.beneficiary() || session.inventory().money() < fee) {
                return;
            }
            mission.money(fee);
            peer.sendNow(Noti169PutItemForEnchantBody.PACKET_ID,
                    new Noti169PutItemForEnchantBody(Cmd180PutItemForEnchantRequest.KIND_MONEY, fee).toBytes());
            return;
        }
        if (type == 1 && plain.length >= 4 && session == mission.beneficiary()) {
            int space = plain[1] & 0xFF;
            int slot = (plain[2] & 0xFF) | (plain[3] & 0xFF) << 8;
            if (setData(mission, true, session, space, slot) != EnchantError.NONE
                    || checkType(mission.equipment(), false, 0) != EnchantError.NONE) {
                mission.equipment(EnchantMission.Material.EMPTY);
                return;
            }
            mission.resetCard();
            mission.enchanterConfirmed(false);
            peer.sendNow(Noti169PutItemForEnchantBody.PACKET_ID,
                    new Noti169PutItemForEnchantBody(Cmd180PutItemForEnchantRequest.KIND_EQUIPMENT,
                            mission.equipment().itemId()).toBytes());
        }
    }

    /** 附魔师 CMD 180：mode 1 = 放好卷轴点确认（0x91C610），其它 = 关窗取消（0x91C350）。 */
    public void onInvite180(byte[] plain) {
        EnchantMission mission = EnchantRegistry.of(session);
        int mode = plain == null || plain.length == 0 ? 2 : plain[0] & 0xFF;
        if (mode != 1) {
            cancelInvite(mission, Cmd180PutItemForEnchantReplyBody.PACKET_ID);
            return;
        }
        if (session != mission.enchanter() || plain.length < 5) {
            replySolo180(Cmd180PutItemForEnchantReplyBody.rejected(EnchantError.NOT_YOURS).toBytes());
            return;
        }
        if (mission.equipment().isEmpty()) {
            replySolo180(Cmd180PutItemForEnchantReplyBody.rejected(EnchantError.NOT_IN_INVENTORY).toBytes());
            return;
        }
        int space = plain[2] & 0xFF;
        int slot = (plain[3] & 0xFF) | (plain[4] & 0xFF) << 8;
        InvenItem scroll = EnchantMission.itemAt(session, space, slot);
        int error = scroll == null || scroll.isEmpty() ? EnchantError.NOT_IN_INVENTORY : EnchantError.NONE;
        ItemScript recipe = null;
        if (error == EnchantError.NONE) {
            mission.recipeItemId(scroll.itemId());
            recipe = itemScript(scroll.itemId());
            int cardItemId = findMonsterCard(recipe);
            int cardSlot = cardItemId == 0 ? -1 : findSlotOf(session, cardItemId);
            error = cardSlot < 0 ? EnchantError.WRONG_MATERIAL
                    : setData(mission, false, session, ItemListType.LIST_MAIN, cardSlot);
        }
        if (error == EnchantError.NONE) {
            error = checkCommon(mission, recipe);
        }
        var rate = error == EnchantError.NONE ? successRateOf(session, recipe) : null;
        if (error == EnchantError.NONE && (rate == null || rate.rate() <= 0)) {
            error = EnchantError.WRONG_TYPE;
        }
        if (error != EnchantError.NONE) {
            mission.resetCard();
            mission.enchanterConfirmed(false);
            replySolo180(Cmd180PutItemForEnchantReplyBody.rejected(error).toBytes());
            return;
        }
        mission.enchanterConfirmed(true);
        mission.beneficiary().sendNow(Noti168TryEnchantBody.PACKET_ID,
                Noti168TryEnchantBody.of(Math.min(rate.rate(), Noti168TryEnchantBody.MAX_RATE),
                        mission.recipeItemId()).toBytes());
    }

    /** 客户 CMD 179：01 = 确认框同意（0x41E6D0），其它 = 关窗取消。 */
    public void onInvite179(byte[] plain) {
        EnchantMission mission = EnchantRegistry.of(session);
        int type = plain == null || plain.length == 0 ? 2 : plain[0] & 0xFF;
        if (type != 1) {
            cancelInvite(mission, Cmd179TryEnchantReplyBody.PACKET_ID);
            return;
        }
        if (session != mission.beneficiary()) {
            replyTry(Cmd179TryEnchantReplyBody.rejected(EnchantError.NOT_YOURS));
            return;
        }
        if (!mission.enchanterConfirmed()) {
            replyTry(Cmd179TryEnchantReplyBody.rejected(EnchantError.NO_SESSION));
            return;
        }
        mission.enchanterConfirmed(false);
        doEnchant(mission, Cmd179TryEnchantRequest.TYPE_OTHER, session);
    }

    private void cancelInvite(EnchantMission mission, int replyPacketId) {
        if (mission == null) {
            return;
        }
        EnchantRegistry.close(mission);
        for (GameSession side : new GameSession[]{mission.enchanter(), mission.beneficiary()}) {
            if (side != null && side.hasCharacter()) {
                side.sendNow(Noti170CancelEnchantBody.PACKET_ID, Noti170CancelEnchantBody.toBytes());
            }
        }
        session.reply(replyPacketId, new byte[]{0, (byte) EnchantError.NO_SESSION});
        session.flush();
    }

    public static void forgetInvites(GameSession s) {
        INVITES.remove(s);
        INVITES.values().removeIf(i -> i.inviter() == s);
    }

    public void onPutItem(Cmd180PutItemForEnchantRequest request) {
        if (!session.hasCharacter()) {
            return;
        }
        EnchantMission mission = EnchantRegistry.of(session);
        if (mission == null) {
            if (com.dylan.net.ServerConfig.A4) {
                // 依据：A4 官方实抓（sweep_town CMD 180，包体 01000000… = 放装备 容器 0 槽 0，但没开附魔）
                // 回 REPLY 180 `00 01`（2 字节：结果 0 + 错误码 1）。
                // 原版 A5 这里静默 return，客户端放材料后一直等。只补回包，会话状态没动。
                session.reply(Cmd180PutItemForEnchantReplyBody.PACKET_ID,
                        Cmd180PutItemForEnchantReplyBody.rejected(EnchantError.NO_SESSION).toBytes());
                session.flush();
            }
            return;
        }
        int error = putItem(mission, request);
        if (error != EnchantError.NONE) {
            session.reply(Cmd180PutItemForEnchantReplyBody.PACKET_ID,
                    Cmd180PutItemForEnchantReplyBody.rejected(error).toBytes());
            session.flush();
            return;
        }
        GameSession peer = mission.other(session);
        if (peer == null) {
            session.reply(Cmd180PutItemForEnchantReplyBody.PACKET_ID,
                    Cmd180PutItemForEnchantReplyBody.rejected(EnchantError.NOT_YOURS).toBytes());
            session.flush();
            return;
        }
        long value = switch (request.kind()) {
            case Cmd180PutItemForEnchantRequest.KIND_EQUIPMENT -> mission.equipment().itemId();
            case Cmd180PutItemForEnchantRequest.KIND_MONSTER_CARD -> mission.card().itemId();
            default -> mission.money();
        };
        peer.sendNow(Noti169PutItemForEnchantBody.PACKET_ID,
                new Noti169PutItemForEnchantBody(request.kind(), value).toBytes());
    }

    private int putItem(EnchantMission mission, Cmd180PutItemForEnchantRequest request) {
        if (session != mission.beneficiary()) {
            if (request.kind() != Cmd180PutItemForEnchantRequest.KIND_MONSTER_CARD
                    || mission.useOtherUserMonsterCard()
                    || session != mission.enchanter()) {
                return EnchantError.NOT_YOURS;
            }
        }
        switch (request.kind()) {
            case Cmd180PutItemForEnchantRequest.KIND_EQUIPMENT -> {
                if (!mission.equipment().isEmpty()) {
                    return EnchantError.SLOT_TAKEN;
                }
                int error = setData(mission, true, session, request.itemSpace(), request.slot());
                if (error != EnchantError.NONE) {
                    return error;
                }
                return checkType(mission.equipment(), false, 0);
            }
            case Cmd180PutItemForEnchantRequest.KIND_MONSTER_CARD -> {
                if (!mission.card().isEmpty()) {
                    return EnchantError.SLOT_TAKEN;
                }
                int error = setData(mission, false, session, request.itemSpace(), request.slot());
                if (error != EnchantError.NONE) {
                    return error;
                }
                return checkType(mission.card(), true,
                        ItemScript.STACKABLE_MATERIAL_EXPERT_JOB);
            }
            case Cmd180PutItemForEnchantRequest.KIND_MONEY -> {
                if (mission.money() > 0) {
                    return EnchantError.SLOT_TAKEN;
                }
                GameSession enchanter = mission.enchanter();
                long enchanterMoney = enchanter.inventory().money();
                if (enchanterMoney + request.money() > MONEY_LIMIT) {
                    return EnchantError.NOT_ENOUGH_MONEY;
                }
                if (session.inventory().money() < request.money()) {
                    return EnchantError.NO_SESSION_OR_NO_MONEY;
                }
                mission.money(request.money());
                return EnchantError.NONE;
            }
            default -> {
                return EnchantError.WRONG_TYPE;
            }
        }
    }

    private void doEnchant(EnchantMission mission, int type, GameSession operator) {
        GameSession enchanter = mission.enchanter();
        GameSession beneficiary = mission.beneficiary();

        if (!mission.solo() && mission.money() > 0
                && beneficiary.inventory().money() < mission.money()) {
            failEnchant(mission, type, operator, EnchantError.WRONG_MATERIAL);
            return;
        }
        int error = EnchantMission.checkStillRight(mission.equipment());
        if (error == EnchantError.NONE) {
            error = EnchantMission.checkStillRight(mission.card());
        }
        if (error != EnchantError.NONE) {
            failEnchant(mission, type, operator, error);
            return;
        }
        InvenItem equipment = EnchantMission.itemAt(beneficiary,
                mission.equipment().itemSpace(), mission.equipment().slot());
        if (equipment == null || equipment.isEmpty()) {
            failEnchant(mission, type, operator, EnchantError.WRONG_MATERIAL);
            return;
        }

        enchanter.run(() -> enchanter.enchant().enchanterSide(mission, type, operator));
    }

    void enchanterSide(EnchantMission mission, int type, GameSession operator) {
        ItemScript recipe = itemScript(mission.recipeItemId());
        if (recipe == null) {
            failEnchant(mission, type, operator, EnchantError.WRONG_MATERIAL);
            return;
        }
        List<RecipeInfo.Material> needed = materialsToConsume(mission, recipe);
        if (needed.isEmpty()) {
            failEnchant(mission, type, operator, EnchantError.NOT_IN_INVENTORY);
            return;
        }
        ItemArray bag = session.inventory().containerOf(ItemListType.LIST_MAIN);
        if (bag == null) {
            failEnchant(mission, type, operator, EnchantError.NOT_IN_INVENTORY);
            return;
        }
        for (RecipeInfo.Material need : needed) {
            long have = InventoryService.countOf(bag, need.itemId());
            if (have < need.count()) {
                failEnchant(mission, type, operator, EnchantError.NOT_IN_INVENTORY);
                return;
            }
        }

        boolean success = drawEnchant(mission, recipe);

        List<Cmd179TryEnchantReplyBody.MaterialChange> changes = new ArrayList<>();
        List<Integer> touched = new ArrayList<>();
        for (RecipeInfo.Material need : needed) {
            for (InventoryService.Taken taken
                    : InventoryService.consume(bag, need.itemId(), need.count())) {
                changes.add(new Cmd179TryEnchantReplyBody.MaterialChange(
                        ItemListType.LIST_MAIN, taken.slot(), taken.count()));
                touched.add(taken.slot());
            }
        }
        session.inventory().markItemsDirty();
        session.inventory().save();

        long money = session.inventory().money();
        if (mission.money() > 0 && !mission.solo()) {
            money = Math.min(MONEY_LIMIT, money + mission.money());
            session.inventory().setMoney(money);
            session.inventory().save();
            session.inventory().sendItemUpdate(ItemListType.LIST_MAIN,
                    "附魔收入 " + mission.money() + " 金币（官方 reason "
                            + MONEY_REASON_ENCHANT_GAIN + "）", BagLayout.SLOT_GOLD);
        }

        long exp = session.expertJob().exp();
        if (mission.a4Invite()) {
            // 双人：附魔师 REPLY 180 {01, 01, 成败, 熟练度, 金币, 00}（0x41BDDB），材料走 NOTI 14
            session.replyNow(Cmd180PutItemForEnchantReplyBody.PACKET_ID,
                    Cmd179TryEnchantReplyBody.done(1, success, exp, money, List.of()).toBytes());
            session.inventory().sendItemUpdate(ItemListType.LIST_MAIN, "附魔用掉材料",
                    touched.stream().mapToInt(Integer::intValue).distinct().toArray());
        } else if (mission.replyOn180()) {
            // 单人窗：REPLY 180 和 REPLY 179 同一布局，类型 0；扣掉的材料走 NOTI 14
            session.replyNow(Cmd180PutItemForEnchantReplyBody.PACKET_ID,
                    Cmd179TryEnchantReplyBody.done(0, success, exp, money, List.of()).toBytes());
            session.inventory().sendItemUpdate(ItemListType.LIST_MAIN, "附魔用掉材料",
                    touched.stream().mapToInt(Integer::intValue).distinct().toArray());
        } else {
            session.replyNow(Cmd179TryEnchantReplyBody.PACKET_ID,
                    Cmd179TryEnchantReplyBody.done(type, success, exp, money, changes).toBytes());
        }

        GameSession beneficiary = mission.beneficiary();
        boolean enchanted = success;
        beneficiary.run(() ->
                beneficiary.enchant().beneficiarySide(mission, type, operator, enchanted));
    }

    void beneficiarySide(EnchantMission mission, int type, GameSession operator,
                         boolean success) {
        if (success) {
            int itemSpace = mission.equipment().itemSpace();
            int slot = mission.equipment().slot();
            ItemArray bag = session.inventory().containerOf(itemSpace);
            InvenItem item = bag == null || slot < 0 || slot >= bag.slots()
                    ? null : bag.get(slot);
            if (item != null && !item.isEmpty()
                    && item.itemId() == mission.equipment().itemId()) {
                bag.set(slot, item.withEnchant(mission.card().itemId()));
                session.inventory().markItemsDirty();
                session.inventory().save();
                session.inventory().sendItemUpdate(itemSpace,
                        "附魔上了卡片 " + mission.card().itemId(), slot);
            }
        }

        if (!mission.solo()) {
            if (mission.money() > 0) {
                long left = Math.max(0, session.inventory().money() - mission.money());
                session.inventory().setMoney(left);
                session.inventory().save();
                session.inventory().sendItemUpdate(ItemListType.LIST_MAIN,
                        "附魔报酬 -" + mission.money() + " 金币（官方 reason "
                                + MONEY_REASON_ENCHANT_USE + "）", BagLayout.SLOT_GOLD);
            }
            if (mission.useOtherUserMonsterCard()) {
                consumeCardFromBeneficiary(mission);
            }
            if (mission.a4Invite()) {
                // 客户 REPLY 179 {01, 成败}（0x41BF88）
                session.replyNow(Cmd179TryEnchantReplyBody.PACKET_ID,
                        new byte[]{1, (byte) (success ? 1 : 0)});
            } else {
                session.replyNow(Cmd178AgreeEnchantReplyBody.PACKET_ID,
                        Cmd178AgreeEnchantReplyBody.done(success).toBytes());
            }
        }

        closeMission(mission, operator, CLOSE_DONE);
    }

    private void consumeCardFromBeneficiary(EnchantMission mission) {
        int slot = mission.card().slot();
        ItemArray bag = session.inventory().containerOf(ItemListType.LIST_MAIN);
        InvenItem item = bag == null || slot < 0 || slot >= bag.slots() ? null : bag.get(slot);
        if (item == null || item.isEmpty() || item.itemId() != mission.card().itemId()) {
            return;
        }
        long left = item.stackCount() - 1;
        bag.set(slot, left <= 0 ? InvenItem.EMPTY : item.withAddInfo(left));
        session.inventory().markItemsDirty();
        session.inventory().save();
        session.inventory().sendItemUpdate(ItemListType.LIST_MAIN,
                "附魔用掉一张怪物卡 " + mission.card().itemId(), slot);
    }

    private boolean drawEnchant(EnchantMission mission, ItemScript recipe) {
        var rate = successRateOf(session, recipe);
        if (rate == null) {
            return false;
        }
        int roll = random.nextInt(100);
        if (rate.rate() <= roll) {
            return false;
        }
        int span = Math.max(1, rate.expMax() - rate.expMin() + 1);
        int gained = random.nextInt(span) + rate.expMin();
        int before = session.expertJob().exp();
        session.expertJob().increaseExp(before + gained);
        return true;
    }

    private ExpertJobScript.CompoundRate successRateOf(GameSession enchanter, ItemScript recipe) {
        if (recipe == null || enchanter == null) {
            return null;
        }
        ExpertJobScript script = enchanter.expertJob().script();
        if (script == null) {
            return null;
        }
        ExpertJobScript.RecipeEntry entry = script.recipeByItemId(recipe.id());
        if (entry == null) {
            return null;
        }
        int diff = entry.level() - enchanter.expertJob().level();
        return script.compoundRate(diff);
    }

    private static final int CLOSE_DONE = 0;

    private static final int CLOSE_LEFT = 1;

    private static final int CLOSE_CANCELLED = 2;

    void closeMission(EnchantMission mission, GameSession trigger, int type) {
        if (mission == null) {
            return;
        }
        GameSession peer = mission.other(trigger);
        EnchantRegistry.close(mission);
        if ((type == CLOSE_LEFT || type == CLOSE_CANCELLED)
                && peer != null && peer.hasCharacter() && !mission.solo()) {
            peer.sendNow(Noti170CancelEnchantBody.PACKET_ID, Noti170CancelEnchantBody.toBytes());
        }
    }

    public void onLeaveUser() {
        forgetInvites(session);
        EnchantMission mission = EnchantRegistry.of(session);
        if (mission == null) {
            return;
        }
        closeMission(mission, session, CLOSE_LEFT);
    }

    private int setData(EnchantMission mission, boolean equipmentSlot, GameSession owner,
                        int itemSpace, int slot) {
        if (itemSpace != ItemListType.LIST_MAIN) {
            return EnchantError.NOT_IN_INVENTORY;
        }
        InvenItem item = EnchantMission.itemAt(owner, itemSpace, slot);
        if (item == null || item.isEmpty()) {
            return EnchantError.NOT_IN_INVENTORY;
        }
        ItemScript script = itemScript(item.itemId());
        if (script == null) {
            return EnchantError.NOT_IN_INVENTORY;
        }
        var material = new EnchantMission.Material(owner, itemSpace, slot, (int) item.itemId());
        if (equipmentSlot) {
            mission.equipment(material);
        } else {
            mission.card(material);
        }
        return EnchantError.NONE;
    }

    private int checkType(EnchantMission.Material material, boolean wantStackable,
                          int stackableType) {
        if (material.isEmpty()) {
            return EnchantError.WRONG_MATERIAL;
        }
        ItemScript script = itemScript(material.itemId());
        if (script == null) {
            return EnchantError.WRONG_TYPE;
        }
        if (script.isStackableItem() != wantStackable) {
            return EnchantError.WRONG_TYPE;
        }
        if (wantStackable) {
            if (script.stackableType() != stackableType) {
                return EnchantError.WRONG_TYPE;
            }
            return EnchantError.NONE;
        }
        if (script.equipmentSlot() == com.dylan.protocol.body.EquipmentSlot.NONE) {
            return EnchantError.WRONG_TYPE;
        }
        return EnchantError.NONE;
    }

    private int checkRecipe(EnchantMission mission, ItemScript recipe) {
        if (recipe == null || !recipe.isRecipeItem()) {
            return EnchantError.WRONG_MATERIAL;
        }
        GameSession enchanter = mission.enchanter();
        ExpertJobScript script = enchanter.expertJob().script();
        if (script == null) {
            return EnchantError.WRONG_MATERIAL;
        }
        int index = script.recipeIndex(recipe.id());
        if (index == ExpertJobScript.NO_RECIPE) {
            return EnchantError.WRONG_MATERIAL;
        }
        var bitmap = enchanter.expertJob().recipes();
        if ((bitmap == null || !bitmap.has(index)) && !enchanter.gmMode()) {
            return EnchantError.WRONG_MATERIAL;
        }
        return EnchantError.NONE;
    }

    private int checkCommon(EnchantMission mission, ItemScript recipe) {
        int error = checkRecipe(mission, recipe);
        if (error != EnchantError.NONE) {
            return error;
        }
        error = checkType(mission.equipment(), false, 0);
        if (error != EnchantError.NONE) {
            return error;
        }
        error = checkType(mission.card(), true, ItemScript.STACKABLE_MATERIAL_EXPERT_JOB);
        if (error != EnchantError.NONE) {
            return error;
        }
        return checkCardAndSlot(mission, recipe);
    }

    private int checkCardAndSlot(EnchantMission mission, ItemScript recipe) {
        if (mission.card().isEmpty()) {
            return EnchantError.WRONG_MATERIAL;
        }
        int wanted = findMonsterCard(recipe);
        if (wanted == 0 || wanted != mission.card().itemId()) {
            return EnchantError.WRONG_MATERIAL;
        }

        ItemScript card = itemScript(mission.card().itemId());
        ItemScript equipment = itemScript(mission.equipment().itemId());
        if (card == null || equipment == null) {
            return EnchantError.WRONG_MATERIAL;
        }
        if (!card.enchant().canAttachTo(equipment.equipmentSlot())) {
            return EnchantError.WRONG_TYPE;
        }
        return EnchantError.NONE;
    }

    private List<RecipeInfo.Material> materialsToConsume(EnchantMission mission,
                                                         ItemScript recipe) {
        RecipeInfo info = recipe.recipeInfo();
        if (!mission.useOtherUserMonsterCard()) {
            return info.materials();
        }
        return info.inputMaterials(this::subTypeOf);
    }

    private int findMonsterCard(ItemScript recipe) {
        if (recipe == null) {
            return 0;
        }
        return recipe.recipeInfo().monsterCardItemId(this::subTypeOf);
    }

    private int subTypeOf(int itemId) {
        ItemScript script = itemScript(itemId);
        return script == null ? -1 : script.subType();
    }

    private ItemScript itemScript(long itemId) {
        if (session.scripts() == null || itemId <= 0 || itemId > Integer.MAX_VALUE) {
            return null;
        }
        return session.scripts().item((int) itemId).orElse(null);
    }

    private int findSlotOf(GameSession owner, int itemId) {
        ItemArray bag = owner.inventory().containerOf(ItemListType.LIST_MAIN);
        if (bag == null) {
            return -1;
        }
        for (int slot = BagLayout.FIRST_ITEM_SLOT; slot < bag.slots(); slot++) {
            InvenItem item = bag.get(slot);
            if (!item.isEmpty() && item.itemId() == itemId) {
                return slot;
            }
        }
        return -1;
    }

    private void replyTry(Cmd179TryEnchantReplyBody body) {
        session.reply(Cmd179TryEnchantReplyBody.PACKET_ID, body.toBytes());
        session.flush();
    }

    private void replyAgree(EnchantMission mission, int error) {
        byte[] body = Cmd178AgreeEnchantReplyBody.rejected(error).toBytes();
        session.reply(Cmd178AgreeEnchantReplyBody.PACKET_ID, body);
        session.flush();
        GameSession enchanter = mission.enchanter();
        if (enchanter != null && enchanter != session) {
            enchanter.replyNow(Cmd178AgreeEnchantReplyBody.PACKET_ID, body);
        }
    }

    private void failEnchant(EnchantMission mission, int type, GameSession operator, int error) {
        if (operator != null) {
            if (mission.a4Invite()) {
                byte[] body = {0, (byte) error};
                GameSession enchanter = mission.enchanter();
                GameSession beneficiary = mission.beneficiary();
                if (beneficiary != null && beneficiary.hasCharacter()) {
                    beneficiary.replyNow(Cmd179TryEnchantReplyBody.PACKET_ID, body);
                }
                if (enchanter != null && enchanter.hasCharacter()) {
                    enchanter.replyNow(Cmd180PutItemForEnchantReplyBody.PACKET_ID, body);
                }
                closeMission(mission, operator, CLOSE_DONE);
                return;
            }
            boolean viaAgree = !mission.solo()
                    && type == Cmd178AgreeEnchantRequest.TYPE_AGREE
                    && operator == mission.beneficiary();
            if (viaAgree) {
                byte[] body = Cmd178AgreeEnchantReplyBody.rejected(error).toBytes();
                operator.replyNow(Cmd178AgreeEnchantReplyBody.PACKET_ID, body);
                GameSession enchanter = mission.enchanter();
                if (enchanter != null && enchanter != operator) {
                    enchanter.replyNow(Cmd178AgreeEnchantReplyBody.PACKET_ID, body);
                }
            } else if (mission.replyOn180()) {
                operator.replyNow(Cmd180PutItemForEnchantReplyBody.PACKET_ID,
                        Cmd180PutItemForEnchantReplyBody.rejected(error).toBytes());
            } else {
                operator.replyNow(Cmd179TryEnchantReplyBody.PACKET_ID,
                        Cmd179TryEnchantReplyBody.rejected(error).toBytes());
            }
        }
        closeMission(mission, operator, CLOSE_DONE);
    }
}
