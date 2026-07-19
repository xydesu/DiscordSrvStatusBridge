package me.xydesu.discordsrvstatusbridge;

import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Queue;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 處理 Discord 伺服器狀態訊息的建構、發送、編輯與 Placeholders 替換。
 * 專為 JDA 4 設計，支援動態下載玩家頭像並在記憶體中快取，並將多位玩家的頭像拼接為單張圖片發送。
 *
 * 作者: xydesu
 */
public class StatusUpdater {

    private final DiscordSrvStatusBridge plugin;
    private final MaintenanceHook maintenanceHook;
    private boolean isUpdating = false;
    private boolean firstUpdate = true;
    private boolean isSendingNewMessage = false;

    // 頭像快取
    private final Map<UUID, BufferedImage> avatarCache = new ConcurrentHashMap<>();
    private final Set<UUID> downloadingAvatars = ConcurrentHashMap.newKeySet();
    private BufferedImage defaultAvatar = null;

    public StatusUpdater(DiscordSrvStatusBridge plugin, MaintenanceHook maintenanceHook) {
        this.plugin = plugin;
        this.maintenanceHook = maintenanceHook;
    }

    /**
     * 透過 DiscordUtil 的 ClassLoader 安全加載類別，防止 Bukkit 與 JDA 之間的 ClassLoader 隔離問題
     */
    private Class<?> getJdaClass(String className) throws ClassNotFoundException {
        return Class.forName(className, true, DiscordUtil.class.getClassLoader());
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

            if (firstUpdate && !offlineMode) {
                firstUpdate = false;
                detectAndCleanDuplicateMessages(channel);
            }

            // 檢查是否有線上玩家的頭像正在非同步下載中，如果是，則暫緩此次更新，等候下載完成後由 callback 自動驅動刷新
            boolean isAnyAvatarDownloading = false;
            for (Player p : getVisiblePlayers()) {
                if (!avatarCache.containsKey(p.getUniqueId()) && downloadingAvatars.contains(p.getUniqueId())) {
                    isAnyAvatarDownloading = true;
                    break;
                }
            }
            if (isAnyAvatarDownloading && !offlineMode) {
                plugin.getLogger().fine("有線上玩家頭像下載中，暫緩 Discord 狀態更新，等候下載完成後自動刷新...");
                isUpdating = false;
                return;
            }

            // 生成拼接頭像圖片 (若離線或無玩家則為 null)
            byte[] imageBytes = offlineMode ? null : generatePlayersImage();

            // 建立狀態 Embed (設定是否附加大圖)
            MessageEmbed embed = buildEmbed(offlineMode, imageBytes != null);
            String messageId = plugin.getConfig().getString("message-id", "").trim();

            if (offlineMode) {
                // 關機狀態強制以同步方式編輯訊息 (此時無附件)
                if (!messageId.isEmpty()) {
                    editEmbedSync(channel, messageId, embed);
                }
            } else {
                if (messageId.isEmpty()) {
                    if (isSendingNewMessage) {
                        plugin.getLogger().fine("目前已有狀態訊息正在發送中，暫緩此次發送。");
                        isUpdating = false;
                        return;
                    }
                    isSendingNewMessage = true;
                    // 發送新訊息（包含圖片附件）
                    sendEmbedWithAttachment(channel, imageBytes, embed, sync);
                } else {
                    // 編輯現有訊息（包含圖片附件）
                    editEmbedWithAttachment(channel, messageId, imageBytes, embed, sync);
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
    private MessageEmbed buildEmbed(boolean offlineMode, boolean hasAttachment) {
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

        // 如果包含頭像拼接圖片，設定引用附件圖片
        if (hasAttachment) {
            builder.setImage("attachment://players.png");
        }

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
        List<Player> onlinePlayers = getVisiblePlayers();
        String onlineCount = offlineMode ? "0" : String.valueOf(onlinePlayers.size());
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

        // 5. 玩家清單與純文字清單 (設有前 15 名玩家截斷防護)
        String avatarApiUrl = plugin.getConfig().getString("avatar-api-url", "https://minotar.net/helm/{uuid}/32.png");

        String playerList;
        String playerNames;

        if (offlineMode || onlinePlayers.isEmpty()) {
            playerList = noPlayersText;
            playerNames = "None";
        } else {
            int limit = 15;
            int total = onlinePlayers.size();
            List<? extends Player> playerListCopy = new ArrayList<>(onlinePlayers);

            String lineTemplate = plugin.getConfig().getString("player-line-template", "- [{name}]({avatar_url})");

            // 處理 {player_list} (Markdown 帶頭像網址清單)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(limit, total); i++) {
                Player player = playerListCopy.get(i);
                String uuidStr = player.getUniqueId().toString();
                String nameStr = player.getName();
                String url = avatarApiUrl
                        .replace("{uuid}", uuidStr)
                        .replace("{name}", nameStr);
                
                String playerLine = lineTemplate
                        .replace("{name}", nameStr)
                        .replace("{uuid}", uuidStr)
                        .replace("{avatar_url}", url)
                        .replace("{display_name}", player.getDisplayName());
                
                // 支援 PlaceholderAPI 變數替換
                playerLine = PlaceholderAPIHook.setPlaceholders(player, playerLine);
                sb.append(playerLine).append("\n");
            }
            if (total > limit) {
                sb.append("- *...以及其他 ").append(total - limit).append(" 位玩家*");
            }
            playerList = sb.toString().trim();

            // 處理 {player_names} (純文字清單，防溢出)
            List<String> names = playerListCopy.stream()
                    .limit(limit)
                    .map(Player::getName)
                    .collect(Collectors.toList());
            if (total > limit) {
                playerNames = String.join(", ", names) + " ... (共 " + total + " 人)";
            } else {
                playerNames = String.join(", ", names);
            }
        }

        // 開始替換
        String replaced = template
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

        // 支援 PlaceholderAPI 全域變數替換
        org.bukkit.OfflinePlayer papiContext = onlinePlayers.isEmpty() ? null : onlinePlayers.get(0);
        return PlaceholderAPIHook.setPlaceholders(papiContext, replaced);
    }

    /**
     * 拼接線上玩家頭像成一張大圖。
     *
     * @return 圖片的 byte 陣列，若無玩家則回傳 null
     */
    private byte[] generatePlayersImage() {
        List<Player> onlinePlayers = getVisiblePlayers();
        if (onlinePlayers.isEmpty()) {
            return null;
        }

        // 確保下載中
        for (Player player : onlinePlayers) {
            fetchAvatarAsync(player.getUniqueId(), player.getName());
        }

        // 限制最多拼接 30 位玩家的頭像，防止圖片巨大與效能耗損
        int maxAvatars = 30;
        int totalPlayers = onlinePlayers.size();

        List<BufferedImage> images = new ArrayList<>();
        int count = 0;

        for (Player player : onlinePlayers) {
            if (count >= maxAvatars) {
                break;
            }
            // 若超過 29 且總人數大於 30，最後一個位置繪製「+X」方塊
            if (count == maxAvatars - 1 && totalPlayers > maxAvatars) {
                images.add(createMorePlayersAvatar(totalPlayers - (maxAvatars - 1)));
                break;
            }

            BufferedImage avatar = avatarCache.get(player.getUniqueId());
            if (avatar == null) {
                avatar = getDefaultAvatar();
            }
            images.add(avatar);
            count++;
        }

        if (images.isEmpty()) {
            return null;
        }

        // 網格排版：每排最多 10 個頭像
        int columns = Math.min(10, images.size());
        int rows = (int) Math.ceil((double) images.size() / 10.0);

        int avatarSize = 32;
        int gap = 2; // 頭像間距

        int width = columns * avatarSize + (columns - 1) * gap;
        int height = rows * avatarSize + (rows - 1) * gap;

        BufferedImage combined = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = combined.createGraphics();

        // 啟用高品質渲染抗鋸齒
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (int i = 0; i < images.size(); i++) {
            int r = i / 10;
            int c = i % 10;
            int x = c * (avatarSize + gap);
            int y = r * (avatarSize + gap);
            g.drawImage(images.get(i), x, y, null);
        }

        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(combined, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "生成玩家頭像拼接圖片時發生異常: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 繪製「+X」文字頭像方塊。
     */
    private BufferedImage createMorePlayersAvatar(int remainingCount) {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 深灰色背景
        g.setColor(new Color(47, 49, 54));
        g.fillRect(0, 0, size, size);

        // 繪製文字
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 12));
        String text = "+" + remainingCount;
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getAscent();
        int x = (size - textWidth) / 2;
        int y = (size + textHeight) / 2 - 2;
        g.drawString(text, x, y);

        g.dispose();
        return img;
    }

    /**
     * 建立簡約風格的預設頭像
     */
    private BufferedImage createDefaultAvatar() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // 灰色背景
        g.setColor(new Color(100, 110, 120));
        g.fillRect(0, 0, size, size);

        // 簡約人像剪影
        g.setColor(new Color(220, 225, 230));
        g.fillRect(10, 6, 12, 10);  // 頭
        g.fillRect(6, 18, 20, 10);  // 肩膀

        // 眼睛
        g.setColor(new Color(60, 70, 80));
        g.fillRect(11, 9, 3, 2);
        g.fillRect(18, 9, 3, 2);

        g.dispose();
        return img;
    }

    private synchronized BufferedImage getDefaultAvatar() {
        if (defaultAvatar == null) {
            defaultAvatar = createDefaultAvatar();
        }
        return defaultAvatar;
    }

    /**
     * 非同步下載玩家頭像並放入快取中。
     */
    public void fetchAvatarAsync(UUID uuid, String name) {
        if (avatarCache.containsKey(uuid) || !downloadingAvatars.add(uuid)) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String avatarUrlTemplate = plugin.getConfig().getString("avatar-api-url", "https://minotar.net/helm/{uuid}/32.png");
                
                String urlStr;
                boolean isBedrock = FloodgateHook.isBedrockPlayer(uuid);
                String xuid = isBedrock ? FloodgateHook.getXuid(uuid) : null;

                if (isBedrock && xuid != null) {
                    // 基岩版玩家：直接抓取 Geyser 官方 Xbox 頭像 API
                    urlStr = "https://api.geysermc.org/v2/avatar/" + xuid;
                } else if (uuid.version() == 3 && avatarUrlTemplate.contains("{uuid}")) {
                    // 離線模式 UUID (version == 3) 回退至 Minotar 以名稱獲取頭像
                    urlStr = "https://minotar.net/avatar/" + name + "/32.png";
                } else {
                    urlStr = avatarUrlTemplate
                            .replace("{uuid}", uuid.toString())
                            .replace("{name}", name);
                }

                BufferedImage img = downloadImage(urlStr);
                
                // 如果失敗且先前不是使用 minotar，則嘗試使用 Minotar (Name) 作為備用回退管道
                if (img == null && !urlStr.contains("minotar.net")) {
                    // 對名稱進行清理，過濾掉基岩版前綴（例如 . 或 *）防止 API 404
                    String cleanName = name.replaceAll("^[^a-zA-Z0-9_]+", "");
                    urlStr = "https://minotar.net/avatar/" + cleanName + "/32.png";
                    img = downloadImage(urlStr);
                }

                if (img != null) {
                    // 縮放到 32x32 規格
                    BufferedImage resized = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = resized.createGraphics();
                    g.drawImage(img, 0, 0, 32, 32, null);
                    g.dispose();

                    avatarCache.put(uuid, resized);

                    // 下載完成，觸發立即的緩衝更新排程以在 Discord 重建圖片
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (plugin.isEnabled()) {
                            plugin.startImmediateUpdateTask();
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().warning("下載玩家 " + name + " (" + uuid + ") 的頭像失敗: " + e.getMessage());
            } finally {
                downloadingAvatars.remove(uuid);
            }
        });
    }

