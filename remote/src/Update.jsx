// Couch Phone: updating the TV phone's app from the newest release on GitHub (Updater.kt), from
// the remote's TV sheet. Original code for couch-phone.
import { useEffect, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { api } from './api.js';
import { t } from './i18n.js';

export function UpdateSection({ open }) {
  const [u, setU] = useState(null);
  const [err, setErr] = useState('');
  useEffect(() => {
    if (!open) return undefined;
    let stop = false; let timer = null;
    const load = () => api.update().then((d) => {
      if (stop) return;
      setU(d); setErr('');
      const busy = d.checking || d.status === 'downloading' || d.status === 'installing';
      timer = setTimeout(load, busy ? 1500 : 15000);
    }).catch((e) => { if (!stop) { setErr(e.message); timer = setTimeout(load, 5000); } });
    load();
    return () => { stop = true; clearTimeout(timer); };
  }, [open]);
  if (!u && !err) return null;
  const go = () => { tick(); api.startUpdate().then(setU, (e) => setErr(e.message)); };
  let line;
  if (!u) line = err;
  else if (u.status === 'downloading') line = t('upd_downloading', { latest: u.latest, p: u.progress || 0 });
  else if (u.status === 'installing') line = t('upd_installing');
  else if (u.available) line = t('upd_available', { latest: u.latest, v: u.current });
  else if (u.checking) line = t('upd_checking', { v: u.current });
  else line = (u.status === 'failed' && u.error) || t('upd_current', { v: u.current });
  return (
    <section className="more-block">
      <h3><Ic.Download size={16} /> {t('upd_title')}</h3>
      <p className="note">{line}</p>
      {u?.available && u.status !== 'downloading' && u.status !== 'installing' && (
        <button className="pill solid arrange-open" onClick={go}><Ic.Download size={18} /> {t('upd_button', { latest: u.latest })}</button>
      )}
      {err && u && <p className="note">{err}</p>}
    </section>
  );
}
