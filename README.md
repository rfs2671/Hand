# Hand Bridge

Android app that turns the phone into a Bluetooth **keyboard + mouse + media remote** and lets your
Meta Neural Band (through the air-gesture app you already use) drive it.

```
Neural Band ─► air-gesture app ─► phone input ─► Hand Bridge ─► Bluetooth HID ─► JMGO projector / XREAL host / TV / PC
                                                       └──────► "This phone only" ─► media + volume ─► Android Auto (BMW)
```

## Install
Download `HandBridge.apk` from the **Latest APK** release (or the Actions artifact), allow installing
unknown apps, install. Or build locally: `./gradlew assembleRelease` (needs the Android SDK, platform 35).

## First-time setup (in the app, top to bottom)
1. **Allow Bluetooth + notifications**.
2. **Turn on accessibility service** → Hand Bridge. This is how the app sees key presses from the band/app.
3. **Battery exemption**, then switch **Bridge running** on (persistent notification = it is alive with the screen off).
4. **Add a device**: put the projector/TV/glasses host in Bluetooth pairing mode and tap *Find and add*; or tap
   *Make this phone discoverable* and pair from the device's own Bluetooth menu, then *Add an already-paired device*.
   Pick a mode per device:
   - **Mouse / pointer** – D-pad gestures move a cursor, Select clicks, hold to accelerate.
   - **Remote / D-pad** – gestures send arrow keys / Enter (best for Android TV launchers such as JMGO's).
   - **This phone only** – nothing is sent over Bluetooth.
5. **Gestures → Learn a gesture**, perform the gesture on your band, then choose what it does. *Last input seen* shows
   exactly what the band/app emits, which is the quickest way to debug a mapping.
6. **Car:** add the car's Bluetooth with mode *This phone only*. When the car connects, the bridge starts so gestures
   reach the phone's media controls (which Android Auto follows).

Auto-connect: every added device is registered with Android's *Companion Device* service, and a Bluetooth link-up from a
remembered device also starts the bridge. Classic-Bluetooth devices only wake the app when they *connect*, not when
they come into range, so the bridge also retries the last device each time the screen turns on. The bridge restarts
after a reboot.

## Wi-Fi remote for Google TV / Android TV / JMGO (recommended)
Many projectors and TVs (JMGO N1 Ultra runs Android TV 11) accept the same remote protocol as Google's phone remote
app. It feels native: real D-pad, OK/Back/Home, volume, play/pause and power, and no Bluetooth pairing is involved.
*Devices → Add a device → Projector or TV on Wi-Fi*, tap the TV, type the 6-character code it shows, done. The phone
reconnects by itself when it joins that Wi-Fi, and finds the TV again if its IP address changes.

- The TV and phone must be on the same Wi-Fi (not a guest network).
- Power: while connected, *Power* toggles the TV. Many TVs shut their network off in standby, so switching one **on**
  from standby is only best-effort (a Wake-on-LAN signal, if the TV supports it); it depends on the model.
- While a Wi-Fi TV is connected it takes priority over Bluetooth devices.
- Map any gesture to *Power on / off* in the Gestures tab.

## If something doesn't work
- **Projector not listed on Wi-Fi:** the finder uses three methods at once (mDNS, Google Cast announcements, and a direct
  scan for the TV's pairing port), so it should list any Google TV/Android TV on the same Wi-Fi. If not, use *Enter its IP
  address* (Google Home shows it in the device settings).
- **Bluetooth shows "connected" on the projector but not on the phone:** this is normal for Android's own Bluetooth
  screen, which never lists a connection where the phone is the keyboard/mouse. Hand Bridge shows the real state on the
  *Devices* tab. If the projector paired before Hand Bridge was running it never learned the phone can act as a keyboard
  and mouse: remove the phone from the projector and from the phone's Bluetooth list, then use *Add a device →
  Bluetooth: pair from the projector's side*.
- **Home → Copy report** copies a plain-text report (Bluetooth and Wi-Fi state, saved devices, recent events).

## Testing the protocol code
`tools/atvtest/run.sh <scratch dir>` compiles the protocol layer (`app/.../atv`, plain Kotlin) and runs it against a
fake TV (`mock_tv.py`) that reads the real protobuf definitions: certificate, pairing, handshake, pings, key presses,
reconnect, certificate pinning, on TLS 1.2 and 1.3. See the script header for what it needs.

## Air Gestures (Neural Band)
Air Gestures broadcasts `dev.kb896.airgestures.GESTURE` for every gesture you set to **Send to automation** in Air
Gestures. Hand Bridge receives it while *Bridge running* is on (*Receive Air Gestures* switch, on by default). Names map
as `EVT_<GESTURE>`, e.g. `index_tap` → `EVT_INDEX_TAP`; a dial with `steps=+3` becomes three `EVT_DIAL_UP` inputs. The
default table covers swipes, index/middle taps, index drags and both dials; change any row in the app.

Optional *Use a "Hand Bridge" profile*: Hand Bridge asks Air Gestures to switch to a profile named `com.hand.bridge`
(create it in Air Gestures with your gestures on *Send to automation*) and receives band status. While Air Gestures
reports the band disconnected, incoming gestures are ignored.

Security: Air Gestures' broadcast is unprotected, so another app on the phone could fake gestures. On Android 14+ Hand
Bridge rejects a broadcast whose sender identifies itself as a different app, but senders can stay anonymous, so this
narrows the risk rather than removing it. Don't install apps you don't trust alongside it.

## Optional: Tasker / MacroDroid
Enable *Allow Tasker / MacroDroid intents* and send a broadcast with action `com.hand.bridge.GESTURE`, **package
`com.hand.bridge`**, and extra `gesture=pinch` (mapped as `EVT_PINCH`, plus `steps` for dials) or `action=SELECT`.

## Release signing (one-time, so updates install over each other)
Without these secrets CI signs with a throwaway debug key, and every new APK must be installed after uninstalling the
old one. Create a key once and keep a backup (losing it means one more uninstall):

```
keytool -genkeypair -v -keystore handbridge.jks -alias handbridge -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 handbridge.jks > handbridge.jks.b64
```

In GitHub → Settings → Secrets and variables → Actions, add `HB_KEYSTORE_BASE64` (contents of the .b64 file),
`HB_STORE_PASSWORD`, `HB_KEY_ALIAS` (`handbridge`) and `HB_KEY_PASSWORD`. Never commit the .jks file. The first build
signed with it needs one uninstall of the debug-signed app; after that, updates install normally.

## Limits you should know about
- **A powered-off phone can't do anything.** "Phone off" works as screen-off/locked/app closed, and after reboot.
- **BMW / Android Auto is not a Bluetooth HID host**, so the phone can't send it mouse/arrow keys. What does work is
  *This phone only* mode: play/pause, next/previous, volume, back/home reach the phone and Android Auto follows them.
- **XREAL glasses** are a display, not an input host: connect them as usual and point Hand Bridge at the device that
  runs the UI (the phone itself – use Local mode – or an Android host such as the XREAL Beam Pro over Bluetooth).
- I don't control what the air-gesture app emits. If it only produces on-screen overlays or private APIs and no key or
  intent events, use its own intent/automation hooks with the broadcast receiver above.
- Not all phones implement Android's Bluetooth HID *device* profile (most Pixel/Samsung/Motorola do).
- Key events can be delivered to accessibility services only while the device is awake on some OEMs; the broadcast path
  has no such restriction.
