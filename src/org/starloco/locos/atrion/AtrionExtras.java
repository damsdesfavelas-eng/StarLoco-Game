package org.starloco.locos.atrion;

import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.entity.monster.Monster;
import org.starloco.locos.entity.monster.MonsterGrade;
import org.starloco.locos.game.world.World;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Atrion : contenu "quotidien" (nuit du 19/11, a valider).
 * - Recompense de connexion quotidienne avec serie de jours.
 * - Chasse du jour : 3 monstres cibles par jour (bas, moyen, haut niveau), points bonus.
 * - Heure de pointe : XP x1.5 chaque soir de 21h a 22h (heure de Paris), butin +20 % le week-end.
 * - Annonces automatiques (conseils, debut/fin de l'heure de pointe).
 * Tout est desactivable via les constantes ci-dessous.
 */
public final class AtrionExtras {

    public static final boolean DAILY_REWARD = true;
    public static final boolean HUNT = true;
    public static final boolean HAPPY_HOUR = true;
    public static final boolean WEEKEND_DROP = true;
    public static final boolean TIPS = true;

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final int HAPPY_START = 21, HAPPY_END = 22; // [21h, 22h[
    private static final double HAPPY_XP = 1.5;
    private static final double WEEKEND_DROP_FACTOR = 1.2;

    private static final int HUNT_POINTS = 3;      // par groupe contenant la cible
    private static final int HUNT_MAX_PER_DAY = 10; // nombre de combats recompenses par jour
    private static final int HUNT_BONUS_ALL3 = 15;  // bonus si les 3 cibles ont ete chassees le meme jour

    private static final int SAFE_MAP = 7411, SAFE_CELL = 325;

    private AtrionExtras() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now(PARIS);
    }

    private static long today() {
        return LocalDate.now(PARIS).toEpochDay();
    }

    // ------------------------------------------------------------------ Bonus de l'heure

    public static boolean isHappyHour() {
        int h = now().getHour();
        return HAPPY_HOUR && h >= HAPPY_START && h < HAPPY_END;
    }

    public static boolean isWeekend() {
        DayOfWeek d = now().getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public static double xpFactor() {
        return isHappyHour() ? HAPPY_XP : 1.0;
    }

    public static double dropFactor() {
        return WEEKEND_DROP && isWeekend() ? WEEKEND_DROP_FACTOR : 1.0;
    }

    public static String bonusLine() {
        List<String> parts = new ArrayList<>();
        if (isHappyHour()) parts.add("<b>Heure de pointe</b> : XP x" + HAPPY_XP + " jusqu'a " + HAPPY_END + "h");
        if (WEEKEND_DROP && isWeekend()) parts.add("<b>Week-end</b> : butin +" + Math.round((WEEKEND_DROP_FACTOR - 1) * 100) + " %");
        if (parts.isEmpty()) return HAPPY_HOUR ? "Prochaine heure de pointe (XP x" + HAPPY_XP + ") : " + HAPPY_START + "h-" + HAPPY_END + "h." : "";
        return String.join(", ", parts) + ".";
    }

    // ------------------------------------------------------------------ Connexion

    public static void onJoin(Player player) {
        startScheduler();
        try {
            if (DAILY_REWARD) dailyReward(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: recompense quotidienne", e);
        }
        try {
            AtrionEvents.onJoin(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: evenements a la connexion", e);
        }
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            try {
                if (!player.isOnline()) return;
                String bonus = bonusLine();
                if (!bonus.isEmpty()) player.sendMessage(bonus);
                if (HUNT) player.sendMessage("Chasse du jour : " + huntSummary(player) + " Details : <b>.chasse</b>");
            } catch (Exception e) {
                World.world.logger.error("Atrion: messages de connexion", e);
            }
        }, 4000);
    }

    /** Si la carte du personnage n'existe pas dans le client (ecran noir), on le replace au zaap d'Astrub. */
    public static int[] safeSpawn(Player player) {
        try {
            GameMap map = player.getCurMap();
            if (map == null || player.getFight() != null) return null;
            if (AutoPilot.isClientMap(map.getId())) return null;
            GameMap safe = World.world.getMap(SAFE_MAP);
            if (safe == null || safe.getCase(SAFE_CELL) == null) return null;
            World.world.logger.warn("Atrion: " + player.getName() + " etait sur la carte " + map.getId() + " absente du client, replace au zaap d'Astrub");
            org.starloco.locos.util.TimerWaiter.addNext(() -> player.sendMessage("Ta carte precedente n'existe pas dans le jeu : tu as ete ramene au zaap d'Astrub."), 5000);
            return new int[]{SAFE_MAP, SAFE_CELL};
        } catch (Exception e) {
            World.world.logger.error("Atrion: retour securise", e);
            return null;
        }
    }

    private static void dailyReward(Player player) {
        long day = today();
        long last = data().buffUntil(player.getId(), "daily_day");
        if (last == day) return;
        long streak = last == day - 1 ? data().buffUntil(player.getId(), "daily_streak") + 1 : 1;
        int reward = 5 + (int) Math.min(streak, 7) * 2; // 7 .. 19 points
        data().setBuff(player.getId(), "daily_day", day);
        data().setBuff(player.getId(), "daily_streak", streak);
        data().addPoints(player.getId(), reward);
        org.starloco.locos.util.TimerWaiter.addNext(() -> player.sendMessage("<b>Recompense quotidienne</b> : +" + reward
                + " points de boutique (serie de " + streak + " jour" + (streak > 1 ? "s" : "") + ", max au 7e jour). Total : "
                + data().points(player.getId()) + "."), 3000);
    }

    // ------------------------------------------------------------------ Chasse du jour

    private static final int[][] BANDS = {{1, 40}, {41, 100}, {101, 200}};
    private static final String[] BAND_NAMES = {"debutant", "confirme", "expert"};

    private static volatile long huntDay = -1;
    private static volatile int[] huntTargets; // id de monstre par tranche
    private static volatile int[][] huntPlaces; // {x, y} d'une carte ou il apparait

    private static synchronized void ensureHunt() {
        long day = today();
        if (day == huntDay && huntTargets != null) return;
        // monstre -> cartes ou il apparait naturellement
        Map<Integer, List<GameMap>> spawns = new HashMap<>();
        for (GameMap map : World.world.getMaps()) {
            if (map == null || DungeonTiers.isDungeonMap(map.getId()) || !AutoPilot.isClientMap(map.getId())) continue;
            List<MonsterGrade> poss = map.getMobPossibles();
            if (poss == null) continue;
            for (MonsterGrade g : poss) {
                if (g == null || g.getTemplate() == null) continue;
                Monster m = g.getTemplate();
                if (m.isBoss() || m.isArchMonster()) continue;
                spawns.computeIfAbsent(m.getId(), k -> new ArrayList<>()).add(map);
            }
        }
        Map<Integer, String> names = names();
        int[] targets = new int[BANDS.length];
        int[][] places = new int[BANDS.length][];
        for (int b = 0; b < BANDS.length; b++) {
            List<Integer> pool = new ArrayList<>();
            for (Map.Entry<Integer, List<GameMap>> e : spawns.entrySet()) {
                Monster m = World.world.getMonstre(e.getKey());
                if (m == null || m.getGrades().isEmpty() || e.getValue().size() < 3) continue;
                if (!names.containsKey(m.getId())) continue;
                int lvl = m.getGrades().values().stream().mapToInt(MonsterGrade::getLevel).min().orElse(0);
                if (lvl >= BANDS[b][0] && lvl <= BANDS[b][1]) pool.add(m.getId());
            }
            Collections.sort(pool);
            if (pool.isEmpty()) { targets[b] = -1; continue; }
            Random rng = new Random(day * 7919L + b * 104729L);
            targets[b] = pool.get(rng.nextInt(pool.size()));
            List<GameMap> maps = new ArrayList<>(spawns.get(targets[b]));
            maps.sort(Comparator.comparingInt(GameMap::getId));
            GameMap where = maps.get(rng.nextInt(maps.size()));
            places[b] = new int[]{where.getX(), where.getY()};
        }
        huntTargets = targets;
        huntPlaces = places;
        huntDay = day;
    }

    private static Map<Integer, String> names() {
        try {
            AtrionGameData gd = DatabaseManager.get(AtrionGameData.class);
            return gd == null ? Collections.emptyMap() : gd.monsterNames();
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    static String monsterName(int id) {
        String n = names().get(id);
        return n == null ? "monstre #" + id : n;
    }

    private static int huntCount(Player player) {
        long v = data().buffUntil(player.getId(), "hunt");
        return v / 1000 == today() ? (int) (v % 100) : 0;
    }

    private static int huntMask(Player player) {
        long v = data().buffUntil(player.getId(), "hunt");
        return v / 1000 == today() ? (int) ((v / 100) % 10) : 0;
    }

    private static String huntSummary(Player player) {
        ensureHunt();
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b < BANDS.length; b++) {
            if (huntTargets[b] < 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append("<b>").append(monsterName(huntTargets[b])).append("</b>");
        }
        return sb + " (" + huntCount(player) + "/" + HUNT_MAX_PER_DAY + ").";
    }

    public static boolean commandHunt(Player player) {
        ensureHunt();
        StringBuilder sb = new StringBuilder("<b>Chasse du jour</b> (+" + HUNT_POINTS + " pts par combat gagne contre un groupe contenant une cible, "
                + HUNT_MAX_PER_DAY + " combats max, +" + HUNT_BONUS_ALL3 + " pts si tu chasses les 3) :");
        int mask = huntMask(player);
        for (int b = 0; b < BANDS.length; b++) {
            if (huntTargets[b] < 0) continue;
            int[] p = huntPlaces[b];
            sb.append("<br/>- ").append(BAND_NAMES[b]).append(" : <b>").append(monsterName(huntTargets[b])).append("</b>")
                    .append(p != null ? " (par exemple en [" + p[0] + "," + p[1] + "])" : "")
                    .append((mask & (1 << b)) != 0 ? " - chasse !" : "");
        }
        sb.append("<br/>Progression : ").append(huntCount(player)).append("/").append(HUNT_MAX_PER_DAY)
                .append(". Double-clic sur la carte du monde pour t'y rendre.");
        player.sendMessage(sb.toString());
        return true;
    }

    // ------------------------------------------------------------------ Bestiaire a completer

    public static final boolean BESTIARY = true;
    private static final int BESTIARY_POINTS = 2, BESTIARY_BOSS_POINTS = 10, BESTIARY_MILESTONE = 25, BESTIARY_MILESTONE_POINTS = 25;

    private static void discover(Player player, Collection<MonsterGrade> defeated) {
        Set<Integer> seen = new HashSet<>();
        int earned = 0;
        List<String> found = new ArrayList<>();
        for (MonsterGrade g : defeated) {
            if (g == null || g.getTemplate() == null) continue;
            int id = g.getTemplate().getId();
            if (!seen.add(id) || data().hasFlag(player.getId(), "bm" + id)) continue;
            data().setFlag(player.getId(), "bm" + id);
            earned += g.getTemplate().isBoss() ? BESTIARY_BOSS_POINTS : BESTIARY_POINTS;
            found.add(monsterName(id));
        }
        if (found.isEmpty()) return;
        int total = data().countFlags(player.getId(), "bm");
        int before = total - found.size();
        int milestones = total / BESTIARY_MILESTONE - before / BESTIARY_MILESTONE;
        earned += milestones * BESTIARY_MILESTONE_POINTS;
        data().addPoints(player.getId(), earned);
        final int e = earned, t = total, ms = milestones;
        final String list = String.join(", ", found);
        org.starloco.locos.util.TimerWaiter.addNext(() -> player.sendMessage("<b>Bestiaire</b> : nouvelle" + (found.size() > 1 ? "s especes" : " espece") + " vaincue" + (found.size() > 1 ? "s" : "")
                + " : " + list + " (+" + e + " pts, " + t + " especes au total"
                + (ms > 0 ? ", palier de " + (t / BESTIARY_MILESTONE * BESTIARY_MILESTONE) + " atteint !" : "") + ")."), 2500);
    }

    public static boolean commandProfile(Player player) {
        int prestige = data().get(player.getId());
        int species = data().countFlags(player.getId(), "bm");
        long streak = data().buffUntil(player.getId(), "daily_streak");
        player.sendMessage("<b>Profil Atrion de " + player.getName() + "</b>"
                + "<br/>- Prestige : <b>" + prestige + "</b>, niveau " + player.getLevel()
                + "<br/>- Points de boutique : <b>" + data().points(player.getId()) + "</b>"
                + "<br/>- Serie de connexion : <b>" + Math.max(0, streak) + "</b> jour(s)"
                + "<br/>- Chasse du jour : " + huntCount(player) + "/" + HUNT_MAX_PER_DAY
                + "<br/>- Bestiaire : <b>" + species + "</b> especes vaincues (prochain palier : " + ((species / BESTIARY_MILESTONE + 1) * BESTIARY_MILESTONE) + ")"
                + "<br/>" + bonusLine());
        return true;
    }

    /** Appele a la fin d'un combat PvM gagne, pour chaque joueur gagnant. */
    public static void onPvmWin(Player player, Collection<MonsterGrade> defeated) {
        if (player == null || defeated == null) return;
        try {
            AtrionAchievements.increment(player, "c_win");
        } catch (Exception ex) {
            World.world.logger.error("Atrion: compteur de victoires", ex);
        }
        if (BESTIARY) {
            try {
                discover(player, defeated);
            } catch (Exception ex) {
                World.world.logger.error("Atrion: bestiaire", ex);
            }
        }
        if (HUNT) try {
            ensureHunt();
            int found = -1;
            for (MonsterGrade g : defeated) {
                if (g == null || g.getTemplate() == null) continue;
                for (int b = 0; b < BANDS.length; b++)
                    if (huntTargets[b] == g.getTemplate().getId()) found = b;
            }
            int count = found < 0 ? HUNT_MAX_PER_DAY : huntCount(player);
            if (count < HUNT_MAX_PER_DAY) {
            int oldMask = huntMask(player);
            int mask = oldMask | (1 << found);
            count++;
            data().setBuff(player.getId(), "hunt", today() * 1000 + mask * 100L + count);
            int earned = HUNT_POINTS;
            boolean all = mask == 7 && oldMask != 7;
            if (all) earned += HUNT_BONUS_ALL3;
            data().addPoints(player.getId(), earned);
            AtrionAchievements.increment(player, "c_hunt");
            final int e = earned, c = count;
            final String name = monsterName(huntTargets[found]);
            org.starloco.locos.util.TimerWaiter.addNext(() -> player.sendMessage("<b>Chasse du jour</b> : " + name + " vaincu, +" + e + " pts"
                    + (all ? " (bonus : les 3 cibles chassees !)" : "") + " (" + c + "/" + HUNT_MAX_PER_DAY + ")."), 1500);
            }
        } catch (Exception ex) {
            World.world.logger.error("Atrion: chasse du jour", ex);
        }
        AtrionAchievements.check(player);
    }

    public static boolean commandDaily(Player player) {
        long day = today();
        long last = data().buffUntil(player.getId(), "daily_day");
        long streak = data().buffUntil(player.getId(), "daily_streak");
        player.sendMessage("Recompense quotidienne : " + (last == day ? "deja recue aujourd'hui" : "disponible a la prochaine connexion")
                + ", serie actuelle : <b>" + Math.max(streak, 0) + "</b> jour(s). " + bonusLine());
        return true;
    }

    // ------------------------------------------------------------------ Annonces automatiques

    private static volatile ScheduledExecutorService scheduler;
    private static volatile boolean lastHappy = false;
    private static int tipIndex = 0;
    private static long lastTip = System.currentTimeMillis();

    private static final String[] TIPS_LIST = {
            "Double-clic sur la carte du monde ou la minicarte : ton personnage s'y rend a pied, carte par carte.",
            "Les objets ont une rarete (Rare, Epique, Legendaire, Mythique) : .recycler et .ameliorer pour en tirer profit.",
            "Chaque combat gagne rapporte des points de boutique. Le marchand du zaap d'Astrub les echange contre potions et cosmetiques.",
            "La chasse du jour change a minuit : tape .chasse pour voir les 3 cibles.",
            "Les donjons ont des paliers : .palier pour plus de difficulte et de meilleures recompenses.",
            "L'encyclopedie et .drops montrent tous les butins, y compris les panoplies ajoutees sur leurs monstres.",
            "Le passeur de l'ile des Wabbits t'emmene directement aux donjons Wabbit.",
            "Tape .aide pour la liste de toutes les commandes Atrion.",
            "Chaque soir de 21h a 22h : heure de pointe, XP x1.5 pour tout le monde.",
    };

    private static synchronized void startScheduler() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "atrion-extras");
            t.setDaemon(true);
            return t;
        });
        lastHappy = isHappyHour();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Throwable t) {
                World.world.logger.error("Atrion: annonces", t);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private static void tick() {
        try { publishStatus(); } catch (Throwable t) { World.world.logger.error("Atrion: etat public", t); }
        if (World.world.getOnlinePlayers().isEmpty()) return;
        AtrionEvents.tick();
        try { AtrionMissions.tick(); } catch (Throwable t) { World.world.logger.error("Atrion: semaine", t); }
        boolean happy = isHappyHour();
        if (happy != lastHappy) {
            lastHappy = happy;
            SocketManager.GAME_SEND_MESSAGE_TO_ALL(happy
                    ? "Atrion : l'heure de pointe commence ! XP x" + HAPPY_XP + " jusqu'a " + HAPPY_END + "h."
                    : "Atrion : fin de l'heure de pointe. Rendez-vous demain a " + HAPPY_START + "h !", "FFB43C");
        }
        if (TIPS && System.currentTimeMillis() - lastTip > 45 * 60_000L) {
            lastTip = System.currentTimeMillis();
            SocketManager.GAME_SEND_MESSAGE_TO_ALL("Astuce : " + TIPS_LIST[tipIndex++ % TIPS_LIST.length], "9BD3FF");
        }
    }

    /** Publie l'etat du serveur dans la base (page atrion-status.php -> launcher). */
    private static void publishStatus() {
        PrestigeData d = data();
        d.setStatus("online", String.valueOf(World.world.getOnlinePlayers().size()));
        d.setStatus("bonus", bonusLine().replaceAll("<[^>]+>", ""));
        d.setStatus("events", AtrionEvents.publicStatus());
        StringBuilder hunt = new StringBuilder();
        try {
            ensureHunt();
            for (int b = 0; b < BANDS.length; b++) {
                if (huntTargets[b] < 0) continue;
                if (hunt.length() > 0) hunt.append(", ");
                hunt.append(monsterName(huntTargets[b]));
            }
        } catch (Exception ignored) { }
        d.setStatus("hunt", hunt.toString());
        d.setStatus("updated", String.valueOf(System.currentTimeMillis()));
    }
}