    private BufferedImage downloadImage(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (InputStream in = conn.getInputStream()) {
                    return ImageIO.read(in);
                }
            } else {
                plugin.getLogger().warning("頭像伺服器回傳錯誤碼 " + responseCode + " (" + urlStr + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("無法連接頭像伺服器 (" + urlStr + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * 移除頭像快取以節省記憶體
     */
    public void removeAvatarFromCache(UUID uuid) {
        avatarCache.remove(uuid);
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

    // ==============================================================================
    // 以下為 JDA 4 反射相容的發送與編輯大圖附件邏輯 (不含 JDA 5 冗餘分支)
    // ==============================================================================

    /**
     * 發送帶有附件的狀態訊息，並將訊息 ID 回寫設定檔。
     */
    private void sendEmbedWithAttachment(TextChannel channel, byte[] bytes, MessageEmbed embed, boolean sync) {
        try {
            Object action;
            if (bytes != null) {
                // JDA 4 TEXT CHANNEL sendFile 必須包含 AttachmentOption... 變長參數
                Class<?> optionClass = getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.AttachmentOption");
                Object optionArray = java.lang.reflect.Array.newInstance(optionClass, 0); // 傳入空陣列
                
                Method sendFileMethod = channel.getClass().getMethod("sendFile", byte[].class, String.class, optionArray.getClass());
                Object act = sendFileMethod.invoke(channel, bytes, "players.png", optionArray);
                action = act.getClass().getMethod("embed", MessageEmbed.class).invoke(act, embed);
            } else {
                action = channel.getClass().getMethod("sendMessage", MessageEmbed.class).invoke(channel, embed);
            }

            java.util.function.Consumer<Message> successConsumer = message -> {
                String newId = message.getId();
                plugin.getConfig().set("message-id", newId);
                plugin.saveConfig();
                plugin.getLogger().fine("已發送新的狀態訊息並記錄 ID: " + newId);
                isSendingNewMessage = false; // 發送成功，解鎖
            };

            java.util.function.Consumer<Throwable> failureConsumer = throwable -> {
                plugin.getLogger().warning("非同步發送狀態訊息失敗: " + throwable.getMessage());
                isSendingNewMessage = false; // 發送失敗，解鎖
            };

            if (sync) {
                try {
                    Object msgObj = action.getClass().getMethod("complete").invoke(action);
                    if (msgObj instanceof Message) {
                        successConsumer.accept((Message) msgObj);
                    }
                } catch (Exception ex) {
                    failureConsumer.accept(ex);
                }
            } else {
                action.getClass().getMethod("queue", java.util.function.Consumer.class, java.util.function.Consumer.class)
                        .invoke(action, successConsumer, failureConsumer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "發送帶有附件的 Discord 狀態 Embed 失敗: " + e.getMessage(), e);
            isSendingNewMessage = false; // 異常，解鎖
        }
    }

    /**
     * 編輯已存在的狀態訊息，支援更新圖片附件。
     */
    private void editEmbedWithAttachment(TextChannel channel, String messageId, byte[] bytes, MessageEmbed embed, boolean sync) {
        try {
            Object retrieveAction = channel.getClass().getMethod("retrieveMessageById", String.class).invoke(channel, messageId);

            java.util.function.Consumer<Message> successConsumer = message -> {
                performEditWithAttachment(message, bytes, embed, sync, channel);
            };

            java.util.function.Consumer<Throwable> failureConsumer = throwable -> {
                plugin.getLogger().warning("無法取得訊息 ID " + messageId + "，將清除 ID 並於下次更新重新發送。");
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
            plugin.getLogger().log(Level.SEVERE, "取得 Discord 訊息失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 執行帶有附件的訊息編輯反射動作。JDA 4 無法修改附件時會回退為「刪除舊訊息重新發送」。
     */
    private void performEditWithAttachment(Message message, byte[] bytes, MessageEmbed embed, boolean sync, TextChannel channel) {
        try {
            if (bytes != null) {
                // JDA 4 不支援編輯時更換/上傳附件，手動拋出以進入 JDA 4 回退刪除重建機制
                throw new NoSuchMethodException("JDA 4 不支援編輯附件");
            } else {
                // 無附件的編輯，直接更新 Embed 內容即可
                Object editAction = message.getClass().getMethod("editMessage", MessageEmbed.class).invoke(message, embed);
                if (sync) {
                    editAction.getClass().getMethod("complete").invoke(editAction);
                } else {
                    editAction.getClass().getMethod("queue").invoke(editAction);
                }
            }
        } catch (NoSuchMethodException e) {
            // JDA 4 附件更新回退方案：刪除舊訊息並發送新訊息
            deleteAndRecreate(message, bytes, embed, sync, channel);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "執行 Discord 編輯失敗: " + e.getMessage(), e);
        }
    }

    private void deleteAndRecreate(Message message, byte[] bytes, MessageEmbed embed, boolean sync, TextChannel channel) {
        plugin.getLogger().fine("目前環境不支援編輯附件（JDA 4），將以「刪除舊訊息並發送新訊息」方式更新圖片...");

        java.util.function.Consumer<Void> deleteSuccess = v -> {
            sendEmbedWithAttachment(channel, bytes, embed, sync);
        };

        try {
            Object deleteAction = message.getClass().getMethod("delete").invoke(message);
            if (sync) {
                deleteAction.getClass().getMethod("complete").invoke(deleteAction);
                deleteSuccess.accept(null);
            } else {
                deleteAction.getClass().getMethod("queue", java.util.function.Consumer.class).invoke(deleteAction, deleteSuccess);
            }
        } catch (Exception ex) {
            // 刪除失敗時直接發送新訊息
            sendEmbedWithAttachment(channel, bytes, embed, sync);
        }
    }

    private void editEmbedSync(TextChannel channel, String messageId, MessageEmbed embed) {
        try {
            Object retrieveAction = channel.getClass().getMethod("retrieveMessageById", String.class).invoke(channel, messageId);
            Object msgObj = retrieveAction.getClass().getMethod("complete").invoke(retrieveAction);
            if (msgObj instanceof Message) {
                Message message = (Message) msgObj;
                try {
                    // 同步刪除舊有附帶圖片的狀態訊息
                    Object deleteAction = message.getClass().getMethod("delete").invoke(message);
                    deleteAction.getClass().getMethod("complete").invoke(deleteAction);
                } catch (Exception ignored) {
                }
                // 同步發送全新的不帶附件的關機狀態訊息，確保大圖徹底消失
                sendEmbedWithAttachment(channel, null, embed, true);
                plugin.getLogger().info("已同步重發 Discord 狀態為「已關閉」以清除頭像大圖");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "同步編輯 Discord 關機訊息失敗: " + e.getMessage());
        }
    }

    /**
     * 取得玩家在名單中的排序權重
     */
    private int getPlayerWeight(Player player) {
        if (!plugin.getConfig().getBoolean("player-sorting.enabled", false)) {
            return 0;
        }

        java.util.List<String> list = plugin.getConfig().getStringList("player-sorting.permission-priority-list");
        if (list == null || list.isEmpty()) return 0;

        int maxWeight = 0;
        int size = list.size();
        for (int i = 0; i < size; i++) {
            String perm = list.get(i).trim();
            if (player.hasPermission(perm)) {
                int w = size - i; // 越上方 (index 越小) 權重越大
                if (w > maxWeight) {
                    maxWeight = w;
                }
            }
        }
        return maxWeight;
    }

    private List<Player> getVisiblePlayers() {
        List<Player> visible = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!VanishHook.isVanished(player)) {
                visible.add(player);
            }
        }

        // 依照玩家權重排序 (由大到小)；權重相同則以名稱字母排序 (A-Z)
        visible.sort((p1, p2) -> {
            int w1 = getPlayerWeight(p1);
            int w2 = getPlayerWeight(p2);
            if (w1 != w2) {
                return Integer.compare(w2, w1);
            }
            return p1.getName().compareToIgnoreCase(p2.getName());
        });

        return visible;
    }

    /**
     * 偵測並清理頻道中的重複舊狀態訊息。
     * 若發現舊有狀態訊息但設定檔中無記錄，則自動綁定；若有多條，則保留一條並刪除其餘重複項。
     */
    private void detectAndCleanDuplicateMessages(TextChannel channel) {
        try {
            plugin.getLogger().info("正在掃描頻道中的歷史狀態訊息以防止重複發送...");
            
            // 1. 獲取 MessageHistory
            Object historyObj = channel.getClass().getMethod("getHistory").invoke(channel);
            // 2. 呼叫 retrievePast(50)
            Object retrieveAction = historyObj.getClass().getMethod("retrievePast", int.class).invoke(historyObj, 50);
            // 3. 執行 complete()
            Object messagesListObj = retrieveAction.getClass().getMethod("complete").invoke(retrieveAction);
            
            if (!(messagesListObj instanceof List)) {
                return;
            }
            
            List<?> messages = (List<?>) messagesListObj;
            String selfId = channel.getJDA().getSelfUser().getId();
            
            // 讀取當前設定檔記錄的 ID
            String currentSavedId = plugin.getConfig().getString("message-id", "").trim();
            
            // 用於保存符合我們特徵的訊息
            List<Message> matchingMessages = new ArrayList<>();
            
            // 讀取設定檔中的 Title 特徵
            String onlineTitle = plugin.getConfig().getString("embed-settings.status-online.title", "伺服器運作中");
            String maintenanceTitle = plugin.getConfig().getString("embed-settings.status-maintenance.title", "伺服器維護中");
            String offlineTitle = plugin.getConfig().getString("embed-settings.status-offline.title", "伺服器已關閉");
            
            for (Object msgObj : messages) {
                if (!(msgObj instanceof Message)) {
                    continue;
                }
                Message msg = (Message) msgObj;
                
                // 必須是 Bot 自己發送的
                if (!msg.getAuthor().getId().equals(selfId)) {
                    continue;
                }
                
                // 檢查是否符合我們的狀態訊息 Embed 特徵
                List<MessageEmbed> embeds = msg.getEmbeds();
                if (embeds == null || embeds.isEmpty()) {
                    continue;
                }
                
                MessageEmbed embed = embeds.get(0);
                String title = embed.getTitle();
                String desc = embed.getDescription();
                
                if (title == null) {
                    continue;
                }
                
                // 只要 Title 包含設定檔中定義的運作中、維護中、或關閉中標題，即判定為本插件的狀態訊息
                boolean matchesTitle = title.contains(onlineTitle) || title.contains(maintenanceTitle) || title.contains(offlineTitle);
                boolean matchesDesc = desc != null && (desc.contains("線上人數") || desc.contains("系統效能") || desc.contains("最後更新時間"));
                
                if (matchesTitle || matchesDesc) {
                    matchingMessages.add(msg);
                }
            }
            
            if (matchingMessages.isEmpty()) {
                plugin.getLogger().info("掃描完成：未發現任何舊有狀態訊息。");
                return;
            }
            
            plugin.getLogger().info("掃描完成：發現 " + matchingMessages.size() + " 條舊狀態訊息。");
            
            Message messageToKeep = null;
            
            // 1. 如果當前有儲存的 ID，且該 ID 還存在於匹配列表中，則保留它
            if (!currentSavedId.isEmpty()) {
                for (Message msg : matchingMessages) {
                    if (msg.getId().equals(currentSavedId)) {
                        messageToKeep = msg;
                        break;
                    }
                }
            }
            
            // 2. 如果沒有儲存的 ID，或者儲存的 ID 沒在列表中，則選擇最新的一條作為保留對象並綁定之
            if (messageToKeep == null) {
                messageToKeep = matchingMessages.get(0); // 歷史訊息通常是從新到舊排序
                String newBindId = messageToKeep.getId();
                plugin.getConfig().set("message-id", newBindId);
                plugin.saveConfig();
                plugin.getLogger().info("已自動對接並綁定偵測到的最新狀態訊息 ID: " + newBindId);
            }
            
            // 3. 將其餘所有重複的狀態訊息通通刪除
            int deletedCount = 0;
            for (Message msg : matchingMessages) {
                if (msg.getId().equals(messageToKeep.getId())) {
                    continue;
                }
                try {
                    Object deleteAction = msg.getClass().getMethod("delete").invoke(msg);
                    deleteAction.getClass().getMethod("complete").invoke(deleteAction);
                    deletedCount++;
                } catch (Exception ignored) {}
            }
            
            if (deletedCount > 0) {
                plugin.getLogger().info("已成功清理 " + deletedCount + " 條重複的舊狀態訊息。");
            }
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "執行重複訊息偵測與清理時發生異常: " + e.getMessage());
        }
    }
}
