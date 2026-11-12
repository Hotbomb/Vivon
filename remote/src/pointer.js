// The TV pointer (Couch Phone's virtual mouse): moves, scrolls and buttons from the touchpad and
// the air mouse. Moves are added up and sent one request at a time, so a slow answer never builds
// a backlog, and a button press waits for the moves before it so a click lands where it should.
import { api } from './api.js';

const acc = { dx: 0, dy: 0, scroll: 0 };
const buttons = [];
let busy = false;
let onError = null;
let lastError = 0;

export const setPointerErrorHandler = (fn) => { onError = fn; };

function pump() {
  if (busy) return;
  const d = {};
  const dx = Math.trunc(acc.dx); const dy = Math.trunc(acc.dy); const sc = Math.trunc(acc.scroll);
  if (dx) { d.dx = dx; acc.dx -= dx; }
  if (dy) { d.dy = dy; acc.dy -= dy; }
  if (sc) { d.scroll = sc; acc.scroll -= sc; }
  if (buttons.length) Object.assign(d, buttons.shift());
  if (!Object.keys(d).length) return;
  busy = true;
  api.pointer(d)
    .catch((e) => { if (onError && Date.now() - lastError > 4000) { lastError = Date.now(); onError(e); } })
    .finally(() => { busy = false; pump(); });
}

export const move = (dx, dy) => { acc.dx += dx; acc.dy += dy; pump(); };
export const scroll = (px) => { acc.scroll += px; pump(); };
/** which: left | right | middle; action: click | down | up */
export const button = (which = 'left', action = 'click') => { buttons.push({ button: which, action }); pump(); };
/** Shows (true) or hides (false) the pointer on the TV straight away. */
export const show = (on = true) => { buttons.push({ show: on }); pump(); };
/** The touchpad's message shape ({dx, dy, scroll, click}). */
export const padPointer = (d) => {
  if (d.dx || d.dy) move(d.dx || 0, d.dy || 0);
  if (d.scroll) scroll(d.scroll);
  if (d.click) button('left');
  if (d.button) button(d.button, d.action || 'click');
  if (d.show !== undefined) show(d.show);
  return Promise.resolve();
};
