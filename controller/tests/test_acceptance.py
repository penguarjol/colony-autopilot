from __future__ import annotations

import unittest

from controller.acceptance import (
    AcceptanceError,
    assert_deadline_progress,
    assert_healthy_runtime,
    assert_request_completed,
)


class AcceptanceTests(unittest.TestCase):
    def test_healthy_runtime_requires_real_offline_progress(self) -> None:
        status = {
            "enabled": True,
            "mode": "ACTIVE",
            "paused": False,
            "metrics": {"tps": 20.0, "tick_ms": 4.0, "failures": 0},
            "offline_progress_active": True,
            "offline_progress_leases": 1,
            "offline_ticking_chunks": 30,
        }
        snapshot = {"colonies": [{"id": 1, "active": True}]}

        assert_healthy_runtime(status, snapshot)

        status["offline_ticking_chunks"] = 0
        with self.assertRaises(AcceptanceError):
            assert_healthy_runtime(status, snapshot)

    def test_deadline_requires_cursor_progress_or_completion(self) -> None:
        before = {
            "construction_jobs": [
                {
                    "work_order_id": 2,
                    "phase": "RECONCILING",
                    "reconciliation_cursor": 176,
                }
            ]
        }
        progressed = {
            "construction_jobs": [
                {
                    "work_order_id": 2,
                    "phase": "RECONCILING",
                    "reconciliation_cursor": 240,
                }
            ]
        }

        assert_deadline_progress(before, progressed)

        failed = {
            "construction_jobs": [
                {
                    "work_order_id": 2,
                    "phase": "FAILED",
                    "reconciliation_cursor": 176,
                }
            ]
        }
        with self.assertRaises(AcceptanceError):
            assert_deadline_progress(before, failed)

    def test_request_completion_requires_citizen_metadata_and_removal(self) -> None:
        request_id = "1d6514f4-17b9-4f06-ad27-d3c199e9bf55"
        before = {
            "colonies": [{
                "loaded_buildings": [{
                    "open_requests": [{
                        "request_id": request_id,
                        "citizen_id": 4,
                        "request_origin": "citizen",
                        "deliverable": True,
                    }]
                }]
            }]
        }
        after = {
            "colonies": [{
                "loaded_buildings": [{"open_requests": []}]
            }]
        }

        assert_request_completed(before, after, request_id)

        with self.assertRaises(AcceptanceError):
            assert_request_completed(before, before, request_id)


if __name__ == "__main__":
    unittest.main()
