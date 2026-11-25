package com.dylan.pvf.script;

import java.util.ArrayList;
import java.util.List;

/**
 * etc/guild.etc —— 公会参数（客户端 {@code Etc/Guild.etc}，读到 dword_D7F26C；台服 GuildParameterScript）。
 * <ul>
 * <li>[guild create cost] 建会费用。</li>
 * <li>[guild levelup cost] 每行 {@code 公会等级 经验 费用}：等级 L 升 L+1 要公会累计经验 ≥ 该行经验、扣该行费用。
 *     客户端博肯菜单（0x945A4D）和公会窗口经验条（0x8D8AC0）都按这张表画。</li>
 * <li>[guild exp weight] 每行 {@code 角色等级下限 上限 权重(百分比)}：台服 CUser::FatigueUp 每用 1 点疲劳给
 *     临时公会经验 weight×疲劳÷100（getGuildExpWeight）。</li>
 * </ul>
 */
public final class GuildParameter {
    public static final String PATH = "etc/guild.etc";

    public record LevelCost(int level, long exp, long price) {
    }

    private record Weight(int min, int max, int percent) {
    }

    private final long createCost;
    private final List<LevelCost> levelCosts;
    private final List<Weight> weights;

    private GuildParameter(long createCost, List<LevelCost> levelCosts, List<Weight> weights) {
        this.createCost = createCost;
        this.levelCosts = List.copyOf(levelCosts);
        this.weights = List.copyOf(weights);
    }

    /** 读不到 PVF 时用官方 guild.etc 的数值。 */
    public static GuildParameter defaults() {
        long[][] t = {{1350, 300000}, {4894, 500000}, {12150, 700000}, {25175, 1000000}, {46738, 1400000},
                {80547, 1800000}, {131545, 2400000}, {206299, 3100000}, {313498, 3900000}, {464610, 4900000}};
        List<LevelCost> costs = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            costs.add(new LevelCost(i + 1, t[i][0], t[i][1]));
        }
        return new GuildParameter(300000, costs, List.of(new Weight(1, 15, 200), new Weight(16, 30, 175),
                new Weight(31, 40, 150), new Weight(41, 50, 125), new Weight(51, 99, 100)));
    }

    public static GuildParameter parse(String text) {
        GuildParameter d = defaults();
        long create = d.createCost;
        List<LevelCost> costs = new ArrayList<>();
        List<Weight> weights = new ArrayList<>();
        String section = "";
        for (String raw : text.split("\r?\n")) {
            String line = raw;
            int cut = line.indexOf("//");
            if (cut >= 0) {
                line = line.substring(0, cut);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                section = end < 0 ? line.substring(1) : line.substring(1, end);
                String rest = end < 0 ? "" : line.substring(end + 1).trim();
                if (section.equals("guild create cost") && !rest.isEmpty()) {
                    create = Long.parseLong(rest.split("\\s+")[0]);
                }
                continue;
            }
            String[] f = line.split("\\s+");
            try {
                if (section.equals("guild levelup cost") && f.length >= 3) {
                    costs.add(new LevelCost(Integer.parseInt(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])));
                } else if (section.equals("guild exp weight") && f.length >= 3) {
                    weights.add(new Weight(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])));
                }
            } catch (NumberFormatException ignored) {
                // 不认识的行跳过
            }
        }
        return new GuildParameter(create, costs.isEmpty() ? d.levelCosts : costs,
                weights.isEmpty() ? d.weights : weights);
    }

    public long createCost() {
        return createCost;
    }

    /** 公会等级 level 升下一级的条件；没有这一行（满级）返回 null。 */
    public LevelCost levelCost(int level) {
        for (LevelCost c : levelCosts) {
            if (c.level() == level) {
                return c;
            }
        }
        return null;
    }

    /** 角色等级对应的公会经验权重（百分比），表里没有返回 0。 */
    public int expWeight(int characterLevel) {
        for (Weight w : weights) {
            if (characterLevel >= w.min() && characterLevel <= w.max()) {
                return w.percent();
            }
        }
        return 0;
    }
}
