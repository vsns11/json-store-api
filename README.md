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

The database is yours to run. Nothing in this repository starts a PostgreSQL or owns your data — the
API connects to one you already have.

### What you need

| | |
| --- | --- |
| PostgreSQL 14 or newer | running locally, or anywhere you can reach |
| JDK 21 or newer | Maven comes with the repo (`./mvnw`) |
| Docker | only if you want to run the API in a container |

### Running it

```bash
createdb jsonstore
./mvnw spring-boot:run
```

That is the whole thing. It connects to `localhost:5432/jsonstore` as your operating-system user with
no password, which is what a stock Homebrew or apt PostgreSQL gives you, applies its migrations, and
starts an in-process LDAP server so there is a directory to sign in against. An empty database also
gets four example profiles, composed from the template catalogue so they match what the form builds.

Check it came up:

```bash
curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}'
```

Sign-in answers with the token and who it belongs to. Send it back as `Authorization: Bearer <accessToken>`.

```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 28800,
  "expiresAt": "2026-01-01T09:00:00Z",
  "user": { "username": "alice", "roles": ["ADMINS", "DEVELOPERS"] }
}
```

Override any of the connection details with the environment variables below — a different host, a real
username and password, a different database name.

### Running the API in a container instead

`docker compose up -d --build` starts the API and an OpenLDAP to sign in against. It still expects a
database you provide: `DB_HOST` defaults to `host.docker.internal`, which is the machine Docker is
running on, and that PostgreSQL has to accept connections from outside localhost (`listen_addresses`
and a `pg_hba.conf` entry). Running the API directly, as above, avoids all of that.

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
| `Connection refused` to 5432 | PostgreSQL is not running: `brew services start postgresql@14`, or `pg_isready` to check |
| `database "jsonstore" does not exist` | `createdb jsonstore` |
| `password authentication failed` | `DB_USER`/`DB_PASSWORD` do not match. Leave both unset to connect as your own account |
| `release version 21 not supported` | An older JDK is first on the path: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS |
| `Validate failed: migration checksum mismatch` | An applied migration was edited. In development, `dropdb jsonstore && createdb jsonstore` and start again |
| Sign-in returns 401 for a user you know exists | `LDAP_USER_DN_PATTERNS` does not match where users live in your directory |
| Port 8080 already taken | `SERVER_PORT=8081 ./mvnw spring-boot:run` |

## Looking at the data

The database is a plain PostgreSQL, so anything that speaks it will do.

It is your own PostgreSQL, so connect to it however you normally would:

```bash
psql -d jsonstore

\dt                        -- the tables: profile, flyway_schema_history
\d profile                 -- its columns and indexes
select name, jsonb_object_keys(payload) as document from profile;
select jsonb_pretty(payload -> 'orders-api') from profile where name = '…';
```

In a GUI client, the connection is `localhost:5432`, database `jsonstore`, your own user — the same
details the API uses.

Two queries worth knowing, because they use the `jsonb` column rather than working around it:

```sql
-- every profile that feeds the payments system with a declined card
select name from profile where payload -> 'payments' @> '{"payment": {"outcome": "declined"}}';

-- what each profile expects to happen
select name, payload -> 'assertions' ->> 'status' as expected from profile;
```

## API documentation

