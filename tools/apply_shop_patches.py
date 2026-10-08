#!/usr/bin/env python3
"""给 LoginActivity/TrialExpired 加ShopActivity入口 + Manifest声明"""
import io

# 1) LoginActivity
p = '/vol1/1000/HD1/APP/iptv-tv-app/app/src/main/java/com/iptv807/tv/LoginActivity.kt'
s = open(p).read()
if 'ShopActivity' not in s:
    s = s.replace('''        val freeBtn = Button(this).apply {''', '''        val shopBtn = Button(this).apply {
            text = "购买套餐（支付宝/微信）"; textSize = 15f
            setBackgroundColor(Color.parseColor("#1B6EF3"))
            setOnClickListener { startActivity(android.content.Intent(this@LoginActivity, ShopActivity::class.java)) }
        }
        root.addView(shopBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })

        val freeBtn = Button(this).apply {''')
    s = s.replace('''            text = "没有卡密？点「先看一会」进免费试看"''', '''            text = "已有卡密可直接激活；或购买套餐自动绑定本机"''')
    open(p, 'w').write(s)
    print('LoginActivity patched')
else:
    print('LoginActivity already patched')

# 2) TrialExpiredActivity
p = '/vol1/1000/HD1/APP/iptv-tv-app/app/src/main/java/com/iptv807/tv/TrialExpiredActivity.kt'
s = open(p).read()
if 'ShopActivity' not in s:
    s = s.replace('''        root.addView(Button(this).apply {
            text = "打开购买页（遥控OK）"
            textSize = 19f
            setOnClickListener { openShop() }
        })''', '''        root.addView(Button(this).apply {
            text = "购买套餐（OK键）"
            textSize = 19f
            setOnClickListener { startActivity(android.content.Intent(this@TrialExpiredActivity, ShopActivity::class.java)); finish() }
        })''')
    open(p, 'w').write(s)
    print('TrialExpired patched')

# 3) Manifest
p = '/vol1/1000/HD1/APP/iptv-tv-app/app/src/main/AndroidManifest.xml'
s = open(p).read()
if 'ShopActivity' not in s:
    s = s.replace('</application>', '''        <activity android:name=".ShopActivity" android:exported="false" />
    </application>''')
    open(p, 'w').write(s)
    print('Manifest patched')
print('done')
