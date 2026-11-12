// One open WebSocket to the TV phone, shared by the keys and the pointer (Couch Phone). A key goes
// out at once, in order, with no new request each time (a request costs a round trip and, on the
// secure remote, more). The socket opens on first use and again after a drop; while it is down,
// callers fall back to plain HTTP. Couch Phone only: elsewhere `available()` stays false.
let ws = null;
let downAt = 0;
let onError = null;
const waiting = [];   // sent while the socket was opening: out as soon as it is open

export const setChannelErrorHandler = (fn) => { onError = fn; };

function open() {
  if (typeof WebSocket === 'undefined' || typeof window === 'undefined' || !window.couchTv) return null;   // only with Couch Phone's pairing (shim.js)
  if (ws && ws.readyState <= 1) return ws;
  if (Date.now() - downAt < 10000) return null;
  try {
    const token = (window.couchTv.token && window.couchTv.token()) || '';
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const s = new WebSocket(`${proto}//${location.host}/api/ws?pad=1${token ? `&token=${encodeURIComponent(token)}` : ''}`);
    ws = s;
    s.onopen = () => { while (waiting.length && s.readyState === 1) s.send(waiting.shift()); };
    s.onmessage = (e) => { try { const m = JSON.parse(e.data); if (m.ok === false && onError) onError(new Error(m.error || 'The TV did not take that.')); } catch (x) { /* */ } };
    // only this socket's own end counts (an older one closing must not drop a newer one)
    s.onclose = () => { if (ws === s) { ws = null; downAt = Date.now(); waiting.length = 0; } };
  } catch (e) { ws = null; downAt = Date.now(); }
  return ws;
}

/** True when a message can go now (open) or as soon as the socket opens. */
export const ready = () => { const s = open(); return !!s && s.readyState <= 1; };

/** Sends a message (an object); false if there is no socket, so the caller uses HTTP. */
export function send(msg) {
  const s = open();
  if (!s) return false;
  const text = JSON.stringify(msg);
  if (s.readyState === 1) { s.send(text); return true; }
  if (s.readyState === 0) { waiting.push(text); return true; }
  return false;
}

/** Opens the socket early (the first key then goes straight out). */
export const warm = () => { open(); };
