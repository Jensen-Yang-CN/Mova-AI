"""人工复核前的定向探针草案；这些标签不是教师生成的 gold。"""

from __future__ import annotations

from typing import Any

# 与正式评测集的 8 个重复信号不同，这里交叉使用新的时间、应用和位置。
SIGNALS = (
    "时间：07:13 | 前台 App：日历 | 位置：公园入口 | 运动：步行 | 屏幕：亮",
    "时间：09:27 | 前台 App：邮件 | 位置：写字楼 | 运动：静止 | 屏幕：亮",
    "时间：11:46 | 前台 App：地图 | 位置：社区广场 | 运动：步行 | 屏幕：亮",
    "时间：13:08 | 前台 App：相册 | 位置：家中 | 运动：静止 | 屏幕：亮",
    "时间：15:39 | 前台 App：文档 | 位置：图书馆 | 运动：静止 | 屏幕：亮",
    "时间：18:22 | 前台 App：消息 | 位置：办公园区 | 运动：步行 | 屏幕：亮",
    "时间：20:14 | 前台 App：桌面 | 位置：公交车上 | 运动：移动中 | 屏幕：亮",
    "时间：06:52 | 前台 App：浏览器 | 位置：家中 | 运动：静止 | 屏幕：亮",
    "时间：10:34 | 前台 App：日历 | 位置：医院门口 | 运动：步行 | 屏幕：亮",
    "时间：12:17 | 前台 App：消息 | 位置：学校操场 | 运动：步行 | 屏幕：亮",
    "时间：14:58 | 前台 App：PDF阅读器 | 位置：咖啡馆 | 运动：静止 | 屏幕：亮",
    "时间：16:41 | 前台 App：地图 | 位置：住宅小区 | 运动：步行 | 屏幕：亮",
    "时间：19:33 | 前台 App：备忘录 | 位置：候车室 | 运动：静止 | 屏幕：亮",
    "时间：21:06 | 前台 App：文档 | 位置：家中 | 运动：静止 | 屏幕：亮",
    "时间：08:48 | 前台 App：聊天 | 位置：教学楼 | 运动：静止 | 屏幕：亮",
    "时间：17:19 | 前台 App：相机 | 位置：街边 | 运动：步行 | 屏幕：亮",
    "时间：22:31 | 前台 App：桌面 | 位置：宿舍 | 运动：静止 | 屏幕：亮",
    "时间：11:02 | 前台 App：邮件 | 位置：共享办公区 | 运动：静止 | 屏幕：亮",
    "时间：15:12 | 前台 App：地图 | 位置：公园 | 运动：步行 | 屏幕：亮",
    "时间：18:54 | 前台 App：消息 | 位置：家中 | 运动：静止 | 屏幕：亮",
)


