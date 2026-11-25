package com.dylan.game.handler;

import java.util.Set;

import com.dylan.game.CommandHandler;
import com.dylan.game.GameSession;
import com.dylan.game.SessionRegistry;
import com.dylan.protocol.body.Cmd178AgreeEnchantRequest;
import com.dylan.protocol.body.Cmd179TryEnchantRequest;
import com.dylan.protocol.body.Cmd180PutItemForEnchantRequest;

public final class EnchantHandler implements CommandHandler {

    /** A4 双人附魔窗里客户填手续费 / 放装备（0x91C440 / 0x91C3E0）。 */
    static final int INVITE_181 = 181;

    @Override
    public Set<Integer> handles() {
        return Set.of(Cmd178AgreeEnchantRequest.PACKET_ID,
                Cmd179TryEnchantRequest.PACKET_ID,
                Cmd180PutItemForEnchantRequest.PACKET_ID,
                INVITE_181);
    }

    @Override
    public void handle(GameSession session, int packetId, byte[] plain) {
        if (!session.hasCharacter() || SessionRegistry.presenceOf(session) == null) {
            return;
        }
        if (packetId == INVITE_181) {
            session.enchant().onInvite181(plain);
            return;
        }
        if (session.enchant().inA4Invite()) {
            if (packetId == Cmd179TryEnchantRequest.PACKET_ID) {
                session.enchant().onInvite179(plain);
                return;
            }
            if (packetId == Cmd180PutItemForEnchantRequest.PACKET_ID) {
                session.enchant().onInvite180(plain);
                return;
            }
        }
        if (packetId == Cmd179TryEnchantRequest.PACKET_ID) {
            Cmd179TryEnchantRequest request = Cmd179TryEnchantRequest.parse(plain);
            session.enchant().onTryEnchant(request);
            return;
        }
        if (packetId == Cmd178AgreeEnchantRequest.PACKET_ID) {
            Cmd178AgreeEnchantRequest request = Cmd178AgreeEnchantRequest.parse(plain);
            session.enchant().onAgreeEnchant(request);
            return;
        }
        if (com.dylan.game.EnchantService.isSoloPut(plain)) {
            session.enchant().onSoloPut(plain);
            return;
        }
        Cmd180PutItemForEnchantRequest request = Cmd180PutItemForEnchantRequest.parse(plain);
        session.enchant().onPutItem(request);
    }
}
