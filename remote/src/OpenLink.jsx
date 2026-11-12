// Couch Phone: open a web address (or a search) in the browser on the TV, from the TV sheet.
// Original code for couch-phone.
import { useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { t } from './i18n.js';
import { api } from './api.js';

const secure = typeof window !== 'undefined' && window.isSecureContext;

export function OpenLink({ say }) {
  const [v, setV] = useState('');
  const [busy, setBusy] = useState(false);
  const canPaste = typeof navigator !== 'undefined' && navigator.clipboard && typeof navigator.clipboard.readText === 'function' && secure;
  const open = (text) => {
    const u = (text ?? v).trim();
    if (!u) return;
    tick(); setBusy(true);
    api.openUrl(u).then(() => { setV(''); say?.(t('link_opened')); }, (e) => say?.(e.message, 'err')).finally(() => setBusy(false));
  };
  const paste = () => { tick(); navigator.clipboard.readText().then((x) => { if (x) { setV(x); open(x); } }, () => say?.(t('paste_blocked'), 'err')); };
  return (
    <section className="more-block">
      <h3><Ic.Link size={16} /> {t('open_on_tv')}</h3>
      <form className="ask-field glass" onSubmit={(e) => { e.preventDefault(); open(); }}>
        <input value={v} onChange={(e) => setV(e.target.value)} placeholder={t('open_placeholder')} type="text" inputMode="url"
          autoCapitalize="off" autoCorrect="off" enterKeyHint="go" aria-label={t('open_on_tv')} />
        <button type="submit" className="round solid small" aria-label={t('open_on_tv')} disabled={busy || !v.trim()}><Ic.Send size={18} /></button>
      </form>
      {canPaste && <button type="button" className="pill glass arrange-open" disabled={busy} onClick={paste}>{t('paste_open')}</button>}
      <p className="note">{t('open_note')}</p>
    </section>
  );
}

