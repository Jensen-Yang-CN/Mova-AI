"""S1 · 种子库 v3.1：场景再平衡 + none 拒答负样本扩量。

v3 背景：v2 池子 28200 条里 food 占 27628（98%）——food 模板带
{veg}×{protein}×{staple} 三词表连乘，单个 spec 就膨胀到 9000 条，
而 reading/chat/location 只有单词表（25~40 条/spec）。8000 条正式跑
实测最终数据集 food 占 90.5%，学生模型会变成"做饭专家"。

v3 三处改动：
  1. 每个 spec 可选 `cap`：笛卡尔积超限后确定性抽样截断（food 三连乘组
     各 cap 到 1700~2000，food 总量压到 ~6300）。
  2. 扩词表：doc 25→60、relation 15→30、place 20→40，新增
     chat_ask/channel/tone/level/purpose/errand/minutes/music/device/
     country/city/number/item 共 12 组小词表。
  3. 补 reading/chat/location/none 模板（多占位符连乘），把四个场景
     的池子抬到几千条量级；none 场景补知识问答/天气/价格/算术等
     负样本（算术 {number}×{number} 连乘一条 spec 出 300 条）。

v3.1 改动（12000 正式跑验收发现）：最终数据集 none（拒答）场景只有
3.6%——拒答是路由器的安全底线（该转云端/拒绝时不能硬接），训练里
占比过低。根因是种子池本身 none 就少（v3 池里只有约 554 条 = 3.4%），
不是采样器的问题。v3.1 只在 SPECS **末尾追加** 20 组 none 新规格
（天气细节/旅行/国家知识/食材知识/设备常识/翻译/算术/体育/金融/
健康/编程/科学/生活窍门/购物/游戏/创作/宠物/学习/数学趣题/成语，
新增 lang/genre 两个小词表），**不动任何既有 spec**。
这样旧种子的全局序号、RNG 消耗顺序、ID、措辞全部不变，
data/cache/labels_limit12000_k3.jsonl 全量复用：实测 12000 抽取
11578 条命中缓存，只有 422 条新增（全部 none_），约 2100 次 API
调用。设计约束：新话题必须明确在 5 场景契约之外，避免教师误判
进 in-contract 场景造成语义错标。

本机自测（2026-09 实测值）：总池 17027，
  food 33.6% / reading 29.3% / chat 19.1% / location 11.8% / none 6.1%
  （1047 条），28 个 (scene,intent,complexity_hint) 格子；
  12000 抽取 food 4037 / reading 3517 / chat 2296 / location 1412 /
  none 738，场景占比与池一致（场景分层抽样），固定种子下逐条可复现。

API 与 v2/v3 完全一致：Seed / generate_seeds / coverage_cells，
`python pipeline/seeds.py` 自测打印池子构成。
"""
from __future__ import annotations

import itertools
import random
from dataclasses import dataclass, field

# ============================================================
# v3 词表（v1/v2 原词在前，保证小 limit 运行时分布可追溯）
# ============================================================

