"""城市初见的数据与高德适配。

定位是确定性任务，不调用语言模型猜城市。真实 GPS 先转为高德坐标，
模拟点位显式标注 GCJ-02。密钥只保存在服务端，异常不包含带 Key 的请求 URL。
城市介绍采用可核对资料，第一版精编武汉与岳阳；其他城市不编造特色美食。
"""
from __future__ import annotations

import requests


class CityServiceError(RuntimeError):
    pass


def _amap(path: str, key: str, **params) -> dict:
    if not key:
        raise CityServiceError("地图服务未配置，请在服务端填写 MOVA_AMAP_KEY")
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
        raise CityServiceError("当前坐标未解析到国内行政区域")
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


def city_guide(city_code: str, city_name: str) -> dict:
    content = GUIDES.get(city_code)
    return {"city_code": city_code, "city_name": content["city_name"] if content else city_name,
            "subtitle": content["subtitle"] if content else "一座新城市，一份新的照应",
            "foods": content["foods"] if content else [],
            "sights": content["sights"] if content else [],
            "sources": content["sources"] if content else [],
            "updated_on": "2026-10-12", "curated": content is not None,
            "notice": "仅介绍城市特色，不提供实时营业、票价或路线信息。" if content else
                      "这座城市的精编资料尚未收录，可打开地图搜索；不会自动编造美食或景点。"}
