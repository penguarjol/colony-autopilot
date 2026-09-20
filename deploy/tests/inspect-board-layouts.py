#!/usr/bin/env python3
"""Report tile geometry per responsive layout, and flag what will render wrong.

Homarr stores one row in item_layout per item per layout, so a tile can be
fine on desktop and broken on mobile. Reading the board through the UI hides
that; this reads the geometry directly.

Read-only. Runs on the server.
"""
from __future__ import annotations

import sqlite3
import sys
from collections import defaultdict

DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"


def main() -> int:
    con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    q = lambda sql, *a: con.execute(sql, a).fetchall()  # noqa: E731

    problems = 0
    for board in q("SELECT id, name FROM board"):
        print(f"BOARD {board['name']}")
        for lay in q(
            "SELECT * FROM layout WHERE board_id=? ORDER BY breakpoint", board["id"]
        ):
            cols = lay["column_count"]
            rows = q(
                "SELECT il.*, i.kind FROM item_layout il "
                "JOIN item i ON i.id = il.item_id WHERE il.layout_id=?",
                lay["id"],
            )
            # Wider than the grid, so the tile is clipped or pushed off-screen.
            overflow = [r for r in rows if r["x_offset"] + r["width"] > cols]
            # Zero-sized tiles render as an empty box, which reads as "broken".
            degenerate = [r for r in rows if r["width"] < 1 or r["height"] < 1]

            occupied: dict[tuple[int, int], list[str]] = defaultdict(list)
            for r in rows:
                for x in range(r["x_offset"], r["x_offset"] + max(r["width"], 1)):
                    for y in range(r["y_offset"], r["y_offset"] + max(r["height"], 1)):
                        occupied[(x, y)].append(r["kind"])
            collisions = {
                tuple(sorted(set(k))) for k in occupied.values() if len(k) > 1
            }

            flag = ""
            if overflow or degenerate or collisions:
                flag = "  <-- PROBLEM"
                problems += 1
            print(
                f"  {lay['name']:<10} cols={cols:<3} bp={lay['breakpoint']:<5} "
                f"items={len(rows):<3} overflow={len(overflow)} "
                f"zero-size={len(degenerate)} overlaps={len(collisions)}{flag}"
            )
            for r in overflow[:8]:
                print(
                    f"      overflow {r['kind']}: x={r['x_offset']} "
                    f"w={r['width']} exceeds {cols}"
                )
            for r in degenerate[:8]:
                print(
                    f"      zero-size {r['kind']}: w={r['width']} h={r['height']}"
                )
            for c in list(collisions)[:8]:
                print(f"      overlap {' + '.join(c)}")

        # An item with no row in a layout simply will not appear there.
        total = q(
            "SELECT COUNT(*) n FROM item WHERE board_id=?", board["id"]
        )[0]["n"]
        for lay in q("SELECT * FROM layout WHERE board_id=?", board["id"]):
            placed = q(
                "SELECT COUNT(*) n FROM item_layout WHERE layout_id=?", lay["id"]
            )[0]["n"]
            if placed != total:
                print(
                    f"  {lay['name']}: {placed}/{total} items placed; "
                    f"{total - placed} will not render"
                )
                problems += 1

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
