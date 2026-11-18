package org.starloco.locos.atrion;

import org.starloco.locos.client.Player;
import org.starloco.locos.client.other.Stats;
import org.starloco.locos.kernel.Constant;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atrion : donjons a paliers.
 *  - Palier 0 normal, 1 Rare, 2 Epique, 3 Legendaire.
 *  - Le joueur choisit son palier avec .palier N (N &lt;= son prestige). Il s'applique dans les donjons.
 *  - Un joueur ne peut pas rejoindre un combat de palier superieur a son prestige.
 *  - Monstres renforces, XP multipliee, butin plus rare.
 */
public final class DungeonTiers {

    public static final String[] NAMES = {"Normal", "Rare", "Épique", "Légendaire"};
    // Index = palier
    private static final double[] MONSTER_STATS = {1.0, 1.3, 1.6, 2.0};
    private static final int[] MONSTER_RESIST = {0, 5, 10, 15};
    private static final double[] XP_FACTOR = {1.0, 1.5, 2.0, 3.0};
    private static final double[] EQUIPMENT_DROP_FACTOR = {1.0, 1.5, 2.0, 2.5};

    /** Cartes de donjon (generees depuis scripts/data/maps/dungeons). */
    private static final int[] DUNGEON_MAP_IDS = {
            307, 308, 309, 310, 314, 316, 317, 318, 319, 320, 321, 322, 323, 325, 327, 328,
            329, 330, 331, 332, 333, 334, 335, 337, 338, 339, 340, 341, 342, 345, 346, 347,
            348, 349, 350, 351, 355, 356, 357, 358, 359, 360, 361, 362, 364, 365, 366, 367,
            368, 369, 370, 371, 372, 377, 378, 379, 380, 381, 382, 387, 388, 392, 397, 403,
            404, 409, 410, 411, 412, 413, 414, 415, 416, 417, 419, 421, 749, 822, 1130, 1298,
            1767, 1768, 1769, 1770, 1771, 1772, 1773, 1774, 1780, 1781, 1783, 1784, 1785, 1786, 1787, 1788,
            1789, 1790, 1791, 1795, 1796, 1856, 2073, 2074, 2075, 2076, 2077, 2079, 2080, 2081, 2082, 2083,
            2084, 2085, 2086, 2087, 2088, 2089, 2090, 2091, 2094, 2095, 2096, 2097, 2098, 2099, 2100, 2102,
            2104, 2105, 2106, 2107, 2109, 2110, 2111, 2112, 2113, 2114, 2115, 2116, 2117, 2118, 2119, 2163,
            2985, 4383, 4786, 4919, 6536, 6738, 7453, 7519, 7520, 7521, 7522, 7523, 7524, 7525, 7526, 7530,
            7531, 7532, 7534, 7536, 7537, 7538, 7539, 7858, 8167, 8244, 8279, 8282, 8286, 8289, 8290, 8291,
            8293, 8294, 8295, 8296, 8321, 8322, 8326, 8327, 8328, 8329, 8330, 8331, 8338, 8340, 8342, 8344,
            8345, 8347, 8349, 8351, 8357, 8497, 8541, 8542, 8543, 8544, 8545, 8546, 8547, 8548, 8549, 8709,
            8710, 8711, 8713, 8714, 8715, 8716, 8718, 8719, 8721, 8722, 8723, 8724, 8905, 8950, 8951, 8952,
            8953, 8954, 8955, 8956, 8957, 8958, 8959, 8960, 8961, 8962, 8963, 8964, 8965, 8966, 8967, 8968,
            8969, 8970, 8971, 8972, 8973, 8974, 8975, 8976, 8977, 8978, 8979, 8980, 8981, 8982, 8983, 8984,
            9052, 9120, 9121, 9122, 9123, 9124, 9125, 9126, 9127, 9395, 9396, 9397, 9470, 9503, 9521, 9522,
            9523, 9524, 9525, 9526, 9527, 9528, 9529, 9530, 9531, 9532, 9533, 9534, 9535, 9538, 9553, 9554,
            9555, 9556, 9557, 9558, 9559, 9560, 9561, 9562, 9563, 9564, 9565, 9566, 9567, 9568, 9569, 9570,
            9571, 9572, 9573, 9574, 9575, 9576, 9577, 9578, 9580, 9581, 9582, 9583, 9584, 9585, 9586, 9587,
            9588, 9590, 9591, 9592, 9593, 9594, 9595, 9596, 9597, 9598, 9599, 9600, 9601, 9602, 9603, 9638,
            9645, 9646, 9647, 9648, 9649, 9650, 9651, 9652, 9653, 9654, 9655, 9656, 9657, 9658, 9659, 9716,
            9717, 9723, 9724, 9750, 9751, 9752, 9753, 9754, 9755, 9756, 9757, 9758, 9759, 9760, 9761, 9762,
            9763, 9765, 9766, 9768, 9769, 9770, 9771, 9772, 9773, 9774, 9775, 9776, 9777, 9778, 9779, 9780,
            9781, 9782, 9784, 9785, 9786, 9787, 9788, 9864, 9876, 9877, 9879, 9880, 9881, 9946, 9981, 9989,
            9993, 10019, 10020, 10021, 10022, 10023, 10024, 10025, 10098, 10099, 10100, 10101, 10102, 10103, 10106, 10107,
            10109, 10110, 10112, 10141, 10142, 10143, 10144, 10145, 10146, 10147, 10148, 10149, 10150, 10151, 10153, 10154,
            10155, 10156, 10157, 10158, 10159, 10160, 10161, 10162, 10163, 10164, 10165, 10176, 10191, 10192, 10193, 10194,
            10195, 10196, 10197, 10198, 10199, 10200, 10201, 10202, 10203, 10204, 10205, 10210, 10211, 10212, 10213, 10234,
            10235, 10352, 10359, 10360, 10361, 10362, 10363, 10364, 10509, 10693, 10722, 10807, 10808, 10809, 10810, 10811,
            10812, 10813, 10814, 10815, 11066, 11067, 11068, 11069, 11071, 11072, 11073, 11074, 11075, 11076, 11077, 11078,
            11079, 11084, 11095, 11096, 11108, 11111, 11113, 11115, 11121, 11127, 11128, 11133, 11136, 11234, 11235, 11259,
            11260, 11262, 11263, 11264, 11265, 11266, 11267, 11517, 11518, 11878, 11879, 11880, 11881, 11882, 11883, 11884,
            11885, 11886, 11887, 11888, 11889, 11890, 11891, 11892, 11927, 11931, 11932, 11933, 11934, 11935, 11936, 11937,
            11938, 11939, 11940, 11941, 11942, 11943, 11944, 11945, 11946, 11947, 11948, 11949, 11950, 11951, 11952, 11953,
            11954, 11955, 11956, 11957, 11958, 11959, 11960, 11961, 11962, 11965, 11966, 11967, 11968, 11969, 11970, 11971,
            11972, 11973, 11974, 11975, 11976,
    };
    private static final Set<Integer> DUNGEON_MAPS = new HashSet<>();
    static {
        for (int id : DUNGEON_MAP_IDS) DUNGEON_MAPS.add(id);
    }

