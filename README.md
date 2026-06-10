# Assumptions

- Fixed typo in the SQL schema: `createdAt` → `created_at` on `sushi_order`.
- Added a `resumed` status. Resumed orders take priority over `created` orders.
- Kitchen queue priority:
  - FIFO for `created` orders
  - FIFO for `resumed` orders
  - `resumed` orders are served before `created` orders
- Cancel is not allowed on completed (`finished`) orders.

- Analytics metrics are tracked in memory (not DB-backed).
- Analytics is meant to reflect **kitchen load** (queue wait and normal cook throughput). Pause/resume orders are excluded because they would inflate averages with extreme outliers and distort load evaluation.
- `averageWaitTime` — every order that goes from `created` to `in-progress` is counted at that moment, except orders paused while still `created`.
- `averageMakeTime` — only orders that went straight from `in-progress` to `finished` without being paused while `in-progress` are included.