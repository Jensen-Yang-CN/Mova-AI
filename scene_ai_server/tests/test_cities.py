"""城市服务边界测试：不发送网络请求，不使用真实地图 Key。"""
import unittest
from unittest.mock import patch
import requests
from mova.cities import resolve_city, city_guide, CityServiceError


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

    def test_unknown_city_does_not_invent_local_food(self):
        guide = city_guide("999999", "未收录城市")
        self.assertFalse(guide["curated"])
        self.assertEqual(guide["foods"], [])
        self.assertEqual(guide["sights"], [])


if __name__ == "__main__":
    unittest.main()
