package com.dylan.pvf.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * etc/unlimitchallenge.etc —— 升级目标奖励（客户端"谢谢您！距离下一个奖励目标还剩下 LvN"弹窗读的就是这张表）。
 * 只用 [reward_server]：按职业分段，每行 {@code 等级 物品号 数量或期限 能力值}，物品号 0 = 金币（数量 = 金额）。
 * [message] 每个等级一行 {@code 等级 `标题键` `正文键`}。官方 CUser::_processLevelReward 在升级时按表发系统邮件。
 */
public final class LevelRewardTable {
    public static final String PATH = "etc/unlimitchallenge.etc";

    private static final String[] JOBS = {"swordman", "fighter", "gunner", "mage", "priest"};

    public record Reward(int level, int itemId, int count, int ability) {
    }

    public record Message(String titleKey, String bodyKey) {
    }

    private final boolean on;
    private final Map<Integer, List<Reward>> byJob;
    private final Map<Integer, Message> messages;

    private LevelRewardTable(boolean on, Map<Integer, List<Reward>> byJob,
                             Map<Integer, Message> messages) {
        this.on = on;
        this.byJob = byJob;
        this.messages = messages;
    }

    public static LevelRewardTable empty() {
        return new LevelRewardTable(false, Map.of(), Map.of());
    }

    public static LevelRewardTable parse(String text) {
        boolean on = false;
        Map<Integer, List<Reward>> byJob = new HashMap<>();
        Map<Integer, Message> messages = new HashMap<>();
        String outer = null;
        int job = -1;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
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
                String tag = end < 0 ? line.substring(1) : line.substring(1, end);
                String rest = end < 0 ? "" : line.substring(end + 1).trim();
                if (tag.equals("onoff")) {
                    on = rest.startsWith("1");
                    continue;
                }
                if (tag.startsWith("/")) {
                    String closed = tag.substring(1);
                    if (closed.equals(outer)) {
                        outer = null;
                    }
                    job = -1;
                    continue;
                }
                int j = jobIndex(tag);
                if (j >= 0) {
                    job = j;
                } else {
                    outer = tag;
                    job = -1;
                }
                continue;
            }
            String[] t = line.split("\\s+");
            if ("reward_server".equals(outer) && job >= 0 && t.length >= 3) {
                try {
                    int level = Integer.parseInt(t[0]);
                    int item = Integer.parseInt(t[1]);
                    int count = Integer.parseInt(t[2]);
                    int ability = t.length >= 4 ? Integer.parseInt(t[3]) : 0;
                    byJob.computeIfAbsent(job, k -> new ArrayList<>())
                            .add(new Reward(level, item, count, ability));
                } catch (NumberFormatException ignored) {
                }
            } else if ("message".equals(outer) && t.length >= 3) {
                try {
                    messages.put(Integer.parseInt(t[0]),
                            new Message(t[1].replace("`", ""), t[2].replace("`", "")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new LevelRewardTable(on, byJob, messages);
    }

    private static int jobIndex(String tag) {
        for (int i = 0; i < JOBS.length; i++) {
            if (JOBS[i].equals(tag)) {
                return i;
            }
        }
        return -1;
    }

    public boolean on() {
        return on;
    }

    /** 某职业在某个等级的奖励（没有返回空表）。 */
    public List<Reward> rewards(int job, int level) {
        List<Reward> out = new ArrayList<>();
        for (Reward r : byJob.getOrDefault(job, List.of())) {
            if (r.level() == level) {
                out.add(r);
            }
        }
        return out;
    }

    public Message message(int level) {
        return messages.get(level);
    }

    @Override
    public String toString() {
        int n = byJob.values().stream().mapToInt(List::size).sum();
        return "升级目标奖励 " + (on ? "开" : "关") + "，" + byJob.size() + " 个职业共 " + n + " 条";
    }
}
