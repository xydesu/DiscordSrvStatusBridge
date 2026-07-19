package me.xydesu.discordsrvstatusbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 處理 DiscordSrvStatusBridge 插件的管理指令。
 * 支援重新載入設定檔 (/dssb reload) 以及強制立即更新 Discord 狀態 (/dssb update)。
 *
 * 作者: xydesu
 */
public class BridgeCommand implements CommandExecutor, TabCompleter {

    private final DiscordSrvStatusBridge plugin;

    public BridgeCommand(DiscordSrvStatusBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("discordsrvstatusbridge.admin")) {
            sender.sendMessage("§c你沒有權限執行此指令！");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("§a[DiscordSrvStatusBridge] 設定檔已成功重新載入！");
            
            // 重新讀取後，強制立即非同步更新一次，並重設計時器
            if (plugin.getStatusUpdater() != null) {
                plugin.getStatusUpdater().updateStatus(false, false);
                long intervalTicks = plugin.getConfig().getLong("update-interval-seconds", 30L) * 20L;
                if (intervalTicks < 200L) {
                    intervalTicks = 600L;
                }
                plugin.startUpdateTask(intervalTicks);
            } else {
                plugin.startUpdateTask();
            }
            sender.sendMessage("§a[DiscordSrvStatusBridge] 已重新套用更新任務排程並觸發更新。");
            return true;
        } else if (sub.equals("update")) {
            sender.sendMessage("§a[DiscordSrvStatusBridge] 開始強制更新狀態...");
            if (plugin.getStatusUpdater() != null) {
                plugin.getStatusUpdater().updateStatus(false, false);
                
                // 強制更新後，計算完整週期延遲來重設/重啟定時器
                long intervalTicks = plugin.getConfig().getLong("update-interval-seconds", 30L) * 20L;
                if (intervalTicks < 200L) {
                    intervalTicks = 600L;
                }
                plugin.startUpdateTask(intervalTicks);
                
                sender.sendMessage("§a[DiscordSrvStatusBridge] 已強制觸發狀態更新並重置定時器。");
            } else {
                sender.sendMessage("§c[DiscordSrvStatusBridge] 狀態更新器未就緒！");
            }
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§b=== DiscordSrvStatusBridge 指令說明 ===");
        sender.sendMessage("§a/" + label + " reload §7- 重新載入設定檔並套用更新");
        sender.sendMessage("§a/" + label + " update §7- 強制立即更新 Discord 狀態");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("discordsrvstatusbridge.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            list.add("reload");
            list.add("update");

            List<String> suggestions = new ArrayList<>();
            for (String s : list) {
                if (s.toLowerCase().startsWith(args[0].toLowerCase())) {
                    suggestions.add(s);
                }
            }
            return suggestions;
        }

        return Collections.emptyList();
    }
}
