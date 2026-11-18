package org.starloco.locos.atrion;

import com.zaxxer.hikari.HikariDataSource;
import org.starloco.locos.database.data.FunctionDAO;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Atrion : lectures dans la base de jeu (noms des monstres) pour le bestiaire.
 */
public class AtrionGameData extends FunctionDAO<AtrionGameData> {

    private volatile Map<Integer, String> monsterNames;
    private volatile Map<Integer, String> setNames;

    public Map<Integer, String> setNames() {
        if (setNames == null) {
            synchronized (this) {
                if (setNames == null) {
                    Map<Integer, String> names = new HashMap<>();
                    try {
                        getData("SELECT ID, name FROM `itemsets`;", result -> {
                            while (result.next())
                                names.put(result.getInt("ID"), result.getString("name"));
                        });
                    } catch (SQLException e) {
                        logger.error("Atrion: panoplies illisibles", e);
                    }
                    setNames = Collections.unmodifiableMap(names);
                }
            }
        }
        return setNames;
    }

    public AtrionGameData(HikariDataSource dataSource) {
        super(dataSource, "monsters");
    }

    public Map<Integer, String> monsterNames() {
        if (monsterNames == null) {
            synchronized (this) {
                if (monsterNames == null) {
                    Map<Integer, String> names = new HashMap<>();
                    try {
                        getData("SELECT id, name FROM `monsters`;", result -> {
                            while (result.next())
                                names.put(result.getInt("id"), result.getString("name"));
                        });
                    } catch (SQLException e) {
                        logger.error("Atrion: noms des monstres illisibles", e);
                    }
                    monsterNames = Collections.unmodifiableMap(names);
                }
            }
        }
        return monsterNames;
    }

    @Override
    public void loadFully() {
    }

    @Override
    public AtrionGameData load(int id) {
        return this;
    }

    @Override
    public boolean insert(AtrionGameData entity) {
        return false;
    }

    @Override
    public void delete(AtrionGameData entity) {
    }

    @Override
    public void update(AtrionGameData entity) {
    }

    @Override
    public Class<?> getReferencedClass() {
        return AtrionGameData.class;
    }
}
