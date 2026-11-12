// Couch TV browser (BrowserActivity.kt): in every frame of every page, players included (it is
// added at document start to all frames). The app sends play, pause and seek here; the top frame
// passes each one down to its frames, and they to theirs, so a player inside an iframe (most
// streaming sites) follows the remote's play/pause, and pauses when you leave the browser.
// Original code for couch-phone.
(function () {
  "use strict";
  if (window.__couchFrame) return;
  window.__couchFrame = true;

  function media() { return Array.prototype.slice.call(document.querySelectorAll("video, audio")); }
  function biggest() {
    var best = null, area = -1;
    media().forEach(function (m) {
      var r = m.getBoundingClientRect(), a = r.width * r.height + (m.paused ? 0 : 1e9);
      if (a > area) { area = a; best = m; }
    });
    return best;
  }
  function act(what, v) {
    if (what === "pause") { media().forEach(function (m) { try { m.pause(); } catch (e) { } }); return; }
    var m = biggest();
    if (!m) return;
    try {
      if (what === "toggle") { if (m.paused) m.play(); else m.pause(); }
      else if (what === "play") m.play();
      else if (what === "seek") m.currentTime = Math.max(0, m.currentTime + (+v || 0));
    } catch (e) { }
  }
  function down(msg) {
    var frames = document.querySelectorAll("iframe");
    for (var i = 0; i < frames.length; i++) { try { frames[i].contentWindow.postMessage(msg, "*"); } catch (e) { } }
  }
  window.addEventListener("message", function (e) {
    var d = e.data;
    if (d && typeof d === "object" && d.__couch) { act(d.__couch, d.v); down(d); }
  });
  // the app calls this in the top frame
  window.__couchMedia = function (what, v) { act(what, v); down({ __couch: what, v: v }); };
})();