VOCAB: dict[str, list[str]] = {
    "veg": [
        "番茄", "青椒", "土豆", "西兰花", "菠菜", "茄子", "黄瓜", "胡萝卜",
        "白菜", "生菜", "洋葱", "香菇", "金针菇", "木耳", "玉米", "南瓜",
        "冬瓜", "山药", "芹菜", "韭菜", "芦笋", "秋葵", "花菜", "娃娃菜",
        "油麦菜", "豆角", "西葫芦", "海带", "莴笋", "包菜",
    ],
    "protein": [
        "鸡蛋", "鸡胸肉", "牛肉", "豆腐", "虾仁", "五花肉", "带鱼", "鸡腿",
        "猪肉", "羊肉", "三文鱼", "巴沙鱼", "鱿鱼", "猪蹄", "鸭腿", "排骨",
        "蛤蜊", "鳕鱼", "牛腩", "里脊",
    ],
    "staple": [
        "米饭", "面条", "馒头", "年糕", "意面", "馄饨",
        "饺子", "包子", "花卷", "小米粥", "燕麦", "紫薯", "红薯", "全麦面包", "粉丝",
    ],
    "meal": ["早饭", "午饭", "晚饭", "夜宵"],
    "doc": [
        "产品需求文档", "学期论文", "租赁合同", "会议纪要", "体检报告", "课程讲义",
        "项目合同", "保险条款", "操作手册", "开题报告", "实验数据报告", "周报",
        "商业计划书", "专利说明书", "用户手册", "培训材料", "项目标书",
        "绩效评估表", "调岗通知", "购房合同", "借款合同", "保密协议",
        "报销单", "项目排期表", "考核评分表",
        "简历", "毕业论文", "实验报告", "病历", "投资协议", "股东协议",
        "竞业协议", "续租合同", "采购订单", "质检报告", "用户调研报告",
        "竞品分析报告", "年度报告", "月度总结", "出差申请", "项目启动文档",
        "技术方案", "测试报告", "发布说明", "接口文档", "数据字典",
        "安全规范", "劳动合同", "试用期考核表", "培训试卷", "参考答案",
        "作文初稿", "演讲稿", "邮件草稿", "报告模板", "事故复盘报告",
        "客户拜访纪要", "供应商协议", "维保手册",
    ],
    "relation": [
        "室友", "同事", "大学同学", "部门主管", "邻居", "表姐",
        "妈妈", "爸爸", "老婆", "男朋友", "女朋友", "老板", "客户", "导师", "房东",
        "闺蜜", "发小", "同学", "组员", "下属", "实习生", "供应商",
        "快递员", "物业管家", "银行经理", "保险代理", "健身教练", "老师", "老乡", "合伙人",
    ],
    "place": [
        "超市", "商场", "学校", "地铁站", "医院", "健身房",
        "图书馆", "咖啡厅", "写字楼", "火车站", "机场", "小区门口", "食堂",
        "药店", "银行", "公园", "电影院", "加油站", "停车场", "便利店",
        "快递驿站", "打印店", "干洗店", "洗车店", "诊所", "牙科诊所",
        "眼镜店", "菜市场", "水果店", "面包店", "奶茶店", "火锅店",
        "影城", "会展中心", "体育中心", "社区卫生站", "邮局", "税务局",
        "公证处", "瑜伽馆",
    ],
    # ---------------- v3 新增小词表 ----------------
    "chat_ask": [
        "帮忙搬家", "周末加班", "借我两千块钱", "请我吃顿饭", "替我值一次班",
        "参加部门团建", "帮忙接孩子", "帮忙做个 PPT", "帮忙排队挂号",
        "帮我投一票", "借我用一下车", "帮忙取个快递", "帮我介绍个朋友",
        "帮忙搬下行李", "借个工具用", "帮忙拍张照", "一起打牌",
        "帮忙浇花", "借下你的笔记", "帮我代一节课",
    ],
    "channel": ["微信", "短信", "电话", "当面", "钉钉", "邮件"],
    "tone": ["委婉", "客气", "随意", "正式", "简洁", "温和"],
    "level": ["高中生", "大一水平", "大三水平", "研究生水平", "纯外行", "有一点基础"],
    "purpose": ["发到群里", "写周报用", "决定要不要签字", "备考用", "汇报给领导", "留作参考"],
    "errand": ["买点菜", "买份水果", "买点零食", "买点日用品", "买点文具", "买盒感冒药", "买份礼物", "买点抽纸"],
    "minutes": ["5", "10", "15", "20", "30", "45", "60"],
    "music": ["轻音乐", "爵士乐", "摇滚乐", "流行歌", "古典乐", "摇篮曲", "老歌"],
    "device": ["空调", "灯", "加湿器", "电视", "音箱", "净水器"],
    "country": [
        "中国", "法国", "日本", "英国", "德国", "意大利", "西班牙", "加拿大",
        "澳大利亚", "巴西", "印度", "泰国", "新加坡", "韩国", "瑞士",
        "新西兰", "墨西哥", "埃及", "土耳其", "俄罗斯",
    ],
    "city": [
        "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安",
        "南京", "苏州", "重庆", "天津", "青岛", "大连", "厦门",
        "昆明", "哈尔滨", "拉萨", "乌鲁木齐", "三亚",
    ],
    # num2 与 number 同内容：同一模板里重复用 {number} 时，
    # _placeholders 按去重名取笛卡尔积只会出 10 条，拆两个占位符才出 100 条
    "number": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"],
    "num2": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"],
    "item": [
        "黄金", "猪肉", "汽油", "鸡蛋", "牛肉", "大米", "鱼", "鸡肉",
        "羊肉", "螃蟹", "豆腐", "白菜", "苹果", "橙子", "香蕉",
        "西瓜", "牛奶", "面包", "啤酒", "咖啡",
    ],
    # ---------------- v3.1 none 负样本词表 ----------------
    "lang": ["英语", "日语", "韩语", "法语", "西班牙语"],
    "genre": ["手机游戏", "主机游戏", "PC游戏", "解谜", "策略", "射击"],
}

