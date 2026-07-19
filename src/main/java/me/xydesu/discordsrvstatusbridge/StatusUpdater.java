package me.xydesu.discordsrvstatusbridge;

import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 處理 Discord 伺服器狀態訊息的建構、發送、編輯與 Placeholders 替換。
 * 支援 JDA 4 與 JDA 5 的反射相容性設計。
 *
 * 作者: xydesu
 */
public class StatusUpdater {

    private final DiscordSrvStatusBridge plugin;
    private final MaintenanceHook maintenanceHook;
    private boolean isUpdating = false;

    public StatusUpdater(DiscordSrvStatusBridge plugin, MaintenanceHook maintenanceHook) {
        this.plugin = plugin;
        this.maintenanceHook = maintenanceHook;
    }

    /**
     * 執行狀態更新。
     *
     * @param offlineMode 是否為伺服器關閉模式。若為 true，將會強制顯示關閉狀態。
     * @param sync        是否同步執行。關機時 (onDisable) 必須使用同步執行。
     */
    public synchronized void updateStatus(boolean offlineMode, boolean sync) {
        // 防止重複更新
        if (isUpdating && !offlineMode) {
            return;
        }
        isUpdating = true;

        try {
            String channelIdInput = plugin.getConfig().getString("channel-id", "").trim();
            if (channelIdInput.isEmpty()) {
                plugin.getLogger().warning("未設定 Discord channel-id，無法更新伺服器狀態。");
                isUpdating = false;
                return;
            }

            String channelId = parseChannelId(channelIdInput);
            if (!channelId.matches("^\\d+$")) {
                plugin.getLogger().warning("設定的頻道 ID \"" + channelIdInput + "\" 不是有效的 Snowflake 格式（應為純數字或頻道連結）。");
                isUpdating = false;
                return;
            }

            // 取得 DiscordSrv 的 JDA 實例
            if (DiscordUtil.getJda() == null) {
                plugin.getLogger().fine("DiscordSrv JDA 尚未就緒，略過本次更新。");
                isUpdating = false;
                return;
            }

            TextChannel channel = DiscordUtil.getJda().getTextChannelById(channelId);
            if (channel == null) {
                plugin.getLogger().warning("找不到指定 ID 的 Discord 頻道: " + channelId);
                isUpdating = false;
                return;
            }

            // 建立狀態 Embed
            MessageEmbed embed = buildEmbed(offlineMode);
            String messageId = plugin.getConfig().getString("message-id", "").trim();

            if (offlineMode) {
                // 關機狀態強制以同步方式編輯訊息
                if (!messageId.isEmpty()) {
                    editEmbedSync(channel, messageId, embed);
                }
            } else {
                if (messageId.isEmpty()) {
                    // 發送新訊息
                    sendEmbed(channel, embed, sync);
                } else {
                    // 編輯現有訊息
                    editEmbed(channel, messageId, embed, sync);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "更新 Discord 狀態訊息時發生異常: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }

    /**
     * 建構狀態 Embed 物件。
     */
    private MessageEmbed buildEmbed(boolean offlineMode) {
        EmbedBuilder builder = new EmbedBuilder();

        // 讀取設定檔參數
        boolean isMaintenance = !offlineMode && maintenanceHook.isMaintenanceEnabled();
        String titleKey = offlineMode ? "status-offline" : (isMaintenance ? "status-maintenance" : "status-online");

        String title = plugin.getConfig().getString("embed-settings." + titleKey + ".title", "伺服器狀態");
        String colorHex = plugin.getConfig().getString("embed-settings." + titleKey + ".color", "#2ECC71");
        String maintenanceTrueText = plugin.getConfig().getString("embed-settings.maintenance-true-text", "🛠️ 維護中");
        String maintenanceFalseText = plugin.getConfig().getString("embed-settings.maintenance-false-text", "✅ 正常開放");
        String noPlayersText = plugin.getConfig().getString("embed-settings.no-players-text", "*目前沒有玩家在線上*");
        String template = plugin.getConfig().getString("embed-settings.description-template", "");

        // 設定標題與顏色
        builder.setTitle(title);
        builder.setColor(parseColor(colorHex));

        // 替換描述中的所有 Placeholders
        String description = replacePlaceholders(template, offlineMode, isMaintenance, maintenanceTrueText, maintenanceFalseText, noPlayersText);
        builder.setDescription(description);

        return builder.build();
    }

    /**
     * 解析 Placeholder
     */
    private String replacePlaceholders(String template, boolean offlineMode, boolean isMaintenance,
                                        String mTrue, String mFalse, String noPlayersText) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        // 1. 人數與版本
        String onlineCount = offlineMode ? "0" : String.valueOf(Bukkit.getOnlinePlayers().size());
        String maxCount = String.valueOf(Bukkit.getMaxPlayers());
        String serverVersion = Bukkit.getServer().getVersion();

        // 2. TPS 效能
        double[] tpsArray = Bukkit.getServer().getTPS();
        double rawTps1m = offlineMode ? 0.0 : tpsArray[0];
        double rawTps5m = offlineMode ? 0.0 : tpsArray[1];
        double rawTps15m = offlineMode ? 0.0 : tpsArray[2];

        String tps1m = String.format("%.2f", Math.min(20.0, Math.max(0.0, rawTps1m)));
        String tps5m = String.format("%.2f", Math.min(20.0, Math.max(0.0, rawTps5m)));
        String tps15m = String.format("%.2f", Math.min(20.0, Math.max(0.0, rawTps15m)));

        // 3. 系統記憶體 (RAM)
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / 1024 / 1024;
        long totalMemory = runtime.totalMemory() / 1024 / 1024;
        long freeMemory = runtime.freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;
        long ramFree = maxMemory - usedMemory;

        String ramUsedStr = offlineMode ? "0" : String.valueOf(usedMemory);
        String ramMaxStr = String.valueOf(maxMemory);
        String ramFreeStr = offlineMode ? String.valueOf(maxMemory) : String.valueOf(ramFree);

        // 4. 維護與時間狀態
        String maintenanceStatus = isMaintenance ? mTrue : mFalse;
        if (offlineMode) {
            maintenanceStatus = "❌ 關閉中";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String lastUpdated = sdf.format(new Date());

        // 5. 玩家清單頭像與純文字清單
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        String avatarApiUrl = plugin.getConfig().getString("avatar-api-url", "https://crafatar.com/avatars/{uuid}?size=32&overlay");

        String playerList;
        String playerNames;

        if (offlineMode || onlinePlayers.isEmpty()) {
            playerList = noPlayersText;
            playerNames = "None";
        } else {
            StringBuilder sb = new StringBuilder();
            for (Player player : onlinePlayers) {
                String uuidStr = player.getUniqueId().toString();
                String nameStr = player.getName();
                String url = avatarApiUrl
                        .replace("{uuid}", uuidStr)
                        .replace("{name}", nameStr);
                sb.append("- [").append(nameStr).append("](").append(url).append(")\n");
            }
            playerList = sb.toString().trim();
            playerNames = onlinePlayers.stream().map(Player::getName).collect(Collectors.joining(", "));
        }

        // 開始替換
        return template
                .replace("{online}", onlineCount)
                .replace("{max}", maxCount)
                .replace("{tps}", tps1m)
                .replace("{tps_1m}", tps1m)
                .replace("{tps_5m}", tps5m)
                .replace("{tps_15m}", tps15m)
                .replace("{ram_used}", ramUsedStr)
                .replace("{ram_max}", ramMaxStr)
                .replace("{ram_free}", ramFreeStr)
                .replace("{maintenance_status}", maintenanceStatus)
                .replace("{player_list}", playerList)
                .replace("{player_names}", playerNames)
                .replace("{server_version}", serverVersion)
                .replace("{last_updated}", lastUpdated);
    }

    /**
     * 解析 Hex 顏色值
     */
    private int parseColor(String hex) {
        try {
            if (hex.startsWith("#")) {
                return Integer.parseInt(hex.substring(1), 16);
            }
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0x2ECC71; // 預設綠色
        }
    }

    // ==============================================================================
    // 以下為 JDA 4 與 JDA 5 反射相容性發送與編輯邏輯
    // ==============================================================================

    /**
     * 發送新訊息，並將生成的訊息 ID 寫入 config。
     */
    private void sendEmbed(TextChannel channel, MessageEmbed embed, boolean sync) {
        try {
            Object action;
            try {
                // JDA 5 呼叫 sendMessageEmbeds(MessageEmbed, MessageEmbed...)
                java.lang.reflect.Method method = channel.getClass().getMethod("sendMessageEmbeds", MessageEmbed[].class);
                action = method.invoke(channel, (Object) new MessageEmbed[]{embed});
            } catch (NoSuchMethodException e) {
                // JDA 4 呼叫 sendMessage(MessageEmbed)
                java.lang.reflect.Method method = channel.getClass().getMethod("sendMessage", MessageEmbed.class);
                action = method.invoke(channel, embed);
            }

            // 定義發送成功的回傳處理
            java.util.function.Consumer<Message> successConsumer = message -> {
                String newId = message.getId();
                plugin.getConfig().set("message-id", newId);
                plugin.saveConfig();
                plugin.getLogger().info("已發送新的狀態訊息並記錄 ID: " + newId);
            };

            if (sync) {
                // 同步執行 (complete)
                Object msgObj = action.getClass().getMethod("complete").invoke(action);
                if (msgObj instanceof Message) {
                    successConsumer.accept((Message) msgObj);
                }
            } else {
                // 異步執行 (queue)
                action.getClass().getMethod("queue", java.util.function.Consumer.class).invoke(action, successConsumer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "發送 Discord 狀態 Embed 失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 編輯已存在的狀態訊息。若該訊息不存在，則清除設定檔中的 ID 並在下一次更新時重新發送。
     */
    private void editEmbed(TextChannel channel, String messageId, MessageEmbed embed, boolean sync) {
        // 先取得/擷取訊息
        try {
            Object retrieveAction = channel.getClass().getMethod("retrieveMessageById", String.class).invoke(channel, messageId);

            java.util.function.Consumer<Message> successConsumer = message -> {
                // 取得訊息成功後進行編輯
                performEdit(message, embed, false);
            };

            java.util.function.Consumer<Throwable> failureConsumer = throwable -> {
                plugin.getLogger().warning("無法取得訊息 ID " + messageId + "，可能已被刪除。將清除 ID 並於下次更新重新發送。");
                plugin.getConfig().set("message-id", "");
                plugin.saveConfig();
            };

            if (sync) {
                try {
                    Object msgObj = retrieveAction.getClass().getMethod("complete").invoke(retrieveAction);
                    if (msgObj instanceof Message) {
                        successConsumer.accept((Message) msgObj);
                    }
                } catch (Exception ex) {
                    failureConsumer.accept(ex);
                }
            } else {
                retrieveAction.getClass().getMethod("queue", java.util.function.Consumer.class, java.util.function.Consumer.class)
                        .invoke(retrieveAction, successConsumer, failureConsumer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "透過 API 取得 Discord 訊息失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 關機時使用的同步編輯訊息（防止 JVM 提前關閉導致請求中斷）。
     */
    private void editEmbedSync(TextChannel channel, String messageId, MessageEmbed embed) {
        try {
            Object retrieveAction = channel.getClass().getMethod("retrieveMessageById", String.class).invoke(channel, messageId);
            Object msgObj = retrieveAction.getClass().getMethod("complete").invoke(retrieveAction);
            if (msgObj instanceof Message) {
                performEdit((Message) msgObj, embed, true);
                plugin.getLogger().info("已同步更新 Discord 狀態為「已關閉」");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "同步編輯 Discord 關機訊息失敗: " + e.getMessage());
        }
    }

    /**
     * 真正執行訊息編輯
     */
    private void performEdit(Message message, MessageEmbed embed, boolean sync) {
        try {
            Object editAction;
            try {
                // JDA 5 呼叫 editMessageEmbeds(MessageEmbed...)
                java.lang.reflect.Method method = message.getClass().getMethod("editMessageEmbeds", MessageEmbed[].class);
                editAction = method.invoke(message, (Object) new MessageEmbed[]{embed});
            } catch (NoSuchMethodException e) {
                // JDA 4 呼叫 editMessage(MessageEmbed)
                java.lang.reflect.Method method = message.getClass().getMethod("editMessage", MessageEmbed.class);
                editAction = method.invoke(message, embed);
            }

            if (sync) {
                editAction.getClass().getMethod("complete").invoke(editAction);
            } else {
                editAction.getClass().getMethod("queue").invoke(editAction);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "執行 Discord 訊息編輯反射調用失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 從設定的字串中解析出正確的 Discord 頻道 Snowflake ID
     * 支援解析完整的 Discord 頻道 URL，如 https://discord.com/channels/guild/channel
     */
    private String parseChannelId(String input) {
        if (input == null) {
            return "";
        }
        input = input.trim();
        if (input.isEmpty()) {
            return "";
        }

        // 若輸入為完整網址，取得最後一節
        if (input.contains("/")) {
            String[] parts = input.split("/");
            input = parts[parts.length - 1].trim();
        }

        // 移除網址後方的參數
        if (input.contains("?")) {
            input = input.split("\\?")[0].trim();
        }

        return input;
    }
}
