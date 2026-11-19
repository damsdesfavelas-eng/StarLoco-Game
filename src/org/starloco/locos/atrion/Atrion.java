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

    /** Familier de depart : Chienchien (familier classique sans evolution), rendu immortel. */
    public static final int STARTER_PET_TEMPLATE = 1711;
    /** Ancien familier de depart (Mini Champion), invisible dans le client : remplace automatiquement. */
    private static final int OLD_STARTER_PET_TEMPLATE = 8071;
    private static final String FLAG_PET = "pet_given_v2";

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
        try {
            ensureKeychain(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: trousseau", e);
        }
    }

    /** Estimation de l'XP en solo pour un groupe de monstres (meme formule que FormuleOfficiel, solo). */
    public static long estimateSoloXp(Player player, org.starloco.locos.entity.monster.MonsterGroup group, int tier) {
        if (player == null || group == null) return 0;
        long groupXp = 0;
        int mobLevels = 0;
        for (MonsterGrade grade : group.getMobs().values()) {
            if (grade == null) continue;
            groupXp += grade.getBaseXp();
            mobLevels += grade.getLevel();
        }
        int lvl = Math.max(1, player.getLevel());
        if (mobLevels <= 0) return 0;
        double sagesse = lvl * 0.5 + player.getTotalStats(true).getEffect(Constant.STATS_ADD_SAGE);
        double ratio = (double) Math.max(lvl, mobLevels) / (double) Math.min(lvl, mobLevels);
        double nvGrp = ratio < 3.0 ? 1 : 1 / ratio;
        int star = group.getStarBonus();
        double total = (1 + sagesse / 100) * (1 + (star / 100)) * (0.5 + 0.6) * nvGrp * groupXp
                * (lvl != 199 ? org.starloco.locos.kernel.Config.rateXp : 1) * World.world.getConquestBonus(player);
        if (lvl != 199 && player.getAccount() != null && player.getAccount().isSubscribeWithoutCondition())
            total *= 1.2;
        long xp = (long) (DungeonTiers.scaleXp((long) total, tier) * xpMultiplier(player));
        if (player.getLevel() >= Prestige.maxLevel(player)) return 0;
        return xp;
    }

    public static void announceXp(Player player, org.starloco.locos.entity.monster.MonsterGroup group, int tier) {
        try {
            long xp = estimateSoloXp(player, group, tier);
            player.sendMessage("XP estimee (solo, sans challenge) : <b>" + String.format(Locale.FRANCE, "%,d", xp).replace('\u202f', ' ').replace('\u00a0', ' ') + "</b>");
        } catch (Exception e) {
            World.world.logger.error("Atrion: estimation XP", e);
        }
    }

    // ------------------------------------------------------------------ Voyage par double-clic sur la carte
    public static void travelTo(Player player, int x, int y) {
        AutoPilot.start(player, x, y);
    }

    // Trousseau de cles (10207) : toutes les cles de donjon, jamais consommees (cf. Dungeons.lua)
    private static final int KEYCHAIN_TEMPLATE = 10207;
    private static final int[] DUNGEON_KEYS = {1568, 1569, 1570, 6884, 7309, 7310, 7311, 7312, 7557, 7908, 7918,
            7926, 7927, 8073, 8135, 8139, 8143, 8156, 8436, 8437, 8438, 8439, 8545, 9247, 9248, 9254, 11474, 11475};

    private static String keychainValue() {
        StringBuilder sb = new StringBuilder();
        for (int key : DUNGEON_KEYS) {
            if (sb.length() > 0) sb.append(',');
            sb.append(Integer.toHexString(key));
        }
        return sb.toString();
    }

    private static void ensureKeychain(Player player) {
        String value = keychainValue();
        GameObject keychain = null;
        for (GameObject item : player.getItems().values())
            if (item != null && item.getTemplate().getId() == KEYCHAIN_TEMPLATE) { keychain = item; break; }
        if (keychain != null) {
            if (!value.equals(keychain.getTxtStat().get(Constant.STATS_NAME_DJ))) {
                keychain.getTxtStat().put(Constant.STATS_NAME_DJ, value);
                DatabaseManager.get(ObjectData.class).update(keychain);
            }
            return;
        }
        ObjectTemplate template = World.world.getObjTemplate(KEYCHAIN_TEMPLATE);
        if (template == null) return;
        keychain = template.createNewItem(1, false);
        if (keychain == null) return;
        keychain.getTxtStat().put(Constant.STATS_NAME_DJ, value);
        DatabaseManager.get(ObjectData.class).update(keychain);
        player.addItem(keychain, false);
        World.world.addGameObject(keychain);
        DatabaseManager.get(PlayerData.class).update(player);
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

    /** Remet les bonus du familier de depart (PA, PM, 50 sagesse, 50 prospection) s'ils ont disparu. */
    private static void repairStarterPets(Player player) {
        boolean equippedChanged = false;
        for (GameObject pet : new ArrayList<>(player.getItems().values())) {
            if (pet == null || pet.getTemplate().getId() != STARTER_PET_TEMPLATE) continue;
            Map<Integer, Integer> effects = pet.getStats().getEffects();
            boolean ok = Integer.valueOf(1).equals(effects.get(Constant.STATS_ADD_PA))
                    && Integer.valueOf(1).equals(effects.get(Constant.STATS_ADD_PM))
                    && Integer.valueOf(50).equals(effects.get(Constant.STATS_ADD_SAGE))
                    && Integer.valueOf(50).equals(effects.get(Constant.STATS_ADD_PROS));
            if (ok) continue;
            effects.put(Constant.STATS_ADD_PA, 1);
            effects.put(Constant.STATS_ADD_PM, 1);
            effects.put(Constant.STATS_ADD_SAGE, 50);
            effects.put(Constant.STATS_ADD_PROS, 50);
            DatabaseManager.get(ObjectData.class).update(pet);
            if (pet.getPosition() != Constant.ITEM_POS_NO_EQUIPED) equippedChanged = true;
        }
        if (equippedChanged) player.refreshStats();
    }

    private static void giveStarterPet(Player player) {
        PrestigeData data = data();
        repairStarterPets(player);
        if (data == null || data.hasFlag(player.getId(), FLAG_PET)) return;
        // Retire l'ancien familier (Mini Champion) que le client n'affichait pas
        for (GameObject old : new ArrayList<>(player.getItems().values()))
            if (old != null && old.getTemplate().getId() == OLD_STARTER_PET_TEMPLATE)
                player.removeItem(old.getGuid(), old.getQuantity(), false, true);
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
        return templateId == STARTER_PET_TEMPLATE || templateId == OLD_STARTER_PET_TEMPLATE;
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
            case ".give":
                if (player.getGroup() == null) return false; // reserve aux admins
                return commandGive(player, msg.trim().substring(cmd.length()).trim());
            case ".item":
            case ".pano":
                if (player.getGroup() == null) return false; // reserve aux admins
                return cmd.equals(".item") ? commandFindItem(player, msg.trim().substring(cmd.length()).trim())
                        : commandFindSet(player, msg.trim().substring(cmd.length()).trim());
            case ".restat":
                if (player.getFight() != null || player.getExchangeAction() != null) {
                    player.sendMessage("Impossible pendant un combat ou un echange.");
                    return true;
                }
                player.resetStats(false); // garde les parchemins
                player.refreshStats();
                SocketManager.GAME_SEND_STATS_PACKET(player);
                DatabaseManager.get(PlayerData.class).update(player);
                player.sendMessage("Caracteristiques remises a zero : tu as <b>" + player.getCapital() + "</b> points de capital a repartir. Les parchemins sont conserves.");
                return true;
            case ".points":
                player.sendMessage("Tu as <b>" + data().points(player.getId()) + "</b> points de boutique. Voir <b>.boutique</b>.");
                return true;
            case ".bonus":
            case ".buffs":
                return commandBuffs(player);
            case ".boutique":
                return commandShop(player);
            case ".acheter":
                return commandBuy(player, msg.trim().substring(cmd.length()).trim());
            case ".recycler":
                return commandRecycle(player, msg.trim().substring(cmd.length()).trim());
            case ".ameliorer":
                return commandUpgrade(player, msg.trim().substring(cmd.length()).trim());
            case ".hdv":
                return commandHdv(player, msg.trim().substring(cmd.length()).trim());
            case ".palier":
                return DungeonTiers.handleCommand(player, msg.trim().substring(cmd.length()).trim());
            case ".craft":
                return commandCraft(player, msg.trim().substring(cmd.length()).trim());
            case ".atrion":
                player.sendMessage("<b>Commandes Atrion</b> : .prestige, .palier, .zaap, .banque, .hdv, .astrub, .craft &lt;metier&gt;, "
                        + ".drops &lt;monstre ou objet&gt;, .top, .points, .boutique, .acheter, .recycler, .ameliorer, .restat");
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

    /** Admin : .give numero rarete [max] - donne un objet dans la rarete choisie. */
    private static boolean commandGive(Player player, String args) {
        String[] parts = norm(args).split("\\s+");
        if (parts.length < 2) {
            player.sendMessage("Utilisation : <b>.give numero rarete [max]</b>, rarete = normal, rare, epique, legendaire ou mythique. "
                    + "Ex : .give 2473 mythique max");
            return true;
        }
        int templateId;
        try {
            templateId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("Le numero d'objet est invalide (utilise .item nom pour le trouver).");
            return true;
        }
        Rarity rarity = null;
        for (Rarity r : Rarity.values())
            if (norm(r.label).startsWith(parts[1])) rarity = r;
        if (rarity == null) {
            player.sendMessage("Rarete inconnue : normal, rare, epique, legendaire ou mythique.");
            return true;
        }
        boolean perfect = parts.length > 2 && parts[2].startsWith("max");
        ObjectTemplate template = World.world.getObjTemplate(templateId);
        if (template == null) {
            player.sendMessage("L'objet " + templateId + " n'existe pas.");
            return true;
        }
        GameObject item = template.createNewItem(1, perfect);
        if (item == null) {
            player.sendMessage("Impossible de creer cet objet.");
            return true;
        }
        rarity.applyTo(item);
        player.addItem(item, true);
        World.world.addGameObject(item);
        DatabaseManager.get(PlayerData.class).update(player);
        player.sendMessage("Objet donne : " + template.getName() + (rarity == Rarity.NORMAL ? "" : " " + rarity.mark()) + (perfect ? " (jets parfaits)" : ""));
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

    // ------------------------------------------------------------------ Chat

    /** Etoiles de prestige devant les messages du chat. */
    public static String decorateChat(Player player, String msg) {
        int p = Prestige.get(player);
        if (p <= 0) return msg;
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < p; i++) stars.append("\u2605");
        return stars + " " + msg;
    }

    // ------------------------------------------------------------------ Boutique de points

    /** {nom, templateId, prix} */
    private static final int BUFF_XP_ID = -1, BUFF_DROP_ID = -2;
    private static final Object[][] SHOP = {
            {"Puissant Parchemin de Force", 797, 15}, {"Puissant Parchemin d'Intelligence", 817, 15},
            {"Puissant Parchemin de Chance", 814, 15}, {"Puissant Parchemin d'Agilite", 801, 15},
            {"Puissant Parchemin de Vitalite", 810, 15}, {"Puissant Parchemin de Sagesse", 805, 20},
            {"Parchemin de Sorts (+1 point de sort)", 684, 40},
            {"Potion d'XP x2 (1 heure)", BUFF_XP_ID, 25},
            {"Potion de butin x2 (1 heure)", BUFF_DROP_ID, 25},
    };

    // ------------------------------------------------------------------ Potions (bonus temporaires)
    private static final long BUFF_DURATION_MS = 3600_000L;

    private static String buffKey(int id) { return id == BUFF_XP_ID ? "xp" : "drop"; }

    private static long buffRemaining(Player player, String buff) {
        if (player == null || data() == null) return 0;
        return Math.max(0, data().buffUntil(player.getId(), buff) - System.currentTimeMillis());
    }

    public static double xpMultiplier(Player player) { return buffRemaining(player, "xp") > 0 ? 2.0 : 1.0; }

    public static double dropMultiplier(Player player) { return buffRemaining(player, "drop") > 0 ? 2.0 : 1.0; }

    private static String formatDuration(long ms) {
        long min = (ms + 59_999) / 60_000;
        return min >= 60 ? (min / 60) + " h " + String.format("%02d", min % 60) + " min" : min + " min";
    }

    private static void giveBuff(Player player, int id, int hours) {
        String key = buffKey(id);
        long now = System.currentTimeMillis();
        long until = Math.max(now, data().buffUntil(player.getId(), key)) + hours * BUFF_DURATION_MS;
        data().setBuff(player.getId(), key, until);
        player.sendMessage("<b>" + (id == BUFF_XP_ID ? "XP x2" : "Butin x2") + "</b> actif pendant encore <b>" + formatDuration(until - now) + "</b>.");
    }

    private static boolean commandBuffs(Player player) {
        long xp = buffRemaining(player, "xp"), drop = buffRemaining(player, "drop");
        player.sendMessage("Bonus actifs : XP x2 " + (xp > 0 ? "<b>" + formatDuration(xp) + "</b>" : "aucun")
                + ", butin x2 " + (drop > 0 ? "<b>" + formatDuration(drop) + "</b>" : "aucun") + ".");
        return true;
    }

    private static boolean commandShop(Player player) {
        StringBuilder sb = new StringBuilder("<b>Boutique Atrion</b> (tu as " + data().points(player.getId()) + " points) :");
        for (int i = 0; i < SHOP.length; i++)
            sb.append("<br/>").append(i + 1).append(". ").append(SHOP[i][0]).append(" : ").append(SHOP[i][2]).append(" pts");
        sb.append("<br/>Achat : <b>.acheter numero [quantite]</b>. Points gagnes : 1 par combat gagne (+ palier du donjon), recyclage d'objets rares.");
        player.sendMessage(sb.toString());
        return true;
    }

    private static boolean commandBuy(Player player, String args) {
        String[] parts = args.split("\\s+");
        int index, quantity = 1;
        try {
            index = Integer.parseInt(parts[0]) - 1;
            if (parts.length > 1) quantity = Math.max(1, Math.min(100, Integer.parseInt(parts[1])));
        } catch (Exception e) {
            player.sendMessage("Utilisation : <b>.acheter numero [quantite]</b> (voir .boutique)");
            return true;
        }
        if (index < 0 || index >= SHOP.length) {
            player.sendMessage("Numero inconnu, voir .boutique");
            return true;
        }
        int templateId = (Integer) SHOP[index][1];
        int cost = (Integer) SHOP[index][2] * quantity;
        if (templateId < 0) {
            if (!data().addPoints(player.getId(), -cost)) {
                player.sendMessage("Pas assez de points (" + cost + " necessaires, tu en as " + data().points(player.getId()) + ").");
                return true;
            }
            giveBuff(player, templateId, quantity);
            return true;
        }
        ObjectTemplate template = World.world.getObjTemplate(templateId);
        if (template == null) {
            player.sendMessage("Objet indisponible.");
            return true;
        }
        if (!data().addPoints(player.getId(), -cost)) {
            player.sendMessage("Pas assez de points (" + cost + " necessaires, tu en as " + data().points(player.getId()) + ").");
            return true;
        }
        GameObject item = template.createNewItem(quantity, false);
        if (item != null) {
            if (player.addItem(item, true, false)) World.world.addGameObject(item);
            DatabaseManager.get(PlayerData.class).update(player);
        }
        player.sendMessage("Achat : " + quantity + " x " + SHOP[index][0] + " (-" + cost + " pts, reste " + data().points(player.getId()) + ").");
        return true;
    }

    // ------------------------------------------------------------------ Recyclage / amelioration

    private static final int[] RECYCLE_POINTS = {0, 2, 5, 15, 40};
    private static final int[] UPGRADE_COST = {10, 30, 80, 200}; // index = rarete actuelle

    /** Objet non equipe de l'inventaire dont le nom correspond (le plus rare d'abord). */
    private static GameObject findInventoryItem(Player player, String query) {
        String q = norm(query);
        if (q.length() < 3) return null;
        return player.getItems().values().stream()
                .filter(o -> o != null && o.getPosition() == Constant.ITEM_POS_NO_EQUIPED)
                .filter(o -> isEquipmentType(o.getTemplate().getType()))
                .filter(o -> norm(o.getTemplate().getName()).contains(q))
                .max(Comparator.comparingInt(o -> Rarity.of(o).tier))
                .orElse(null);
    }

    private static boolean commandRecycle(Player player, String query) {
        GameObject item = findInventoryItem(player, query);
        if (item == null) {
            player.sendMessage("Utilisation : <b>.recycler nom de l'objet</b> (objet rare non equipe, dans ton inventaire).");
            return true;
        }
        Rarity rarity = Rarity.of(item);
        if (rarity == Rarity.NORMAL) {
            player.sendMessage("Seuls les objets Rares ou mieux se recyclent.");
            return true;
        }
        int earned = RECYCLE_POINTS[rarity.tier];
        player.removeItem(item.getGuid(), 1, true, true);
        data().addPoints(player.getId(), earned);
        DatabaseManager.get(PlayerData.class).update(player);
        player.sendMessage(item.getTemplate().getName() + " " + rarity.mark() + " recycle : +" + earned + " pts (total " + data().points(player.getId()) + ").");
        return true;
    }

    private static boolean commandUpgrade(Player player, String query) {
        GameObject item = findInventoryItem(player, query);
        if (item == null) {
            player.sendMessage("Utilisation : <b>.ameliorer nom de l'objet</b> (objet non equipe). Couts : Normal->Rare 10, Rare->Epique 30, Epique->Legendaire 80, Legendaire->Mythique 200 pts.");
            return true;
        }
        if (item.getQuantity() > 1) {
            player.sendMessage("Separe d'abord la pile : on ne peut ameliorer qu'un objet a la fois.");
            return true;
        }
        Rarity current = Rarity.of(item);
        if (current == Rarity.MYTHIQUE) {
            player.sendMessage("Cet objet est deja Mythique.");
            return true;
        }
        int cost = UPGRADE_COST[current.tier];
        if (!data().addPoints(player.getId(), -cost)) {
            player.sendMessage("Il faut " + cost + " points (tu en as " + data().points(player.getId()) + ").");
            return true;
        }
        Rarity next = Rarity.fromTier(current.tier + 1);
        next.upgradeFrom(current, item);
        SocketManager.GAME_SEND_UPDATE_OBJECT_DISPLAY_PACKET(player, item);
        player.sendMessage(item.getTemplate().getName() + " passe " + next.mark() + " (-" + cost + " pts).");
        return true;
    }

    // ------------------------------------------------------------------ Hotel de vente partout

    /** Hotels de vente de Bonta : {nom, carte}. */
    private static final Object[][] HDV = {
            {"bijoux", 4216}, {"armes", 4174}, {"armes2", 4098}, {"cordonnier", 4183}, {"tailleur", 4172},
            {"alchimiste", 4271}, {"ressources", 4262}, {"familiers", 8759}, {"boulanger", 2221},
            {"bucheron", 4178}, {"mineur", 4179}, {"pecheur", 4247}, {"paysan", 4299}, {"boucher", 4287},
            {"runes", 8757}, {"ames", 10129}, {"divers", 4232}, {"parchemins", 8760}, {"objets", 6159},
    };

    private static boolean commandHdv(Player player, String args) {
        String[] parts = norm(args).split("\\s+");
        if (parts[0].isEmpty()) {
            StringBuilder sb = new StringBuilder("Utilisation : <b>.hdv categorie</b> (achat) ou <b>.hdv categorie vente</b>. Categories : ");
            for (int i = 0; i < HDV.length; i++) sb.append(i == 0 ? "" : ", ").append(HDV[i][0]);
            player.sendMessage(sb.toString());
            return true;
        }
        if (player.getFight() != null || player.isInPrison()) return true;
        Integer mapId = null;
        for (Object[] h : HDV) if (((String) h[0]).equals(parts[0])) mapId = (Integer) h[1];
        if (mapId == null) {
            player.sendMessage("Categorie inconnue. Tape .hdv pour la liste.");
            return true;
        }
        boolean sell = parts.length > 1 && parts[1].startsWith("vente");
        org.starloco.locos.hdv.BigStore bigStore = World.world.getHdv(mapId);
        if (bigStore == null) {
            player.sendMessage("Cet hotel de vente n'existe pas sur ce serveur.");
            return true;
        }
        if (player.getExchangeAction() != null)
            org.starloco.locos.game.GameClient.leaveExchange(player);
        String info = "1,10,100;" + bigStore.getStrCategory() + ";" + bigStore.parseTaxe() + ";" + bigStore.getLvlMax() + ";"
                + bigStore.getMaxAccountItem() + ";-1;" + bigStore.getDuration();
        org.starloco.locos.game.action.type.BigStoreActionData data = new org.starloco.locos.game.action.type.BigStoreActionData(mapId);
        if (sell) {
            SocketManager.GAME_SEND_ECK_PACKET(player, 10, info);
            player.setExchangeAction(new ExchangeAction<>(ExchangeAction.AUCTION_HOUSE_SELLING, data));
            SocketManager.GAME_SEND_HDVITEM_SELLING(player, data.hdvId);
        } else {
            SocketManager.GAME_SEND_ECK_PACKET(player, 11, info);
            player.setExchangeAction(new ExchangeAction<>(ExchangeAction.AUCTION_HOUSE_BUYING, data));
        }
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
    /** Defi de donjon : palier Legendaire, un boss dans le groupe, victoire en 15 tours maximum. */
    public static boolean isDungeonChallengeWon(int tier, Collection<MonsterGrade> mobs, int turnCalls, int fighters) {
        if (tier < 3 || mobs == null || fighters <= 0) return false;
        boolean boss = mobs.stream().anyMatch(g -> g != null && g.getTemplate() != null && g.getTemplate().isBoss());
        return boss && turnCalls <= 15 * fighters;
    }

    public static void rollBonusDrops(Collection<MonsterGrade> defeated, Fighter winner, Map<Integer, Integer> objectsWon, int tier, boolean challengeWon) {
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
            if (rng.nextDouble() * 100 < CHANCE_EQUIPMENT * factor * DungeonTiers.equipmentDropFactor(tier)) {
                ObjectTemplate equipment = randomEquipmentForLevel(level, rng);
                if (equipment != null) equipmentWon.add(equipment);
            }
        }
        giveWorldEquipment(winner.getPlayer(), equipmentWon, tier);

        Player player = winner.getPlayer();
        if (player != null) {
            // Points de boutique : 1 par combat gagne, + palier du donjon
            int earned = 1 + Math.max(0, tier);
            data().addPoints(player.getId(), earned);
            // Defi de donjon reussi : un objet Mythique garanti
            if (challengeWon) {
                int bossLevel = defeated.stream().filter(g -> g != null && g.getTemplate() != null && g.getTemplate().isBoss())
                        .mapToInt(MonsterGrade::getLevel).max().orElse(winner.getLvl());
                ObjectTemplate reward = randomEquipmentForLevel(bossLevel, rng);
                if (reward != null) giveFixedRarity(player, reward, Rarity.MYTHIQUE, "<b>Defi reussi !</b> ");
            }
        }
    }

    private static void giveFixedRarity(Player player, ObjectTemplate template, Rarity rarity, String prefix) {
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            GameObject item = template.createNewItem(1, false);
            if (item == null) return;
            rarity.applyTo(item);
            player.addItem(item, true);
            World.world.addGameObject(item);
            player.sendMessage(prefix + template.getName() + " " + rarity.mark() + " (niv. " + template.getLevel() + ")");
            DatabaseManager.get(PlayerData.class).update(player);
        }, 2000);
    }

    // ------------------------------------------------------------------ Equipements droppables partout

    /** Types d'equipement concernes (hors familiers et Dofus). */
    private static final Set<Integer> EQUIPMENT_TYPES = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 16, 17, 19, 20, 21, 22, 82));
    /** Chance par monstre vaincu qu'un equipement de son niveau tombe, en pourcentage. */
    private static final double CHANCE_EQUIPMENT = 0.5;

    public static boolean isEquipmentType(int type) {
        return EQUIPMENT_TYPES.contains(type);
    }
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

    private static void giveWorldEquipment(Player player, List<ObjectTemplate> templates, int tier) {
        if (player == null || templates.isEmpty()) return;
        org.starloco.locos.util.TimerWaiter.addNext(() -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            // En donjon de palier, la rarete de base est celle du palier ; sinon celle du prestige
            int prestige = tier > 0 ? tier : Prestige.get(player);
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
