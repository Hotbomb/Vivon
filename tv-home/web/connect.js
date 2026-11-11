// Bluetooth and Wi-Fi (Couch Phone): two screens on the TV, opened from the Bluetooth and Wi-Fi
// buttons in the top row (or the Input menu). They talk to the phone through /api/bluetooth and
// /api/wifi (Bt.kt and Wifi.kt in the app).
//
// On the screen: up and down move between rows; left and right pick a row's button (Connect,
// Unpair, Forget...); OK presses it; Back closes the screen (or the password box). A Wi-Fi password
// is typed with the phone remote's Keyboard, straight into the box on the TV.
//
// app.js owns the keys: while zone is "connect" it hands them here (move, ok, back, typingKey).
// Original code for couch-phone.
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var esc = function (v) { return String(v == null ? "" : v).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); };

  // English words, added to i18n.js's table so the other languages fall back to them.
  var EN = {
    cn_bt_title: "Bluetooth",
    cn_wifi_title: "Wi-Fi",
    cn_on: "On",
    cn_off: "Off",
    cn_bt_toggle: "Bluetooth",
    cn_wifi_toggle: "Wi-Fi",
    cn_paired: "Paired devices",
    cn_none_paired: "No paired devices yet.",
    cn_other: "Other devices",
    cn_search: "Search for devices",
    cn_searching: "Searching… put your device in pairing mode",
    cn_search_again: "Search again",
    cn_found_none: "Nothing found yet. Put the device in pairing mode, close to the TV.",
    cn_connected: "Connected",
    cn_not_connected: "Not connected",
    cn_connect: "Connect",
    cn_disconnect: "Disconnect",
    cn_unpair: "Unpair",
    cn_pair: "Pair",
    cn_cancel: "Cancel",
    cn_pairing_type: "Type {code} on {name}, then press Enter on it.",
    cn_pairing_confirm: "Check the code {code} on {name} and confirm there. If the phone asks too, press Pair on its screen.",
    cn_pairing_pin: "{name} wants a PIN: confirm it on the phone's screen (often 0000 or 1234).",
    cn_bt_off_note: "Bluetooth is off.",
    cn_networks: "Networks",
    cn_wifi_off_note: "Wi-Fi is off. The phone remote only works over Wi-Fi: turn it on with a keyboard or the phone itself if the remote stops.",
    cn_wifi_warn: "The phone remote works over this Wi-Fi. Moving to another network disconnects it until your phone joins the same one.",
    cn_scan: "Look again",
    cn_saved: "Saved",
    cn_open: "Open",
    cn_secured: "Password",
    cn_away: "Not in range",
    cn_join: "Join",
    cn_forget: "Forget",
    cn_show: "Show",
    cn_hide: "Hide",
    cn_password_for: "Password for {name}",
    cn_password_hint: "Type it with the Keyboard on the phone remote, then press OK.",
    cn_confirm_wifi_off: "Press OK again to turn Wi-Fi off. The phone remote stops working until Wi-Fi is back on.",
    cn_confirm_move: "Press OK again to join {name}. The phone remote disconnects until your phone is on {name} too.",
    cn_confirm_forget: "Press OK again to forget {name}.",
    cn_confirm_unpair: "Press OK again to unpair {name}.",
    cn_system: "Android's own {name} settings",
    cn_system_sub: "Everything else, on the phone's settings screen",
    cn_working: "One moment…",
    cn_joining: "Joining {name}…",
    cn_hint: "Up and down to choose, left and right for the buttons, OK to press, Back to close.",
    cn_kind_headphones: "Headphones", cn_kind_speaker: "Speaker", cn_kind_keyboard: "Keyboard", cn_kind_mouse: "Mouse",
    cn_kind_gamepad: "Controller", cn_kind_phone: "Phone", cn_kind_computer: "Computer", cn_kind_watch: "Watch",
    cn_kind_tv: "TV", cn_kind_other: "Device"
  };
  try { var S = window.I18N && window.I18N.strings; if (S && S.en) for (var k in EN) if (!(k in S.en)) S.en[k] = EN[k]; } catch (e) {}
  var t = function (k, v) {
    var s = window.I18N ? window.I18N.t(k, v) : k;
    if (s === k && EN[k]) { s = EN[k]; if (v) s = s.replace(/\{(\w+)\}/g, function (m, n) { return v[n] != null ? v[n] : m; }); }
    return s;
  };

  var ICON = {
    bluetooth: '<svg viewBox="0 0 24 24"><path d="M6.5 7.5l11 9-5.5 4.5v-18l5.5 4.5-11 9"/></svg>',
    wifi: '<svg viewBox="0 0 24 24"><path d="M2.5 9a14 14 0 0 1 19 0M5.7 12.5a9.5 9.5 0 0 1 12.6 0M8.9 16a4.8 4.8 0 0 1 6.2 0"/><circle cx="12" cy="19.3" r="1"/></svg>',
    headphones: '<svg viewBox="0 0 24 24"><path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3.5" y="14" width="4" height="6.5" rx="1.6"/><rect x="16.5" y="14" width="4" height="6.5" rx="1.6"/></svg>',
    speaker: '<svg viewBox="0 0 24 24"><rect x="6" y="3" width="12" height="18" rx="2.5"/><circle cx="12" cy="14.5" r="3"/><path d="M12 7h.01"/></svg>',
    keyboard: '<svg viewBox="0 0 24 24"><rect x="2.5" y="6" width="19" height="12" rx="2.5"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7.5 14h9"/></svg>',
    mouse: '<svg viewBox="0 0 24 24"><rect x="6.5" y="3" width="11" height="18" rx="5.5"/><path d="M12 3v6.5"/></svg>',
    gamepad: '<svg viewBox="0 0 24 24"><path d="M7 7h10a4.5 4.5 0 0 1 4.4 5.4l-1 4.6a2.5 2.5 0 0 1-4.2 1.2L14 16h-4l-2.2 2.2a2.5 2.5 0 0 1-4.2-1.2l-1-4.6A4.5 4.5 0 0 1 7 7z"/><path d="M8 10.5v3M6.5 12h3M15.5 11h.01M17.5 13h.01"/></svg>',
    phone: '<svg viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>',
    computer: '<svg viewBox="0 0 24 24"><rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M2 19.5h20"/></svg>',
    watch: '<svg viewBox="0 0 24 24"><rect x="6.5" y="6.5" width="11" height="11" rx="3"/><path d="M9 6.5l.7-3.5h4.6l.7 3.5M9 17.5l.7 3.5h4.6l.7-3.5"/></svg>',
    tv: '<svg viewBox="0 0 24 24"><rect x="2.5" y="4" width="19" height="13" rx="2.5"/><path d="M8 21h8M12 17v4"/></svg>',
    other: '<svg viewBox="0 0 24 24"><path d="M6.5 7.5l11 9-5.5 4.5v-18l5.5 4.5-11 9"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/></svg>',
    gear: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.2"/><path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.5 5.5l1.8 1.8M16.7 16.7l1.8 1.8M5.5 18.5l1.8-1.8M16.7 7.3l1.8-1.8"/></svg>',
    lock: '<svg viewBox="0 0 24 24"><rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/></svg>'
  };
  function bars(n) {
    var s = '<svg viewBox="0 0 24 24" class="cn-bars">';
    for (var i = 0; i < 4; i++) s += '<rect x="' + (3 + i * 5) + '" y="' + (17 - i * 4) + '" width="3" height="' + (4 + i * 4) + '" rx="1"' + (i < n ? ' class="lit"' : "") + "/>";
    return s + "</svg>";
  }

  // state
  var S2 = { open: false, kind: null, data: null, err: "", note: "", row: 0, btn: 0, busy: false, timer: null,
    pw: null, pwShow: false, armed: null, armTimer: null, api: null, onClose: null };
  var rows = [];   // what is on screen: {id, icon, label, sub, right, acts:[{id,label}], head, info, input}

  function api(method, path, body) { return S2.api(method, path, body, 25000); }

  function build() {
    rows = [];
    var d = S2.data;
    if (S2.kind === "bluetooth") buildBt(d); else buildWifi(d);
    rows.push({ id: "system", icon: ICON.gear, label: t("cn_system", { name: t(S2.kind === "bluetooth" ? "cn_bt_title" : "cn_wifi_title") }), sub: t("cn_system_sub"), acts: [{ id: "system", label: t("cn_open") }] });
  }

  function onOff(on) { return [{ id: "toggle", label: on ? t("cn_on") : t("cn_off") }]; }

  function buildBt(d) {
    if (!d) return;
    rows.push({ id: "toggle", icon: ICON.bluetooth, label: t("cn_bt_toggle"), sub: d.on ? "" : t("cn_bt_off_note"), acts: onOff(d.on), toggle: d.on });
    if (!d.on) return;
    if (d.pairing) {
      var p = d.pairing, msg = p.kind === "type_on_device" ? "cn_pairing_type" : p.kind === "pin" ? "cn_pairing_pin" : "cn_pairing_confirm";
      rows.push({ info: true, label: t(msg, { code: p.code || "", name: p.name }), acts: [{ id: "cancel:" + p.address, label: t("cn_cancel") }] });
    }
    rows.push({ head: true, label: t("cn_paired") });
    if (!d.paired || !d.paired.length) rows.push({ info: true, quiet: true, label: t("cn_none_paired") });
    (d.paired || []).forEach(function (x) {
      rows.push({ id: "dev:" + x.address, icon: ICON[x.kind] || ICON.other, label: x.name,
        sub: (x.connected ? t("cn_connected") : t("cn_not_connected")) + " · " + t("cn_kind_" + (x.kind || "other")), on: x.connected,
        acts: [{ id: (x.connected ? "disconnect:" : "connect:") + x.address, label: x.connected ? t("cn_disconnect") : t("cn_connect") },
               { id: "unpair:" + x.address, label: t("cn_unpair"), name: x.name }] });
    });
    rows.push({ head: true, label: t("cn_other") });
    rows.push({ id: "search", icon: ICON.search, label: d.searching ? t("cn_searching") : (d.found && d.found.length ? t("cn_search_again") : t("cn_search")),
      busy: d.searching, acts: [{ id: d.searching ? "stop" : "search", label: d.searching ? t("cn_cancel") : t("cn_search") }] });
    (d.found || []).forEach(function (x) {
      rows.push({ id: "new:" + x.address, icon: ICON[x.kind] || ICON.other, label: x.name, sub: t("cn_kind_" + (x.kind || "other")),
        acts: [{ id: "pair:" + x.address, label: t("cn_pair") }] });
    });
    if (d.searching === false && d.found && !d.found.length && S2.searched) rows.push({ info: true, quiet: true, label: t("cn_found_none") });
  }

  function buildWifi(d) {
    if (!d) return;
    rows.push({ id: "toggle", icon: ICON.wifi, label: t("cn_wifi_toggle"), sub: d.on ? (d.current ? d.current + (d.address ? " · " + d.address : "") : "") : t("cn_wifi_off_note"), acts: onOff(d.on), toggle: d.on });
    if (!d.on) return;
    rows.push({ info: true, quiet: true, label: t("cn_wifi_warn") });
    rows.push({ head: true, label: t("cn_networks") });
    (d.networks || []).forEach(function (n) {
      var acts = [];
      if (!n.current && !n.away) acts.push({ id: "join", label: t("cn_join"), net: n });
      if (n.saved) acts.push({ id: "forget", label: t("cn_forget"), net: n });
      var sub = n.current ? t("cn_connected") : n.away ? t("cn_away") : n.saved ? t("cn_saved") : (n.security === "open" || n.security === "owe" ? t("cn_open") : t("cn_secured"));
      if (n.band && !n.away) sub += " · " + n.band;
      rows.push({ id: "net:" + n.ssid, icon: ICON.wifi, label: n.ssid, sub: sub, on: n.current, right: n.away ? "" : bars(n.bars || 0) + (n.security && n.security !== "open" && n.security !== "owe" ? ICON.lock : ""), acts: acts });
      if (S2.pw && S2.pw.ssid === n.ssid) rows.push({ id: "pw", input: true, label: t("cn_password_for", { name: n.ssid }), sub: t("cn_password_hint"),
        acts: [{ id: "pwjoin", label: t("cn_join") }, { id: "pwshow", label: S2.pwShow ? t("cn_hide") : t("cn_show") }, { id: "pwcancel", label: t("cn_cancel") }] });
    });
    rows.push({ id: "scan", icon: ICON.search, label: t("cn_scan"), acts: [{ id: "scan", label: t("cn_scan") }] });
  }

  // rows that take the focus
  function focusable(r) { return r && !r.head && r.acts && r.acts.length; }
  function fixFocus() {
    if (!rows.length) { S2.row = 0; return; }
    if (S2.row >= rows.length) S2.row = rows.length - 1;
    if (!focusable(rows[S2.row])) {
      var i = S2.row; while (i < rows.length && !focusable(rows[i])) i++;
      if (i >= rows.length) { i = S2.row; while (i >= 0 && !focusable(rows[i])) i--; }
      S2.row = Math.max(0, i);
    }
    var r = rows[S2.row];
    if (r && r.acts && S2.btn >= r.acts.length) S2.btn = r.acts.length - 1;
    if (S2.btn < 0) S2.btn = 0;
  }

  function paint() {
    var el = $("connect");
    if (!el) return;
    build(); fixFocus();
    var html = '<h1 class="cn-title">' + (S2.kind === "bluetooth" ? ICON.bluetooth : ICON.wifi) + "<span>" + esc(t(S2.kind === "bluetooth" ? "cn_bt_title" : "cn_wifi_title")) + "</span></h1>";
    html += '<p class="cn-msg' + (S2.err ? " err" : "") + '" aria-live="polite">' + esc(S2.err || S2.note || (S2.busy ? t("cn_working") : (S2.data && S2.data.note) || "")) + "</p>";
    html += '<div class="cn-list" id="cn-list">';
    if (!S2.data && !S2.err) html += '<p class="cn-info quiet">' + esc(t("cn_working")) + "</p>";
    rows.forEach(function (r, i) {
      if (r.head) { html += '<h2 class="cn-head">' + esc(r.label) + "</h2>"; return; }
      var focus = i === S2.row;
      if (r.info && !(r.acts && r.acts.length)) { html += '<p class="cn-info' + (r.quiet ? " quiet" : "") + '">' + esc(r.label) + "</p>"; return; }
      html += '<div class="cn-row' + (focus ? " focus" : "") + (r.on ? " on" : "") + (r.info ? " info" : "") + (r.input ? " input" : "") + '" data-i="' + i + '">';
      if (r.icon) html += '<i class="cn-ico' + (r.busy ? " busy" : "") + '">' + r.icon + "</i>";
      html += '<span class="cn-text"><b>' + esc(r.label) + "</b>" + (r.sub ? "<small>" + esc(r.sub) + "</small>" : "");
      if (r.input) html += '<input id="cn-pw" class="cn-pw" type="' + (S2.pwShow ? "text" : "password") + '" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" value="' + esc(S2.pw ? S2.pw.value : "") + '">';
      html += "</span>";
      if (r.right) html += '<span class="cn-right">' + r.right + "</span>";
      html += '<span class="cn-acts">' + (r.acts || []).map(function (a, j) {
        var armed = S2.armed && S2.armed.id === a.id && S2.armed.row === r.id;
        return '<span class="cn-btn' + (focus && j === S2.btn ? " focus" : "") + (a.id === "toggle" && r.toggle ? " on" : "") + (armed ? " armed" : "") + '">' + esc(a.label) + "</span>";
      }).join("") + "</span></div>";
    });
    html += '</div><p class="cn-hint">' + esc(t("cn_hint")) + "</p>";
    el.innerHTML = html;
    // keep the password box's keys and caret when the list is drawn again
    var inp = $("cn-pw");
    if (inp) {
      inp.addEventListener("input", function () { if (S2.pw) S2.pw.value = inp.value; });
      if (rows[S2.row] && rows[S2.row].input) { inp.focus(); try { inp.setSelectionRange(inp.value.length, inp.value.length); } catch (e) {} }
    }
    var f = el.querySelector(".cn-row.focus");
    if (f && f.scrollIntoView) f.scrollIntoView({ block: "nearest" });
  }

  function load() {
    return api("GET", S2.kind === "bluetooth" ? "/api/bluetooth" : "/api/wifi").then(function (d) {
      if (!S2.open) return;
      S2.data = d; S2.err = ""; if (!S2.busy) paint();
    }).catch(function (e) { if (!S2.open) return; S2.err = e.message; paint(); });
  }

  function schedule() {
    clearTimeout(S2.timer);
    if (!S2.open) return;
    // Bluetooth changes quickly while searching or pairing; Wi-Fi asks Android less often.
    var fast = S2.data && (S2.data.searching || S2.data.pairing);
    S2.timer = setTimeout(function () { if (S2.busy || S2.pw) { schedule(); return; } load().then(schedule); }, S2.kind === "bluetooth" ? (fast ? 1500 : 4000) : 8000);
  }

  function act(body, note) {
    S2.busy = true; S2.err = ""; S2.note = note || ""; paint();
    return api("POST", S2.kind === "bluetooth" ? "/api/bluetooth" : "/api/wifi", body).then(function (d) {
      S2.busy = false; S2.data = d; S2.note = d.note || ""; if (S2.open) paint();
    }).catch(function (e) {
      S2.busy = false; S2.note = ""; S2.err = e.message; if (S2.open) paint();
    }).then(schedule);
  }

  // A press that cuts the remote off (or forgets something) asks for a second OK within 6 seconds.
  function arm(rowId, a, text) {
    if (S2.armed && S2.armed.row === rowId && S2.armed.id === a.id) { disarm(); return true; }
    S2.armed = { row: rowId, id: a.id }; S2.note = text; S2.err = "";
    clearTimeout(S2.armTimer); S2.armTimer = setTimeout(function () { disarm(); paint(); }, 6000);
    paint();
    return false;
  }
  function disarm() { S2.armed = null; S2.note = ""; clearTimeout(S2.armTimer); }

  function ok() {
    var r = rows[S2.row]; if (!r || !r.acts || S2.busy) return;
    var a = r.acts[S2.btn] || r.acts[0], id = a.id, d = S2.data || {};
    if (id === "system") { S2.api("POST", "/api/launch", { app: S2.kind === "bluetooth" ? "bluetooth" : "network" }).catch(function () {}); close(); return; }
    if (S2.kind === "bluetooth") {
      if (id === "toggle") { act({ action: d.on ? "off" : "on" }); return; }
      if (id === "search" || id === "stop") { S2.searched = id === "search"; act({ action: id }); return; }
      var p = id.split(":"), what = p[0], addr = p.slice(1).join(":");
      if (what === "unpair" && !arm(r.id, a, t("cn_confirm_unpair", { name: a.name || r.label }))) return;
      act({ action: what, address: addr }, what === "pair" ? t("cn_working") : "");
      return;
    }
    // Wi-Fi
    if (id === "toggle") {
      if (d.on && !arm(r.id, a, t("cn_confirm_wifi_off"))) return;
      act({ action: d.on ? "off" : "on" }); return;
    }
    if (id === "scan") { act({ action: "scan" }); return; }
    if (id === "forget") { if (!arm(r.id, a, t("cn_confirm_forget", { name: a.net.ssid }))) return; act({ action: "forget", ssid: a.net.ssid }); return; }
    if (id === "join") {
      var n = a.net, needsPw = n.security && n.security !== "open" && n.security !== "owe" && !n.saved;
      if (needsPw) { S2.pw = { ssid: n.ssid, value: "" }; S2.pwShow = false; S2.row += 1; S2.btn = 0; disarm(); paint(); return; }
      if (d.current && !arm(r.id, a, t("cn_confirm_move", { name: n.ssid }))) return;
      act({ action: "connect", ssid: n.ssid }, t("cn_joining", { name: n.ssid })); return;
    }
    if (id === "pwshow") { S2.pwShow = !S2.pwShow; paint(); return; }
    if (id === "pwcancel") { S2.pw = null; S2.row = Math.max(0, S2.row - 1); paint(); return; }
    if (id === "pwjoin") {
      var pw = S2.pw; if (!pw) return;
      if (d.current && !arm(r.id, a, t("cn_confirm_move", { name: pw.ssid }))) return;
      S2.pw = null; S2.row = Math.max(0, S2.row - 1);
      act({ action: "connect", ssid: pw.ssid, password: pw.value }, t("cn_joining", { name: pw.ssid }));
    }
  }

  function move(dx, dy) {
    if (dy) {
      var i = S2.row + dy;
      while (i >= 0 && i < rows.length && !focusable(rows[i])) i += dy;
      if (i >= 0 && i < rows.length) { S2.row = i; S2.btn = 0; }
    }
    if (dx) {
      var r = rows[S2.row];
      if (r && r.acts) S2.btn = Math.max(0, Math.min(r.acts.length - 1, S2.btn + dx));
    }
    if (S2.armed) disarm();
    paint();
  }

  /**
   * Keys while the password box has the focus: letters, Backspace and the caret keys belong to the
   * box; up and down leave it, OK joins, Back closes it. Returns true when the key was used here.
   */
  function typingKey(e) {
    var inp = $("cn-pw");
    if (!inp || document.activeElement !== inp) return false;
    var k = e.key, c = e.keyCode;
    if (k === "ArrowUp" || k === "ArrowDown" || c === 19 || c === 20) {
      e.preventDefault(); inp.blur(); move(0, k === "ArrowUp" || c === 19 ? -1 : 1); return true;
    }
    if (k === "Enter" || c === 13 || c === 23) {
      e.preventDefault(); if (S2.pw) S2.pw.value = inp.value;
      var r = rows[S2.row]; if (r && r.input) { S2.btn = 0; ok(); } return true;
    }
    if (k === "Escape" || k === "GoBack" || k === "BrowserBack" || c === 27 || c === 10009) {
      e.preventDefault(); S2.pw = null; S2.row = Math.max(0, S2.row - 1); paint(); return true;
    }
    return true;   // everything else is typing: the box gets it, app.js does nothing with it
  }

  function back() {
    if (S2.pw) { S2.pw = null; S2.row = Math.max(0, S2.row - 1); paint(); return false; }
    close(); return true;
  }

  function open(kind, apiFn, onClose) {
    S2.open = true; S2.kind = kind === "network" || kind === "wifi" ? "wifi" : "bluetooth";
    S2.api = apiFn; S2.onClose = onClose || null;
    S2.data = null; S2.err = ""; S2.note = ""; S2.row = 0; S2.btn = 0; S2.pw = null; S2.searched = false; disarm();
    var el = $("connect");
    if (!el) { el = document.createElement("div"); el.id = "connect"; el.className = "connect"; el.setAttribute("role", "dialog"); el.setAttribute("aria-modal", "true"); document.body.appendChild(el); }
    el.setAttribute("aria-label", t(S2.kind === "bluetooth" ? "cn_bt_title" : "cn_wifi_title"));
    el.classList.add("on");
    paint();
    load().then(schedule);
  }

  function close() {
    if (!S2.open) return;
    S2.open = false; clearTimeout(S2.timer); disarm();
    var inp = $("cn-pw"); if (inp) inp.blur();
    // stop a Bluetooth search nobody is watching
    if (S2.kind === "bluetooth" && S2.data && S2.data.searching) S2.api("POST", "/api/bluetooth", { action: "stop" }).catch(function () {});
    var el = $("connect"); if (el) el.classList.remove("on");
    var cb = S2.onClose; S2.onClose = null;
    if (cb) cb();
  }

  window.couchConnect = { open: open, close: close, move: move, ok: ok, back: back, paint: paint, typingKey: typingKey, isOpen: function () { return S2.open; } };
})();
