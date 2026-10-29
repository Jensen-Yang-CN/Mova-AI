"""城市服务边界测试：不发送网络请求，不使用真实地图 Key。"""
import unittest
import tempfile
import json
from pathlib import Path
from unittest.mock import patch
import requests
from mova.cities import resolve_city, city_guide, CityServiceError, _search


class CityServiceTests(unittest.TestCase):
    def test_municipality_uses_province_when_city_is_array(self):
        address = {"province": "北京市", "city": [], "adcode": "110108", "district": "海淀区"}
        with patch("mova.cities._amap", return_value={"regeocode": {"addressComponent": address}}):
            city = resolve_city("example", 116, 39, "gcj02")
        self.assertEqual(city["city_code"], "110000")
        self.assertEqual(city["city_name"], "北京市")

    def test_province_direct_county_is_not_merged_into_fake_city(self):
        address = {"province": "湖北省", "city": [], "adcode": "429004", "district": "仙桃市"}
        with patch("mova.cities._amap", return_value={"regeocode": {"addressComponent": address}}):
            city = resolve_city("example", 113, 30, "gcj02")
        self.assertEqual(city["city_code"], "429004")
        self.assertEqual(city["city_name"], "仙桃市")

    def test_request_failure_does_not_expose_query_key(self):
        with patch("mova.cities.requests.get", side_effect=requests.ConnectionError("?key=private-example")):
            with self.assertRaises(CityServiceError) as result:
                resolve_city("private-example", 113, 30, "gcj02")
        self.assertNotIn("private-example", str(result.exception))

    def test_without_sources_does_not_fabricate_recommendations(self):
        guide = city_guide("999999", "未收录城市")
        self.assertFalse(guide["curated"])
        self.assertEqual(guide["foods"], [])
        self.assertEqual(guide["sights"], [])

    def test_municipality_poi_filter_keeps_its_districts_only(self):
        body = {"pois": [{"name": "测试景点甲", "adcode": "110108"},
                         {"name": "测试景点乙", "adcode": "120101"}]}
        with patch("mova.cities._amap", return_value=body):
            places = _search("example", "110000", "测试")
        self.assertEqual([p["name"] for p in places], ["测试景点甲"])

    def test_known_city_uses_sources_without_calling_model_or_map(self):
        with patch("mova.cities._safe_search", side_effect=AssertionError("不应请求地图")):
            guide = city_guide("370200", "青岛市", "example", lambda _: self.fail("不应猜测知识"))
        self.assertIn("鲅鱼水饺", guide["foods"])
        self.assertEqual(guide["knowledge_status"], "available")
        self.assertTrue(all(i["source_url"].startswith("https://") for i in guide["knowledge"]))

    def test_unknown_city_map_fallback_is_labeled_as_places(self):
        def lookup(key, code, keyword="", category=""):
            if category == "050000":
                return [{"name": "附带餐饮的宾馆", "typecode": "100103|050000"},
                        {"name": "地图餐厅", "typecode": "050100"}]
            return [{"name": "地图景点", "typecode": category}]
        with tempfile.TemporaryDirectory() as temporary:
            with patch("mova.cities._safe_search", side_effect=lookup), patch("mova.cities.CACHE_DIR", Path(temporary)):
                guide = city_guide("999998", "未收录城市", "example", lambda _: self.fail("不应猜特色菜"))
        self.assertEqual(guide["food_kind"], "places")
        self.assertEqual(guide["knowledge_status"], "missing")
        self.assertTrue(guide["foods"] and guide["sights"])
        self.assertEqual(guide["foods"], ["地图餐厅"])

    def test_old_unsourced_cache_is_not_reused(self):
        with tempfile.TemporaryDirectory() as temporary:
            (Path(temporary) / "999998.json").write_text(json.dumps({"version": 3, "foods": ["旧版模型猜测"]}), encoding="utf-8")
            with patch("mova.cities.CACHE_DIR", Path(temporary)), patch("mova.cities._safe_search", return_value=[]):
                guide = city_guide("999998", "未收录城市", "example")
        self.assertEqual(guide["foods"], [])
        self.assertEqual(guide["version"], 4)


if __name__ == "__main__":
    unittest.main()
