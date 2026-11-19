package org.starloco.locos.atrion;

import org.starloco.locos.area.map.GameCase;
import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.common.PathFinding;
import org.starloco.locos.game.GameClient;
import org.starloco.locos.game.world.World;
import org.starloco.locos.util.TimerWaiter;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atrion : deplacement automatique carte par carte (double-clic sur la carte du monde ou la minicarte).
 * Le personnage marche reellement jusqu'a la sortie de chaque carte : le serveur envoie le chemin comme si
 * le joueur avait clique, puis le changement de carte se fait par les cellules de sortie normales.
 * Le trajet s'arrete si le joueur bouge lui-meme, entre en combat ou si un chemin est bloque.
 */
public final class AutoPilot {

    private static final String LINKS_FILE = "scripts/data/atrion/map_links.txt";
    private static final int MAX_SEARCH = 6000;
    private static final long MS_PER_CELL = 260;

    /** carte -> liste de {cellule de sortie, carte cible} */
    private static volatile Map<Integer, List<int[]>> links;
    private static final Map<Integer, Route> active = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> injecting = ThreadLocal.withInitial(() -> false);

    private static final class Route {
        final int playerId;
        final List<int[]> steps; // {carte de depart, cellule de sortie, carte d'arrivee}
        int index = 0, retries = 0, stuck = 0;
        long waitUntil = 0;
        final int targetX, targetY;
        Route(int playerId, List<int[]> steps, int x, int y) { this.playerId = playerId; this.steps = steps; this.targetX = x; this.targetY = y; }
    }

    private AutoPilot() {
    }

    private static Map<Integer, List<int[]>> links() {
        if (links == null) {
            synchronized (AutoPilot.class) {
                if (links == null) {
                    Map<Integer, List<int[]>> m = new HashMap<>();
                    try (BufferedReader r = Files.newBufferedReader(Paths.get(LINKS_FILE), StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            String[] p = line.trim().split("\\s+");
                            if (p.length < 3) continue;
                            m.computeIfAbsent(Integer.parseInt(p[0]), k -> new ArrayList<>())
                                    .add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                        }
                    } catch (Exception e) {
                        World.world.logger.error("Atrion: liaisons de cartes illisibles (" + LINKS_FILE + ")", e);
                    }
                    links = m;
                }
            }
        }
        return links;
    }

    /** true pendant que le serveur envoie lui-meme un deplacement (pour ne pas annuler le trajet). */
    public static boolean isInjecting() {
        return injecting.get();
    }

    /** Appele quand le joueur demande lui-meme un deplacement : on arrete le trajet automatique. */
    public static void onManualMove(Player player) {
        if (player != null && !isInjecting() && active.remove(player.getId()) != null)
            player.sendMessage("Deplacement automatique arrete.");
    }

    public static void start(Player player, int x, int y) {
        if (player.getFight() != null) { player.sendMessage("Impossible pendant un combat."); return; }
        if (player.getExchangeAction() != null) { player.sendMessage("Termine d'abord ton echange ou ton dialogue."); return; }
        GameMap current = player.getCurMap();
        if (current == null) return;
        if (current.getX() == x && current.getY() == y) { active.remove(player.getId()); return; }

        int superArea = Optional.ofNullable(current.getArea()).map(org.starloco.locos.area.Area::getSuperArea).orElse(-1);
        Set<Integer> targets = new HashSet<>(World.world.getMapIdByPosInSuperArea(x, y, superArea));
        targets.removeIf(DungeonTiers::isDungeonMap);
        if (targets.isEmpty()) { player.sendMessage("Destination [" + x + "," + y + "] inconnue."); return; }

        List<int[]> steps = findRoute(current.getId(), targets);
        if (steps == null) { player.sendMessage("Aucun chemin a pied trouve vers [" + x + "," + y + "]."); return; }

        Route route = new Route(player.getId(), steps, x, y);
        active.put(player.getId(), route);
        player.sendMessage("Deplacement automatique vers <b>[" + x + "," + y + "]</b> : " + steps.size() + " carte" + (steps.size() > 1 ? "s" : "") + ". Clique ailleurs pour arreter.");
        schedule(route, 100);
    }

