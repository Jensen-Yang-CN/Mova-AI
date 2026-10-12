"""城市初见的数据与高德适配。

定位是确定性任务，不调用语言模型猜城市。真实 GPS 先转为高德坐标，
模拟点位显式标注 GCJ-02。密钥只保存在服务端，异常不包含带 Key 的请求 URL。
城市介绍优先复用已有资料；其他城市自动由云端整理候选，高德检索地点。
明确区分 AI 城市知识建议与地图返回的实际地点，不把餐厅存在当作菜肴起源证明。
"""
from __future__ import annotations

import requests
import json
import re
import time
import threading
from pathlib import Path
from datetime import datetime, timezone, timedelta
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlencode
from typing import Callable
from jsonx import extract_json


class CityServiceError(RuntimeError):
    pass


_amap_rate_lock = threading.Lock()
_amap_next_request = 0.0


def _amap(path: str, key: str, **params) -> dict:
    global _amap_next_request
    if not key:
        raise CityServiceError("地图服务未配置，请在服务端填写 MOVA_AMAP_KEY")
    # 同一个 Web Key 共享请求节奏；城市推荐不能瞬间并发八个地图请求。
    with _amap_rate_lock:
        pause = _amap_next_request - time.monotonic()
        if pause > 0:
            time.sleep(pause)
        _amap_next_request = time.monotonic() + 0.6
    try:
        result = requests.get(
            "https://restapi.amap.com/v3/" + path,
            params={"key": key, **params}, timeout=(5, 10),
        )
        result.raise_for_status()
        body = result.json()
    except (requests.RequestException, ValueError):
        # requests 的异常文本可能包含 URL 和密钥，不能直接向客户端转发。
        raise CityServiceError("地图服务暂时无法连接，请稍后重试") from None
    if body.get("status") != "1":
        raise CityServiceError("地图服务拒绝请求，错误码 " + str(body.get("infocode", "unknown")))
    return body


def _text(value) -> str:
    # 高德部分缺失字段是 [] 而不是空字符串。
    return value.strip() if isinstance(value, str) else ""


def resolve_city(key: str, longitude: float, latitude: float, coordsys: str) -> dict:
    location = f"{longitude:.6f},{latitude:.6f}"
    if coordsys == "wgs84":
        converted = _amap("assistant/coordinate/convert", key,
                          locations=location, coordsys="gps")
        location = converted.get("locations", "")
        if not location:
            raise CityServiceError("地图坐标转换失败")
    body = _amap("geocode/regeo", key, location=location, extensions="base")
    address = body.get("regeocode", {}).get("addressComponent", {})
    province = _text(address.get("province"))
    city = _text(address.get("city"))
    adcode = _text(address.get("adcode"))
    if len(adcode) != 6 or not adcode.isdigit():
        raise CityServiceError("没有识别到国内城市，请检查经度与纬度数值，或改用城市陆地点位")
    if province in {"北京市", "天津市", "上海市", "重庆市"}:
        city, code = province, adcode[:2] + "0000"
    elif city:
        code = adcode[:4] + "00"
    else:
        # 省直辖县级行政区不能随意合并成一座虚构城市。
        city, code = _text(address.get("district")), adcode
    if not city:
        raise CityServiceError("城市信息为空，请等待下一次有效定位")
    return {"city_code": code, "city_name": city, "province": province,
            "district": _text(address.get("district")), "resolver": "amap"}


