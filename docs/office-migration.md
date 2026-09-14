# Moving JSON Store into the office

A step-by-step checklist for taking the two apps — `json-store-api` and `json-store-web` — from a laptop
into an office environment: your Git server, your registry, your PostgreSQL, your directory, your
cluster. Work down it in order; each section only needs the ones above it. Tick the boxes as you go.

Nothing here assumes a particular vendor. Where offices usually differ — OpenShift or plain Kubernetes,
Active Directory or OpenLDAP, Nexus or Artifactory — both paths are given.

---

## 0. Collect what you will need

Fill this in before starting. Most of these come from other teams, and waiting on them is usually the
slowest part.

| What | Who usually has it | Value |
| --- | --- | --- |
| Git server and the two repository URLs | Platform / DevOps | |
| Container registry, and a robot account that can push | Platform | |
| Maven and npm mirrors (Nexus, Artifactory) | Platform | |
| Mirrored base images: Maven + JDK 21, JRE 21, Node 22, nginx-unprivileged | Platform | |
| Cluster, namespace, and whether it is OpenShift or Kubernetes | Platform | |
| Host name the users will open, e.g. `json-store.apps.corp.example` | Platform / network | |
| TLS: edge termination at the router, or a certificate Secret | Platform | |
| PostgreSQL host, port, database name, application user and password | DBA | |
| PostgreSQL `max_connections`, and how many are already spoken for | DBA | |
| Directory URL (`ldaps://…:636`), base DN, service account DN and password | Identity team | |
| How users are found: DN pattern (OpenLDAP) or search filter (Active Directory) | Identity team | |
| Three directory groups: viewers, editors, admins | Identity team | |
| The corporate root CA certificate, if the directory's certificate is signed by it | Identity / security | |
| Where backups of PostgreSQL go, and who restores them | DBA | |

---

## 1. Bring the code in

- [ ] Merge the `feature/audit-now` branch into `main` in both repositories, if that has not happened
      yet, so you start from one line of history.
- [ ] Create both repositories on the office Git server and push every branch and tag:
  ```bash
  git remote add office https://git.corp.example/test-data/json-store-api.git
  git push office --all && git push office --tags
  ```
  Repeat for `json-store-web`.
- [ ] Update the links between them: the web README points at `github.com/vsns11/json-store-api`.
- [ ] Remove anything that must not leave your machine. Neither repository holds a real secret — the
      local directory's passwords are `secret` and the development JWT secret is refused outside the
      `local` profile — but check any `.env` you created yourself; it is git-ignored and should stay behind.

## 2. Make the builds work behind the office proxy

- [ ] **Maven.** Point `~/.m2/settings.xml` on the build agents at the mirror:
  ```xml
  <settings><mirrors><mirror>
    <id>office</id><mirrorOf>*</mirrorOf>
    <url>https://nexus.corp.example/repository/maven-public/</url>
  </mirror></mirrors></settings>
  ```
  The wrapper downloads Maven itself too: set `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties`
  to the same mirror, or run `mvn` installed on the agent instead of `./mvnw`.
- [ ] **npm.** Add an `.npmrc` beside `package.json` (or on the agent):
  ```
  registry=https://nexus.corp.example/repository/npm-public/
  ```
  Then `npm ci` once and commit nothing but `.npmrc` if it holds no token.
- [ ] **Base images.** Both Dockerfiles take them as build arguments, so nothing needs editing:
  ```bash
  docker build --build-arg BUILD_IMAGE=registry.corp.example/maven:3.9-eclipse-temurin-21 \
               --build-arg RUNTIME_IMAGE=registry.corp.example/eclipse-temurin:21-jre-alpine \
               -t registry.corp.example/test-data/json-store-api:1.0.0 .
  docker build --build-arg BUILD_IMAGE=registry.corp.example/node:22-alpine \
               --build-arg RUNTIME_IMAGE=registry.corp.example/nginx-unprivileged:1.27-alpine \
               -t registry.corp.example/test-data/json-store-web:1.0.0 .
  ```
- [ ] **Tests that start PostgreSQL.** The API's integration tests use Testcontainers, which pulls
      `postgres:16-alpine` and needs Docker on the agent. Behind a proxy, tell it where images come from:
  ```bash
  export TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=registry.corp.example/dockerhub/
  ```
  If the agents have no Docker at all, run `./mvnw verify -DskipTests` in CI and the tests on a
  machine that does — but do run them before each release.

## 3. Set up CI

The `.github/workflows/ci.yml` in each repository is the recipe. If the office uses GitHub Enterprise
with runners that have Docker, it runs as it is. Otherwise, translate the same steps:

- [ ] **API:** `./mvnw -B verify` → `helm lint chart` with stand-in values (copy the command from
      `ci.yml`) → `docker build` → push, tagged with the version or the commit, never `latest`.
- [ ] **Web:** `npm ci` → `npm run lint` → `npm test` → `npm run build` → `helm lint chart --set image.tag=ci`
      → `docker build` → push.
