#!/usr/bin/env python3
"""Reflow item geometry to fit each responsive layout's column count.

board.saveLayouts copies existing placements into every new layout, so tiles
sized for twelve columns land unchanged in the two-column phone layout and
overflow it. Only x/y/width/height change here; the rows and their widget
options already exist and are untouched.
"""
from __future__ import annotations

import sqlite3
import sys

DB = "/var/lib/docker/volumes/docker_homarr_data/_data/db/db.sqlite"

# Widgets that stay readable only at close to full width on a phone.
FULL_WIDTH_ON_MOBILE = {
    "iframe", "healthMonitoring", "dockerContainers",
    "dnsHoleSummary", "dnsHoleControls", "rssFeed",
}


def reflow(conn: sqlite3.Connection, layout_id: str, columns: int) -> int:
    items = conn.execute(
        """SELECT il.item_id, i.kind, il.width, il.height
           FROM item_layout il JOIN item i ON i.id = il.item_id
           WHERE il.layout_id = ?
           ORDER BY il.y_offset, il.x_offset""",
        (layout_id,),
    ).fetchall()

    x = y = row_height = 0
    changed = 0
    for item_id, kind, width, height in items:
        if kind in FULL_WIDTH_ON_MOBILE:
            new_width = columns
            new_height = max(height, 3 if columns <= 2 else height)
        else:
            new_width = min(width, max(1, columns // 2))
            new_height = height

        if x + new_width > columns:
            x = 0
            y += row_height
            row_height = 0

        conn.execute(
            """UPDATE item_layout
               SET x_offset=?, y_offset=?, width=?, height=?
               WHERE item_id=? AND layout_id=?""",
            (x, y, new_width, new_height, item_id, layout_id),
        )
        changed += 1
        x += new_width
        row_height = max(row_height, new_height)
        if x >= columns:
            x = 0
            y += row_height
            row_height = 0
    return changed


def main() -> None:
    conn = sqlite3.connect(DB)
    try:
        layouts = conn.execute(
            "SELECT id, name, column_count FROM layout ORDER BY breakpoint"
        ).fetchall()
        for layout_id, name, columns in layouts:
            # The widest layout already reflects how the board was authored.
            if columns >= 12:
                print(f"  {name}: left as authored ({columns} columns)")
                continue
            count = reflow(conn, layout_id, columns)
            print(f"  {name}: reflowed {count} items into {columns} columns")
        conn.commit()

        print("\nverification:")
        for layout_id, name, columns in layouts:
            over = conn.execute(
                "SELECT COUNT(*) FROM item_layout "
                "WHERE layout_id=? AND (x_offset + width) > ?",
                (layout_id, columns),
            ).fetchone()[0]
            status = "ok" if over == 0 else f"{over} OVERFLOWING"
            print(f"  {name:<8} cols={columns:<3} {status}")
            if over:
                sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
