package com.dylan.game;

import com.dylan.protocol.io.PacketWriter;
import com.dylan.pvf.GameScripts;
import com.dylan.pvf.script.InvalidNameTable;
import com.dylan.pvf.script.SlangNameTable;
import com.dylan.store.GameStore;

import java.util.ArrayList;
import java.util.List;

public final class NameFilter {
    private static final java.nio.charset.Charset CHARSET = PacketWriter.DEFAULT_CHARSET;

    private NameFilter() {
    }

    private record Tables(InvalidNameTable invalidName, SlangNameTable slangName,
                          SlangFilter slang, SlangFilter slangNames) {
        static final Tables EMPTY =
                new Tables(null, null, SlangFilter.EMPTY, SlangFilter.EMPTY);
    }

    private static volatile Tables tables = Tables.EMPTY;

    public static void init(GameScripts scripts, GameStore store) {
        InvalidNameTable invalidName = scripts == null ? null : scripts.invalidName();
        SlangNameTable slangName = scripts == null ? null : scripts.slangName();

        List<String> fromEtc = slangName == null ? List.of() : slangName.slangs();
        List<String> slangWords = new ArrayList<>(fromEtc);
        List<String> slangNameWords = new ArrayList<>(fromEtc);
        if (store != null) {
            slangWords.addAll(store.slang().loadSlangList());
            slangNameWords.addAll(store.slang().loadSlangListName());
        }
        install(invalidName, slangName, slangWords, slangNameWords);
    }

    public static void install(InvalidNameTable invalidName, SlangNameTable slangName,
                               List<String> slangWords, List<String> slangNameWords) {
        tables = new Tables(invalidName, slangName,
                SlangFilter.of(slangWords), SlangFilter.of(slangNameWords));

    }

    public static boolean hasSpecialCharacter(String name) {
        InvalidNameTable table = tables.invalidName();
        if (table == null || name == null) {
            return false;
        }
        return table.hasSpecialCharacter(name.getBytes(CHARSET));
    }

    public static boolean hasSpecialCharacterExceptSpace(String text) {
        InvalidNameTable table = tables.invalidName();
        if (table == null || text == null) {
            return false;
        }
        return table.hasSpecialCharacterExceptSpace(text.getBytes(CHARSET));
    }

    public static boolean hasSlang(String text) {
        return matches(tables.slang(), text);
    }

    public static boolean hasSlangName(String text) {
        return matches(tables.slangNames(), text);
    }

    private static boolean matches(SlangFilter filter, String text) {
        if (text == null || text.isEmpty() || filter.size() == 0) {
            return false;
        }
        if (filter.has(text)) {
            return true;
        }
        SlangNameTable slangName = tables.slangName();
        if (slangName == null || slangName.skipChars().isEmpty()) {
            return false;
        }
        String stripped = slangName.stripSkipChars(text);
        return !stripped.equals(text) && filter.has(stripped);
    }

    public static String rejectReason(String name) {
        if (name == null || name.isEmpty()) {
            return "名字是空的";
        }
        if (hasSpecialCharacter(name)) {
            return "名字里有不许用的字符（etc/invalidname.etc）";
        }
        if (hasSlang(name)) {
            return "名字命中通用敏感词（slang_list）";
        }
        if (hasSlangName(name)) {
            return "名字命中名字专用敏感词（slang_list_name）";
        }
        return null;
    }

    /**
     * 宠物改名专用的宽松校验：允许英文字母、数字、常见符号，仅拦截空名、控制字符、超长和敏感词。
     */
    public static String rejectReasonForCreature(String name) {
        if (name == null || name.isEmpty()) {
            return "名字是空的";
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                return "名字里有控制字符";
            }
        }
        if (name.getBytes(CHARSET).length > 24) {
            return "名字太长（宠物名最多 24 字节）";
        }
        if (hasSlang(name)) {
            return "名字命中通用敏感词（slang_list）";
        }
        return null;
    }
}