- [ ] Run your image scanner on both images before they are pushed, if the office requires one.

## 4. Prepare PostgreSQL

- [ ] Ask the DBA for a database (e.g. `jsonstore`) and an application user that **owns** it, so the
      migrations can create tables and indexes. Version 14 or newer.
- [ ] Ask the DBA to enable the trigram extension once, because the application user usually may not:
  ```sql
  create extension if not exists pg_trgm;
  ```
  Migration V4 creates the search index with it and fails without it.
- [ ] Work out the connection budget. The chart refuses a release whose pools could exceed it:
      `DB_POOL_MAX × (max replicas + 1) + 2` must fit in `database.maxConnections − database.reservedConnections`.
      With the defaults (pool 8, up to 6 replicas) that is 58 connections.
- [ ] Make sure backups are on, and that someone has restored one at least once. Migrations only go
      forward; a backup is how a release is undone.
- [ ] Open the network path from the cluster to PostgreSQL (port 5432), including any NetworkPolicy.

## 5. Prepare the directory

- [ ] Create three groups and put people in them. Anyone in none of them cannot sign in at all:
  | Group (example name) | Role | May |
  | --- | --- | --- |
  | `json-store-viewers` | VIEWER | read profiles |
  | `json-store-editors` | EDITOR | also create, duplicate, combine and change profiles |
  | `json-store-admins` | ADMIN | also delete profiles and rebuild stored inputs |
- [ ] Ask for a read-only service account the API can search groups with.
- [ ] Decide how users are found, and note the settings:
  | | OpenLDAP-style | Active Directory |
  | --- | --- | --- |
  | `LDAP_USER_DN_PATTERNS` | `uid={0},ou=people` | *(empty)* |
  | `LDAP_USER_SEARCH_BASE` | *(empty)* | `ou=Users` |
  | `LDAP_USER_SEARCH_FILTER` | *(unused)* | `(sAMAccountName={0})` |
  | `LDAP_USERNAME_ATTRIBUTE` | `uid` | `sAMAccountName` |
  | `LDAP_GROUP_SEARCH_BASE` | `ou=groups` | `ou=Groups` |
  | `LDAP_GROUP_SEARCH_FILTER` | `(member={0})` | `(member={0})` |
- [ ] **If the directory's certificate is signed by a corporate CA**, the JVM will not trust it and
      sign-in answers 503. The chart does not mount a trust store yet, so build a thin image on top of
      the released one:
  ```Dockerfile
  FROM registry.corp.example/test-data/json-store-api:1.0.0
  USER root
  COPY corp-root-ca.pem /tmp/corp-root-ca.pem
  RUN keytool -importcert -noprompt -cacerts -storepass changeit -alias corp-root -file /tmp/corp-root-ca.pem \
      && rm /tmp/corp-root-ca.pem
  USER 10001
  ```
- [ ] Open the network path from the cluster to the directory (usually port 636).
- [ ] Test the account and filter from any machine before involving the app:
  ```bash
  ldapsearch -H ldaps://ldap.corp.example:636 -D 'cn=svc-json-store,ou=Services,dc=corp,dc=example' -W \
             -b 'ou=Groups,dc=corp,dc=example' '(member=uid=someone,ou=people,dc=corp,dc=example)' cn
  ```

## 6. Decide the template catalogue

- [ ] Choose: the TMF702 catalogue built into the image (`catalog.builtin=tmf702`), or your own.
- [ ] If your own, check it on a laptop first. The API refuses a catalogue with an undeclared
      placeholder, an unknown field type or a broken `pattern`, and says which:
  ```bash
  APP_TEMPLATES_CATALOG=file:/path/to/office-catalog.json ./mvnw spring-boot:run
  ```
  Remember: identifiers (names, serial numbers, MACs, ids) get an `example`, not a `default`.
- [ ] Put it in a ConfigMap in the namespace:
  ```bash
  kubectl create configmap json-store-catalog -n json-store --from-file=catalog.json=office-catalog.json
  ```

## 7. Create the secret

- [ ] Create it by hand, once, so no credential is ever written into a values file or Git:
  ```bash
  kubectl create secret generic json-store-credentials -n json-store \
    --from-literal=DB_USER=jsonstore \
    --from-literal=DB_PASSWORD='…' \
    --from-literal=LDAP_MANAGER_DN='cn=svc-json-store,ou=Services,dc=corp,dc=example' \
    --from-literal=LDAP_MANAGER_PASSWORD='…' \
    --from-literal=JWT_SECRET="$(openssl rand -base64 48)"
  ```
- [ ] Store the values wherever the office keeps secrets (Vault, a password manager), not in chat.

## 8. Deploy the API

