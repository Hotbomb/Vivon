# Couch Phone

A TV box made from a rooted Samsung Galaxy S10e plugged into a TV in **Samsung DeX** mode:

- **TV home screen** on the TV, with couch-tv's scenes, clock and weather. It shows every app on
  the phone, and you can reorder, hide and pin your favourite apps. You can drive it with the
  arrow keys and OK.
- **Phone remote:** on any other phone, open a web page and get a remote (d-pad, OK, back, home,
  volume and more).

It is one app (one APK). After you install it, no computer is needed.

Couch Phone is a fork of [couch-tv](https://github.com/Seagey/couch-tv) by Seagey (MIT licence).
The home screen and the phone remote are couch-tv's own designs. See [CREDITS.md](CREDITS.md).

## What you need

- A Galaxy S10e (or another Samsung phone with DeX) that is **rooted with Magisk**.
- A USB-C to HDMI adapter to plug it into the TV.
- Wi-Fi. The phone you use as the remote must be on the **same Wi-Fi**.

## One-time setup on GitHub (only the repo owner, about 1 minute)

GitHub builds the app for you. To make every new version install over the old one, the builds
are signed with one key, which is locked with a password. Give GitHub that password once:

1. On GitHub, open this repository, then **Settings**, then **Secrets and variables**, then
   **Actions**.
2. Press **New repository secret**.
3. Name: `SIGNING_PASSWORD`. Secret: the password you were given with this app (the
   `SIGNING_PASSWORD.txt` file). Press **Add secret**.
4. Open the **Actions** tab, choose **Build APK** on the left, press **Run workflow**, then
   **Run workflow**. After about 5 minutes the new APK is on the **Releases** page.

Keep the password somewhere safe. If it is lost, you can still make a new key, but you would
have to uninstall the app once before installing the new version.

## Install on the S10e

1. On the S10e, open this repository's **Releases** page in the browser (on the repository
   page, tap **Releases** on the right, or under "About" on a phone).
2. Under the newest release, tap **couch-phone-0.1.N.apk** to download it.
3. Open the download. If Android asks, allow your browser to **install unknown apps**, then tap
   **Install**.
4. Open **Couch Phone**. When **Magisk** asks to grant superuser, tap **Grant**.
5. On the Couch Phone screen, tap **Battery: set to Unrestricted**. Under **Battery**, choose
   **Unrestricted**. This keeps Samsung from putting the app to sleep.

To update later, download the newer APK from Releases and install it the same way. Your
settings and paired phones stay.

## Use it

1. Plug the S10e into the TV. DeX starts, and a few seconds later the Couch Phone home screen
   opens full screen on the TV. (If it does not, open Couch Phone and tap **Open the home screen
   on the TV**.)
2. On your other phone, open the address shown on the Couch Phone screen, for example
   `http://192.168.1.23:5400`.
3. Type the 6-digit code shown on the TV. You only do this once per phone. Then tap **Share**
   and **Add to Home Screen** for an app-like remote.

**Home screen controls** (remote, or a keyboard on the TV): arrow keys move, OK opens. **Hold OK
on an app** to move it, hide it, or see its info. **Home settings** (the gear) has the look, the
weather place, and hidden apps. The first row is your favourites.

**The phone's own screen** goes fully off while DeX is on the TV (the phone stays awake, so the TV
keeps going), and comes back when you unplug. Apps that were open on the phone move to the TV when
you open them from the home. Both need root.

**TV screen** (on the remote, pull down for the TV sheet): the resolution DeX draws at, text and
icon size, and the phone's screen on or off. If the picture looks soft, also check the TV: set its
HDMI input to PC or Game mode and its picture size to Just Scan / Screen Fit (no overscan).

**Apps full screen:** Couch Phone turns on DeX's own **Auto hide taskbar** by itself (with root), so
apps fill the TV and the taskbar only comes back when the pointer touches the bottom edge. If the
taskbar still shows, turn that switch on yourself once: right-click the taskbar (two-finger tap on
the touchpad), or on the S10e open Settings, Samsung DeX.

## Safety

- The remote only answers devices on the **same Wi-Fi** as the S10e. Anything coming from mobile
  data or the internet is refused.
- Each phone must type a code shown on the TV once. A code lasts 3 minutes, works once, and is
  thrown away after 5 wrong tries.
- **Forget all paired phones** on the Couch Phone screen signs every phone out.

## Stage 2: keyboard, speak, air mouse, Bluetooth and Wi-Fi

**On the phone remote**, a new row above the play and volume buttons:

- **Keyboard:** what you type is typed on the TV as you type, into whatever box is open there
  (YouTube search, a browser, a Wi-Fi password). Accents and emoji work too. Below the box:
  Delete, left, right, Tab and Enter.
- **Speak:** tap and say it. The words are typed into the box on the TV. Then tap **Enter** to search.
- **Air mouse:** hold the phone like a TV remote, point at the TV and turn it: a mouse pointer on
  the TV follows. The big **Click** button clicks (hold it to drag), with **Right click**, a
  **Scroll** strip (drag up and down on it), **Back** and **Home**. Five speeds.
