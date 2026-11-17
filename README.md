# Horizon server with Fabric Simple Voice Chat support

Builds a Linux x86-64 `horizon-engine` from **Nightly 2026-11-13**, commit `9f42064c11efe7ef3e5ed2baad7ddc2a3b36eea0`, with Horizon's voice-chat relay patched to support **standard Fabric Simple Voice Chat 2.6.x clients (compatibility version 20) on Minecraft 26.3**. Existing compatible Horizon voice-chat clients remain supported, including mixed groups of Fabric and Horizon players.

The first build (run #1) predates this patch and only supports Horizon mod messages. Download a successful run containing `fabric-svc.patch` and a `fabric_simple_voice_chat` entry in `build-info.json`.

## Download

Open [Actions](https://github.com/ninem4re/horizon-voicechat-build/actions/workflows/build.yml), select the latest successful patched build, and download `horizon-voicechat-linux-x86_64-nightly-2026-11-13` under Artifacts. GitHub sign-in is required. Unzip the download; upload the `.tar.gz` and its adjacent `.sha256` file to your server.

The workflow runs on changes to the build script or workflow; **Run workflow** rebuilds it manually. Artifacts are retained for 90 days.

## Install on your SSH server

Keep your `horizon-server`, world, configuration, and prepared `resources/` directory. From the server directory after uploading the archive:

```sh
sha256sum -c horizon-engine-voicechat-linux-x86_64.tar.gz.sha256
mkdir -p engines/voicechat-fabric-2026-11-13
tar -xzf horizon-engine-voicechat-linux-x86_64.tar.gz -C engines/voicechat-fabric-2026-11-13
chmod +x engines/voicechat-fabric-2026-11-13/horizon-engine
realpath engines/voicechat-fabric-2026-11-13/horizon-engine
```

Stop the server normally. In your existing `[engine]` section of `server.toml`, set `path` to the absolute path printed above:

```toml
[engine]
path = "/absolute/path/to/server/engines/voicechat-fabric-2026-11-13/horizon-engine"
```

Preserve the other keys, including your resources setting; do not add a second `[engine]` section. Start `horizon-server` as usual. To roll back, stop it, restore the previous engine path, and start again.

The binary targets glibc Linux x86-64 (Ubuntu 22.04 / glibc 2.35 baseline), without ALSA or udev runtime dependencies. It is not an ARM64 or Alpine/musl build.

## Players and configuration

Install the normal **Simple Voice Chat 2.6.x Fabric mod for Minecraft 26.3**, plus its required client dependencies. No custom Fabric companion mod is needed. SVC **2.7 prereleases (compatibility 21)** are not supported by this patch; they changed the handshake. Clients without voice chat can still play.

Forward/open **UDP 24454** to the server, including any provider firewall. A web panel on **TCP 24454** is a separate service and does not open the UDP port.

`config/voicechat-server.json`, beside `server.properties`, uses the existing relay settings: `port` 24454, `bind` `0.0.0.0`, `distance` 48, `whisper_distance` 24, and groups enabled. The config is created when a player joins; the UDP listener starts only when a supported voice client requests a secret. Restart after editing it. Set `voice_host` if clients need a different external voice address/port.

The server advertises its incoming `voicechat:*` channels through `minecraft:register`, handles standard custom payloads, and returns control messages on the transport each player selected. The AES-GCM UDP relay, player secrets, rate limits, replay protection, distance/dimension rules, and group routing are retained.

## Tests and source

`fabric-svc.patch` applies to the pinned official Horizon Mods revision `87cbc721e68a689559c508cb5357d77cdae2ab81` before the official native-build helper applies the mod to Horizon. Source pins, patch checksum and build provenance are in `build-info.json`. The complete staged Rust source, patch and license notices ship with the engine.

Validation includes:

- The original 21 relay tests plus 3 transport tests for routing, malformed/unsupported requests, and logout cleanup.
- `FabricInteropTest.java`: an independent JDK client exercising the real patched relay over local UDP sockets. It uses Java AES-GCM and independently writes/reads the SVC packet layouts, checking the secret handshake, authentication, voice data, proximity, dimension isolation, password groups, disabled state, replay rejection, and mixed Horizon/Fabric recipients.
- Optimized engine compilation with the actual injected plugin-channel hooks, dynamic-library resolution, and `--help` startup.

Protocol reference: [Simple Voice Chat source at compatibility version 20](https://github.com/henkelmax/simple-voice-chat/tree/2e56198016e02fd99139d37f382b7155c5bd63f5). The Java test and bridge are independent implementations; they do not bundle Mojang or Simple Voice Chat source.

The automated interoperability test covers the real relay and transport router, not a full Minecraft world with a graphical Fabric client. A real microphone/game session must still be checked on your server with your prepared resources. No Minecraft files are needed for compilation or these tests.
