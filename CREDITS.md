# Credits

Couch Phone is a fork of **couch-tv** by Seagey: https://github.com/Seagey/couch-tv (MIT licence,
see LICENSE). From couch-tv, unchanged or lightly adapted:

- `tv-home/web/`: the TV home page (scenes, clock, weather, app dock, Home settings) and its
  background photo credits (`tv-home/web/backgrounds/CREDITS.md`).
- `remote/`: the phone remote.
- `android/web/shim.js` and parts of `android/app/src/main/java/app/couchphone/` (the web
  server, pairing, home page activity, weather), adapted from couch-tv's `on-tv/` app.

New in Couch Phone: root input (`root/Injector.java`, `Root.kt`), the DeX display handling
(`Dex.kt`), Wi-Fi-only access, and the build workflow.

No code is taken from Projectivy Launcher or KDE Connect. Their features are only used as ideas
and are implemented from scratch here.