GUIDES = {
    "420100": {
        "city_name": "武汉市", "subtitle": "江湖之间，慢慢认识这座城",
        "foods": ["热干面", "莲藕汤"], "sights": ["黄鹤楼", "东湖绿道"],
        "sources": [{"title": "武汉市政府 · 武汉美食与周边文旅资源",
                     "url": "https://www.wuhan.gov.cn/zjwh/whly/202511/t20251112_2676644.shtml"}],
    },
    "430600": {
        "city_name": "岳阳市", "subtitle": "在洞庭湖畔，给旅途一点余地",
        "foods": ["岳阳米粉", "火焙鱼"], "sights": ["岳阳楼", "君山岛"],
        "sources": [{"title": "岳阳市政府 · 旅游指南",
                     "url": "https://www.yueyang.gov.cn/mobile/23215/23219/default.htm"},
                    {"title": "岳阳市政府 · 岳阳楼君山岛旅游区",
                     "url": "https://yueyang.gov.cn/yyly/lyzt/5ajjq/content_202219.html"}],
    },
}


def _base_guide(city_code: str, city_name: str) -> dict:
    content = GUIDES.get(city_code)
    return {"city_code": city_code, "city_name": content["city_name"] if content else city_name,
            "subtitle": content["subtitle"] if content else "一座新城市，一份新的照应",
            "foods": content["foods"] if content else [],
            "sights": content["sights"] if content else [],
            "sources": content["sources"] if content else [],
            "updated_on": "2026-10-12", "curated": content is not None, "version": 3,
            "food_kind": "dishes", "generation": "curated" if content else "unavailable",
            "notice": "仅介绍城市特色，不提供实时营业、票价或路线信息。" if content else
                      "城市推荐暂时未加载，请稍后重试。"}


CACHE_TTL = 24 * 60 * 60
CACHE_DIR = Path(__file__).resolve().parents[1] / "data" / "city_guides"
_locks_guard = threading.Lock()
_city_locks: dict[str, threading.Lock] = {}


def _names(value) -> list[str]:
    """输出只保留少量可展示名称，丢弃长文、重复项及格式不合法的值。"""
    if not isinstance(value, list):
        return []
    result = []
    for item in value:
        if isinstance(item, str) and 1 <= len(item.strip()) <= 24 and item.strip() not in result:
            result.append(item.strip())
    return result[:3]


def _search(key: str, city_code: str, keyword: str = "", category: str = "") -> list[dict]:
    params = dict(city=city_code, citylimit="true", offset=6, page=1, extensions="all")
    # 关键词搜索时不同时指定大类，避免类型排序压过具体地标关键词。
    if keyword:
        params["keywords"] = keyword
    else:
        params["types"] = category
    body = _amap("place/text", key, **params)
    pois = []
    for poi in body.get("pois", []):
        code = _text(poi.get("adcode"))
        if city_code in {"110000", "120000", "310000", "500000"}:
            matches = code[:2] == city_code[:2]  # 直辖市区县编码的前四位并不相同。
        elif city_code[2:4] == "90":
            matches = code == city_code
        else:
            matches = code[:4] == city_code[:4]
        name = _text(poi.get("name"))
        if matches and name:
            pois.append({"name": name, "address": _text(poi.get("address")),
                         "type": _text(poi.get("type")), "typecode": _text(poi.get("typecode")),
                         "id": _text(poi.get("id"))})
    return pois


def _safe_search(key: str, code: str, keyword: str = "", category: str = "") -> list[dict]:
    try:
        return _search(key, code, keyword, category)
    except CityServiceError:
        return []


def _map_url(code: str, keyword: str) -> str:
    return "https://uri.amap.com/search?" + urlencode({"keyword": keyword, "city": code, "callnative": 0})


