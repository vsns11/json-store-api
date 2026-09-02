# JSON Store API

Stores JSON documents in PostgreSQL, in a native `jsonb` column. Stateless Spring Boot service, built to
run as several replicas behind a load balancer.

The web client lives in its own repository (`json-store-web`) and talks to this over HTTP. Nothing here
depends on it.

```
src/main/java/com/nest/jsonstore/
├── document/   entity, repository, service, controller, DTOs
├── config/     CORS, limits, request-id filter, ETag filter
└── error/      one error shape for the whole API
src/main/resources/db/migration/   Flyway schema
openshift/                          Deployment, Service, Route, HPA, BuildConfig
```

## Run it

**With Docker** — brings up PostgreSQL too:

```bash
cp .env.example .env   # then edit the password
docker compose up -d --build
```

**Locally** — needs a PostgreSQL and JDK 21+:

```bash
createdb jsonstore && ./mvnw spring-boot:run
```

It defaults to `localhost:5432/jsonstore` with your OS username and an empty password, which is what a
stock Homebrew PostgreSQL gives you, and seeds three example documents into an empty database (never
under the `prod` profile).

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `DB_HOST` `DB_PORT` `DB_NAME` | `localhost` `5432` `jsonstore` | |
| `DB_USER` `DB_PASSWORD` | OS username, empty | Required under the `prod` profile |
| `DB_POOL_MAX` `DB_POOL_MIN` | `16` `4` | Per instance — see scaling below |
| `SERVER_PORT` `MANAGEMENT_PORT` | `8080` `8081` | Actuator listens on its own port |
| `CORS_ORIGINS` | `http://localhost:5173,…` | Only needed when the UI is on another origin |
| `MAX_PAYLOAD_BYTES` `MAX_PAGE_SIZE` | `1048576` `100` | Request guard rails |
| `TOMCAT_MAX_THREADS` `TOMCAT_MAX_CONNECTIONS` | `200` `10000` | |
| `SPRING_PROFILES_ACTIVE` | — | Set to `prod` in production |
| `SEED_EXAMPLES` | `true` | Example documents for an empty DB; ignored under `prod` |

## API

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/documents` | `search`, `page`, `size`, `sort`, `direction`; returns summaries |
| `GET` | `/api/documents/stats` | Document count, total bytes, last write |
| `GET` | `/api/documents/{id}` | One document including its payload |
| `POST` | `/api/documents` | Create · `201` with the stored document |
| `PUT` | `/api/documents/{id}` | Replace name, description, tags and payload |
| `DELETE` | `/api/documents/{id}` | `204` |

```bash
curl -s localhost:8080/api/documents -H 'Content-Type: application/json' \
  -d '{"name":"Hello","tags":["demo"],"payload":{"greeting":"hi","items":[1,2,3]}}'
```

Errors always come back in one shape, and JSON syntax errors carry the position that broke:

```json
{
  "timestamp": "2026-09-02T04:49:03.772813Z",
  "status": 400,
  "error": "Invalid JSON",
  "message": "Unexpected character ('}' (code 125)): expected a value",
  "location": { "line": 1, "column": 28 }
}
```

Because the payload is `jsonb`, you can query inside it from SQL:

```sql
select name, payload -> 'rollout' ->> 'percentage' as pct
from json_document
where payload @> '{"checkout.newFlow": true}';
```

## Deploying to OpenShift

The image runs as an arbitrary UID with GID 0, which is what the `restricted-v2` SCC assigns — so no
`anyuid` exception is needed. It listens on 8080, never binds a privileged port, and runs fine with a
read-only root filesystem (only `/tmp` is mounted writable).

```bash
oc new-project json-store

# Either let OpenShift build the image from git…
oc apply -f openshift/build.yaml
oc start-build json-store-api --follow

# …or push your own image and skip build.yaml.

oc apply -f openshift/config.yaml       # edit the secret first
oc apply -f openshift/deployment.yaml
oc apply -f openshift/route.yaml        # only if the API needs its own hostname
```

If the web app's Route already forwards `/api` to this Service, skip `route.yaml` — same-origin means no
CORS and one certificate. Otherwise set `CORS_ORIGINS` to the web app's URL.

Probes use the management port, which is not exposed through any Service or Route:

| Endpoint | Purpose |
| --- | --- |
| `:8081/actuator/health/liveness` | Liveness probe |
| `:8081/actuator/health/readiness` | Readiness probe — includes the database |
| `:8081/actuator/prometheus` | Metrics (JVM, HTTP, Hikari pool) |

Every request gets an `X-Request-Id` — reusing the router's if present — echoed back and printed in
every log line for that request.

## Scaling

Stateless, so throughput scales with replicas. What actually needs attention as you add them:

- **Connections, not CPU, are the first ceiling.** Total connections = replicas × `DB_POOL_MAX`. Keep
  that under PostgreSQL's `max_connections`, or put PgBouncer in front in transaction pooling mode.
- **Reads dominate.** Every list and search is a read-only transaction and can go to read replicas.
- **Search is indexed.** The payload has a `jsonb_path_ops` GIN index for containment queries. Free-text
  search uses `ILIKE` across name, description, tags and payload text, which holds up into the hundreds
  of thousands of rows — past that, move to a `tsvector` column with its own GIN index.
- **Deep pagination degrades.** Offset paging is fine for browsing; for very deep pages switch the list
  query to keyset pagination on `(updated_at, id)`.
- **Graceful shutdown** drains for 25s and the pod sleeps 5s before it starts, so rolling deploys drop
  no requests.
- **Migrations** run on startup and are serialised by Flyway's schema lock, so concurrent replicas are
  safe. For stricter change control, run them as a release step and set `FLYWAY_ENABLED=false`.

## Tests

```bash
./mvnw verify
```

A controller slice covers validation, malformed-JSON reporting and unknown ids with no database. An
integration test runs the real stack against a PostgreSQL started by Testcontainers, checking the
migrations, the `jsonb` mapping, payload-inclusive search and the size limit — so that one needs a
Docker daemon.
