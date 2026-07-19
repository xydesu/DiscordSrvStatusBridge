# DiscordSrvStatusBridge

[![Release](https://img.shields.io/github/v/release/xydesu/DiscordSrvStatusBridge?style=flat-square)](https://github.com/xydesu/DiscordSrvStatusBridge/releases)
[![Downloads](https://img.shields.io/github/downloads/xydesu/DiscordSrvStatusBridge/total?style=flat-square)](https://github.com/xydesu/DiscordSrvStatusBridge/releases)
[![License](https://img.shields.io/github/license/xydesu/DiscordSrvStatusBridge?style=flat-square)](LICENSE)

A DiscordSRV addon that displays your Minecraft server's live status in a Discord channel.

## Download

Download the latest pre-built `.jar` from the [GitHub Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) page. No manual compilation needed.

---

## Core Features

- **Status Monitoring**: Displays server TPS (1m/5m/15m averages) and RAM usage.
- **Dynamic Status Message**: Automatically edits a single Discord message when the server transitions between Online, Maintenance, or Offline states.
- **Player Avatar Grid**: Stitches online player avatars into a grid image and uploads it as an embed attachment.

## Advanced Features

- **Deep PlaceholderAPI Support**: Use any PAPI variable inside `description-template` or `player-line-template`. Legacy color codes and MiniMessage tags are automatically stripped.
- **Player List Weight Sorting**: Sort players by permission group using either a PAPI placeholder (e.g. `%luckperms_highest_group_weight%`) or a priority list.
- **Vanish Support**: Fully compatible with SuperVanish, PremiumVanish, CMI, and EssentialsX Vanish. Vanished players are hidden from all Discord output.
- **Geyser/Floodgate Support**: Auto-detects Bedrock players and fetches their Xbox avatar via the Geyser API.
- **Maintenance Integration**: Integrates with the Maintenance plugin for real-time maintenance state sync.
- **Smart Rate-Limit Protection**: After immediate events (e.g. player join), the periodic timer is reset to a full interval to prevent duplicate Discord API requests.
- **Configurable Messages**: All plugin messages (console logs and command responses) are fully configurable in `messages.yml`.

---

## Installation

1. Download the latest `.jar` from [Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) and place it in your `plugins/` folder.
2. Start the server to generate the default config files.
3. Edit `plugins/DiscordSrvStatusBridge/config.yml` and set your `channel-id`.
4. Reload or restart the server.
5. After the plugin sends the message, the ID is automatically saved in `data.yml`. All future updates will edit that message instead of sending a new one.

> [!NOTE]
> `config.yml` is only written on server start and after `/dssb reload`. Runtime data (message ID) is stored in `data.yml` to keep your config clean.

---

## Supported Placeholders

In addition to any **PlaceholderAPI** variable, the following built-in placeholders are available:

### `description-template`

| Placeholder | Description |
| :--- | :--- |
| `{online}` / `{max}` | Online player count (vanished excluded) / Max capacity |
| `{tps}` | 1-minute average TPS (formatted ##.##) |
| `{tps_1m}` / `{tps_5m}` / `{tps_15m}` | Per-interval average TPS |
| `{ram_used}` / `{ram_max}` / `{ram_free}` | Memory usage (MB) |
| `{maintenance_status}` | Maintenance status text (from `embed-settings`) |
| `{player_list}` | Linked player list with avatar URLs (max 15, truncated) |
| `{player_names}` | Plain-text comma-separated player list |
| `{server_version}` | Server core version string |
| `{last_updated}` | Last update timestamp (yyyy-MM-dd HH:mm:ss) |

### `player-line-template`

| Placeholder | Description |
| :--- | :--- |
| `{name}` / `{uuid}` | Player's in-game name / UUID |
| `{avatar_url}` | Player's avatar URL (generated from `avatar-api-url`) |
| `{display_name}` | Player's DisplayName (includes chat plugin prefix/suffix) |

---

## Config Example (`config.yml`)

```yaml
# ==============================================================================
# DiscordSrvStatusBridge Configuration
# Author: xydesu
# ==============================================================================

# Target Discord channel ID
channel-id: ""

# Status update interval (seconds, minimum 10 to avoid Discord rate limits)
update-interval-seconds: 30

# Whether to integrate with the Maintenance plugin
maintenance-integration: true

# Player avatar API URL. {uuid} and {name} are replaced automatically.
# Common APIs:
# - Minotar (with helmet): https://minotar.net/helm/{uuid}/32.png
# - Minotar (without helmet): https://minotar.net/avatar/{uuid}/32.png
avatar-api-url: "https://minotar.net/helm/{uuid}/32.png"

# Display format for each player in the {player_list}
player-line-template: "- [{name}]({avatar_url})"

# Player list sorting
# Uses papi-weight-placeholder first. Falls back to permission-priority-list if unavailable.
# Permissions higher in the list have higher priority.
player-sorting:
  enabled: true
  papi-weight-placeholder: "%luckperms_highest_group_weight%"
  permission-priority-list:
    - "group.admin"
    - "group.mod"
    - "group.vip"

# Embed appearance settings
embed-settings:
  status-online:
    title: "🟢 Server Online"
    color: "#2ECC71"
  status-maintenance:
    title: "🟡 Server Maintenance"
    color: "#F1C40F"
  status-offline:
    title: "🔴 Server Offline"
    color: "#E74C3C"

  maintenance-true-text: "🛠️ Under Maintenance"
  maintenance-false-text: "✅ Open Normally"
  maintenance-offline-text: "❌ Offline"

  no-players-text: "*No players currently online*"

  description-template: |
    **Server IP**: `play.yourserver.com`
    **Server Version**: `{server_version}`
    **System Status**: {maintenance_status}

    **Online**: `{online} / {max}`
    **System Performance**: `{tps} TPS` *(1m: {tps_1m} | 5m: {tps_5m} | 15m: {tps_15m})*
    **Memory Usage**: `{ram_used} MB / {ram_max} MB` *(Free: {ram_free} MB)*

    **Online Players**:
    {player_list}

    *Last Updated: {last_updated}*
```

---

## Plugin Files

After first launch, the plugin creates the following files in `plugins/DiscordSrvStatusBridge/`:

| File | Purpose |
| :--- | :--- |
| `config.yml` | All plugin settings. Written only on start/reload. |
| `data.yml` | Runtime data (message ID). Written automatically at runtime. |
| `messages.yml` | All console/command messages. Fully customizable. |

---

## Commands & Permissions

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/dssb reload` | Reload `config.yml` and `messages.yml` and apply changes | `discordsrvstatusbridge.admin` (OP by default) |
| `/dssb update` | Force an immediate Discord status update | `discordsrvstatusbridge.admin` (OP by default) |

The main command is `/discordsrvstatusbridge`, aliased as `/dssb`.

---

## Building from Source

This project uses Maven. Requires Java 21 and Maven installed.

```bash
mvn clean package
```

Output jar: `target/discordsrv-status-bridge-1.0.0-SNAPSHOT.jar`