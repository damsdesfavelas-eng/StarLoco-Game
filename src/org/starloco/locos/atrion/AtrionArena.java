package org.starloco.locos.atrion;

import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.fight.Fight;
import org.starloco.locos.game.world.World;
import org.starloco.locos.kernel.Constant;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atrion : arene 1 contre 1 (facon Kolizeum).
 * .arene : s'inscrire / se desinscrire. Les joueurs de niveau proche sont opposes en defi ;
 * le second est amene sur la carte du premier puis ramene a sa position apres le combat.
 * Classement par cote (Elo), points de boutique pour les vainqueurs (5 victoires recompensees par jour).
 */
public final class AtrionArena {

    public static final boolean ENABLED = true;
    private static final int START_RATING = 1000, K = 32;
    private static final int WIN_POINTS = 8, LOSS_POINTS = 2, REWARDED_PER_DAY = 5;
    private static final int LEVEL_GAP = 25;
    private static final long WIDEN_AFTER_MS = 3 * 60_000L; // apres 3 min d'attente, n'importe quel adversaire

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Map<Integer, Long> queue = new ConcurrentHashMap<>(); // joueur -> heure d'inscription

    private static final class Match {
        final int a, b;
        final int backMap, backCell; // position d'origine du joueur b
        volatile int fightId = -1;
        volatile GameMap map;
        volatile boolean done = false;

        Match(int a, int b, int backMap, int backCell) { this.a = a; this.b = b; this.backMap = backMap; this.backCell = backCell; }
    }

    private static final Map<Integer, Match> matches = new ConcurrentHashMap<>(); // joueur -> match

    private AtrionArena() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    private static long today() {
        return LocalDate.now(PARIS).toEpochDay();
    }

    public static int rating(int playerId) {
        long v = data().buffUntil(playerId, "elo");
        return v <= 0 ? START_RATING : (int) v;
    }

    private static boolean available(Player p) {
        return p != null && p.isOnline() && p.getFight() == null && p.getExchangeAction() == null && !p.isInPrison()
                && p.getCurMap() != null && !DungeonTiers.isDungeonMap(p.getCurMap().getId());
    }

