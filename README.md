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

**If the DeX taskbar still shows on the TV:** on the S10e, open DeX settings and turn on **Auto
hide taskbar**.

## Safety

- The remote only answers devices on the **same Wi-Fi** as the S10e. Anything coming from mobile
  data or the internet is refused.
- Each phone must type a code shown on the TV once. A code lasts 3 minutes, works once, and is
  thrown away after 5 wrong tries.
- **Forget all paired phones** on the Couch Phone screen signs every phone out.

## What works so far

| | Stage 1 (now) | Coming in stage 2 |
|---|---|---|
| Home screen on the TV, all apps, reorder, hide, favourites, scenes, clock, weather | yes | |
| Starts at boot, opens on the TV when DeX starts | yes | |
| Remote: d-pad, OK, back, home, volume | yes | |
| Remote: play/pause, next, previous | yes (basic) | |
| Remote: app grid to open any app | yes (name tiles) | app icons |
| Remote: type text | yes (letters and numbers) | accents and emoji |
| Remote: touchpad with mouse pointer and scroll | | yes |

## How it works (for the curious)

- The app runs a small web server on port 5400. It serves couch-tv's phone remote and the TV home
  page, and the API they both use.
- Keys go through a small helper that runs as root (started with Magisk's `su`). It stays running,
  so a key press costs well under a millisecond, and each key is sent to the DeX display, not the
  phone's own screen.
- Samsung's DeX desktop always owns the TV's "home", so the remote's Home button opens Couch Phone's
  home instead of Samsung's desktop.

## Building it yourself (optional)

GitHub does this on every push (`.github/workflows/build.yml`). By hand: build the remote with
`cd remote && npm ci && npm run build`, then run `gradle -p android assembleRelease` with the
Android SDK installed.

## Licence

MIT, like couch-tv. See [LICENSE](LICENSE).
