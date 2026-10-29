"""行动卡必须能在原文中定位事项，日期也需逐项核对。"""

from mova.action_card import normalize_action_card


def test_action_card_discards_unsupported_tasks():
    source = "请于2026年10月29日 15:30提交报名表。"
    card = normalize_action_card({"title": "报名", "tasks": [
        {"title": "提交报名表", "evidence": source,
         "due_at": "2026-10-29T15:30:00+08:00"},
        {"title": "交学费", "evidence": "请于今晚交学费", "due_at": None},
    ]}, source)
    assert len(card["tasks"]) == 1
    assert card["tasks"][0]["due_at"] == "2026-10-29T15:30:00+08:00"


def test_action_card_drops_unverifiable_calendar_time():
    source = "请于2026年10月29日 15:30提交报名表。"
    card = normalize_action_card({"tasks": [
        {"title": "提交报名表", "evidence": source,
         "due_at": "2026-10-30T15:30:00+08:00"},
    ]}, source)
    assert card["tasks"][0]["due_at"] is None