def build_cases() -> list[dict[str, Any]]:
    """按问题族构造 150 条草案；人工审核后才能转成正式测试集。"""
    rows: list[dict[str, Any]] = []

    def add(
        family: str,
        utterance: str,
        scene: str,
        intent: str,
        *,
        slots: dict[str, Any] | None = None,
        need_cloud: bool | None = None,
        checks: tuple[str, ...] = ("scene", "intent"),
    ) -> None:
        number = len(rows) + 1
        rows.append(
            {
                "id": f"indep_{number:04d}",
                "family": family,
                "signals": SIGNALS[((number - 1) * 7) % len(SIGNALS)],
                "utterance": utterance,
                "scene": scene,
                "intent": intent,
                "slots": slots or {},
                "need_cloud": need_cloud,
                "checks": list(checks),
            }
        )

    # 食物关键词不等于烹饪意图：关注是否错误路由到 food。
    for utterance in (
        "牛油果表皮有黑点，还算新鲜吗？",
        "这盒蓝莓今天卖得比昨天贵吗？",
        "切开的哈密瓜在冰箱里能放几天？",
        "买草莓时怎样看有没有被压坏？",
        "盒装牛奶过了标注日期一天还能喝吗？",
        "菜市场的西红柿现在什么价？",
        "蘑菇表面发黏是不是已经坏了？",
        "生姜放在厨房多久会干掉？",
        "榴莲闻起来不香，是不是没熟？",
        "超市这袋大米有没有促销活动？",
        "绿叶菜叶缘发黄还能留到明天吗？",
        "我买的鸡蛋壳上有裂纹，要不要扔掉？",
        "香蕉还很青，怎么判断它什么时候熟？",
        "这瓶酸奶开封后冷藏期限是多少？",
        "橙子外皮有点软，是运输压伤了吗？",
    ):
        add("food_boundary", utterance, "none", "none")

    # 用户自述的当前位置覆盖环境信号；未列入枚举的地点应归“其他”。
    other_places = (
        ("奶茶店", "我在奶茶店等人，二十分钟后提醒我去拿包裹。", "timing_reminder"),
        ("影城", "刚到影城，散场后附近哪里能顺路买水？", "nearby_hint"),
        ("邮局", "我在邮局排队，半小时后提醒我打电话。", "timing_reminder"),
        ("牙科诊所", "现在在牙科诊所，结束后附近有地方吃点东西吗？", "nearby_hint"),
        ("图书馆", "我正在图书馆，离开前提醒我还书。", "timing_reminder"),
        ("停车场", "车停在停车场，附近顺路有洗车的地方吗？", "nearby_hint"),
        ("眼镜店", "我在眼镜店配镜，四十分钟后提醒我回来取。", "timing_reminder"),
        ("理发店", "刚进理发店，附近能顺便取快递吗？", "nearby_hint"),
        ("博物馆", "我在博物馆，闭馆前半小时提醒我去出口。", "timing_reminder"),
        ("宠物医院", "现在在宠物医院，附近有卖宠物用品的吗？", "nearby_hint"),
        ("银行网点", "我在银行网点等号，十五分钟后提醒我看叫号。", "timing_reminder"),
        ("药店", "我在药店，附近有没有顺路的打印店？", "nearby_hint"),
        ("花店", "我在花店挑花，十分钟后提醒我去接人。", "timing_reminder"),
        ("健身房", "我正在健身房，附近能顺路买一瓶水吗？", "nearby_hint"),
        ("社区服务中心", "我在社区服务中心办事，三点前提醒我取号。", "timing_reminder"),
        ("快递驿站", "刚到快递驿站，附近还有什么可以顺路办的？", "nearby_hint"),
        ("洗衣店", "我在洗衣店等衣服，二十分钟后提醒我回来。", "timing_reminder"),
        ("公园", "我在公园散步，附近哪里能顺路买饮料？", "nearby_hint"),
        ("派出所", "现在在派出所，办完后附近能复印材料吗？", "nearby_hint"),
        ("游泳馆", "我在游泳馆，六点半提醒我出发回家。", "timing_reminder"),
    )
    for _place, utterance, intent in other_places:
        add("location_other", utterance, "location", intent, slots={"place_type": "其他"},
            checks=("scene", "intent", "slots.place_type"))

    near_enum = (
        ("我在火锅店等位，附近还有什么顺路可办？", "餐厅", "nearby_hint"),
        ("现在在面馆，十分钟后提醒我回公司。", "餐厅", "timing_reminder"),
        ("坐在食堂里，吃完后附近能买文具吗？", "餐厅", "nearby_hint"),
        ("我到地铁站了，五分钟后提醒我检票。", "交通枢纽", "timing_reminder"),
        ("人在高铁候车厅，附近能顺路买充电线吗？", "交通枢纽", "nearby_hint"),
        ("我在机场航站楼，登机前半小时提醒我。", "交通枢纽", "timing_reminder"),
        ("现在站在校门口，附近有可顺路办的事吗？", "校园", "nearby_hint"),
        ("我在教学楼，下午四点提醒我去取书。", "校园", "timing_reminder"),
        ("刚回宿舍楼，附近哪里能买到笔记本？", "校园", "nearby_hint"),
        ("我在生鲜超市挑菜，十分钟后提醒我结账。", "超市", "timing_reminder"),
        ("人在连锁超市，附近还有什么能顺路买的？", "超市", "nearby_hint"),
        ("我在大型超市，半小时后提醒我去停车区。", "超市", "timing_reminder"),
        ("到了购物中心，附近有顺路的服务台吗？", "商场", "nearby_hint"),
        ("我在百货商场，二十分钟后提醒我去出口。", "商场", "timing_reminder"),
        ("人在商业综合体，附近还能顺便逛什么？", "商场", "nearby_hint"),
    )
    for utterance, place_type, intent in near_enum:
        add("location_near_enum", utterance, "location", intent, slots={"place_type": place_type},
            checks=("scene", "intent", "slots.place_type"))

    # 目的地即使更显眼，也不能覆盖用户明确自述的当前位置。
    destination_cases = (
        ("我现在在校园，去机场要几点出发才来得及？", "校园", "timing_reminder"),
        ("我人在商场，附近顺路去药店方便吗？", "商场", "nearby_hint"),
        ("目前在超市，去火车站前提醒我先结账。", "超市", "timing_reminder"),
        ("我在餐厅吃饭，附近能顺道去银行吗？", "餐厅", "nearby_hint"),
        ("正在地铁站，去图书馆前十分钟提醒我。", "交通枢纽", "timing_reminder"),
        ("我在校园门口，附近有没有去商场顺路的地方？", "校园", "nearby_hint"),
        ("人在购物中心，去学校之前提醒我买水。", "商场", "timing_reminder"),
        ("我现在在超市，去医院路上有顺路的打印店吗？", "超市", "nearby_hint"),
        ("正在面馆，去机场前半小时提醒我出门。", "餐厅", "timing_reminder"),
        ("我在候车厅，附近有没有去邮局顺路的路线？", "交通枢纽", "nearby_hint"),
        ("现在在教学楼，去餐厅前提醒我把书带上。", "校园", "timing_reminder"),
        ("我在商场，去地铁站路上能顺便买药吗？", "商场", "nearby_hint"),
        ("人在生鲜超市，去公园前提醒我拿伞。", "超市", "timing_reminder"),
        ("我在食堂，附近去快递驿站会绕路吗？", "餐厅", "nearby_hint"),
        ("现在在机场航站楼，去酒店前提醒我取行李。", "交通枢纽", "timing_reminder"),
    )
    for utterance, place_type, intent in destination_cases:
        add("location_destination", utterance, "location", intent, slots={"place_type": place_type},
            checks=("scene", "intent", "slots.place_type"))

    relations = {
        "上级": (
            "领导突然让我周末值班，我想推迟，帮我回一句。",
            "主管问我项目什么时候交，帮我写句礼貌回复。",
            "导师催我改论文，我想先解释进度，该怎么回？",
            "部门经理要我提前汇报，帮我回复说需要一天准备。",
            "直属负责人邀请我加班，帮我委婉拒绝。",
            "老板问我能否临时出差，帮我回一句说明安排冲突。",
        ),
        "同事": (
            "同事想和我换班，我今天不方便，帮我回一句。",
            "下属说任务可能延期，我该怎么回复比较稳妥？",
            "室友又把东西放在我桌上，帮我写一句不伤和气的话。",
            "同学想借我的笔记，我想明天再给，怎么回复？",
            "邻居问我能否帮忙收快递，帮我婉拒。",
            "项目搭档要改分工，我希望先讨论，帮我回一句。",
        ),
        "陌生人": (
            "客户反复催报价，我需要先确认，帮我回一句。",
            "快递员说包裹放门口了，我想让他改放驿站，怎么回复？",
            "银行客户经理问我要不要办卡，帮我礼貌拒绝。",
            "外卖员找不到门牌号，帮我回一句说明入口。",
            "陌生网友一直要我加好友，帮我写句拒绝的话。",
            "维修师傅说明天才能来，我想确认时间，怎么回？",
        ),
        "家人": (
            "妈妈问我周末回不回家，帮我回一句说还没确定。",
            "姐姐约我吃饭，但我已经有安排，怎么回复？",
            "舅舅问我工作顺不顺利，帮我回得自然一点。",
            "父亲提醒我早睡，我想轻松地回一句。",
            "表妹想让我帮她看作业，我得晚点才有空，怎么回？",
            "哥哥邀请我旅行，我想先问清日期，帮我回复。",
        ),
        "朋友": (
            "好友喊我今晚打球，我想改到周末，帮我回一句。",
            "发小问我要不要聚餐，我想先看看时间，怎么回复？",
            "闺蜜说她今天很难过，帮我回一句安慰的话。",
            "老朋友想借书，我下周才能寄，怎么回？",
            "多年好友叫我一起看电影，我想婉拒，帮我写一句。",
            "关系很好的朋友问我近况，帮我自然地回一句。",
        ),
    }
    for relation, utterances in relations.items():
        for utterance in utterances:
            add("chat_relation", utterance, "chat", "reply_suggest", slots={"relation": relation},
                checks=("scene", "intent", "slots.relation"))

    for utterance in (
        "最近想减脂，用鸡蛋、菠菜和豆腐凑三菜一汤怎么搭配热量？",
        "我在控糖，晚饭想吃米饭和南瓜，怎样调整份量？",
        "有高血压的人做一顿家常菜，盐要怎么控制？",
        "想把一周午餐控制在合理热量，手里的鸡胸肉怎么安排？",
        "我准备增肌，晚饭只有牛奶、土豆和鸡蛋，怎么配？",
        "减重期间做三菜一汤，怎样避免油放太多？",
        "乳糖不耐受又想补蛋白，早餐怎么组合比较合适？",
        "外婆需要清淡饮食，豆腐和青菜怎么搭一餐？",
        "我在控制总热量，夜宵想吃水果和酸奶怎么选？",
        "运动后想补充碳水和蛋白质，现有食材如何搭配？",
    ):
        add("food_diet", utterance, "food", "diet_advice", need_cloud=True)

    reading_cases = (
        ("这份 PDF 我想快速过一遍，先给我一段整体梗概。", "summarize"),
        ("我只想知道这篇文章大概讲了什么，不用逐条列。", "summarize"),
        ("快速浏览一下报告，帮我概括主要内容。", "summarize"),
        ("这页资料先用几句话说清楚核心意思。", "summarize"),
        ("先把这份 PDF 的整体脉络讲给我听。", "summarize"),
        ("开会前快速过一下这份 PDF，列出必须讲的三点。", "key_points"),
        ("我等下要汇报，帮我从文档里提炼关键结论。", "key_points"),
        ("准备给同事转述，先列出这篇文章最重要的几点。", "key_points"),
        ("这份材料里哪些事项需要我今天跟进？列个清单。", "key_points"),
        ("会前只有一分钟，把报告需要讨论的要点挑出来。", "key_points"),
    )
    for utterance, intent in reading_cases:
        add("reading_boundary", utterance, "reading", intent, need_cloud=True)

    for utterance in (
        "把手机手电筒打开。", "现在把屏幕亮度调低一点。", "帮我把媒体音量静音。",
        "给我设一个明早六点四十的闹钟。", "把蓝牙先关掉。", "启动手机飞行模式。",
        "打开相机的前置镜头。", "帮我切换到深色模式。", "把手机铃声调到最大。",
        "给这台手机截个屏。",
    ):
        add("device_command", utterance, "none", "none")

    for utterance in (
        "把刚才下载的文件移到文档文件夹。", "把这个 PDF 直接转发给小王。",
        "把照片备份到我的网盘。", "帮我打印这份会议纪要。",
        "删除下载目录里的旧压缩包。", "把桌面上的两个表格合并成一个文件。",
        "将录音文件重命名为今天的日期。", "把这份合同保存到共享盘。",
        "从相册里找出昨天的截图并发邮件。", "把刚拍的照片导出成 PDF。",
    ):
        add("file_command", utterance, "none", "none")

    # 最后一组跨场景使用新语境，检查模型是否只记住固定信号组合。
    context_cases = (
        ("冰箱里有茄子和鸡蛋，今晚想做一顿热饭，给我个做法。", "food", "recipe_lookup", {}),
        ("手头只有虾仁和西葫芦，怎么炒成一盘菜？", "food", "recipe_lookup", {}),
        ("豆腐和香菇能不能放一起吃？", "food", "ingredient_check", {}),
        ("我想用燕麦做早饭，怎么搭配比较省事？", "food", "recipe_lookup", {}),
        ("这篇论文术语密集，帮我判断读起来难不难。", "reading", "difficulty_judge", {}),
        ("这段政策说明对普通人来说理解难度高吗？", "reading", "difficulty_judge", {}),
        ("把这张截图的文字内容概括一下。", "reading", "summarize", {}),
        ("这份阅读材料先说个大意即可。", "reading", "summarize", {}),
        ("我写的‘你必须今天给我’，语气能调得礼貌一点吗？", "chat", "tone_adjust", {}),
        ("‘收到，随便吧’这句回复听着太冷，帮我改柔和些。", "chat", "rewrite", {}),
        ("这句话显得生硬，帮我改成更自然的说法。", "chat", "rewrite", {}),
        ("给朋友的消息想轻松一点，帮我调整语气。", "chat", "tone_adjust", {}),
        ("我在商场，十分钟后提醒我去地下停车区。", "location", "timing_reminder", {"place_type": "商场"}),
        ("现在在校园，附近哪里能顺路买到笔？", "location", "nearby_hint", {"place_type": "校园"}),
        ("我在地铁站，下一趟车前提醒我取票。", "location", "timing_reminder", {"place_type": "交通枢纽"}),
    )
    for utterance, scene, intent, slots in context_cases:
        checks = ("scene", "intent", "slots.place_type") if slots else ("scene", "intent")
        add("new_context", utterance, scene, intent, slots=slots, checks=checks)

    assert len(rows) == 150, f"定向探针数量异常：{len(rows)}"
    assert len({r["utterance"] for r in rows}) == len(rows), "存在重复话语"
    return rows
