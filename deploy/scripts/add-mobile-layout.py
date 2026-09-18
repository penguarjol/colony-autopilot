#!/usr/bin/env python3
"""Give the board a phone-friendly layout.

Homarr sizes tiles by column count per responsive breakpoint. The board ships
with a single 12-column layout at breakpoint 0, which applies to every screen
width, so a phone tries to fit twelve columns across and every tile becomes
unreadable.

Breakpoints are minimum widths, so the fix is mobile-first: make the
breakpoint-0 layout narrow, and add wider layouts that take over on larger
screens.
"""
from __future__ import annotations

import json
import sqlite3
import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError  # noqa: E402

DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"

# (name, breakpoint_min_width_px, columns)
# 2 columns on a phone keeps tiles legible on an iPhone-class screen.
LAYOUTS = [
    ("Mobile", 0, 2),
    ("Tablet", 768, 6),
    ("Desktop", 1280, 12),
]


def main() -> None:
    api = Homarr()
    api.ensure_api_key()

    board = api.trpc_query("board.getHomeBoard")
    board_id = board["id"]
    existing = {l["name"]: l for l in board.get("layouts", [])}
    print(f"board {board_id}, existing layouts: {list(existing)}")

    # Every layout needs an id, including new ones, so generate ids the
    # client side rather than expecting the server to mint them.
    import secrets

    prior = board.get("layouts") or []
    payload = []
    for index, (name, breakpoint_px, columns) in enumerate(LAYOUTS):
        if name in existing:
            layout_id = existing[name]["id"]
        elif index < len(prior):
            # Reuse the existing layout's id for the first entry so its item
            # placements carry over instead of being orphaned.
            layout_id = prior[index]["id"]
        else:
            layout_id = secrets.token_hex(12)
        payload.append({
            "id": layout_id,
            "name": name,
            "breakpoint": breakpoint_px,
            "columnCount": columns,
        })

    try:
        api.trpc_mutate("board.saveLayouts", {"id": board_id,
                                              "layouts": payload})
        print("saved layouts")
    except HomarrError as exc:
        sys.exit(f"saveLayouts failed: {str(exc)[:400]}")

    conn = sqlite3.connect(DB)
    try:
        rows = conn.execute(
            "SELECT id, name, column_count, breakpoint FROM layout "
            "WHERE board_id=? ORDER BY breakpoint", (board_id,),
        ).fetchall()
        for layout_id, name, columns, breakpoint_px in rows:
            placed = conn.execute(
                "SELECT COUNT(*) FROM item_layout WHERE layout_id=?",
                (layout_id,),
            ).fetchone()[0]
            print(f"  {name:<8} breakpoint>={breakpoint_px:<5} "
                  f"cols={columns:<3} items={placed}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
