# Torsion Balance Tracker

Package `dev.qi.torsionbalance`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the phone

1. Build with a shell that has unrestricted network and filesystem access. A sandboxed Gradle uses an empty cache and cannot resolve `com.android.application`. Done when `./gradlew assembleDebug` prints `BUILD SUCCESSFUL` and the debug APK exists.
2. Invoke `.tools/platform-tools/adb` by path. `/usr/local/bin/adb` is 1.0.39 and `~/Library/Android/sdk/platform-tools/adb` is 1.0.40; neither speaks TLS wireless debugging. If `.tools/platform-tools/adb` is missing, download current platform-tools (see below). Done when `adb version` is 1.0.41 / platform-tools 37 or newer.
3. Connect. USB when the cable is attached; otherwise wireless debugging. Done when `adb devices -l` shows the phone as `device`.
4. Install once: `adb -s <serial-or-address> install -r app/build/outputs/apk/debug/app-debug.apk`. Done when the command prints `Success`.

The same phone often appears twice (USB plus mDNS, or an explicit `adb connect` plus the mDNS entry). One install updates it.

## ADB

Kill and start the server with the platform-tools binary so an older daemon is not left on port 5037.

**USB.** `unauthorized` means the RSA prompt is waiting on the phone. `adb kill-server`, then `adb start-server`, raises it again. Done when the state is `device`.

**Wireless.** Wireless debugging advertises `_adb-tls-connect._tcp`. The port changes every time wireless debugging restarts; discover it, do not reuse an old port.

```bash
.tools/platform-tools/adb mdns services
```

This Mac's fallback, which sees the service even when `adb mdns` does not:

```bash
dns-sd -B _adb-tls-connect._tcp local.
dns-sd -L "<instance>" _adb-tls-connect._tcp local.
```

Then `adb connect <ip-or-host>:<port>`. A computer that is already paired connects with no pairing code. If connect fails to authenticate, the user opens Wireless debugging → Pair device with pairing code. That dialog alone advertises `_adb-tls-pairing._tcp`. Pair with the code and port it shows, then connect to the `_adb-tls-connect` port.

**Platform-tools.** `.tools/` is gitignored. Refresh the binary with:

```bash
curl -fsSL -o /tmp/platform-tools-darwin.zip https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
rm -rf .tools/platform-tools
unzip -q -o /tmp/platform-tools-darwin.zip -d .tools
```

## Camera session

Arm length is the radius from the pivot to the moving dot, in millimetres. The angle is `atan2(displacementMm, armLengthMm)`.
