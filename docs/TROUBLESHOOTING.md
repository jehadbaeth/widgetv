# Troubleshooting

Commands below assume you are in the repo with `TV_IP` set (or `tools/tv.conf`), and use `adb` as
shorthand for `adb -s <tv ip>:5555`. The launcher's package is the `PKG` value in `tools/env.sh`.

## Connecting

**`adb connect` times out or is refused.** Network debugging is off, or the TV changed address. Check
Settings > System > Developer options, and the IP under Settings > Network. Some Google TV builds only
offer **Wireless debugging** (pairing code) instead of plain port 5555:

```bash
adb pair <ip>:<pairing port>       # enter the 6-digit code shown on the TV
adb connect <ip>:<debug port>      # the port on the Wireless debugging screen
adb tcpip 5555                     # optional: then connect to <ip>:5555 as usual
```

Wireless debugging switches itself off whenever Wi-Fi drops.

**"unauthorized".** Accept "Allow USB debugging?" on the TV (tick "Always allow" if you'll come back).

**macOS: "No route to host" although the TV is reachable.** macOS Local Network privacy blocks the
terminal app. Allow it under System Settings > Privacy & Security > Local Network, and restart the
terminal.

**Windows Git Bash turns `/data/local/tmp` into `C:/Program Files/Git/data/...`.** `tools/env.sh` sets
`MSYS_NO_PATHCONV=1`; do the same if you type adb commands by hand.

## Install fails

- **`adb install` hangs on Android 14.** It waits for a confirmation the TV never shows. Push and
  install from the shell instead (this is what `tools/build-install.sh` does):

  ```bash
  adb push app-debug.apk /data/local/tmp/l.apk
  adb shell pm install -r /data/local/tmp/l.apk
  ```
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: an older build signed with another debug key is installed.
  Uninstall it first (`adb shell pm uninstall <package>`), then reinstall. Prayer settings reset.
- **`INSTALL_FAILED_VERIFICATION_FAILURE`** on every APK: some China-market firmware refuses all
  sideloads. The debloat still works; the launcher cannot be installed on that TV.
- **Build fails with "SDK location not found"**: run the build through `tools/build-install.sh`, which
  writes `launcher/local.properties`, or set `ANDROID_HOME`.

## Home takeover

`cmd package set-home-activity` often prints `Success` on Google TV and changes nothing: the stock
launcher's HOME priority beats any sideloaded app. The order that works:

1. List who claims HOME:

   ```bash
   adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
2. Disable the stock launcher (`com.google.android.apps.tv.launcherx` on Google TV,
   `com.google.android.tvlauncher` on older Android TV):

   ```bash
   tools/disable.sh home com.google.android.apps.tv.launcherx
   ```
3. Press HOME. If the **setup wizard** (`com.google.android.tungsten.setupwraith`, a "Recovery" screen)
   now takes HOME, check who owns the saved Wi-Fi networks **before** disabling it:

   ```bash
   adb shell dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u
   ```

   - Only `android.uid.system` (or similar): safe, `tools/disable.sh home com.google.android.tungsten.setupwraith`.
   - Any entry mentioning `setupwraith`: the network was saved during first setup and belongs to the
     wizard. Disabling it deletes that network and the TV drops off Wi-Fi (and ADB). On the TV, forget
     the network and join it again under Settings > Network, check again, then disable.
4. Leave the `FallbackHome` activities (`com.android.tv.settings`, vendor settings) alone: they are the
   safety net if the launcher ever crashes.
5. Confirm with the only check that counts:

   ```bash
   adb shell input keyevent KEYCODE_HOME
   adb shell dumpsys activity activities | grep -m1 mFocusedApp
   ```

## The screen

**Weather never appears.** It needs a successful fetch first and hides when older than 3 hours. Check
`weather_enabled`, the coordinates in `config.xml`, and the log: `adb logcat -s SkyLauncher | grep weather`.
Private DNS ad-blockers do not block Open-Meteo.

**Times look off by a few minutes.** Use the Prayer settings panel (move Up to the timeline, press OK)
to change the method or Asr school, or add per-prayer offsets to match your masjid's timetable.

**Prayer times look wrong by an hour.** The TV's time zone is wrong: Settings > System > Date & time.

**The second clock's flag is missing.** `second_flag` must name a drawable in
`launcher/app/src/main/res/drawable/`; an unknown name hides the flag (the log says so).

**The picture goes black after a while but the TV stays on.** The Fire Stick went to sleep and stopped
sending HDMI. Fire OS has a hidden 20 minute sleep timer (`secure sleep_timeout`, 1200000 ms) that the
screensaver setting does not touch, so Never only stops the screensaver. The home screen keeps the
screen on while it is in front; inside other apps the stick still sleeps. If it still goes black on the
home screen, check `adb shell settings get secure attentive_timeout`: a positive value is Android 11's
inattentive sleep, which ignores apps, and `adb shell settings put secure attentive_timeout -1` turns it
off. Firmware updates can put these values back.

**Live TV or HDMI screenshots are black or 0 bytes.** Content protection. Check what is on screen with
`adb shell dumpsys activity activities | grep -m1 topResumedActivity` instead.

**A remote button does nothing after the debloat.** On Google TV the stock launcher handled some
buttons. Note which ones and re-enable just that package with `tools/restore.sh <package>` if it matters.

**Never open factory or service-menu apps** (names containing `factory`, `servicemenu`, `fac`, `AT`)
to see what they do. `adb shell dumpsys package <name>` describes a package without starting it.

## Lost `state/`

`state/disabled.txt` (your disable list) and `state/backup-*/` live only on the computer you ran this from.
Keep the repo folder. If it is gone, `adb shell pm list packages -d --user 0` on the TV lists what is
disabled; save that as a new `state/disabled.txt` before using `restore.sh` or `reapply.sh`.

## Preview hooks (debug builds)

The installed build is a debug build, so it accepts a broadcast that previews other times of day without
touching the TV's clock. Everything time-based (both clocks, date, Hijri date, sky, timeline, countdown,
thought) follows the fake time. Nothing is saved; `auto` or a reboot returns to the real clock. They only
work while ADB is on, so they are inert once ADB is closed. Replace `<package>` with `PKG` from
`tools/env.sh`:

```bash
A=<package>.DEBUG_SKY
adb shell am broadcast -a $A --es fake_time 05:30            # any HH:mm
adb shell am broadcast -a $A --es fake_date 2026-10-23       # a Friday: Jumu'ah thought, that day's times
adb shell am broadcast -a $A --es period golden              # dawn|morning|midday|golden|sunset|night|auto
adb shell am broadcast -a $A --ei pattern 1                  # 0..3 wallpaper patterns
adb shell am broadcast -a $A --ez weather_refresh true       # fetch weather now
adb shell am broadcast -a $A --es fake_time auto --es fake_date auto --es period auto   # back to normal
```

Weather stays real (current conditions) whatever time you fake. The log shows what was applied:
`adb logcat -s SkyLauncher`.
