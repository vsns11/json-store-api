# JSON Store API

Stores test-scenario profiles in PostgreSQL — a profile being a named set of inputs a scenario runs
with, held in a native `jsonb` column. Stateless Spring Boot service, built to run as several replicas
behind a load balancer.

The web client lives in its own repository (`json-store-web`) and talks to this over HTTP. Nothing here
depends on it.

```
src/main/java/com/nest/jsonstore/
├── profile/    entity, repository, service, controller, DTOs
├── security/   LDAP sign-in, token issuing, authorisation rules
├── template/   the catalogue of input fragments the composer merges
├── config/     limits, request-id filter, ETag filter
└── error/      one error shape for the whole API
src/main/resources/
├── db/migration/       Flyway schema
├── ldap/users.ldif     test directory used outside production
└── templates/          the fragment catalogue
chart/                  Helm chart: Deployment, Service, Route or Ingress, HPA, PDB
```

## Getting started

### What you need

| Path | Needs |
| --- | --- |
| With Docker (recommended) | Docker Desktop, or Docker Engine with Compose v2 |
| Without Docker | JDK 21 or newer, PostgreSQL 14 or newer. Maven comes with the repo (`./mvnw`) |

### With Docker

Brings up PostgreSQL, an OpenLDAP directory to sign in against, and the API.

```bash
git clone <this-repo> json-store-api
cd json-store-api
cp .env.example .env      # then set DB_PASSWORD and JWT_SECRET
docker compose up -d --build
```

Give it a minute on the first run — it builds the image and downloads PostgreSQL and OpenLDAP. Check it
came up:

```bash
docker compose ps                       # three services, all healthy
curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}'
```

A token in the response means the database, the directory and the API are all talking to each other.
The store starts empty: the container runs the `prod` profile, which never seeds anything.

Stop it with `docker compose down`, or `docker compose down -v` to throw the database away too.

### Without Docker

An in-process LDAP server starts automatically outside the `prod` profile, so only PostgreSQL is needed:

```bash
createdb jsonstore
./mvnw spring-boot:run
```

It defaults to `localhost:5432/jsonstore` with your operating-system username and an empty password,
which is what a stock Homebrew PostgreSQL gives you, and seeds three example profiles into an empty
database. Override any of it with the environment variables below.

### Signing in

Both paths give you the same two accounts:

| User | Password | Groups | Can delete |
| --- | --- | --- | --- |
| `alice` | `secret` | admins, developers | yes |
| `bob` | `secret` | developers | no |

### The web client

The browser app is a separate repository, `json-store-web`. Start this API first, then follow that
repository's README; in development it proxies to `localhost:8080`.

### If something does not start

| Symptom | Cause and fix |
| --- | --- |
| `port is already allocated` | Something else holds 5432, 8080 or 389. Set `DB_PORT`, `API_PORT` or `LDAP_PORT` in `.env` |
| `Cannot connect to the Docker daemon` | Docker Desktop is not running |
| `release version 21 not supported` | An older JDK is first on the path: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS |
| `password authentication failed` | `DB_USER`/`DB_PASSWORD` do not match the database. With a local PostgreSQL, leave both unset to use your own account |
| `Validate failed: migration checksum mismatch` | A migration changed after being applied. In development, `docker compose down -v` (or `dropdb jsonstore && createdb jsonstore`) and start again |
| Sign-in returns 401 for a user you know exists | `LDAP_USER_DN_PATTERNS` does not match where users live in your directory |

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
| `LDAP_URL` | embedded server | Required under `prod` |
| `LDAP_BASE` | `dc=example,dc=com` | Root the DNs below are relative to |
| `LDAP_MANAGER_DN` `LDAP_MANAGER_PASSWORD` | empty | Account used for group lookups |
| `LDAP_USER_DN_PATTERNS` | `uid={0},ou=people` | Leave empty to search instead |
| `LDAP_USER_SEARCH_BASE` `LDAP_USER_SEARCH_FILTER` | empty, `(uid={0})` | Used when no DN pattern is set |
| `LDAP_GROUP_SEARCH_BASE` `LDAP_GROUP_SEARCH_FILTER` | `ou=groups`, `(member={0})` | Membership becomes a role |
| `JWT_SECRET` | dev default | Required under `prod`; at least 32 characters |
| `JWT_TTL` | `PT8H` | How long a sign-in lasts |
| `SEED_EXAMPLES` | `true` | Example profiles for an empty DB; ignored under `prod` |

## API

