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
 * 支援動態下載玩家頭像並在記憶體中快取，並將多位玩家的頭像拼接為單張圖片發送。
 * 支援 JDA 4 與 JDA 5 的反射相容性設計。
 *
 * 作者: xydesu
 */
public class StatusUpdater {

    private final DiscordSrvStatusBridge plugin;
    private final MaintenanceHook maintenanceHook;
    private boolean isUpdating = false;

    // 偵測 JDA 版本 (JDA 5 引入了 MessageCreateBuilder)
    private boolean isJda5 = false;

    // 頭像快取
    private final Map<UUID, BufferedImage> avatarCache = new ConcurrentHashMap<>();
    private BufferedImage defaultAvatar = null;

    public StatusUpdater(DiscordSrvStatusBridge plugin, MaintenanceHook maintenanceHook) {
        this.plugin = plugin;
        this.maintenanceHook = maintenanceHook;

        // 在實例化時動態偵測 JDA 版本，確保 DiscordUtil 的 ClassLoader 已就緒
        try {
            getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.messages.MessageCreateBuilder");
            this.isJda5 = true;
            plugin.getLogger().info("已偵測並啟用 JDA 5 狀態更新引擎。");
        } catch (Throwable e) {
            this.isJda5 = false;
            plugin.getLogger().info("已偵測並啟用 JDA 4 狀態更新引擎。");
        }
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

        // 5. 玩家清單與純文字清單 (供相容保留)
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
     * 拼接線上玩家頭像成一張大圖。
     *
     * @return 圖片的 byte 陣列，若無玩家則回傳 null
     */
    private byte[] generatePlayersImage() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
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
        if (avatarCache.containsKey(uuid)) {
            return;
        }

        // 下載前先提供預設頭像佔位，防止重複提交任務
        avatarCache.put(uuid, getDefaultAvatar());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String avatarUrlTemplate = plugin.getConfig().getString("avatar-api-url", "https://crafatar.com/avatars/{uuid}?size=32&overlay");
                String urlStr = avatarUrlTemplate
                        .replace("{uuid}", uuid.toString())
                        .replace("{name}", name);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                if (conn.getResponseCode() == 200) {
                    try (InputStream in = conn.getInputStream()) {
                        BufferedImage img = ImageIO.read(in);
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
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("下載玩家 " + name + " (" + uuid + ") 的頭像失敗: " + e.getMessage());
            }
        });
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
    // 以下為 JDA 4 與 JDA 5 反射相容的發送與編輯大圖附件邏輯
    // ==============================================================================

    /**
     * 反射建立 JDA 5 的 FileUpload 實體。若是 JDA 4 環境則回傳 null。
     */
    private Object createFileUpload(byte[] bytes, String name) {
        try {
            Class<?> clazz = getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.FileUpload");
            Method method = clazz.getMethod("fromData", byte[].class, String.class);
            return method.invoke(null, bytes, name);
        } catch (Exception e) {
            return null; // JDA 4
        }
    }

