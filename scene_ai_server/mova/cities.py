"""城市初见的数据与高德适配。

定位是确定性任务，不调用语言模型猜城市。真实 GPS 先转为高德坐标，
模拟点位显式标注 GCJ-02。密钥只保存在服务端，异常不包含带 Key 的请求 URL。
城市介绍读取有来源的资料库；未收录城市只展示地图返回的实际地点。
不把餐厅存在当作菜肴起源证明，旧版无来源的模型候选与缓存不再复用。
"""
from __future__ import annotations

import requests
import json
import re
import time
import threading
from pathlib import Path
from datetime import datetime, timezone, timedelta
from urllib.parse import urlencode
from typing import Callable
from .city_kb import knowledge_base


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


CACHE_TTL = 24 * 60 * 60
CACHE_DIR = Path(__file__).resolve().parents[1] / "data" / "city_guides"
_locks_guard = threading.Lock()
_city_locks: dict[str, threading.Lock] = {}


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


def city_guide(city_code: str, city_name: str, key: str = "",
               generate: Callable[[str], str] | None = None) -> dict:
    if not re.fullmatch(r"\d{6}", city_code) or not city_name or len(city_name) > 40:
        raise CityServiceError("城市标识不合法")
    # v4：城市事实来自可引用资料库。generate 参数为旧调用方保留，不再让模型猜特色菜。
    knowledge = knowledge_base()
    known = knowledge.guide(city_code)
    if known:
        return known
    guide = {"city_code": city_code, "city_name": city_name,
             "subtitle": "一座新城市，慢慢认识", "foods": [], "sights": [], "sources": [],
             "knowledge": [], "knowledge_status": "missing", "kb_revision": knowledge.revision,
             "updated_on": "", "curated": False, "version": 4, "food_kind": "places",
             "generation": "unavailable", "notice": "这座城市尚未收录可引用的资料"}
    if not key:
        return guide
    with _locks_guard:
        lock = _city_locks.setdefault(city_code, threading.Lock())
    with lock:
        path = CACHE_DIR / (city_code + ".json")
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            if (cached.get("version") == 4 and cached.get("map_cache_version") == 2
                and time.time() - path.stat().st_mtime < CACHE_TTL):
                cached["kb_revision"] = knowledge.revision
                return cached
        except (OSError, ValueError):
            pass
        # 未收录城市仍可看地图返回的实际店铺与景点，不能把店铺冒充当地特色菜。
        # 地图会把带餐饮附属分类的宾馆也返回，只展示主分类是餐饮的地点。
        foods = [p for p in _safe_search(key, city_code, category="050000")
                 if p.get("typecode", "").startswith("05")]
        sights = [p for p in _safe_search(key, city_code, category="110000")
                  if p.get("typecode", "").startswith("11")]
        guide.update(foods=[p["name"] for p in foods[:3]], sights=[p["name"] for p in sights[:3]], map_cache_version=2,
                     generation="map", updated_on=datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d"),
                     notice="本城知识资料尚未收录，以下仅为地图地点；营业与路线请在地图中确认")
        if foods or sights:
            guide["sources"] = [{"title": "高德地图 · 地点检索", "url": _map_url(city_code, "景点 美食")}]
        else:
            guide.update(generation="unavailable", notice="本城知识资料尚未收录，地图地点暂未获取，可稍后刷新")
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
