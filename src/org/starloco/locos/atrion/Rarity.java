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
    EPIQUE(2, "Épique", 1.5),
    LEGENDAIRE(3, "Légendaire", 1.75),
    MYTHIQUE(4, "Mythique", 2.0);

    public static final int RARITY_TXT_STAT = Constant.STATS_CHANGE_BY; // 985
    // « » existent en latin1 : la colonne stats de world_objects est en latin1, l'etoile ★ y serait perdue.
    private static final String OPEN = "\u00AB", CLOSE = "\u00BB";

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

    /** Texte affiche dans l'infobulle, ex : "« Épique »". */
    public String mark() {
        return OPEN + " " + label + " " + CLOSE;
    }

    /**
     * Rarete d'un objet (NORMAL si aucune marque).
     * Accepte « Rare », ★ Rare ★, ? Rare ? (etoile perdue en base latin1) et l'ancien format "Rarete Rare".
     */
    public static Rarity of(GameObject object) {
        if (object == null) return NORMAL;
        String txt = object.getTxtStat().get(RARITY_TXT_STAT);
        if (txt == null) return NORMAL;
        String key = normalize(txt).replaceAll("[^a-z ]", " ").trim().replaceAll("\\s+", " ");
        if (key.startsWith("rarete ")) key = key.substring(7);
        for (Rarity r : new Rarity[]{MYTHIQUE, LEGENDAIRE, EPIQUE, RARE})
            if (key.equals(normalize(r.label))) return r;
        return NORMAL;
    }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase();
    }

    /**
     * Signature par rune lors d'une forgemagie : on garde la marque de rarete si l'objet en a une,
     * sinon on met le nom du forgemage (comportement normal).
     */
    public static void signForge(GameObject object, String forgerName) {
        if (of(object) != NORMAL) return;
        object.getTxtStat().put(RARITY_TXT_STAT, forgerName);
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
        object.getTxtStat().put(RARITY_TXT_STAT, mark());
        DatabaseManager.get(ObjectData.class).update(object);
    }

    /** Passe un objet d'une rarete a celle-ci (recalcule les caracteristiques). */
    public void upgradeFrom(Rarity from, GameObject object) {
        if (object == null) return;
        double ratio = this.multiplier / from.multiplier;
        for (Map.Entry<Integer, Integer> entry : object.getStats().getEffects().entrySet()) {
            int value = entry.getValue();
            if (value > 0 && isBoostable(entry.getKey()))
                entry.setValue((int) Math.ceil(value * ratio));
        }
        if (this == NORMAL) object.getTxtStat().remove(RARITY_TXT_STAT);
        else object.getTxtStat().put(RARITY_TXT_STAT, mark());
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
