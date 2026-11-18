package org.starloco.locos.atrion;

import org.starloco.locos.client.Player;
import org.starloco.locos.client.other.Stats;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.database.data.login.ObjectData;
import org.starloco.locos.database.data.login.PlayerData;
import org.starloco.locos.entity.monster.Monster;
import org.starloco.locos.entity.monster.MonsterGrade;
import org.starloco.locos.game.action.ExchangeAction;
import org.starloco.locos.fight.Fighter;
import org.starloco.locos.game.world.World;
import org.starloco.locos.job.Job;
import org.starloco.locos.kernel.Constant;
import org.starloco.locos.object.GameObject;
import org.starloco.locos.object.ObjectTemplate;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Atrion : fonctionnalites du serveur (connexion, commandes, drops bonus).
 */
public final class Atrion {

    /** Familier de depart : Mini Champion (familier sans faim ni evolution), rendu immortel. */
    public static final int STARTER_PET_TEMPLATE = 8071;
    private static final String FLAG_PET = "pet_given";

    private Atrion() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    // ------------------------------------------------------------------ Connexion

    /** Appele au debut de Player.OnJoinGame, avant l'envoi des metiers au client. */
    public static void onJoinGame(Player player) {
        try {
            refreshAdminGroup(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: droits admin", e);
        }
        try {
            unlockAllZaaps(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: zaaps", e);
        }
        try {
            learnAllJobs(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: metiers", e);
        }
        try {
            giveStarterPet(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: familier", e);
        }
    }

    /** Relit les droits en base et les etend a tous les personnages d'un compte admin. */
    private static void refreshAdminGroup(Player player) {
        PrestigeData data = data();
        if (data == null || player.getAccount() == null) return;
        int dbGroup = data.groupOf(player.getId());
        int accountGroup = data.maxGroupForAccount(player.getAccount().getId());
        int group = dbGroup > 0 ? dbGroup : accountGroup;
        if (group > 0) {
            player.setGroupe(group, dbGroup != group);
        }
    }

    private static void unlockAllZaaps(Player player) {
        for (int mapId : Constant.ZAAPS.keySet())
            player.atrionAddZaapSilently(mapId);
    }

    private static void learnAllJobs(Player player) {
        for (Job job : World.world.getJobs()) {
            if (job == null || job.getId() == 1) continue; // metier "Base"
            player.atrionLearnJobSilently(job);
        }
    }

    private static void giveStarterPet(Player player) {
        PrestigeData data = data();
        if (data == null || data.hasFlag(player.getId(), FLAG_PET)) return;
        ObjectTemplate template = World.world.getObjTemplate(STARTER_PET_TEMPLATE);
        if (template == null || World.world.getPets(STARTER_PET_TEMPLATE) == null) return;
        GameObject pet = template.createNewItem(1, false);
        if (pet == null) return;
        Map<Integer, Integer> effects = pet.getStats().getEffects();
        effects.clear();
        effects.put(Constant.STATS_ADD_PA, 1);
        effects.put(Constant.STATS_ADD_PM, 1);
        effects.put(Constant.STATS_ADD_SAGE, 50);
        effects.put(Constant.STATS_ADD_PROS, 50);
        DatabaseManager.get(ObjectData.class).update(pet);
        player.addItem(pet, false);
        World.world.addGameObject(pet);
        data.setFlag(player.getId(), FLAG_PET);
        DatabaseManager.get(PlayerData.class).update(player);
    }

    public static boolean isImmortalPet(int templateId) {
        return templateId == STARTER_PET_TEMPLATE;
    }

    // ------------------------------------------------------------------ Commandes

    /** Commandes joueur Atrion. Retourne true si la commande a ete traitee. */
    public static boolean handleCommand(Player player, String msg) {
        String lower = msg.toLowerCase(Locale.ROOT).trim();
        String cmd = lower.split("\\s+")[0];
        switch (cmd) {
            case ".prestige":
                return Prestige.handleCommand(player, msg);
            case ".zaap":
                return commandZaap(player);
            case ".drops":
            case ".bestiaire":
                return commandDrops(player, msg.trim().substring(cmd.length()).trim());
            case ".top":
                return commandTop(player);
            case ".item":
            case ".pano":
                if (player.getGroup() == null) return false; // reserve aux admins
                return cmd.equals(".item") ? commandFindItem(player, msg.trim().substring(cmd.length()).trim())
                        : commandFindSet(player, msg.trim().substring(cmd.length()).trim());
            case ".craft":
                return commandCraft(player, msg.trim().substring(cmd.length()).trim());
            case ".atrion":
                player.sendMessage("<b>Commandes Atrion</b> : .prestige, .zaap, .banque, .astrub, .craft &lt;metier&gt;, .drops &lt;monstre ou objet&gt;, .top");
                return true;
            default:
                return false;
        }
    }

    private static boolean commandZaap(Player player) {
        if (player.getFight() != null || player.getExchangeAction() != null || player.isInPrison()) {
            player.sendMessage("Impossible maintenant.");
            return true;
        }
        player.setExchangeAction(new ExchangeAction<>(ExchangeAction.IN_ZAAPING, 0));
        SocketManager.GAME_SEND_WC_PACKET(player);
        return true;
    }

    /** Noms de metiers (sans accents) -> identifiant. */
    private static final Map<String, Integer> JOB_NAMES = new LinkedHashMap<>();
    static {
        String[][] jobs = {
                {"boulanger", "25"}, {"boucher", "56"}, {"poissonnier", "58"}, {"alchimiste", "26"},
                {"tailleur", "27"}, {"cordonnier", "15"}, {"bijoutier", "16"}, {"bricoleur", "65"},
                {"forgeur d'epees", "11"}, {"forgeur de dagues", "17"}, {"forgeur de marteaux", "14"},
                {"forgeur de pelles", "20"}, {"forgeur de haches", "31"}, {"forgeur de boucliers", "60"},
                {"sculpteur d'arcs", "13"}, {"sculpteur de batons", "18"}, {"sculpteur de baguettes", "19"},
                {"mineur", "24"}, {"bucheron", "2"}, {"paysan", "28"}, {"pecheur", "36"}, {"chasseur", "41"},
                {"forgemage d'epees", "44"}, {"forgemage de dagues", "43"}, {"forgemage de marteaux", "45"},
                {"forgemage de pelles", "46"}, {"forgemage de haches", "47"}, {"sculptemage d'arcs", "48"},
                {"sculptemage de baguettes", "49"}, {"sculptemage de batons", "50"},
                {"cordomage", "62"}, {"joaillomage", "63"}, {"costumage", "64"}
        };
        for (String[] j : jobs) JOB_NAMES.put(j[0], Integer.parseInt(j[1]));
    }

    private static boolean commandCraft(Player player, String query) {
        String q = norm(query);
        if (q.length() < 3) {
            player.sendMessage("Utilisation : <b>.craft boulanger</b>. Metiers : " + String.join(", ", JOB_NAMES.keySet()));
            return true;
        }
        if (player.getFight() != null || player.getExchangeAction() != null || player.isInPrison()) {
            player.sendMessage("Impossible maintenant.");
            return true;
        }
        List<Map.Entry<String, Integer>> matches = JOB_NAMES.entrySet().stream()
                .filter(e -> e.getKey().startsWith(q) || e.getKey().contains(q)).collect(Collectors.toList());
        Optional<Map.Entry<String, Integer>> exact = matches.stream().filter(e -> e.getKey().equals(q)).findFirst();
        if (!exact.isPresent() && matches.size() != 1) {
            player.sendMessage(matches.isEmpty() ? "Metier inconnu : " + query
                    : "Precise le metier : " + matches.stream().map(Map.Entry::getKey).collect(Collectors.joining(", ")));
            return true;
        }
        int jobId = exact.orElse(matches.get(0)).getValue();
        org.starloco.locos.job.JobStat jobStat = player.getMetierByID(jobId);
        if (jobStat == null) {
            player.sendMessage("Tu ne connais pas ce metier.");
            return true;
        }
        int skill = jobStat.atrionCraftSkill();
        if (skill < 0) {
            player.sendMessage("Ce metier n'a pas de recette a fabriquer.");
            return true;
        }
        player.useCraftSkill(skill, org.starloco.locos.job.JobConstant.getTotalCaseByJobLevel(jobStat.get_lvl()));
        return true;
    }

    /** Admin : recherche de numeros d'objets (pour la commande ITEM). */
    private static boolean commandFindItem(Player player, String query) {
        String q = norm(query);
        if (q.length() < 3) {
            player.sendMessage("Utilisation : <b>.item gelano</b> (3 lettres minimum), puis ITEM &lt;numero&gt; dans la console.");
            return true;
        }
        List<ObjectTemplate> found = World.world.getObjectsTemplates().values().stream()
                .filter(t -> norm(t.getName()).contains(q))
                .sorted(Comparator.comparingInt(ObjectTemplate::getLevel).thenComparing(ObjectTemplate::getName))
                .collect(Collectors.toList());
        if (found.isEmpty()) {
            player.sendMessage("Aucun objet ne correspond a \"" + query + "\".");
            return true;
        }
        StringBuilder sb = new StringBuilder("<b>Objets</b> (" + found.size() + ") :");
        found.stream().limit(20).forEach(t -> sb.append("<br/>").append(t.getId()).append(" - ").append(t.getName())
                .append(" (niv. ").append(t.getLevel()).append(")"));
        if (found.size() > 20) sb.append("<br/>... precise ta recherche.");
        player.sendMessage(sb.toString());
        return true;
    }

    /** Admin : recherche de numeros de panoplies (pour la commande ITEMSET). */
    private static boolean commandFindSet(Player player, String query) {
        String q = norm(query);
        if (q.length() < 3) {
            player.sendMessage("Utilisation : <b>.pano bouftou</b>, puis ITEMSET &lt;numero&gt; dans la console.");
            return true;
        }
        Map<Integer, String> sets = DatabaseManager.get(AtrionGameData.class).setNames();
        List<Map.Entry<Integer, String>> found = sets.entrySet().stream()
                .filter(e -> norm(e.getValue()).contains(q)).sorted(Map.Entry.comparingByKey()).collect(Collectors.toList());
        if (found.isEmpty()) {
            player.sendMessage("Aucune panoplie ne correspond a \"" + query + "\".");
            return true;
        }
        StringBuilder sb = new StringBuilder("<b>Panoplies</b> (" + found.size() + ") :");
        found.stream().limit(20).forEach(e -> sb.append("<br/>").append(e.getKey()).append(" - ").append(e.getValue()));
        player.sendMessage(sb.toString());
        return true;
    }

    private static boolean commandTop(Player player) {
        List<String[]> top = data().top(10);
        StringBuilder sb = new StringBuilder("<b>Classement Atrion</b>");
        int rank = 1;
        for (String[] row : top)
            sb.append("<br/>").append(rank++).append(". ").append(row[0]).append(" - prestige ").append(row[1]).append(", niveau ").append(row[2]);
        player.sendMessage(sb.toString());
        return true;
    }

    // ------------------------------------------------------------------ Bestiaire

    private static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String pct(double v) {
        if (v >= 10) return String.format(Locale.FRANCE, "%.0f%%", v);
        if (v >= 1) return String.format(Locale.FRANCE, "%.1f%%", v);
        return String.format(Locale.FRANCE, "%.2f%%", v);
    }

    private static String range(World.Drop drop) {
        List<Double> p = drop.atrionPercents();
        if (p == null || p.isEmpty()) return "?";
        double min = Double.MAX_VALUE, max = 0;
        for (Double d : p) {
            if (d == null || d <= 0) continue;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        if (max == 0) return "?";
        return min == max ? pct(max) : pct(min) + " a " + pct(max);
    }

    private static boolean commandDrops(Player player, String query) {
        if (query.length() < 3) {
            player.sendMessage("Utilisation : <b>.drops bouftou</b> (monstre) ou <b>.drops laine de bouftou</b> (objet). 3 lettres minimum.");
            return true;
        }
        String q = norm(query);
        Map<Integer, String> names = DatabaseManager.get(AtrionGameData.class).monsterNames();

        // 1. Recherche par monstre
        List<Monster> monsters = new ArrayList<>();
        for (Monster m : World.world.getMonstres()) {
            String name = names.get(m.getId());
            if (name != null && norm(name).contains(q) && !m.getDrops().isEmpty()) monsters.add(m);
        }
        Optional<Monster> exactMonster = monsters.stream().filter(m -> norm(names.get(m.getId())).equals(q)).findFirst();
        if (exactMonster.isPresent() || monsters.size() == 1) {
            Monster m = exactMonster.orElse(monsters.get(0));
            StringBuilder sb = new StringBuilder("<b>").append(names.get(m.getId())).append("</b>").append(levelRange(m)).append(" laisse :");
            List<World.Drop> drops = new ArrayList<>(m.getDrops());
            drops.sort(Comparator.comparingDouble(d -> -maxPercent(d)));
            int shown = 0;
            for (World.Drop d : drops) {
                ObjectTemplate t = World.world.getObjTemplate(d.getObjectId());
                if (t == null) continue;
                sb.append("<br/>- ").append(t.getName()).append(" : ").append(range(d));
                if (d.getCeil() > 0) sb.append(" (prospection ").append(d.getCeil()).append(")");
                if (++shown >= 25) break;
            }
            sb.append("<br/><i>+ parchemins, parchemins de sort tres rares et equipements de niveau proche (rarete selon ton prestige).</i>");
            player.sendMessage(sb.toString());
            return true;
        }

        // 2. Recherche par objet
        List<ObjectTemplate> templates = World.world.getObjectsTemplates().values().stream()
                .filter(t -> norm(t.getName()).contains(q)).collect(Collectors.toList());
        Optional<ObjectTemplate> exactItem = templates.stream().filter(t -> norm(t.getName()).equals(q)).findFirst();
        List<ObjectTemplate> droppable = templates.stream().filter(t -> !droppers(t.getId()).isEmpty()).collect(Collectors.toList());
        ObjectTemplate item = exactItem.filter(t -> !droppers(t.getId()).isEmpty()).orElse(droppable.size() == 1 ? droppable.get(0) : null);
        if (item != null) {
            StringBuilder sb = new StringBuilder("<b>").append(item.getName()).append("</b> est laisse par :");
            List<Map.Entry<Monster, World.Drop>> list = droppers(item.getId());
            list.sort(Comparator.comparingDouble(e -> -maxPercent(e.getValue())));
            int shown = 0;
            for (Map.Entry<Monster, World.Drop> e : list) {
                String name = names.getOrDefault(e.getKey().getId(), "Monstre " + e.getKey().getId());
                sb.append("<br/>- ").append(name).append(levelRange(e.getKey())).append(" : ").append(range(e.getValue()));
                if (e.getValue().getCeil() > 0) sb.append(" (prospection ").append(e.getValue().getCeil()).append(")");
                if (++shown >= 25) break;
            }
            player.sendMessage(sb.toString());
            return true;
        }

        // 3. Plusieurs resultats : proposer une liste
        List<String> choices = new ArrayList<>();
        monsters.stream().limit(8).forEach(m -> choices.add(names.get(m.getId()) + " (monstre)"));
        droppable.stream().limit(8).forEach(t -> choices.add(t.getName() + " (objet)"));
        if (choices.isEmpty()) {
            player.sendMessage("Aucun monstre ni objet droppable ne correspond a \"" + query + "\".");
        } else {
            player.sendMessage("Plusieurs resultats, precise ta recherche :<br/>- " + String.join("<br/>- ", choices));
        }
        return true;
    }

    private static double maxPercent(World.Drop d) {
        List<Double> p = d.atrionPercents();
        if (p == null) return 0;
        return p.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private static String levelRange(Monster m) {
        int min = Integer.MAX_VALUE, max = 0;
        for (MonsterGrade g : m.getGrades().values()) {
            min = Math.min(min, g.getLevel());
            max = Math.max(max, g.getLevel());
        }
        if (max == 0) return "";
        return min == max ? " (niv. " + max + ")" : " (niv. " + min + "-" + max + ")";
    }

    private static List<Map.Entry<Monster, World.Drop>> droppers(int templateId) {
        List<Map.Entry<Monster, World.Drop>> out = new ArrayList<>();
        for (Monster m : World.world.getMonstres())
            for (World.Drop d : m.getDrops())
                if (d.getObjectId() == templateId) out.add(new AbstractMap.SimpleEntry<>(m, d));
        return out;
    }

    // ------------------------------------------------------------------ Drops bonus

    // Parchemins de caracteristiques par palier : [force, sagesse, vitalite, chance, agilite, intelligence]
    private static final int[] SCROLLS_SMALL = {683, 802, 806, 809, 798};           // pas de petit parchemin d'intelligence en base
    private static final int[] SCROLLS_NORMAL = {795, 803, 807, 811, 799, 815};
    private static final int[] SCROLLS_GREAT = {796, 804, 808, 812, 800, 816};
    private static final int[] SCROLLS_POWERFUL = {797, 805, 810, 814, 801, 817};
    /** Parchemins de sort (dont le parchemin de points de sort 684). */
    private static final int[] SPELL_SCROLLS = {684, 718, 719, 720, 731, 6664, 6966, 9200, 9916, 10506, 10507, 10508,
            10509, 10510, 10511, 10512, 10513, 10602, 10604, 10605};

    // Chances par monstre vaincu, en pourcentage (avant bonus de prospection)
    private static final double CHANCE_SMALL = 1.5, CHANCE_NORMAL = 1.2, CHANCE_GREAT = 1.0, CHANCE_POWERFUL = 0.6;
    private static final double CHANCE_SPELL = 0.05;

    /**
     * Ajoute les parchemins bonus gagnes par un joueur a la fin d'un combat contre des monstres.
     * Appele depuis Fight pour chaque gagnant.
     */
    public static void rollBonusDrops(Collection<MonsterGrade> defeated, Fighter winner, Map<Integer, Integer> objectsWon) {
        if (defeated == null || winner == null || objectsWon == null) return;
        double prospect = Math.max(1.0, Math.min(3.0, winner.atrionProspection() / 100.0));
        double factor = Math.sqrt(prospect);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<ObjectTemplate> equipmentWon = new ArrayList<>();
        for (MonsterGrade grade : defeated) {
            if (grade == null) continue;
            int level = grade.getLevel();
            int[] pool;
            double chance;
            if (level <= 40) { pool = SCROLLS_SMALL; chance = CHANCE_SMALL; }
            else if (level <= 90) { pool = SCROLLS_NORMAL; chance = CHANCE_NORMAL; }
            else if (level <= 140) { pool = SCROLLS_GREAT; chance = CHANCE_GREAT; }
            else { pool = SCROLLS_POWERFUL; chance = CHANCE_POWERFUL; }

            if (rng.nextDouble() * 100 < chance * factor)
                add(objectsWon, pool[rng.nextInt(pool.length)]);
            if (rng.nextDouble() * 100 < CHANCE_SPELL * factor)
                add(objectsWon, SPELL_SCROLLS[rng.nextInt(SPELL_SCROLLS.length)]);
            if (rng.nextDouble() * 100 < CHANCE_EQUIPMENT * factor) {
                ObjectTemplate equipment = randomEquipmentForLevel(level, rng);
                if (equipment != null) equipmentWon.add(equipment);
            }
        }
        giveWorldEquipment(winner.getPlayer(), equipmentWon);
    }

    // ------------------------------------------------------------------ Equipements droppables partout

    /** Types d'equipement concernes (hors familiers et Dofus). */
    private static final Set<Integer> EQUIPMENT_TYPES = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 16, 17, 19, 20, 21, 22, 82));
    /** Chance par monstre vaincu qu'un equipement de son niveau tombe, en pourcentage. */
    private static final double CHANCE_EQUIPMENT = 0.5;
    private static volatile List<ObjectTemplate> worldEquipment;

    /** Equipements droppables par niveau : ni deja lies a un monstre, ni objets MJ, ni objets sans caracteristiques. */
    private static List<ObjectTemplate> worldEquipment() {
        if (worldEquipment == null) {
            synchronized (Atrion.class) {
                if (worldEquipment == null) {
                    Set<Integer> monsterDrops = new HashSet<>();
                    for (Monster m : World.world.getMonstres())
                        for (World.Drop d : m.getDrops()) monsterDrops.add(d.getObjectId());
                    List<ObjectTemplate> list = World.world.getObjectsTemplates().values().stream()
                            .filter(t -> EQUIPMENT_TYPES.contains(t.getType()))
                            .filter(t -> !monsterDrops.contains(t.getId()))
                            .filter(t -> t.getStrTemplate() != null && !t.getStrTemplate().trim().isEmpty())
                            .filter(t -> !t.getName().contains("(MJ)"))
                            .sorted(Comparator.comparingInt(ObjectTemplate::getLevel))
                            .collect(Collectors.toList());
                    worldEquipment = list;
                }
            }
        }
        return worldEquipment;
    }

    private static ObjectTemplate randomEquipmentForLevel(int monsterLevel, ThreadLocalRandom rng) {
        int min = Math.max(1, monsterLevel - 10), max = monsterLevel + 5;
        List<ObjectTemplate> candidates = worldEquipment().stream()
                .filter(t -> t.getLevel() >= min && t.getLevel() <= max).collect(Collectors.toList());
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    /** Rarete d'un equipement droppe selon le prestige : rarete du prestige, petite chance au-dessus. */
    public static Rarity rollRarityForPrestige(int prestige, ThreadLocalRandom rng) {
        Rarity base = Rarity.fromTier(Math.min(prestige, Rarity.LEGENDAIRE.tier));
        double upgrade = base == Rarity.LEGENDAIRE ? 2.0 : 10.0;
        if (rng.nextDouble() * 100 < upgrade) return Rarity.fromTier(base.tier + 1);
        return base;
    }

    private static void giveWorldEquipment(Player player, List<ObjectTemplate> templates) {
        if (player == null || templates.isEmpty()) return;
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int prestige = Prestige.get(player);
            for (ObjectTemplate template : templates) {
                GameObject item = template.createNewItem(1, false);
                if (item == null) continue;
                Rarity rarity = rollRarityForPrestige(prestige, rng);
                rarity.applyTo(item);
                player.addItem(item, true);
                World.world.addGameObject(item);
                player.sendMessage("<b>Butin</b> : " + template.getName()
                        + (rarity == Rarity.NORMAL ? "" : " " + rarity.mark()) + " (niv. " + template.getLevel() + ")");
            }
            DatabaseManager.get(PlayerData.class).update(player);
        }, 1500);
    }

    private static void add(Map<Integer, Integer> objectsWon, int templateId) {
        if (World.world.getObjTemplate(templateId) == null) return;
        objectsWon.merge(templateId, 1, Integer::sum);
    }
}
