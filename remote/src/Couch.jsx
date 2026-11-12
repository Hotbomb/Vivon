// Couch Phone's own remote controls: the tools row (keyboard, speak, air mouse), the live
// keyboard, speaking to type, the gyro air mouse, and the one-time move to the secure remote.
// Original code for couch-phone.
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { Sheet } from './Sheets.jsx';
import { t, locale } from './i18n.js';
import { api } from './api.js';
import * as pointer from './pointer.js';
import { inApp, NativeSpeech } from './appBridge.js';

export const secure = typeof window !== 'undefined' && window.isSecureContext;
const Speech = typeof window !== 'undefined' && (window.SpeechRecognition || window.webkitSpeechRecognition || (inApp && window.CouchApp.startSpeech ? NativeSpeech : null));
const isIos = typeof navigator !== 'undefined' && (/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1));

// ---------------------------------------------------------------- tools row

// One row under the pad: Keyboard, Speak and Air mouse. While a browser is in front on the TV
// they shrink to icons and the browser's buttons join the same row (it scrolls sideways).
export function Tools({ onKeyboard, onSpeak, onAirMouse, onPad, disabled, browser, say }) {
  if (browser) {
    return (
      <div className={`tools tools-browser ${disabled ? 'is-off' : ''}`} role="group" aria-label={t('tools')}>
        <button className="round glass bb-btn" aria-label={t('keyboard')} onClick={onKeyboard}><Ic.Keyboard size={20} /></button>
        <button className="round glass bb-btn" aria-label={t('speak')} onClick={onSpeak}><Ic.Mic size={20} /></button>
        <button className="round glass bb-btn" aria-label={t('air_mouse')} onClick={onAirMouse}><Ic.AirMouse size={20} /></button>
        <button className="round glass bb-btn" aria-label={t('controller')} onClick={onPad}><Ic.Gamepad size={20} /></button>
        <span className="bb-sep" aria-hidden="true" />
        <BrowserButtons onAddress={onKeyboard} say={say} />
      </div>
    );
  }
  return (
    <div className={`tools four ${disabled ? 'is-off' : ''}`} role="group" aria-label={t('tools')}>
      <button className="tool glass" onClick={onKeyboard}><Ic.Keyboard size={20} /><span>{t('keyboard')}</span></button>
      <button className="tool glass" onClick={onSpeak}><Ic.Mic size={20} /><span>{t('speak')}</span></button>
      <button className="tool glass" onClick={onAirMouse}><Ic.AirMouse size={20} /><span>{t('air_mouse')}</span></button>
      <button className="tool glass" onClick={onPad}><Ic.Gamepad size={20} /><span>{t('controller')}</span></button>
    </div>
  );
}

// ---------------------------------------------------------------- the trackpad's buttons

// Under the trackpad while it drives the TV pointer (in place of Back, Home, play and volume): the
// air mouse's row. Right click, a scroll strip (drag up and down, or sideways), Back and Home.
export function PointerButtons({ onKey }) {
  const at = useRef(null);
  const down = (e) => { e.currentTarget.setPointerCapture?.(e.pointerId); at.current = { x: e.clientX, y: e.clientY }; tick(); };
  const move = (e) => {
    const a = at.current; if (!a) return;
    const dx = e.clientX - a.x; const dy = e.clientY - a.y;
    if (Math.abs(dy) >= Math.abs(dx)) pointer.scroll(dy * 1.4); else pointer.hscroll(dx * 1.4);
    at.current = { x: e.clientX, y: e.clientY };
  };
  const up = () => { at.current = null; };
  return (
    <div className="air-row pad-row" role="group" aria-label={t('pointer_buttons')}>
      <button className="air-btn glass" onClick={() => { tick(); pointer.button('right'); }}><Ic.Mouse size={20} /><span>{t('air_right')}</span></button>
      <div className="air-scroll glass" role="slider" aria-label={t('air_scroll')} aria-valuenow={0}
        onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}><Ic.Updown size={20} /><span>{t('air_scroll')}</span></div>
      <button className="air-btn glass" onClick={() => { tick(); onKey('back'); }}><Ic.Back size={20} /><span>{t('back')}</span></button>
      <button className="air-btn glass" onClick={() => { tick(); onKey('home'); }}><Ic.Home size={20} /><span>{t('home')}</span></button>
    </div>
  );
}

