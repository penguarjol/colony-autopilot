#!/usr/bin/env python3
"""Lay out the Command Center board to mirror the reference screenshot.

Widget kinds were read off this build's board.addItem enum rather than taken
from documentation, and every item is placed through the API so Homarr
validates option shapes instead of silently accepting junk.

board.addItem only accepts sections of kind "empty". The original board was
hand-written into SQLite with "category" sections, which the API refuses to
place into, so a board created the supported way is required.
"""
from __future__ import annotations

import sys

sys.path.insert(0, "/tmp")
from homarr_api import Homarr, HomarrError  # noqa: E402

HOST = "192.168.1.171"
# Board names are validated against /^[A-Za-z0-9-_]*$/, so the slug carries no
# space; the human-readable title is set separately in board settings.
BOARD = "command-center"
BOARD_TITLE = "Command Center"


def main() -> None:
    api = Homarr()
    api.ensure_api_key()

    boards = {b["name"]: b["id"] for b in (api.trpc_query("board.getAllBoards") or [])}
    print(f"existing boards: {list(boards)}")

    if BOARD in boards:
        board_id = boards[BOARD]
        print(f"reusing board {board_id}")
    else:
        created = api.trpc_mutate(
            "board.createBoard",
            {"name": BOARD, "columnCount": 10, "isPublic": False},
        )
        board_id = created["boardId"]
        print(f"created board {board_id}")

    for procedure in ("board.setHomeBoard", "board.setMobileHomeBoard"):
        try:
            api.trpc_mutate(procedure, {"id": board_id})
        except HomarrError as exc:
            print(f"  {procedure}: {str(exc)[:110]}")

    try:
        api.trpc_mutate("board.savePartialBoardSettings", {
            "id": board_id,
            "pageTitle": BOARD_TITLE,
            "metaTitle": BOARD_TITLE,
        })
    except HomarrError as exc:
        print(f"  title: {str(exc)[:110]}")

    board = api.trpc_query("board.getHomeBoard")
    sections = board.get("sections") or []
    print(f"sections: {[(s.get('kind')) for s in sections]}")
    empty = [s for s in sections if s.get("kind") == "empty"]
    section_id = (empty or sections or [{}])[0].get("id")

    integrations = {i["name"]: i["id"] for i in (api.trpc_query("integration.all") or [])}
    pihole = [integrations[n] for n in ("Pi-hole Main", "Pi-hole Backup")
              if n in integrations]
    kuma = [integrations[n] for n in ("Uptime Kuma",) if n in integrations]

    plan = [
        ("clock", {}, 2, 2),
        ("weather", {}, 2, 2),
        ("dnsHoleSummary", {"integrationIds": pihole}, 3, 2),
        ("dnsHoleControls", {"integrationIds": pihole}, 3, 2),
        # The OpenCode session panel, embedded so agent sessions are created
        # and managed from the dashboard itself.
        ("iframe", {"embedUrl": f"http://{HOST}:4096"}, 6, 5),
        ("notebook", {"content": "## Command Center\n\n"
                                 "One credential covers every service."}, 4, 3),
    ]
    if kuma:
        plan.insert(4, ("healthMonitoring", {"integrationIds": kuma}, 4, 3))

    placed = 0
    x = y = 0
    for kind, options, width, height in plan:
        payload = {
            "boardId": board_id,
            "kind": kind,
            "options": options,
            "xOffset": x,
            "yOffset": y,
            "width": width,
            "height": height,
        }
        if section_id:
            payload["sectionId"] = section_id
        try:
            api.trpc_mutate("board.addItem", payload)
            print(f"  placed {kind}")
            placed += 1
        except HomarrError as exc:
            print(f"  FAILED {kind}: {str(exc)[:140]}")
        x += width
        if x >= 10:
            x = 0
            y += height

    print(f"placed {placed} of {len(plan)}")
    sys.exit(0 if placed else 1)


if __name__ == "__main__":
    main()