The running service describes itself: <http://localhost:8080/swagger-ui.html> for the interactive
viewer, <http://localhost:8080/v3/api-docs> for the OpenAPI description a client generator can read.
Both are reachable without a token — they describe the API and expose no data.

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `DB_HOST` `DB_PORT` `DB_NAME` | `localhost` `5432` `jsonstore` | |
| `DB_USER` `DB_PASSWORD` | OS username, empty | Required under the `prod` profile |
| `DB_POOL_MAX` `DB_POOL_MIN` | `16` `4` | Per instance — see scaling below |
| `SERVER_PORT` `MANAGEMENT_PORT` | `8080` `8081` | Actuator listens on its own port |
| `CORS_ORIGINS` | `http://localhost:[*],…` | Origins the browser app is served from; an entry may be a pattern |
| `MAX_PAYLOAD_BYTES` `MAX_PAGE_SIZE` | `1048576` `100` | Largest inputs per profile (minified), largest page |
| `MAX_REQUEST_BYTES` | `8388608` | Largest request body at all. Refused on its announced length before it is read, and counted as it is read, so a body sent in chunks stops at the limit too |
| `FLYWAY_ENABLED` | `true` | Whether a starting pod applies migrations; the chart turns it off when its migration Job does that instead |
| `TOMCAT_MAX_THREADS` `TOMCAT_MAX_CONNECTIONS` | `200` `10000` | |
| `SPRING_PROFILES_ACTIVE` | unset, which runs `local` | `local` alone gets the test directory, the development secret and example data. Name any profile, usually `prod`, and all three are off |
| `LDAP_URL` | embedded server under `local` | Required under any other profile |
| `LDAP_BASE` | `dc=example,dc=com` | Root the DNs below are relative to |
| `LDAP_MANAGER_DN` `LDAP_MANAGER_PASSWORD` | empty | Account used for group lookups |
| `LDAP_USER_DN_PATTERNS` | `uid={0},ou=people` | Leave empty to search instead |
| `LDAP_USER_SEARCH_BASE` `LDAP_USER_SEARCH_FILTER` | empty, `(uid={0})` | Used when no DN pattern is set |
| `LDAP_GROUP_SEARCH_BASE` `LDAP_GROUP_SEARCH_FILTER` | `ou=groups`, `(member={0})` | Where the groups below are looked up |
| `ROLE_VIEWER_GROUPS` `ROLE_EDITOR_GROUPS` `ROLE_ADMIN_GROUPS` | empty; `auditors`, `developers`, `admins` under `local` | Directory groups (cn, comma-separated) that may read, also write, and also delete. At least one is required; an account in none is refused at sign-in |
| `LDAP_USERNAME_ATTRIBUTE` | `uid` | The directory's spelling of the username, recorded as author whatever case was typed |
| `LDAP_CONNECT_TIMEOUT_MS` `LDAP_READ_TIMEOUT_MS` | `5000` `10000` | How long a sign-in waits on the directory before answering `503` |
| `LOGIN_MAX_FAILURES` `LOGIN_FAILURE_WINDOW` | `5` `PT5M` | Wrong passwords per username, per replica, before sign-in pauses for that name |
| `JWT_SECRET` | development secret under `local` | Required under any other profile; at least 32 characters. The development secret is refused outside `local`, even if set on purpose |
| `JWT_TTL` | `PT8H` | How long one token lasts; the browser renews it before it runs out |
| `JWT_MAX_SESSION` | `PT24H` | How long a sign-in can be kept alive by renewing, counted from the bind |
| `SEED_EXAMPLES` | `true` | Example profiles for an empty DB; `local` only |

## API

All endpoints need a bearer token except `POST /api/auth/login`. A request without one is answered
`401` with `WWW-Authenticate: Bearer`; a valid token without the right role gets `403`.

Signing in proves who someone is; the groups mapped in `ROLE_*_GROUPS` decide what they may do. A
viewer may read profiles and the catalogue, an editor may also create and change profiles, and an
admin may also delete them and run maintenance. The roles nest, so an admin is also an editor and a
viewer. An account in none of the mapped groups is refused at sign-in with `403` and no token. Both carry
the same JSON error shape as everything else. The rule covers every path, not only `/api`: the only
things readable without a token are the OpenAPI description and its viewer.

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/auth/login` | `{username, password}` — binds to LDAP, returns the token below · `403` in no mapped group, `429` after repeated wrong passwords (with `Retry-After`), `503` when the directory cannot be reached |
| `POST` | `/api/auth/refresh` | A new token for a caller who already holds a valid one, until `JWT_MAX_SESSION` is up |
| `GET` | `/api/auth/me` | Who the token belongs to |
| `GET` | `/api/templates` | The catalogue of input fragments the composer merges |
| `GET` | `/api/profiles` | `search`, `tag`, `page`, `size`, `sort`, `direction`; returns summaries |
| `GET` | `/api/profiles/stats` | Profile count, total input bytes, last change |
| `GET` | `/api/profiles/{id}` | One profile including its inputs, and who wrote it |
| `POST` | `/api/profiles` | Create from templates · `201` with the stored profile, inputs built by the server |
| `PUT` | `/api/profiles/{id}` | Replace name, description and tags; with `template`, rebuild the inputs too · needs `If-Match` |
| `DELETE` | `/api/profiles/{id}` | `204` — requires the admins group · needs `If-Match` |
| `POST` | `/api/admin/profiles/recompose` | Rebuild stored inputs from their own templates; a dry run unless `apply=true` — admins only |

A profile's `ETag` is its version, sent with every read and save, and in each list item as
`version`. Changing or deleting a profile must send it back as `If-Match`, so two people editing the
same profile cannot silently overwrite each other: without the header the answer is `428`, and when
someone else has saved since, `412` naming who. `If-Match: *` overwrites whatever is stored, on
purpose.

A write carries what was chosen and typed, never the inputs themselves. The server checks the
selection and every value against the catalogue, composes the documents, and stores its own result,
so nothing reaches the database that the form could not have built. Values left out take the
catalogue's defaults, and the stored `template` records them all:

```bash
curl -s localhost:8090/api/profiles -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Checkout — declined card","tags":["checkout"],
       "template":{"selection":{"scenario":"checkout","payment":"card-declined"},
                   "values":{"orderRef":"ORD-777","quantity":2}}}'
