// The Couch Remote Android app (android/remote-app) loads this same remote in a WebView and adds
// window.CouchApp. Here: speaking through Android's own speech recognizer (WebViews have no
// SpeechRecognition), in the shape the Speak sheet already uses.
// Original code for couch-phone.
export const inApp = typeof window !== 'undefined' && !!window.CouchApp;

let current = null;
if (inApp) {
  // The app calls this with "result" (text, final), "error" (a SpeechRecognition error name) and "end".
  window.__couchSpeech = (type, text, isFinal) => { if (current) current.event(type, text, isFinal); };
}

export class NativeSpeech {
  constructor() { this.lang = ''; this.onresult = null; this.onerror = null; this.onend = null; }
  start() { current = this; window.CouchApp.startSpeech(this.lang || ''); }
  stop() { window.CouchApp.stopSpeech(); }
  abort() { if (current === this) current = null; window.CouchApp.cancelSpeech(); }
  event(type, text, isFinal) {
    if (type === 'result') {
      const r = [{ transcript: text || '' }]; r.isFinal = !!isFinal;
      if (this.onresult) this.onresult({ results: [r] });
    } else if (type === 'error') {
      if (this.onerror) this.onerror({ error: text });
    } else if (type === 'end') {
      if (current === this) current = null;
      if (this.onend) this.onend();
    }
  }
}
