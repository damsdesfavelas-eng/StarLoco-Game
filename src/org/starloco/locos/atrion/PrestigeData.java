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
