#!/usr/bin/env python3
# i18n 字典生成：dict-en.tsv（唯一真源）→ assets/i18n/{en,zh-TW}.json + server/public/i18n/
# zh-TW = 词级覆盖（先）+ OpenCC 字表（后，复用 foyue01/public/js/zh-t.js）
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def unesc(s):
    return s.replace('\\n', '\n').replace('\\"', '"').replace('\\\\', '\\')

# ---- en ----
en = {}
for line in open(os.path.join(ROOT, 'i18n/dict-en.tsv')):
    line = line.rstrip('\n')
    if not line or '\t' not in line: continue
    k, v = line.split('\t', 1)
    en[unesc(k)] = unesc(v)

# ---- zh-TW：字表 ----
zt = open('/Users/bincai/Downloads/foyue/foyue01/public/js/zh-t.js').read()
frm = re.search(r'S2T_FROM = "([^"]+)"', zt).group(1)
to  = re.search(r'S2T_TO\s*=\s*"([^"]+)"', zt).group(1)
assert len(frm) == len(to), f'字表长度不一致 {len(frm)} vs {len(to)}'
cmap = dict(zip(frm, to))

# 台湾用词覆盖（先于字表，长词优先）
WORDS = [
 ('日历','行事曆'),('日程','行程'),('登录','登入'),('退出登录','登出'),('注册','註冊'),
 ('设置','設定'),('默认','預設'),('模板','範本'),('搜索','搜尋'),('视图','檢視'),
 ('缓存','快取'),('文件','檔案'),('导出','匯出'),('导入','匯入'),('信息','資訊'),
 ('软件','軟體'),('网络','網路'),('同步','同步'),('云同步','雲端同步'),('云端','雲端'),
 ('智能','智慧'),('刷新','重新整理'),('点击','點擊'),('绑定','綁定'),('邮箱','信箱'),
 ('隐私政策','隱私權政策'),('用户协议','使用者條款'),('用户','使用者'),('数据','資料'),
 ('服务端','伺服器端'),('服务器','伺服器'),('内存','記憶體'),('通知权限','通知權限'),
 ('周日','週日'),('周一','週一'),('周二','週二'),('周三','週三'),('周四','週四'),
 ('周五','週五'),('周六','週六'),('本周','本週'),('每周','每週'),('上周','上週'),
 ('下周','下週'),('一周','一週'),('账号','帳號'),('账户','帳戶'),('密码','密碼'),
 ('支持','支援'),('程序','程式'),('日志','日誌'),('窗口','視窗'),('菜单','選單'),
]
WORDS.sort(key=lambda x: -len(x[0]))

def to_tw(s):
    for a, b in WORDS:
        s = s.replace(a, b)
    return ''.join(cmap.get(c, c) for c in s)

tw = {k: to_tw(k) for k in en.keys()}

def dump(path, d):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(d, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)

dump(os.path.join(ROOT, 'app/src/main/assets/i18n/en.json'), en)
dump(os.path.join(ROOT, 'app/src/main/assets/i18n/zh-TW.json'), tw)
dump(os.path.join(ROOT, 'server/public/i18n/en.json'), en)
dump(os.path.join(ROOT, 'server/public/i18n/zh-TW.json'), tw)

# 覆盖率核查：源码里的 key 是否都在字典
import glob
pat = re.compile(r'tr\(\s*"((?:[^"\\]|\\.)*)"')
missing = set()
for p in glob.glob(os.path.join(ROOT, 'app/src/main/java/com/looka/app/**/*.kt'), recursive=True):
    for m in pat.finditer(open(p).read()):
        k = unesc(m.group(1))
        if re.search(r'[一-鿿]', k) and k not in en:
            missing.add(k)
print(f'en {len(en)} 条 / zh-TW {len(tw)} 条 / 缺译 {len(missing)} 条')
for k in sorted(missing)[:20]: print('  缺:', repr(k))
