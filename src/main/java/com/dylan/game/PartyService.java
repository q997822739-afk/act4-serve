package com.dylan.game;

import com.dylan.protocol.body.Noti9PartyInfoBody;

import java.util.List;

public final class PartyService {
    private final GameSession session;

    public PartyService(GameSession session) {
        this.session = session;
    }

    public Party current() {
        return PartyRegistry.partyOf(session);
    }

    public boolean mayLeadTheParty() {
        Party party = current();
        return party == null || party.isManager(session);
    }

    private boolean inTown() {
        SessionRegistry.Presence p = SessionRegistry.presenceOf(session);
        return p != null && p.inTown();
    }

    public void onLeaveWorld(String why) {
        PartyRegistry.dropInvite(session);
        PartyRegistry.dropInvitesFrom(session);
        Party party = current();
        if (party != null) {
            depart(party, session, why);
        }
    }

    public void onLeaveDungeon(String why) {
        Party party = current();
        if (party != null) {
            depart(party, session, why);
        }
    }

    private void depart(Party party, GameSession leaver, String why) {
        Party.Departure gone = party.leave(leaver);
        if (gone == null) {
            return;
        }
        PartyRegistry.forget(leaver);

        // 城镇组队进本用的是多人 Party（singlePlay = false）。退一个人不能拆掉剩下的人。
        if (!party.singlePlay()) {
            if (gone.disbanded()) {
                PartyRegistry.dissolve(party);
            } else if (party.state() != DungeonInstance.STATE_TOWN) {
                // 副本打到一半有人走了（放弃回城 / 掉线）：照台服 CParty::giveup_game → leave_user(user, 2)，
                // 他整个退出队伍，剩下的人收 NOTI 9 类型 2（槽位不动）+ 换主机。以前什么都不发，
                // 剩下的人副本里那个人一直站着（B 服反馈"一个队友回城了但还是没有结束"）。
                PartyInviteService.onLeftDungeonParty(leaver, gone);
            }
            return;
        }

        if (!gone.disbanded()) {
            throw new IllegalStateException("单人版的队伍 " + party.index() + " 在角色 "
                    + leaver.characNo() + " 离开后还剩 " + gone.view().size() + " 人");
        }

        byte[] disband = Noti9PartyInfoBody.disband(party.index()).toBytes();
        leaver.sendNow(Noti9PartyInfoBody.PACKET_ID, disband);
        PartyRegistry.dissolve(party);
    }

    public List<GameSession> chatRecipients() {
        Party party = current();
        return party == null ? List.of() : party.view().members();
    }

}
