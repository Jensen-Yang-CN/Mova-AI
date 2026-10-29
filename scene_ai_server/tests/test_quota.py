"""公网计费接口的请求上限边界测试。"""

import unittest

from mova.quota import RequestQuota


class RequestQuotaTests(unittest.TestCase):
    def test_minute_limit_recovers_after_window(self):
        quota = RequestQuota(per_minute=2, per_day=5)
        self.assertTrue(quota.allow(now=0))
        self.assertTrue(quota.allow(now=1))
        self.assertFalse(quota.allow(now=59))
        self.assertTrue(quota.allow(now=60))

    def test_daily_limit_blocks_even_after_minute_window(self):
        quota = RequestQuota(per_minute=2, per_day=2)
        self.assertTrue(quota.allow(now=0))
        self.assertTrue(quota.allow(now=1))
        self.assertFalse(quota.allow(now=60))
        self.assertTrue(quota.allow(now=24 * 60 * 60))


if __name__ == "__main__":
    unittest.main()
