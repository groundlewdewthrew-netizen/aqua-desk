# AquaDesk native (wake word + PC remote)

Your original package was a Trusted Web Activity (a shell around
https://create-app-4a7921.netlify.app/) with no source, so it can't listen for wake words
or talk to a PC. This project replaces it with a native app that keeps the **same
package id** (`app.netlify.create_app_4a7921.twa`), so it can ship as an update on Play
using your existing `signing.keystore`.

## What it does
* **Web app** – loads in a WebView. A ⚙ button opens voice + PC settings.
* **Wake word** – foreground service using Android `SpeechRecognizer`. Say
  *"hey aqua"* (changeable). Then either:
  * *"hey aqua, connect to my computer"* → opens the PC viewer
  * *"hey aqua, open app"* → opens AquaDesk
  * only *"hey aqua"* → chime, then say the command.
  From the background it uses a full-screen notification (Android blocks direct launches).
* **Computer on your phone** – `RemoteDesktopActivity` shows the PC screen; tap = click,
  double-tap = double-click, long-press = right-click, drag = move pointer, two-finger drag =
  scroll, Drag button = hold left button, Keys button = type on the PC.
* The web page can call `AquaNative.startWakeWord()`, `.stopWakeWord()`, `.openRemote()`
  and listen for the `aquadesk-voice` event.

## Run it
**PC** (Python 3.9+): `pip install -r pc-agent/requirements.txt`, then
`python pc-agent/aquadesk_agent.py`. Note the printed address and PIN. Allow it through the
firewall. macOS: grant Screen Recording + Accessibility. Linux: X11 (Wayland blocks input).
`--view-only` disables control.

**Phone**: open this folder in Android Studio, create `local.properties` with
`storeFile`, `storePassword`, `keyAlias`, `keyPassword` (from your signing-key-info.txt),
raise `versionCode` above what's on Play, run *Build > Generate Signed Bundle*.
In the app: ⚙ → enter PC address, port, PIN → Connect.

## Honest limits
* **Untested on a device.** The PC agent protocol was tested end-to-end; the Android code
  was written without a compiler/SDK, so expect to fix a few compile errors.
* SpeechRecognizer wake words need internet, may beep/restart often, use more battery, and
  can be killed by OEM battery savers (exempt the app). For a reliable offline wake word swap
  `WakeWordService` for Porcupine or Vosk.
* **Security:** the link is unencrypted (PIN-only, 5 failed tries per IP, one phone at a time).
  Use home Wi-Fi or a VPN such as Tailscale; never expose the port to the internet.
* Play Store: declare the *microphone foreground service* type and a privacy policy
  mentioning microphone use. `assetlinks.json` is no longer needed.
* Keyboard: Backspace may not register on some soft keyboards; text, Enter and Esc work.

## Installing on a Benco (or any Android) phone without Android Studio
1. Put this folder in a new GitHub repo and push. Actions > "Build APK" builds it
   (a debug-signed APK, fine for installing on your own phone; Play uploads still need your keystore).
2. Download the `AquaDesk-debug-apk` artifact, unzip, copy `app-debug.apk` to the phone, open it
   and allow "install unknown apps". Uninstall the old AquaDesk first (different signing key).
3. Needs Android 8.0+. Wake words also need Google's speech service installed on the phone;
   some budget Bencos lack it.

## Open / close PC apps from chat or voice
The agent can launch and politely close apps. Only apps in its allowlist work (chrome, firefox, edge,
notepad, calculator, spotify, discord, vscode, vlc, steam, word, excel, powerpoint, terminal, files,
settings, task manager). Add your own in `pc-agent/apps.json` next to the agent:
`{"obs": "C:\\Program Files\\obs-studio\\bin\\64bit\\obs64.exe"}`.
Closing asks the app to quit normally (unsaved-work prompts still appear).

* **Voice:** "hey aqua, open chrome" / "close spotify on my computer" (works with the app in the background).
* **Chat (web page code):**
  ```js
  // in your chat's send handler:
  if (window.AquaNative && AquaNative.pcCommand(userText)) return;   // handled as a PC command
  // or explicitly: AquaNative.pcOpen("chrome"); AquaNative.pcClose("spotify"); AquaNative.pcList();
  window.addEventListener("aquadesk-pc", e => showMessage(e.detail.message));  // {action, app, ok, message}
  ```

### Power: shut down / restart / sleep / lock the PC
Voice: "hey aqua, shut down my computer" (also restart, sleep, lock; you must say computer/PC/laptop).
Shutdown and restart wait **30 seconds** and can be stopped with "hey aqua, cancel shutdown".
Chat: `AquaNative.pcShutdown() / pcRestart() / pcSleep() / pcLock() / pcCancel()` (or `pcCommand(text)`).
Shutdown is graceful (no force), so apps with unsaved work may prompt or delay it.
Once the PC is off or asleep, the phone can't wake it. Start the agent with `--no-power` to disable all of this.
