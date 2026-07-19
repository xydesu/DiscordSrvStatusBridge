package me.xydesu.discordsrvstatusbridge;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Plugin main class. Handles lifecycle, event listening, and DiscordSRV bridging.
 *
 * Author: xydesu
 */
public class DiscordSrvStatusBridge extends JavaPlugin implements Listener {

    private MaintenanceHook maintenanceHook;
    private StatusUpdater statusUpdater;
    private BukkitTask updateTask;
    private boolean discordSrvEnabled = false;
    private Messages messages;

    private BukkitTask immediateUpdateTask = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updateConfig();

        messages = new Messages(this);

        maintenanceHook = new MaintenanceHook(this);
        statusUpdater = new StatusUpdater(this, maintenanceHook);

        // 若 reload 時已有玩家在線，預先載入頭像快取
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            statusUpdater.fetchAvatarAsync(player.getUniqueId(), player.getName());
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (getServer().getPluginManager().isPluginEnabled("DiscordSRV")) {
            discordSrvEnabled = true;
            try {
                github.scarsz.discordsrv.DiscordSRV.api.subscribe(new DiscordSrvListener());
                getLogger().info(messages.raw("logger.discordsrv-subscribed"));
            } catch (Throwable t) {
                getLogger().warning(messages.raw("logger.discordsrv-subscribe-error", "error", t.getMessage()));
                startUpdateTask();
            }
        } else {
            getLogger().warning(messages.raw("logger.discordsrv-not-found"));
        }

        BridgeCommand cmd = new BridgeCommand(this);
        getCommand("discordsrvstatusbridge").setExecutor(cmd);
        getCommand("discordsrvstatusbridge").setTabCompleter(cmd);

