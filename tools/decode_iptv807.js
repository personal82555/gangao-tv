// 解析 m.iptv807.com 播放页加密线路 → 真实播放地址
// 用法: node decode_iptv807.js <play_url>  (缺省 TVB翡翠台)
const https = require('https');
const http = require('http');

function fetch(url) {
  return new Promise((res, rej) => {
    const h = url.startsWith('https') ? https : http;
    const req = h.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 12) Chrome/124 Mobile Safari/537.36' }
    }, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => res(d));
    });
    req.on('error', rej);
    req.setTimeout(20000, () => { req.destroy(); rej(new Error('timeout')); });
  });
}

// site JS: base64 decode
function sappb(data) { return Buffer.from(data, 'binary').toString('binary'); }
function atob(s) { return Buffer.from(s, 'base64').toString('binary'); }

function rfhmo(str, key) {
  const string = atob(str);
  key = key + '93cf0945a0a4eecb';
  const len = key.length;
  let code = '';
  for (let i = 0; i < string.length; i++) {
    code += String.fromCharCode(string.charCodeAt(i) ^ key.charCodeAt(i % len));
  }
  return atob(code);  // stra = sappb(code) → 实际是 base64 解码
}

function wngbe(dek, xefgv, ouyit, qrtkk) {
  dek = dek.split('').reverse().join('');
  dek = rfhmo(dek, xefgv);
  dek = dek.replace('token=' + ouyit, 'token=' + qrtkk);
  dek = dek.replace(xefgv, '');
  return dek;
}

function b64utf8(bin) {
  // 加密前URL可能含UTF-8中文，用 UTF-8 重新编码
  const bytes = Buffer.from(bin, 'binary');
  return bytes.toString('utf8');
}

(async () => {
  const playUrl = process.argv[2] || 'https://m.iptv807.com/?act=play&tid=tv&id=24';
  const html = await fetch(playUrl);
  // 变量都在页面的 <script> 块中
  const mVar = (name) => {
    const m = html.match(new RegExp('var\\\\s+' + name + '\\\\s*=\\\\s*"([^"]*)"'));
    return m ? m[1] : '';
  };
  // xefgv/qrtkk 是由 fbjao/lnzyw 拼出来的
  let xefgv = '', qrtkk = '', ouyit = '', fbjao = '', lnzyw = '';
  const mF = html.match(/var fbjao\s*=(.*?);/s);
  const mL = html.match(/var lnzyw\s*=(.*?);/s);
  const mO = html.match(/ouyit\s*=\s*"([^"]+)"/);
  if (mF) fbjao = eval(mF[1]);
  if (mL) lnzyw = eval(mL[1]);
  if (mO) ouyit = mO[1];
  xefgv = fbjao; qrtkk = lnzyw;

  // 所有线路 option
  const opts = [...html.matchAll(/<option value="([^"]+)">([^<]+)<\/option>/g)];
  if (!opts.length) { console.error('no lines found'); process.exit(1); }
  console.error('lines:', opts.length);
  const results = opts.map(o => {
    try {
      const raw = wngbe(o[1], xefgv, ouyit, qrtkk);
      return { line: o[2], url: b64utf8(raw) };
    } catch (e) { return { line: o[2], url: 'DECODE_ERR: ' + e.message }; }
  });
  console.log(JSON.stringify(results, null, 2));
})().catch(e => { console.error('FAIL:', e.message); process.exit(1); });
