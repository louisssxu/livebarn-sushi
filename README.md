# Assumptions

- Fixed typo in the SQL schema: `createdAt` → `created_at` on `sushi_order`.
- Added a `resumed` status. Resumed orders take priority over `created` orders.
- Kitchen queue priority:
  - FIFO for `created` orders
  - FIFO for `resumed` orders
  - `resumed` orders are served before `created` orders
- Cancel is not allowed on completed (`finished`) orders.
