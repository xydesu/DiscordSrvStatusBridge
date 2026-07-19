package me.xydesu.discordsrvstatusbridge;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * 以 100% 反射方式與 PlaceholderAPI 進行安全對接。
 * 避免因直接依賴而導致伺服器未安裝該插件時崩潰。
 *
 * 作者: xydesu
 */
public class PlaceholderAPIHook {

    private static boolean papiEnabled = false;
    private static Method setPlaceholdersMethod = null;

    static {
        try {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi != null && papi.isEnabled()) {
                ClassLoader loader = papi.getClass().getClassLoader();
                Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, loader);
                setPlaceholdersMethod = papiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                papiEnabled = true;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 替換字串中的 PlaceholderAPI 變數
     *
     * @param player 關聯的玩家，可為 null
     * @param text   含有 %placeholder% 的文字
     * @return 替換後的文字，若未啟用或發生錯誤則回傳原文字
     */
    public static String setPlaceholders(OfflinePlayer player, String text) {
        if (!papiEnabled || setPlaceholdersMethod == null || text == null || text.isEmpty()) {
            return text;
        }
        try {
            Object result = setPlaceholdersMethod.invoke(null, player, text);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Exception ignored) {}
        return text;
    }
}
