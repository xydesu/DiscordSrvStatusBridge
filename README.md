# DiscordSrvStatusBridge

[繁體中文](https://github.com/xydesu/DiscordSrvStatusBridge/blob/main/README_zh_tw.md)

A DiscordSrv add-on for displaying the operational status of a Minecraft server in a Discord channel.

## Download

You can directly download the latest compiled `.jar` file on the [GitHub Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) page without needing to compile it manually.

---

## Core Features

* **System Status Monitoring**: Retrieves server TPS (1-minute, 5-minute, 15-minute averages) and system memory (RAM) usage.
* **Dynamic Status Messages**: Automatically edits and syncs the title and color of a single Discord message when the server is online, under maintenance, or offline.
* **Avatar Composite Grid Image**: When players are online, automatically stitches player avatars (supports spacing) into a large grid image and uploads it as an embed attachment, preventing Embed description text overflow caused by too many players.

## Advanced Features

* **Deep PlaceholderAPI Compatibility**: Supports directly injecting any PAPI variables into the status description and player-specific line formats, while automatically filtering out traditional color codes and MiniMessage tags.
* **Player List Weight Sorting**: Supports customizing the sorting order of the list and grid image avatars based on permission nodes (e.g., LuckPerms groups) to assign weights.
* **Vanish Plugin Compatibility**: Fully supports SuperVanish, PremiumVanish, CMI, etc. Players in vanish mode will automatically be hidden on Discord.
* **Bedrock Player Support (Geyser/Floodgate)**: Automatically detects Bedrock players, prioritizing the official Geyser API to fetch Xbox avatars, complete with an error-prevention filtering mechanism.
* **Maintenance Mode Integration**: Supports integration with the Maintenance plugin, instantly switching to the maintenance status on Discord when the server activates maintenance mode.
* **Smart Rate-Limit Prevention Mechanism**: After triggering an asynchronous real-time update (e.g., player login), the timer cycle is automatically reset, significantly reducing Discord API 429 rate limit issues caused by repeated requests.

---

## Installation & Setup Steps

1. Go to the [Releases](https://github.com/xydesu/DiscordSrvStatusBridge/releases) page to download the latest `.jar` file and place it in the server's `plugins` folder.
2. Start the server to generate the default configuration file.
3. Edit `plugins/DiscordSrvStatusBridge/config.yml` and fill in the target Discord channel ID in the `channel-id` field.
4. Reload or restart the server to apply the changes.
5. After the plugin sends the message, it will automatically write the `message-id` into `config.yml`. Subsequent status updates will edit this specific message.

---

## Supported Placeholders

This plugin supports using any **PlaceholderAPI** variables (e.g., `%server_ram_used%`, `%luckperms_primary_group_name%`, etc.) in the `description-template` and `player-line-template`.

In addition, the following lightweight built-in variables are also provided:

### Status Description Template (description-template)

| Placeholder | Description |
| --- | --- |
| `{online}` / `{max}` | Current online players (excluding vanished) / Server maximum capacity |
| `{tps}` | Current 1-minute average TPS (formatted as ##.##) |
| `{tps_1m}` / `{tps_5m}` / `{tps_15m}` | Average TPS for each time period |
| `{ram_used}` / `{ram_max}` / `{ram_free}` | Memory usage status (MB) |
| `{maintenance_status}` | Maintenance status text (displayed according to config settings) |
| `{player_list}` | Hyperlinked list of online player avatars (supports line templates, truncated after the top 15) |
| `{player_names}` | Plain text list of online players (comma-separated, excluding vanished players) |
| `{server_version}` | Server core version |
| `{last_updated}` | Last update time (Format: yyyy-MM-dd HH:mm:ss) |

### Player List Line Template (player-line-template)

| Placeholder | Description |
| --- | --- |
| `{name}` / `{uuid}` | Player's in-game name / Player's UUID |
| `{avatar_url}` | Player's avatar API URL (generated from avatar-api-url in the config) |
| `{display_name}` | Player's DisplayName (usually contains prefixes set by chat plugins) |

---

## Configuration Example (config.yml)

```yaml
# ==============================================================================
# DiscordSrvStatusBridge Configuration
# Author: xydesu
# ==============================================================================

# Target Discord channel ID
channel-id: ""

# Status message ID (The plugin will automatically write this after sending the message, do not modify manually)
message-id: ""

# Status update interval (Unit: seconds, recommended not lower than 10 seconds to avoid Discord API rate limits)
update-interval-seconds: 30

# Whether to integrate with the Maintenance plugin (reads if the server is in maintenance state)
maintenance-integration: true

# Player avatar API URL. Supports replacing {uuid} or {name} with the player's corresponding UUID or name
# Common APIs:
# - Minotar (with helmet): https://minotar.net/helm/{uuid}/32.png
# - Minotar (without helmet): https://minotar.net/avatar/{uuid}/32.png
avatar-api-url: "https://minotar.net/helm/{uuid}/32.png"

# In the online player list ({player_list}), the display format template for each player
# Supported Placeholders:
# - {name} : Player's in-game name (e.g., xydesu)
# - {uuid} : Player's UUID
# - {avatar_url} : Player's avatar API URL (generated by the avatar-api-url above)
# - {display_name} : Player's DisplayName (usually includes prefix/titles set by chat plugins)
player-line-template: "- [{name}]({avatar_url})"


# Player list sorting settings
player-sorting:
  enabled: true
  # Assign weight based on permission nodes (e.g., LuckPerms group.admin).
  # Permissions listed "higher" in the list represent a higher priority in the player list.
  permission-priority-list:
    - "group.admin"
    - "group.mod"
    - "group.vip"

# Status message content and appearance settings
embed-settings:
  # Title and color settings for different server states (Supports Hex color codes)
  status-online:
    title: "🟢 Server Online"
    color: "#2ECC71" # Green
  status-maintenance:
    title: "🟡 Server Maintenance"
    color: "#F1C40F" # Yellow
  status-offline:
    title: "🔴 Server Offline"
    color: "#E74C3C" # Red

  # Maintenance status text mapping
  maintenance-true-text: "🛠️ Under Maintenance"
  maintenance-false-text: "✅ Open Normally"

  # Replacement text when no players are online
  no-players-text: "*No players currently online*"

  # Status message description content, supports Placeholder replacement.
  # Note: Static content like server IP should be filled directly in the template.
  #
  # Supported dynamic Placeholders:
  # {online}             - Online player count
  # {max}                - Server maximum capacity
  # {tps}                - Current 1-minute average TPS (formatted to ##.##)
  # {tps_1m}             - 1-minute average TPS
  # {tps_5m}             - 5-minute average TPS
  # {tps_15m}            - 15-minute average TPS
  # {ram_used}           - Used memory (MB)
  # {ram_max}            - Max allocated memory (MB)
  # {ram_free}           - Free memory (MB)
  # {maintenance_status} - Maintenance status string (🛠️ Under Maintenance / ✅ Open Normally)
  # {player_list}        - Player avatar hyperlinked list (Markdown format)
  # {player_names}       - Plain text list of online players (Comma-separated, e.g.: xydesu, Player1)
  # {server_version}     - Server core version
  # {last_updated}       - Last updated time (Format: yyyy-MM-dd HH:mm:ss)
  description-template: |
    **Server IP**: `play.yourserver.com`
    **Server Version**: `{server_version}`
    **System Status**: {maintenance_status}
    
    **Online**: `{online} / {max}`
    **System Performance**: `{tps} TPS` *(1m: {tps_1m} | 5m: {tps_5m} | 15m: {tps_15m})*
    **Memory Usage**: `{ram_used} MB / {ram_max} MB` *(Free: {ram_free} MB)*
    
    **Online Players**: (Avatars shown below)
    
    *Last Updated: {last_updated}*


```

---

## Admin Commands & Permissions

This plugin provides the following admin commands for easy control in-game or directly from the console (the main command is `/discordsrvstatusbridge`, which can be abbreviated as `/dssb`):

| Command | Description | Permission |
| --- | --- | --- |
| `/dssb reload` | Reloads the `config.yml` configuration file and applies changes immediately | `discordsrvstatusbridge.admin` (OP has it by default) |
| `/dssb update` | Forces an immediate update to the Discord status message | `discordsrvstatusbridge.admin` (OP has it by default) |

---

## Local Compilation Guide

This project uses Maven for builds. Please ensure that Java 21 and Maven are installed in your development environment.

Run the following command in the project root directory to compile and package:

```bash
mvn clean package

```

The compiled .jar file will be generated in the `target` directory:
`target/discordsrv-status-bridge-1.0.0-SNAPSHOT.jar`