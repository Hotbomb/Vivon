// The Watch page's apps, arranged by hand: Edit (or press and hold an app) to drag apps into any
// order, take apps off with the minus, and put them back from the list below. The arrangement is
// kept on the TV phone (/api/remote-apps), so every remote shows the same page.
// Original code for couch-phone (the tiles are couch-tv's).
import { useEffect, useRef, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { api } from './api.js';
import { t } from './i18n.js';

const HOLD_MS = 550;

// The app's own icon (from the TV phone), the brand logo, or its name on a tile.
function Icon({ app, logo, AppIcon }) {
  const [broken, setBroken] = useState(false);
  const src = !broken && (app.icon || logo);
  if (!src) return <span className="app-icon"><span className="app-icon-name">{app.name}</span></span>;
  if (!app.icon && AppIcon) return <AppIcon src={src} />;
  return <span className="app-icon own"><img src={src} alt="" loading="lazy" onError={() => setBroken(true)} /></span>;
}

export function AppGrid({ apps, tvHidden, logoFor, AppIcon, short, onLaunch, onAppsChanged, sites = false }) {
  const [layout, setLayout] = useState(null);   // { order, hidden, saved }
  const [editing, setEditing] = useState(false);
  const [drag, setDrag] = useState(null);       // the package being dragged
  const grid = useRef(null);
  const hold = useRef(null);
  const [siteUrl, setSiteUrl] = useState('');
  const [siteName, setSiteName] = useState('');
  const [siteMsg, setSiteMsg] = useState('');
  const [siteBusy, setSiteBusy] = useState(false);

  useEffect(() => { api.remoteApps().then(setLayout).catch(() => setLayout({ order: [], hidden: [], saved: false })); }, []);

  const all = (apps || []).filter((a) => a.installed !== false && !['settings', 'home'].includes(a.id));
  const by = Object.fromEntries(all.map((a) => [a.package || a.id, a]));
  const keyOf = (a) => a.package || a.id;
  // Before anything was arranged here, the TV home's hidden apps stay hidden, as before.
  const hidden = layout?.saved ? layout.hidden : (tvHidden || []);
  const order = (layout?.order || []).filter((p) => by[p] && !hidden.includes(p));
  const rest = all.map(keyOf).filter((p) => !order.includes(p) && !hidden.includes(p));
  const shown = [...order, ...rest];
  const off = all.map(keyOf).filter((p) => hidden.includes(p));

  const save = (nextOrder, nextHidden) => {
    const next = { order: nextOrder, hidden: nextHidden, saved: true };
    setLayout(next);
    api.saveRemoteApps(nextOrder, nextHidden).catch(() => {});
  };
  const remove = (p) => { tick(); save(shown.filter((x) => x !== p), [...hidden.filter((x) => x !== p), p]); };
  const add = (p) => { tick(); save([...shown, p], hidden.filter((x) => x !== p)); };

  // Dragging: the app moves to the place under the finger as it passes over the others.
  const startDrag = (e, p) => {
    e.preventDefault();
    e.currentTarget.setPointerCapture?.(e.pointerId);
    tick(); setDrag(p);
  };
  const moveDrag = (e) => {
    if (!drag || !grid.current) return;
    const tiles = Array.from(grid.current.children);
    let best = -1; let bestD = Infinity;
    tiles.forEach((el, i) => {
      const r = el.getBoundingClientRect();
      const d = Math.hypot(e.clientX - (r.left + r.width / 2), e.clientY - (r.top + r.height / 2));
      if (d < bestD) { bestD = d; best = i; }
    });
    const from = shown.indexOf(drag);
    if (best >= 0 && best !== from) {
      const next = shown.slice(); next.splice(from, 1); next.splice(best, 0, drag);
      setLayout((l) => ({ ...(l || {}), order: next, hidden, saved: true }));
    }
  };
  const endDrag = () => { if (!drag) return; setDrag(null); save(shown, hidden); };

  // Press and hold an app (not editing): edit mode.
  const holdStart = (p) => { clearTimeout(hold.current); hold.current = setTimeout(() => { hold.current = 'held'; tick(); setEditing(true); }, HOLD_MS); };
  const holdEnd = () => { if (hold.current !== 'held') clearTimeout(hold.current); };

  return (
    <section>
      <div className="h3-row">
        <h3>{t('apps')}</h3>
        {apps && apps.length > 0 && (
          <button type="button" className={`pill glass small edit-apps ${editing ? 'on' : ''}`} onClick={() => { tick(); setEditing(!editing); setDrag(null); }}>
            {editing ? t('done') : t('edit')}
          </button>
        )}
      </div>
      {apps === null && <p className="empty">{t('loading')}</p>}
      {apps === false && <p className="empty">{t('couldnt_load_apps')}</p>}
      {editing && <p className="empty edit-lede">{t('apps_edit_lede')}</p>}
      <div ref={grid} className={`apps ${editing ? 'editing' : ''}`} onPointerMove={editing ? moveDrag : undefined} onPointerUp={endDrag} onPointerCancel={endDrag}>
        {shown.map((p) => {
          const a = by[p];
          return (
            <div key={p} className={`app-slot ${drag === p ? 'lifted' : ''}`}>
              <button className="app-tile" style={{ '--tile': a.colour || '#3a3a3c' }}
                onPointerDown={editing ? (e) => startDrag(e, p) : () => holdStart(p)}
                onPointerUp={editing ? undefined : holdEnd} onPointerLeave={editing ? undefined : holdEnd} onPointerCancel={editing ? undefined : holdEnd}
                onContextMenu={(e) => e.preventDefault()}
                onClick={() => { if (editing || hold.current === 'held') { hold.current = null; return; } tick(); onLaunch(a); }}>
                <Icon app={a} logo={logoFor(a)} AppIcon={AppIcon} />
                <span className="app-label">{short[a.id] || a.name}</span>
              </button>
              {editing && <button type="button" className="app-remove" aria-label={t('remove_named', { name: a.name })} onClick={() => remove(p)}><Ic.Minus size={14} /></button>}
            </div>
          );
        })}
      </div>
      {editing && (
        <>
          {sites && (
            <div className="sites-edit">
              <h3 className="add-head">{t('sites_title')}</h3>
              <p className="empty">{t('sites_lede')}</p>
              <ul className="arrange">
                {all.filter((a) => a.kind === 'site').map((a) => (
                  <li key={keyOf(a)} className="arrange-row glass site-row">
                    <b><span className="cn-name">{a.name}</span><small>{(a.url || '').replace(/^https?:\/\//, '').replace(/\/$/, '')}</small></b>
                    <button type="button" className="pill glass small" disabled={siteBusy} onClick={() => {
                      tick(); setSiteBusy(true);
                      api.removeSite(keyOf(a)).then(() => { setSiteMsg(t('site_removed', { name: a.name })); onAppsChanged?.(); }, (e) => setSiteMsg(e.message)).finally(() => setSiteBusy(false));
                    }}>{t('site_delete')}</button>
                  </li>
                ))}
              </ul>
              <form className="site-form" onSubmit={(e) => {
                e.preventDefault(); if (!siteUrl.trim()) return;
                tick(); setSiteBusy(true); setSiteMsg('');
                api.addSite(siteName.trim(), siteUrl.trim()).then((r) => {
                  setSiteUrl(''); setSiteName(''); setSiteMsg(t('site_added', { name: r.site?.name || '' })); onAppsChanged?.();
                }, (err) => setSiteMsg(err.message)).finally(() => setSiteBusy(false));
              }}>
                <input className="glass" value={siteUrl} onChange={(e) => setSiteUrl(e.target.value)} placeholder={t('site_url')} aria-label={t('site_url')}
                  inputMode="url" autoCapitalize="off" autoCorrect="off" spellCheck={false} enterKeyHint="next" />
                <input className="glass" value={siteName} onChange={(e) => setSiteName(e.target.value)} placeholder={t('site_name')} aria-label={t('site_name')} maxLength={40} enterKeyHint="done" />
                <button type="submit" className="pill solid" disabled={siteBusy || !siteUrl.trim()}><Ic.Plus size={18} /> {t('site_add')}</button>
              </form>
              {siteMsg && <p className="empty" role="status">{siteMsg}</p>}
            </div>
          )}
          <h3 className="add-head">{off.length ? t('apps_add') : t('apps_all_shown')}</h3>
          <div className="apps add">
            {off.map((p) => {
              const a = by[p];
              return (
                <div key={p} className="app-slot">
                  <button className="app-tile" onClick={() => add(p)} aria-label={t('add_named', { name: a.name })}>
                    <Icon app={a} logo={logoFor(a)} AppIcon={AppIcon} />
                    <span className="app-label">{short[a.id] || a.name}</span>
                  </button>
                  <span className="app-add" aria-hidden="true"><Ic.Plus size={14} /></span>
                </div>
              );
            })}
          </div>
        </>
      )}
    </section>
  );
}
