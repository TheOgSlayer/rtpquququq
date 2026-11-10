package me.server.duels;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class Stats {
    private final File file;
    private final YamlConfiguration yml;

    public Stats(File file) {
        this.file = file;
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    public int wins(UUID id) { return yml.getInt("players." + id + ".wins", 0); }
    public int total(UUID id) { return yml.getInt("players." + id + ".total", 0); }

    public void record(UUID winner, UUID loser) {
        int w = wins(winner) + 1, wt = total(winner) + 1, lt = total(loser) + 1;
        yml.set("players." + winner + ".wins", w);
        yml.set("players." + winner + ".total", wt);
        yml.set("players." + loser + ".total", lt);
        save();
    }

    public void save() {
        try { yml.save(file); } catch (IOException e) { e.printStackTrace(); }
    }
}
