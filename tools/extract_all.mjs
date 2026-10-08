import https from 'https';
import http from 'http';
import { URL } from 'url';
const UA = 'Mozilla/5.0 (Linux; Android 12; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
function fetch(url) {
  return new Promise((res, rej) => {
    const h = url.startsWith('https') ? https : http;
    const req = h.get(url, { headers: { 'User-Agent': UA } }, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => res(d));
    });
    req.on('error', rej);
    req.setTimeout(15000, () => { req.destroy(); rej(new Error('timeout')); });
  });
}
// 动态eval加密变量，再按解密函数链跑（bbot: page uses randomly-named vars each request）
function decodePage(html) {
  // 提取 var xx=""; var yy="...拼接..."; var qq=""; var ww="..."; ouyit/token块
  const blocks = [];
  const re = /<script>([\s\S]*?)<\/script>/g;
  let m;
  while ((m = re.exec(html))) {
    const body = m[1];
    if (body.includes('.split("")') && body.includes('var')) blocks.push(body);
    if (body.includes('function ') && body.includes('keyStr') && body.includes('charCodeAt')) blocks.push(body);
    if (body.includes('wngbe') || /function \w+\(dek\)/.test(body)) blocks.push(body);
  }
  const context = {};
  const sandbox = new Function(blocks.join('\n') + `
    // 返回解密函数名 + 猜出变量名
    const fnNames = [];
    // 找形如 function XXXX(dek){...} 就把名字报出来
    const decls = [...arguments.callee ? '' : ''][0];
    return { xefgv: typeof xefgv !== 'undefined' ? xefgv : null, ouyit: typeof ouyit !== 'undefined' ? ouyit : null, qrtkk: typeof qrtkk !== 'undefined' ? qrtkk : null };
  `);
  return null;
}

// 更简单：直接在Node vm里跑整段，把全部函数和变量都留在沙箱里
import vm from 'vm';
async function processPage(url) {
  const html = await fetch(url);
  const ctx = { window: {}, atob: null };
  const sandbox = vm.createContext({ window: {}, document: { getElementById: () => null, querySelectorAll: () => [], querySelector: () => null, addEventListener(){}, createElement() { return { setAttribute(){}, style:{} }; }, body: { appendChild(){} } }, location: { href: url }, navigator: { userAgent: UA }, setInterval: () => 0, clearInterval(){}, setTimeout: () => 0, clearTimeout(){}, fetch: () => {}, console, addEventListener(){}, $: () => ({ hide(){}, show(){}, selectmenu(){}, length:0 }), Hls: { isSupported: () => false, Events: {} }, mpegts: { isSupported: () => false, Events: {} }, devicePixelRatio: 2, innerWidth: 1280, innerHeight: 720, atob: (s) => Buffer.from(s, 'base64').toString('binary') });
  // 只跑不依赖DOM交互的脚本：解密脚本+变量， crib main script(避免动画循环定时器问题——用vm.runInContext就够了，主脚本里$()等没被调用)
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
  for (const s of scripts) {
    try { vm.runInContext(s, sandbox, { timeout: 3000 }); } catch (e) { /* DOM依赖的忽略 */ }
  }
  // 抓options
  const opts = [...html.matchAll(/<option value="([^"]+)">([^<]+)<\/option>/g)];
  // 找到call_in_main
  const main = scripts.find(s => s.includes('startPlayer') && s.includes('(uri)'));
  const decName = (main && (main.match(/const \w+ = (\w+)\(uri\)/) || [])[1]) || (main && (main.match(/const puri ?= ?(\w+)\(/) || [])[1]);
  if (!decName) return { url, error: 'no dec fn' };
  const results = opts.map(o => {
    try { return { line: o[2], url: sandbox[decName](o[1]) }; }
    catch (e) { return { line: o[2], error: String(e).slice(0,100) }; }
  });
  return { url, title: (html.match(/<title>([^<]*)</) || [])[1], results };
}

// 频道列表：从各分类页抓全部链接（data-ajax="false"）
const tids = ['tv', 'ty', 'ys', 'ws', 'gt'];
const seen = new Map();  // link -> name（按tid顺序去重）
for (const tid of tids) {
  try {
    const page = await fetch('https://m.iptv807.com/?tid=' + tid);
    for (const m of page.matchAll(/<a href="(\?act=play[^"]*)"[^>]*>([^<]+)<\/a>/g)) {
      const link = 'https://m.iptv807.com/' + m[1];
      if (!seen.has(link)) seen.set(link, m[2]);
    }
  } catch (e) { console.error('cat fail', tid, e.message); }
}
const channels = [...seen.entries()].map(([link, name]) => ({ link, name }));
// 去掉首页广告位那条假频道
const real = channels.filter(c => c.link.includes('&id='));
console.error('channels found:', channels.length);

const out = [];
for (const ch of real) {
  try {
    const r = await processPage(ch.link);
    out.push({ name: ch.name, url: r.url, lines: r.results?.map(l => l.url) });
    console.error('OK', ch.name, r.results?.[0]?.url || r.error);
  } catch (e) {
    out.push({ name: ch.name, error: e.message });
    console.error('ERR', ch.name, e.message);
  }
}
// 输出 JSON 到stdout
console.log(JSON.stringify(out, null, 2));
