package com.dylan.game;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * SS 史诗（物品 [rarity] = 4）从怪物掉落被捡起时：全服系统消息播报 + 写 s1a5_audit.epic_drop_log 供查询。
 * 格式：玩家名 + 时间 + 副本 + 物品名。玩家自己扔在地上的不算（ground.fromPlayer()）。
 */
public final class EpicDropNotice {

    public static final int RARITY_EPIC = 4;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private EpicDropNotice() {
    }

    public static void onPickup(GameSession winner, int itemId, String itemName, int rarity) {
        if (rarity != RARITY_EPIC || winner == null) {
            return;
        }
        DungeonRunSummary summary = winner.runSummary();
        int dungeonId = summary == null ? 0 : summary.dungeonId();
        String dungeonName = summary == null ? "" : summary.dungeonName();
        if ((dungeonName == null || dungeonName.isBlank()) && winner.scripts() != null && dungeonId > 0) {
            dungeonName = winner.scripts().dungeonName(dungeonId);
        }
        if (dungeonName == null || dungeonName.isBlank()) {
            dungeonName = dungeonId > 0 ? "副本#" + dungeonId : "未知副本";
        }
        String player = winner.characName() == null || winner.characName().isBlank()
                ? "角色#" + winner.characNo() : winner.characName();

        try {
            AuditRecorder.recordEpicDrop(winner, dungeonId, dungeonName, itemId, itemName, rarity);
        } catch (RuntimeException e) {
            System.err.println("[史诗播报] 记录失败：" + e.getMessage());
        }

        String text = "【全服播报】玩家[" + player + "] 于 " + LocalDateTime.now().format(TIME_FMT)
                + " 在[" + dungeonName + "]获得SS史诗【" + itemName + "】！";
        System.out.println("[史诗播报] " + text);
        for (GameSession other : new ArrayList<>(SessionRegistry.playing())) {
            try {
                other.run(() -> ChatService.system(other, text));
            } catch (RuntimeException ignored) {
                // 个别会话已断开，不影响其他人
            }
        }
    }
}