- [ ] Write `office-api-values.yaml` — this file holds no secrets, so it can live in Git:
  ```yaml
  image:
    repository: registry.corp.example/test-data/json-store-api
    tag: "1.0.0"
  existingSecret: json-store-credentials
  config:
    DB_HOST: postgres.corp.example
    DB_NAME: jsonstore
    LDAP_URL: ldaps://ldap.corp.example:636
    LDAP_BASE: dc=corp,dc=example
    LDAP_USER_DN_PATTERNS: uid={0},ou=people
    LDAP_GROUP_SEARCH_BASE: ou=groups
    ROLE_VIEWER_GROUPS: json-store-viewers
    ROLE_EDITOR_GROUPS: json-store-editors
    ROLE_ADMIN_GROUPS: json-store-admins
    CORS_ORIGINS: ""            # empty: the UI and the API share one host
  database:
    maxConnections: 100         # from the DBA
    reservedConnections: 20
  catalog:
    builtin: tmf702             # or: existingConfigMap: json-store-catalog
  route:                        # OpenShift; use `ingress:` on Kubernetes
    enabled: true
    host: json-store.apps.corp.example
  ```
- [ ] Look at what it would create, then install:
  ```bash
  helm template json-store-api ./chart -f office-api-values.yaml | less
  helm upgrade --install json-store-api ./chart -n json-store -f office-api-values.yaml
  ```
- [ ] Watch the migration Job. It runs before the pods start; if it fails, the release stops there:
  ```bash
  kubectl logs -n json-store job/json-store-api-json-store-api-migrate
  ```
- [ ] Wait for the pods to be ready: `kubectl rollout status -n json-store deploy/json-store-api-json-store-api`.

## 9. Deploy the web app

- [ ] Write `office-web-values.yaml`:
  ```yaml
  image:
    repository: registry.corp.example/test-data/json-store-web
    tag: "1.0.0"
  config:
    API_BASE_URL: ""            # same host: the API's Route or Ingress serves /api
    APP_NAME: "Resource Inventory Builder"
    BRAND_MARK: "R"
    ACCENT_COLOR: "#0b6bcb"
  route:
    enabled: true
    host: json-store.apps.corp.example   # the same host as the API
  ```
- [ ] Install it:
  ```bash
  helm upgrade --install json-store-web ./chart -n json-store -f office-web-values.yaml
  ```
- [ ] Sign-in rate limiting: the API chart adds a second Route or Ingress for `/api/auth/login`. Check
      the annotations match your router — HAProxy on OpenShift, ingress-nginx on Kubernetes — and
      adjust `route.signInRateLimit` or `ingress.signInRateLimit` if not.

## 10. Bring existing profiles across (optional)

Skip this if the office starts empty.

- [ ] On the laptop, start the API from this version once, so the local database is migrated and
      checked the same way the office one is.
- [ ] Rebuild anything stored before the server built inputs itself. As an admin:
  ```bash
  curl -s -X POST localhost:8090/api/admin/profiles/recompose -H "Authorization: Bearer $TOKEN"
  curl -s -X POST 'localhost:8090/api/admin/profiles/recompose?apply=true' -H "Authorization: Bearer $TOKEN"
  ```
  Anything reported `invalid` or `not-templated` needs opening and fixing by hand, or leaving behind.
- [ ] Export only the rows, after the office migration Job has created the tables:
  ```bash
  pg_dump --data-only --column-inserts --table=profile jsonstore > profiles.sql
  psql "postgresql://jsonstore@postgres.corp.example/jsonstore" -f profiles.sql
  ```
  The office database refuses a row that still holds an unfilled placeholder; that is the check
  doing its job. Fix the row locally and export again.

## 11. Check it works

- [ ] Health: `kubectl exec` into a pod, or port-forward the management port, and check
      `/actuator/health/readiness` says `UP` — it includes the database.
- [ ] Sign in as someone in each group, and as someone in none:
  - the viewer sees profiles but no New profile, Save or Delete;
  - the editor can create a profile, start one from saved profiles, and save;
  - the admin can delete;
  - the person in no group is told they have no access.
- [ ] Create one real resource through the form, open the Tree view, and hand that JSON to the system
      it is for. That is the check that matters.
- [ ] Open the same profile in two browsers, save in one, then save in the other: the second is asked
      whether to load the first one's version or overwrite it.
- [ ] Once any imported rows are repaired, make the database checks cover old rows too:
  ```sql
  alter table profile validate constraint profile_payload_is_named_documents;
  alter table profile validate constraint profile_payload_has_no_placeholder;
  ```

## 12. Hand over

- [ ] Write down who owns what: the namespace, the database, the three groups, the catalogue.
- [ ] Point monitoring at the pods: they expose Prometheus metrics on `:8081/actuator/prometheus`, and
      every log line carries the `X-Request-Id` of the request it belongs to.
- [ ] Agree how the catalogue changes: edit the ConfigMap, then set `catalog.revision` to any new value in
      the values file and `helm upgrade`, so the pods restart and check it.
- [ ] Agree how releases go: build a new tag → `helm upgrade` both charts with it → the migration Job runs
      first. To undo one, `helm rollback`; if a migration had already run, restore the database backup
      taken before it.
- [ ] Rotating `JWT_SECRET` signs everyone out, which is fine — do it if the secret is ever exposed.
