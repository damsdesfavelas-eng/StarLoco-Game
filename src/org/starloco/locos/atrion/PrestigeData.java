package org.starloco.locos.atrion;

import com.zaxxer.hikari.HikariDataSource;
import org.starloco.locos.database.data.FunctionDAO;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atrion : stockage du niveau de prestige de chaque personnage (base login, table atrion_prestige).
 * La table est creee automatiquement au premier acces.
 */
public class PrestigeData extends FunctionDAO<PrestigeData> {

    private final Map<Integer, Integer> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> flags = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Integer> points = new ConcurrentHashMap<>();

    public int points(int playerId) {
        ensureLoaded();
        return points.getOrDefault(playerId, 0);
    }

    /** Ajoute (ou retire si negatif) des points. Retourne false si le solde serait negatif. */
    public synchronized boolean addPoints(int playerId, int delta) {
        ensureLoaded();
        int value = points.getOrDefault(playerId, 0) + delta;
        if (value < 0) return false;
        points.put(playerId, value);
        execute("INSERT INTO `atrion_points` (player_id, points) VALUES (" + playerId + ", " + value + ") " +
                "ON DUPLICATE KEY UPDATE points = " + value + ";");
        return true;
    }
    private volatile boolean loaded = false;

    public PrestigeData(HikariDataSource dataSource) {
        super(dataSource, "atrion_prestige");
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        execute("CREATE TABLE IF NOT EXISTS " + getTableName() + " (" +
                "`player_id` INT(11) NOT NULL PRIMARY KEY, " +
                "`prestige` INT(11) NOT NULL DEFAULT 0, " +
                "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8;");
        execute("CREATE TABLE IF NOT EXISTS `atrion_points` (" +
                "`player_id` INT(11) NOT NULL PRIMARY KEY, `points` INT(11) NOT NULL DEFAULT 0" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8;");
        try {
            getData("SELECT player_id, points FROM `atrion_points`;", result -> {
                while (result.next())
                    points.put(result.getInt("player_id"), result.getInt("points"));
            });
        } catch (SQLException e) {
            logger.error("Atrion: impossible de charger les points", e);
        }
        execute("CREATE TABLE IF NOT EXISTS `atrion_flags` (" +
                "`player_id` INT(11) NOT NULL, `flag` VARCHAR(32) NOT NULL, PRIMARY KEY (`player_id`, `flag`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8;");
        try {
            getData("SELECT player_id, flag FROM `atrion_flags`;", result -> {
                while (result.next())
                    flags.add(result.getInt("player_id") + ":" + result.getString("flag"));
            });
        } catch (SQLException e) {
            logger.error("Atrion: impossible de charger les drapeaux", e);
        }
        try {
            getData("SELECT player_id, prestige FROM " + getTableName() + ";", result -> {
                while (result.next())
                    cache.put(result.getInt("player_id"), result.getInt("prestige"));
            });
        } catch (SQLException e) {
            logger.error("Atrion: impossible de charger les prestiges", e);
        }
        loaded = true;
    }

    public int get(int playerId) {
        ensureLoaded();
        return cache.getOrDefault(playerId, 0);
    }

    public void set(int playerId, int prestige) {
        ensureLoaded();
        cache.put(playerId, prestige);
        execute("INSERT INTO " + getTableName() + " (player_id, prestige) VALUES (" + playerId + ", " + prestige + ") " +
                "ON DUPLICATE KEY UPDATE prestige = " + prestige + ";");
    }

    /** Drapeau persistant par personnage (ex : "pet_given"). */
    public boolean hasFlag(int playerId, String flag) {
        ensureLoaded();
        return flags.contains(playerId + ":" + flag);
    }

    public void setFlag(int playerId, String flag) {
        ensureLoaded();
        if (flags.add(playerId + ":" + flag))
            execute("INSERT IGNORE INTO `atrion_flags` (player_id, flag) VALUES (" + playerId + ", '" + flag.replace("'", "") + "');");
    }

    /** Groupe admin le plus eleve parmi les personnages d'un compte (0 si aucun). */
    public int maxGroupForAccount(int accountId) {
        try {
            Integer value = getData("SELECT MAX(groupe) AS g FROM `world_players` WHERE account = " + accountId + ";", result -> {
                if (!result.next()) return 0;
                return Math.max(0, result.getInt("g"));
            });
            return value == null ? 0 : value;
        } catch (SQLException e) {
            logger.error("Atrion: lecture du groupe impossible", e);
            return 0;
        }
    }

    /** Groupe admin actuel d'un personnage en base. */
    public int groupOf(int playerId) {
        try {
            Integer value = getData("SELECT groupe FROM `world_players` WHERE id = " + playerId + ";", result -> {
                if (!result.next()) return 0;
                return result.getInt("groupe");
            });
            return value == null ? 0 : value;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Classement : liste "nom;prestige;niveau" triee par prestige puis niveau. */
    public java.util.List<String[]> top(int limit) {
        ensureLoaded();
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            getData("SELECT p.name, COALESCE(a.prestige, 0) AS prestige, p.level FROM `world_players` p " +
                    "LEFT JOIN " + getTableName() + " a ON a.player_id = p.id " +
                    "ORDER BY prestige DESC, p.level DESC, p.xp DESC LIMIT " + limit + ";", result -> {
                while (result.next())
                    out.add(new String[]{result.getString("name"), String.valueOf(result.getInt("prestige")), String.valueOf(result.getInt("level"))});
            });
        } catch (SQLException e) {
            logger.error("Atrion: classement impossible", e);
        }
        return out;
    }

    @Override
    public void loadFully() {
        ensureLoaded();
    }

    @Override
    public PrestigeData load(int id) {
        return this;
    }

    @Override
    public boolean insert(PrestigeData entity) {
        return false;
    }

    @Override
    public void delete(PrestigeData entity) {
    }

    @Override
    public void update(PrestigeData entity) {
    }

    @Override
    public Class<?> getReferencedClass() {
        return PrestigeData.class;
    }
}
