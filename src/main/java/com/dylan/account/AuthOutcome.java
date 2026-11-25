package com.dylan.account;

import com.dylan.store.AccountRow;

public record AuthOutcome(int code, String message, AccountRow account) {

    public boolean ok() {
        return code == ApiCode.OK;
    }

    public static AuthOutcome ok(String message, AccountRow account) {
        return new AuthOutcome(ApiCode.OK, message, account);
    }

    public static AuthOutcome fail(int code, String message) {
        return new AuthOutcome(code, message, null);
    }
}
