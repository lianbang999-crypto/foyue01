#!/usr/bin/env python3
# 表情包流水线：256 PNG → WebP + packs.json（三语名 + 分类）
# 三个官方包：日常(daily 42) / 敦煌(dunhuang 38) / 牛来(cow 24)，每包 ≤50（Lifebear 规则：5×2×5页）
import json, os, glob, shutil
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app/src/main/assets/stamps')

ZH = {
 'mood_happy':'开心','mood_love':'爱心','mood_calm':'平静','mood_sad_cloud':'难过','mood_angry':'生气',
 'mood_excited':'兴奋','mood_sun':'晴朗','mood_cheerful_cloud':'雀跃','mood_sleepy_cloud':'困困',
 'life_coffee':'咖啡','life_gift':'礼物','life_birthday':'生日','life_meal':'吃饭','life_movie':'看电影',
 'life_plant':'绿植','life_handbag':'购物','life_tea':'喝茶','life_reading':'读书',
 'work_meeting':'开会','work_briefcase':'上班','work_notes':'笔记','work_morning':'早班','work_night':'加班',
 'work_deadline':'截止日','work_chat':'沟通','work_scroll':'文书','work_checklist':'清单','work_laptop':'办公',
 'health_run':'跑步','health_meditation':'冥想','health_strength':'健身','health_heart':'健康',
 'health_badminton':'羽毛球','health_bicycle':'骑行','health_sleep':'早睡','health_jump_rope':'跳绳','health_yoga_mat':'瑜伽',
 'travel_luggage':'行李','travel_ticket':'车票','travel_car':'出行','travel_camera':'拍照','travel_camel':'骆驼',
 'entertainment_music':'音乐','entertainment_game':'游戏','entertainment_pipa':'琵琶',
 'festival_lantern':'灯笼','festival_mooncake':'月饼','festival_zongzi':'粽子','festival_fireworks':'烟花',
 'schedule_rest':'休息日','schedule_work':'工作日','schedule_done':'完成',
 'deer_nine_color':'九色鹿','theme_lotus':'莲花','theme_cloud':'祥云','theme_mountain':'远山',
 'theme_moon':'月亮','theme_wave':'水波',
 'dunhuang_apsara':'飞天','dunhuang_vase':'宝瓶','dunhuang_deer':'九色鹿','dunhuang_lotus':'莲花',
 'dunhuang_cloud':'祥云','dunhuang_phoenix':'凤鸟','dunhuang_mountain':'远山','dunhuang_wave':'水波',
 'dunhuang_jewel_lotus':'宝莲',
 # 牛来
 'happy':'开心','love':'爱心','sleepy':'困困','sad':'难过','angry':'生气','excited':'兴奋',
 'coffee':'咖啡','gift':'礼物','birthday_cake':'生日','reading':'读书','notes':'笔记',
 'work_laptop2':'办公','checklist_done':'完成','meeting':'开会','shopping':'购物','travel':'旅行',
 'camera':'拍照','music':'音乐','exercise':'运动','yoga_meditation':'瑜伽','eating':'吃饭',
 'rest_day':'休息日','moon_night':'晚安','celebration':'庆祝',
}
EN = {
 'mood_happy':'Happy','mood_love':'Love','mood_calm':'Calm','mood_sad_cloud':'Sad','mood_angry':'Angry',
 'mood_excited':'Excited','mood_sun':'Sunny','mood_cheerful_cloud':'Cheerful','mood_sleepy_cloud':'Sleepy',
 'life_coffee':'Coffee','life_gift':'Gift','life_birthday':'Birthday','life_meal':'Meal','life_movie':'Movie',
 'life_plant':'Plant','life_handbag':'Shopping','life_tea':'Tea','life_reading':'Reading',
 'work_meeting':'Meeting','work_briefcase':'Work','work_notes':'Notes','work_morning':'Morning','work_night':'Overtime',
 'work_deadline':'Deadline','work_chat':'Chat','work_scroll':'Docs','work_checklist':'Checklist','work_laptop':'Laptop',
 'health_run':'Run','health_meditation':'Meditate','health_strength':'Gym','health_heart':'Health',
 'health_badminton':'Badminton','health_bicycle':'Cycling','health_sleep':'Sleep','health_jump_rope':'Jump rope','health_yoga_mat':'Yoga',
 'travel_luggage':'Luggage','travel_ticket':'Ticket','travel_car':'Trip','travel_camera':'Photo','travel_camel':'Camel',
 'entertainment_music':'Music','entertainment_game':'Game','entertainment_pipa':'Pipa',
 'festival_lantern':'Lantern','festival_mooncake':'Mooncake','festival_zongzi':'Zongzi','festival_fireworks':'Fireworks',
 'schedule_rest':'Day off','schedule_work':'Workday','schedule_done':'Done',
 'deer_nine_color':'Nine-color Deer','theme_lotus':'Lotus','theme_cloud':'Cloud','theme_mountain':'Mountain',
 'theme_moon':'Moon','theme_wave':'Wave',
 'dunhuang_apsara':'Apsara','dunhuang_vase':'Vase','dunhuang_deer':'Nine-color Deer','dunhuang_lotus':'Lotus',
 'dunhuang_cloud':'Cloud','dunhuang_phoenix':'Phoenix','dunhuang_mountain':'Mountain','dunhuang_wave':'Wave',
 'dunhuang_jewel_lotus':'Jewel Lotus',
 'happy':'Happy','love':'Love','sleepy':'Sleepy','sad':'Sad','angry':'Angry','excited':'Excited',
 'coffee':'Coffee','gift':'Gift','birthday_cake':'Birthday','reading':'Reading','notes':'Notes',
 'work_laptop2':'Laptop','checklist_done':'Done','meeting':'Meeting','shopping':'Shopping','travel':'Travel',
 'camera':'Photo','music':'Music','exercise':'Exercise','yoga_meditation':'Yoga','eating':'Meal',
 'rest_day':'Day off','moon_night':'Night','celebration':'Celebrate',
}
TW = {'开心':'開心','爱心':'愛心','平静':'平靜','难过':'難過','生气':'生氣','兴奋':'興奮','晴朗':'晴朗',
 '雀跃':'雀躍','困困':'睏睏','咖啡':'咖啡','礼物':'禮物','生日':'生日','吃饭':'吃飯','看电影':'看電影',
 '绿植':'綠植','购物':'購物','喝茶':'喝茶','读书':'讀書','开会':'開會','上班':'上班','笔记':'筆記',
 '早班':'早班','加班':'加班','截止日':'截止日','沟通':'溝通','文书':'文書','清单':'清單','办公':'辦公',
 '跑步':'跑步','冥想':'冥想','健身':'健身','健康':'健康','羽毛球':'羽毛球','骑行':'騎行','早睡':'早睡',
 '跳绳':'跳繩','瑜伽':'瑜伽','行李':'行李','车票':'車票','出行':'出行','拍照':'拍照','骆驼':'駱駝',
 '音乐':'音樂','游戏':'遊戲','琵琶':'琵琶','灯笼':'燈籠','月饼':'月餅','粽子':'粽子','烟花':'煙花',
 '休息日':'休息日','工作日':'工作日','完成':'完成','九色鹿':'九色鹿','莲花':'蓮花','祥云':'祥雲',
 '远山':'遠山','月亮':'月亮','水波':'水波','飞天':'飛天','宝瓶':'寶瓶','凤鸟':'鳳鳥','宝莲':'寶蓮',
 '旅行':'旅行','运动':'運動','晚安':'晚安','庆祝':'慶祝'}

