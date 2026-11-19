package org.starloco.locos.atrion;

import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.game.world.World;

/**
 * Atrion : succes (objectifs permanents) avec recompenses en points de boutique.
 * Verifies a la connexion, apres chaque combat gagne et apres les evenements.
 */
public final class AtrionAchievements {

    public static final boolean ENABLED = true;

    private enum Kind { LEVEL, PRESTIGE, BESTIARY, STREAK, COUNTER }

    private static final class Ach {
        final String code, name, desc, counter;
        final Kind kind;
        final long goal;
        final int reward;

        Ach(String code, String name, String desc, Kind kind, String counter, long goal, int reward) {
            this.code = code; this.name = name; this.desc = desc; this.kind = kind; this.counter = counter; this.goal = goal; this.reward = reward;
        }
    }

    private static final Ach[] LIST = {
            new Ach("lvl20", "Premiers pas", "Atteindre le niveau 20", Kind.LEVEL, null, 20, 10),
            new Ach("lvl50", "Aventurier", "Atteindre le niveau 50", Kind.LEVEL, null, 50, 20),
            new Ach("lvl100", "Veteran", "Atteindre le niveau 100", Kind.LEVEL, null, 100, 40),
            new Ach("lvl150", "Heros des Douze", "Atteindre le niveau 150", Kind.LEVEL, null, 150, 60),
            new Ach("lvl200", "Legende", "Atteindre le niveau 200", Kind.LEVEL, null, 200, 100),
            new Ach("pre1", "Renaissance", "Atteindre le prestige 1", Kind.PRESTIGE, null, 1, 50),
            new Ach("pre5", "Phenix", "Atteindre le prestige 5", Kind.PRESTIGE, null, 5, 150),
            new Ach("pre10", "Cendres eternelles", "Atteindre le prestige 10", Kind.PRESTIGE, null, 10, 300),
            new Ach("best25", "Curieux", "Vaincre 25 especes differentes", Kind.BESTIARY, null, 25, 10),
            new Ach("best100", "Naturaliste", "Vaincre 100 especes differentes", Kind.BESTIARY, null, 100, 40),
            new Ach("best250", "Encyclopediste", "Vaincre 250 especes differentes", Kind.BESTIARY, null, 250, 100),
            new Ach("best500", "Bestiaire vivant", "Vaincre 500 especes differentes", Kind.BESTIARY, null, 500, 250),
            new Ach("day7", "Fidele", "Se connecter 7 jours d'affilee", Kind.STREAK, null, 7, 30),
            new Ach("day30", "Inseparable", "Se connecter 30 jours d'affilee", Kind.STREAK, null, 30, 150),
            new Ach("hunt10", "Pisteur", "Vaincre 10 cibles de la chasse du jour", Kind.COUNTER, "c_hunt", 10, 15),
            new Ach("hunt100", "Grand veneur", "Vaincre 100 cibles de la chasse du jour", Kind.COUNTER, "c_hunt", 100, 80),
            new Ach("inv1", "Sentinelle", "Repousser une invasion", Kind.COUNTER, "c_inv", 1, 15),
            new Ach("inv10", "Rempart d'Atrion", "Repousser 10 invasions", Kind.COUNTER, "c_inv", 10, 80),
            new Ach("quiz1", "Erudit", "Gagner un quiz", Kind.COUNTER, "c_quiz", 1, 10),
            new Ach("quiz25", "Puits de science", "Gagner 25 quiz", Kind.COUNTER, "c_quiz", 25, 80),
            new Ach("lotto1", "Main chanceuse", "Gagner la tombola", Kind.COUNTER, "c_lotto", 1, 20),
            new Ach("fights100", "Combattant", "Gagner 100 combats", Kind.COUNTER, "c_win", 100, 20),
            new Ach("fights1000", "Machine de guerre", "Gagner 1000 combats", Kind.COUNTER, "c_win", 1000, 100),
    };

    private AtrionAchievements() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    public static long counter(Player player, String key) {
        return data().buffUntil(player.getId(), key);
    }

    public static void increment(Player player, String key) {
        data().setBuff(player.getId(), key, counter(player, key) + 1);
    }

    private static long progress(Player player, Ach a) {
        switch (a.kind) {
            case LEVEL: return player.getLevel();
            case PRESTIGE: return data().get(player.getId());
            case BESTIARY: return data().countFlags(player.getId(), "bm");
            case STREAK: return data().buffUntil(player.getId(), "daily_streak");
            default: return counter(player, a.counter);
        }
    }

    /** Debloque les succes atteints. */
    public static void check(Player player) {
        if (!ENABLED || player == null) return;
        try {
            for (Ach a : LIST) {
                String flag = "ach_" + a.code;
                if (data().hasFlag(player.getId(), flag)) continue;
                if (progress(player, a) < a.goal) continue;
                data().setFlag(player.getId(), flag);
                data().addPoints(player.getId(), a.reward);
                final Ach fa = a;
                org.starloco.locos.util.TimerWaiter.addNext(() -> {
                    player.sendMessage("<b>Succes debloque : " + fa.name + "</b> (" + fa.desc + ") : +" + fa.reward + " points de boutique.");
                    SocketManager.GAME_SEND_MESSAGE_TO_ALL(player.getName() + " a debloque le succes \"" + fa.name + "\" !", "C8A2FF");
                }, 3500);
            }
        } catch (Exception e) {
            World.world.logger.error("Atrion: succes", e);
        }
    }

    public static int unlockedCount(Player player) {
        int n = 0;
        for (Ach a : LIST) if (data().hasFlag(player.getId(), "ach_" + a.code)) n++;
        return n;
    }

    public static int total() {
        return LIST.length;
    }

    public static boolean command(Player player) {
        StringBuilder sb = new StringBuilder("<b>Succes Atrion</b> (" + unlockedCount(player) + "/" + LIST.length + ") :");
        for (Ach a : LIST) {
            boolean done = data().hasFlag(player.getId(), "ach_" + a.code);
            sb.append("<br/>").append(done ? "[x] " : "[ ] ").append("<b>").append(a.name).append("</b> - ").append(a.desc);
            if (!done) sb.append(" (").append(Math.min(progress(player, a), a.goal)).append("/").append(a.goal).append(")");
            sb.append(" : ").append(a.reward).append(" pts");
        }
        player.sendMessage(sb.toString());
        return true;
    }
}
