from __future__ import annotations

import json
import sqlite3
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .models import Action, Tier


class Store:
    def __init__(self, path: str | Path) -> None:
        database_path = Path(path)
        database_path.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(database_path)
        self._connection.row_factory = sqlite3.Row
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._connection.execute("PRAGMA synchronous=NORMAL")
        self._migrate()

    def _migrate(self) -> None:
        self._connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS controller_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS actions (
                idempotency_key TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                payload TEXT NOT NULL,
                rationale TEXT NOT NULL,
                status TEXT NOT NULL,
                request_id TEXT,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS one_pending_action
                ON actions ((1)) WHERE status IN ('reserved', 'dispatched');
            CREATE TABLE IF NOT EXISTS progression (
                player_id TEXT PRIMARY KEY,
                tier TEXT NOT NULL,
                tier_rank INTEGER NOT NULL,
                evidence TEXT NOT NULL,
                grant_spent INTEGER NOT NULL DEFAULT 0,
                grant_day TEXT NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS audit (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp REAL NOT NULL,
                event TEXT NOT NULL,
                details TEXT NOT NULL
            );
            """
        )
        columns = {
            row[1]
            for row in self._connection.execute("PRAGMA table_info(progression)")
        }
        if "tier_rank" not in columns:
            self._connection.execute(
                "ALTER TABLE progression ADD COLUMN tier_rank INTEGER NOT NULL DEFAULT 0"
            )
        if "grant_day" not in columns:
            self._connection.execute(
                "ALTER TABLE progression ADD COLUMN grant_day TEXT NOT NULL DEFAULT ''"
            )

    def reserve_action(self, action: Action) -> bool:
        now = time.time()
        try:
            with self._connection:
                self._connection.execute(
                    """
                    INSERT INTO actions (
                        idempotency_key, kind, payload, rationale, status,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'reserved', ?, ?)
                    """,
                    (
                        action.idempotency_key,
                        action.kind.value,
                        _json(action.to_json()["payload"]),
                        action.rationale,
                        now,
                        now,
                    ),
                )
                self._audit("reserved", {"idempotency_key": action.idempotency_key})
            return True
        except sqlite3.IntegrityError:
            return False

    def mark_dispatched(self, idempotency_key: str, request_id: str) -> None:
        with self._connection:
            self._connection.execute(
                """
                UPDATE actions SET status = 'dispatched', request_id = ?, updated_at = ?
                WHERE idempotency_key = ? AND status = 'reserved'
                """,
                (request_id, time.time(), idempotency_key),
            )
            self._audit(
                "dispatched",
                {"idempotency_key": idempotency_key, "request_id": request_id},
            )

    def complete_action(
        self, idempotency_key: str, status: str, details: dict[str, Any]
    ) -> None:
        if status not in {"completed", "failed", "rejected"}:
            raise ValueError("invalid terminal action status")
        with self._connection:
            self._connection.execute(
                "UPDATE actions SET status = ?, updated_at = ? WHERE idempotency_key = ?",
                (status, time.time(), idempotency_key),
            )
            self._audit(status, {"idempotency_key": idempotency_key, **details})

    def pending_action(self) -> dict[str, Any] | None:
        row = self._connection.execute(
            """
            SELECT * FROM actions
            WHERE status IN ('reserved', 'dispatched')
            ORDER BY created_at LIMIT 1
            """
        ).fetchone()
        return dict(row) if row else None

    def save_progression(
        self,
        player_id: str,
        tier: Tier,
        evidence: dict[str, Any],
        spent: int,
        now: datetime | None = None,
    ) -> None:
        tier_rank = tuple(Tier).index(tier)
        current_day = _utc_day(now)
        with self._connection:
            self._connection.execute(
                """
                INSERT INTO progression(
                    player_id, tier, tier_rank, evidence, grant_spent, grant_day,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_id) DO UPDATE SET
                    tier = CASE
                        WHEN excluded.tier_rank > progression.tier_rank
                        THEN excluded.tier ELSE progression.tier
                    END,
                    tier_rank = MAX(progression.tier_rank, excluded.tier_rank),
                    evidence = excluded.evidence,
                    grant_spent = CASE
                        WHEN progression.grant_day = excluded.grant_day
                        THEN MAX(progression.grant_spent, excluded.grant_spent)
                        ELSE excluded.grant_spent
                    END,
                    grant_day = excluded.grant_day,
                    updated_at = excluded.updated_at
                """,
                (
                    player_id,
                    tier.value,
                    tier_rank,
                    _json(evidence),
                    spent,
                    current_day,
                    time.time(),
                ),
            )

    def progression(self, player_id: str) -> dict[str, Any] | None:
        row = self._connection.execute(
            "SELECT * FROM progression WHERE player_id = ?", (player_id,)
        ).fetchone()
        return dict(row) if row else None

    def grant_spent(
        self, player_id: str, now: datetime | None = None
    ) -> int:
        current_day = _utc_day(now)
        row = self._connection.execute(
            "SELECT grant_spent, grant_day FROM progression WHERE player_id = ?",
            (player_id,),
        ).fetchone()
        if row is None:
            return 0
        if row["grant_day"] == current_day:
            return int(row["grant_spent"])
        with self._connection:
            self._connection.execute(
                """
                UPDATE progression SET grant_spent = 0, grant_day = ?, updated_at = ?
                WHERE player_id = ?
                """,
                (current_day, time.time(), player_id),
            )
        return 0

    def increment_grant_spent(
        self, player_id: str, amount: int, now: datetime | None = None
    ) -> None:
        self.grant_spent(player_id, now)
        with self._connection:
            self._connection.execute(
                """
                UPDATE progression SET grant_spent = grant_spent + ?, updated_at = ?
                WHERE player_id = ?
                """,
                (amount, time.time(), player_id),
            )

    def claim_cadence(
        self, name: str, cadence_seconds: float, now: float | None = None
    ) -> bool:
        timestamp = time.time() if now is None else now
        key = f"cadence:{name}"
        with self._connection:
            row = self._connection.execute(
                "SELECT value FROM controller_state WHERE key = ?", (key,)
            ).fetchone()
            if row is not None and timestamp - float(row["value"]) < cadence_seconds:
                return False
            self._connection.execute(
                """
                INSERT INTO controller_state(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                (key, str(timestamp)),
            )
        return True

    def audit(self, event: str, details: dict[str, Any]) -> None:
        with self._connection:
            self._audit(event, details)

    def _audit(self, event: str, details: dict[str, Any]) -> None:
        self._connection.execute(
            "INSERT INTO audit(timestamp, event, details) VALUES (?, ?, ?)",
            (time.time(), event, _json(details)),
        )

    def audit_entries(self) -> list[dict[str, Any]]:
        return [
            {"event": row["event"], **json.loads(row["details"])}
            for row in self._connection.execute("SELECT * FROM audit ORDER BY sequence")
        ]

    def status(self) -> dict[str, Any]:
        pending = self.pending_action()
        audit_count = self._connection.execute("SELECT COUNT(*) FROM audit").fetchone()[0]
        return {"pending_action": pending, "audit_entries": audit_count}

    def close(self) -> None:
        self._connection.close()


def _json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def _utc_day(now: datetime | None) -> str:
    value = now or datetime.now(UTC)
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).date().isoformat()
