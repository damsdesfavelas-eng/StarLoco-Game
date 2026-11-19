package org.starloco.locos.atrion;

import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.entity.monster.Monster;
import org.starloco.locos.entity.monster.MonsterGrade;
import org.starloco.locos.entity.monster.MonsterGroup;
import org.starloco.locos.game.world.World;
import org.starloco.locos.kernel.Config;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Atrion : evenements automatiques (nuit du 19 au 20/11, a valider).
 * - Invasion : un groupe d'elite apparait regulierement sur une carte, recompense pour ceux qui le battent.
 * - Quiz : une question dans le chat, le premier qui repond (.r reponse) gagne des points.
 * - Tombola : tickets en points de boutique, tirage chaque soir a 22h.
 * - Commandes : .infos, .vie, .classement, annonce de connexion.
 */
public final class AtrionEvents {

    public static final boolean INVASION = true;
    public static final boolean QUIZ = true;
    public static final boolean LOTTERY = true;
    public static final boolean HEAL_COMMAND = true;
    public static final boolean CONNECT_ANNOUNCE = true;

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private AtrionEvents() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    private static long today() {
        return LocalDate.now(PARIS).toEpochDay();
    }

    private static void broadcast(String msg, String color) {
        SocketManager.GAME_SEND_MESSAGE_TO_ALL(msg, color);
    }

    /** Appele chaque minute par le planificateur d'AtrionExtras. */
    static void tick() {
        if (World.world.getOnlinePlayers().isEmpty()) return;
        try { if (INVASION) invasionTick(); } catch (Throwable t) { World.world.logger.error("Atrion: invasion", t); }
        try { if (QUIZ) quizTick(); } catch (Throwable t) { World.world.logger.error("Atrion: quiz", t); }
        try { if (LOTTERY) lotteryTick(); } catch (Throwable t) { World.world.logger.error("Atrion: tombola", t); }
    }

    // ================================================================== Invasion

    private static final long INVASION_EVERY_MS = 150 * 60_000L;
    private static final long INVASION_FIRST_MS = 15 * 60_000L;
    private static final long INVASION_DURATION_MS = 40 * 60_000L;
    private static final int INVASION_SIZE = 6;
    private static final short INVASION_STARS = 200;
    private static final int INVASION_REWARD = 25;

    private static volatile MonsterGroup invasionGroup;
    private static volatile GameMap invasionMap;
    private static volatile long invasionAt = 0;
    private static volatile boolean invasionBeaten = false;
    private static final long bootAt = System.currentTimeMillis();

    private static void invasionTick() {
        long now = System.currentTimeMillis();
        if (invasionGroup != null) {
            if (!invasionBeaten && now - invasionAt > INVASION_DURATION_MS) {
                GameMap map = invasionMap;
                MonsterGroup g = invasionGroup;
                boolean onMap = map != null && map.getMobGroups().get(g.getId()) == g;
                if (!onMap && now - invasionAt < INVASION_DURATION_MS + 30 * 60_000L) return; // combat en cours : on attend
                if (onMap) {
                    map.removeNpcOrMobGroup(g.getId());
                    SocketManager.GAME_SEND_ERASE_ON_MAP_TO_MAP(map, g.getId());
                }
                broadcast("Invasion : les monstres d'elite se sont retires sans etre vaincus...", "FF7B5C");
                invasionGroup = null;
            } else if (invasionBeaten && now - invasionAt > INVASION_DURATION_MS) {
                invasionGroup = null;
            }
            return;
        }
        long last = invasionAt == 0 ? bootAt - INVASION_EVERY_MS + INVASION_FIRST_MS : invasionAt;
        if (now - last < INVASION_EVERY_MS) return;
        spawnInvasion();
    }

    private static int averageOnlineLevel() {
        int n = 0, sum = 0;
        for (Player p : World.world.getOnlinePlayers()) { if (p == null) continue; n++; sum += p.getLevel(); }
        return n == 0 ? 50 : sum / n;
    }