    public static boolean command(Player player, String args) {
        if (!ENABLED) return false;
        String a = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("class") || a.startsWith("top")) return ranking(player);
        if (matches.containsKey(player.getId())) { player.sendMessage("Tu as deja un combat d'arene en cours."); return true; }
        if (queue.remove(player.getId()) != null) {
            player.sendMessage("Tu as quitte la file de l'arene.");
            return true;
        }
        if (!available(player)) { player.sendMessage("Impossible maintenant (combat, echange, prison ou donjon)."); return true; }
        queue.put(player.getId(), System.currentTimeMillis());
        player.sendMessage("<b>Arene</b> : tu es inscrit (cote " + rating(player.getId()) + ", " + queue.size() + " en attente). "
                + "Tape a nouveau <b>.arene</b> pour te desinscrire, <b>.arene classement</b> pour le classement.");
        for (Integer id : queue.keySet()) {
            Player p = World.world.getPlayer(id);
            if (p != null && p != player && p.isOnline()) p.sendMessage("<i>" + player.getName() + " (niv. " + player.getLevel() + ") attend un adversaire dans l'arene : .arene</i>");
        }
        tryMatch();
        return true;
    }

    /** Appele chaque minute et a chaque inscription. */
    static synchronized void tryMatch() {
        if (!ENABLED || queue.size() < 2) return;
        long now = System.currentTimeMillis();
        List<Integer> ids = new ArrayList<>(queue.keySet());
        ids.removeIf(id -> {
            Player p = World.world.getPlayer(id);
            if (!available(p)) { queue.remove(id); return true; }
            return false;
        });
        ids.sort(Comparator.comparingLong(id -> queue.getOrDefault(id, now)));
        for (int i = 0; i < ids.size(); i++) {
            Player p1 = World.world.getPlayer(ids.get(i));
            if (p1 == null || !queue.containsKey(p1.getId())) continue;
            boolean wide = now - queue.getOrDefault(p1.getId(), now) > WIDEN_AFTER_MS;
            for (int j = i + 1; j < ids.size(); j++) {
                Player p2 = World.world.getPlayer(ids.get(j));
                if (p2 == null || !queue.containsKey(p2.getId())) continue;
                if (p1.getAccount() != null && p2.getAccount() != null && p1.getAccount().getId() == p2.getAccount().getId()) continue;
                if (!wide && Math.abs(p1.getLevel() - p2.getLevel()) > LEVEL_GAP) continue;
                queue.remove(p1.getId());
                queue.remove(p2.getId());
                start(p1, p2);
                break;
            }
        }
    }

    private static void start(Player a, Player b) {
        GameMap map = a.getCurMap();
        Match m = new Match(a.getId(), b.getId(), b.getCurMap().getId(), b.getCurCell() != null ? b.getCurCell().getId() : 0);
        m.map = map;
        matches.put(a.getId(), m);
        matches.put(b.getId(), m);
        a.sendMessage("<b>Arene</b> : adversaire trouve, " + b.getName() + " (niv. " + b.getLevel() + ", cote " + rating(b.getId()) + "). Le combat commence dans quelques secondes.");
        b.sendMessage("<b>Arene</b> : adversaire trouve, " + a.getName() + " (niv. " + a.getLevel() + ", cote " + rating(a.getId()) + "). Tu es amene a lui pour le combat.");
        int cell = a.getCurCell() != null ? a.getCurCell().getId() : map.getRandomFreeCellId();
        if (b.getCurMap() != map) b.teleport(map.getId(), cell);
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            try {
                if (!available(a) || !available(b) || a.getCurMap() != b.getCurMap()) {
                    cancel(m, "Le combat d'arene est annule (un joueur n'est plus disponible).");
                    return;
                }
                SocketManager.GAME_SEND_MAP_START_DUEL_TO_MAP(map, a.getId(), b.getId());
                Fight fight = map.newFight(a, b, Constant.FIGHT_TYPE_CHALLENGE);
                if (fight == null) { cancel(m, "Le combat d'arene n'a pas pu commencer."); return; }
                a.setFight(fight); a.setAway(false);
                b.setFight(fight); b.setAway(false);
                m.fightId = fight.getId();
                SocketManager.GAME_SEND_MESSAGE_TO_ALL("Arene : " + a.getName() + " (" + rating(a.getId()) + ") contre " + b.getName() + " (" + rating(b.getId()) + ") !", "B9A6FF");
            } catch (Exception e) {
                World.world.logger.error("Atrion: arene", e);
                cancel(m, "Le combat d'arene n'a pas pu commencer.");
            }
        }, 4000);
    }

    private static void cancel(Match m, String msg) {
        if (m.done) return;
        m.done = true;
        matches.remove(m.a, m);
        matches.remove(m.b, m);
        Player a = World.world.getPlayer(m.a), b = World.world.getPlayer(m.b);
        if (a != null && a.isOnline()) a.sendMessage(msg);
        if (b != null && b.isOnline()) { b.sendMessage(msg); sendBack(b, m); }
    }

    private static void sendBack(Player b, Match m) {
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            try {
                if (b.isOnline() && b.getFight() == null && b.getCurMap() != null && b.getCurMap().getId() != m.backMap && World.world.getMap(m.backMap) != null)
                    b.teleport(m.backMap, m.backCell);
            } catch (Exception e) {
                World.world.logger.error("Atrion: retour d'arene", e);
            }
        }, 3000);
    }

    /** Appele en fin de combat de defi, pour chaque joueur gagnant. */
    public static void onChallengeWin(Fight fight, Player winner) {
        if (!ENABLED || fight == null || winner == null) return;
        Match m = matches.get(winner.getId());
        if (m == null || m.done || m.fightId != fight.getId() || m.map != fight.getMapOld()) return;
        m.done = true;
        matches.remove(m.a, m);
        matches.remove(m.b, m);
        int loserId = m.a == winner.getId() ? m.b : m.a;
        int rw = rating(winner.getId()), rl = rating(loserId);
        double expected = 1.0 / (1.0 + Math.pow(10, (rl - rw) / 400.0));
        int delta = Math.max(1, (int) Math.round(K * (1 - expected)));
        data().setBuff(winner.getId(), "elo", rw + delta);
        data().setBuff(loserId, "elo", Math.max(100, rl - delta));
        AtrionAchievements.increment(winner, "c_arena");
        int rewarded = rewardedToday(winner.getId());
        boolean pay = rewarded < REWARDED_PER_DAY;
        if (pay) {
            data().setBuff(winner.getId(), "arena_day", today() * 100 + rewarded + 1);
            data().addPoints(winner.getId(), WIN_POINTS);
            data().addPoints(loserId, LOSS_POINTS);
        }
        Player loser = World.world.getPlayer(loserId);
        String names = winner.getName() + " (" + (rw + delta) + ", +" + delta + ") bat " + (loser != null ? loser.getName() : "son adversaire") + " (" + Math.max(100, rl - delta) + ", -" + delta + ")";
        SocketManager.GAME_SEND_MESSAGE_TO_ALL("Arene : " + names + " !", "B9A6FF");
        final boolean fpay = pay;
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            winner.sendMessage("<b>Victoire en arene</b> : cote " + (rw + delta) + (fpay ? ", +" + WIN_POINTS + " pts." : " (plus de points aujourd'hui, la cote compte toujours)."));
            if (loser != null && loser.isOnline())
                loser.sendMessage("<b>Defaite en arene</b> : cote " + Math.max(100, rl - delta) + (fpay ? ", +" + LOSS_POINTS + " pts de participation." : "."));
            AtrionAchievements.check(winner);
        }, 2500);
        Player b = World.world.getPlayer(m.b);
        if (b != null) sendBack(b, m);
    }

    /** Fin de combat de defi sans vainqueur arene detecte (abandon, deconnexion) : on libere le match. */
    public static void onChallengeEnd(Fight fight) {
        if (fight == null) return;
        for (Match m : new HashSet<>(matches.values())) {
            if (m.fightId == fight.getId() && m.map == fight.getMapOld() && !m.done) {
                org.starloco.locos.util.TimerWaiter.addNext(() -> {
                    if (!m.done) cancel(m, "Le combat d'arene est termine sans resultat.");
                }, 6000);
            }
        }
    }

    private static int rewardedToday(int playerId) {
        long v = data().buffUntil(playerId, "arena_day");
        return v / 100 == today() ? (int) (v % 100) : 0;
    }

    private static boolean ranking(Player player) {
        List<Map.Entry<Integer, Long>> rows = new ArrayList<>(data().buffsByKey("elo").entrySet());
        rows.removeIf(e -> e.getKey() <= 0);
        rows.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        if (rows.size() > 10) rows = rows.subList(0, 10);
        Map<Integer, String> names = data().names(rows.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList()));
        StringBuilder sb = new StringBuilder("<b>Classement de l'arene</b> (ta cote : " + rating(player.getId()) + ")");
        int r = 1;
        for (Map.Entry<Integer, Long> e : rows) sb.append("<br/>").append(r++).append(". ").append(names.getOrDefault(e.getKey(), "#" + e.getKey())).append(" - ").append(e.getValue());
        if (rows.isEmpty()) sb.append("<br/>Aucun combat d'arene pour l'instant.");
        player.sendMessage(sb.toString());
        return true;
    }
}
