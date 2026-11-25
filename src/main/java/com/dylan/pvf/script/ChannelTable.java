package com.dylan.pvf.script;

import com.dylan.pvf.PvfArchive;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code etc/channel_info.etc}：频道表与「对应频道」的副本覆盖表。
 */
public final class ChannelTable {

    public static final String PATH = "etc/channel_info.etc";

    /** 频道行：频道号、地区 TAG、对应频道经验加成百分比。 */
    public record Row(int channelId, String tag, int bonusPercent) {
    }

    private final Map<Integer, Row> byChannel;

    private final Map<String, Set<Integer>> dungeonsByTag;

    private ChannelTable(Map<Integer, Row> byChannel, Map<String, Set<Integer>> dungeonsByTag) {
        this.byChannel = Map.copyOf(byChannel);
        this.dungeonsByTag = Map.copyOf(dungeonsByTag);
    }

    public static ChannelTable load(PvfArchive archive) {
        return parse(archive.readText(PATH));
    }

    public static ChannelTable parse(String text) {
        Map<Integer, Row> channels = new LinkedHashMap<>();
        Map<String, Set<Integer>> dungeons = new LinkedHashMap<>();
        String section = "";
        String pendingTag = null;

        for (String raw : text.split("\r?\n", -1)) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (line.startsWith("[/")) {
                    section = "";
                    pendingTag = null;
                } else {
                    section = line.substring(1, line.indexOf(']')).trim();
                }
                continue;
            }
            if ("dungeon".equals(section)) {
                if (pendingTag == null) {
                    String tag = unwrapTag(line);
                    if (!tag.isEmpty()) {
                        pendingTag = tag;
                        dungeons.computeIfAbsent(tag, k -> new LinkedHashSet<>());
                    }
                    continue;
                }
                String[] cells = line.split("\t", -1);
                for (String cell : cells) {
                    if (parseInt(cell.trim()) instanceof Integer id && id > 0) {
                        dungeons.get(pendingTag).add(id);
                    }
                }
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length < 5) {
                continue;
            }
            Integer id = parseInt(f[0].trim());
            if (id == null) {
                continue;
            }
            String tag = unwrapTag(f[3].trim());
            Integer bonus = parseInt(f[4].trim());
            if (tag.isEmpty() || bonus == null) {
                continue;
            }
            channels.put(id, new Row(id, tag, bonus));
        }
        return new ChannelTable(channels, dungeons);
    }

    public Optional<Row> channel(int channelId) {
        return Optional.ofNullable(byChannel.get(channelId));
    }

    /**
     * 对应频道经验加成百分比：频道所属地区包含该副本才返回加成，否则 0。
     */
    public int bonusPercent(int channelId, int dungeonId) {
        Row row = byChannel.get(channelId);
        if (row == null) {
            return 0;
        }
        Set<Integer> list = dungeonsByTag.get(row.tag());
        if (list == null || !list.contains(dungeonId)) {
            return 0;
        }
        return row.bonusPercent();
    }

    public int size() {
        return byChannel.size();
    }

    @Override
    public String toString() {
        return "ChannelTable[" + byChannel.size() + " 个频道，" + dungeonsByTag.size() + " 个地区]";
    }

    private static String stripComment(String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }

    private static String unwrapTag(String cell) {
        String s = cell.replace("`", "").trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static Integer parseInt(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
