"""独立测试流程中的锁定和严格评分边界。"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

from independent_cases import build_cases  # noqa: E402
from independent_eval import (  # noqa: E402
    PRIOR_GOLD_MD5, REVIEW_FIELDS, TRAIN_MD5, check_overlap, draft, finalize, read_review, score,
)


class IndependentEvalTest(unittest.TestCase):
    def test_review_status_is_required_before_gold(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "review.tsv"
            with redirect_stdout(StringIO()):
                draft(argparse.Namespace(out=path))
            with self.assertRaisesRegex(ValueError, "尚未审核"):
                read_review(path)
            with path.open(encoding="utf-8-sig", newline="") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            for row in rows:
                row["审核"] = "通过"
            rows[0]["备注"] = "AI复核：与契约一致"
            with path.open("w", encoding="utf-8-sig", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=REVIEW_FIELDS, delimiter="\t")
                writer.writeheader()
                writer.writerows(rows)
            self.assertEqual(len(read_review(path)), 150)
            with self.assertRaisesRegex(ValueError, "不能用 --reviewer human"):
                finalize(argparse.Namespace(
                    gold=Path(tmp) / "gold.jsonl", review=path, reviewer="human",
                    train_file=Path(tmp) / "train.jsonl", prior_gold=Path(tmp) / "prior.jsonl",
                    overlap_report=Path(tmp) / "overlap.tsv", approve_near=False,
                ))
            args = argparse.Namespace(
                gold=Path(tmp) / "gold.jsonl", review=path, reviewer="ai",
                train_file=Path(tmp) / "train.jsonl", prior_gold=Path(tmp) / "prior.jsonl",
                overlap_report=Path(tmp) / "overlap.tsv", approve_near=False,
            )
            train = [{"id": f"train_{i}"} for i in range(5500)]
            prior = [{"id": f"prior_{i}"} for i in range(500)]
            with patch("independent_eval.md5", side_effect=(TRAIN_MD5, PRIOR_GOLD_MD5)), \
                    patch("independent_eval.read_jsonl", side_effect=(train, prior)), \
                    patch("independent_eval.check_overlap", return_value=(0, 0)), \
                    redirect_stdout(StringIO()):
                finalize(args)
            locked = json.loads(args.gold.read_text(encoding="utf-8").splitlines()[0])
            self.assertEqual(locked["reviewer"], "ai")
            self.assertIs(locked["human_reviewed"], False)

    def test_training_overlap_is_reported(self) -> None:
        rows = build_cases()
        with tempfile.TemporaryDirectory() as tmp:
            exact, _near = check_overlap(
                rows,
                [{"id": "training_1", "utterance": rows[0]["utterance"]}],
                [],
                Path(tmp) / "overlap.tsv",
            )
            self.assertEqual(exact, 1)

    def test_explanatory_text_around_json_is_invalid(self) -> None:
        cases = build_cases()
        gold = []
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for case in cases:
                expected = {"scene": case["scene"], "intent": case["intent"], "slots": case["slots"]}
                if case["need_cloud"] is not None:
                    expected["need_cloud"] = case["need_cloud"]
                gold.append({
                    "id": case["id"], "family": case["family"], "utterance": case["utterance"],
                    "expected": expected, "checks": case["checks"],
                    "reviewer": "ai", "human_reviewed": False,
                })
            gold_path = folder / "gold.jsonl"
            gold_path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in gold), encoding="utf-8")
            gold_path.with_suffix(".manifest.json").write_text(json.dumps({
                "gold_sha256": hashlib.sha256(gold_path.read_bytes()).hexdigest(),
                "reviewer": "ai",
            }), encoding="utf-8")
            predictions = folder / "predictions.jsonl"
            with predictions.open("w", encoding="utf-8") as handle:
                for index, row in enumerate(gold):
                    expected = row["expected"]
                    scene = expected["scene"]
                    slots = {
                        "food": {"ingredients": []},
                        "reading": {"source": "text", "text_length": 0},
                        "chat": {"target_tone": "自然", "goal": "不指定"},
                        "location": {"place_type": "其他"},
                        "none": {},
                    }[scene]
                    slots.update(expected["slots"])
                    obj = {
                        "scene": scene, "intent": expected["intent"], "slots": slots,
                        "complexity": 0.5, "confidence": 0.5,
                        "need_cloud": expected.get("need_cloud", False),
                    }
                    raw = json.dumps(obj, ensure_ascii=False)
                    if index == 0:
                        raw = "解释如下：" + raw
                    handle.write(json.dumps({"id": row["id"], "text": raw}, ensure_ascii=False) + "\n")
            with redirect_stdout(StringIO()):
                score(argparse.Namespace(gold=gold_path, predictions=predictions, out_dir=folder / "scored"))
            report = json.loads((folder / "scored" / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(report["contract_valid"], 149)
            self.assertEqual(report["failed_ids"], 1)
            self.assertEqual(report["reviewer"], "ai")
            gold_path.write_text(gold_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "锁定清单不一致"):
                score(argparse.Namespace(gold=gold_path, predictions=predictions, out_dir=folder / "tampered"))


if __name__ == "__main__":
    unittest.main()
