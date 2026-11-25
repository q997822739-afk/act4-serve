package com.dylan.pvf.script;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code etc/pvp_ref.etc} 的 [pvp experience]：决斗经验到第 n 档需要的累计值（第 1 行 = 9 级）。
 * 段位 0 = 10 级（新手），1..9 = 9 级..1 级，10..19 = 1 段..10 段，20..29 = 至尊 1..10。
 */
public final class PvpRefTable {

    public static final String PATH = "etc/pvp_ref.etc";

    private final List<Long> thresholds;

    private final int maxGrade;

    private PvpRefTable(List<Long> thresholds, int maxGrade) {
        this.thresholds = List.copyOf(thresholds);
        this.maxGrade = maxGrade;
    }

    public static PvpRefTable empty() {
        return new PvpRefTable(List.of(), 0);
    }

    public static PvpRefTable parse(String text) {
        List<Long> thresholds = new ArrayList<>();
        int maxGrade = 0;
        boolean inTable = false;
        for (String raw : text.split("\n")) {
            String line = raw;
            int comment = line.indexOf("//");
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[pvp experience max grade]")) {
                String rest = line.substring("[pvp experience max grade]".length()).trim();
                try {
                    maxGrade = Integer.parseInt(rest.split("\\s+")[0]);
                } catch (NumberFormatException ignored) {
                    maxGrade = 0;
                }
                continue;
            }
            if (line.equals("[pvp experience]")) {
                inTable = true;
                continue;
            }
            if (line.equals("[/pvp experience]")) {
                inTable = false;
                continue;
            }
            if (inTable) {
                String[] f = line.split("\\s+");
                if (f.length >= 2) {
                    try {
                        thresholds.add(Long.parseLong(f[1]));
                    } catch (NumberFormatException ignored) {
                        // 跳过不是数字的行
                    }
                }
            }
        }
        return new PvpRefTable(thresholds, maxGrade == 0 ? thresholds.size() : maxGrade);
    }

    public boolean isEmpty() {
        return thresholds.isEmpty();
    }

    /** 累计决斗经验对应的段位。 */
    public int gradeOf(long pvpExp) {
        int grade = 0;
        for (int i = 0; i < thresholds.size() && i < maxGrade - 1; i++) {
            if (pvpExp >= thresholds.get(i)) {
                grade = i + 1;
            }
        }
        return grade;
    }
}
