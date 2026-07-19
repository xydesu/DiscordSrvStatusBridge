# DiscordSrvStatusBridge

一個用於在 Discord 頻道顯示 Minecraft 伺服器運作狀態的 DiscordSrv 附加組件。

## 下載

您可以直接在 [GitHub Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) 頁面下載最新編譯好的 `.jar` 檔案，無需手動進行編譯。

---

## 核心功能

- **系統狀態監控**：取得伺服器 TPS（1分鐘、5分鐘、15分鐘平均值）以及系統記憶體（RAM）使用狀況。
- **維護狀態整合**：支援與 Maintenance 插件對接，當伺服器開啟維護模式時，狀態訊息會切換為對應的標題與顏色。
- **頭像拼接大圖**：當有玩家在線上時，自動將玩家頭像（每個 32x32，支援間距）拼接成一張網格大圖並作為附件上傳嵌入至 Discord Embed。最多支援拼接前 30 位玩家，若人數超過，最後一格將自動繪製為「+X」文字方塊，防止因人數過多導致 Discord 訊息長度溢出。
- **關閉狀態編輯**：在伺服器停用（onDisable）時發送同步請求，將訊息更新為已關閉狀態，防止顯示錯誤的運作資訊。
- **API 相容性設計**：使用反射調用 JDA API，相容不同版本 DiscordSrv 所內建的 JDA 4 或 JDA 5，減少因相依套件升級導致 plugin 崩潰的機率。
- **自訂描述模板**：支援在設定檔中自訂描述格式，並提供多種預留位置進行動態替換。

---

## 安裝與設定步驟

1. 前往 [Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) 頁面下載最新版本的 `.jar` 檔案，並放入伺服器的 `plugins` 資料夾中。
2. 啟動伺服器以生成預設設定檔。
3. 編輯 `plugins/DiscordSrvStatusBridge/config.yml`，在 `channel-id` 欄位中填入目標 Discord 頻道 ID。
4. 重新載入或重啟伺服器以套用變更。
5. 插件發送訊息後，會自動在 `config.yml` 中寫入 `message-id`。後續的狀態更新將會以編輯該訊息的方式進行。

---

## 支援的預留位置 (Placeholders)

可在描述模板中使用的動態變數如下：

| 預留位置 | 說明 |
| :--- | :--- |
| `{online}` | 當前線上玩家人數 |
| `{max}` | 伺服器最大容納人數 |
| `{tps}` | 當前 1 分鐘平均 TPS (格式化為 ##.##) |
| `{tps_1m}` | 1 分鐘平均 TPS |
| `{tps_5m}` | 5 分鐘平均 TPS |
| `{tps_15m}` | 15 分鐘平均 TPS |
| `{ram_used}` | 已使用記憶體 (MB) |
| `{ram_max}` | 最大分配記憶體 (MB) |
| `{ram_free}` | 剩餘可用記憶體 (MB) |
| `{maintenance_status}` | 維護狀態文字 (依設定檔設定顯示) |
| `{player_list}` | 線上玩家頭像超連結列表 (Markdown 格式) |
| `{player_names}` | 線上玩家純文字清單 (以逗號分隔) |
| `{server_version}` | 伺服器核心版本 |
| `{last_updated}` | 上次更新時間 (格式: yyyy-MM-dd HH:mm:ss) |

---

## 設定檔範例 (config.yml)

```yaml
# ==============================================================================
# DiscordSrvStatusBridge 設定檔
# 作者: xydesu
# ==============================================================================

# 目標 Discord 頻道 ID
channel-id: ""

# 狀態訊息 ID（插件發送後會自動寫入此處，請勿手動修改）
message-id: ""

# 狀態更新頻率（單位：秒，建議不低於 10 秒以避免 Discord API 速率限制）
update-interval-seconds: 30

# 是否整合 Maintenance 插件 (讀取伺服器是否處於維護狀態)
maintenance-integration: true

# 玩家頭像 API 網址。支援將 {uuid} 或 {name} 替換為玩家對應的 UUID 或名稱
avatar-api-url: "https://minotar.net/helm/{uuid}/32.png"

# 狀態訊息內容與外觀設定
embed-settings:
  # 當伺服器為不同狀態時的標題與顏色設定 (支援 Hex 色碼)
  status-online:
    title: "🟢 伺服器運作中"
    color: "#2ECC71" # 綠色
  status-maintenance:
    title: "🟡 伺服器維護中"
    color: "#F1C40F" # 黃色
  status-offline:
    title: "🔴 伺服器已關閉"
    color: "#E74C3C" # 紅色

  # 維護狀態文字對應
  maintenance-true-text: "🛠️ 維護中"
  maintenance-false-text: "✅ 正常開放"

  # 當沒有玩家在線上時的替代文字
  no-players-text: "*目前沒有玩家在線上*"

  # 狀態訊息的描述 (Description) 內容，支援 Placeholder 替換。
  # 說明：伺服器 IP 等靜態內容請直接在模板中填寫即可。
  description-template: |
    **伺服器位址**: `play.yourserver.com`
    **伺服器版本**: `{server_version}`
    **系統狀態**: {maintenance_status}
    
    **線上人數**: `{online} / {max}`
    **系統效能**: `{tps} TPS` *(1m: {tps_1m} | 5m: {tps_5m} | 15m: {tps_15m})*
    **記憶體使用**: `{ram_used} MB / {ram_max} MB` *(剩餘 {ram_free} MB)*
    
    **線上玩家**:
    {player_list}
    
    *最後更新時間: {last_updated}*
```

---

## 本地編譯指南

本專案使用 Maven 進行建置。請確保您的開發環境中安裝有 Java 21 與 Maven。

在專案根目錄下執行以下指令進行編譯與打包：

```bash
mvn clean package
```

編譯完成的 .jar 檔案將會生成於 target 目錄下：
`target/discordsrv-status-bridge-1.0.0-SNAPSHOT.jar`
