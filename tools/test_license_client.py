#!/usr/bin/env python3
"""License-SaaS 客户端 API 端到端测试（HMAC-SHA256签名协议）"""
import hmac, hashlib, json, time, uuid, urllib.request, urllib.error

APP_KEY = 'iptv807-tv'
CLIENT_SECRET = '91a62f41ff09c2eed51fa571a78c69014c5f9eacbaeca56c'
BASE = 'http://192.168.68.19:8090'

def signed(method, path, body=None):
    body_str = json.dumps(body or {}, ensure_ascii=False, separators=(',', ':'))
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex[:16]
    canonical = f"{method}\n{path}\n{ts}\n{nonce}\n{body_str}"
    sig = hmac.new(CLIENT_SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + path, data=(body_str.encode() if method == 'POST' else None), method=method)
    req.add_header('Content-Type', 'application/json')
    req.add_header('X-Timestamp', ts)
    req.add_header('X-Nonce', nonce)
    req.add_header('X-Signature', sig)
    req.add_header('X-App-Key', APP_KEY)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {'http': e.code, 'body': e.read().decode()[:200]}

print('app-info:', signed('GET', '/api/client/app-info'))
r = signed('POST', '/api/client/register', {'username': 'test01', 'password': 'test123456', 'cardKey': 'LS-F10E53D1-8C91BFD6-7E098CFF', 'machineCode': 'ANDROID-TV-001'})
print('register:', r)
r = signed('POST', '/api/client/login', {'username': 'test01', 'password': 'test123456', 'machineCode': 'ANDROID-TV-001'})
print('login:', r)
r = signed('POST', '/api/client/heartbeat', {'token': r.get('client_token', '')})
print('heartbeat:', r)