// ---------------------------------------------------------------- the browser bar

// Shown while a browser is in front on the TV: Chrome's own keyboard shortcuts as buttons.
export const BROWSERS = ['com.android.chrome', 'com.brave.browser'];   // Chrome and Brave only

function BrowserButtons({ onAddress, say }) {
  const send = (name) => { tick(); api.shortcut(name).catch((e) => say?.(e.message, 'err')); };
  const B = ({ name, label, children }) => (
    <button type="button" className="round glass bb-btn" aria-label={label} onClick={() => send(name)}>{children}</button>
  );
  return (
    <>
      <button type="button" className="pill glass bb-address" onClick={() => { tick(); api.shortcut('address').then(onAddress, (e) => say?.(e.message, 'err')); }}>
        <Ic.Search size={18} /> <span>{t('bb_address')}</span>
      </button>
      <B name="back" label={t('bb_back')}><Ic.Back size={20} /></B>
      <B name="forward" label={t('bb_forward')}><Ic.ChevronR size={20} /></B>
      <B name="reload" label={t('bb_reload')}><Ic.Reload size={20} /></B>
      <B name="new_tab" label={t('bb_new_tab')}><Ic.TabNew size={20} /></B>
      <B name="next_tab" label={t('bb_next_tab')}><Ic.TabNext size={20} /></B>
      <B name="close_tab" label={t('bb_close_tab')}><Ic.TabClose size={20} /></B>
      <B name="zoom_out" label={t('bb_zoom_out')}><Ic.ZoomOut size={20} /></B>
      <B name="zoom_in" label={t('bb_zoom_in')}><Ic.ZoomIn size={20} /></B>
    </>
  );
}

/** A link shared to the remote (Android's Share menu, once it is on the home screen): open it on the TV. */
export function useSharedLink(say) {
  useEffect(() => {
    if (typeof window === 'undefined' || window.location.pathname !== '/share') return;
    const q = new URLSearchParams(window.location.search);
    const text = [q.get('url'), q.get('text'), q.get('title')].find((x) => x && /https?:\/\//.test(x)) || q.get('url') || q.get('text') || '';
    window.history.replaceState({}, '', '/');
    if (text) api.openUrl(text).then(() => say(t('link_opened')), (e) => say(e.message, 'err'));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
}

// One request at a time, in order, so typed letters and backspaces reach the TV as typed.
function useQueue(say) {
  const q = useRef(Promise.resolve());
  return (fn) => { q.current = q.current.then(fn).catch((e) => say(e.message, 'err')); };
}

// ---------------------------------------------------------------- keyboard

// The field types on the TV as you type: new letters are sent, deleted ones become backspaces.
// It works with any phone keyboard, including autocorrect, emoji and the keyboard's own mic.
export const KeyboardSheet = forwardRef(function KeyboardSheet({ open, onClose, say, onSpeak }, inputRef) {
  const [val, setVal] = useState('');
  const prev = useRef([]);
  const send = useQueue(say);
  useEffect(() => { if (!open) { setVal(''); prev.current = []; } }, [open]);

  const key = (k) => send(() => api.key(k, false, true));
  const onChange = (e) => {
    const v = e.target.value;
    const now = Array.from(v); const was = prev.current;   // by character, so an emoji is one backspace
    let i = 0;
    while (i < was.length && i < now.length && was[i] === now[i]) i += 1;
    const del = was.length - i; const add = now.slice(i).join('');
    prev.current = now; setVal(v);
    for (let k = 0; k < del; k += 1) key('backspace');
    if (add) send(() => api.text(add));
  };
  const onKeyDown = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); tick(); key('enter'); setVal(''); prev.current = []; }
    else if (e.key === 'Backspace' && !val) key('backspace');   // an empty field still deletes on the TV
  };
  const btn = (label, k, icon) => (
    <button type="button" className="kb-key glass" aria-label={label} onPointerDown={(e) => e.preventDefault()}
      onClick={() => { tick(); key(k); }}>{icon}</button>
  );

  return (
    <Sheet open={open} onClose={onClose} title={t('keyboard')} className="kb-sheet">
      <p className="note">{t('kb_lede')}</p>
      <div className="ask-field glass">
        <input ref={inputRef} value={val} onChange={onChange} onKeyDown={onKeyDown} placeholder={t('kb_placeholder')}
          autoCapitalize="none" autoCorrect="off" autoComplete="off" spellCheck={false} enterKeyHint="go" aria-label={t('kb_placeholder')} />
        {onSpeak && <button type="button" className="round glass small" aria-label={t('speak')} onPointerDown={(e) => e.preventDefault()} onClick={onSpeak}><Ic.Mic size={18} /></button>}
      </div>
      <div className="kb-keys">
        {btn(t('kb_backspace'), 'backspace', <Ic.Backspace size={20} />)}
        {btn(t('kb_left'), 'left', <Ic.Left size={20} />)}
        {btn(t('kb_right'), 'right', <Ic.Right size={20} />)}
        {btn(t('kb_tab'), 'tab', <Ic.Tab size={20} />)}
        {btn(t('kb_enter'), 'enter', <Ic.Enter size={20} />)}
      </div>
      <button type="button" className="pill glass kb-clear" onPointerDown={(e) => e.preventDefault()}
        onClick={() => { tick(); setVal(''); prev.current = []; }}>{t('kb_new_line')}</button>
    </Sheet>
  );
});

