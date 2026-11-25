package com.dylan.pvf.script;

import com.dylan.pvf.PvfArchive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QuestParameterScript {
    public static final String PATH = "quest/questparameter.etc";

    public static final int DEFAULT_WEIGHT = 100;

    public static final int GREEN_PENALTY_FROM = 7;

    public static final int GREEN_PENALTY_TO = 11;

    private final Map<Character, Integer> weights;
    private final int greenLevelPenalty;
    private final int greyLevelPenalty;
    private final int balKunPenalty;
    private final List<Integer> expRewardTable;
    private final List<Integer> goldRewardTable;

    private QuestParameterScript(Map<Character, Integer> weights, int greenLevelPenalty,
                                 int greyLevelPenalty, int balKunPenalty,
                                 List<Integer> expRewardTable, List<Integer> goldRewardTable) {
        this.weights = Map.copyOf(weights);
        this.greenLevelPenalty = greenLevelPenalty;
        this.greyLevelPenalty = greyLevelPenalty;
        this.balKunPenalty = balKunPenalty;
        this.expRewardTable = List.copyOf(expRewardTable);
        this.goldRewardTable = List.copyOf(goldRewardTable);
    }

    public static QuestParameterScript empty() {
        return new QuestParameterScript(Map.of(), 100, 100, 0, List.of(), List.of());
    }

    public static QuestParameterScript load(PvfArchive archive) {
        return parse(archive.readText(PATH));
    }

    public static QuestParameterScript parse(String text) {
        Map<String, List<String>> sections = EtcSections.split(text);
        Map<Character, Integer> weights = new HashMap<>();
        for (String line : sections.getOrDefault("difficulty", List.of())) {
            String label = EtcSections.groupLabel(line);
            if (label == null || label.isEmpty()) {
                continue;
            }
            String[] parts = EtcSections.tokens(line);
            int value = EtcSections.parseInt(parts[parts.length - 1], DEFAULT_WEIGHT);
            weights.put(normalize(label.charAt(0)), value);
        }
        return new QuestParameterScript(
                weights,
                firstInt(sections, "green level penalty", 100),
                firstInt(sections, "grey level penalty", 100),
                firstInt(sections, "bal kun penalty", 0),
                allInts(sections, "exp reward table"),
                allInts(sections, "gold reward table"));
    }

    public int rewardWeight(char difficulty) {
        Integer value = weights.get(normalize(difficulty));
        return value == null ? DEFAULT_WEIGHT : value;
    }

    public int levelPenalty(int levelDifference) {
        if (levelDifference >= GREEN_PENALTY_FROM && levelDifference <= GREEN_PENALTY_TO) {
            return greenLevelPenalty;
        }
        return levelDifference <= GREEN_PENALTY_TO ? 100 : greyLevelPenalty;
    }

    public int rewardExp(int level) {
        return at(expRewardTable, level);
    }

    public int rewardGold(int level) {
        return at(goldRewardTable, level);
    }

    private static int at(List<Integer> table, int index) {
        if (index < 0 || index >= table.size()) {
            return 0;
        }
        return table.get(index);
    }

    private static char normalize(char c) {
        return Character.toUpperCase(c);
    }

    private static int firstInt(Map<String, List<String>> sections, String name, int fallback) {
        List<String> lines = sections.getOrDefault(name, List.of());
        if (lines.isEmpty()) {
            return fallback;
        }
        return EtcSections.parseInt(EtcSections.tokens(lines.get(0))[0], fallback);
    }

    private static List<Integer> allInts(Map<String, List<String>> sections, String name) {
        List<Integer> out = new ArrayList<>();
        for (String line : sections.getOrDefault(name, List.of())) {
            for (String token : EtcSections.tokens(line)) {
                try {
                    out.add(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "难度权重 %d 档、等级差惩罚 %d/%d、经验表 %d 项、金币表 %d 项",
                weights.size(), greenLevelPenalty, greyLevelPenalty,
                expRewardTable.size(), goldRewardTable.size());
    }

    // ------------------------------------------------------------------
    // A4 兜底表：这个客户端的 Script.pvf 里**没有** quest/questparameter.etc
    // （那是 ACT5 才带进来的文件），于是交任务的经验/金币全算成 0，
    // 弹窗显示"0金币"、背包也不加钱。
    // 下面这份数字抄自完整 PVF 里的同名文件，结构一致，缺文件时用它。
    // ------------------------------------------------------------------

    private static final int[] EXP_TABLE = {
            100, 156, 254, 365, 488, 625, 775, 1251, 1485, 3473, 4011, 5042,
            5711, 6419, 7163, 7946, 8768, 9627, 10525, 13545, 14697, 15893, 17134, 15587,
            16713, 17879, 19083, 20324, 21604, 25006, 26485, 28007, 29569, 31174, 32820, 34507,
            36238, 38009, 39822, 34730, 36310, 37925, 39575, 41259, 38680, 40259, 41869, 43510,
            45182, 41676, 43218, 44788, 46386, 48010, 49664, 44927, 46422, 47942, 49485, 51053,
            52646, 54262, 55903, 57569, 59258, 60972, 62710, 64473, 66260, 68071, 69906, 71766,
            73651, 75559, 77491, 79449, 81430, 83435, 85465, 87520, 89598, 91701, 93828, 95980,
            98155, 100356, 102580, 104829, 107102, 109400, 111721, 114067, 116437, 118832, 121251, 123694,
            126162, 128654, 131170,
    };

    private static final int[] GOLD_TABLE = {
            80, 112, 144, 176, 208, 240, 272, 312, 352, 400, 448, 504,
            560, 616, 672, 728, 784, 840, 896, 960, 1024, 1088, 1152, 1216,
            1280, 1344, 1408, 1472, 1536, 1600, 1680, 1760, 1840, 1920, 2000, 2080,
            2160, 2240, 2320, 2400, 2520, 2640, 2760, 2880, 3000, 3120, 3240, 3360,
            3480, 3600, 3720, 3840, 3960, 4080, 4200, 4320, 4440, 4560, 4680, 4800,
            4920, 5040, 5160, 5280, 5400, 5520, 5640, 5760, 5880, 6000, 6120, 6240,
            6360, 6480, 6600, 6720, 6840, 6960, 7080, 7200, 7360, 7520, 7680, 7840,
            8000, 8160, 8320, 8480, 8640, 8800, 9120, 9440, 9760, 10080, 10400, 10720,
            11040, 11360, 11680,
    };

    /** PVF 里没有（或解析出空表）时用这套内置表。 */
    public static QuestParameterScript builtIn() {
        java.util.Map<Character, Integer> w = new java.util.HashMap<>();
        w.put('S', 100);
        w.put('A', 80);
        w.put('B', 60);
        w.put('C', 40);
        w.put('M', 10);
        w.put('Z', 430);
        w.put('Y', 410);
        w.put('X', 390);
        w.put('W', 370);
        w.put('O', 10);
        w.put('D', 200);
        java.util.List<Integer> exp = new java.util.ArrayList<>();
        for (int v : EXP_TABLE) {
            exp.add(v);
        }
        java.util.List<Integer> gold = new java.util.ArrayList<>();
        for (int v : GOLD_TABLE) {
            gold.add(v);
        }
        return new QuestParameterScript(w, 80, 30, 5, exp, gold);
    }

    /** 经验和金币表都读到了才算可用。 */
    public boolean hasRewardTables() {
        return !expRewardTable.isEmpty() && !goldRewardTable.isEmpty();
    }
}
