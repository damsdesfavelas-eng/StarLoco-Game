package org.starloco.locos.atrion;

import org.starloco.locos.job.JobAction;
import org.starloco.locos.kernel.Constant;
import org.starloco.locos.object.GameObject;

import java.util.Map;

/**
 * Atrion : limites d'over en forgemagie selon la rarete.
 *  - Max de base d'une ligne = max du template x multiplicateur de rarete (les objets rares sont deja boostes).
 *  - Over autorise : Normal +10%, Rare +25%, Epique +50%, Legendaire +75%, Mythique +100% (minimum +1).
 *  - Exotiques : petit plafond proportionnel a la rarete.
 *  - PA / PM / portee / invocations : +1 maximum, quelle que soit la rarete.
 *  - 2 lignes overmax (ou exotiques) maximum par objet.
 */
public final class ForgeOver {

    public static final int MAX_OVER_LINES = 2;
    private static final double[] OVER_PCT = {0.10, 0.25, 0.50, 0.75, 1.00};

    private ForgeOver() {
    }

    private static boolean isBoostable(int statId) {
        switch (statId) {
            case Constant.STATS_ADD_PA:
            case Constant.STATS_ADD_PM:
            case 117:
            case 182:
                return false;
            default:
                return true;
        }
    }

    private static double pct(GameObject obj) {
        int tier = Math.max(0, Math.min(OVER_PCT.length - 1, Rarity.of(obj).tier));
        return OVER_PCT[tier];
    }

    /** Max "normal" d'une ligne sur cet objet (template x rarete). */
    public static int baseMax(GameObject obj, int effectId, int templateMax) {
        if (templateMax <= 0 || !isBoostable(effectId)) return Math.max(0, templateMax);
        return (int) Math.ceil(templateMax * Rarity.of(obj).multiplier);
    }

    /** Valeur maximale atteignable en FM pour cette ligne. */
    public static int cap(GameObject obj, int effectId, int templateMax, int overPerEffect) {
        if (!isBoostable(effectId)) return Math.max(0, templateMax) + 1;
        int base = baseMax(obj, effectId, templateMax);
        double p = pct(obj);
        if (base <= 0) // exotique
            return Math.max(1, (int) Math.round(overPerEffect * p * 0.2));
        return base + Math.max(1, (int) Math.ceil(base * p));
    }

    /** true si la ligne est deja over, ou si l'objet a encore de la place pour une ligne over. */
    public static boolean canAddOverLine(GameObject obj, int effectId) {
        int count = 0;
        boolean alreadyOver = false;
        for (Map.Entry<Integer, Integer> e : obj.getStats().getEffects().entrySet()) {
            int id = e.getKey(), value = e.getValue();
            if (value <= 0) continue;
            int tMax = JobAction.getStatBaseMaxs(obj.getTemplate(), Integer.toHexString(id));
            if (value > baseMax(obj, id, tMax)) {
                if (id == effectId) alreadyOver = true;
                else count++;
            }
        }
        return alreadyOver || count < MAX_OVER_LINES;
    }

    public static boolean isOver(GameObject obj, int effectId, int value, int templateMax) {
        return value > baseMax(obj, effectId, templateMax);
    }
}
