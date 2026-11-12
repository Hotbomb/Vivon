// The TV pointer (Couch Phone's own pointer, Cursor.kt): moves, scrolls and buttons from the
// touchpad and the air mouse.
//
// Smooth: everything goes over one open WebSocket, at most once a frame (about 60 times a second),
// never waiting for an answer. Moves are added up in between, so nothing piles up on a slow Wi-Fi.
// Buttons wait for the moves before them, so a click lands where the pointer is. Without a socket
// (it cannot open), the same messages go one request at a time over plain HTTP.
import { api } from './api.js';
import * as chan from './channel.js';

const acc = { dx: 0, dy: 0, scroll: 0, hscroll: 0 };
const queue = [];   // buttons and show/hide, in order
let onError = null;
let lastError = 0;
let frame = 0;
let busy = false;

// The trackpad's speed, 1 to 5 (3 is normal), kept on this phone.
const SPEEDS = [0.55, 0.75, 1, 1.35, 1.8];
let padSpeed = 3;
try { padSpeed = Math.min(5, Math.max(1, Number(localStorage.getItem('tv.pad.speed')) || 3)); } catch (e) { /* */ }
export const getPadSpeed = () => padSpeed;
export const padGain = () => SPEEDS[padSpeed - 1];
export const setPadSpeed = (n) => { padSpeed = Math.min(5, Math.max(1, n)); try { localStorage.setItem('tv.pad.speed', String(padSpeed)); } catch (e) { /* */ } };

// Enhanced pointer precision (acceleration, as on a PC): a quick flick goes further than a slow move.
let padAccel = true;
try { padAccel = localStorage.getItem('tv.pad.accel') !== '0'; } catch (e) { /* */ }
export const getPadAccel = () => padAccel;
export const setPadAccel = (on) => { padAccel = !!on; try { localStorage.setItem('tv.pad.accel', on ? '1' : '0'); } catch (e) { /* */ } };

export const setPointerErrorHandler = (fn) => { onError = fn; chan.setChannelErrorHandler((e) => fail(e.message)); };
const fail = (msg) => { if (onError && Date.now() - lastError > 4000) { lastError = Date.now(); onError(new Error(msg)); } };

function socket() { return chan.ready(); }

function take() {
  const d = {};
  const dx = Math.trunc(acc.dx); const dy = Math.trunc(acc.dy);
  const sc = Math.trunc(acc.scroll); const hs = Math.trunc(acc.hscroll);
  if (dx) { d.dx = dx; acc.dx -= dx; }
  if (dy) { d.dy = dy; acc.dy -= dy; }
  if (sc) { d.scroll = sc; acc.scroll -= sc; }
  if (hs) { d.hscroll = hs; acc.hscroll -= hs; }
  // the remote's clock: the TV replays moves at the finger's pace (Cursor.kt)
  if (d.dx || d.dy) d.t = Math.round(performance.now() * 10) / 10;
  if (queue.length) Object.assign(d, queue.shift());
  return Object.keys(d).length ? d : null;
}

function send() {
  frame = 0;
  if (chan.ready()) {
    let d;
    while ((d = take())) chan.send({ pointer: d, quiet: true });
    return;
  }
  // no socket: one request at a time
  if (busy) return;
  const d = take();
  if (!d) return;
  busy = true;
  api.pointer(d).catch((e) => fail(e.message)).finally(() => { busy = false; schedule(); });
}

function schedule() {
  if (frame) return;
  frame = typeof requestAnimationFrame === 'function' ? requestAnimationFrame(send) : setTimeout(send, 16);
}

export const move = (dx, dy) => { acc.dx += dx; acc.dy += dy; schedule(); };
export const scroll = (px) => { acc.scroll += px; schedule(); };
export const hscroll = (px) => { acc.hscroll += px; schedule(); };
/** which: left | right | middle; action: click | down | up */
export const button = (which = 'left', action = 'click') => { queue.push({ button: which, action }); schedule(); };
/** Shows (true) or hides (false) the pointer on the TV straight away. */
export const show = (on = true) => { queue.push({ show: on }); schedule(); };
/** The touchpad's message shape ({dx, dy, scroll, hscroll, click}). */
export const padPointer = (d) => {
  if (d.dx || d.dy) move(d.dx || 0, d.dy || 0);
  if (d.scroll) scroll(d.scroll);
  if (d.hscroll) hscroll(d.hscroll);
  if (d.click) button('left');
  if (d.button) button(d.button, d.action || 'click');
  if (d.show !== undefined) show(d.show);
  return Promise.resolve();
};