All endpoints need a bearer token except `POST /api/auth/login`.

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/auth/login` | `{username, password}` — binds to LDAP, returns a token and roles |
| `GET` | `/api/auth/me` | Who the token belongs to |
| `GET` | `/api/templates` | The catalogue of input fragments the composer merges |
| `GET` | `/api/profiles` | `search`, `page`, `size`, `sort`, `direction`; returns summaries |
| `GET` | `/api/profiles/stats` | Profile count, total input bytes, last change |
| `GET` | `/api/profiles/{id}` | One profile including its inputs |
| `POST` | `/api/profiles` | Create · `201` with the stored profile |
| `PUT` | `/api/profiles/{id}` | Replace name, description, tags and inputs |
| `DELETE` | `/api/profiles/{id}` | `204` — requires the admins group |

```bash
curl -s localhost:8080/api/profiles -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Checkout — gift card","tags":["checkout"],
       "payload":{"scenario":"checkout","payment":{"method":"gift-card"},"expected":{"status":"paid"}}}'
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

Because the inputs are `jsonb`, you can query inside them from SQL — which profiles expect a
particular outcome, for instance:

```sql
select name, payload -> 'expected' ->> 'status' as expected_status
from profile
where payload @> '{"scenario": "checkout"}';
```

## Deploying with Helm

The chart in `chart/` deploys the API to OpenShift or plain Kubernetes. Images come from your CI;
the chart only deploys them.

```bash
helm upgrade --install json-store-api ./chart \
  --namespace json-store --create-namespace \
  --set image.repository=registry.example.com/json-store-api \
  --set image.tag=1.0.0 \
  --set existingSecret=json-store-credentials \
  --set route.enabled=true --set route.host=json-store.apps.example.com
```

Create the secret first, so no credential is ever written into a values file:

```bash
kubectl create secret generic json-store-credentials -n json-store \
  --from-literal=DB_USER=jsonstore \
  --from-literal=DB_PASSWORD='…' \
  --from-literal=LDAP_MANAGER_DN='cn=service-account,ou=services,dc=example,dc=com' \
  --from-literal=LDAP_MANAGER_PASSWORD='…' \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)"
```

Everything else lives in `chart/values.yaml`, which is commented; the settings that usually change are
`image.repository`, the entries under `config` (database host, LDAP URL and DN patterns, CORS origin)
and whether you want a `route` (OpenShift) or an `ingress` (Kubernetes).

See what a release will contain before applying it:

```bash
helm template json-store-api ./chart --set route.enabled=true --set route.host=… | less
```

If the web app's Route already forwards `/api` to this Service, leave `route.enabled=false` and
`CORS_ORIGINS` empty — same origin means no CORS and one certificate.

The image runs as an arbitrary UID with GID 0, which is what the `restricted-v2` SCC assigns, listens
on 8080 and needs no privileged port, and runs with a read-only root filesystem (the chart mounts an
`emptyDir` at `/tmp` for Tomcat).

Probes use the management port, which no Service or Route exposes:

| Endpoint | Purpose |
| --- | --- |
| `:8081/actuator/health/liveness` | Liveness probe |
| `:8081/actuator/health/readiness` | Readiness probe — includes the database |
| `:8081/actuator/prometheus` | Metrics (JVM, HTTP, Hikari pool) |

Every request gets an `X-Request-Id` — reusing the router's if present — echoed back and printed in
every log line for that request.

## Building the image

```bash
docker build -t json-store-api:1.0.0 .
```

Both base images are build arguments, so an internal image can be used instead — including an OpenJDK
image that already contains Maven:

```bash
docker build -t json-store-api:1.0.0 \
  --build-arg BUILD_IMAGE=registry.example.com/openjdk-maven:21 \
  --build-arg RUNTIME_IMAGE=registry.example.com/openjdk:21-jre .
```

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
integration test runs the real stack against a PostgreSQL started by Testcontainers and the in-process
directory, checking sign-in, the roles that come from LDAP groups (bob cannot delete, alice can), the
migrations, the `jsonb` mapping, input-inclusive search, the size limit and the template catalogue —
so that one needs a Docker daemon.

## Template catalogue

`GET /api/templates` returns `src/main/resources/templates/catalog.json`: fragments grouped into a
required scenario and optional customer, payment, delivery and expectation modules, each with the fields
it needs and a body containing `${field}` placeholders. The browser merges the chosen fragments —
objects deeply, lists by appending — substitutes the values, and stores the result as one profile. A
string that is exactly one placeholder keeps the field's type, so `"quantity": "${quantity}"` is stored
as a number.

Each field declares a `type`, which decides the control the browser draws: `text`, `textarea`, `number`,
`range`, `date`, `select`, `radio`, `switch`, `checkbox`, `checkboxes` or `tags`. Together with `label`,
`default`, `required`, `help` and — where it applies — `options`, `min`, `max` and `step`, that is the
whole vocabulary. The web repository's README lists what each one stores.

A composed profile keeps the selection it was built from in its `template` column — which fragment was
chosen in each group, and what was typed into their fields — so the browser can offer the same form
again when the profile is edited. Profiles written by hand have no template, and the field is simply
absent from their responses.

Editing the catalogue is a config change, not a code change; a malformed catalogue fails startup rather
than a user's first click.