```

Inputs the catalogue would not build are refused with `422`, listing every problem at once and
naming where each one is, so a form can mark them all:

```json
{
  "status": 422,
  "error": "Invalid inputs",
  "message": "Choose a template for Scenario",
  "fieldErrors": [
    { "field": "template.selection.scenario", "message": "Choose a template for Scenario" },
    { "field": "template.values.quantity", "message": "Quantity must be at most 99" }
  ]
}
```

The database holds the same line: a check refuses any stored inputs that are not an object of named
documents, or that still contain a placeholder. Migration `V8` adds it `NOT VALID`, so a database
that already holds a bad row still starts. Repair such rows, then extend the check to them:

```bash
curl -s -X POST localhost:8090/api/admin/profiles/recompose -H "Authorization: Bearer $TOKEN"              # report
curl -s -X POST 'localhost:8090/api/admin/profiles/recompose?apply=true' -H "Authorization: Bearer $TOKEN" # repair
```

```sql
alter table profile validate constraint profile_payload_is_named_documents;
alter table profile validate constraint profile_payload_has_no_placeholder;
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

### A TMF702 example to start from

`src/main/resources/templates/tmf702-catalog.json` is a second catalogue modelling a TMF702
Resource inventory: six resource types (ONU, OLT chassis, OLT port, optical splitter, service VLAN,
IP subnet), three sites, three related-party kinds and five lifecycle states. Point the service at
it instead of the default and the form builds Resources rather than test scenarios.

```bash
APP_TEMPLATES_CATALOG=classpath:templates/tmf702-catalog.json ./mvnw spring-boot:run
```

Any path a `Resource` can read works, so a workspace keeps its own catalogue outside the image:
`file:/etc/json-store/catalog.json`, or a mounted ConfigMap.

Two things that catalogue is written to demonstrate. A placeholder standing alone keeps the value's
own type, so `"value": "${portCount}"` stores `8` and not `"8"` — which is what keeps an
`IntegerCharacteristic` honest. And fragments merge: the resource type, the site, the party and the
lifecycle state each contribute part of one Resource, with objects merged key by key and arrays
appended, so `resourceCharacteristic` ends up carrying entries from two fragments at once. Names,
serial numbers, MAC addresses, addresses and party ids are `example`s rather than defaults, so each
resource has to be given its own. Under the `local` profile an empty database is given five example resources written for this
catalogue — an ONU at a subscriber address, an OLT chassis and one of its ports, a planned service VLAN
and a reserved subnet — from `src/main/resources/examples/profiles.json`.

The limit worth knowing before writing your own: **a fragment has no conditionals and always writes
its whole body**. A field left blank still emits its block, which is how you get a `relatedParty`
with an empty `id`. Anything genuinely optional belongs in its own group, so it can be left unset.

Every profile records who wrote it. `createdBy` and `updatedBy` are directory usernames taken from
the verified token, never from the request body, so a client cannot claim to be another account; a
`updatedBy` sent in the body is ignored. Both are null on profiles stored before the columns existed
and on the seeded examples, because a name that was never recorded must not be rendered as one that
was. `updatedBy` is on the list response too, so a table can show it without opening each row.

Because the inputs are `jsonb`, you can query inside them from SQL — which profiles expect a
particular outcome, for instance:

```sql
select name, payload -> 'assertions' ->> 'status' as expected_status
from profile
where payload -> 'orders-api' @> '{"scenario": "checkout"}';
```