SIGNALS = {
    "kitchen": "时间：18:40 | 前台 App：相机 | 位置：家 | 运动：静止 | 屏幕：亮",
    "desk": "时间：14:10 | 前台 App：WPS | 位置：办公室 | 运动：静止 | 屏幕：亮",
    "commute": "时间：08:05 | 前台 App：微信 | 位置：地铁 | 运动：移动中 | 屏幕：亮",
    "market": "时间：19:20 | 前台 App：桌面 | 位置：超市 | 运动：步行 | 屏幕：亮",
    "night": "时间：23:30 | 前台 App：微信 | 位置：家 | 运动：静止 | 屏幕：亮",
    "campus": "时间：12:15 | 前台 App：微信 | 位置：校园 | 运动：步行 | 屏幕：亮",
    "gym": "时间：20:15 | 前台 App：微信 | 位置：健身房 | 运动：静止 | 屏幕：亮",
    "hospital": "时间：09:40 | 前台 App：微信 | 位置：医院 | 运动：移动中 | 屏幕：亮",
}


@dataclass
class Seed:
    id: str
    scene: str
    intent: str
    complexity_hint: str
    signals: str
    utterance: str
    expect_slots: list[str] = field(default_factory=list)


# spec 结构：scene / intent / complexity_hint / signals / expect_slots /
# templates / （v3 新增可选）cap —— 单模板笛卡尔积的截断上限。
SPECS: list[dict] = [
    # ---------------- 做饭（v3：cap 压量，~6300） ----------------
    dict(scene="food", intent="diet_advice", complexity_hint="medium", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"], cap=2000,
         templates=[
             "我在厨房，手边有{veg}和{protein}，{meal}做点什么好？",
             "拍了一下冰箱，只有{veg}和{staple}，能凑一顿{meal}吗？",
             "今天买了{protein}和{veg}，想清淡一点，怎么做好？",
         ]),
    dict(scene="food", intent="ingredient_check", complexity_hint="easy", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"], cap=2000,
         templates=[
             "看看这些是不是都能一起吃：{veg}、{protein}、{staple}？",
             "我这里有{veg}和{protein}，会不会相克？",
         ]),
    dict(scene="food", intent="recipe_lookup", complexity_hint="hard", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"], cap=1700,
         templates=[
             "只有{veg}、{protein}和{staple}，帮我设计一份适合减脂的三菜一汤，要控制总热量",
             "用{protein}和{veg}做一道能带饭的菜，要放凉了也好吃，最好前一天晚上就能备好",
         ]),
    dict(scene="food", intent="none", complexity_hint="easy", signals=SIGNALS["kitchen"],
         expect_slots=[],
         templates=["这个菜市场叫什么名字？", "现在几点了？"]),
    # 设备指令类负样本（契约之外，明确不属于任何 intent）
    dict(scene="food", intent="none", complexity_hint="easy", signals=SIGNALS["kitchen"],
         expect_slots=[],
         templates=[
             "把音乐放起来",
             "打开手电筒",
             "帮我定个明早七点的闹钟",
             "音量调大一点",
             "今天星期几",
             "讲个笑话吧",
         ]),
    dict(scene="food", intent="none", complexity_hint="easy", signals=SIGNALS["kitchen"],
         expect_slots=[],
         templates=[
             "定个{minutes}分钟的计时器",
             "把{device}打开",
             "放点{music}",
             "音量调小一点",
         ]),

    # ---------------- 阅读（v3：扩 doc/level/purpose 词表，~5000） ----------------
    dict(scene="reading", intent="summarize", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "这份{doc}太长了，帮我看看重点是什么",
             "把这份{doc}总结三句话，{purpose}",
             "我只有{level}的水平，帮我把这份{doc}过一遍，告诉我哪些部分重要",
         ]),
    dict(scene="reading", intent="summarize", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "这份{doc}是 PDF 格式，帮我快速过一遍",
             "拍了一张{doc}的照片，把重点帮我标出来",
             "这份{doc}是 PDF，总结成三句话，{purpose}",
         ]),
    dict(scene="reading", intent="summarize", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "帮我看下这份{doc}，直接告诉我结论",
             "把这份{doc}扫一遍，数据有没有明显问题",
             "这份{doc}帮我快速过一下，我等下开会前只有两分钟",
         ]),
    # 三占位符连乘的 hard 变体（2160 条池，cap 到 900）
    dict(scene="reading", intent="summarize", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"], cap=900,
         templates=[
             "这份{doc}是 PDF，我只有{level}的基础，总结三句话重点，{purpose}",
         ]),
    dict(scene="reading", intent="key_points", complexity_hint="easy", signals=SIGNALS["hospital"],
         expect_slots=["source", "text_length"],
         templates=[
             "{doc}里有哪些是我必须注意的条款？",
             "从这份{doc}里挑出最关键的几条要求",
         ]),
    dict(scene="reading", intent="key_points", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "把{doc}里的要点列个清单，{purpose}",
             "{doc}里哪些条款要特别留意？{purpose}",
         ]),
    dict(scene="reading", intent="difficulty_judge", complexity_hint="hard", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"], cap=780,
         templates=[
             "这份{doc}以我目前的水平能看懂吗？我只有{level}基础",
             "{doc}里哪些部分以我{level}的基础看不懂？把需要补的背景知识列个清单",
             "把{doc}按难度排序，我是{level}，告诉我哪部分需要先补背景",
         ]),
    dict(scene="reading", intent="difficulty_judge", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "{doc}难不难？我{level}，能看完吗",
         ]),
    dict(scene="reading", intent="difficulty_judge", complexity_hint="hard", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "帮我把{doc}读一遍，告诉我哪里容易踩坑，{level}的人要注意什么风险",
         ]),
    # reading 的 intent=none（转发/存档/打印类指令，不属于任何阅读 intent）
    dict(scene="reading", intent="none", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=[], cap=360,
         templates=[
             "把这份{doc}直接转发到工作群",
             "这份{doc}存到我的网盘里",
             "这份{doc}能打印出来吗",
             "把这份{doc}转发给{relation}",
         ]),

    # ---------------- 聊天（v3：扩 relation 词表 + chat_ask/channel/tone，~3300） ----------------
    dict(scene="chat", intent="reply_suggest", complexity_hint="medium", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"], cap=900,
         templates=[
             "{relation}问我周末能不能{chat_ask}，我不想直接答应，怎么回？",
             "{relation}在群里@我问方案进度，其实我还没开始，怎么回比较得体？",
             "{relation}在{channel}上问我能不能{chat_ask}，我其实想拒绝，怎么说？",
         ]),
    dict(scene="chat", intent="reply_suggest", complexity_hint="hard", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"], cap=900,
         templates=[
             "{relation}在{channel}上又让我{chat_ask}，这都第三次了，我确实做不了，怎么拒绝又不伤感情？",
             "帮我拟一段{channel}回复给{relation}，背景是他想让我{chat_ask}，但我那天要出远门",
         ]),
    dict(scene="chat", intent="reply_suggest", complexity_hint="medium", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "{relation}发来一段很长的语音，说想让我{chat_ask}，帮我想个回复，别太冷淡也别太热络",
         ]),
    dict(scene="chat", intent="rewrite", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "把「这个方案不行」改得委婉一点",
             "把这句话改得不那么冲：你根本没看我的消息",
             "把「我没空」改成给{relation}的回复，别太生硬",
             "把「我没做」改成给{relation}的回复，不伤关系的那种",
             "把这句话改成{tone}一点：这个方案不行",
         ]),
    dict(scene="chat", intent="rewrite", complexity_hint="hard", signals=SIGNALS["commute"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "原话是「你这做得不对」，帮我改成{channel}里给{relation}的反馈，专业但不伤人",
         ]),
    dict(scene="chat", intent="tone_adjust", complexity_hint="easy", signals=SIGNALS["commute"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "{relation}发来的消息我要回得专业一点，帮我把语气调一下",
             "我想拒绝但不想得罪{relation}，这句话怎么改？",
             "把「我没法去」改成{tone}语气，发给{relation}",
         ]),
    dict(scene="chat", intent="tone_adjust", complexity_hint="medium", signals=SIGNALS["commute"],
         expect_slots=["target_tone", "goal"], cap=360,
         templates=[
             "{relation}在{channel}上说「你到底来不来」，帮我把回复改得更{tone}一点",
         ]),
    # chat 的 intent=none（打招呼 / 确认类，不属于任何 intent）
    dict(scene="chat", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "在吗", "嗯嗯，好的", "哈哈", "收到", "忙什么呢",
             "好的好的", "行，没问题", "认真的吗", "知道了知道了", "哈哈哈",
         ]),

    # ---------------- 位置（v3：扩 place 词表 + errand 连乘，~2000） ----------------
    dict(scene="location", intent="nearby_hint", complexity_hint="easy", signals=SIGNALS["market"],
         expect_slots=["place_type"],
         templates=[
             "我现在在{place}，有什么需要顺手买的吗？",
             "到了{place}，提醒我该准备点什么",
         ]),
    dict(scene="location", intent="nearby_hint", complexity_hint="medium", signals=SIGNALS["market"],
         expect_slots=["place_type"],
         templates=[
             "我在{place}，回家路上想{errand}，附近哪家值得顺路去？",
         ]),
    dict(scene="location", intent="nearby_hint", complexity_hint="medium", signals=SIGNALS["market"],
         expect_slots=["place_type"],
         templates=[
             "外面下雨，我在{place}，{errand}有必要专门跑一趟吗，还是直接网上买",
         ]),
    dict(scene="location", intent="nearby_hint", complexity_hint="hard", signals=SIGNALS["market"],
         expect_slots=["place_type"],
         templates=[
             "我现在在{place}，要{errand}，还得在十五分钟内到下一站，附近哪家顺路又不绕远？",
         ]),
    dict(scene="location", intent="timing_reminder", complexity_hint="easy", signals=SIGNALS["campus"],
         expect_slots=["place_type", "time_bucket"],
         templates=[
             "我在{place}，这个点食堂还开着吗？",
             "在{place}，接下来一小时适合做什么？",
         ]),
    dict(scene="location", intent="timing_reminder", complexity_hint="easy", signals=SIGNALS["gym"],
         expect_slots=["place_type", "time_bucket"],
         templates=[
             "我在{place}，下一站过去要多久？",
         ]),
    dict(scene="location", intent="timing_reminder", complexity_hint="medium", signals=SIGNALS["market"],
         expect_slots=["place_type", "time_bucket"],
         templates=[
             "我在{place}，这个点{errand}来得及吗？",
             "在{place}，我只有半小时要办完事，能塞进去什么？",
         ]),
    dict(scene="location", intent="timing_reminder", complexity_hint="hard", signals=SIGNALS["market"],
         expect_slots=["place_type", "time_bucket"],
         templates=[
             "我在{place}，要{errand}，还要赶七点半的高铁，帮我排一下时间",
         ]),
    # location 的 intent=none
    dict(scene="location", intent="none", complexity_hint="easy", signals=SIGNALS["hospital"],
         expect_slots=[],
         templates=[
             "我现在定位在哪",
             "这个位置能停车吗",
             "这里信号不好，怎么办",
             "导航到{place}",
         ]),
    dict(scene="location", intent="none", complexity_hint="medium", signals=SIGNALS["market"],
         expect_slots=[],
         templates=[
             "{place}现在营业吗",
             "从这里到{place}有多远",
             "{place}叫什么名字",
         ]),

    # ---------------- 无场景（v3：负样本扩量，~550） ----------------
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "你好",
             "今天天气怎么样",
             "你是谁",
             "中国有几个省",
             "法国首都叫什么",
             "《红楼梦》是谁写的",
             "一小时有多少分钟",
             "苹果公司的 CEO 是谁",
             "CPU 的英文全称是什么",
             "闰年有多少天",
             "水的沸点是多少度",
             "最大的洋是哪个",
             "澳大利亚的国宝是什么",
             "电话是谁发明的",
             "144 的平方根是多少",
             "日本的首都是哪",
             "世界上有多少个时区",
             "跑得最快的陆地动物是什么",
             "《活着》的作者是谁",
             "水的化学式是什么",
             "地球到月球有多远",
             "元素周期表有多少种元素",
             "英国的货币是什么",
         ]),
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "{country}的首都是哪里",
             "{country}在哪个洲",
             "{country}的人口大概多少",
         ]),
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["commute"],
         expect_slots=[],
         templates=[
             "{city}今天天气怎么样",
             "{city}明天出门要带伞吗",
         ]),
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["market"],
         expect_slots=[],
         templates=[
             "{item}现在多少钱一斤",
             "最近{item}涨价了吗",
         ]),
    # 算术负样本：{number}×{number} 连乘，一条 spec 300 条
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "{number}加{num2}等于几",
             "{number}乘{num2}等于几",
             "{number}减{num2}等于几",
         ]),
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "把{device}关掉",
             "定个{minutes}分钟的计时器",
             "放点{music}",
             "屏幕亮度调高一点",
             "手机静音",
             "电量还有多少",
             "帮我拍张照",
             "把 Wi-Fi 关掉",
             "连一下蓝牙",
             "打开相机",
             "这张照片存到相册",
             "移动数据关了",
         ]),
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "早安", "晚安", "拜拜", "想你了", "恭喜",
             "我来了", "好的拜拜", "马上到", "撑住", "别担心",
             "没问题", "可以啊", "成交", "明天见", "回头聊",
             "我看看", "好嘞", "不客气", "你也是", "慢走",
         ]),
    dict(scene="none", intent="none", complexity_hint="medium", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "昨晚做了个噩梦", "明天面试有点紧张", "最近天气真不错",
             "想去旅游但不知道去哪", "我家猫今天特别粘人", "刚下班累死了",
             "睡不着觉怎么办", "想换个工作你觉得呢", "今天吃了好吃的",
             "这个项目真是折磨人", "想学个乐器", "翻到了初中时的照片",
             "最近天天堵车", "养的花开了", "在准备马拉松",
             "想学做饭，有什么建议", "刚搬了新家", "考试通过了",
             "又把钥匙弄丢了", "好期待周末", "手机屏幕摔碎了",
             "空调声音怪怪的", "隔壁狗叫了一夜", "又胖了几斤",
             "周末好想睡到自然醒", "今天健身房人好多", "地铁站门口太堵了",
             "食堂新出的菜一般般", "电脑风扇声音好大", "刚收拾完衣柜",
             "突然想吃火锅",
         ]),
    dict(scene="none", intent="none", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "把「谢谢」翻译成英语",
             "「早上好」用日语怎么说",
             "一斤是多少克",
             "一公里是多少米",
             "一周有几天",
             "μ 这个符号怎么读",
         ]),
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "推荐部电影",
             "推荐本书",
             "今晚看什么好",
             "给点学习建议",
             "帮我想个名字",
             "推荐首歌",
         ]),

    # ================= v3.1：none 负样本扩量（约 +490） =================
    # 追加在 SPECS 末尾：全局序号与 RNG 消耗顺序都不扰动既有 spec，
    # 旧种子 ID/措辞逐条不变 → data/cache 全量可复用（见文件头说明）。
    # 设计约束：话题必须**明确**在 5 场景契约之外（教师不会误判进
    # food/reading/chat/location），避免语义错标混进训练集。
    # 天气细节（city × 4）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "{city}今天气温多少度",
             "{city}今天紫外线强不强",
             "{city}这两小时会下雨吗",
             "{city}今天空气质量怎么样",
         ]),
    # 旅行规划（city × 3）
    dict(scene="none", intent="none", complexity_hint="medium", signals=SIGNALS["commute"],
         expect_slots=[],
         templates=[
             "去{city}玩几天合适",
             "{city}有什么值得去的景点",
             "几月份去{city}最合适",
         ]),
    # 国家知识（country × 3）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "{country}人说什么语言",
             "{country}有什么特产",
             "去{country}旅游要办签证吗",
         ]),
    # 食材知识（item × 2，选购常识，非烹饪）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["market"],
         expect_slots=[],
         templates=[
             "怎么看{item}新不新鲜",
             "{item}的保质期一般是多久",
         ]),
    # 设备常识（device × 2）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "家里的{device}突然不工作了，怎么排查",
             "{device}费电吗",
         ]),
    # 翻译（lang × 4）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["commute"],
         expect_slots=[],
         templates=[
             "「明天见」用{lang}怎么说",
             "把「我十分钟到」翻译成{lang}",
             "「谢谢，你太客气了」用{lang}怎么说",
             "「最近的地铁站怎么走」翻译成{lang}",
         ]),
    # 算术扩量（number × num2 连乘 100 + 平方 10）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "{number}除以{num2}等于几",
             "{number}的平方是多少",
         ]),
    # 体育
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "昨晚 NBA 谁赢了",
             "世界杯决赛比分是多少",
             "国足现在排第几",
             "足球越位怎么算",
             "篮球场上每队几个人",
             "百米世界纪录是多少",
             "奥运会几年一届",
             "罚球算几分",
             "乒乓球怎么算发球违例",
             "F1 车手一年打几站",
         ]),
    # 金融
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "今天 A 股涨了多少",
             "现在黄金多少钱一克",
             "美元兑人民币汇率是多少",
             "比特币现在多少钱",
             "定期存款和活期哪个划算",
             "现在房贷利率多少",
             "K 线怎么看",
             "基金分红是什么意思",
             "房贷利息怎么算",
             "现在换美元划算吗",
         ]),
    # 健康
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "头疼发烧吃什么药",
             "轻微胃疼怎么办",
             "鼻塞怎么缓解",
             "低血糖是什么症状",
             "布洛芬和感冒药能一起吃吗",
             "BMI 28 算胖吗",
             "被蚊子咬了特别痒怎么处理",
             "血压怎么量才准",
             "打嗝停不下来怎么办",
             "熬夜后怎么补救",
         ]),
    # 编程
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "Python 怎么读一个文件",
             "这段 SQL 为什么查不出数据",
             "HTTP 和 HTTPS 有什么区别",
             "JPEG 怎么批量转成 PNG",
             "二分查找的时间复杂度是多少",
             "Linux 怎么看磁盘占用",
             "网页报 404 一般是什么原因",
             "Redis 和 MySQL 的区别",
             "正则怎么校验手机号",
             "Docker 容器是什么",
         ]),
    # 科学常识
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "天空为什么是蓝色的",
             "日食和月食有什么区别",
             "人体有多少块骨头",
             "光速是多少",
             "铁为什么会生锈",
             "5G 比 4G 快在哪",
             "黑洞是什么",
             "阳光到地球要多久",
             "为什么冬天窗户上会有水珠",
             "指南针为什么指南北",
         ]),
    # 生活窍门
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["market"],
         expect_slots=[],
         templates=[
             "衣服上的油渍怎么去掉",
             "床垫怎么选",
             "大蒜怎么长期保存",
             "冰箱有异味怎么处理",
             "车冬天冻住了怎么办",
             "鱼刺卡住了怎么办",
             "手机屏幕上的笔印怎么擦掉",
             "保温杯的水垢怎么除",
             "行李箱拉链卡住了怎么办",
             "白鞋怎么洗得干净",
         ]),
    # 购物
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["market"],
         expect_slots=[],
         templates=[
             "双十一怎么买手机最划算",
             "800 块的跑鞋值不值",
             "空气炸锅哪个牌子靠谱",
             "学生买笔记本看什么参数",
             "车险要不要加全险",
             "怎么分辨真皮和人革",
             "空调一级能效省电多少",
             "网上买衣服买什么码合适",
             "网购七天无理由退货怎么操作",
             "防晒霜指数多少够用",
         ]),
    # 游戏娱乐（genre × 1 + 固定 6）
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "推荐个好玩的{genre}",
             "有什么两个人能一起玩的游戏",
             "最近有什么好看的动漫",
             "有什么轻小说推荐",
             "演唱会值不值得去",
             "这部剧哪一季最经典",
         ]),
    # 创作
    dict(scene="none", intent="none", complexity_hint="medium", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "帮我写一首关于月亮的诗",
             "帮宝宝取个名字",
             "写一段三行的生日祝福",
             "给我的小说编个结局",
             "写首打油诗调侃室友总迟到",
             "写一首关于春天的五言绝句",
         ]),
    # 宠物
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "猫一直吐黄水正常吗",
             "狗狗多久打一次狂犬疫苗",
             "小猫为什么总蹭我的腿",
             "狗吃了袜子怎么办",
             "猫咪多久该洗澡",
             "仓鼠为什么老咬笼子",
         ]),
    # 学习
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "高考英语单词怎么背效率高",
             "公务员考试行测难不难",
             "注册会计师怎么备考",
             "元素周期表怎么记最快",
             "英语四级怎么准备",
             "考研数学先学什么",
         ]),
    # 数学趣题
    dict(scene="none", intent="none", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "鸡兔同笼，35 个头 94 只脚，各几只",
             "5 台机器 5 分钟做 5 个零件，100 台机器做 100 个要几分钟",
             "1, 1, 2, 3, 5, 8 下一个数是多少",
             "半径 5 的圆面积是多少",
             "一个数除以 7 余 3，除以 5 余 2，最小是多少",
         ]),
    # 成语
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=[],
         templates=[
             "「画蛇添足」怎么用在句子里",
             "「月晕而风，础润而雨」什么意思",
             "「百年不遇」是褒义还是贬义",
             "「一丝不苟」的反义词是什么",
             "「三七开」的三是指什么",
             "「赋能」这个词是褒义还是贬义",
         ]),
]


