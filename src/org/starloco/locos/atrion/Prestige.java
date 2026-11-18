package org.starloco.locos.atrion;

import org.starloco.locos.client.Player;
import org.starloco.locos.client.other.Stats;
import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.database.data.login.PlayerData;
import org.starloco.locos.game.world.World;
import org.starloco.locos.kernel.Constant;
import org.starloco.locos.object.GameObject;
import org.starloco.locos.object.ObjectTemplate;
import org.starloco.locos.util.TimerWaiter;

import java.util.ArrayList;

/**
 * Atrion : systeme de prestige.
 *  - Prestige 0 a 3 (plafond).
 *  - Niveau max = 200 + 20 x prestige. Le reset se fait au niveau max, au zaap d'Astrub.
 *  - Bonus de caracteristiques permanents et cumulatifs.
 *  - Panoplie de l'Aventurier offerte (rarete = prestige + 1).
 *  - Rarete max equipable = prestige + 1.
 */
public final class Prestige {

    public static final int MAX_PRESTIGE = 3;
    /** Sagesse gagnee a chaque niveau (la sagesse ne s'achete plus avec le capital). */
    public static final int WISDOM_PER_LEVEL = 1;
    public static final int BASE_MAX_LEVEL = 200;
    public static final int LEVELS_PER_PRESTIGE = 20;
    /** Carte du zaap d'Astrub, seul endroit ou le reset est possible. */
    public static final int RESET_MAP_ID = 7411;

    /** Panoplie de l'Aventurier : cape, chapeau, anneau, bottes, ceinture, amulette. */
    public static final int[] ADVENTURER_SET = {2473, 2474, 2475, 2476, 2477, 2478};

    // Bonus permanents par prestige (index = prestige) : vita, force/intel/chance/agi, sagesse, PM, PA
    private static final int[] BONUS_VITA = {0, 100, 200, 300};
    private static final int[] BONUS_CARAC = {0, 20, 40, 60};
    private static final int[] BONUS_SAGE = {0, 20, 40, 60};
    private static final int[] BONUS_PM = {0, 0, 1, 1};
    private static final int[] BONUS_PA = {0, 0, 0, 1};

    private Prestige() {
    }

    private static PrestigeData data() {
        return DatabaseManager.get(PrestigeData.class);
    }

    public static int get(Player player) {
        if (player == null) return 0;
        PrestigeData data = data();
        return data == null ? 0 : Math.min(MAX_PRESTIGE, Math.max(0, data.get(player.getId())));
    }

    public static int maxLevel(Player player) {
        return BASE_MAX_LEVEL + LEVELS_PER_PRESTIGE * get(player);
    }

    public static Rarity maxEquipableRarity(Player player) {
        return Rarity.fromTier(get(player) + 1);
    }

    public static boolean canEquip(Player player, GameObject object) {
        return Rarity.of(object).tier <= maxEquipableRarity(player).tier;
    }

    /** Bonus permanents lies au prestige (ajoutes aux caracteristiques du personnage). */
    public static Stats bonusStats(Player player) {
        Stats stats = new Stats(false, null);
        // Sagesse gagnee par niveau
        if (player != null && player.getLevel() > 1)
            stats.addOneStat(Constant.STATS_ADD_SAGE, (player.getLevel() - 1) * WISDOM_PER_LEVEL);
        int p = get(player);
        if (p <= 0) return stats;
        stats.addOneStat(Constant.STATS_ADD_VITA, BONUS_VITA[p]);
        stats.addOneStat(Constant.STATS_ADD_FORC, BONUS_CARAC[p]);
        stats.addOneStat(Constant.STATS_ADD_INTE, BONUS_CARAC[p]);
        stats.addOneStat(Constant.STATS_ADD_CHAN, BONUS_CARAC[p]);
        stats.addOneStat(Constant.STATS_ADD_AGIL, BONUS_CARAC[p]);
        stats.addOneStat(Constant.STATS_ADD_SAGE, BONUS_SAGE[p]);
        if (BONUS_PM[p] > 0) stats.addOneStat(Constant.STATS_ADD_PM, BONUS_PM[p]);
        if (BONUS_PA[p] > 0) stats.addOneStat(Constant.STATS_ADD_PA, BONUS_PA[p]);
        return stats;
    }

