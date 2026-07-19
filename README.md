# DiscordSrvStatusBridge

一個用於在 Discord 頻道顯示 Minecraft 伺服器運作狀態的 DiscordSrv 附加組件。

## 下載

您可以直接在 [GitHub Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) 頁面下載最新編譯好的 `.jar` 檔案，無需手動進行編譯。

---

## 核心功能

- **系統狀態監控**：取得伺服器 TPS（1分鐘、5分鐘、15分鐘平均值）以及系統記憶體（RAM）使用狀況。
- **維護狀態整合**：支援與 Maintenance 插件對接，當伺服器開啟維護模式時，狀態訊息會切換為對應的標題與顏色，並支援維護模式變更事件的即時狀態同步。
- **隱身插件相容 (Vanish)**：完美支援 SuperVanish, PremiumVanish, CMI 以及 EssentialsX Vanish。當管理員處於隱身狀態時，將自動從 Discord 線上人數、玩家清單、純文字名稱及頭像大圖中抹除，確保隱私。
- **基岩版玩家支援 (Geyser/Floodgate)**：自動偵測 Geyser/Floodgate 基岩版玩家，並反射取得其 XUID，優先由 Geyser 官方 API 取得 Xbox 頭像，並具備前綴字元過濾機制防範頭像下載錯誤。
- **大中型伺服器截斷防護**：設有前 15 名玩家截斷限制，大於 15 人時 Markdown 清單將自動以 `...以及其他 X 位玩家` 截斷，防範 Embed 描述文字過長。
- **頭像拼接大圖**：當有玩家在線上時，自動將玩家頭像（每個 32x32，支援間距）拼接成一張網格大圖並作為附件上傳嵌入至 Discord Embed。最多支援拼接前 30 位玩家，若人數超過，最後一格將自動繪製為「+X」文字方塊，防止因人數過多導致 Discord 訊息長度溢出。
- **關閉狀態編輯**：在伺服器停用（onDisable）時發送同步請求，將訊息更新為已關閉狀態，並自動清除舊有的大圖附件，確保大圖不會殘留在關閉訊息中。
- **API 相容性設計**：使用反射調用 JDA API，相容不同版本 DiscordSrv 所內建的 JDA 4 或 JDA 5，減少因相依套件升級導致 plugin 崩潰的機率。
- **自訂描述與行模板**：支援在設定檔中自訂描述格式，並提供 `player-line-template` 自訂每一位玩家行格式，相容稱號展示。同時**深度整合 PlaceholderAPI**，支援在狀態描述與玩家行格式中直接代入任意 PAPI 變數（如 %server_ram_used%、%player_ping% 等）。

---

## 安裝與設定步驟

1. 前往 [Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) 頁面下載最新版本的 `.jar` 檔案，並放入伺服器的 `plugins` 資料夾中。
2. 啟動伺服器以生成預設設定檔。
3. 編輯 `plugins/DiscordSrvStatusBridge/config.yml`，在 `channel-id` 欄位中填入目標 Discord 頻道 ID。
4. 重新載入或重啟伺服器以套用變更。
5. 插件發送訊息後，會自動在 `config.yml` 中寫入 `message-id`。後續的狀態更新將會以編輯該訊息的方式進行。

---

## 支援的預留位置 (Placeholders)

### 狀態描述模板變數 (description-template)

| 預留位置 | 說明 |
| :--- | :--- |
| `{online}` | 當前線上玩家人數 (自動排除隱身玩家) |
| `{max}` | 伺服器最大容納人數 |
| `{tps}` | 當前 1 分鐘平均 TPS (格式化為 ##.##) |
| `{tps_1m}` | 1 分鐘平均 TPS |
| `{tps_5m}` | 5 分鐘平均 TPS |
| `{tps_15m}` | 15 分鐘平均 TPS |
| `{ram_used}` | 已使用記憶體 (MB) |
| `{ram_max}` | 最大分配記憶體 (MB) |
| `{ram_free}` | 剩餘可用記憶體 (MB) |
| `{maintenance_status}` | 維護狀態文字 (依設定檔設定顯示) |
| `{player_list}` | 線上玩家頭像超連結列表 (支援行模板，設有前 15 名玩家截斷) |
| `{player_names}` | 線上玩家純文字清單 (以逗號分隔，排除隱身玩家) |
| `{server_version}` | 伺服器核心版本 |
| `{last_updated}` | 上次更新時間 (格式: yyyy-MM-dd HH:mm:ss) |

### 玩家清單行模板變數 (player-line-template)

| 預留位置 | 說明 |
| :--- | :--- |
| `{name}` | 玩家的遊戲名稱 (例如 xydesu) |
| `{uuid}` | 玩家的 UUID |
| `{avatar_url}` | 玩家的頭像 API 網址 (由設定檔中的 avatar-api-url 生成) |
| `{display_name}` | 玩家的 DisplayName (通常包含聊天插件所設置的 prefix 稱號/字元) |

### PlaceholderAPI 支援

本插件支援在 `description-template` 與 `player-line-template` 中使用任意 **PlaceholderAPI** 變數（例如 `%server_ram_used%`、`%player_ping%`、`%luckperms_primary_group_name%` 等）：
- **全域描述變數**：在 `description-template` 中使用時，會優先使用當前第一個線上玩家作為 PAPI 變數的上下文進行替換（若無玩家則以 null 處理）。
- **玩家專屬變數**：在 `player-line-template` 中使用時，會對每一位玩家代入其對應的玩家上下文進行個人變數替換（例如顯示玩家個人的 ping 值或稱號）。

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
# 常用 API：
# - Minotar (帶頭盔): https://minotar.net/helm/{uuid}/32.png
# - Minotar (無頭盔): https://minotar.net/avatar/{uuid}/32.png
avatar-api-url: "https://minotar.net/helm/{uuid}/32.png"

# 線上玩家清單 ({player_list}) 中，每一位玩家的顯示格式模板
player-line-template: "- [{name}]({avatar_url})"

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

## 管理指令與權限

本插件提供以下管理指令以方便在遊戲中或控制台直接進行控制（主指令為 `/discordsrvstatusbridge`，可使用縮寫 `/dssb`）：

| 指令 | 說明 | 權限 |
| :--- | :--- | :--- |
| `/dssb reload` | 重新載入 `config.yml` 設定檔並立即套用更新 | `discordsrvstatusbridge.admin` (預設 OP 擁有) |
| `/dssb update` | 強制立即對 Discord 狀態訊息進行一次更新 | `discordsrvstatusbridge.admin` (預設 OP 擁有) |

---

## 本地編譯指南

本專案使用 Maven 進行建置。請確保您的開發環境中安裝有 Java 21 與 Maven。

在專案根目錄下執行以下指令進行編譯與打包：

```bash
mvn clean package
```

編譯完成的 .jar 檔案將會生成於 target 目錄下：
`target/discordsrv-status-bridge-1.0.0-SNAPSHOT.jar`
