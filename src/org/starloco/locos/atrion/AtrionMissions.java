package org.starloco.locos.atrion;

import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.entity.monster.MonsterGrade;
import org.starloco.locos.game.world.World;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Atrion : missions quotidiennes, classement de la semaine, premieres victoires du serveur sur les boss,
 * bonus d'XP en groupe, annonces de butin legendaire.
 */
public final class AtrionMissions {

    public static final boolean MISSIONS = true;
    public static final boolean WEEKLY = true;
    public static final boolean SERVER_FIRSTS = true;
    public static final boolean GROUP_BONUS = true;
    public static final boolean LOOT_ANNOUNCE = true;

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private AtrionMissions() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    private static long today() {
        return LocalDate.now(PARIS).toEpochDay();
    }

    // ================================================================== Bonus d'XP en groupe

    /** +10 % d'XP par joueur supplementaire dans l'equipe gagnante, jusqu'a +30 %. */
    public static double groupXpFactor(int playersInTeam) {
        if (!GROUP_BONUS || playersInTeam <= 1) return 1.0;
        return 1.0 + 0.10 * Math.min(3, playersInTeam - 1);
    }

    // ================================================================== Annonces de butin

    public static void announceLoot(Player player, String itemName, Rarity rarity) {
        if (!LOOT_ANNOUNCE || player == null || rarity == null || rarity.tier < Rarity.LEGENDAIRE.tier) return;
        SocketManager.GAME_SEND_MESSAGE_TO_ALL(player.getName() + " a obtenu " + itemName + " " + rarity.mark() + " !",
                rarity == Rarity.MYTHIQUE ? "FF3B3B" : "FFC21F");
    }

    // ================================================================== Missions quotidiennes

    private enum Type { WIN, KILL, KILL_HIGH, BOSS, JOB_XP, GROUP }

    private static final class Mission {
        final Type type;
        final int goal, reward;

        Mission(Type type, int goal, int reward) { this.type = type; this.goal = goal; this.reward = reward; }

        String label() {
            switch (type) {
                case WIN: return "Gagner " + goal + " combats";
                case KILL: return "Vaincre " + goal + " monstres";
                case KILL_HIGH: return "Vaincre " + goal + " monstres de ton niveau ou plus (-10 niveaux)";
                case BOSS: return "Vaincre " + goal + " boss de donjon";
                case JOB_XP: return "Gagner " + goal + " points d'XP de metier";
                default: return "Gagner " + goal + " combats en groupe (2 joueurs ou plus)";
            }
        }
    }

    private static final int ALL_BONUS = 25;

    private static Mission[] missionsFor(Player player) {
        Random rng = new Random(today() * 31 + player.getId() * 7L);
        List<Type> types = new ArrayList<>(Arrays.asList(Type.values()));
        Collections.shuffle(types, rng);
        Mission[] out = new Mission[3];
        for (int i = 0; i < 3; i++) {
            Type t = types.get(i);
            switch (t) {
                case WIN: out[i] = new Mission(t, 8 + rng.nextInt(8), 15); break;
                case KILL: out[i] = new Mission(t, 30 + rng.nextInt(31), 15); break;
                case KILL_HIGH: out[i] = new Mission(t, 15 + rng.nextInt(16), 20); break;
                case BOSS: out[i] = new Mission(t, 1 + rng.nextInt(2), 25); break;
                case JOB_XP: out[i] = new Mission(t, 2000 * (1 + rng.nextInt(5)), 15); break;
                default: out[i] = new Mission(t, 3 + rng.nextInt(3), 20); break;
            }
        }
        return out;
    }

    private static long progress(Player player, int i) {
        long v = data().buffUntil(player.getId(), "mis" + i);
        return v / 1_000_000L == today() ? v % 1_000_000L : 0;
    }

    private static void setProgress(Player player, int i, long value) {
        data().setBuff(player.getId(), "mis" + i, today() * 1_000_000L + Math.min(999_999L, value));
    }

    private static void advance(Player player, Type type, long amount) {
        if (!MISSIONS || player == null || amount <= 0) return;
        Mission[] ms = missionsFor(player);
        for (int i = 0; i < ms.length; i++) {
            if (ms[i].type != type) continue;
            long before = progress(player, i);
            if (before >= ms[i].goal) continue;
            long after = Math.min(ms[i].goal, before + amount);
            setProgress(player, i, after);
            if (after >= ms[i].goal) {
                data().addPoints(player.getId(), ms[i].reward);
                boolean all = true;
                for (int j = 0; j < ms.length; j++) if (progress(player, j) < ms[j].goal) all = false;
                if (all) data().addPoints(player.getId(), ALL_BONUS);
                AtrionAchievements.increment(player, "c_mis");
                final String label = ms[i].label();
                final int reward = ms[i].reward;
                final boolean fall = all;
                org.starloco.locos.util.TimerWaiter.addNext(() -> player.sendMessage("<b>Mission accomplie</b> : " + label + " (+" + reward + " pts)"
                        + (fall ? ". <b>Toutes les missions du jour sont terminees : +" + ALL_BONUS + " pts !</b>" : ".")), 2000);
            }
        }
    }

