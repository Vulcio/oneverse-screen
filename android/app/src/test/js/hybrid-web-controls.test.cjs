const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const kotlin = fs.readFileSync(path.join(__dirname, '../../main/kotlin/org/oneverse/screen/HybridWebControls.kt'), 'utf8');
const script = kotlin.match(/private val script = """([\s\S]*?)"""\.trimIndent\(\)/)[1];

function page({ iframe = false, supported = true } = {}) {
  const sent = [], timers = new Map(), events = [];
  const window = { dispatchEvent: event => events.push(event.type) };
  window.top = iframe ? {} : window;
  const native = { postMessage: value => sent.push(JSON.parse(value)) };
  if (supported) window.OneverseNative = native;
  const context = vm.createContext({ window, OneverseNative: native, Map, Promise, Event: class { constructor(type) { this.type = type; } },
    setTimeout: callback => { const id = timers.size + 1; timers.set(id, callback); return id; }, clearTimeout: id => timers.delete(id) });
  vm.runInContext(script, context);
  return { window, native, sent, timers, events, context };
}

test('subframes and unsupported WebViews receive no public control API', () => {
  assert.equal(page({ iframe: true }).window.OneverseHybrid, undefined);
  assert.equal(page({ supported: false }).window.OneverseHybrid, undefined);
});

test('requests correlate replies through the public API', async () => {
  const p = page();
  const result = p.window.OneverseHybrid.selectNdiSourceByLabel('Main camera');
  assert.equal(p.sent[0].method, 'selectNdiSourceByLabel');
  assert.deepEqual(p.sent[0].args, ['Main camera']);
  p.native.onmessage({ data: 'not json' });
  p.native.onmessage({ data: JSON.stringify({ id: 999, result: false }) });
  p.native.onmessage({ data: JSON.stringify({ id: p.sent[0].id, result: true }) });
  assert.equal(await result, true);
  assert.equal(p.timers.size, 0);
  vm.runInContext(script, p.context);
  assert.deepEqual(p.events, ['oneversehybridready']);
});

test('native errors reject requests and release pending entries', async () => {
  const p = page();
  const result = p.window.OneverseHybrid.setNdiViewport(0, 0, 100, 100);
  p.native.onmessage({ data: JSON.stringify({ id: p.sent[0].id, error: 'Invalid Hybrid View request' }) });
  await assert.rejects(result, /Invalid Hybrid/);
  assert.equal(p.timers.size, 0);
});

test('unanswered requests time out instead of hanging the page', async () => {
  const p = page();
  const result = p.window.OneverseHybrid.getAvailableNdiSources();
  p.timers.values().next().value();
  await assert.rejects(result, /timed out/);
});
