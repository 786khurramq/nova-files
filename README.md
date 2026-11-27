# Nova Files — Spectrum Flow

Milestone 1 (v0.1.0-transfer): architecture, design system, adaptive phone/TV navigation, Home, local file manager, and the secure wireless transfer system.

## Build

Requirements: JDK 17+ (21 tested), Android SDK platform 35 + build-tools 35.

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease          # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest        # transfer protocol tests (loopback, real sockets)
```

The release key `nova-release.jks` is **not in git** (it's a secret). Copy it into the project root to sign builds that update the installed app; passwords can be overridden with `NOVA_KEYSTORE_PASSWORD` / `NOVA_KEY_PASSWORD`. Without it, builds are signed with the debug key and can't update an install signed with the release key.

## Install

- **S24 Ultra:** open the APK and allow "Install unknown apps" for your file manager or browser.
- **Shield / MINIX:** `adb connect <tv-ip>` then `adb install -r app-release.apk`, or copy it to USB and open it with a TV file manager.
- **All files access on Android TV:** some TV firmwares have no settings screen for this permission. Grant it once with:
  `adb shell appops set --uid com.nova.files MANAGE_EXTERNAL_STORAGE allow`
  Receiving works without it (files go to `Download/NovaFiles`).

## Transfer architecture

| Layer | Implementation |
|---|---|
| Discovery | UDP broadcast beacons on port 47820 plus DNS-SD `_novafiles._tcp`, with manual IP and QR/link fallbacks |
| Transport | TCP port 47821 (falls back to a free port), length-prefixed frames, 256 KiB streamed chunks. Files are never held fully in memory. |
| Identity | EC P-256 signing key held in Android Keystore. Device ID = SHA-256 fingerprint. |
| Session | Ephemeral ECDH, HKDF-SHA256 per-direction keys, and AES-256-GCM with counter nonces (protects against replay, reordering and tampering) |
| Pairing | Both screens show a 6-digit code derived from the session transcript (detects MITM). Trusted devices are pinned by fingerprint after that. |
| Integrity | SHA-256 for every file, temp `.part` files in a hidden folder, atomic rename after verification, never overwrites (`name (1).ext`) |
| Safety | Path sanitising rejects `..`, absolute paths and reserved names. Canonical-path check. Free-space check before accepting. |
| Resume | Partial data is kept per (sender, path, size, mtime). Sender auto-retries 3× and the receiver auto-resumes the same transfer ID. |
| Liveness | Heartbeat every 8 s; a dead link is detected within 45 s, including while paused |
| Background | Foreground service (`dataSync`) holds Wi-Fi high-perf and partial wake locks only while transferring |

Code layout: `transfer/protocol` (pure Kotlin, JVM-tested), `transfer` (Android discovery/service/stores), `core` (storage, settings, device profile), `ui` (Compose, theme, screens).

## Device test checklist (milestone 1)

Run on the same Wi-Fi, with Nova Files open on the receiver.

1. **Discovery:** each device appears in Transfer › Nearby devices on the other two (6 directions).
2. **Pairing:** the first send shows the same 6-digit code on both screens. Trust it. Later sends ask nothing except the Accept prompt (unless auto-accept is on).
3. **Send matrix:** S24→Shield, S24→MINIX, Shield→S24, MINIX→S24, Shield↔MINIX. For each, send a photo, a folder containing subfolders, and a file over 4 GB.
4. **Verify:** files land in `Download/NovaFiles` with folder structure intact. The card says "SHA-256 verified".
5. **Interruption:** start a large send and turn the receiver's Wi-Fi off for 10 s, then on. The sender retries and resumes without starting over (or press Retry in History).
6. **Controls:** pause, resume and cancel from both sender and receiver.
7. **Manual IP:** Transfer › Connect by IP using the address shown under Show QR / IP.
8. **APK handoff:** send an APK to the Shield or MINIX, open it from Files, and confirm the system installer appears (nothing installs automatically).
9. **TV navigation:** use the remote only. Every button should be reachable and show a clear focus ring.

If discovery fails but Connect by IP works, the router is blocking broadcast/mDNS (AP/client isolation).

## Cloud foundation (Phase 1, v0.2.0)

Module `:cloud` (Android library, no UI):

| Package | Purpose |
|---|---|
| `cloud.core` | `CloudProviderAdapter`, `CloudFile`, `Operation` capabilities + item-specific `canExecute`, typed `CloudException`, NFC/FAT-safe `FileNames` |
| `cloud.db` | Room `nova_cloud.db` v1: `cloud_accounts` (unique providerId+providerAccountId), `cloud_items` (PK accountId+fileId), `cloud_cursors`, `transfers`. Schema exported to `cloud/schemas`; destructive migration is disabled. |
| `cloud.auth` | Keystore AES-256-GCM `CredentialStore` (record key bound as AAD), PKCE + state (`PendingAuthStore`, single use, 15 min expiry, survives process death), `OAuthClient`, per-account single-flight `AccountTokenProvider` |
| `cloud.net` | Shared OkHttp base client, per-account derived clients (no global auth interceptor), HTTPS↔HTTP redirects disabled, `HttpErrors`, `RetryPolicy` (backoff + jitter + Retry-After, idempotency-aware), `ProviderLimiter`, `Redact` |
| `cloud.accounts` | `AccountRepository`: connect/reconnect/remove, per-account `AccountSession` |
| `cloud.transfer` | `Endpoint` (Local, ContentUri, SafTree, Cloud, Wireless), `TransferSource`/`TransferDestination`, persistent `TransferQueue`, `TransferEngine` (bounded buffer, checkpoints, conflict policy, size + SHA-256 checks) |

App: `cloudui/CloudRuntime` (wiring, Android 14+ user-initiated jobs for network transfers, dataSync service otherwise, WorkManager recovery notification), `cloudui/CloudScreen` (account centre + shared transfer queue). Wireless transfers are recorded in the same queue.

Tests: `./gradlew :app:testDebugUnitTest :cloud:testDebugUnitTest`. Offline Robolectric: set `ROBOLECTRIC_DEPS` to a folder containing `android-all-instrumented-14-robolectric-10818077-i7.jar`.

## Google Drive + Dropbox (Phase 2, v0.3.0)

- OAuth 2.0 authorization code + PKCE in the system browser; redirects `com.nova.files:/oauth2redirect` (Google) and `db-<app key>://1/connect` (Dropbox) land in `OAuthRedirectActivity`; state is single-use and survives process death.
- Client identifiers live in `gradle.properties` (`nova.googleClientId`, `nova.dropboxAppKey`). They are public identifiers; no client secret exists or is used.
- Google Drive uses only `drive.file`: Nova Files sees files and folders it created/uploaded. Google Docs/Sheets/Slides are exported (PDF/DOCX/XLSX/PPTX). Deletes go to Drive trash.
- Dropbox uses Full Dropbox access in development status; identity is the Dropbox file id. Upload sessions (8 MiB chunks) and Drive resumable sessions are checkpointed in the transfer queue and resumed after interruptions. Downloads use HTTP Range.
- Integrity: Dropbox `content_hash` and Drive `md5Checksum` are verified after uploads and downloads; mismatches discard the copy.
- Cloud thumbnails come from provider thumbnail endpoints through the existing thumbnail cache (key = account + file id + revision).
