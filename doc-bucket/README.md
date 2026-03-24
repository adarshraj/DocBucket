# doc-bucket — document storage service

**Kotlin + Quarkus** backend that stores document bytes in any **S3-compatible** object store and metadata in a relational database (**SQLite** by default; **PostgreSQL** in production). Acts as a single controlled gateway so no application ever touches the storage credentials directly.

---

## Table of contents

1. [Why this service exists](#why-this-service-exists)
2. [API reference](#api-reference)
3. [Feature guide](#feature-guide)
   - [Document upload](#document-upload)
   - [Document retrieval and streaming](#document-retrieval-and-streaming)
   - [Soft delete and hard delete](#soft-delete-and-hard-delete)
   - [Presigned URLs](#presigned-urls)
   - [Multi-tenancy](#multi-tenancy)
   - [Authentication — per-app API keys](#authentication--per-app-api-keys)
   - [Authentication — dev-open mode](#authentication--dev-open-mode)
   - [API client management](#api-client-management)
   - [Rate limiting](#rate-limiting)
   - [Soft-delete purge job](#soft-delete-purge-job)
   - [Upload restrictions](#upload-restrictions)
   - [Observability](#observability)
   - [CORS](#cors)
4. [Configuration reference](#configuration-reference)
5. [Local development](#local-development)
6. [Production deployment](#production-deployment)

---

## Why this service exists

Storing files naively — giving every application its own S3 credentials, or letting them write directly to a shared bucket — creates several problems:

- **No access control between apps.** Any app that has the bucket credentials can read or delete another app's files.
- **No consistent metadata.** There is nowhere to record who uploaded a file, when, or which user it belongs to.
- **No audit trail.** Direct S3 operations bypass any business logic.
- **Credentials sprawl.** Every new app needs storage credentials, which must be rotated individually.

DocBucket solves this by acting as the single gateway to storage. Applications authenticate with short-lived per-app API keys. The service enforces tenant isolation, validates uploads, and keeps a queryable metadata record for every document. The underlying S3 credentials never leave the service.

---

## API reference

### Documents

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/documents/upload` | API key | Upload document bytes |
| `GET` | `/api/documents` | API key | List documents with pagination and filters |
| `GET` | `/api/documents/{id}` | API key | Get document metadata |
| `GET` | `/api/documents/{id}/content` | API key | Stream document bytes |
| `GET` | `/api/documents/{id}/presign` | API key | Issue a time-limited presigned S3 URL |
| `DELETE` | `/api/documents/{id}` | API key | Soft-delete (default) or hard-delete (`?hard=true`) |

### Client management (admin)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/clients` | Admin key | Register a new API client |
| `GET` | `/api/clients` | Admin key | List all registered clients |
| `POST` | `/api/clients/{id}/rotate` | Admin key | Issue a new key; old key immediately invalidated |
| `DELETE` | `/api/clients/{id}` | Admin key | Revoke a client |

### Infrastructure (always open)

| Path | Description |
|------|-------------|
| `GET /q/health/live` | Liveness probe |
| `GET /q/health/ready` | Readiness probe |
| `GET /q/openapi` | OpenAPI specification |
| `GET /q/swagger-ui` | Swagger UI |
| `GET /q/metrics` | Prometheus metrics |

---

## Feature guide

### Document upload

```
POST /api/documents/upload
Content-Type: application/pdf
X-API-Key: <key>
?filename=contract.pdf

<raw bytes>
```

**Why raw bytes instead of multipart?** Multipart encoding adds framing overhead and forces the server to parse boundaries before it can begin writing to storage. Raw binary with a `Content-Type` header is simpler, faster, and sufficient for programmatic clients, which is the only intended caller.

**How it works:**

1. The `Content-Length` header is checked first. If it exceeds the configured limit, the request is rejected immediately without reading the body (the HTTP layer also enforces `quarkus.http.limits.max-body-size` for clients that omit the header).
2. The body is written to a temporary file. This decouples the HTTP read from the S3 upload so the upload to S3 uses a seekable file rather than a raw stream, which is required by the AWS SDK for reliable size reporting.
3. The actual byte count is verified after writing. This catches cases where `Content-Length` was absent or incorrect.
4. The MIME type allowlist is checked (if configured).
5. The file is uploaded to S3.
6. A metadata row is persisted to the database.
7. The temp file is deleted.

The stored object key follows the pattern `{tenantId}/{appId}/{uuid}/{sanitisedFilename}`, which provides natural namespace separation in the bucket without requiring separate buckets per tenant.

Content-type is normalised to the base MIME type (e.g. `application/pdf`) before storage, stripping parameters such as `; charset=UTF-8`. This ensures that filtering documents by content type with `GET /api/documents?contentType=application/pdf` returns consistent results regardless of which charset variant was sent at upload time.

**Response fields:**

| Field | Description |
|-------|-------------|
| `id` | UUID. Store this in your own database to reference the document later. |
| `tenantId` / `appId` | The tenant and app this document belongs to. |
| `ownerUserId` | Optional user identifier passed at upload time. |
| `originalFilename` | The filename as provided by the caller (up to 512 chars). |
| `contentType` | Normalised base MIME type. |
| `sizeBytes` | Exact byte count as measured after buffering. |
| `etag` | ETag returned by S3, useful for cache validation. |
| `createdAt` | Upload timestamp. |
| `contentPath` | Relative path to stream the bytes through this service. |

---

### Document retrieval and streaming

**Metadata only:**
```
GET /api/documents/{id}
X-API-Key: <key>
```
Returns the same JSON shape as upload. Use `contentPath` to construct the stream URL.

**Byte streaming:**
```
GET /api/documents/{id}/content
X-API-Key: <key>
```

The service fetches the object from S3 and pipes it directly to the HTTP response. It sets `Content-Type`, `Content-Length`, `ETag`, and `Content-Disposition: attachment; filename="..."` so browsers and HTTP clients handle the file correctly without any additional configuration.

**Why proxy through the service instead of presigning?** Proxying keeps the S3 bucket private. The caller needs only an API key; they never need S3 credentials or knowledge of the bucket topology. Presigning is offered separately for cases where the overhead of proxying matters (see below).

---

### Soft delete and hard delete

**Soft delete (default):**
```
DELETE /api/documents/{id}
X-API-Key: <key>
```
Sets `deleted_at` on the metadata row and attempts to remove the object from S3. The row is retained so that:
- The document ID can still be referenced without returning stale data to callers (all reads filter on `deleted_at IS NULL`).
- The purge job can clean up in a controlled retention window.
- Accidental deletion can be recovered within the retention period by directly nulling `deleted_at` (admin operation outside the API).

Soft delete is idempotent — deleting an already-deleted document returns 204 with no effect.

**Hard delete:**
```
DELETE /api/documents/{id}?hard=true
X-API-Key: <key>
```
Removes the S3 object and the database row immediately. Used when you need instant removal and do not want to wait for the purge cycle, for example to comply with a right-to-erasure request.

---

### Presigned URLs

```
GET /api/documents/{id}/presign?ttl=3600
X-API-Key: <key>
```

Returns a time-limited URL that grants direct read access to the S3 object, bypassing the service entirely. The TTL must be between `doc.bucket.presign.min-ttl-seconds` (default 60 s) and `doc.bucket.presign.max-ttl-seconds` (default 7 days).

**Why presign instead of always proxying?** Proxying every download through the service works well for low-to-moderate traffic, but for large files or high download volumes the service becomes a bottleneck: every byte passes through its JVM and network stack. A presigned URL lets the client download directly from S3, eliminating that overhead, while still requiring the caller to authenticate with the service to obtain the URL. The S3 bucket remains private.

---

### Multi-tenancy

Every document is tagged with a `tenantId` and `appId`. These values come from the authenticated API client (per-app key mode) or from request headers (dev-open mode). All database queries include both as mandatory filters. A caller authenticated as `(tenantA, appX)` cannot read, list, or delete documents belonging to `(tenantB, appY)` — it receives 404 as if those documents do not exist.

**Why 404 instead of 403?** Returning 403 would confirm that a document with that ID exists. 404 leaks no information about documents outside the caller's scope.

The `ownerUserId` field is optional and caller-supplied. It is stored but not enforced by the service — use it to attach your own application's user identity to a document for filtering (`GET /api/documents?ownerUserId=u123`) without requiring the storage service to understand your user model.

Tenant and app IDs are validated to match `[a-zA-Z0-9_-]+`. This prevents path traversal attacks, since these values are embedded directly in the S3 object key.

---

### Authentication — per-app API keys

Each application that needs to store documents is registered as an API client:

```
POST /api/clients
X-Admin-Key: <admin-key>
Content-Type: application/json

{"tenantId": "acme", "appId": "billing", "label": "Billing service prod"}
```

The response contains a `apiKey` field — a 256-bit (64 hex character) cryptographically random key. **This is the only time the raw key is returned.** Store it in your application's secret manager immediately.

The application then authenticates every request with:
```
X-API-Key: <raw-key>
```

**Why per-app keys instead of a single shared key?**

- **Blast radius.** If one application's key is compromised, only that application is affected. You rotate that key without touching others.
- **Isolation.** The key is bound to a specific `(tenantId, appId)` pair in the database. An application cannot forge headers to write into another tenant's namespace.
- **Auditability.** Rate limiting and logs are per-key, so you can trace which application is responsible for activity.

**How keys are stored:**

The raw key is never stored. On registration, the service computes `HMAC-SHA256(rawKey, serverSecret)` and stores the 64-character hex result. On each request, the same computation is performed on the incoming header and compared to the stored hash using a constant-time equality check.

HMAC-SHA256 is used instead of Argon2id/bcrypt because the keys have 256 bits of random entropy — brute force is computationally infeasible regardless of hash speed. The slow KDF cost would only add latency to every authenticated request with no security benefit. The HMAC server secret (distinct from the stored hash) means that even if the database is fully dumped, an attacker cannot verify guesses without also knowing the secret.

**Key expiry:**

Clients can be registered with an optional `expiresAt` timestamp. Requests using an expired key receive 401 with a message indicating expiry rather than a generic "invalid key" error, which helps diagnose configuration problems.

---

### Authentication — dev-open mode

When no API clients are registered in the database, the authentication filter enters dev-open mode: no `X-API-Key` is required, and `X-Tenant-Id` / `X-App-Id` headers are read directly from the request.

**Why this exists:** Running locally requires a working service before you have gone through the client registration flow. Dev-open mode makes the initial setup frictionless.

**Why it is safe:** The service logs a `SECURITY: No API clients registered` warning at startup. In production profile, this is logged at ERROR level. The mode is not a configuration flag — it activates automatically only when the `api_client` table is empty, so deploying to production with clients registered eliminates it without any additional action.

If a request arrives with an `X-API-Key` header, it is always validated against the database even in dev-open mode. This prevents the cache from masking a newly registered client and ensures directly-inserted rows are honoured immediately.

---

### API client management

**Registration** (`POST /api/clients`) — creates a client and returns the raw key once.

**Listing** (`GET /api/clients`) — returns all clients without raw keys (hashes are never returned).

**Rotation** (`POST /api/clients/{id}/rotate`) — generates a new key for an existing client and immediately invalidates the old one. The old key will start receiving 401 responses within the same request cycle; there is no grace period. Rotation does not change the client's `tenantId` or `appId`, so no documents need to be migrated.

**Revocation** (`DELETE /api/clients/{id}`) — removes the client. Subsequent requests using its key receive 401.

All admin endpoints require an `X-Admin-Key` header matching the `DOC_BUCKET_ADMIN_KEY` environment variable. If that variable is not set, the admin API returns 501 (disabled). The admin key comparison uses the same HMAC constant-time verification as API keys to prevent timing-based attacks.

**Why a separate admin key rather than a special API client?** Admin operations (register, rotate, revoke) must work even before any clients exist. Bootstrapping with a well-known env var avoids the chicken-and-egg problem of needing a client to create the first client.

---

### Rate limiting

Authenticated requests are subject to a per-API-key fixed-window rate limit (default: 200 requests per minute). Requests that exceed the limit receive 429 with a `Retry-After: 60` header.

**Why per-key instead of global?** A global limit would punish well-behaved applications when a single misbehaving client spikes traffic. Per-key limits let each application hit its quota independently.

**Current limitation:** Counters are in-memory, per JVM instance. With multiple service replicas, the effective limit is `requestsPerMinute × replicaCount`. For shared enforcement across replicas, replace `RateLimiter` with a Redis-backed counter or delegate to an API gateway (Traefik, Caddy, etc.).

Stale window entries (keys inactive for more than two window periods) are evicted on a 2-minute schedule to prevent unbounded memory growth.

Rate limiting can be disabled entirely with `doc.bucket.rate-limit.enabled=false` (default in test profile).

---

### Soft-delete purge job

Soft-deleted documents are retained for a configurable number of days (default: 30). Every 24 hours, the purge job:

1. Loads soft-deleted rows older than the retention cutoff (up to 500 per run) in a read transaction.
2. Deletes the corresponding S3 objects outside the DB transaction. S3 deletes are idempotent — if an object was already removed at soft-delete time, this is a no-op.
3. Removes the DB rows in a separate transaction.

**Why the S3 and DB deletes are in separate transactions:** S3 is not transactional. Doing S3 deletes inside a DB transaction risks a scenario where S3 succeeds but the DB commit fails — leaving DB rows that point to now-missing objects. By deleting from S3 first and then committing the DB removals separately, the worst-case failure mode is a DB row that still references a missing S3 object. The next purge run will attempt the S3 delete again (no-op) and successfully remove the DB row.

**Why batch to 500 rows per run?** Loading all soft-deleted rows into memory at once would cause heap pressure on mature deployments with many accumulated deletes. Limiting to 500 per run keeps memory bounded; the 24-hour schedule means large backlogs drain over multiple days, which is acceptable for a background cleanup job.

The purge job can be disabled with `doc.bucket.purge.enabled=false`.

---

### Upload restrictions

**Maximum size:**
```yaml
doc.bucket.upload.max-bytes: 104857600  # 100 MB default
```
Controlled via `DOC_BUCKET_MAX_UPLOAD_BYTES`. Also applied at the HTTP layer via `quarkus.http.limits.max-body-size` so oversized requests are rejected before any service code runs when `Content-Length` is present.

**MIME allowlist:**
```yaml
doc.bucket.upload.mime-allowlist: application/pdf,image/png,image/jpeg
```
Controlled via `DOC_BUCKET_UPLOAD_MIME_ALLOWLIST`. When absent or set to `*`, all content types are accepted. When set, only listed base MIME types are allowed; requests with other types receive 400. Rejection increments the `docbucket.upload.rejected{reason="content_type_blocked"}` counter.

**Why allowlist instead of blocklist?** An allowlist is safe by default — new MIME types are rejected until explicitly permitted. A blocklist requires anticipating every type you want to exclude and updating it as new types emerge.

---

### Observability

**Health checks** (`/q/health/live`, `/q/health/ready`):
Standard Quarkus SmallRye Health probes. Use `live` for the Kubernetes liveness probe and `ready` for the readiness probe. The service is ready when the database connection is established and Flyway migrations have completed.

**Prometheus metrics** (`/q/metrics`):

| Metric | Type | Description |
|--------|------|-------------|
| `docbucket.documents.uploaded` | Counter | Successful uploads |
| `docbucket.documents.downloaded` | Counter | Content stream requests |
| `docbucket.documents.presigned` | Counter | Presigned URL requests |
| `docbucket.documents.deleted` | Counter | Soft deletes |
| `docbucket.documents.hard_deleted` | Counter | Hard deletes |
| `docbucket.upload.rejected{reason}` | Counter | Rejected uploads, tagged with `size_exceeded` or `content_type_blocked` |

**OpenTelemetry** (`quarkus.otel.enabled`):
Distributed tracing is enabled by default. Spans are emitted for every HTTP request and database query. Configure an OTLP exporter endpoint to ship traces to Jaeger, Tempo, or a compatible backend.

**OpenAPI / Swagger UI** (`/q/openapi`, `/q/swagger-ui`):
Auto-generated from annotations. Use during development to explore and test the API interactively.

---

### CORS

Cross-origin requests are enabled for all origins by default (`origins: "*"`). The allowed headers include `x-api-key`, `x-tenant-id`, `x-app-id`, `x-owner-user-id`, and `x-admin-key`. Preflight responses are cached for 24 hours.

**Why CORS is enabled:** The service is designed to be called from browser-based frontend applications as well as backend services. Restricting CORS to specific origins is left to each deployment — override `quarkus.http.cors.origins` in the production environment to match your actual frontend domain(s).

---

## Configuration reference

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `doc.storage.endpoint` | `S3_ENDPOINT` | `http://127.0.0.1:3900` | S3 API base URL |
| `doc.storage.region` | `S3_REGION` | `garage` | S3 region (must match server config) |
| `doc.storage.access-key-id` | `DOC_STORAGE_ACCESS_KEY_ID` | — | S3 access key |
| `doc.storage.secret-access-key` | `DOC_STORAGE_SECRET_ACCESS_KEY` | — | S3 secret key |
| `doc.storage.path-style-access` | `S3_PATH_STYLE` | `true` | Required for self-hosted S3 (Garage, MinIO) |
| `doc.storage.default-bucket` | `DOC_BUCKET_BUCKET` | `documents` | Bucket name; must exist before startup |
| `doc.bucket.admin-key` | `DOC_BUCKET_ADMIN_KEY` | — | Admin key for `/api/clients`; unset disables admin API |
| `doc.bucket.key-hmac-secret` | `DOC_BUCKET_KEY_HMAC_SECRET` | dev default | HMAC-SHA256 secret for API key hashing; min 32 chars; **must be set in production** |
| `doc.bucket.upload.max-bytes` | `DOC_BUCKET_MAX_UPLOAD_BYTES` | `104857600` | Max upload size in bytes (100 MB) |
| `doc.bucket.upload.mime-allowlist` | `DOC_BUCKET_UPLOAD_MIME_ALLOWLIST` | — | Comma-separated allowed MIME types; absent = all allowed |
| `doc.bucket.rate-limit.enabled` | `DOC_BUCKET_RATE_LIMIT_ENABLED` | `true` | Enable per-key rate limiting |
| `doc.bucket.rate-limit.requests-per-minute` | `DOC_BUCKET_RATE_LIMIT_RPM` | `200` | Requests per minute per API key |
| `doc.bucket.purge.enabled` | `DOC_BUCKET_PURGE_ENABLED` | `true` | Enable soft-delete purge job |
| `doc.bucket.purge.retention-days` | `DOC_BUCKET_PURGE_RETENTION_DAYS` | `30` | Days to retain soft-deleted documents |
| `doc.bucket.presign.min-ttl-seconds` | `DOC_BUCKET_PRESIGN_MIN_TTL` | `60` | Minimum presigned URL TTL |
| `doc.bucket.presign.max-ttl-seconds` | `DOC_BUCKET_PRESIGN_MAX_TTL` | `604800` | Maximum presigned URL TTL (7 days) |
| `quarkus.http.limits.max-body-size` | `DOC_BUCKET_MAX_UPLOAD_BYTES` | `104857600` | HTTP-layer body size cap (mirrors upload limit) |
| `DOCBUCKET_DB_FILE` | — | `./docbucket.db` | SQLite database file path (default and dev profiles) |
| `QUARKUS_DATASOURCE_JDBC_URL` | — | SQLite file | Override the full JDBC URL (e.g. to point at PostgreSQL in production) |
| `QUARKUS_DATASOURCE_USERNAME` | — | `docbucket` | Database username (PostgreSQL / `%prod` only) |
| `QUARKUS_DATASOURCE_PASSWORD` | — | — | Database password (PostgreSQL / `%prod` only) |

---

## Local development

```bash
# Prerequisites: a running S3-compatible server (Garage, MinIO, etc.)
# No Docker or external database required — SQLite is used automatically.
export DOC_STORAGE_ACCESS_KEY_ID=<key>
export DOC_STORAGE_SECRET_ACCESS_KEY=<secret>

cd doc-bucket
./mvnw quarkus:dev
```

The service starts with an embedded SQLite database (`./docbucket-dev.db` in the current directory). Flyway migrations run automatically on startup. The S3 server must be running separately with the bucket already created.

On startup, the service logs a `SECURITY: No API clients registered` warning — this is expected in dev. The service runs in dev-open mode: no `X-API-Key` is required. Pass `X-Tenant-Id` and `X-App-Id` headers directly.

**Tests** use an in-memory SQLite database — no Docker or external services required:

```bash
./mvnw test                                          # all tests
./mvnw test -Dtest=DocumentResourceTest              # single class
./mvnw test -Dtest=DocumentResourceTest#uploadDocument  # single method
```

---

## Production deployment

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

### Database choice

| Profile | Database | Notes |
|---------|----------|-------|
| `%dev` (default) | SQLite file | No external process; file in current directory |
| `%test` | SQLite in-memory | Zero setup; discarded after each test run |
| `%prod` | PostgreSQL | Set `QUARKUS_PROFILE=prod` to activate |

Both drivers are bundled. Switching databases is a **config-only change** — no rebuild required.

**To use PostgreSQL in production**, set `QUARKUS_PROFILE=prod` and provide:
```bash
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/docbucket
QUARKUS_DATASOURCE_USERNAME=docbucket        # optional, defaults to "docbucket"
QUARKUS_DATASOURCE_PASSWORD=<secret>
```

**To keep SQLite in production** (single-node, low-traffic deployments):
```bash
DOCBUCKET_DB_FILE=/data/docbucket.db         # persistent path outside the working dir
```
SQLite is sufficient for most deployments — DocBucket's database load is light (one write per upload, one read per request). Use PostgreSQL when you need multi-replica horizontal scaling or existing PostgreSQL infrastructure.

### Required environment variables

```bash
# S3-compatible storage (Garage, MinIO, etc.)
DOC_STORAGE_ACCESS_KEY_ID=<key>
DOC_STORAGE_SECRET_ACCESS_KEY=<secret>
S3_ENDPOINT=http://your-garage-node:3900     # or your MinIO/R2 endpoint
DOC_BUCKET_BUCKET=documents                  # bucket must exist before startup

# Security — must be set; min 32 chars; rotate only with a full key re-registration
DOC_BUCKET_KEY_HMAC_SECRET=<random-32+-char-string>

# Admin API — omit to disable client management endpoints
DOC_BUCKET_ADMIN_KEY=<random-string>
```

**Changing `DOC_BUCKET_KEY_HMAC_SECRET`** invalidates every existing API key. All registered clients must receive new keys via the rotate endpoint before the secret is changed, or be re-registered afterwards.

**Bucket setup:**
Create the bucket and grant the storage key read/write access before starting the service. The service does not create buckets automatically. See `Garage-Setup-Guide.docx` in the repository root for a step-by-step Garage installation and bucket configuration guide.

**CORS for production:**
Override `quarkus.http.cors.origins` to restrict to your actual frontend domains:
```bash
QUARKUS_HTTP_CORS_ORIGINS=https://app.example.com
```

**Capacity and backups:**
- Monitor S3 and (if using PostgreSQL) database disk usage; alert before ~85% full.
- For SQLite: back up the `.db` file while the service is idle, or use SQLite's online backup API. Back up alongside S3 data — metadata without objects (or vice versa) is an incomplete restore.
- For PostgreSQL: use `pg_dump` and back up together with S3 data.