# (包id, 中文, 英文, 繁体, 源目录)  —— 顺序即选择器 Tab 顺序
PACKS = [
    ('daily',    '日常', 'Daily',    '日常', 'calendar_stamps_app_256png/base_256_png'),
    ('dunhuang', '敦煌', 'Dunhuang', '敦煌', 'calendar_stamps_app_256png/dunhuang_256_png'),
    ('cow',      '牛来', 'Cow',      '牛來', 'LOOKA_Cow_Stamp_V1_256_fixed'),
]
MAX_PER_PACK = 50   # Lifebear 规则：5列×2行×5页

def cat_of(sem):
    head = sem.split('_')[0]
    return {'mood':'mood','life':'life','work':'work','health':'health','travel':'travel',
            'entertainment':'life','festival':'festival','schedule':'work',
            'deer':'theme','theme':'theme','dunhuang':'theme'}.get(head, 'life')

if os.path.isdir(OUT):
    shutil.rmtree(OUT)
packs, total, warn = [], 0, []
for pid, zh, en, tw, src in PACKS:
    d = os.path.join(OUT, pid); os.makedirs(d, exist_ok=True)
    stamps = []
    files = sorted(glob.glob(os.path.join(ROOT, src, '*.png')))
    for f in files:
        base = os.path.splitext(os.path.basename(f))[0]
        if base.endswith('_preview'):        # 跳过联系表预览图
            continue
        im = Image.open(f).convert('RGBA')
        if im.size != (256, 256):
            warn.append(f'{base} 尺寸 {im.size}，跳过'); continue
        if len(stamps) >= MAX_PER_PACK:
            warn.append(f'{pid} 超出 {MAX_PER_PACK} 上限，{base} 起丢弃'); break
        sem = base.split('_', 1)[1] if '_' in base else base
        key = sem if sem in ZH else base
        im.save(os.path.join(d, base + '.webp'), 'WEBP', quality=90, method=6)
        zh_name = ZH.get(key, sem)
        stamps.append({
            'id': f'{pid}/{base}', 'file': f'{pid}/{base}.webp',
            'zh': zh_name, 'en': EN.get(key, sem.replace('_', ' ').title()),
            'tw': TW.get(zh_name, zh_name), 'cat': cat_of(sem)
        })
        total += 1
    packs.append({'id': pid, 'zh': zh, 'en': en, 'tw': tw, 'count': len(stamps), 'stamps': stamps})

with open(os.path.join(OUT, 'packs.json'), 'w', encoding='utf-8') as f:
    json.dump({'version': 2, 'packs': packs}, f, ensure_ascii=False, separators=(',', ':'))

size = sum(os.path.getsize(p) for p in glob.glob(os.path.join(OUT, '*/*.webp')))
for p in packs:
    pages = (p['count'] + 9) // 10
    print(f"  {p['id']:<10} {p['zh']:<4} {p['count']:>2} 枚 → {pages} 页")
print(f'✅ 共 {total} 枚 → assets/stamps（{size//1024}KB）')
if warn: print('⚠️', '; '.join(warn))