    public static boolean commandMissions(Player player) {
        Mission[] ms = missionsFor(player);
        StringBuilder sb = new StringBuilder("<b>Missions du jour</b> (+" + ALL_BONUS + " pts si tu termines les 3, renouvelees a minuit) :");
        for (int i = 0; i < ms.length; i++) {
            long p = progress(player, i);
            sb.append("<br/>").append(p >= ms[i].goal ? "[x] " : "[ ] ").append(ms[i].label())
                    .append(" (").append(p).append("/").append(ms[i].goal).append(") : ").append(ms[i].reward).append(" pts");
        }
        player.sendMessage(sb.toString());
        return true;
    }

    /** Fin de combat PvM gagne. */
    public static void onPvmWin(Player player, Collection<MonsterGrade> defeated, int playersInTeam) {
        if (player == null || defeated == null) return;
        try {
            int kills = 0, high = 0, bosses = 0;
            for (MonsterGrade g : defeated) {
                if (g == null) continue;
                kills++;
                if (g.getLevel() >= player.getLevel() - 10) high++;
                if (g.getTemplate() != null && g.getTemplate().isBoss()) bosses++;
            }
            advance(player, Type.WIN, 1);
            advance(player, Type.KILL, kills);
            advance(player, Type.KILL_HIGH, high);
            advance(player, Type.BOSS, bosses);
            if (playersInTeam >= 2) advance(player, Type.GROUP, 1);
            weeklyAdd(player, 1);
            if (SERVER_FIRSTS) serverFirsts(player, defeated);
        } catch (Exception e) {
            World.world.logger.error("Atrion: missions", e);
        }
    }

    /** Appele quand un personnage gagne de l'XP de metier. */
    public static void onJobXp(Player player, long xp) {
        try {
            advance(player, Type.JOB_XP, xp);
        } catch (Exception e) {
            World.world.logger.error("Atrion: missions (metier)", e);
        }
    }

    // ================================================================== Premieres victoires sur les boss

    private static void serverFirsts(Player player, Collection<MonsterGrade> defeated) {
        Set<Integer> done = new HashSet<>();
        for (MonsterGrade g : defeated) {
            if (g == null || g.getTemplate() == null || !g.getTemplate().isBoss()) continue;
            int id = g.getTemplate().getId();
            if (!done.add(id) || data().hasFlag(0, "first" + id)) continue;
            data().setFlag(0, "first" + id);
            data().addPoints(player.getId(), 30);
            String name = AtrionExtras.monsterName(id);
            SocketManager.GAME_SEND_MESSAGE_TO_ALL("PREMIERE D'ATRION : " + player.getName() + " est le premier du serveur a vaincre " + name + " ! (+30 pts)", "FF9F1C");
        }
    }

    // ================================================================== Classement de la semaine

    private static final int[] WEEKLY_REWARDS = {100, 60, 40};

    private static long weekId(LocalDate d) {
        return d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private static long currentWeek() {
        return weekId(LocalDate.now(PARIS));
    }

    private static void weeklyAdd(Player player, int amount) {
        if (!WEEKLY) return;
        long w = currentWeek();
        long v = data().buffUntil(player.getId(), "w_win");
        long count = v / 100_000L == w ? v % 100_000L : 0;
        data().setBuff(player.getId(), "w_win", w * 100_000L + Math.min(99_999L, count + amount));
    }

    private static List<Map.Entry<Integer, Long>> weeklyTop(long week, int limit) {
        List<Map.Entry<Integer, Long>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : data().buffsByKey("w_win").entrySet())
            if (e.getKey() > 0 && e.getValue() / 100_000L == week) rows.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue() % 100_000L));
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    public static boolean commandWeekly(Player player) {
        List<Map.Entry<Integer, Long>> top = weeklyTop(currentWeek(), 10);
        Map<Integer, String> names = data().names(top.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList()));
        StringBuilder sb = new StringBuilder("<b>Classement de la semaine</b> (combats gagnes, recompenses lundi : 100 / 60 / 40 pts) :");
        int r = 1;
        for (Map.Entry<Integer, Long> e : top)
            sb.append("<br/>").append(r++).append(". ").append(names.getOrDefault(e.getKey(), "#" + e.getKey())).append(" - ").append(e.getValue());
        if (top.isEmpty()) sb.append("<br/>Personne pour l'instant cette semaine.");
        player.sendMessage(sb.toString());
        return true;
    }

    /** Appele chaque minute : distribue les recompenses de la semaine precedente le lundi. */
    static void tick() {
        if (!WEEKLY) return;
        LocalDate now = LocalDate.now(PARIS);
        if (now.getDayOfWeek() != DayOfWeek.MONDAY) return;
        long last = weekId(now.minusDays(1));
        if (data().buffUntil(0, "week_paid") >= last) return;
        data().setBuff(0, "week_paid", last);
        List<Map.Entry<Integer, Long>> top = weeklyTop(last, WEEKLY_REWARDS.length);
        if (top.isEmpty()) return;
        Map<Integer, String> names = data().names(top.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList()));
        StringBuilder sb = new StringBuilder("Classement de la semaine termine ! ");
        for (int i = 0; i < top.size(); i++) {
            int pid = top.get(i).getKey();
            data().addPoints(pid, WEEKLY_REWARDS[i]);
            sb.append(i + 1).append(". ").append(names.getOrDefault(pid, "#" + pid)).append(" (+").append(WEEKLY_REWARDS[i]).append(" pts) ");
        }
        SocketManager.GAME_SEND_MESSAGE_TO_ALL(sb.toString().trim(), "FFD84C");
    }
}
