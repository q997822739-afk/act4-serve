package com.dylan.game.handler;

import com.dylan.game.CommandHandler;
import com.dylan.game.FriendService;
import com.dylan.game.GameSession;
import com.dylan.net.ServerConfig;

import java.util.Set;

/** A4 好友 CMD 63 加 / 64 删；黑名单 CMD 128 加 / 129 删（见 BlacklistService）。布局见 {@link FriendService}。 */
public final class FriendHandler implements CommandHandler {

    @Override
    public Set<Integer> handles() {
        return ServerConfig.A4 ? Set.of(63, 64, 128, 129) : Set.of();
    }

    @Override
    public void handle(GameSession s, int packetId, byte[] plain) {
        if (!s.hasCharacter()) {
            return;
        }
        switch (packetId) {
            case 63 -> FriendService.add(s, plain);
            case 64 -> FriendService.remove(s, plain);
            case 128 -> com.dylan.game.BlacklistService.add(s, plain);
            case 129 -> com.dylan.game.BlacklistService.remove(s, plain);
            default -> throw new IllegalStateException("不该路由到这里：CMD " + packetId);
        }
        s.flush();
    }
}
