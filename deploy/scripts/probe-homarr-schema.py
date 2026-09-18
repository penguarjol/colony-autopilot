#!/usr/bin/env python3
"""Print Homarr's full validation errors and integration kinds.

tRPC returns the complete Zod issue list on a bad payload, which is the most
reliable way to learn required fields and enum members for this exact build.
"""
from __future__ import annotations

import json
import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError  # noqa: E402


def full_error(api, procedure, payload):
    print(f"\n----- {procedure} -----")
    try:
        print(json.dumps(api.trpc_mutate(procedure, payload))[:1500])
    except HomarrError as exc:
        text = str(exc)
        marker = '"message":"'
        if marker in text:
            body = text.split(marker, 1)[1]
            body = body.split('","code"', 1)[0]
            print(body.encode().decode("unicode_escape")[:2500])
        else:
            print(text[:1500])


def main() -> None:
    api = Homarr()
    api.ensure_api_key()

    kinds = api.trpc_query("integration.getKinds")
    print("=== integration kinds ===")
    for kind in kinds:
        print(f"  {kind['kind']:<20} {','.join(kind['category']):<40} "
              f"secrets={kind['requiredSecrets']}")

    board = api.trpc_query("board.getHomeBoard")
    print(f"\n=== home board ===\n  id={board['id']} name={board['name']}")
    for layout in board.get("layouts", []):
        print(f"  layout {layout.get('id')} cols={layout.get('columnCount')}")
    print(f"  sections={len(board.get('sections', []))} "
          f"items={sum(len(s.get('items', [])) for s in board.get('sections', []))}")

    full_error(api, "board.addItem", {"boardId": board["id"]})
    full_error(api, "integration.create", {"name": "probe"})
    full_error(api, "app.create", {"name": "probe"})


if __name__ == "__main__":
    main()
