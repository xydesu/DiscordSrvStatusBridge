package me.xydesu.discordsrvstatusbridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 用於整合 Geyser/Floodgate 插件的 Hook，安全反射獲取基岩版玩家的 XUID。
 * 防止在未安裝 Floodgate 時導致 NoSuchMethodError 或 ClassNotFoundException。
 *
 * 作者: xydesu
 */
public class FloodgateHook {

    private static boolean enabled = false;
    private static Object apiInstance = null;
    private static Method isBedrockPlayerMethod = null;
    private static Method getPlayerMethod = null;
    private static Method getXuidMethod = null;

    static {
        try {
            Plugin floodgatePlugin = Bukkit.getPluginManager().getPlugin("floodgate");
            if (floodgatePlugin != null && floodgatePlugin.isEnabled()) {
                ClassLoader loader = floodgatePlugin.getClass().getClassLoader();
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
                Method getInstanceMethod = apiClass.getMethod("getInstance");
                apiInstance = getInstanceMethod.invoke(null);

                isBedrockPlayerMethod = apiInstance.getClass().getMethod("isBedrockPlayer", UUID.class);
                getPlayerMethod = apiInstance.getClass().getMethod("getPlayer", UUID.class);
                enabled = true;
            }
        } catch (Throwable ignored) {
            enabled = false;
        }
    }

    /**
     * 判斷玩家是否為 Geyser/Floodgate 基岩版連線玩家。
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (!enabled || apiInstance == null || isBedrockPlayerMethod == null) {
            return false;
        }
        try {
            return (boolean) isBedrockPlayerMethod.invoke(apiInstance, uuid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取得基岩版玩家的 Xbox 唯一識別碼 XUID。若非基岩版玩家則回傳 null。
     */
    public static String getXuid(UUID uuid) {
        if (!enabled || apiInstance == null || getPlayerMethod == null) {
            return null;
        }
        try {
            Object player = getPlayerMethod.invoke(apiInstance, uuid);
            if (player != null) {
                if (getXuidMethod == null) {
                    getXuidMethod = player.getClass().getMethod("getXuid");
                }
                return (String) getXuidMethod.invoke(player);
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }
}
