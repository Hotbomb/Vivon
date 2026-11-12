// Couch Phone: the TV's picture under DeX (resolution and text size) and the phone's own screen
// while it is on the TV. Shown in the TV sheet. Original code for couch-phone.
import { useEffect, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { api } from './api.js';
import { t } from './i18n.js';
import { getPadSpeed, setPadSpeed } from './pointer.js';

// The trackpad's speed, 1 to 5 (kept on this phone; the TV also scales it to its screen size).
export function PadSpeedSection() {
  const [n, setN] = useState(getPadSpeed());
  return (
    <section className="more-block">
      <h3><Ic.Cursor size={16} /> {t('pad_speed')}</h3>
      <div className="chips-row" role="radiogroup" aria-label={t('pad_speed')}>
        {[1, 2, 3, 4, 5].map((v) => (
          <button key={v} role="radio" aria-checked={n === v} className={`chip glass ${n === v ? 'on' : ''}`}
            onClick={() => { tick(); setPadSpeed(v); setN(v); }}>{v}</button>
        ))}
      </div>
      <p className="note">{t('pad_speed_note')}</p>
    </section>
  );
}

export function TvScreenSection({ open }) {
  const [info, setInfo] = useState(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState('');
  useEffect(() => {
    if (!open) return;
    setMsg('');
    api.display().then(setInfo).catch((e) => setMsg(e.message));
  }, [open]);

  const set = (body) => {
    tick(); setBusy(true); setMsg('');
    api.setDisplay(body).then(setInfo).catch((e) => setMsg(e.message)).finally(() => setBusy(false));
  };

  if (!info) return msg ? <section className="more-block"><h3><Ic.Screen size={16} /> {t('tv_screen')}</h3><p className="note">{msg}</p></section> : null;

  const nd = info.native_density;
  const sizes = info.native_size ? [['reset', t('tv_size_native', { size: info.native_size.replace('x', ' × ') })]] : [];
  (info.sizes || []).filter((s) => s !== info.native_size).forEach((s) => sizes.push([s, s.replace('x', ' × ')]));
  const curSize = info.size === info.native_size ? 'reset' : info.size;
  const dens = nd ? [[Math.round(nd * 0.8), t('text_smaller')], ['reset', t('text_default')], [Math.round(nd * 1.2), t('text_larger')], [Math.round(nd * 1.45), t('text_largest')]] : [];
  const curDen = info.density === nd ? 'reset' : info.density;
  const sig = info.signal;
  const bigger = (info.modes || []).find((m) => sig && m.w * m.h > sig.w * sig.h);

  return (
    <section className="more-block">
      <h3><Ic.Screen size={16} /> {t('tv_screen')}</h3>
      {info.tv === false && <p className="note">{t('tv_screen_no_dex')}</p>}
      {info.tv && sizes.length > 0 && (
        <>
          <p className="note">{t('tv_resolution')}</p>
          <div className="chips-row chips-wrap" role="radiogroup" aria-label={t('tv_resolution')}>
            {sizes.map(([v, label]) => (
              <button key={v} role="radio" aria-checked={curSize === v} disabled={busy} className={`chip glass ${curSize === v ? 'on' : ''}`} onClick={() => set({ size: v })}>{label}</button>
            ))}
          </div>
        </>
      )}
      {info.tv && dens.length > 0 && (
        <>
          <p className="note">{t('tv_text_size')}</p>
          <div className="chips-row chips-wrap" role="radiogroup" aria-label={t('tv_text_size')}>
            {dens.map(([v, label]) => (
              <button key={label} role="radio" aria-checked={curDen === v} disabled={busy} className={`chip glass ${curDen === v ? 'on' : ''}`} onClick={() => set({ density: v })}>{label}</button>
            ))}
          </div>
        </>
      )}
      <p className="note">{t('phone_screen')}</p>
      <div className="chips-row" role="radiogroup" aria-label={t('phone_screen')}>
        <button role="radio" aria-checked={info.phone_screen_off} disabled={busy} className={`chip glass ${info.phone_screen_off ? 'on' : ''}`} onClick={() => set({ phone_screen_off: true })}>{t('phone_screen_off')}</button>
        <button role="radio" aria-checked={!info.phone_screen_off} disabled={busy} className={`chip glass ${!info.phone_screen_off ? 'on' : ''}`} onClick={() => set({ phone_screen_off: false })}>{t('phone_screen_on')}</button>
      </div>
      <p className="note">{t('browser_dpad')}</p>
      <div className="chips-row chips-wrap" role="radiogroup" aria-label={t('browser_dpad')}>
        {[['pointer', t('bdpad_pointer')], ['links', t('bdpad_links')], ['keys', t('bdpad_keys')]].map(([v, label]) => (
          <button key={v} role="radio" aria-checked={(info.browser_dpad || 'pointer') === v} disabled={busy}
            className={`chip glass ${(info.browser_dpad || 'pointer') === v ? 'on' : ''}`} onClick={() => set({ browser_dpad: v })}>{label}</button>
        ))}
      </div>
      <p className="note">{t('bdpad_' + (info.browser_dpad || 'pointer') + '_note')}</p>
      {sig && <p className="note">{t('tv_signal', { w: sig.w, h: sig.h, fps: sig.fps })}{bigger ? ' ' + t('tv_signal_more', { w: bigger.w, h: bigger.h }) : ''}</p>}
      {msg && <p className="note">{msg}</p>}
    </section>
  );
}
