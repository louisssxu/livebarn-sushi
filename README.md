# Sushi

## Assumptions

- Fixed typo in the SQL schema: `createdAt` → `created_at` on `sushi_order`.
- Added a `resumed` status. Queue is FIFO within each group; `resumed` orders are served before `created` orders.
- Cancel is not allowed on completed (`finished`) orders.
- Analytics are in-memory (not persisted) and reflect normal kitchen load:
  - `averageMakeTime` — seconds from `in-progress` to `finished`, excluding orders paused while cooking.

## Overview

Sushi is a Spring Boot backend that simulates a sushi kitchen. Customers place orders via REST; three chefs process orders from a shared queue in the background. Orders move through statuses (`created`, `in-progress`, `paused`, `resumed`, `finished`, `cancelled`), and the API supports pause, resume, and cancel. Kitchen analytics (wait time, make time, chef utilization, popularity) are computed in memory while order state is persisted in H2.

## Tech stack

| | |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.0.6 |
| Persistence | Spring Data JPA, H2 (file-backed) |
| Build | Gradle |
| Testing | JUnit 5, Spring Boot Test, MockMvc |

## Getting started

### Prerequisites

- Java 17+
- No external services required (H2 is embedded)

### Run

```bash
./gradlew bootRun
```

On Windows:

```bash
gradlew.bat bootRun
```

The server starts on **http://localhost:9000**.

### H2 console (optional)

- URL: http://localhost:9000/h2-console
- JDBC URL: `jdbc:h2:file:./data/sushi`
- Username: `sa`
- Password: (empty)

Data is stored under `./data/`. Delete that folder to reset the database.

### Test

```bash
./gradlew test
```

Tests include unit tests (services, controller) and integration tests (full order lifecycle).


## Order lifecycle

```
created ──► in-progress ──► finished
                │   ▲
                ▼   │
              paused ──► resumed ──► in-progress ──► finished
```

| Action | Allowed from |
|--------|----------------|
| Cancel | `created`, `in-progress`, `paused`, `resumed` |
| Pause | `in-progress` only |
| Resume | `paused` only (must have remaining cook time) |

Cancel is **not** allowed on `finished` or already `cancelled` orders.

Status IDs (internal): `created` = 1, `in-progress` = 2, `paused` = 3, `resumed` = 4, `finished` = 5, `cancelled` = 6.

## Kitchen & queue behavior

- **3 chefs** run as background threads in a fixed pool (`Chef.COUNT = 3`).
- On startup, any existing `created` and `resumed` orders in the database are re-queued.
- New orders are enqueued after the create transaction commits.
- **Queue priority:**
  - `resumed` orders are inserted ahead of `created` orders.
  - Within each group, ordering is FIFO by enqueue time.
- Cooking uses 1-second ticks (`Thread.sleep(1)`); remaining time is tracked in memory.
- When a chef picks an order:
  - `created` → sets `in-progress`, initializes remaining time from the sushi's `time_to_make`.
  - `resumed` → sets `in-progress`, continues from stored remaining time.
- Pause interrupts the cooking thread; resume re-queues with priority.
- Cancel removes the order from the queue or interrupts an active cook, then marks it `cancelled`.
