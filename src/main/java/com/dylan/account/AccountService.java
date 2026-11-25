package com.dylan.account;

import com.dylan.store.AccountRow;
import com.dylan.store.AccountStore;
import com.dylan.store.GameStore;

import java.util.Optional;

public final class AccountService {

    private final GameStore store;

    public AccountService(GameStore store) {
        this.store = store;
    }

    public AuthOutcome authenticateFromGame(String userId, String password, boolean autoCreate) {
        String bad = AccountRules.checkUserId(userId);
        if (bad != null) {
            return AuthOutcome.fail(ApiCode.BAD_REQUEST, bad);
        }

        if (com.dylan.net.ServerConfig.LOGIN_TOKEN_MODE) {
            return authenticateByToken(userId, password);
        }

        return authenticateFromGameWithPassword(userId, password, autoCreate);
    }

    /** 原来那套：CMD 1 的密码位就是密码，直接比对 member_info.passwd。 */
    private AuthOutcome authenticateFromGameWithPassword(String userId, String password,
                                                         boolean autoCreate) {
        Optional<AccountStore.Credential> found = store.account().findCredential(userId);
        if (found.isEmpty()) {
            if (!autoCreate) {
                return AuthOutcome.fail(ApiCode.BAD_CREDENTIAL, "账号或密码错误");
            }
            store.account().createAccount(userId, password);
            AccountRow created = store.account().findAccount(userId).orElseThrow(
                    () -> new IllegalStateException("刚建的账号 " + userId + " 查不回来"));
            return AuthOutcome.ok("登录成功（新建账号）", created);
        }

        AccountStore.Credential credential = found.get();
        if (!matches(credential.password(), password)) {
            return AuthOutcome.fail(ApiCode.BAD_CREDENTIAL, "账号或密码错误");
        }
        if (credential.state() != AccountStore.ACCOUNT_STATE_NORMAL) {
            return AuthOutcome.fail(ApiCode.ACCOUNT_BANNED, "该账号已被封停");
        }

        store.account().touchLastPlayTime(userId);
        return AuthOutcome.ok("登录成功", credential.account());
    }

    /**
     * 令牌登录：CMD 1 的密码位装的是外部登录服务发的一次性令牌。
     * 令牌在 gw_login_token 里，命中即删除；账号本身仍然按 member_info 读，后面流程不变。
     */
    private AuthOutcome authenticateByToken(String userId, String token) {
        if (!store.account().consumeLoginToken(userId, token)) {
            if (com.dylan.net.ServerConfig.LOGIN_PASSWORD_FALLBACK) {
                // 调试期：令牌不对时还允许直接拿密码登录（老的 bat 还这么用）。
                // 正式服把 -Ds1a5.loginPasswordFallback=0 关掉，只认令牌。
                return authenticateFromGameWithPassword(userId, token, false);
            }
            return AuthOutcome.fail(ApiCode.BAD_CREDENTIAL, "登录令牌无效或已过期");
        }
        Optional<AccountStore.Credential> found = store.account().findCredential(userId);
        if (found.isEmpty()) {
            return AuthOutcome.fail(ApiCode.BAD_CREDENTIAL, "账号不存在");
        }
        if (found.get().state() != AccountStore.ACCOUNT_STATE_NORMAL) {
            return AuthOutcome.fail(ApiCode.ACCOUNT_BANNED, "该账号已被封停");
        }
        store.account().touchLastPlayTime(userId);
        return AuthOutcome.ok("登录成功（令牌）", found.get().account());
    }

    private static final String PBKDF2_PREFIX = "$pbkdf2$";

    private static boolean matches(String stored, String given) {
        if (stored == null || given == null) {
            return false;
        }
        if (stored.startsWith(PBKDF2_PREFIX)) {
            return matchesPbkdf2(stored, given);
        }
        return constantTimeEquals(stored, given);
    }

    private static boolean matchesPbkdf2(String stored, String given) {
        // 格式：$pbkdf2$<iterations>$<salt_hex>$<hash_hex>
        String[] parts = stored.split("\\$");
        if (parts.length != 5) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = parseHex(parts[3]);
            byte[] expected = parseHex(parts[4]);
            if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
                return false;
            }
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    given.toCharArray(), salt, iterations, expected.length * 8);
            javax.crypto.SecretKeyFactory skf =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actual = skf.generateSecret(spec).getEncoded();
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] parseHex(String hex) {
        if ((hex.length() & 1) != 0) {
            return new byte[0];
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return new byte[0];
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