        // 動態訂閱 Maintenance 事件，防止 NoClassDefFoundError
        org.bukkit.plugin.Plugin maintenancePlugin = getServer().getPluginManager().getPlugin("Maintenance");
        if (maintenancePlugin != null && maintenancePlugin.isEnabled()) {
            try {
                ClassLoader loader = maintenancePlugin.getClass().getClassLoader();
                @SuppressWarnings("unchecked")
                Class<? extends org.bukkit.event.Event> eventClass =
                    (Class<? extends org.bukkit.event.Event>) Class.forName("eu.kennytv.maintenance.api.event.MaintenanceChangedEvent", true, loader);

                getServer().getPluginManager().registerEvent(
                    eventClass,
                    new org.bukkit.event.Listener() {},
                    org.bukkit.event.EventPriority.MONITOR,
                    (listener, event) -> startImmediateUpdateTask(),
                    this
                );
                getLogger().info(messages.raw("logger.maintenance-subscribed"));
            } catch (Throwable t) {
                getLogger().warning(messages.raw("logger.maintenance-subscribe-error", "error", t.getMessage()));
            }
        }
    }

    @Override
    public void onDisable() {
        stopUpdateTask();
        if (immediateUpdateTask != null) {
            immediateUpdateTask.cancel();
        }

        if (discordSrvEnabled && statusUpdater != null) {
            getLogger().info(messages.raw("logger.shutdown-updating"));
            statusUpdater.updateStatus(true, true);
        }
    }

    /**
     * Starts the async status update task with a 2-second initial delay.
     */
    public void startUpdateTask() {
        startUpdateTask(40L);
    }

    /**
     * Starts the async status update task with a custom initial delay.
     *
     * @param firstDelayTicks Initial delay in ticks before the first execution
     */
    public void startUpdateTask(long firstDelayTicks) {
        stopUpdateTask();

        long intervalTicks = getConfig().getLong("update-interval-seconds", 30L) * 20L;
        if (intervalTicks < 200L) {
            intervalTicks = 600L;
        }

        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (statusUpdater != null) {
                statusUpdater.updateStatus(false, false);
            }
        }, firstDelayTicks, intervalTicks);

        // 僅在預設 2 秒延遲啟動時印出日誌，防止計時器重置時洗版日誌
        if (firstDelayTicks == 40L) {
            getLogger().info(messages.raw("logger.task-started", "interval", String.valueOf(intervalTicks / 20)));
        }
    }

    /**
     * Stops the currently running update task.
     */
    public void stopUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    // ==============================================================================
    // Player event handlers: trigger an immediate status update on join/quit
    // ==============================================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (statusUpdater != null) {
            statusUpdater.fetchAvatarAsync(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        }
        startImmediateUpdateTask();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (statusUpdater != null) {
            statusUpdater.removeAvatarFromCache(event.getPlayer().getUniqueId());
        }
        startImmediateUpdateTask();
    }

    /**
     * Schedules a debounced immediate update to merge rapid consecutive events.
     */
    public synchronized void startImmediateUpdateTask() {
        if (!discordSrvEnabled || statusUpdater == null) {
            return;
        }
        if (immediateUpdateTask != null) {
            immediateUpdateTask.cancel();
        }
        immediateUpdateTask = Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            statusUpdater.updateStatus(false, false);

            // 以完整週期延遲重設定時器，防止即時更新後立即重複觸發
            long intervalTicks = getConfig().getLong("update-interval-seconds", 30L) * 20L;
            if (intervalTicks < 200L) {
                intervalTicks = 600L;
            }
            final long delay = intervalTicks;
            Bukkit.getScheduler().runTask(this, () -> startUpdateTask(delay));

            immediateUpdateTask = null;
        }, 20L);
    }

    public StatusUpdater getStatusUpdater() {
        return statusUpdater;
    }

    public Messages getMessages() {
        return messages;
    }

    /**
     * Fills in missing config keys to ensure seamless upgrades from older versions.
     */
    private void updateConfig() {
        boolean changed = false;
        org.bukkit.configuration.file.FileConfiguration config = getConfig();

        if (!config.contains("player-line-template")) {
            config.set("player-line-template", "- [{name}]({avatar_url})");
            changed = true;
        }

        if (!config.contains("description-template")) {
            config.set("description-template", "");
            changed = true;
        }

        if (!config.contains("player-sorting")) {
            config.set("player-sorting.enabled", true);
            config.set("player-sorting.papi-weight-placeholder", "%luckperms_highest_group_weight%");
            config.set("player-sorting.permission-priority-list", java.util.Arrays.asList(
                    "group.admin",
                    "group.mod",
                    "group.vip"
            ));
            changed = true;
        } else {
            if (!config.contains("player-sorting.papi-weight-placeholder")) {
                config.set("player-sorting.papi-weight-placeholder", "%luckperms_highest_group_weight%");
                changed = true;
            }
            if (!config.contains("player-sorting.permission-priority-list")) {
                java.util.List<String> list = new java.util.ArrayList<>();
                if (config.isConfigurationSection("player-sorting.weights")) {
                    org.bukkit.configuration.ConfigurationSection weights = config.getConfigurationSection("player-sorting.weights");
                    if (weights != null) {
                        for (String key : weights.getKeys(true)) {
                            if (weights.isInt(key)) {
                                list.add(key);
                            }
                        }
                    }
                    config.set("player-sorting.weights", null);
                } else if (config.isList("player-sorting.weights")) {
                    for (String entry : config.getStringList("player-sorting.weights")) {
                        String[] parts = entry.split(":");
                        list.add(parts[0].trim());
                    }
                    config.set("player-sorting.weights", null);
                }

                if (list.isEmpty()) {
                    list.addAll(java.util.Arrays.asList("group.admin", "group.mod", "group.vip"));
                }
                config.set("player-sorting.permission-priority-list", list);
                changed = true;
            }
        }

        if (changed) {
            saveConfig();
            getLogger().info("Missing config keys have been automatically added to config.yml!");
        }
    }

    // ==============================================================================
    // Inner class for DiscordSRV API listener
    // Loaded lazily to prevent ClassNotFoundException during plugin initialization
    // ==============================================================================

    private class DiscordSrvListener {

        @github.scarsz.discordsrv.api.Subscribe
        public void discordReady(github.scarsz.discordsrv.api.events.DiscordReadyEvent event) {
            getLogger().info(messages.raw("logger.discordsrv-ready"));

            Bukkit.getScheduler().runTask(DiscordSrvStatusBridge.this, () -> {
                startUpdateTask();

                Bukkit.getScheduler().runTaskAsynchronously(DiscordSrvStatusBridge.this, () -> {
                    statusUpdater.updateStatus(false, false);
                });
            });
        }
    }
}
