// Couch TV browser (BrowserActivity.kt): TV-style navigation on any website.
//
// The remote's arrows move a highlight between the things a page lets you pick (links, buttons,
// posters and cards, menus, players); the page scrolls to keep it in view; OK picks it. The app
// asks this script where the picked thing is and clicks there for real (a mouse click through the
// root helper), so every site reacts as to a person clicking. Only in the top frame; players in
// iframes are clicked into, then their own keys work ("player mode", handled by the app).
// Original code for couch-phone.
(function () {
  "use strict";
  if (window.top !== window || window.__couchNav) return;

  var cur = null;          // the highlighted element
  var ring = null;         // the highlight drawn over it
  var raf = 0;

  var SEL = "a[href], button, input:not([type=hidden]), select, textarea, summary, iframe, video, " +
    "[role=button], [role=link], [role=tab], [role=menuitem], [role=option], [role=checkbox], [role=switch], " +
    "[tabindex]:not([tabindex='-1']), [onclick], [contenteditable=true]";

  function visible(el, r) {
    if (r.width < 6 || r.height < 6) return false;
    if (r.bottom < -2000 || r.top > innerHeight + 4000) return false;
    var s = getComputedStyle(el);
    if (s.visibility === "hidden" || s.display === "none" || +s.opacity < 0.05 || s.pointerEvents === "none") return false;
    if (el.disabled) return false;
    return true;
  }

  // Everything that can be picked: the usual controls, plus anything drawn with a hand cursor
  // (cards and posters that only listen for clicks). An element inside another pickable one is
  // left out, so a poster card is one stop, not three. The scan (the costly part) is kept until the
  // page changes; each press only measures where the kept elements are now.
  var kept = null, dirty = true;
  try {
    new MutationObserver(function (recs) {
      for (var i = 0; i < recs.length; i++) if (recs[i].target !== ring) { dirty = true; return; }
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true,
      attributeFilter: ["class", "style", "hidden", "disabled", "aria-hidden", "href", "role", "tabindex"] });
  } catch (e) { }
  addEventListener("resize", function () { dirty = true; });

  function scan() {
    var set = new Set(document.querySelectorAll(SEL));
    var all = document.body ? document.body.getElementsByTagName("*") : [];
    for (var i = 0, n = Math.min(all.length, 6000); i < n; i++) {
      var el = all[i];
      if (set.has(el)) continue;
      var tag = el.tagName;
      if (tag === "DIV" || tag === "LI" || tag === "IMG" || tag === "SPAN" || tag === "ARTICLE" || tag === "FIGURE" || tag === "SECTION" || tag === "SVG" || tag === "svg") {
        if (getComputedStyle(el).cursor === "pointer" && (!el.parentElement || getComputedStyle(el.parentElement).cursor !== "pointer")) set.add(el);
      }
    }
    var out = [];
    set.forEach(function (el) {
      if (el === ring) return;
      for (var p = el.parentElement; p; p = p.parentElement) if (set.has(p) && p.tagName !== "BODY" && p.tagName !== "HTML") return;
      out.push(el);
    });
    return out;
  }

  function candidates() {
    if (dirty || !kept) { kept = scan(); dirty = false; }
    var out = [];
    for (var i = 0; i < kept.length; i++) {
      var el = kept[i];
      if (!el.isConnected) { dirty = true; continue; }
      var r = el.getBoundingClientRect();
      if (visible(el, r)) out.push({ el: el, r: r });
    }
    return out;
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function draw() {
    raf = 0;
    if (!cur || !document.contains(cur)) { if (ring) ring.style.opacity = "0"; return; }
    if (!ring) {
      ring = document.createElement("div");
      ring.setAttribute("aria-hidden", "true");
      ring.style.cssText = "position:fixed;z-index:2147483647;pointer-events:none;border-radius:10px;" +
        "box-shadow:0 0 0 3px #fff,0 0 0 7px rgba(255,181,46,.95),0 10px 40px rgba(0,0,0,.65);" +
        "transition:left .12s ease,top .12s ease,width .12s ease,height .12s ease,opacity .15s;opacity:0";
      (document.body || document.documentElement).appendChild(ring);
    }
    var r = cur.getBoundingClientRect();
    var rad = getComputedStyle(cur).borderRadius;
    ring.style.left = (r.left - 3) + "px"; ring.style.top = (r.top - 3) + "px";
    ring.style.width = (r.width + 6) + "px"; ring.style.height = (r.height + 6) + "px";
    ring.style.borderRadius = rad && rad !== "0px" ? rad : "10px";
    ring.style.opacity = "1";
    raf = requestAnimationFrame(draw);   // follows the element while the page scrolls or animates
  }

  function focus(el) {
    if (cur && cur.classList) cur.classList.remove("couch-focus");
    cur = el;
    try { el.focus({ preventScroll: true }); } catch (e) { }
    try { el.scrollIntoView({ block: "center", inline: "center", behavior: "smooth" }); } catch (e) { el.scrollIntoView(); }
    if (!raf) raf = requestAnimationFrame(draw);
  }

  // The nearest candidate that way: distance along the arrow, plus twice the sideways offset.
  function best(list, from, dir) {
    var c = center(from), pick = null, score = Infinity;
    list.forEach(function (o) {
      if (o.el === cur) return;
      var r = o.r, p = center(r), d, side;
      if (dir === "down") { if (r.top < from.bottom - from.height * 0.4) return; d = r.top - from.bottom; side = Math.abs(p.x - c.x); }
      else if (dir === "up") { if (r.bottom > from.top + from.height * 0.4) return; d = from.top - r.bottom; side = Math.abs(p.x - c.x); }
      else if (dir === "right") { if (r.left < from.right - from.width * 0.4) return; d = r.left - from.right; side = Math.abs(p.y - c.y); }
      else { if (r.right > from.left + from.width * 0.4) return; d = from.left - r.right; side = Math.abs(p.y - c.y); }
      var s = Math.max(0, d) + side * 2;
      if (s < score) { score = s; pick = o.el; }
    });
    return pick;
  }

  function firstInView(list) {
    var pick = null, score = Infinity;
    list.forEach(function (o) {
      var r = o.r;
      if (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth) return;
      var s = r.top * 2 + r.left;
      if (s < score) { score = s; pick = o.el; }
    });
    return pick;
  }

  function editable(el) {
    return el && (el.tagName === "TEXTAREA" || el.isContentEditable ||
      (el.tagName === "INPUT" && !/^(button|submit|reset|checkbox|radio|range|color|file|image)$/i.test(el.type)));
  }

  window.__couchNav = {
    /** An arrow: "moved", "scrolled", "pass" (the key belongs to a text box) or "none". */
    move: function (dir) {
      var a = document.activeElement;
      if (editable(a) && (dir === "left" || dir === "right")) return "pass";
      var list = candidates();
      if (!cur || !document.contains(cur)) {
        var f = firstInView(list);
        if (f) { focus(f); return "moved"; }
      } else {
        var next = best(list, cur.getBoundingClientRect(), dir);
        // nothing that way in reach: scroll a little and look again next press
        var fr = next && next.getBoundingClientRect();
        if (next && fr.top > -innerHeight * 1.5 && fr.top < innerHeight * 2.5) { focus(next); return "moved"; }
      }
      var dy = dir === "down" ? 0.6 : dir === "up" ? -0.6 : 0, dx = dir === "right" ? 0.6 : dir === "left" ? -0.6 : 0;
      if (dx || dy) { window.scrollBy({ left: dx * innerWidth, top: dy * innerHeight, behavior: "smooth" }); return "scrolled"; }
      return "none";
    },
    /** OK: where to click (screen pixels in the page), and what it is. */
    ok: function () {
      if (!cur || !document.contains(cur)) { var f = firstInView(candidates()); if (f) focus(f); return JSON.stringify({ none: true }); }
      var r = cur.getBoundingClientRect(), tag = cur.tagName;
      var kind = tag === "IFRAME" || tag === "VIDEO" ? "player" : editable(cur) ? "text" : "click";
      if (kind === "text") { try { cur.focus(); } catch (e) { } }
      var dpr = window.devicePixelRatio || 1;
      return JSON.stringify({ kind: kind, x: (r.left + r.width / 2) * dpr, y: (r.top + r.height / 2) * dpr,
        w: r.width * dpr, h: r.height * dpr });
    },
    /** Forget the highlight (a new page, or the pointer took over). */
    reset: function () { cur = null; if (ring) ring.style.opacity = "0"; },
    /** The biggest player on the page (for play and pause when it is in a frame we cannot reach). */
    player: function () {
      var big = null, area = 0, dpr = window.devicePixelRatio || 1;
      document.querySelectorAll("iframe, video").forEach(function (el) {
        var r = el.getBoundingClientRect();
        if (r.width * r.height > area && r.bottom > 0 && r.top < innerHeight) { area = r.width * r.height; big = r; }
      });
      return big ? JSON.stringify({ x: (big.left + big.width / 2) * dpr, y: (big.top + big.height / 2) * dpr }) : "null";
    }
  };
})();