def generate_seeds(limit: int | None = None, seed: int = 20260921) -> list[Seed]:
    """把种子模板展开成具体的用户输入。

    limit 控制总量。两步采样：
    1. 组间轮转（每个 spec 按序各取一条）——保证每个 spec 都有代表，
       困难样本组不会像全局随机截断那样被整组丢掉
       （第一轮实测踩过的坑：难度分布永远填不满 hard 档）。
    2. 达到 limit 时**按场景分层**（v3）——轮转只保证 spec 代表，
       不保证场景占比（实测 12000 抽取里 food 会从池占比 34.6%
       掉到 12.5%），所以按池内场景占比用最大余数法分配 limit。

    v3 新增：spec 级 `cap` —— 单模板笛卡尔积超限后先确定性打散再截断，
    防止三词表连乘组（如 {veg}×{protein}×{staple}）独占池子。

    展开是确定性的（固定随机种子），任何人重跑都得到同一份数据集。
    """
    rng = random.Random(seed)
    groups: list[list[Seed]] = []
    counter = 0

    for spec in SPECS:
        group: list[Seed] = []
        for template in spec["templates"]:
            # 占位符按**单个模板**取，而不是取整个 spec 的并集 ——
            # 否则会把不相干的词表也做笛卡尔积，导致样本量爆炸且分布严重失衡
            names = _placeholders([template])
            pools = [VOCAB[name] for name in names]
            combos = list(itertools.product(*pools)) if pools else [()]

            for combo in combos:
                fields = dict(zip(names, combo))
                utterance = template.format(**fields) if fields else template
                counter += 1
                group.append(
                    Seed(
                        id=f"{spec['scene']}_{counter:04d}",
                        scene=spec["scene"],
                        intent=spec["intent"],
                        complexity_hint=spec["complexity_hint"],
                        signals=spec["signals"],
                        utterance=utterance,
                        expect_slots=list(spec["expect_slots"]),
                    )
                )
        if group:
            # v3：cap 在 **spec 层** 生效（组内打散后截断），
            # 防止三词表连乘 spec 独占池子
            rng.shuffle(group)
            cap = spec.get("cap")
            if cap is not None and len(group) > cap:
                group = group[:cap]
            groups.append(group)

    # 组内已打散，组间轮转取样，保证每个 spec 都有代表
    interleaved: list[Seed] = []
    cursors = [0] * len(groups)
    while len(interleaved) < sum(len(g) for g in groups):
        progressed = False
        for index, group in enumerate(groups):
            if cursors[index] < len(group):
                interleaved.append(group[cursors[index]])
                cursors[index] += 1
                progressed = True
        if not progressed:
            break

    if limit is not None and limit < len(interleaved):
        # v3：场景分层抽样。组间轮转只保证"每个 spec 有代表"，
        # 达到 limit 时大池 spec 被系统性截断（实测 12000 抽取里
        # food 从池占比 34.6% 掉到 12.5%）。这里按池内场景占比用
        # 最大余数法分配 limit，场景内保持轮转顺序。
        from collections import Counter, defaultdict

        by_scene: dict[str, list[Seed]] = defaultdict(list)
        for s in interleaved:
            by_scene[s.scene].append(s)
        total = len(interleaved)
        exact = {sc: limit * len(items) / total for sc, items in by_scene.items()}
        alloc = {sc: int(v) for sc, v in exact.items()}
        remainder = limit - sum(alloc.values())
        for sc, _ in sorted(exact.items(), key=lambda kv: kv[1] - int(kv[1]), reverse=True):
            if remainder <= 0:
                break
            alloc[sc] += 1
            remainder -= 1
        interleaved = [s for sc in by_scene for s in by_scene[sc][: alloc[sc]]]
    return interleaved