// ---------------------------------------------------------------- speak

// Speak, and the words are typed into whatever box is open on the TV (a search, a message).
export const SpeakSheet = forwardRef(function SpeakSheet({ open, onClose, say }, ref) {
  const [st, setSt] = useState('idle');   // idle | listening | sent | error
  const [heard, setHeard] = useState('');
  const [err, setErr] = useState('');
  const rec = useRef(null);
  const stop = () => { const r = rec.current; rec.current = null; try { r?.abort(); } catch (e) { /* already stopped */ } };
  useEffect(() => { if (!open) { stop(); setSt('idle'); setHeard(''); } }, [open]);
  useEffect(() => stop, []);

  // Called straight from the tap (iPhones only listen when asked inside a tap).
  const start = () => {
    stop();
    if (!Speech) { setSt('error'); setErr(t('speak_unsupported')); return; }
    const r = new Speech();
    r.lang = locale(); r.interimResults = true; r.continuous = false; r.maxAlternatives = 1;
    let final = '';
    r.onresult = (e) => {
      let text = '';
      for (let i = 0; i < e.results.length; i += 1) { text += e.results[i][0].transcript; if (e.results[i].isFinal) final = text; }
      setHeard(text);
    };
    let failed = false;
    r.onerror = (e) => { failed = true; setSt('error'); setErr(e.error === 'not-allowed' || e.error === 'service-not-allowed' ? t('speak_blocked') : e.error === 'no-speech' ? t('speak_nothing') : t('speak_failed')); };
    r.onend = () => {
      if (rec.current !== r) return;   // stopped on purpose (closed, or started again)
      rec.current = null;
      if (failed) return;
      const said = (final || '').trim();
      if (!said) { setSt('error'); setErr(t('speak_nothing')); return; }
      setSt('sent');
      api.text(said).then(() => tick()).catch((e) => say(e.message, 'err'));
    };
    rec.current = r;
    setHeard(''); setErr(''); setSt('listening');
    try { r.start(); } catch (e) { setSt('error'); setErr(t('speak_failed')); }
  };
  useImperativeHandle(ref, () => ({ start }));

  return (
    <Sheet open={open} onClose={onClose} title={t('speak')} className="speak-sheet">
      <button type="button" className={`speak-mic ${st === 'listening' ? 'listening' : ''}`} aria-label={st === 'listening' ? t('speak_stop') : t('speak')}
        onClick={() => { tick(); if (st === 'listening') { try { rec.current?.stop(); } catch (e) { /* */ } } else start(); }}><Ic.Mic size={44} /></button>
      <p className="speak-heard" aria-live="polite">{heard || (st === 'listening' ? t('speak_listening') : st === 'error' ? err : t('speak_lede'))}</p>
      {st === 'sent' && <p className="note center">{t('speak_sent')}</p>}
      <div className="answer-actions">
        <button className="pill glass" onClick={() => { tick(); api.key('enter').catch((e) => say(e.message, 'err')); }}><Ic.Enter size={18} /> {t('kb_enter')}</button>
        <button className="pill glass" onClick={() => { tick(); start(); }}>{t('speak_again')}</button>
      </div>
    </Sheet>
  );
});

