package me.xydesu.discordsrvstatusbridge;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 插件主類別，處理生命週期、事件監聽以及與 DiscordSrv 的橋接。
 *
 * 作者: xydesu
 */
public class DiscordSrvStatusBridge extends JavaPlugin implements Listener {

    private MaintenanceHook maintenanceHook;
    private StatusUpdater statusUpdater;
    private BukkitTask updateTask;
    private boolean discordSrvEnabled = false;

    private BukkitTask immediateUpdateTask = null;

    @Override
    public void onEnable() {
        // 儲存預設設定檔
        saveDefaultConfig();
        
        // 自動檢查並更新缺失的設定項
        updateConfig();

        maintenanceHook = new MaintenanceHook(this);
        statusUpdater = new StatusUpdater(this, maintenanceHook);

        // 如果重載插件時已經有玩家在線上，先載入頭像快取
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            statusUpdater.fetchAvatarAsync(player.getUniqueId(), player.getName());
        }

        // 註冊 Bukkit 事件監聽
        getServer().getPluginManager().registerEvents(this, this);

        // 檢查 DiscordSrv 是否啟用
        if (getServer().getPluginManager().isPluginEnabled("DiscordSRV")) {
            discordSrvEnabled = true;
            try {
                // 註冊 DiscordSrv API 監聽器
                github.scarsz.discordsrv.DiscordSRV.api.subscribe(new DiscordSrvListener());
                getLogger().info("已成功訂閱 DiscordSrv API 事件。");
            } catch (Throwable t) {
                getLogger().warning("訂閱 DiscordSrv API 時發生異常，將直接啟動更新任務: " + t.getMessage());
                startUpdateTask();
            }
        } else {
            getLogger().warning("未偵測到啟用的 DiscordSRV 插件。將在 DiscordSrv 載入後才執行監控。");
        }

        // 註冊管理指令
        BridgeCommand cmd = new BridgeCommand(this);
        getCommand("discordsrvstatusbridge").setExecutor(cmd);
        getCommand("discordsrvstatusbridge").setTabCompleter(cmd);

        // 註冊 Maintenance 事件監聽 (動態 EventExecutor 以防 NoClassDefFoundError)
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
                getLogger().info("已成功訂閱 Maintenance 維護狀態變更事件。");
            } catch (Throwable t) {
                getLogger().warning("訂閱 Maintenance 事件失敗: " + t.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        // 停止定時任務
        stopUpdateTask();
        if (immediateUpdateTask != null) {
            immediateUpdateTask.cancel();
        }

        // 關機時同步將 Discord 狀態更新為「已關閉」
        if (discordSrvEnabled && statusUpdater != null) {
            getLogger().info("伺服器關閉中，正在更新 Discord 狀態...");
            statusUpdater.updateStatus(true, true);
        }
    }

    /**
     * 啟動異步定時更新狀態任務，預設延遲 2 秒後首次執行
     */
    public void startUpdateTask() {
        startUpdateTask(40L); // 預設延遲 2 秒以盡快初始化狀態
    }

    /**
     * 啟動異步定時更新狀態任務，並指定首次執行的延遲 tick 數
     *
     * @param firstDelayTicks 首次執行的延遲
     */
    public void startUpdateTask(long firstDelayTicks) {
        stopUpdateTask();

        long intervalTicks = getConfig().getLong("update-interval-seconds", 30L) * 20L;
        if (intervalTicks < 200L) { // 限制最低不得低於 10 秒
            intervalTicks = 600L;
        }

        // 使用 Bukkit Scheduler 每隔指定時間更新狀態
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (statusUpdater != null) {
                statusUpdater.updateStatus(false, false);
            }
        }, firstDelayTicks, intervalTicks);

        // 僅在預設 2 秒延遲啟動時印出日誌，防止計時器重置時洗版日誌
        if (firstDelayTicks == 40L) {
            getLogger().info("已啟動伺服器狀態監控任務（更新頻率：" + (intervalTicks / 20) + " 秒）。");
        }
    }

    /**
     * 停止更新任務
     */
    public void stopUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    // ==============================================================================
    // 玩家事件處理：當玩家加入/離開時，立即觸發狀態更新
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
     * 啟動緩衝式立即更新，合併高頻觸發事件防速率限制
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
            
            // 執行完即時更新後，計算一個完整的更新週期延遲，並重設/重啟定時監控任務，以防重複更新
            long intervalTicks = getConfig().getLong("update-interval-seconds", 30L) * 20L;
            if (intervalTicks < 200L) {
                intervalTicks = 600L;
            }
            final long delay = intervalTicks;
            Bukkit.getScheduler().runTask(this, () -> startUpdateTask(delay));
            
            immediateUpdateTask = null;
        }, 20L); // 延遲 1 秒合併更新
    }

    public StatusUpdater getStatusUpdater() {
        return statusUpdater;
    }

    /**
     * 動態補全缺少的新版本設定項，確保舊版設定檔無縫升級且保留註解
     */
    private void updateConfig() {
        boolean changed = false;
        org.bukkit.configuration.file.FileConfiguration config = getConfig();

        // v1.0.0 新增: 玩家列表自訂格式
        if (!config.contains("player-line-template")) {
            config.set("player-line-template", "- [{name}]({avatar_url})");
            changed = true;
        }

        // v1.0.0 新增: 全域描述模板
        if (!config.contains("description-template")) {
            config.set("description-template", "");
            changed = true;
        }

        // v1.0.0 新增: 玩家名單權重排序
        if (!config.contains("player-sorting")) {
            config.set("player-sorting.enabled", true);
            config.set("player-sorting.weights.group.admin", 100);
            config.set("player-sorting.weights.group.mod", 50);
            config.set("player-sorting.weights.group.vip", 10);
            changed = true;
        }

        if (changed) {
            saveConfig();
            getLogger().info("已自動將缺失的新版本設定項補齊至 config.yml 中！");
        }
    }

    // ==============================================================================
    // DiscordSrv API 監聽內部類別
    // 避免在此類別初始化前載入 DiscordSrv API 類別以防止 ClassNotFoundException
    // ==============================================================================

    private class DiscordSrvListener {

        @github.scarsz.discordsrv.api.Subscribe
        public void discordReady(github.scarsz.discordsrv.api.events.DiscordReadyEvent event) {
            getLogger().info("DiscordSrv 已成功連線，開始狀態橋接。");
            
            // 在 Bukkit 同步執行緒中啟動更新排程
            Bukkit.getScheduler().runTask(DiscordSrvStatusBridge.this, () -> {
                startUpdateTask();
                
                // 立即非同步更新一次
                Bukkit.getScheduler().runTaskAsynchronously(DiscordSrvStatusBridge.this, () -> {
                    statusUpdater.updateStatus(false, false);
                });
            });
        }
    }
}
