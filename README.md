# DiscordSrvStatusBridge

一個為 Minecraft 伺服器設計的 DiscordSrv Addon 插件，能在指定的 Discord 頻道中維護與即時更新一個極其精美的「伺服器狀態」嵌入式訊息（Embed）。

## 💡 功能特色

- **動態效能監控**：即時展示伺服器 TPS（包含 1 分鐘、5 分鐘、15 分鐘平均值）與系統記憶體（RAM）使用率。
- **維護模式整合**：以反射技術對接知名維護插件 [Maintenance (by kennytv)](https://github.com/kennytv/Maintenance/)，當開啟維護模式時，Discord 狀態訊息會同步變更為維護標題與顏色。
- **精美玩家頭像清單**：支援透過外部頭像 API（如 Crafatar 或 Minotar）渲染線上玩家列表，玩家名稱在 Discord Embed 中會顯示為可點選的頭像超連結。
- **關機狀態自動編輯**：當伺服器關機（`onDisable`）時，插件會利用同步阻塞機制，100% 保證將 Discord 訊息狀態更新為「🔴 伺服器已關閉」，避免顯示不實狀態。
- **極致相容性**：特別採用反射技術來處理 JDA API，**同時完美相容 DiscordSrv 內建的 JDA 4 與 JDA 5**，無懼 DiscordSrv 版本升級帶來的 `NoSuchMethodError` 崩潰問題。
- **高度可配置**：所有的狀態標題、Embed 顏色、維護字樣、甚至整個訊息描述版面（透過 description-template），都可以在設定檔中自由客製化。

---

## 🛠️ 安裝與設定

1. 將編譯好的 `discordsrv-status-bridge-1.0.0-SNAPSHOT.jar` 放入伺服器的 `plugins` 目錄中。
2. 啟動伺服器以生成預設的 `config.yml` 設定檔。
3. 開啟 `plugins/DiscordSrvStatusBridge/config.yml`，在 `channel-id` 欄位中填入您想要用來顯示狀態的 Discord 頻道 ID。
4. 使用 `/minecraft:reload` 或重啟伺服器套用設定。
5. 插件會在該頻道發送一則狀態訊息，並自動將訊息 ID 記錄至 `config.yml` 的 `message-id` 中（請勿手動修改此 ID）。

---

## 📄 設定檔範例 (`config.yml`)

```yaml
# ==============================================================================
# DiscordSrvStatusBridge 設定檔
# 作者: xydesu
# ==============================================================================

# 目標 Discord 頻道 ID
channel-id: "123456789012345678"

# 狀態訊息 ID（插件發送後會自動寫入此處，請勿手動修改）
message-id: ""

# 狀態更新頻率（單位：秒，建議不低於 10 秒以避免 Discord API 速率限制）
update-interval-seconds: 30

# 是否整合 Maintenance 插件 (讀取伺服器是否處於維護狀態)
maintenance-integration: true

# 玩家頭像 API 網址。支援將 {uuid} 或 {name} 替換為玩家對應的 UUID 或名稱
avatar-api-url: "https://crafatar.com/avatars/{uuid}?size=32&overlay"

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
  #
  # 支援的動態 Placeholders：
  # {online}             - 線上玩家人數
  # {max}                - 伺服器最大人數
  # {tps}                - 當前 1 分鐘平均 TPS (格式化為 ##.##)
  # {tps_1m}             - 1 分鐘平均 TPS
  # {tps_5m}             - 5 分鐘平均 TPS
  # {tps_15m}            - 15 分鐘平均 TPS
  # {ram_used}           - 已使用記憶體 (MB)
  # {ram_max}            - 最大分配記憶體 (MB)
  # {ram_free}           - 剩餘可用記憶體 (MB)
  # {maintenance_status} - 維護狀態字串 (🛠️ 維護中 / ✅ 正常開放)
  # {player_list}        - 玩家頭像超連結列表 (Markdown 格式)
  # {player_names}       - 線上玩家純文字清單 (以逗號分隔)
  # {server_version}     - 伺服器核心版本
  # {last_updated}       - 上次更新時間 (格式: yyyy-MM-dd HH:mm:ss)
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

## 💻 本地編譯指南

本專案使用 Maven 進行建置。請確保您的開發環境中安裝有 **Java 21** 與 Maven。

在專案根目錄下執行以下指令進行編譯與打包：

```bash
mvn clean package
```

編譯完成的 `.jar` 檔案將會生成於 `target` 目錄下：
`target/discordsrv-status-bridge-1.0.0-SNAPSHOT.jar`
