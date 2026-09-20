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

# Widgets carrying a list or an embedded document need the full width of a
# narrow grid; at half width they clip.
WIDE = {
    "iframe", "healthMonitoring", "dockerContainers",
    "dnsHoleSummary", "dnsHoleControls", "rssFeed",
}

# Only app tiles are legible in a single cell. Everything else draws a
# heading plus content, and below these heights renders as an empty box,
# which is what "broken tiles" on a phone actually looks like.
MIN_HEIGHT = {
    "clock": 1,
    "weather": 1,
    "minecraftServerStatus": 2,
    "dnsHoleSummary": 2,
    "dnsHoleControls": 2,
    "dockerContainers": 4,
    "healthMonitoring": 4,
    "rssFeed": 4,
    # An embedded terminal or agent session is unusable in a few rows.
    "iframe": 6,
}

COMPACT = {"app"}
DEFAULT_MIN_HEIGHT = 2


# Width each widget wants on a twelve-column desktop grid, scaled down for
# narrower ones. A formula over the column count cannot express that a clock
# and a container list want very different amounts of room.
PREFERRED_WIDTH_AT_12 = {
    "app": 1,
    "clock": 2,
    "weather": 2,
    "minecraftServerStatus": 3,
    "dnsHoleSummary": 3,
    "dnsHoleControls": 3,
    "rssFeed": 4,
    "dockerContainers": 6,
    "healthMonitoring": 6,
    "iframe": 6,
}
DEFAULT_PREFERRED_WIDTH = 3


def target_size(kind: str, width: int, height: int, columns: int) -> tuple[int, int]:
    if kind in COMPACT:
        return 1, 1
    preferred = PREFERRED_WIDTH_AT_12.get(kind, DEFAULT_PREFERRED_WIDTH)
    scaled = round(preferred * columns / 12)
    # Never below two cells: one cell leaves no room for a heading and its
    # content, which is what rendered as an empty box.
    new_width = min(columns, max(2, scaled))
    return new_width, max(height, MIN_HEIGHT.get(kind, DEFAULT_MIN_HEIGHT))


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
        new_width, new_height = target_size(kind, width, height, columns)

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
            # The desktop layout was previously skipped as "authored", which
            # left eight of its widgets at one cell and unreadable. Every
            # layout gets sized.
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
            # Fitting the grid is not the same as being readable. A widget
            # shrunk below its minimum fits perfectly and still renders blank.
            undersized = [
                f"{kind} {w}x{h}"
                for kind, w, h in conn.execute(
                    "SELECT i.kind, il.width, il.height FROM item_layout il "
                    "JOIN item i ON i.id = il.item_id WHERE il.layout_id=?",
                    (layout_id,),
                )
                if kind not in COMPACT
                and h < MIN_HEIGHT.get(kind, DEFAULT_MIN_HEIGHT)
            ]
            problems = []
            if over:
                problems.append(f"{over} OVERFLOWING")
            if undersized:
                problems.append(f"{len(undersized)} UNDERSIZED")
            status = "ok" if not problems else ", ".join(problems)
            print(f"  {name:<8} cols={columns:<3} {status}")
            for u in undersized[:6]:
                print(f"      undersized {u}")
            if problems:
                sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
