package me.xydesu.discordsrvstatusbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * 以反射方式與 kennytv 的 Maintenance 插件進行對接。
 * 避免因直接依賴而導致伺服器未安裝該插件時崩潰。
 *
 * 作者: xydesu
 */
public class MaintenanceHook {

    private final DiscordSrvStatusBridge plugin;

    public MaintenanceHook(DiscordSrvStatusBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * 判斷伺服器當前是否處於維護模式
     *
     * @return 處於維護中回傳 true，未啟用維護或未安裝插件回傳 false
     */
    public boolean isMaintenanceEnabled() {
        // 若設定檔中關閉了維護整合，直接回傳 false
        if (!plugin.getConfig().getBoolean("maintenance-integration", true)) {
            return false;
        }

        Plugin maintenancePlugin = Bukkit.getPluginManager().getPlugin("Maintenance");
        if (maintenancePlugin == null || !maintenancePlugin.isEnabled()) {
            return false;
        }

        try {
            // 反射載入 MaintenanceProvider 與 API 介面
            ClassLoader loader = maintenancePlugin.getClass().getClassLoader();
            Class<?> providerClass = Class.forName("eu.kennytv.maintenance.api.MaintenanceProvider", true, loader);
            Object apiInstance = providerClass.getMethod("get").invoke(null);
            
            if (apiInstance != null) {
                // 從 public 介面 eu.kennytv.maintenance.api.MaintenanceApi 取得方法，防範 JPMS 強封裝限制
                Class<?> apiClass = Class.forName("eu.kennytv.maintenance.api.MaintenanceApi", true, loader);
                Method isMaintenanceMethod = apiClass.getMethod("isMaintenance");
                Object result = isMaintenanceMethod.invoke(apiInstance);
                if (result instanceof Boolean) {
                    return (boolean) result;
                }
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "找不到 Maintenance API 類別。若有更新 API 請通知作者。");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "與 Maintenance API 互動時發生異常: " + e.getMessage());
        }
        return false;
    }
}