    private static final Map<Integer, Integer> chosenTier = new ConcurrentHashMap<>();

    private DungeonTiers() {
    }

    public static boolean isDungeonMap(int mapId) {
        return DUNGEON_MAPS.contains(mapId);
    }

    public static int chosen(Player player) {
        return Math.min(chosenTier.getOrDefault(player.getId(), 0), Prestige.get(player));
    }

    /** Palier d'un combat lance par ce joueur sur cette carte. */
    public static int tierFor(Player player, int mapId) {
        if (player == null || !isDungeonMap(mapId)) return 0;
        return Math.max(0, Math.min(3, chosen(player)));
    }

    public static boolean canJoin(Player player, int fightTier) {
        return fightTier <= Prestige.get(player);
    }

    public static boolean handleCommand(Player player, String args) {
        int prestige = Prestige.get(player);
        if (args.isEmpty()) {
            player.sendMessage("<b>Palier de donjon</b> : " + NAMES[chosen(player)] + " (" + chosen(player) + "). "
                    + "Tu peux choisir de 0 a " + Math.min(3, prestige) + " avec <b>.palier N</b>. "
                    + "Le palier s'applique aux combats que tu lances dans un donjon.");
            return true;
        }
        int wanted;
        try {
            wanted = Integer.parseInt(args.trim());
        } catch (NumberFormatException e) {
            player.sendMessage("Utilisation : .palier 0, .palier 1, .palier 2 ou .palier 3");
            return true;
        }
        if (wanted < 0 || wanted > 3) {
            player.sendMessage("Les paliers vont de 0 (Normal) a 3 (Legendaire).");
            return true;
        }
        if (wanted > prestige) {
            player.sendMessage("Il faut etre prestige " + wanted + " pour le palier " + NAMES[wanted] + ".");
            return true;
        }
        chosenTier.put(player.getId(), wanted);
        player.sendMessage("Palier de donjon : <b>" + NAMES[wanted] + "</b>. Monstres x" + MONSTER_STATS[wanted]
                + ", XP x" + XP_FACTOR[wanted] + ", butin " + Rarity.fromTier(wanted).label + " et plus.");
        return true;
    }

    /** Caracteristiques d'un monstre renforcees pour le palier. */
    public static Stats scaleMonsterStats(Stats base, int tier) {
        Stats out = new Stats(false, null);
        if (base != null)
            for (Map.Entry<Integer, Integer> e : base.getEffects().entrySet())
                out.addOneStat(e.getKey(), e.getValue());
        if (tier <= 0) return out;
        double m = MONSTER_STATS[tier];
        int[] scaled = {Constant.STATS_ADD_VITA, Constant.STATS_ADD_FORC, Constant.STATS_ADD_INTE,
                Constant.STATS_ADD_CHAN, Constant.STATS_ADD_AGIL, Constant.STATS_ADD_INIT};
        for (int id : scaled) {
            int v = out.getEffects().getOrDefault(id, 0);
            if (v > 0) out.getEffects().put(id, (int) Math.round(v * m));
        }
        int res = MONSTER_RESIST[tier];
        for (int id : new int[]{Constant.STATS_ADD_RP_TER, Constant.STATS_ADD_RP_EAU, Constant.STATS_ADD_RP_AIR,
                Constant.STATS_ADD_RP_FEU, Constant.STATS_ADD_RP_NEU})
            out.addOneStat(id, res);
        return out;
    }

    public static long scaleXp(long xp, int tier) {
        if (tier <= 0 || xp <= 0) return xp;
        return Math.round(xp * XP_FACTOR[tier]);
    }

    public static double equipmentDropFactor(int tier) {
        return EQUIPMENT_DROP_FACTOR[Math.max(0, Math.min(3, tier))];
    }
}