def _generate_guide(code: str, name: str, key: str, generate: Callable[[str], str] | None) -> dict:
    """所有已识别城市走同一条流程，无城市白名单；输入模型的只有城市名与编码。"""
    candidate = {}
    if generate:
        try:
            candidate = extract_json(generate(f"""为来到中国{name}（行政编码 {code}）的旅客提供简短城市介绍。
只列你有把握的本地代表性菜肴/小吃，以及该市真实存在的著名景点；不要餐厅广告。
最多各 3 个名称。不要编造营业时间、票价、排名或用户偏好。不确定的名称不列。
只输出 JSON：{{"foods":["菜肴名"],"sights":["景点名"],"subtitle":"不超过20字的温暖欢迎语"}}"""))
        except Exception:
            # 语言模型失败仍尝试地图，且不把上游异常、URL 或密钥放进城市资料。
            candidate = {}
    if not isinstance(candidate, dict):
        candidate = {}
    foods, sights = _names(candidate.get("foods")), _names(candidate.get("sights"))
    lookups = {}
    # 先核验地标。菜肴知识与餐厅是否存在是不同问题，不用逐道菜制造多余请求。
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {("sight", term): pool.submit(_safe_search, key, code, term) for term in sights}
        for target, future in jobs.items():
            lookups[target] = future.result()
    food_places, sight_places = [], []

    # 景点展示采用本市地图检索中的相关实际名称，过滤完全无关的搜索结果。
    verified_sights = []
    for term in sights:
        core = term.removeprefix(name.removesuffix("市"))
        match = next((p for p in lookups.get(("sight", term), [])
                      if (core in p["name"] or p["name"] in term)
                      and p["typecode"][:2] in {"11", "14"}), None)
        if match and match["name"] not in verified_sights:
            verified_sights.append(match["name"])
    if not verified_sights:
        sight_places = _safe_search(key, code, "", "110000")
        verified_sights = [p["name"] for p in sight_places[:3]]
    if not foods:
        food_places = _safe_search(key, code, "", "050000")

    guide = _base_guide(code, name)
    guide.update(foods=foods or [p["name"] for p in food_places[:3]], sights=verified_sights[:3],
                 curated=False, food_kind="dishes" if foods else "places",
                 generation="ai_map" if foods else "map", version=3,
                 updated_on=datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d"))
    subtitle = candidate.get("subtitle")
    if isinstance(subtitle, str) and 0 < len(subtitle.strip()) <= 30:
        guide["subtitle"] = subtitle.strip()
    guide["sources"] = []
    if foods:
        guide["sources"].append({"title": "美食建议 · AI 城市知识整理", "url": ""})
    if verified_sights:
        guide["sources"].append({"title": "景点地点 · 高德地图检索", "url": _map_url(code, "景点")})
    if food_places:
        guide["sources"].append({"title": "餐饮地点 · 高德地图检索", "url": _map_url(code, "美食")})
    guide["notice"] = "城市推荐供参考；营业、票价与具体地址请在地图中确认。"
    if not guide["foods"] or not guide["sights"]:
        guide["notice"] = "部分推荐暂未获取，稍后可刷新；已有内容仍可查看。"
    if not guide["foods"] and not guide["sights"]:
        raise CityServiceError("城市推荐暂时获取失败，请稍后刷新重试")
    return guide


def city_guide(city_code: str, city_name: str, key: str = "",
               generate: Callable[[str], str] | None = None) -> dict:
    if not re.fullmatch(r"\d{6}", city_code) or not city_name or len(city_name) > 40:
        raise CityServiceError("城市标识不合法")
    if city_code in GUIDES:
        return _base_guide(city_code, city_name)
    if not key and generate is None:
        return _base_guide(city_code, city_name)
    with _locks_guard:
        lock = _city_locks.setdefault(city_code, threading.Lock())
    with lock:
        path = CACHE_DIR / (city_code + ".json")
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            if cached.get("version") == 3 and time.time() - path.stat().st_mtime < CACHE_TTL:
                return cached
        except (OSError, ValueError):
            pass
        guide = _generate_guide(city_code, city_name, key, generate)
        # 只缓存完整结果，避免一次失败导致未来一天都拿到空内容。
        if guide["foods"] and guide["sights"]:
            try:
                CACHE_DIR.mkdir(parents=True, exist_ok=True)
                temporary = path.with_suffix(".part")
                temporary.write_text(json.dumps(guide, ensure_ascii=False), encoding="utf-8")
                temporary.replace(path)
            except OSError:
                pass  # 缓存不可写不能阻止用户收到推荐。
        return guide