- **Touchpad:** tap the small arrow at the top left of the big pad. Then drag to move the
  pointer, tap to click, two fingers to scroll, a two-finger tap to right click.

**On the TV home**, two new buttons in the top row (move up with the remote):

- **Bluetooth:** turn it on or off, see paired devices, connect, disconnect and unpair, and
  **Search for devices** to pair new headphones, speakers, controllers or keyboards.
- **Wi-Fi:** turn it on or off, see networks nearby and the one in use, join one (type the
  password with the remote's **Keyboard**), and forget saved ones.

Both screens end with a link to Android's own Bluetooth or Wi-Fi settings, for anything they
cannot do. If the buttons are not in the top row, open **Home settings** (the gear), find the top
row's buttons, and switch on Bluetooth and Network.

### The secure remote (once per phone, for Speak and the air mouse)

Phones only give a web page the microphone and the motion sensors on a secure `https://` address.
So the S10e now also serves the remote at `https://<its address>:5401`. The first time you tap
**Speak** or **Air mouse**, the remote shows the steps:

1. **Trust the TV box (recommended).** iPhone: tap **Get the Couch Phone profile**, **Allow**,
   then **Settings > General > VPN & Device Management > Couch Phone remote > Install**, then
   **Settings > General > About > Certificate Trust Settings** and switch on **Couch Phone home
   root**. Android: tap **Get the Couch Phone certificate**, then in **Settings** search for
   **CA certificate**, tap **Install anyway** and pick `couch-phone.crt`. If you skip this step,
   the browser shows a "not private" warning once: tap **Advanced**, then **Proceed**.
2. **Open the secure remote.** It is already paired: no new code.
3. **Add it to your Home Screen** in place of the old one.

The certificate only works for your Couch Phone, which makes it on the S10e itself the first time
it starts.

## Update the S10e to a new version

You do **not** need to uninstall, root again or set anything up again.

1. On the S10e, open this repository's **Releases** page in the browser.
2. Under the newest release, tap **couch-phone-0.1.N.apk** to download it (the highest N).
3. Open the download and tap **Update** (or **Install**).
4. Open **Couch Phone** once. If Magisk asks for superuser again, tap **Grant**.

Your settings, paired phones, favourites and hidden apps all stay.

## What works so far

| | Stage 1 | Stage 2 |
|---|---|---|
| Home screen on the TV, all apps, reorder, hide, favourites, scenes, clock, weather | yes | |
| Starts at boot, opens on the TV when DeX starts | yes | |
| Remote: d-pad, OK, back, home, volume | yes | |
| Remote: play/pause, next, previous | yes (basic) | |
| Remote: app grid to open any app | yes (name tiles) | |
| Remote: type text | letters and numbers | live keyboard, accents and emoji |
| Remote: speak to type | | yes (secure remote) |
| Remote: touchpad, mouse pointer, scroll, right click | | yes |
| Remote: air mouse (gyro) with click, right click, scroll | | yes (secure remote) |
| TV: Bluetooth on/off, pair, unpair, connect, disconnect | | yes |
| TV: Wi-Fi on/off, join with a password, forget | | yes |

**Known limits.** Unpairing, connecting and disconnecting Bluetooth use hidden Android calls; if
the phone refuses one, the screen says so, and **Android's own Bluetooth settings** at the bottom
always works. A keyboard that needs a code typed shows the code on the TV; a code to confirm may
also pop up on the phone's own screen. Wi-Fi networks with a company sign-in (802.1X) need
Android's own Wi-Fi settings. Changing Wi-Fi from the TV disconnects the phone remote until your
phone is on the same network.

## How it works (for the curious)

- The app runs a small web server on port 5400. It serves couch-tv's phone remote and the TV home
  page, and the API they both use. Port 5401 serves the same over `https`, with a certificate the
  app makes itself.
- The TV pointer is a virtual mouse made with Linux `uinput` by a small root helper
  (`android/native/couchmouse.c`), so DeX shows a real mouse pointer.
- Wi-Fi uses Android's own `cmd wifi` tool as root; Bluetooth uses Android's Bluetooth API, with
  its permissions granted through root so there are no pop-ups.
- Keys go through a small helper that runs as root (started with Magisk's `su`). It stays running,
  so a key press costs well under a millisecond, and each key is sent to the DeX display, not the
  phone's own screen.
- Samsung's DeX desktop always owns the TV's "home", so the remote's Home button opens Couch Phone's
  home instead of Samsung's desktop.

## Building it yourself (optional)

GitHub does this on every push (`.github/workflows/build.yml`). By hand: build the mouse helper
with the Android NDK into `android/app/src/main/jniLibs/<abi>/libcouchmouse.so` (see the
workflow), build the remote with
`cd remote && npm ci && npm run build`, then run `gradle -p android assembleRelease` with the
Android SDK installed.

## Licence

MIT, like couch-tv. See [LICENSE](LICENSE).
