import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makePadSender } from '../src/padSender.js';

// The DeX touchpad answers at once (Promise.resolve), so a sender that keeps re-sending
// leftover fractions loops forever on microtasks and freezes the page.
const settle = () => new Promise((r) => setTimeout(r, 50));

test('a small finger move ends after a few sends (no endless loop)', async () => {
  let calls = 0;
  const add = makePadSender(() => { calls += 1; if (calls > 1000) throw new Error('endless'); return Promise.resolve(); });
  add('dx', 1.8 * 1); add('dy', 1.8 * 2);   // GAIN 1.8: fractions left over
  await settle();
  assert.ok(calls <= 2, `sent ${calls} times`);
});

test('nothing is sent when the move rounds to zero', async () => {
  const sent = [];
  const add = makePadSender((d) => { sent.push(d); if (sent.length > 1000) throw new Error('endless'); return Promise.resolve(); });
  add('dx', 0.3);
  await settle();
  assert.equal(sent.length, 0);
});

test('fractions add up and are sent once they make a whole pixel', async () => {
  const sent = [];
  const add = makePadSender((d) => { sent.push(d); return Promise.resolve(); });
  add('dx', 0.6); await settle(); add('dx', 0.6); await settle();
  assert.equal(sent.reduce((s, d) => s + d.dx, 0), 1);
});

test('a tap sends one click', async () => {
  const sent = [];
  const add = makePadSender((d) => { sent.push(d); return Promise.resolve(); });
  add('click'); await settle();
  assert.equal(sent.filter((d) => d.click).length, 1);
});

test('two fingers sideways: horizontal scroll is sent too', async () => {
  const sent = [];
  const add = makePadSender((d) => { sent.push(d); return Promise.resolve(); });
  add('hscroll', 30); await settle();
  assert.equal(sent.reduce((n, d) => n + (d.hscroll || 0), 0), 30);
});
