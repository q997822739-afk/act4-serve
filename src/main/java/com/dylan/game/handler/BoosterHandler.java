package com.dylan.game.handler;

import java.util.List;
import java.util.Set;

import com.dylan.game.BoosterService;
import com.dylan.game.CommandHandler;
import com.dylan.game.GameSession;
import com.dylan.protocol.body.Cmd170ReplyBody;
import com.dylan.protocol.body.Cmd170UseBoosterItemRequest;
import com.dylan.protocol.io.PacketWriter;

public final class BoosterHandler implements CommandHandler {

    /** ACT4 客户端右键 [booster] 礼包实际发的是 CMD 171。 */
    private static final int CMD_USE_BOOSTER_ITEM_V2 = 171;

    @Override
    public Set<Integer> handles() {
        // A4 的 170 是死亡之塔层命令（见 DungeonHandler.CMD_DEATH_TOWER_STAGE_A4），拆礼包只有 171。
        if (com.dylan.net.ServerConfig.A4) {
            return Set.of(CMD_USE_BOOSTER_ITEM_V2);
        }
        return Set.of(Cmd170UseBoosterItemRequest.PACKET_ID, CMD_USE_BOOSTER_ITEM_V2);
    }

    private static byte[] openedV2(int slot, long remain,
                                   List<Cmd170ReplyBody.Entry> entries) {
        PacketWriter w = new PacketWriter(9 + entries.size() * 8)
                .u8(Cmd170ReplyBody.RESULT_OK)
                .u16(slot)
                .u32((int) remain)
                .u16(entries.size());
        for (Cmd170ReplyBody.Entry e : entries) {
            w.u32(e.itemId()).u32((int) e.count());
        }
        return w.toArray();
    }

    @Override
    public void handle(GameSession session, int packetId, byte[] plain) {
        Cmd170UseBoosterItemRequest req;
        try {
            req = Cmd170UseBoosterItemRequest.parse(plain);
        } catch (RuntimeException e) {
            session.fail("CMD " + packetId + " 拆礼包包体不合法（" + plain.length + " 字节）", e);
            return;
        }

        if (!session.hasCharacter()) {
            session.reply(packetId, Cmd170ReplyBody.rejected(Cmd170ReplyBody.ERROR_CANNOT_USE));
            return;
        }

        BoosterService.Result result = session.booster().use(req.slot());
        if (result.fatal() != null) {
            session.fail("CMD " + packetId + " 拆礼包：" + result.fatal());
            return;
        }
        if (!result.ok()) {
            session.reply(packetId, Cmd170ReplyBody.rejected(result.error()));
            return;
        }
        if (packetId == CMD_USE_BOOSTER_ITEM_V2) {
            session.reply(packetId, openedV2(result.slot(), result.remain(), result.entries()));
        } else {
            session.reply(packetId,
                    Cmd170ReplyBody.opened(result.slot(), result.remain(), result.entries()));
        }
    }
}
