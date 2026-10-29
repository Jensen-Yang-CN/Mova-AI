"""核查城市隔离、引用白名单、无依据拒答和模型故障降级。"""
import unittest
from mova.city_kb import CityKnowledge


class KnowledgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.kb = CityKnowledge()

    def test_city_filter_and_food_intent(self):
        hits = self.kb.search("370200", "当地有哪些特色美食")
        self.assertEqual({h["city_code"] for h in hits}, {"370200"})
        self.assertEqual({h["kind"] for h in hits}, {"food"})

    def test_specific_landmark_ranks_first(self):
        self.assertEqual(self.kb.search("370200", "八大关有什么特点")[0]["id"], "qd-sight-badaguan")

    def test_unknown_city_never_calls_cloud(self):
        answer = self.kb.answer("999999", "有哪些景点", lambda _: self.fail("不能跨城搜索"))
        self.assertFalse(answer["grounded"])

    def test_live_information_does_not_use_static_fact(self):
        answer = self.kb.answer("370200", "八大关今天营业到几点", lambda _: self.fail("实时问题不能送模型编造"))
        self.assertFalse(answer["grounded"])
        self.assertEqual(answer["citations"], [])

    def test_unrelated_question_is_not_answered_by_common_character(self):
        self.assertFalse(self.kb.answer("420100", "市长是谁")["grounded"])

    def test_model_can_only_select_retrieved_ids(self):
        answer = self.kb.answer("370200", "有什么好吃的", lambda _: '{"ids":["wh-food-reganmian"]}')
        self.assertEqual(answer["mode"], "retrieval")
        self.assertNotIn("热干面", answer["answer"])
        self.assertTrue(all(c["id"].startswith("qd-") for c in answer["citations"]))

    def test_model_selected_fact_has_traceable_citation(self):
        answer = self.kb.answer("370200", "介绍八大关", lambda _: '{"ids":["qd-sight-badaguan"]}')
        self.assertEqual(answer["mode"], "rag")
        self.assertIn("[1]", answer["answer"])
        self.assertTrue(answer["citations"][0]["source_url"].startswith("https://www.qingdao.gov.cn/"))

    def test_model_failure_keeps_evidence_available(self):
        def unavailable(_):
            raise RuntimeError("上游不可用")
        answer = self.kb.answer("420100", "热干面是什么", unavailable)
        self.assertEqual(answer["mode"], "retrieval")
        self.assertTrue(answer["grounded"])

    def test_empty_selection_abstains(self):
        answer = self.kb.answer("420100", "热干面是什么", lambda _: '{"ids":[]}')
        self.assertFalse(answer["grounded"])


if __name__ == "__main__":
    unittest.main()
