"""城市资料库检查与检索。默认不调用地图或云模型，也不会读取服务端密钥。"""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import re
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scene_ai_server"))
from mova.city_kb import CityKnowledge, CORPUS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=CORPUS)
    parser.add_argument("--city-code", default="")
    parser.add_argument("--question", default="")
    parser.add_argument("--check-sources", action="store_true", help="联网检查来源 HTTP 状态与页面标题")
    args = parser.parse_args()
    kb = CityKnowledge(args.corpus)
    if args.check_sources:
        import requests
        def check(url):
            try:
                response = requests.get(url, timeout=15)
                response.encoding = response.apparent_encoding
                title = re.search(r"<title[^>]*>(.*?)</title>", response.text, re.I | re.S)
                title = title.group(1).strip() if title else ""
                valid = response.status_code == 200 and not re.search(r"404|not found|页面不存在", title, re.I)
                return {"url": url, "status": response.status_code, "title": title, "accessible": valid}
            except requests.RequestException as exc:
                return {"url": url, "status": None, "title": "", "accessible": False, "error_kind": type(exc).__name__}
        with ThreadPoolExecutor(max_workers=3) as pool:
            result = list(pool.map(check, dict.fromkeys(i["source_url"] for i in kb.items)))
    elif args.question:
        if not args.city_code:
            parser.error("检索时必须指定 --city-code")
        result = kb.answer(args.city_code, args.question)
    else:
        result = {"revision": kb.revision, "items": len(kb.items),
                  "source_pages": len({i['source_url'] for i in kb.items}),
                  "cities": [{"code": code, "name": name, "items": sum(i['city_code'] == code for i in kb.items)}
                             for code, name in kb.cities.items()],
                  "kinds": dict(Counter(i['kind'] for i in kb.items)), "retriever": "Chinese n-gram BM25"}
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