    /** Recherche en largeur sur le graphe des sorties de cartes. */
    private static List<int[]> findRoute(int from, Set<Integer> targets) {
        Map<Integer, List<int[]>> graph = links();
        Map<Integer, int[]> previous = new HashMap<>(); // carte -> {carte precedente, cellule de sortie}
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        previous.put(from, null);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < MAX_SEARCH) {
            int map = queue.poll();
            if (targets.contains(map)) {
                LinkedList<int[]> steps = new LinkedList<>();
                int cur = map;
                while (previous.get(cur) != null) {
                    int[] prev = previous.get(cur);
                    steps.addFirst(new int[]{prev[0], prev[1], cur});
                    cur = prev[0];
                }
                return steps;
            }
            for (int[] link : graph.getOrDefault(map, Collections.emptyList())) {
                int next = link[1];
                if (previous.containsKey(next) || DungeonTiers.isDungeonMap(next) || World.world.getMap(next) == null) continue;
                previous.put(next, new int[]{map, link[0]});
                queue.add(next);
            }
        }
        return null;
    }

    private static final char[] DIRS = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'};

    /** Chemin a pied sur une carte (8 directions, memes regles que la validation du serveur). Format client : dir + cellule. */
    private static String findCellPath(GameMap map, int start, int target, int[] stepCount) {
        if (start == target) return null;
        Map<Integer, Integer> prev = new HashMap<>();
        Map<Integer, Character> via = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        prev.put(start, -1);
        while (!queue.isEmpty()) {
            int c = queue.poll();
            if (c == target) break;
            if (c != start && map.data.cellHasMoveEndActions(c)) continue; // le serveur s'arreterait ici
            for (char d : DIRS) {
                int n = PathFinding.GetCaseIDFromDirection(c, d, map, false);
                if (n < 0 || prev.containsKey(n)) continue;
                GameCase gc = map.getCase(n);
                if (gc == null || !gc.isWalkable(true, false, target)) continue;
                prev.put(n, c);
                via.put(n, d);
                queue.add(n);
            }
        }
        if (!prev.containsKey(target)) return null;
        LinkedList<Integer> cells = new LinkedList<>();
        for (int c = target; c != start; c = prev.get(c)) cells.addFirst(c);
        stepCount[0] = cells.size();
        StringBuilder sb = new StringBuilder();
        char lastDir = 0;
        int lastCell = -1;
        for (int c : cells) {
            char d = via.get(c);
            if (d != lastDir && lastDir != 0)
                sb.append(lastDir).append(World.world.getCryptManager().cellID_To_Code(lastCell));
            lastDir = d;
            lastCell = c;
        }
        sb.append(lastDir).append(World.world.getCryptManager().cellID_To_Code(lastCell));
        return sb.toString();
    }

    private static void schedule(Route route, long delay) {
        TimerWaiter.addNext(() -> {
            try {
                tick(route);
            } catch (Exception e) {
                World.world.logger.error("Atrion: deplacement automatique", e);
                active.remove(route.playerId, route);
            }
        }, delay);
    }

    private static void stop(Route route, Player player, String message) {
        active.remove(route.playerId, route);
        if (player != null && message != null) player.sendMessage(message);
    }

    private static void tick(Route route) throws InterruptedException {
        if (active.get(route.playerId) != route) return; // annule ou remplace
        Player player = World.world.getPlayer(route.playerId);
        if (player == null || !player.isOnline() || player.getGameClient() == null) { active.remove(route.playerId, route); return; }
        if (player.getFight() != null) { stop(route, null, null); return; }
        GameMap map = player.getCurMap();
        if (map == null) { schedule(route, 500); return; }

        int[] step = route.steps.get(route.index);
        if (map.getId() == step[2]) { // carte suivante atteinte
            route.index++;
            route.retries = 0;
            route.stuck = 0;
            route.waitUntil = 0;
            if (route.index >= route.steps.size()) { stop(route, player, "Tu es arrive en <b>[" + route.targetX + "," + route.targetY + "]</b>."); return; }
            schedule(route, 1300); // laisse le temps au client de charger la carte
            return;
        }
        if (map.getId() != step[0]) { stop(route, player, "Deplacement automatique interrompu."); return; }
        if (player.getExchangeAction() != null) { schedule(route, 1000); return; }

        long now = System.currentTimeMillis();
        if (now < route.waitUntil) { schedule(route, Math.min(500, route.waitUntil - now + 50)); return; }

        GameCase cell = player.getCurCell();
        if (cell == null) { schedule(route, 500); return; }
        if (cell.getId() == step[1]) { // sur la sortie, le changement de carte arrive
            if (++route.stuck > 8) { stop(route, player, "Deplacement automatique bloque sur cette carte."); return; }
            schedule(route, 500);
            return;
        }
        if (route.retries++ >= 3) { stop(route, player, "Chemin bloque, deplacement automatique arrete."); return; }

        int[] steps = new int[1];
        String path = findCellPath(map, cell.getId(), step[1], steps);
        if (path == null || path.isEmpty()) { stop(route, player, "Chemin introuvable, deplacement automatique arrete."); return; }

        GameClient client = player.getGameClient();
        injecting.set(true);
        try {
            client.parsePacket("GA001" + path);
        } finally {
            injecting.set(false);
        }
        route.waitUntil = now + steps[0] * MS_PER_CELL + 700;
        schedule(route, 600);
    }
}