// ---------------------------------------------------------------- air mouse

const SPEEDS = [12, 18, 26, 36, 50];   // pointer pixels per degree turned
const DEAD = 3;                        // degrees a second of hand shake that never move the pointer
const curve = (r) => { const a = Math.abs(r); return a < DEAD ? 0 : Math.sign(r) * (a - DEAD) * (1 + a / 180); };

// Point the phone at the TV and turn it: the pointer follows. The big pad is the mouse button
// (press, hold to drag), with right click, a scroll strip, back and home below.
export const AirMouseSheet = forwardRef(function AirMouseSheet({ open, onClose, say, onKey }, ref) {
  const [running, setRunning] = useState(false);
  const [paused, setPaused] = useState(false);
  const [speed, setSpeed] = useState(() => { try { return Number(localStorage.getItem('tv.air.speed')) || 3; } catch (e) { return 3; } });
  const state = useRef({ last: 0, paused: false, speed: 3 });
  state.current.paused = paused; state.current.speed = speed;
  const listening = useRef(false);

  // One listener for the sheet's whole life (a ref), so closing the sheet always removes the same
  // function start() added: a new one each render kept the old one moving the pointer.
  const motion = useRef(null);
  if (!motion.current) motion.current = (e) => onMotionNow.current(e);
  const onMotionNow = useRef(null);
  onMotionNow.current = (e) => {
    const r = e.rotationRate; const s = state.current;
    if (r && (r.alpha != null || r.beta != null)) s.seen = true;
    const now = performance.now(); const dt = Math.min(0.1, (now - (s.last || now)) / 1000); s.last = now;
    if (!r || s.paused || !dt) return;
    const g = SPEEDS[s.speed - 1] || SPEEDS[2];
    // Turning left and right is the z axis held flat and the y axis held upright, so both count.
    const yaw = (r.alpha || 0) + (r.gamma || 0); const pitch = r.beta || 0;
    pointer.move(-curve(yaw) * dt * g, -curve(pitch) * dt * g);
  };
  const detach = () => { window.removeEventListener('devicemotion', motion.current); listening.current = false; setRunning(false); };
  useEffect(() => { if (!open) detach(); }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => detach, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Called straight from the tap: iPhones ask for motion access only inside a tap.
  const start = async () => {
    if (listening.current) return;
    if (!('DeviceMotionEvent' in window)) { say(t('air_unsupported'), 'err'); return; }
    // Only iPhones and iPads ask first; Android browsers just send the motion (or a site setting blocks it).
    if (isIos && typeof window.DeviceMotionEvent.requestPermission === 'function') {
      try {
        const p = await window.DeviceMotionEvent.requestPermission();
        if (p !== 'granted') { say(t('air_denied'), 'err'); return; }
      } catch (e) { say(t('air_denied'), 'err'); return; }
    }
    state.current.last = 0; state.current.seen = false;
    window.addEventListener('devicemotion', motion.current);
    listening.current = true; setRunning(true); setPaused(false);
    pointer.show(true);
    setTimeout(() => { if (listening.current && !state.current.seen) say(t('air_no_motion'), 'err'); }, 3000);
  };
  useImperativeHandle(ref, () => ({ start }));

  const setSp = (n) => { setSpeed(n); try { localStorage.setItem('tv.air.speed', String(n)); } catch (e) { /* */ } };
  // the main button: down and up, so holding it drags
  const held = useRef(false);
  const down = (e) => { e.currentTarget.setPointerCapture?.(e.pointerId); held.current = true; tick(); pointer.button('left', 'down'); };
  const up = () => { if (!held.current) return; held.current = false; pointer.button('left', 'up'); };
  // the scroll strip
  const sy = useRef(null);
  const sDown = (e) => { e.currentTarget.setPointerCapture?.(e.pointerId); sy.current = e.clientY; };
  const sMove = (e) => { if (sy.current == null) return; pointer.scroll((e.clientY - sy.current) * 1.4); sy.current = e.clientY; };
  const sUp = () => { sy.current = null; };

  return (
    <Sheet open={open} onClose={onClose} title={t('air_mouse')} className="air-sheet">
      <p className="note">{running ? t('air_lede') : t('air_start_lede')}</p>
      {!running && <button className="pill big solid" onClick={() => { tick(); start(); }}><Ic.AirMouse size={22} /> {t('air_start')}</button>}
      <button type="button" className="air-click glass" aria-label={t('air_click')}
        onPointerDown={down} onPointerUp={up} onPointerCancel={up} onContextMenu={(e) => e.preventDefault()}>
        <Ic.Mouse size={34} /><span>{t('air_click')}</span><small>{t('air_click_hint')}</small>
      </button>
      <div className="air-row">
        <button className="air-btn glass" onClick={() => { tick(); pointer.button('right'); }}><Ic.Mouse size={20} /><span>{t('air_right')}</span></button>
        <div className="air-scroll glass" role="slider" aria-label={t('air_scroll')} aria-valuenow={0}
          onPointerDown={sDown} onPointerMove={sMove} onPointerUp={sUp} onPointerCancel={sUp}><Ic.Updown size={20} /><span>{t('air_scroll')}</span></div>
        <button className="air-btn glass" onClick={() => { tick(); onKey('back'); }}><Ic.Back size={20} /><span>{t('back')}</span></button>
        <button className="air-btn glass" onClick={() => { tick(); onKey('home'); }}><Ic.Home size={20} /><span>{t('home')}</span></button>
      </div>
      {running && (
        <div className="air-row">
          <button className={`pill glass ${paused ? 'on' : ''}`} aria-pressed={paused} onClick={() => { tick(); setPaused((p) => !p); }}>{paused ? t('air_resume') : t('air_pause')}</button>
        </div>
      )}
      <div className="more-block">
        <h3>{t('air_speed')}</h3>
        <div className="chips-row" role="radiogroup" aria-label={t('air_speed')}>
          {[1, 2, 3, 4, 5].map((n) => <button key={n} role="radio" aria-checked={speed === n} className={`chip glass ${speed === n ? 'on' : ''}`} onClick={() => { tick(); setSp(n); }}>{n}</button>)}
        </div>
      </div>
    </Sheet>
  );
});

// ---------------------------------------------------------------- the secure remote

// Phones only give a web page the motion sensors and the microphone on a secure (https) address.
// The TV serves one too; this sheet moves this phone there, once, without pairing again.
export function SecureSheet({ open, onClose, why }) {
  const [info, setInfo] = useState(null);
  useEffect(() => { if (open && !info) api.secure().then(setInfo).catch(() => setInfo(false)); }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  const token = typeof window !== 'undefined' && window.couchTv?.token?.();
  const go = info?.url ? `${info.url}${token ? `#ctoken=${token}` : ''}` : null;
  return (
    <Sheet open={open} onClose={onClose} title={t('secure_title')} className="secure-sheet">
      <p className="note">{why === 'air' ? t('secure_why_air') : t('secure_why_speak')} {t('secure_once')}</p>
      <ol className="steps">
        <li>
          <b>{t('secure_step1')}</b> <span className="muted">{t('secure_optional')}</span>
          {isIos ? (
            <>
              <a className="pill glass link" href={info?.ios_profile || '/couch-phone.mobileconfig'}><Ic.Lock size={18} /> {t('secure_ios_get')}</a>
              <p className="tip-text">{t('secure_ios_steps')}</p>
            </>
          ) : (
            <>
              <a className="pill glass link" href={info?.android_cert || '/couch-phone.crt'} download="couch-phone.crt"><Ic.Lock size={18} /> {t('secure_android_get')}</a>
              <p className="tip-text">{t('secure_android_steps')}</p>
            </>
          )}
        </li>
        <li>
          <b>{t('secure_step2')}</b>
          {go ? <a className="pill big solid link" href={go}>{t('secure_open')}</a>
            : <p className="tip-text">{info === false ? t('secure_unknown') : t('secure_loading')}</p>}
          <p className="tip-text">{t('secure_warning')}</p>
        </li>
        <li><b>{t('secure_step3')}</b></li>
      </ol>
    </Sheet>
  );
}