## Deploying with Helm

Moving both apps into an office environment — your registry, database, directory and cluster — has
its own step-by-step checklist in [docs/office-migration.md](docs/office-migration.md).

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

The chart refuses to render a release that would start in a bad state rather than letting pods
crash-loop afterwards: `image.tag` is required, so a node never quietly keeps running an old image
under a moving tag; the five credentials are required unless `existingSecret` names a Secret you
manage; and a release whose connection pools could outgrow the database is refused, because
`DB_POOL_MAX` × (the most replicas + one surge pod) must fit inside `database.maxConnections` less
`database.reservedConnections`, counting two more for the migration Job. At least one of
`config.ROLE_VIEWER_GROUPS`, `ROLE_EDITOR_GROUPS` and `ROLE_ADMIN_GROUPS` is required too, because without
one nobody could sign in. Changing a chart-managed secret rolls the pods.

Migrations run as a Helm hook before anything else in an install or upgrade: a Job starts the release's
own image with `--migrate-only`, which applies them and exits without starting the API, and the
Deployment's pods then run with `FLYWAY_ENABLED=false` and only check that the schema matches. A
migration that fails stops the release there, with the old pods still serving, instead of crash-looping
the new ones; `kubectl logs job/<release>-json-store-api-migrate` says why. On a first install the Job
reads the database credentials from `existingSecret`, or from a short-lived Secret of its own, because
the chart's Secret does not exist yet when the hook runs. Set `migrations.job.enabled=false` to go back
to every starting pod migrating.

Sign-in gets a second Ingress or Route of its own for `/api/auth/login` (`ingress.signInRateLimit`,
`route.signInRateLimit`), so the controller can limit how often one client address tries it. That is on
top of the API's own pause after repeated wrong passwords for one username, which counts per replica.

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

The build needs nothing from Docker Hub beyond the two base images — there is no `# syntax=` directive
pulling a frontend — so it works behind a proxy that only mirrors what you have allowed.

Both base images are build arguments, so an internal image can be used instead — including an OpenJDK
image that already contains Maven:

```bash
docker build -t json-store-api:1.0.0 \
  --build-arg BUILD_IMAGE=registry.example.com/openjdk-maven:21 \
  --build-arg RUNTIME_IMAGE=registry.example.com/openjdk:21-jre .
```

## Scaling

Stateless, so throughput scales with replicas. These are measured numbers, not estimates: 100,000
profiles (95 MB of jsonb) on a laptop container, timed end to end through the API.

| Request | Time |
| --- | --- |
| First page of the list | 10 ms |
| One profile by id | 12 ms |
| Search matching one row | 8 ms |
| Search matching 200 rows | 41 ms |
| Search matching 33,000 rows | 144 ms |
| Sort by name or size | 18 ms |
| Page 5,000 (offset 75,000) | 19 ms |
| Store statistics | 25 ms |

What matters as the store or the traffic grows:

- **Filtering by tag is exact and cheap**, unlike the free-text search: it is a containment test on
  the tags array, answered by a GIN index (migration V5).
- **Search is the one query with real cost.** It reads the name, description, tags and inputs, and is
  served by a trigram index (migration V4). Without it, a search matching a single row took 455 ms at
  100k because every row had to be read; with it, 8 ms. Broad terms are still linear in the number of
  matches — 144 ms for a term matching a third of the table — because the total has to be counted for
  the page footer. Terms shorter than three characters cannot use a trigram index and fall back to a
  scan (~140 ms).
- **The index costs writes and disk.** About 33 MB per 100k profiles, and every insert or update
  maintains it. This store is read-heavy, so that is the right trade; if it ever is not, drop
  `idx_profile_search` and searches go back to scanning.
- **Connections, not CPU, are the first ceiling.** The chart checks this for you and refuses a release that could exceed it. Total connections = replicas × `DB_POOL_MAX`. Keep
  that under PostgreSQL's `max_connections`, or put PgBouncer in front in transaction pooling mode.
- **Reads dominate.** Every list and search is a read-only transaction and can go to read replicas.
- **Offset paging holds up further than expected** — page 5,000 costs 19 ms. It grows linearly with the
  offset, so if you ever expose millions of rows, move the list query to keyset pagination on
  `(updated_at, id)`.
