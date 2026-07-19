package me.xydesu.discordsrvstatusbridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * 用於整合 SuperVanish, PremiumVanish, CMI 以及 EssentialsX 等隱身插件的 Hook。
 * 以 100% 反射方式進行相容判定，防止在未安裝這些插件的伺服器中引發 ClassNotFoundException。
 *
 * 作者: xydesu
 */
public class VanishHook {

    private static boolean superVanishEnabled = false;
    private static Method superVanishIsInvisibleMethod = null;

    private static boolean cmiEnabled = false;
    private static Object cmiPlayerManager = null;
    private static Method cmiGetUserMethod = null;
    private static Method cmiIsVanishedMethod = null;

    static {
        // 1. 初始化 SuperVanish / PremiumVanish API 反射
        try {
            Plugin vanishPlugin = Bukkit.getPluginManager().getPlugin("SuperVanish");
            if (vanishPlugin == null) {
                vanishPlugin = Bukkit.getPluginManager().getPlugin("PremiumVanish");
            }
            if (vanishPlugin != null && vanishPlugin.isEnabled()) {
                ClassLoader loader = vanishPlugin.getClass().getClassLoader();
                Class<?> apiClass = Class.forName("de.myzelyam.api.vanish.VanishAPI", true, loader);
                superVanishIsInvisibleMethod = apiClass.getMethod("isInvisible", Player.class);
                superVanishEnabled = true;
            }
        } catch (Throwable ignored) {}

        // 2. CMI Vanish API 反射
        try {
            Plugin cmiPlugin = Bukkit.getPluginManager().getPlugin("CMI");
            if (cmiPlugin != null && cmiPlugin.isEnabled()) {
                ClassLoader loader = cmiPlugin.getClass().getClassLoader();
                Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI", true, loader);
                Object cmiInstance = cmiClass.getMethod("getInstance").invoke(null);

                Method getPlayerManagerMethod = cmiInstance.getClass().getMethod("getPlayerManager");
                cmiPlayerManager = getPlayerManagerMethod.invoke(cmiInstance);

                cmiGetUserMethod = cmiPlayerManager.getClass().getMethod("getUser", Player.class);

                Class<?> cmiUserClass = Class.forName("com.Zrips.CMI.containers.CMIUser", true, loader);
                cmiIsVanishedMethod = cmiUserClass.getMethod("isVanished");

                cmiEnabled = true;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 判斷玩家當前是否處於隱身狀態
     *
     * @param player 目標玩家
     * @return 隱身中回傳 true，否則回傳 false
     */
    public static boolean isVanished(Player player) {
        if (player == null) {
            return false;
        }

        // 1. 優先檢查標準 Bukkit/EssentialsX/SuperVanish 設置的 "vanished" Metadata 元數據 (最通用)
        if (player.hasMetadata("vanished")) {
            for (MetadataValue value : player.getMetadata("vanished")) {
                if (value.asBoolean()) {
                    return true;
                }
            }
        }

        // 2. 檢查 SuperVanish / PremiumVanish API
        if (superVanishEnabled && superVanishIsInvisibleMethod != null) {
            try {
                return (boolean) superVanishIsInvisibleMethod.invoke(null, player);
            } catch (Exception ignored) {}
        }

        // 3. 檢查 CMI API
        if (cmiEnabled && cmiPlayerManager != null && cmiGetUserMethod != null && cmiIsVanishedMethod != null) {
            try {
                Object cmiUser = cmiGetUserMethod.invoke(cmiPlayerManager, player);
                if (cmiUser != null) {
                    return (boolean) cmiIsVanishedMethod.invoke(cmiUser);
                }
            } catch (Exception ignored) {}
        }

        // 4. 退回檢查 Bukkit 原生隱形效能 (Java 1.16+ 引入)
        try {
            if (player.isInvisible()) {
                return true;
            }
        } catch (NoSuchMethodError ignored) {}

        return false;
    }
}
