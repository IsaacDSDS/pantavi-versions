# Pantavi

**Pantavi**, a receiver for Android TV compatible with AirPlay: mirror an iPhone, iPad or Mac screen (with
sound), send videos with the AirPlay button in Safari and video apps, and stream AirPlay audio to
the TV, the way an Apple TV does. Built and tested on a TCL Android TV
(Android 11, Realtek RTD2851A, `armeabi-v7a`).

Pantavi is free to use with a few limits; installing the separate **Pantavi Pro** key app lifts
them (see [Free and Pro](#free-and-pro)).

## How it works

```
iPhone / iPad / Mac
   │  mDNS (_airplay._tcp, _raop._tcp)  ──►  internal mDNS responder (UDP 5353)
   │  RTSP/HTTP control: /info, pair-setup/verify, fp-setup (FairPlay), SETUP, RECORD …
   │  mirror stream (TCP, AES-CTR)  /  audio (RTP, AES-CBC)
   │  AirPlay video (HTTP): /play <url>, /rate, /scrub, /playback-info, /stop
   ▼
libaircast.so ── UxPlay 1.74 protocol core (C) + OpenSSL libcrypto + libplist + Apple ALAC decoder
   │  JNI: decrypted H.264/H.265 access units, AAC/PCM audio, metadata, session events,
   │       AirPlay video URL + remote-control commands
   ▼
Kotlin receiver ── MediaCodec (hardware video → SurfaceView), MediaCodec AAC-ELD/LC + AudioTrack,
                   Media3 ExoPlayer for AirPlay video URLs (progressive and HLS)
```

The AirPlay protocol (pairing, FairPlay key exchange, RTP) comes from
[UxPlay](https://github.com/FDH2/UxPlay), vendored unmodified in `app/src/main/cpp/uxplay`
except for one patch in `mdnsd/dnssd_mdnsd.c` (Android's `gethostname()` returns `localhost`,
so the app sets a unique mDNS host label). `android/aircast_jni.c` replaces `uxplay.cpp`:
same startup order, advertised feature bits and reset/reconnect loop, with GStreamer swapped for
JNI callbacks.

Kotlin code follows feature-first Clean Architecture:

```
core/                       DI (ServiceLocator), network address, Surface adapter
core/entitlement/           Entitlement (FREE / PRO), ProKeyEntitlementRepository
core/navigation/            AppNavigator (opens other features' screens; implemented in core/di)
features/receiver/domain/   ReceiverState, ReceiverCapabilities, NowPlaying, AirPlayReceiverRepository (pure Kotlin)
features/receiver/data/     JNI bridge, VideoDecoder, AudioPlayer, StreamPlayer, DMAP parser, identity store
features/receiver/presentation/  ReceiverActivity, ReceiverService
features/pro/presentation/  ProActivity (what Pro adds, link to the key app on Google Play)
features/about/presentation/  LicensesActivity (legal notices, source link, license texts)
shared/ui/                  AspectRatioFrameLayout
```

UI tokens and components are documented in [STYLES.md](STYLES.md).

### Free and Pro

| | Free | Pro |
|---|---|---|
| Screen mirroring | up to 1920×1080 at 30 fps, H.264 | up to 3840×2160 at 60 fps, H.265 or H.264 |
| AirPlay video (the AirPlay button in apps) | up to 1080p | up to 4K |
| Session length | 15 minutes of mirroring or playing video per connection, with a countdown on screen (reconnect to continue) | no limit |
| AirPlay audio | yes | yes |

Pro is unlocked by the **Pantavi Pro** key app (`com.isaacbenavides.pantavi.pro`), sold
separately on Google Play. It has no code of its own beyond a confirmation screen: Pantavi
checks that the key is installed and signed with the same certificate
(`PackageManager.checkSignatures`), and watches package installs and removals so the plan switches
at once. The capabilities are advertised over mDNS when the receiver starts, so a plan change
restarts the receiver while no device is connected. The key app is not part of this source;
builds made from it are always Free unless signed with the same key as an installed Pro key.

The 15 minutes are spent only while the TV shows something: mirroring, or an AirPlay video that
is playing (not paused or loading). The time left carries over to the next video while the
device stays connected; a new connection starts with the full time. A countdown sits in a corner
over the video, and the home screen's plan line shows the time left. When it runs out, the receiver
stops the video, drops the device's connections (the phone sees mirroring or the video stop) and
opens the Pro screen saying the free minutes are up. AirPlay video picks HLS variants up to the
plan's resolution (1080p on Free; the long side, so portrait videos aren't penalised).

### Video modes

With Pro, the receiver advertises a 3840×2160 display at up to 60 fps with H.265 support (AirPlay
feature bit 42). Clients that can encode HEVC (recent iPhones/iPads, Apple-silicon Macs) then
mirror in 4K H.265; others send H.264 at up to 1080p. Both match the TV's hardware decoders
(Realtek: HEVC up to 4096×2176 @ 60 fps, AVC up to 1920×1088). The fps value is only a ceiling —
the client lowers it when the content is static.

### AirPlay video (the AirPlay button in Safari and video apps)

Feature bits 0 and 4 are advertised (UxPlay's `-hls` mode), so instead of sending only audio,
apps hand the TV the video itself:

- **Safari and apps that use direct links** send the media URL (`.mp4`, `.m3u8`…), which the TV
  downloads and plays itself with ExoPlayer. HLS links without a `.m3u8` suffix are retried as HLS.
- **The YouTube app** sends HLS playlists over the AirPlay connection; the UxPlay core serves them
  at `http://localhost:<port>/master.m3u8` and the segments come straight from YouTube. The audio
  track follows the TV's language list.

The client keeps control: pause/play (`/rate`), seek (`/scrub`) and its progress bar
(`/playback-info`, answered from a snapshot so HTTP threads never wait for the player). When the
video ends or fails, the receiver reports it as finished and the session closes. Mirroring and
AirPlay video share the screen surface but never at the same time.

The TV remote controls the video too, and the phone follows on its next `/playback-info` poll:

| Key | Action |
|---|---|
| OK / Play-Pause | pause or resume |
| ← / → | back / forward 10 s |
| Rewind / Fast-forward | back / forward 30 s |
| ↑ / ↓ / Info | show the controls |
| Back / Stop | close the player at once and drop the client's AirPlay video connections, so the phone ends its session too (like Menu on an Apple TV) |

Mirroring can't be controlled from the TV: the picture is the phone's screen.

## Build

Requirements: Android SDK (platform 35, NDK 27.1.12297006, CMake 3.22.1), JDK 17+.

```sh
# one-off: OpenSSL libcrypto for both ABIs (already done; output in app/src/main/cpp/third_party/openssl)
scripts/build_openssl.sh <work-dir> "$PWD/app/src/main/cpp/third_party/openssl" 3.5.4

./gradlew assembleRelease     # signed with the local debug key, for sideloading
./gradlew bundleRelease       # Android App Bundle for Google Play
```

Native libraries are linked with `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` (16 KB page alignment on
`arm64-v8a`), as Google Play requires.

## Install on the TV

Enable *Developer options → USB/Network debugging* on the TV, then:

```sh
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
# let the receiver bring the mirroring screen to the front from the background
adb shell appops set com.isaacbenavides.pantavi SYSTEM_ALERT_WINDOW allow
```

Without adb, the home screen shows a button that opens the TV's *Display over other apps* setting
until the permission is granted; until then, keep Pantavi open while mirroring.

The receiver starts when **Pantavi** is opened from the launcher; nothing starts it on boot or
after an update. Once opened it stays discoverable in the background (until the TV restarts or
the system stops the app) and comes to the front by itself (waking the screensaver) when a
device starts mirroring or sends a video.

## TCL firmware notes

TCL's `tcl-service.jar` (`TclAppBootManagerImpl.canStartForegroundGo`) blocks foreground
services of third-party apps unless the app has a visible activity, is a TCL/system app, or is on a
cloud-delivered whitelist. The app therefore:

- starts its service from `ReceiverActivity` and promotes it to foreground every time the
  activity resumes.

Check with `adb shell dumpsys activity services com.isaacbenavides.pantavi | grep isForeground`.

## Limitations

- Content protected with FairPlay Streaming DRM (Apple TV+, Netflix…) stays black when mirrored —
  true of every non-Apple receiver.
- AirPlay video only works with unprotected streams. Apps that use FairPlay Streaming (Apple TV+,
  Netflix, Disney+, Max, Prime Video…) can't play on any non-Apple receiver; links that need the
  phone's cookies or are tied to its IP address fail too. Screen Mirroring still works for
  unprotected content.
- Audio and video are rendered as soon as they are decoded (no clock sync), which keeps latency
  low for mirroring.
- Anyone on the same network can connect (no PIN), like UxPlay's default.
- Free sessions end after 15 minutes of mirroring or AirPlay video (see [Free and Pro](#free-and-pro)).

## License

Pantavi © 2026 Isaac Benavides, licensed under the GPL-3.0, inherited from UxPlay
(`app/src/main/cpp/uxplay/LICENSE`). Third-party code:

- UxPlay 1.74 and playfair (GPL-3.0); files from shairplay and the mDNS responder (LGPL-2.1+)
- libplist (LGPL-2.1+)
- Apple ALAC decoder (Apache-2.0)
- OpenSSL 3.5.4 libcrypto (Apache-2.0); its source tarball ships with each published version
- llhttp, csrp (`uxplay/srp.c`), jsmn and time64 (in libplist) (MIT)
- AndroidX, Media3 and the Kotlin libraries (Apache-2.0)

The app shows the notices and full license texts under *Licenses and source code*.

### Source code

The complete source of every published version is at
<https://github.com/IsaacDSDS/pantavi-versions>, one tag per version. It is exported from the
development repository with `scripts/export_source.sh <tag> <clone>`.

### Trademarks

AirPlay is a trademark of Apple Inc. Pantavi is not affiliated with, sponsored or endorsed by
Apple. The GPL does not grant rights to the Pantavi name or logo (GPL-3.0 §7(e)): forks must use
their own name and icon.