    /** Donne la panoplie de l'Aventurier avec la rarete indiquee. */
    public static void giveAdventurerSet(Player player, Rarity rarity, boolean perfect) {
        for (int templateId : ADVENTURER_SET) {
            ObjectTemplate template = World.world.getObjTemplate(templateId);
            if (template == null) continue;
            GameObject item = template.createNewItem(1, perfect);
            if (item == null) continue;
            rarity.applyTo(item);
            player.addItem(item, true);
        }
    }

    /** Appele a la creation d'un personnage (prestige 0) : panoplie Rare, jets aleatoires. */
    public static void onCharacterCreated(Player player) {
        try {
            giveAdventurerSet(player, Rarity.fromTier(1), false);
            DatabaseManager.get(PlayerData.class).update(player);
        } catch (Exception e) {
            World.world.logger.error("Atrion: panoplie de depart impossible pour " + player.getName(), e);
        }
    }

    /** Commande .prestige */
    public static boolean handleCommand(Player player, String msg) {
        int p = get(player);
        int max = maxLevel(player);
        String[] args = msg.trim().split("\\s+");
        boolean confirm = args.length > 1 && args[1].equalsIgnoreCase("confirmer");

        if (!confirm) {
            player.sendMessage("<b>Prestige</b> : tu es prestige <b>" + p + "</b>/" + MAX_PRESTIGE
                    + " (niveau max " + max + ", rarete max " + maxEquipableRarity(player).label + ").");
            if (p >= MAX_PRESTIGE) {
                player.sendMessage("Tu as atteint le prestige maximum. Bravo !");
            } else {
                player.sendMessage("Au niveau " + max + ", rends-toi au zaap d'Astrub et tape <b>.prestige confirmer</b> pour passer prestige "
                        + (p + 1) + " : retour au niveau 1, inventaire conserve, bonus permanents et panoplie de l'Aventurier "
                        + Rarity.fromTier(p + 2).label + ".");
            }
            return true;
        }

        if (p >= MAX_PRESTIGE) {
            player.sendMessage("Tu as deja atteint le prestige maximum (" + MAX_PRESTIGE + ").");
            return true;
        }
        if (player.getLevel() < max) {
            player.sendMessage("Il faut etre niveau " + max + " pour passer prestige " + (p + 1) + " (tu es niveau " + player.getLevel() + ").");
            return true;
        }
        if (player.getCurMap() == null || player.getCurMap().getId() != RESET_MAP_ID) {
            player.sendMessage("Le passage de prestige se fait au zaap d'Astrub.");
            return true;
        }
        if (player.getFight() != null || player.getExchangeAction() != null) {
            player.sendMessage("Impossible pendant un combat ou un echange.");
            return true;
        }

        doReset(player, p + 1);
        return true;
    }

    private static void doReset(Player player, int newPrestige) {
        // 1. Retirer l'equipement (il reste dans l'inventaire)
        for (GameObject object : new ArrayList<>(player.getEquippedObjects())) {
            if (object.getPosition() >= 0 && object.getPosition() <= 15)
                player.unequipedObjet(object);
        }

        // 2. Remise a zero du personnage (niveau, caracteristiques, sorts)
        player.atrionResetToLevelOne();

        // 3. Nouveau prestige
        data().set(player.getId(), newPrestige);

        // 4. Panoplie de l'Aventurier, jets parfaits, rarete = prestige + 1
        giveAdventurerSet(player, Rarity.fromTier(newPrestige + 1), true);

        player.refreshStats();
        player.setPdv(player.getMaxPdv());
        DatabaseManager.get(PlayerData.class).update(player);

        player.sendMessage("<b>Felicitations !</b> Tu es maintenant <b>prestige " + newPrestige + "</b>. Reconnexion dans quelques secondes...");

        // Reconnexion pour que le client recharge proprement le personnage
        TimerWaiter.addNext(() -> {
            if (player.getGameClient() != null) player.getGameClient().kick();
        }, 4000);
    }
}
