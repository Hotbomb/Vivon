// Couch Phone: Bluetooth and Wi-Fi on the remote, the same controls as the TV's Bluetooth and Wi-Fi
// screens (connect.js on the TV), through the phone's /api/bluetooth and /api/wifi.
// Original code for couch-phone.
import { useEffect, useRef, useState } from 'react';
import * as Ic from './icons.jsx';
import { tick } from './haptics.js';
import { Sheet } from './Sheets.jsx';
import { t } from './i18n.js';
import { api } from './api.js';

function Bars({ n }) {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" className="cn-bars" aria-hidden="true">
      {[0, 1, 2, 3].map((i) => <rect key={i} x={3 + i * 5} y={17 - i * 4} width="3" height={4 + i * 4} rx="1" opacity={i < n ? 1 : 0.25} fill="currentColor" />)}
    </svg>
  );
}

export function ConnectSheet({ open, kind, onClose, say }) {
  const bt = kind === 'bluetooth';
  const [st, setSt] = useState(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState('');       // what is being done: an address/ssid plus the action
  const [armed, setArmed] = useState('');     // a second tap is needed (unpair, forget, join another network)
  const [pw, setPw] = useState({ ssid: '', value: '' });
  const timer = useRef(null);

  const load = () => (bt ? api.bluetooth() : api.wifi()).then((d) => { setSt(d); setErr(''); }).catch((e) => setErr(e.message));

  useEffect(() => {
    clearInterval(timer.current);
    if (!open) return undefined;
    setSt(null); setErr(''); setArmed(''); setPw({ ssid: '', value: '' });
    load();
    timer.current = setInterval(load, bt ? 2500 : 6000);
    return () => clearInterval(timer.current);
  }, [open, kind]); // eslint-disable-line react-hooks/exhaustive-deps

  const act = (body, key, done) => {
    tick(); setBusy(key || body.action); setArmed('');
    (bt ? api.bluetoothAct(body) : api.wifiAct(body))
      .then((d) => { setSt(d); setErr(''); if (done) say?.(done); })
      .catch((e) => setErr(e.message))
      .finally(() => setBusy(''));
  };
  // Things that cut something off ask for a second tap.
  const twice = (key, run) => { if (armed === key) run(); else { tick(); setArmed(key); } };

  const on = !!st?.on;
  return (
    <Sheet open={open} onClose={onClose} title={bt ? t('bluetooth') : t('wifi')} className="arrange-sheet connect-sheet">
      <div className="chips-row" role="radiogroup" aria-label={bt ? t('bluetooth') : t('wifi')}>
        <button role="radio" aria-checked={on} disabled={!st || !!busy} className={`chip glass ${on ? 'on' : ''}`} onClick={() => act({ action: 'on' })}>{t('cn_on')}</button>
        <button role="radio" aria-checked={st && !on} disabled={!st || !!busy} className={`chip glass ${st && !on ? 'on' : ''}`}
          onClick={() => (bt ? act({ action: 'off' }) : twice('wifi-off', () => act({ action: 'off' })))}>{armed === 'wifi-off' ? t('cn_tap_again') : t('cn_off')}</button>
      </div>
      {!bt && armed === 'wifi-off' && <p className="note">{t('cn_wifi_off_warn')}</p>}
      {!st && !err && <p className="note">{t('loading')}</p>}
      {err && <p className="note arrange-msg" role="alert">{err}</p>}
      {st?.note && <p className="note" role="status">{st.note}</p>}

      {bt && st && on && (
        <>
          {st.pairing && (
            <p className="note cn-pairing" role="status">
              {st.pairing.kind === 'type_on_device' ? t('cn_pair_type', { code: st.pairing.code, name: st.pairing.name })
                : t('cn_pair_confirm', { code: st.pairing.code || '', name: st.pairing.name })}
            </p>
          )}
          {st.audio?.speakers?.length > 0 && (
            <>
              <h3 className="cn-head">{t('cn_sound_out')}</h3>
              <div className="chips-row chips-wrap" role="radiogroup" aria-label={t('cn_sound_out')}>
                <button role="radio" aria-checked={!st.audio.out} disabled={!!busy} className={`chip glass ${!st.audio.out ? 'on' : ''}`}
                  onClick={() => act({ action: 'audio' }, 'audio')}>{t('cn_sound_tv')}</button>
                {st.audio.speakers.map((sp) => (
                  <button key={sp.address} role="radio" aria-checked={st.audio.out === sp.address} disabled={!!busy}
                    className={`chip glass ${st.audio.out === sp.address ? 'on' : ''}`} onClick={() => act({ action: 'audio', address: sp.address }, 'audio')}>{sp.name}</button>
                ))}
              </div>
            </>
          )}
          <h3 className="cn-head">{t('cn_paired')}</h3>
          {!(st.paired || []).length && <p className="note">{t('cn_none_paired')}</p>}
          <ul className="arrange">
            {(st.paired || []).map((d) => (
              <li key={d.address} className="arrange-row glass cn-row">
                <span className={`cn-dot ${d.connected ? 'on' : ''}`} aria-hidden="true" />
                <b><span className="cn-name">{d.name}</span><small>{d.connected ? t('cn_connected') : t('cn_not_connected')}</small></b>
                <button className="pill glass small" disabled={!!busy} onClick={() => act({ action: d.connected ? 'disconnect' : 'connect', address: d.address }, d.address)}>
                  {busy === d.address ? '…' : d.connected ? t('cn_disconnect') : t('cn_connect')}</button>
                <button className="pill glass small" disabled={!!busy} onClick={() => twice('unpair' + d.address, () => act({ action: 'unpair', address: d.address }, d.address))}>
                  {armed === 'unpair' + d.address ? t('cn_tap_again') : t('cn_unpair')}</button>
              </li>
            ))}
          </ul>
          <h3 className="cn-head">{t('cn_other')}</h3>
          <button className="pill glass arrange-open" disabled={!!busy || st.searching} onClick={() => act({ action: 'search' })}>
            {st.searching ? t('cn_searching') : (st.found || []).length ? t('cn_search_again') : t('cn_search')}</button>
          <ul className="arrange">
            {(st.found || []).map((d) => (
              <li key={d.address} className="arrange-row glass cn-row">
                <b><span className="cn-name">{d.name}</span>{d.name === d.address ? null : <small>{d.address}</small>}</b>
                <button className="pill glass small" disabled={!!busy} onClick={() => act({ action: 'pair', address: d.address }, d.address)}>{busy === d.address ? '…' : t('cn_pair')}</button>
              </li>
            ))}
          </ul>
        </>
      )}

      {!bt && st && on && (
        <>
          <p className="note">{st.current ? t('cn_wifi_on', { ssid: st.current }) : t('cn_wifi_none')}</p>
          <button className="pill glass arrange-open" disabled={!!busy} onClick={() => act({ action: 'scan' })}>{busy === 'scan' ? t('cn_searching_wifi') : t('cn_scan')}</button>
          <ul className="arrange">
            {(st.networks || []).map((n) => {
              const open_ = n.security === 'open' || n.security === 'owe';
              const typing = pw.ssid === n.ssid;
              return (
                <li key={n.ssid} className={`arrange-row glass cn-row ${typing ? 'cn-typing' : ''}`}>
                  <Bars n={n.bars || 0} />
                  <b><span className="cn-name">{n.ssid}</span><small>{n.current ? t('cn_connected') : n.away ? t('cn_away') : [n.saved ? t('cn_saved') : null, open_ ? t('cn_open') : null, n.band].filter(Boolean).join(' · ')}</small></b>
                  {!open_ && !n.current && <Ic.Lock size={16} />}
                  {n.saved && (
                    <button className="pill glass small" disabled={!!busy} onClick={() => twice('forget' + n.ssid, () => act({ action: 'forget', ssid: n.ssid }, n.ssid))}>
                      {armed === 'forget' + n.ssid ? t('cn_tap_again') : t('cn_forget')}</button>
                  )}
                  {!n.current && !n.away && (
                    <button className="pill glass small" disabled={!!busy} onClick={() => {
                      // saved ones too: switching to a secured network takes its password again
                      if (!open_) { tick(); setPw({ ssid: n.ssid, value: '' }); return; }
                      twice('join' + n.ssid, () => act({ action: 'connect', ssid: n.ssid }, n.ssid, t('cn_joining', { ssid: n.ssid })));
                    }}>{busy === n.ssid ? '…' : armed === 'join' + n.ssid ? t('cn_tap_again') : t('cn_join')}</button>
                  )}
                  {typing && (
                    <form className="cn-pw" onSubmit={(e) => { e.preventDefault(); act({ action: 'connect', ssid: n.ssid, password: pw.value }, n.ssid, t('cn_joining', { ssid: n.ssid })); setPw({ ssid: '', value: '' }); }}>
                      <input type="password" autoFocus value={pw.value} onChange={(e) => setPw({ ssid: n.ssid, value: e.target.value })} placeholder={t('cn_password')}
                        aria-label={t('cn_password')} autoComplete="off" enterKeyHint="go" minLength={8} />
                      <button type="submit" className="round solid small" aria-label={t('cn_join')} disabled={pw.value.length < 8}><Ic.Send size={18} /></button>
                    </form>
                  )}
                </li>
              );
            })}
          </ul>
          <p className="note">{t('cn_wifi_warn')}</p>
        </>
      )}
    </Sheet>
  );
}
