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
     * 過濾字串中的 Minecraft 傳統色碼與常見 MiniMessage 標籤
     */
    private static String stripColors(String text) {
        if (text == null) return null;
        // 移除傳統 &a 和 §a
        text = text.replaceAll("(?i)[&§][0-9a-fk-or]", "");
        // 移除 Hex 傳統格式如 &#ffffff 或 &x&f&f&f&f&f&f (BungeeCord / Spigot hex)
        text = text.replaceAll("(?i)[&§]x([&§][0-9a-f]){6}", "");
        text = text.replaceAll("(?i)[&§]#[0-9a-f]{6}", "");
        // 移除常見 MiniMessage 標籤
        text = text.replaceAll("(?i)<(/)?(color:[^>]+|c:[^>]+|#[0-9a-f]{6}|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|obfuscated|bold|strikethrough|underline|italic|reset|font:[^>]+|click:[^>]+|hover:[^>]+)>", "");
        return text;
    }

    /**
     * 替換字串中的 PlaceholderAPI 變數，並過濾顏色代碼
     *
     * @param player 關聯的玩家，可為 null
     * @param text   含有 %placeholder% 的文字
     * @return 替換且過濾後的文字，若未啟用或發生錯誤則回傳過濾後的原文字
     */
    public static String setPlaceholders(OfflinePlayer player, String text) {
        if (!papiEnabled || setPlaceholdersMethod == null || text == null || text.isEmpty()) {
            return stripColors(text);
        }
        try {
            Object result = setPlaceholdersMethod.invoke(null, player, text);
            if (result instanceof String) {
                return stripColors((String) result);
            }
        } catch (Exception ignored) {}
        return stripColors(text);
    }
}
