// Couch Phone: the TV's picture under DeX (resolution and text size) and the phone's own screen
// while it is on the TV. Shown in the TV sheet. Original code for couch-phone.
import { useEffect, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { api } from './api.js';
import { t } from './i18n.js';

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
      {info.chrome_nav !== null && info.chrome_nav !== undefined && (
        <>
          <p className="note">{t('chrome_nav')}</p>
          <div className="chips-row" role="radiogroup" aria-label={t('chrome_nav')}>
            <button role="radio" aria-checked={!info.chrome_nav} disabled={busy} className={`chip glass ${!info.chrome_nav ? 'on' : ''}`} onClick={() => set({ chrome_nav: false })}>{t('chrome_nav_scroll')}</button>
            <button role="radio" aria-checked={info.chrome_nav} disabled={busy} className={`chip glass ${info.chrome_nav ? 'on' : ''}`} onClick={() => set({ chrome_nav: true })}>{t('chrome_nav_links')}</button>
          </div>
          <p className="note">{t('chrome_nav_note')}</p>
        </>
      )}
      {sig && <p className="note">{t('tv_signal', { w: sig.w, h: sig.h, fps: sig.fps })}{bigger ? ' ' + t('tv_signal_more', { w: bigger.w, h: bigger.h }) : ''}</p>}
      {msg && <p className="note">{msg}</p>}
    </section>
  );
}
