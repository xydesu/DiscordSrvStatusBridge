package me.xydesu.discordsrvstatusbridge;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Handles loading and retrieval of configurable plugin messages from messages.yml.
 *
 * Author: xydesu
 */
public class Messages {

    private final DiscordSrvStatusBridge plugin;
    private FileConfiguration messages;

    public Messages(DiscordSrvStatusBridge plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");

        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        messages = YamlConfiguration.loadConfiguration(file);

        // 使用內建 messages.yml 作為 defaults，補齊缺失的 key
        InputStream defStream = plugin.getResource("messages.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);

            // 補齊缺失的 key 並存回磁碟
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key) && !messages.contains(key, false)) {
                    messages.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                try {
                    messages.save(file);
                    plugin.getLogger().info("Missing message keys have been automatically added to messages.yml!");
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to save updated messages.yml: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Gets a message string by key, translates color codes, and replaces placeholders.
     *
     * @param key          The message key (e.g. "command.no-permission")
     * @param replacements Key-value pairs for placeholder replacement. e.g. "error", e.getMessage()
     * @return The formatted, color-translated message string
     */
    public String get(String key, String... replacements) {
        String msg = messages.getString(key, "&c[DSSB] Missing message key: " + key);
        msg = ChatColor.translateAlternateColorCodes('&', msg);

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return msg;
    }

    /**
     * Gets a raw message string (no color translation) for use in logger messages.
     */
    public String raw(String key, String... replacements) {
        String msg = messages.getString(key, "[DSSB] Missing message key: " + key);
        msg = msg.replace("&", "").replace("§", "");

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return msg;
    }
}
