# ArenaSystem Plugins & Client

ArenaSystem combines a Paper server plugin and a Fabric client mod. The plugin manages arenas, teams, matches, classes, abilities, and terrain. The client mod verifies its connection to the server through a versioned handshake and checks for updates using HTTPS and SHA-256 file verification.

## Stable downloads

This repository is the distribution point for **stable ArenaSystem builds**.

1. Open the [Releases page](https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server/releases).
2. Choose the latest release that is not marked as a prerelease.
3. Download the artifact that matches your installation (Paper server plugin, Fabric client mod, or other explicitly listed supporting files).
4. Follow the installation notes included with that release.

Do not treat files from the development repository, test builds, draft releases, or prereleases as stable downloads.

## Publishing a stable release

A maintainer publishes a stable release manually:

1. Confirm that the plugin/mod build and its required compatibility checks have passed.
2. Create a GitHub Release in this repository.
3. Select or create the appropriate version tag.
4. Attach the final, tested files (for example, the Paper plugin JAR and Fabric client JAR).
5. Add release notes that describe changes, compatibility, and any required installation steps.
6. Ensure at least one final JAR artifact is attached.
7. Leave **Set as a pre-release** unchecked, then publish the release.

Publishing a non-prerelease is the signal used by the Telegram announcer. The workflow does not compile the files or independently certify their stability; maintainers are responsible for validating the artifacts before publishing.

## Telegram release announcements

The GitHub Actions workflow at **.github/workflows/telegram-release-notify.yml** sends a message to the configured Telegram chat when a non-draft, non-prerelease release is published. The announcement includes the release name, version tag, publication date, release notes, downloadable asset links, and the GitHub Release page. Releases without an uploaded JAR are skipped.

Configure these repository secrets under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret name | Value |
| --- | --- |
| **TELEGRAM_BOT_TOKEN** | Token created by Telegram's @BotFather |
| **TELEGRAM_CHAT_ID** | Numeric chat ID of the target Telegram group or channel |
| **TELEGRAM_MESSAGE_THREAD_ID** (optional) | Forum topic ID when announcements should go to a specific topic |

Add the bot to the target group. For a channel, grant it permission to post messages. Leave TELEGRAM_MESSAGE_THREAD_ID unset for a normal group/channel chat; set it only when posting to a specific forum topic.

### Test the Telegram connection

After saving both secrets:

1. Open the repository's **Actions** tab.
2. Select **Telegram Release Announcer**.
3. Select **Run workflow**.
4. Keep **Send a test message** enabled and run it.
5. Confirm that the test message arrives in the configured Telegram chat.

The test run sends only a connection-check message; it does not create a GitHub Release.

## Important

The Telegram workflow only announces releases published in this repository. It does not automatically copy or promote files from the development repository. Only upload files here after they have been reviewed and approved as stable.