def _placeholders(templates: list[str]) -> list[str]:
    import re

    found: list[str] = []
    for template in templates:
        for name in re.findall(r"\{(\w+)\}", template):
            if name not in found and name in VOCAB:
                found.append(name)
    return found


def coverage_cells(seeds: list[Seed]) -> dict[str, list[str]]:
    """把种子映射到覆盖网格的格子：scene × intent × complexity_hint。"""
    cells: dict[str, list[str]] = {}
    for item in seeds:
        key = f"{item.scene}|{item.intent}|{item.complexity_hint}"
        cells.setdefault(key, []).append(item.id)
    return cells


if __name__ == "__main__":
    import re
    from collections import Counter

    # 安全网：模板里出现的占位符必须都在词表里，否则 format 会 KeyError
    missing = {
        name
        for spec in SPECS
        for t in spec["templates"]
        for name in re.findall(r"\{(\w+)\}", t)
        if name not in VOCAB
    }
    if missing:
        raise SystemExit(f"占位符不在词表：{sorted(missing)}")

    all_seeds = generate_seeds()
    cells = coverage_cells(all_seeds)
    scene_counts = Counter(s.scene for s in all_seeds)
    print(f"种子总数：{len(all_seeds)}")
    print(
        "场景构成："
        + "  ".join(
            f"{k}={v}（{v / len(all_seeds) * 100:.1f}%）"
            for k, v in scene_counts.most_common()
        )
    )
    print(f"覆盖格子：{len(cells)}")
    for key, ids in sorted(cells.items()):
        print(f"  {key:<40} {len(ids):>3} 条")
