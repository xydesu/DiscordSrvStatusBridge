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

    @Override
    public void onEnable() {
        // 儲存預設設定檔
        saveDefaultConfig();

        maintenanceHook = new MaintenanceHook(this);
        statusUpdater = new StatusUpdater(this, maintenanceHook);

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
    }

    @Override
    public void onDisable() {
        // 停止定時任務
        stopUpdateTask();

        // 關機時同步將 Discord 狀態更新為「已關閉」
        if (discordSrvEnabled && statusUpdater != null) {
            getLogger().info("伺服器關閉中，正在更新 Discord 狀態...");
            statusUpdater.updateStatus(true, true);
        }
    }

    /**
     * 啟動異步定時更新狀態任務
     */
    public void startUpdateTask() {
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
        }, 40L, intervalTicks); // 延遲 2 秒後首次執行

        getLogger().info("已啟動伺服器狀態監控任務（更新頻率：" + (intervalTicks / 20) + " 秒）。");
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
        triggerImmediateUpdate();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        triggerImmediateUpdate();
    }

    /**
     * 觸發立即的非同步更新（稍作延遲以確保資料正確性）
     */
    private void triggerImmediateUpdate() {
        if (discordSrvEnabled && statusUpdater != null) {
            // 延遲 1 秒執行更新，以確保人數計算與狀態是最新的
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                statusUpdater.updateStatus(false, false);
            }, 20L);
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