- **Graceful shutdown** drains for 25s and the pod sleeps 5s before it starts, so rolling deploys drop
  no requests.
- **Migrations** run once per release, in the chart's migration Job, before the new pods start. Without
  the chart they run on startup instead, serialised by Flyway's schema lock, so concurrent replicas are
  still safe. The same runner works anywhere: `java -jar app.jar --migrate-only`.
- **The list reads no inputs.** A page of profiles is one query for the columns the table shows, plus
  the document names and a 180-character preview worked out in PostgreSQL, so a page costs the same
  whether each profile's inputs are a kilobyte or a megabyte. Ties in the sort column are broken by id,
  so paging never shows a profile twice or skips one.

The browser side does not care how many profiles exist: it asks for 15 at a time and renders about 500
DOM nodes whether the store holds 7 rows or 100,000.

## Tests

```bash
./mvnw verify
```

A controller slice covers validation, malformed-JSON reporting and unknown ids with no database. An
integration test runs the real stack against a PostgreSQL started by Testcontainers and the in-process
directory, checking sign-in and the roles that come from LDAP groups (alice is an admin, bob an editor,
dave a viewer, and carol is refused), repeated wrong passwords, versions and If-Match, the inputs the
server builds and refuses, the database's own checks, rebuilding stale inputs, the `jsonb` mapping,
input-inclusive search, paging, the size limits and the template catalogue. A second test runs the
`--migrate-only` runner against its own PostgreSQL. Both need a Docker daemon.

## Template catalogue

`GET /api/templates` returns `src/main/resources/templates/catalog.json`: fragments grouped into a
scenario and optional customer, payment, delivery and expectation modules, each naming the system it
feeds with `target`, each with the fields
it needs and a body containing `${field}` placeholders. The server merges the chosen fragments —
objects deeply, lists by appending — substitutes the values, and stores the result as one profile; the
browser runs the same merge only to preview it. A string that is exactly one placeholder keeps the
field's type, so `"quantity": "${quantity}"` is stored as a number.

A fragment writes into **every system it names**, so one scenario can already produce an API request,
an event on the bus and the assertions for the run:

```json
{
  "id": "checkout", "group": "scenario", "name": "Checkout",
  "fields": [ { "key": "orderRef", "label": "Order reference", "type": "text", "default": "ORD-10042" } ],
  "documents": {
    "orders-api":   { "method": "POST", "path": "/v2/orders", "body": { "reference": "${orderRef}" } },
    "kafka-events": { "topic": "orders.events", "key": "${orderRef}" },
    "assertions":   { "order": { "reference": "${orderRef}" } }
  }
}
```

Fields are shared by key across fragments, so a value typed once reaches every document that mentions
it — the SKU from the scenario ends up in the inventory reservation without being asked for twice.
The catalogue that ships covers six systems: `orders-api`, `payments`, `inventory`, `kafka-events`,
`notifications` and `assertions`.

Each field declares a `type`, which decides the control the browser draws: `text`, `textarea`, `number`,
`range`, `date`, `select`, `radio`, `switch`, `checkbox`, `checkboxes` or `tags`. Together with `label`,
`default`, `example`, `required`, `pattern`, `help` and — where it applies — `options`, `min`, `max` and
`step`, that is the whole vocabulary. The web repository's README lists what each one stores.

A `default` is stored unless someone changes it; an `example` is a hint in the empty box and is never
stored. Anything that identifies one particular thing — a serial number, a MAC address, a customer id —
should be an `example`, so no profile is saved carrying the sample identity of equipment that does not
exist. A save is checked against these declarations: `required`, `min`/`max`, a real date, one of the
`options`, and the whole value matching `pattern`.

The catalogue is checked at startup too, and refuses to load with a placeholder no field declares, an
unknown field type, a choice with no options, a field declared twice in one fragment, or a `pattern`
that is not a valid regular expression.

A composed profile keeps the selection it was built from in its `template` column — which fragment was
chosen in each group, and what was typed into their fields — so the browser can offer the same form
again when the profile is edited, and so an administrator can rebuild stored inputs after a catalogue
fix. Profiles stored before templates were recorded have none, and the field is absent from their
responses; they can be renamed and retagged, but their inputs can only be rebuilt by choosing templates.

Editing the catalogue is a config change, not a code change; a malformed catalogue fails startup rather
than a user's first click.
