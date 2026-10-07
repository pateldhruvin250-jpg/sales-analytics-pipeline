// Runs dashboard/index.html's inline script against a stub DOM + stub Chart.js (no browser or network needed).
const fs = require('fs'), vm = require('vm'), path = require('path'), assert = require('assert');
const dir = path.join(__dirname, '..', 'dashboard');
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
assert.strictEqual(scripts.length, 1, 'expected exactly one inline script');
const ids = [...html.matchAll(/id="([^"]+)"/g)].map(m => m[1]);

function makeEnv(withData, withChart) {
  const els = {}, handlers = {}, charts = [];
  const mk = id => els[id] = els[id] || {
    id, innerHTML: '', textContent: '', hidden: true,
    addEventListener: (t, fn) => { handlers[id] = fn; },
    querySelectorAll: () => [{ setAttribute() {} }], setAttribute() {}, insertAdjacentHTML(_, h) { this.innerHTML = h + this.innerHTML; },
  };
  ids.forEach(mk);
  class Chart { constructor(c, cfg) { this.cfg = cfg; this.data = cfg.data; charts.push(this); } update() {} }
  Chart.defaults = { font: {}, animation: true };
  const window = {};
  if (withData) vm.runInNewContext(fs.readFileSync(path.join(dir, 'data.js'), 'utf8'), { window });
  const ctx = { window, document: { getElementById: mk }, Intl, Object, Array, String, Math, Chart: withChart ? Chart : undefined };
  return { ctx, els, handlers, charts };
}

// 1. Normal run
let env = makeEnv(true, true);
vm.runInNewContext(scripts[0], env.ctx);
assert.strictEqual(env.charts.length, 5, 'five charts drawn');
assert.strictEqual((env.els.kpis.innerHTML.match(/class="kpi"/g) || []).length, 6, 'six KPI tiles');
assert.ok(/West/.test(env.els.finding.textContent) && !env.els.finding.hidden, 'finding names the weakest region');
assert.ok(/Customer/.test(env.els.customerTable.innerHTML) && env.els.customerTable.innerHTML.split('<tr>').length === 12, 'customer table has header + 10 rows');
assert.ok(/rejected during cleaning/.test(env.els.quality.textContent), 'data quality line');
const trend = env.charts[0];
assert.strictEqual(trend.data.datasets.length, 2, 'both metrics by default');
const monthlyCount = trend.data.labels.length;
env.handlers.periodSeg({ target: { closest: () => ({ dataset: { v: 'weekly' } }) } });
assert.ok(trend.data.labels.length > monthlyCount, 'weekly view has more points than monthly');
env.handlers.metricSeg({ target: { closest: () => ({ dataset: { v: 'profit' } }) } });
assert.strictEqual(JSON.stringify(trend.data.datasets.map(d => d.label)), '["Profit"]', 'profit-only toggle');
env.handlers.metricSeg({ target: { closest: () => null } });   // click outside a button must not throw
assert.ok(trend.data.labels.every(l => /^\d{4}-W\d{2}$/.test(l)), 'weekly labels are ISO weeks');

// 2. data.js missing -> friendly message, no crash
env = makeEnv(false, true);
vm.runInNewContext(scripts[0], env.ctx);
assert.ok(/data\.js is missing/.test(env.els.app.innerHTML), 'missing data message');

// 3. Chart.js blocked/offline -> text parts still render, message shown
env = makeEnv(true, false);
vm.runInNewContext(scripts[0], env.ctx);
assert.ok(/Chart\.js could not load/.test(env.els.app.innerHTML), 'chart-load error shown');
assert.ok(env.els.kpis.innerHTML.includes('kpi'), 'KPIs still render without Chart.js');
console.log('Dashboard smoke tests: all passed');
