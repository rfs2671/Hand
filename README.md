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

Auto-connect: every added device is registered with Android's *Companion Device* service. When it comes into range the
system wakes Hand Bridge (even if the app was closed and the screen is off), which starts the foreground service and
connects. The bridge also restarts after a reboot.

## Optional: Tasker / MacroDroid
If your gesture app can fire intents, enable *Allow gesture broadcasts* and send `com.hand.bridge.GESTURE` with
extra `gesture=pinch` (mapped as `EVT_PINCH`) or `action=SELECT` (any action name).

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
