"""城市服务边界测试：不发送网络请求，不使用真实地图 Key。"""
import unittest
from unittest.mock import patch
import requests
from mova.cities import resolve_city, city_guide, CityServiceError, _search, _generate_guide


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

    def test_any_new_city_can_generate_food_and_verified_sights(self):
        # 虚构名称只作为测试输入，测试不调用网络或写入推荐缓存。
        def lookup(key, code, keyword="", category=""):
            if keyword == "测试景区":
                return [{"name": "测试景区", "typecode": "110200"}]
            return []
        with patch("mova.cities._safe_search", side_effect=lookup):
            guide = _generate_guide("330100", "杭州市", "example",
                                    lambda _: '{"foods":["测试菜"],"sights":["测试景区"]}')
        self.assertEqual(guide["foods"], ["测试菜"])
        self.assertEqual(guide["sights"], ["测试景区"])
        self.assertEqual(guide["generation"], "ai_map")

    def test_unrelated_or_parking_poi_is_not_a_verified_sight(self):
        def lookup(key, code, keyword="", category=""):
            if keyword:
                return [{"name": "测试景区停车场", "typecode": "150900"}]
            return []
        with patch("mova.cities._safe_search", side_effect=lookup):
            guide = _generate_guide("330100", "杭州市", "example",
                                    lambda _: '{"foods":["测试菜"],"sights":["测试景区"]}')
        self.assertEqual(guide["sights"], [])

    def test_model_failure_falls_back_to_actual_map_places(self):
        def lookup(key, code, keyword="", category=""):
            return [{"name": "地图餐厅" if category == "050000" else "地图景点"}]
        with patch("mova.cities._safe_search", side_effect=lookup):
            guide = _generate_guide("330100", "杭州市", "example", lambda _: "broken JSON")
        self.assertEqual(guide["food_kind"], "places")
        self.assertTrue(guide["foods"] and guide["sights"])


if __name__ == "__main__":
    unittest.main()
