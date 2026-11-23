# ArenaSystem Knowledge Base

## Project overview
ArenaSystem is a Minecraft project made of two cooperating parts: a Paper server plugin and a Fabric client mod. Together they provide the server-side arena and match systems plus client-side connection verification and update checking. The supported stable distribution is published in the ArenaSystem GitHub Releases page.

## Paper server plugin
The ArenaSystem Paper plugin manages arenas, teams, matches, classes, abilities, and terrain. This is the server-side component. Exact gameplay rules, class statistics, ability effects, and installation requirements must be taken from documentation or release notes for the relevant stable version; do not infer them.

## Fabric client mod
ArenaSystemClient is the Fabric client-side mod. It verifies its connection to the ArenaSystem server using a versioned handshake protocol. It also checks for client updates using HTTPS and SHA-256 file verification. Do not assume compatibility with a specific Minecraft, Fabric Loader, Java, or server version unless the current stable release explicitly documents it.

## Handshake protocol
The client mod verifies its connection to the server through a versioned handshake. The protocol is used to check that the client and server can communicate using the expected protocol version. Detailed packet fields, timeout behavior, and compatibility rules are not documented in this knowledge base; do not invent them.

## Client updater
The client mod checks for updates over HTTPS and verifies downloaded files using SHA-256. Stable downloadable artifacts are published as GitHub Releases. Do not claim that an update is available until the release metadata confirms it.

## Stable releases and downloads
The stable distribution repository is https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server. Use its GitHub Releases page at https://github.com/au3891427-wq/Arena-System-Plugins-Client-and-Server/releases. A stable release must not be a draft or prerelease and must include an uploaded JAR before it is shown as a downloadable stable release. Release notes are the source of truth for version-specific changes and compatibility.

## Installation and compatibility
Installation instructions and exact version compatibility are version-specific. They are not confirmed in this general knowledge base. Ask the user which stable release they are using and direct them to that release's notes. Never guess Minecraft version, Paper version, Fabric Loader version, Java version, dependencies, or install paths.

## Classes and abilities
The Paper plugin manages classes and abilities, but this knowledge base does not yet contain confirmed descriptions, statistics, cooldowns, damage values, or gameplay behavior for individual classes. Do not invent details. Refer the user to the current stable release notes or ask a maintainer to add verified documentation.