    /**
     * 發送帶有附件的狀態訊息，並將訊息 ID 回寫設定檔。
     */
    private void sendEmbedWithAttachment(TextChannel channel, byte[] bytes, MessageEmbed embed, boolean sync) {
        try {
            Object action;
            if (isJda5) {
                // JDA 5 核心發送機制：使用 MessageCreateBuilder 搭配 MessageCreateData
                Object builder = getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.messages.MessageCreateBuilder").getConstructor().newInstance();
                
                // setEmbeds(MessageEmbed...)
                Method setEmbedsMethod = builder.getClass().getMethod("setEmbeds", MessageEmbed[].class);
                builder = setEmbedsMethod.invoke(builder, (Object) new MessageEmbed[]{embed});
                
                // setFiles(FileUpload...)
                if (bytes != null) {
                    Object fileUpload = createFileUpload(bytes, "players.png");
                    if (fileUpload != null) {
                        Object[] fileArray = (Object[]) java.lang.reflect.Array.newInstance(fileUpload.getClass(), 1);
                        fileArray[0] = fileUpload;
                        Method setFilesMethod = builder.getClass().getMethod("setFiles", fileArray.getClass());
                        builder = setFilesMethod.invoke(builder, (Object) fileArray);
                    }
                }
                
                // build()
                Object createData = builder.getClass().getMethod("build").invoke(builder);
                
                // sendMessage(MessageCreateData)
                Class<?> createDataClass = getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.messages.MessageCreateData");
                Method sendMessageMethod = channel.getClass().getMethod("sendMessage", createDataClass);
                action = sendMessageMethod.invoke(channel, createData);
            } else {
                // JDA 4 核心發送機制
                if (bytes != null) {
                    Object act = channel.getClass().getMethod("sendFile", byte[].class, String.class).invoke(channel, bytes, "players.png");
                    action = act.getClass().getMethod("embed", MessageEmbed.class).invoke(act, embed);
                } else {
                    action = channel.getClass().getMethod("sendMessage", MessageEmbed.class).invoke(channel, embed);
                }
            }

            java.util.function.Consumer<Message> successConsumer = message -> {
                String newId = message.getId();
                plugin.getConfig().set("message-id", newId);
                plugin.saveConfig();
                plugin.getLogger().info("已發送新的狀態訊息並記錄 ID: " + newId);
            };

            if (sync) {
                Object msgObj = action.getClass().getMethod("complete").invoke(action);
                if (msgObj instanceof Message) {
                    successConsumer.accept((Message) msgObj);
                }
            } else {
                action.getClass().getMethod("queue", java.util.function.Consumer.class).invoke(action, successConsumer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "發送帶有附件的 Discord 狀態 Embed 失敗: " + e.getMessage(), e);
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
     * 執行帶有附件的訊息編輯反射動作。遇到 JDA 4 無法修改附件時會回退為「刪除舊訊息重新發送」。
     */
    private void performEditWithAttachment(Message message, byte[] bytes, MessageEmbed embed, boolean sync, TextChannel channel) {
        try {
            if (isJda5) {
                // JDA 5 核心編輯機制：使用 MessageEditBuilder 與 MessageEditData
                Object builder = getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.messages.MessageEditBuilder").getConstructor().newInstance();
                
                // setEmbeds(MessageEmbed...)
                Method setEmbedsMethod = builder.getClass().getMethod("setEmbeds", MessageEmbed[].class);
                builder = setEmbedsMethod.invoke(builder, (Object) new MessageEmbed[]{embed});
                
                // setFiles(FileUpload...)
                if (bytes != null) {
                    Object fileUpload = createFileUpload(bytes, "players.png");
                    if (fileUpload != null) {
                        Object[] fileArray = (Object[]) java.lang.reflect.Array.newInstance(fileUpload.getClass(), 1);
                        fileArray[0] = fileUpload;
                        Method setFilesMethod = builder.getClass().getMethod("setFiles", fileArray.getClass());
                        builder = setFilesMethod.invoke(builder, (Object) fileArray);
                    }
                }
                
                // build()
                Object editData = builder.getClass().getMethod("build").invoke(builder);
                
                // editMessage(MessageEditData)
                Class<?> editDataClass = getJdaClass("github.scarsz.discordsrv.dependencies.jda.api.utils.messages.MessageEditData");
                Method editMessageMethod = message.getClass().getMethod("editMessage", editDataClass);
                Object action = editMessageMethod.invoke(message, editData);
                
                if (sync) {
                    action.getClass().getMethod("complete").invoke(action);
                } else {
                    action.getClass().getMethod("queue").invoke(action);
                }
            } else {
                // JDA 4 核心編輯機制
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
            }
        } catch (NoSuchMethodException e) {
            // JDA 4 附件更新回退方案：刪除舊訊息並發送新訊息
            plugin.getLogger().info("目前環境不支援編輯附件（可能是 JDA 4），將以「刪除舊訊息並發送新訊息」方式更新圖片...");

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
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "執行帶有附件的 Discord 編輯失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 關機時同步編輯（無附件）
     */
    private void editEmbedSync(TextChannel channel, String messageId, MessageEmbed embed) {
        try {
            Object retrieveAction = channel.getClass().getMethod("retrieveMessageById", String.class).invoke(channel, messageId);
            Object msgObj = retrieveAction.getClass().getMethod("complete").invoke(retrieveAction);
            if (msgObj instanceof Message) {
                performEditWithAttachment((Message) msgObj, null, embed, true, channel);
                plugin.getLogger().info("已同步更新 Discord 狀態為「已關閉」");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "同步編輯 Discord 關機訊息失敗: " + e.getMessage());
        }
    }
}