    /** Lance une invasion (aussi utilisable par un admin : .invasion). */
    public static boolean spawnInvasion() {
        int avg = averageOnlineLevel();
        List<GameMap> candidates = new ArrayList<>();
        List<GameMap> fallback = new ArrayList<>();
        for (GameMap map : World.world.getMaps()) {
            if (map == null || DungeonTiers.isDungeonMap(map.getId()) || !AutoPilot.isClientMap(map.getId())) continue;
            List<MonsterGrade> poss = map.getMobPossibles();
            if (poss == null || poss.isEmpty() || map.getCases() == null || map.getCases().isEmpty()) continue;
            if (map.getMobGroups().isEmpty()) continue; // carte reellement peuplee
            int lvl = poss.stream().filter(Objects::nonNull).mapToInt(MonsterGrade::getLevel).max().orElse(0);
            fallback.add(map);
            if (Math.abs(lvl - avg) <= 25) candidates.add(map);
        }
        List<GameMap> pool = candidates.isEmpty() ? fallback : candidates;
        if (pool.isEmpty()) return false;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 10; attempt++) {
            GameMap map = pool.get(rng.nextInt(pool.size()));
            Map<Integer, Monster> templates = new LinkedHashMap<>();
            for (MonsterGrade g : map.getMobPossibles())
                if (g != null && g.getTemplate() != null && !g.getTemplate().isBoss()) templates.put(g.getTemplate().getId(), g.getTemplate());
            if (templates.isEmpty()) continue;
            List<Monster> list = new ArrayList<>(templates.values());
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < INVASION_SIZE; i++) {
                Monster m = list.get(rng.nextInt(list.size()));
                int max = m.getGrades().values().stream().mapToInt(MonsterGrade::getLevel).max().orElse(1);
                data.append(m.getId()).append(',').append(max).append(',').append(max).append(';');
            }
            int cell = map.getRandomFreeCellId();
            if (cell <= 0) continue;
            MonsterGroup group = map.spawnGroupOnCommand(cell, data.toString(), false);
            if (group == null || group.getMobs().isEmpty()) continue;
            group.setStarBonus(INVASION_STARS);
            SocketManager.GAME_SEND_MAP_MOBS_GM_PACKET(map, group);
            invasionGroup = group;
            invasionMap = map;
            invasionAt = System.currentTimeMillis();
            invasionBeaten = false;
            String area = "";
            try { if (map.getSubArea() != null && map.getSubArea().getName() != null) area = " (" + map.getSubArea().getName() + ")"; } catch (Exception ignored) { }
            int lvl = group.getMobs().values().stream().mapToInt(MonsterGrade::getLevel).sum();
            broadcast("INVASION ! Un groupe d'elite (niveau total " + lvl + ", bonus d'etoiles maximal) est apparu en [" + map.getX() + "," + map.getY() + "]" + area
                    + ". +" + INVASION_REWARD + " pts pour les vainqueurs. Il repart dans " + (INVASION_DURATION_MS / 60_000) + " minutes. Double-clic sur la carte pour y aller !", "FF5C3C");
            return true;
        }
        return false;
    }

    /** Appele pour chaque joueur gagnant d'un combat PvM. */
    public static void onGroupDefeated(MonsterGroup group, Player player) {
        if (group == null || player == null || group != invasionGroup) return;
        boolean first = !invasionBeaten;
        invasionBeaten = true;
        data().addPoints(player.getId(), INVASION_REWARD);
        AtrionAchievements.increment(player, "c_inv");
        org.starloco.locos.util.TimerWaiter.addNext(() -> player.sendMessage("<b>Invasion repoussee</b> : +" + INVASION_REWARD + " points de boutique."), 2000);
        if (first) broadcast(player.getName() + " et son equipe ont repousse l'invasion ! Prochaine invasion dans environ "
                + (INVASION_EVERY_MS / 60_000) + " minutes.", "FFB43C");
        AtrionAchievements.check(player);
    }

    private static String invasionStatus() {
        if (!INVASION) return "";
        MonsterGroup g = invasionGroup;
        GameMap m = invasionMap;
        if (g != null && !invasionBeaten && m != null)
            return "Invasion en cours en [" + m.getX() + "," + m.getY() + "] (encore " + Math.max(1, (INVASION_DURATION_MS - (System.currentTimeMillis() - invasionAt)) / 60_000) + " min).";
        long last = invasionAt == 0 ? bootAt - INVASION_EVERY_MS + INVASION_FIRST_MS : invasionAt;
        long next = Math.max(0, INVASION_EVERY_MS - (System.currentTimeMillis() - last));
        return "Prochaine invasion dans environ " + Math.max(1, next / 60_000) + " min.";
    }

    // ================================================================== Quiz

    private static final long QUIZ_EVERY_MS = 40 * 60_000L;
    private static final long QUIZ_TIMEOUT_MS = 3 * 60_000L;
    private static final int QUIZ_REWARD = 10;

    private static final String[][] QUESTIONS = {
            {"Combien de PA a un personnage de niveau 1 ?", "6", "six"},
            {"Combien de PM a un personnage de niveau 1 ?", "3", "trois"},
            {"A quel niveau gagne-t-on un PA supplementaire ?", "100", "cent"},
            {"Quel est le niveau maximum d'un personnage (hors prestige) ?", "200", "deux cents"},
            {"Combien y a-t-il de classes jouables ?", "12", "douze"},
            {"Quelle classe peut devenir invisible et poser des pieges ?", "sram"},
            {"Quelle classe d'archers tire a longue distance ?", "cra"},
            {"Quelle classe soigne avec le Mot Curatif ?", "eniripsa", "eni"},
            {"Quelle classe adore les kamas et manie la pelle ?", "enutrof", "enu"},
            {"Quelle classe parie sur la chance avec la Roulette ?", "ecaflip", "eca"},
            {"Quelle classe frappe avec la Colere de Iop ?", "iop"},
            {"Quelle classe manipule le temps ?", "xelor"},
            {"Quelle classe se sert de son propre sang et de la douleur ?", "sacrieur", "sacri"},
            {"Quelle classe protege ses allies avec des armures elementaires ?", "feca"},
            {"Quelle classe invoque des poupees et des arbres ?", "sadida"},
            {"Quelle classe invoque des creatures comme le Tofu ou le Bouftou ?", "osamodas", "osa"},
            {"Quelle est la capitale des anges ?", "bonta"},
            {"Quelle est la capitale des demons ?", "brakmar"},
            {"Dans quelle cite les nouveaux aventuriers font-ils leurs premiers pas ?", "astrub"},
            {"Quelle est la monnaie du Monde des Douze ?", "kamas", "kama"},
            {"Combien y a-t-il de Dofus primordiaux ?", "6", "six"},
            {"Quel Dofus donne de la vitalite ?", "emeraude", "dofus emeraude"},
            {"Quel Dofus augmente les dommages en pourcentage ?", "pourpre", "dofus pourpre"},
            {"Quel Dofus donne des coups critiques ?", "turquoise", "dofus turquoise"},
            {"Quel metier fabrique les anneaux et les amulettes ?", "bijoutier"},
            {"Quel metier fabrique les bottes et les ceintures ?", "cordonnier"},
            {"Quel metier fabrique les capes et les chapeaux ?", "tailleur"},
            {"Quel metier recolte le bois ?", "bucheron"},
            {"Quel metier extrait les minerais ?", "mineur"},
            {"Quel metier recolte les cereales ?", "paysan"},
            {"Quel metier fabrique les potions ?", "alchimiste"},
            {"Quel metier fabrique le pain ?", "boulanger"},
            {"Quelle monture peut-on elever dans un enclos ?", "dragodinde", "dinde"},
            {"Quel est le legume prefere des Wabbits ?", "cawotte", "carotte"},
            {"Qui regne sur le chateau des Wabbits ?", "wa wabbit", "le wa wabbit", "wa"},
            {"Quel boss attend au bout du donjon des Bouftous ?", "bouftou royal", "le bouftou royal"},
            {"Quel familier de depart chaque aventurier d'Atrion recoit-il ?", "chienchien"},
            {"Combien de raretes existent au-dessus de Commun sur Atrion ?", "4", "quatre"},
            {"Quelle rarete est la plus haute sur Atrion ?", "mythique"},
            {"Quel oiseau de feu est l'embleme d'Atrion ?", "phenix", "phoenix", "le phenix"},
            {"Quelle commande montre les cibles de la chasse du jour ?", "chasse", ".chasse"},
            {"A quelle heure commence l'heure de pointe d'Atrion ?", "21", "21h", "21 h"},
            {"Quel est le dieu des Iops ?", "iop"},
            {"Quel animal de compagnie typique des Ecaflips est un chat ?", "chacha", "chacha blanc", "chat"},
            {"Comment s'appelle le monstre rose emblematique des plaines d'Astrub, proche du Bouftou, en forme d'oiseau ?", "tofu"},
    };

    private static volatile String[] currentQuestion;
    private static volatile long questionAt = 0;
    private static long lastQuiz = System.currentTimeMillis() - 20 * 60_000L; // premier quiz ~20 min apres le demarrage
    private static int lastIndex = -1;

    private static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static void quizTick() {
        long now = System.currentTimeMillis();
        String[] q = currentQuestion;
        if (q != null) {
            if (now - questionAt > QUIZ_TIMEOUT_MS) {
                currentQuestion = null;
                broadcast("Quiz : personne n'a trouve ! La reponse etait : " + q[1] + ".", "9BD3FF");
            }
            return;
        }
        if (now - lastQuiz < QUIZ_EVERY_MS) return;
        if (World.world.getOnlinePlayers().size() < 1) return;
        lastQuiz = now;
        int idx;
        do { idx = ThreadLocalRandom.current().nextInt(QUESTIONS.length); } while (idx == lastIndex && QUESTIONS.length > 1);
        lastIndex = idx;
        currentQuestion = QUESTIONS[idx];
        questionAt = now;
        broadcast("QUIZ (+" + QUIZ_REWARD + " pts) : " + QUESTIONS[idx][0] + " Reponds avec .r ta reponse", "7CFFB2");
    }

    public static synchronized boolean commandAnswer(Player player, String answer) {
        String[] q = currentQuestion;
        if (q == null) { player.sendMessage("Aucun quiz en cours."); return true; }
        String a = norm(answer);
        boolean ok = false;
        for (int i = 1; i < q.length; i++) if (norm(q[i]).equals(a)) ok = true;
        if (!ok) { player.sendMessage("Mauvaise reponse, essaie encore !"); return true; }
        currentQuestion = null;
        data().addPoints(player.getId(), QUIZ_REWARD);
        AtrionAchievements.increment(player, "c_quiz");
        broadcast("Quiz : " + player.getName() + " a trouve la bonne reponse (" + q[1] + ") et gagne " + QUIZ_REWARD + " points !", "7CFFB2");
        AtrionAchievements.check(player);
        return true;
    }

    // ================================================================== Tombola

    private static final int TICKET_PRICE = 5, MAX_TICKETS = 20, LOTTERY_BONUS = 25, DRAW_HOUR = 22;

    private static int tickets(Player player) {
        long v = data().buffUntil(player.getId(), "lotto");
        return v / 1000 == today() ? (int) (v % 1000) : 0;
    }

    public static boolean commandLottery(Player player, String args) {
        if (!LOTTERY) return false;
        int wanted = 1;
        try { if (!args.isEmpty()) wanted = Math.max(1, Integer.parseInt(args.trim())); } catch (NumberFormatException ignored) { }
        ZonedDateTime now = ZonedDateTime.now(PARIS);
        if (args.isEmpty() || args.equalsIgnoreCase("info")) {
            player.sendMessage(lotteryInfo(player));
            return true;
        }
        if (now.getHour() >= DRAW_HOUR) {
            player.sendMessage("Le tirage du jour est passe. La vente de tickets reprend a minuit.");
            return true;
        }
        int have = tickets(player);
        int n = Math.min(wanted, MAX_TICKETS - have);
        if (n <= 0) { player.sendMessage("Maximum " + MAX_TICKETS + " tickets par jour."); return true; }
        if (!data().addPoints(player.getId(), -n * TICKET_PRICE)) {
            player.sendMessage("Pas assez de points (" + (n * TICKET_PRICE) + " necessaires).");
            return true;
        }
        data().setBuff(player.getId(), "lotto", today() * 1000 + have + n);
        player.sendMessage("Tu as achete " + n + " ticket(s) de tombola. " + lotteryInfo(player));
        return true;
    }

    private static String lotteryInfo(Player player) {
        int total = 0, players = 0;
        long day = today();
        for (Map.Entry<Integer, Long> e : data().buffsByKey("lotto").entrySet()) {
            if (e.getValue() / 1000 != day || e.getKey() <= 0) continue;
            int t = (int) (e.getValue() % 1000);
            if (t > 0) { total += t; players++; }
        }
        int pot = total * TICKET_PRICE + (total > 0 ? LOTTERY_BONUS : 0);
        return "Tombola du jour : <b>" + pot + "</b> points en jeu, " + total + " ticket(s), " + players + " participant(s). Tes tickets : "
                + (player == null ? 0 : tickets(player)) + ". Tirage a " + DRAW_HOUR + "h. Achat : .tombola [nombre] (" + TICKET_PRICE + " pts le ticket).";
    }

    private static void lotteryTick() {
        ZonedDateTime now = ZonedDateTime.now(PARIS);
        if (now.getHour() < DRAW_HOUR) return;
        long day = today();
        if (data().buffUntil(0, "lotto_draw") >= day) return;
        data().setBuff(0, "lotto_draw", day);
        List<Integer> bag = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : data().buffsByKey("lotto").entrySet()) {
            if (e.getValue() / 1000 != day || e.getKey() <= 0) continue;
            int t = (int) (e.getValue() % 1000);
            for (int i = 0; i < t; i++) bag.add(e.getKey());
        }
        if (bag.isEmpty()) return;
        int winner = bag.get(ThreadLocalRandom.current().nextInt(bag.size()));
        int pot = bag.size() * TICKET_PRICE + LOTTERY_BONUS;
        data().addPoints(winner, pot);
        data().setBuff(winner, "c_lotto", data().buffUntil(winner, "c_lotto") + 1);
        String name = data().names(Collections.singleton(winner)).getOrDefault(winner, "un aventurier");
        broadcast("TOMBOLA : " + name + " remporte " + pot + " points de boutique ! Nouveau tirage demain a " + DRAW_HOUR + "h.", "FFD84C");
        Player p = World.world.getPlayer(winner);
        if (p != null && p.isOnline()) {
            p.sendMessage("<b>Tu as gagne la tombola</b> : +" + pot + " points de boutique !");
            AtrionAchievements.check(p);
        }
    }

    // ================================================================== Commandes diverses

    public static void onJoin(Player player) {
        if (CONNECT_ANNOUNCE) {
            for (Player p : World.world.getOnlinePlayers())
                if (p != null && p != player) p.sendMessage("<i>" + player.getName() + " vient de se connecter.</i>");
        }
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            if (!player.isOnline()) return;
            if (currentQuestion != null) player.sendMessage("Un quiz est en cours : " + currentQuestion[0] + " (.r ta reponse)");
            MonsterGroup g = invasionGroup;
            if (g != null && !invasionBeaten) player.sendMessage(invasionStatus());
            AtrionAchievements.check(player);
        }, 6000);
    }

    public static boolean commandInfos(Player player) {
        long up = (System.currentTimeMillis() - Config.startTime) / 60_000;
        player.sendMessage("<b>Serveur Atrion</b>"
                + "<br/>- En ligne : <b>" + World.world.getOnlinePlayers().size() + "</b> joueur(s), depuis " + (up / 60) + " h " + (up % 60) + " min"
                + "<br/>- Taux : XP x" + Config.rateXp + ", butin x" + Config.rateDrop + ", kamas x" + Config.rateKamas + ", metiers x" + Config.rateJob
                + "<br/>- " + AtrionExtras.bonusLine()
                + "<br/>- " + invasionStatus()
                + (LOTTERY ? "<br/>- " + lotteryInfo(player) : "")
                + "<br/>- Commandes : .aide");
        return true;
    }

    private static final long HEAL_COOLDOWN_MS = 10 * 60_000L;

    public static boolean commandHeal(Player player) {
        if (!HEAL_COMMAND) return false;
        if (player.getFight() != null) { player.sendMessage("Impossible en combat."); return true; }
        long last = data().buffUntil(player.getId(), "heal_at");
        long wait = last + HEAL_COOLDOWN_MS - System.currentTimeMillis();
        if (wait > 0) { player.sendMessage("Tu pourras te soigner a nouveau dans " + (wait / 60_000 + 1) + " min."); return true; }
        if (player.getCurPdv() >= player.getMaxPdv()) { player.sendMessage("Tu as deja tous tes points de vie."); return true; }
        player.fullPDV();
        SocketManager.GAME_SEND_STATS_PACKET(player);
        data().setBuff(player.getId(), "heal_at", System.currentTimeMillis());
        player.sendMessage("Points de vie restaures (utilisable toutes les 10 minutes).");
        return true;
    }

    public static boolean commandRanking(Player player, String args) {
        String type = norm(args);
        Map<Integer, Integer> values;
        String title, unit;
        if (type.startsWith("best")) { values = data().flagCounts("bm"); title = "Bestiaire"; unit = "especes"; }
        else if (type.startsWith("succ")) { values = data().flagCounts("ach_"); title = "Succes"; unit = "succes"; }
        else if (type.startsWith("point")) { values = data().allPoints(); title = "Points de boutique"; unit = "pts"; }
        else {
            player.sendMessage("Classements : <b>.classement bestiaire</b>, <b>.classement succes</b>, <b>.classement points</b> (et <b>.top</b> pour le prestige).");
            return true;
        }
        List<Map.Entry<Integer, Integer>> rows = new ArrayList<>(values.entrySet());
        rows.removeIf(e -> e.getKey() <= 0);
        rows.sort((a, b) -> b.getValue() - a.getValue());
        if (rows.size() > 10) rows = rows.subList(0, 10);
        Map<Integer, String> names = data().names(rows.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList()));
        StringBuilder sb = new StringBuilder("<b>Classement : " + title + "</b>");
        int rank = 1;
        for (Map.Entry<Integer, Integer> e : rows)
            sb.append("<br/>").append(rank++).append(". ").append(names.getOrDefault(e.getKey(), "#" + e.getKey())).append(" - ").append(e.getValue()).append(' ').append(unit);
        if (rows.isEmpty()) sb.append("<br/>Personne pour l'instant.");
        player.sendMessage(sb.toString());
        return true;
    }

    // ================================================================== Parchottage contre points

    public static final boolean PARCHO = true;
    private static final int PARCHO_COST = 2, PARCHO_MAX = 101;

    public static boolean commandParcho(Player player, String args) {
        if (!PARCHO) return false;
        String[] p = norm(args).split(" ");
        Object[][] stats = {
                {"vitalite", 11, org.starloco.locos.kernel.Constant.STATS_ADD_VITA}, {"vita", 11, org.starloco.locos.kernel.Constant.STATS_ADD_VITA},
                {"sagesse", 12, org.starloco.locos.kernel.Constant.STATS_ADD_SAGE}, {"sage", 12, org.starloco.locos.kernel.Constant.STATS_ADD_SAGE},
                {"force", 10, org.starloco.locos.kernel.Constant.STATS_ADD_FORC},
                {"intelligence", 15, org.starloco.locos.kernel.Constant.STATS_ADD_INTE}, {"intel", 15, org.starloco.locos.kernel.Constant.STATS_ADD_INTE},
                {"chance", 13, org.starloco.locos.kernel.Constant.STATS_ADD_CHAN},
                {"agilite", 14, org.starloco.locos.kernel.Constant.STATS_ADD_AGIL}, {"agi", 14, org.starloco.locos.kernel.Constant.STATS_ADD_AGIL},
        };
        Object[] chosen = null;
        if (p.length > 0) for (Object[] st : stats) if (st[0].equals(p[0])) chosen = st;
        if (chosen == null) {
            StringBuilder sb = new StringBuilder("<b>Parchottage</b> (" + PARCHO_COST + " pts par point, max " + PARCHO_MAX + ") : .parcho &lt;stat&gt; [nombre|max]. Actuellement :");
            String[] labels = {"vitalite", "sagesse", "force", "intelligence", "chance", "agilite"};
            int[] ids = {org.starloco.locos.kernel.Constant.STATS_ADD_VITA, org.starloco.locos.kernel.Constant.STATS_ADD_SAGE, org.starloco.locos.kernel.Constant.STATS_ADD_FORC,
                    org.starloco.locos.kernel.Constant.STATS_ADD_INTE, org.starloco.locos.kernel.Constant.STATS_ADD_CHAN, org.starloco.locos.kernel.Constant.STATS_ADD_AGIL};
            for (int i = 0; i < labels.length; i++) sb.append(" ").append(labels[i]).append(" ").append(player.getStatsParcho().getEffect(ids[i])).append(i < labels.length - 1 ? "," : ".");
            player.sendMessage(sb.toString());
            return true;
        }
        if (player.getFight() != null || player.getExchangeAction() != null) { player.sendMessage("Impossible pendant un combat ou un echange."); return true; }
        int statId = (Integer) chosen[2];
        int current = player.getStatsParcho().getEffect(statId);
        int room = PARCHO_MAX - current;
        if (room <= 0) { player.sendMessage("Cette caracteristique est deja parchottee au maximum."); return true; }
        int n;
        if (p.length < 2 || p[1].equals("max")) n = room;
        else { try { n = Math.max(1, Integer.parseInt(p[1])); } catch (NumberFormatException e) { n = room; } }
        n = Math.min(n, room);
        int affordable = data().points(player.getId()) / PARCHO_COST;
        n = Math.min(n, affordable);
        if (n <= 0) { player.sendMessage("Pas assez de points (" + PARCHO_COST + " par point de caracteristique)."); return true; }
        if (!data().addPoints(player.getId(), -n * PARCHO_COST)) { player.sendMessage("Pas assez de points."); return true; }
        player.getStats().addOneStat(statId, n); // meme effet que les parchemins (hors capital)
        player.getStatsParcho().addOneStat(statId, n);
        player.refreshStats();
        SocketManager.GAME_SEND_STATS_PACKET(player);
        DatabaseManager.get(org.starloco.locos.database.data.login.PlayerData.class).update(player);
        player.sendMessage("+" + n + " " + chosen[0] + " parchottee (" + (current + n) + "/" + PARCHO_MAX + ", -" + (n * PARCHO_COST) + " pts, reste " + data().points(player.getId()) + ").");
        return true;
    }
}
