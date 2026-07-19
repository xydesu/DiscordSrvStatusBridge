package me.xydesu.discordsrvstatusbridge;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * Manages persistent runtime data (e.g. message-id) in a separate data.yml file.
 * This keeps config.yml clean and only written on server start or /dssb reload.
 *
 * Author: xydesu
 */
public class DataManager {

    private final DiscordSrvStatusBridge plugin;
    private final File dataFile;
    private FileConfiguration data;

    public DataManager(DiscordSrvStatusBridge plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        load();
    }

    private void load() {
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public String getMessageId() {
        return data.getString("message-id", "").trim();
    }

    public void setMessageId(String id) {
        data.set("message-id", id);
        save();
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save data.yml: " + e.getMessage());
        }
    }
}
