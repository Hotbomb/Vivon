// Couch Phone: the remote as a game controller. Two sticks, a d-pad, A B X Y, the bumpers and
// triggers, View, Home and Menu, laid out like an Xbox controller for a phone held sideways.
// The phone becomes a real controller on the TV phone (player 1 to 4, see Gamepad.kt), so Steam
// Link and controller games (Terraria and the like) work with it.
// Original code for couch-phone.
import { useEffect, useRef, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { t } from './i18n.js';

// Button bits, the same order as the helper's (android/native/couchmouse.c).
const BTN = { a: 1, b: 2, x: 4, y: 8, lb: 16, rb: 32, view: 64, menu: 128, home: 256, ls: 512, rs: 1024 };

function socketUrl() {
  const token = (window.couchTv && window.couchTv.token && window.couchTv.token()) || '';
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}/api/ws?pad=1${token ? `&token=${encodeURIComponent(token)}` : ''}`;
}

// The controller's state, sent whole (at most once a frame) whenever it changes.
function usePad(open) {
  const st = useRef({ lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, hx: 0, hy: 0, b: 0 });
  const ws = useRef(null);
  const queued = useRef(false);
  const [player, setPlayer] = useState(0);
  const [err, setErr] = useState('');

  const flush = () => {
    queued.current = false;
    const s = ws.current;
    if (s && s.readyState === 1) s.send(JSON.stringify({ pad: st.current }));
  };
  const changed = () => { if (!queued.current) { queued.current = true; requestAnimationFrame(flush); } };

  useEffect(() => {
    if (!open) return undefined;
    setErr(''); setPlayer(0);
    let closed = false;
    const s = new WebSocket(socketUrl());
    ws.current = s;
    s.onopen = () => flush();
    s.onmessage = (e) => {
      try {
        const m = JSON.parse(e.data);
        if (m.player) setPlayer(m.player);
        if (m.ok === false) setErr(m.error || t('pad_failed'));
      } catch (x) { /* */ }
    };
    s.onclose = () => { if (!closed) setErr(t('pad_lost')); };
    // Every second, even with nothing new: a lost message never leaves a button held down.
    const keep = setInterval(flush, 1000);
    return () => {
      closed = true; clearInterval(keep);
      try { if (s.readyState === 1) s.send(JSON.stringify({ pad: false })); s.close(); } catch (x) { /* */ }
      ws.current = null;
      st.current = { lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, hx: 0, hy: 0, b: 0 };
    };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const set = (k, v) => { if (st.current[k] !== v) { st.current = { ...st.current, [k]: v }; changed(); } };
  const press = (bit, down) => {
    const b = down ? st.current.b | bit : st.current.b & ~bit;
    if (b !== st.current.b) { st.current = { ...st.current, b }; changed(); }
  };
  return { set, press, player, err };
}

// A thumbstick: the knob follows the finger inside the ring; a quick tap is a stick click.
function Stick({ side, onMove, onClick }) {
  const ring = useRef(null);
  const [knob, setKnob] = useState({ x: 0, y: 0, on: false });
  const g = useRef(null);
  const at = (e) => {
    const r = ring.current.getBoundingClientRect();
    const R = r.width / 2;
    let x = (e.clientX - (r.left + R)) / (R * 0.8);
    let y = (e.clientY - (r.top + R)) / (R * 0.8);
    const m = Math.hypot(x, y);
    if (m > 1) { x /= m; y /= m; }
    return { x, y };
  };
  const down = (e) => {
    e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId);
    g.current = { id: e.pointerId, t: Date.now(), moved: false };
    const p = at(e); setKnob({ ...p, on: true }); onMove(p.x, p.y);
  };
  const move = (e) => {
    if (!g.current || g.current.id !== e.pointerId) return;
    const p = at(e);
    if (Math.hypot(p.x, p.y) > 0.25) g.current.moved = true;
    setKnob({ ...p, on: true }); onMove(p.x, p.y);
  };
  const up = (e) => {
    if (!g.current || g.current.id !== e.pointerId) return;
    const quick = !g.current.moved && Date.now() - g.current.t < 220;
    g.current = null; setKnob({ x: 0, y: 0, on: false }); onMove(0, 0);
    if (quick) onClick();
  };
  return (
    <div ref={ring} className={`pad-stick ${side}`} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}
      role="application" aria-label={side === 'left' ? t('pad_left_stick') : t('pad_right_stick')}>
      <span className={`pad-knob ${knob.on ? 'on' : ''}`} style={{ transform: `translate(${knob.x * 40}%, ${knob.y * 40}%)` }} />
    </div>
  );
}

// A button that is held while a finger is on it.
function Hold({ className, label, onHold, children }) {
  const [on, setOn] = useState(false);
  const down = (e) => { e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId); setOn(true); tick(); onHold(true); };
  const up = () => { if (on) { setOn(false); onHold(false); } };
  return (
    <button type="button" className={`${className} ${on ? 'on' : ''}`} aria-label={label}
      onPointerDown={down} onPointerUp={up} onPointerCancel={up} onLostPointerCapture={up} onContextMenu={(e) => e.preventDefault()}>{children}</button>
  );
}

// The d-pad: one pad, the finger's direction picks up, down, left or right (diagonals too).
function DPad({ onHat }) {
  const el = useRef(null);
  const [dir, setDir] = useState({ x: 0, y: 0 });
  const g = useRef(null);
  const at = (e) => {
    const r = el.current.getBoundingClientRect();
    const dx = (e.clientX - (r.left + r.width / 2)) / (r.width / 2);
    const dy = (e.clientY - (r.top + r.height / 2)) / (r.height / 2);
    return { x: Math.abs(dx) > 0.33 ? Math.sign(dx) : 0, y: Math.abs(dy) > 0.33 ? Math.sign(dy) : 0 };
  };
  const set = (d) => { if (d.x !== dir.x || d.y !== dir.y) { if (d.x || d.y) tick(); setDir(d); onHat(d.x, d.y); } };
  const down = (e) => { e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId); g.current = e.pointerId; set(at(e)); };
  const move = (e) => { if (g.current === e.pointerId) set(at(e)); };
  const up = (e) => { if (g.current === e.pointerId) { g.current = null; set({ x: 0, y: 0 }); } };
  return (
    <div ref={el} className="pad-dpad" onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} role="application" aria-label={t('pad_dpad')}>
      <span className={`up ${dir.y < 0 ? 'on' : ''}`} /><span className={`down ${dir.y > 0 ? 'on' : ''}`} />
      <span className={`left ${dir.x < 0 ? 'on' : ''}`} /><span className={`right ${dir.x > 0 ? 'on' : ''}`} />
    </div>
  );
}

export function ControllerScreen({ open, onClose }) {
  const { set, press, player, err } = usePad(open);
  const [portrait, setPortrait] = useState(false);

  useEffect(() => {
    if (!open) return undefined;
    // Sideways, full screen: where the phone allows it (Android browsers, the Couch Remote app).
    const root = document.documentElement;
    try { if (window.CouchApp && window.CouchApp.landscape) window.CouchApp.landscape(true); } catch (e) { /* */ }
    try { root.requestFullscreen?.().then(() => screen.orientation?.lock?.('landscape')).catch(() => {}); } catch (e) { /* */ }
    const check = () => setPortrait(window.innerHeight > window.innerWidth);
    check(); window.addEventListener('resize', check);
    return () => {
      window.removeEventListener('resize', check);
      try { if (window.CouchApp && window.CouchApp.landscape) window.CouchApp.landscape(false); } catch (e) { /* */ }
      try { screen.orientation?.unlock?.(); } catch (e) { /* */ }
      try { if (document.fullscreenElement) document.exitFullscreen(); } catch (e) { /* */ }
    };
  }, [open]);

  if (!open) return null;
  const B = (bit) => (on) => press(bit, on);
  return (
    <div className="pad-screen" role="dialog" aria-label={t('controller')}>
      {portrait && <div className="pad-rotate"><Ic.Rotate size={40} /><p>{t('pad_rotate')}</p></div>}
      <div className="pad-top">
        <div className="pad-shoulders">
          <Hold className="pad-trigger" label="LT" onHold={(on) => set('lt', on ? 1 : 0)}>LT</Hold>
          <Hold className="pad-bumper" label="LB" onHold={B(BTN.lb)}>LB</Hold>
        </div>
        <div className="pad-mid">
          <Hold className="pad-small" label={t('pad_view')} onHold={B(BTN.view)}><Ic.PadView size={18} /></Hold>
          <Hold className="pad-small pad-home" label={t('pad_home')} onHold={B(BTN.home)}><Ic.PadHome size={20} /></Hold>
          <Hold className="pad-small" label={t('pad_menu')} onHold={B(BTN.menu)}><Ic.PadMenu size={18} /></Hold>
        </div>
        <div className="pad-shoulders right">
          <Hold className="pad-bumper" label="RB" onHold={B(BTN.rb)}>RB</Hold>
          <Hold className="pad-trigger" label="RT" onHold={(on) => set('rt', on ? 1 : 0)}>RT</Hold>
        </div>
      </div>
      <div className="pad-body">
        <div className="pad-side">
          <Stick side="left" onMove={(x, y) => { set('lx', x); set('ly', y); }} onClick={() => { press(BTN.ls, true); setTimeout(() => press(BTN.ls, false), 80); }} />
          <DPad onHat={(x, y) => { set('hx', x); set('hy', y); }} />
        </div>
        <div className="pad-status">
          <b>{player ? t('pad_player', { n: player }) : err ? '' : t('pad_connecting')}</b>
          {err && <span className="pad-err">{err}</span>}
          <button type="button" className="pill glass pad-close" onClick={() => { tick(); onClose(); }}><Ic.Close size={16} /> {t('pad_done')}</button>
        </div>
        <div className="pad-side right">
          <div className="pad-face">
            <Hold className="pad-btn y" label="Y" onHold={B(BTN.y)}>Y</Hold>
            <Hold className="pad-btn x" label="X" onHold={B(BTN.x)}>X</Hold>
            <Hold className="pad-btn b" label="B" onHold={B(BTN.b)}>B</Hold>
            <Hold className="pad-btn a" label="A" onHold={B(BTN.a)}>A</Hold>
          </div>
          <Stick side="right" onMove={(x, y) => { set('rx', x); set('ry', y); }} onClick={() => { press(BTN.rs, true); setTimeout(() => press(BTN.rs, false), 80); }} />
        </div>
      </div>
    </div>
  );
}
