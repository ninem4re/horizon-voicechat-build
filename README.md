# Horizon voice-chat server build

Builds a Linux x86-64 `horizon-engine` with Horizon's official `voicechat-server` native mod. The engine is based on **Nightly 2026-11-13**, commit `9f42064c11efe7ef3e5ed2baad7ddc2a3b36eea0`, matching the November 13 server release.

The workflow uses the official mod-application helper, runs the voice relay's tests, compiles an optimized engine without sound or controller support, checks its shared libraries and `--help`, and uploads the result. Building does not require Minecraft files. Playing still requires your existing prepared resources.

## Download

1. Open [Actions](https://github.com/ninem4re/horizon-voicechat-build/actions/workflows/build.yml).
2. Open a successful **Build Horizon voice-chat engine** run.
3. Under **Artifacts**, download `horizon-voicechat-linux-x86_64-nightly-2026-11-13` (GitHub sign-in required).
4. Unzip it. The file to upload to your server is `horizon-engine-voicechat-linux-x86_64.tar.gz`.

The first push starts a build automatically. For another build, select **Run workflow**. Artifacts are retained for 90 days.

## Install on your SSH server

Keep your existing `horizon-server`, prepared `resources/`, world, and configuration. Upload the `.tar.gz` and its adjacent `.sha256` file into your server directory. From that directory:

```sh
sha256sum -c horizon-engine-voicechat-linux-x86_64.tar.gz.sha256
mkdir -p engines/voicechat-2026-11-13
tar -xzf horizon-engine-voicechat-linux-x86_64.tar.gz -C engines/voicechat-2026-11-13
chmod +x engines/voicechat-2026-11-13/horizon-engine
realpath engines/voicechat-2026-11-13/horizon-engine
```

Stop your server normally. In the existing `[engine]` section of `server.toml`, set `path` to the absolute path printed by `realpath`:

```toml
[engine]
path = "/absolute/path/to/server/engines/voicechat-2026-11-13/horizon-engine"
```

Keep your existing `resources` setting and the other keys in that section; do not create a second `[engine]` section. Start `horizon-server` as usual. To roll back, stop the server, restore the previous `engine.path`, and start again.

This targets glibc Linux x86-64, built on Ubuntu 22.04 (glibc 2.35). It is not an ARM64 or Alpine/musl binary. The workflow checks that ALSA and udev are not linked.

## Voice chat clients and port

Forward/open **UDP 24454** to the server. A web panel using **TCP 24454** is a different service and does not open this UDP port.

Players need a compatible **Horizon voice-chat client mod**, such as azetech's Complex Voice Chat. The server uses Simple Voice Chat 2.6.x voice packets but exchanges its handshake through Horizon's `horizon:mod_net` channel. Do not assume that an unmodified Fabric/Forge Simple Voice Chat client works with it. Players without a compatible voice client can still play normally.

`config/voicechat-server.json`, beside `server.properties`, is created when a compatible client first requests voice chat. Its defaults include port `24454`, bind `0.0.0.0`, distance `48`, and whisper distance `24`. The UDP listener also starts only after that request. If you forward a different external address/port, configure `voice_host` in that file and restart.

## Reproducibility and verification

Source pins live in `scripts/build_engine.py`; the Horizon Mods checkout and Rust version are also pinned in the workflow. The helper reads the exact Horizon Mixins commit from the mod's `compatibility.json`. Build provenance is included in `build-info.json`.

The archive includes the engine, install guide, license notices, and the complete staged Horizon source as `horizon-voicechat-source.tar.gz`. Cargo dependencies are identified by that source's `Cargo.lock`. Build settings use release optimization, 16 codegen units, and no LTO to fit a hosted runner. No machine-specific CPU optimizations are enabled.

CI verifies compilation, relay tests over local UDP sockets, dynamic-library resolution, and CLI startup. An actual world/voice-client session must be checked on your server using your prepared resources; CI does not upload, download, or generate Mojang game files.

Official sources: [Horizon](https://github.com/horizon-rust/horizon), [voicechat-server](https://github.com/horizon-rust/horizon-mods/tree/87cbc721e68a689559c508cb5357d77cdae2ab81/native/voicechat-server), [native-mod build helper](https://github.com/horizon-rust/horizon-mods/blob/87cbc721e68a689559c508cb5357d77cdae2ab81/native/tools/native.py).
