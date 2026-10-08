#!/usr/bin/env python3
"""验证 channels.json 里各频道线路1可拉流（302 → 流头 FLV/m3u8）"""
import json, urllib.request, socket
socket.setdefaulttimeout(12)

UA = 'Mozilla/5.0 (Linux; Android 12) Chrome/124 Mobile Safari/537.36'
data = json.load(open('/vol1/1000/HD1/APP/iptv-tv-app/tools/channels.json'))
ok = [c for c in data if c.get('lines')]
print('channels:', len(ok))
for c in ok:
    url = c['lines'][0]
    try:
        req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': 'https://m.iptv807.com/'})
        # 不跟随重定向，先拿302目标
        class NoRedir(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k): return None
        op = urllib.request.build_opener(NoRedir)
        try:
            op.open(req)
            red = None
        except urllib.error.HTTPError as e:
            red = e.headers.get('Location')
        if not red:
            print(f"❌ {c['name']}: no redirect")
            continue
        # 拉最终流前32字节
        req2 = urllib.request.Request(red, headers={'User-Agent': UA})
        with urllib.request.urlopen(req2) as r:
            head = r.read(32)
        magic = 'FLV' if head.startswith(b'FLV') else ('m3u8' if b'#EXTM3U' in head else head[:8])
        print(f"✅ {c['name']}: {magic}")
    except Exception as e:
        print(f"❌ {c['name']}: {str(e)[:80]}")
