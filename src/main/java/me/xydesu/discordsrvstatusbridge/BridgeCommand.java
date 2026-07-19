package me.xydesu.discordsrvstatusbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles admin commands for DiscordSrvStatusBridge.
 * Supports config reload (/dssb reload) and forced status update (/dssb update).
 *
 * Author: xydesu
 */
public class BridgeCommand implements CommandExecutor, TabCompleter {

    private final DiscordSrvStatusBridge plugin;

    public BridgeCommand(DiscordSrvStatusBridge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.getMessages();

        if (!sender.hasPermission("discordsrvstatusbridge.admin")) {
            sender.sendMessage(msg.get("command.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            plugin.reloadConfig();
            plugin.getMessages().load();
            sender.sendMessage(msg.get("command.reload-success"));

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
            sender.sendMessage(msg.get("command.reload-task-reset"));
            return true;
        } else if (sub.equals("update")) {
            sender.sendMessage(msg.get("command.update-start"));
            if (plugin.getStatusUpdater() != null) {
                plugin.getStatusUpdater().updateStatus(false, false);

                // 強制更新後，計算完整週期延遲來重設/重啟定時器
                long intervalTicks = plugin.getConfig().getLong("update-interval-seconds", 30L) * 20L;
                if (intervalTicks < 200L) {
                    intervalTicks = 600L;
                }
                plugin.startUpdateTask(intervalTicks);

                sender.sendMessage(msg.get("command.update-success"));
            } else {
                sender.sendMessage(msg.get("command.update-not-ready"));
            }
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        Messages msg = plugin.getMessages();
        sender.sendMessage(msg.get("command.help-header"));
        sender.sendMessage(msg.get("command.help-reload", "label", label));
        sender.sendMessage(msg.get("command.help-update", "label", label));
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
