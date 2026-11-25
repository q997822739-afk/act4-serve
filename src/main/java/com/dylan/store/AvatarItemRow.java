package com.dylan.store;

import java.time.Instant;

public record AvatarItemRow(long uiId, int characNo, int slot, int itemId,
                            Instant expireDate, int abilityNo, long commodityNo,
                            int lockKey) {

    /** 兼容旧调用：不带锁。 */
    public AvatarItemRow(long uiId, int characNo, int slot, int itemId,
                         Instant expireDate, int abilityNo, long commodityNo) {
        this(uiId, characNo, slot, itemId, expireDate, abilityNo, commodityNo, 0);
    }

    public AvatarItemRow withLockKey(int key) {
        return new AvatarItemRow(uiId, characNo, slot, itemId, expireDate, abilityNo,
                commodityNo, key);
    }

    public static final long NOT_PERSISTED = 0L;

    public AvatarItemRow withSlot(int newSlot) {
        return new AvatarItemRow(uiId, characNo, newSlot, itemId, expireDate, abilityNo,
                commodityNo, lockKey);
    }

    public AvatarItemRow withCharacNo(int newCharacNo) {
        return new AvatarItemRow(uiId, newCharacNo, slot, itemId, expireDate, abilityNo,
                commodityNo, lockKey);
    }

    public static final Instant NO_EXPIRE = Instant.EPOCH;

    public boolean permanent() {
        return expireDate == null;
    }

    public long remainingSeconds(Instant now) {
        if (expireDate == null) {
            return 0L;
        }
        long remaining = expireDate.getEpochSecond() - now.getEpochSecond();
        return remaining <= 0 ? 0L : Math.min(MAX_REMAINING_SECONDS, remaining);
    }

    public static final long MAX_REMAINING_SECONDS = Integer.MAX_VALUE / 2;
}
