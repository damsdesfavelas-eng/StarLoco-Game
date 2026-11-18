package org.starloco.locos.atrion;

import org.starloco.locos.database.DatabaseManager;
import org.starloco.locos.database.data.login.ObjectData;
import org.starloco.locos.kernel.Constant;
import org.starloco.locos.object.GameObject;

import java.util.Map;

/**
 * Atrion : raretes des objets.
 * La rarete est stockee dans l'effet texte 985 ("Modifie par : ...") afin d'etre visible dans l'infobulle du client.
 */
public enum Rarity {
    NORMAL(0, "Normal", 1.0),
    RARE(1, "Rare", 1.25),
    EPIQUE(2, "Epique", 1.5),
    LEGENDAIRE(3, "Legendaire", 1.75),
    MYTHIQUE(4, "Mythique", 2.0);

    public static final int RARITY_TXT_STAT = Constant.STATS_CHANGE_BY; // 985
    private static final String PREFIX = "Rarete ";

    public final int tier;
    public final String label;
    public final double multiplier;

    Rarity(int tier, String label, double multiplier) {
        this.tier = tier;
        this.label = label;
        this.multiplier = multiplier;
    }

    public static Rarity fromTier(int tier) {
        for (Rarity r : values())
            if (r.tier == tier) return r;
        return tier > MYTHIQUE.tier ? MYTHIQUE : NORMAL;
    }

    /** Rarete d'un objet (NORMAL si aucune marque). */
    public static Rarity of(GameObject object) {
        if (object == null) return NORMAL;
        String txt = object.getTxtStat().get(RARITY_TXT_STAT);
        if (txt == null || !txt.startsWith(PREFIX)) return NORMAL;
        String label = txt.substring(PREFIX.length());
        for (Rarity r : values())
            if (r.label.equalsIgnoreCase(label)) return r;
        return NORMAL;
    }

    /**
     * Applique la rarete a un objet fraichement cree : multiplie ses caracteristiques positives
     * et pose la marque visible. Sauvegarde l'objet.
     */
    public void applyTo(GameObject object) {
        if (object == null || this == NORMAL) return;
        Map<Integer, Integer> effects = object.getStats().getEffects();
        for (Map.Entry<Integer, Integer> entry : effects.entrySet()) {
            int value = entry.getValue();
            if (value > 0 && isBoostable(entry.getKey()))
                entry.setValue((int) Math.ceil(value * multiplier));
        }
        object.getTxtStat().put(RARITY_TXT_STAT, PREFIX + label);
        DatabaseManager.get(ObjectData.class).update(object);
    }

    /** On ne multiplie pas les PA/PM/portee/invocations pour garder l'equilibre. */
    private static boolean isBoostable(int statId) {
        switch (statId) {
            case Constant.STATS_ADD_PA:
            case Constant.STATS_ADD_PM:
            case 117: // portee
            case 182: // invocations
                return false;
            default:
                return true;
        }
    }
}
