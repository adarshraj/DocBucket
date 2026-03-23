# doc-bff — document storage BFF (Quarkus + Kotlin)

Central **document API**: metadata in **PostgreSQL**, bytes in any **S3-compatible** store (e.g. Garage) via **AWS SDK for Java v2** — no vendor-specific code paths.

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/documents/upload` | Upload raw body (`application/octet-stream`). With **per-app** API keys, `X-Tenant-Id` / `X-App-Id` are optional (resolved from the key). Otherwise send **`X-Tenant-Id`**, **`X-App-Id`**; optional `X-Owner-User-Id`, `Content-Type`. Query: `filename` (optional). |
| `GET` | `/api/documents/{id}` | Metadata + `contentPath` for streaming. |
| `GET` | `/api/documents/{id}/content` | Stream bytes (S3 `GetObject` through the BFF). |
| `DELETE` | `/api/documents/{id}` | Soft-delete row + best-effort delete in object storage. |

OpenAPI: `/q/openapi`, Swagger UI: `/q/swagger-ui`.

## How each app talks to the BFF

Apps do **not** talk to Garage directly (unless you deliberately bypass the BFF). They use **plain HTTP** to the BFF:

1. **Base URL** — e.g. `https://doc-bff.internal` or `http://localhost:8080` in dev. Put it in each app’s config (`DOC_BFF_BASE_URL`, etc.).
2. **Service auth** — Choose **one** of:
   - **Per-app keys (recommended)** — Leave `doc.bff.api-key` unset, register one row per app in **`bff_api_client`** (see below). Each app sends its own secret as **`X-API-Key`**; **`X-Tenant-Id` / `X-App-Id` on upload are optional** (they are taken from the DB row and headers are ignored for tenant/app).
   - **Legacy shared key** — Set **`doc.bff.api-key`**. Every trusted backend uses the **same** **`X-API-Key`**. You must still send **`X-Tenant-Id`** and **`X-App-Id`** on upload so the BFF knows how to label the document.
3. **Tenant context** — With **per-app** keys, tenant/app come from the registered client. With **legacy** key or **no** API key (dev only), send **`X-Tenant-Id`** and **`X-App-Id`** on upload (and optionally **`X-Owner-User-Id`**). The BFF stores these on the document row.
4. **Upload** — `POST /api/documents/upload` with body = raw bytes, `Content-Type` set when known, optional `?filename=`.
5. **Save the id** — The JSON response includes `id` (UUID). Your app’s **own** PostgreSQL (or other DB) should store that id next to business rows (orders, profiles, etc.) so you can fetch or delete the file later via `GET` / `DELETE` on the BFF.

**Network:** On one VPS, apps and BFF often share `localhost` or a private Docker network. Across machines, use TLS + firewall rules so only your backends can reach the BFF.

### Multiple apps: shared key vs per-app keys

- **Legacy shared key** — Set `doc.bff.api-key`. All backends use the same `X-API-Key`. **`X-App-Id` / `X-Tenant-Id` are not cryptographically bound** to the key; any holder of the shared secret could mislabel uploads unless you trust every caller.

- **Per-app keys** — Do **not** set `doc.bff.api-key`. Insert rows into **`bff_api_client`** (SHA-256 hex of the raw secret, never store plaintext). As soon as **at least one** row exists, `/api/*` requires a valid **`X-API-Key`** that matches a row; the BFF resolves **`tenant_id` / `app_id`** from that row. Document **GET** / **DELETE** are scoped so a key only sees its own tenant/app (wrong id → 404).

- **Dev / empty registry** — If there is **no** `doc.bff.api-key` and **no** rows in `bff_api_client`, document routes do **not** require `X-API-Key` (tenant/app from headers only). **Do not rely on this in production.**

**Registering a per-app key** (PostgreSQL example — compute `key_hash` as SHA-256 of the UTF-8 secret, lowercase hex, 64 characters):

```sql
INSERT INTO bff_api_client (tenant_id, app_id, key_hash, label)
VALUES (
  'acme',
  'billing-service',
  '<sha256-hex-of-your-secret>',
  'Billing service prod'
);
```

You can compute the hash with `printf '%s' 'your-secret' | sha256sum` (same algorithm as `ApiKeyHasher` in the BFF).

**Future** options: JWT/OIDC, mTLS. Garage stays out of app auth: it only sees the storage credentials used by the BFF.

## Configuration

**You are not using Amazon AWS** unless you point `doc.storage.endpoint` at AWS. The app speaks the **S3 API** to Garage (or any compatible server). Keys are the **access key id + secret** that **Garage** gives you (`garage key create …`) — the same kind of credential every S3-compatible product uses.

The Java **AWS SDK** historically reads `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`; that is only a **naming convention** of the SDK, not a requirement to use AWS. This project prefers clearer names and falls back to the old ones:

| Env var (preferred) | Fallback | Purpose |
|---------------------|----------|---------|
| `DOC_STORAGE_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | Key id from Garage (or other S3 backend). |
| `DOC_STORAGE_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | Secret from Garage. |

Environment / `application.yml`:

| Key | Purpose |
|-----|---------|
| `doc.storage.endpoint` | S3 API base URL (e.g. `http://127.0.0.1:3900` for Garage). |
| `doc.storage.region` | Must match Garage `s3_region` (often `garage`). |
| `doc.storage.access-key-id` / `doc.storage.secret-access-key` | Same as the env vars above (keys from **Garage**, not from AWS). |
| `doc.storage.path-style-access` | `true` for most self-hosted S3 (including Garage). |
| `doc.storage.default-bucket` | Bucket name; create in Garage before use. |
| `doc.bff.api-key` | If set, **legacy** mode: same shared `X-API-Key` for all callers; unset to use **per-app** keys via `bff_api_client` (see above). Health/OpenAPI under `/q/*` stay open. |
| `QUARKUS_DATASOURCE_JDBC_URL` | **Production** JDBC URL (`%prod` profile). Dev/test use Dev Services PostgreSQL when URL is not set. |

## Decisions (from product plan)

### Tenant model & auth

- **Tenant** = `X-Tenant-Id`, **app** = `X-App-Id`, optional **owner** = `X-Owner-User-Id` (your user id from an IdP). Stored on each document row.
- **Phase 1 auth**: optional **legacy** shared API key, or **per-app** keys in **`bff_api_client`** + `X-API-Key`. **Future**: replace or augment with **OIDC/JWT** (Quarkus OIDC) and map `sub` → `ownerUserId`.

### Object storage provider

- Code uses **only** `S3Client` + config. Swap Garage for R2/AWS/MinIO by changing env — **no code changes**.
- **Validate Garage** (layout, bucket, keys) using [Garage quick start](https://garagehq.deuxfleurs.fr/documentation/quick-start/) before production.

### Compression strategy

- **Filesystem**: ZFS/btrfs compression on the volume holding Garage data (transparent).
- **Application**: for compressible types only, gzip/zstd **before** upload and record encoding in metadata (not implemented in this skeleton).
- **Already compressed** (JPEG, zip, etc.): store as-is; extra compression rarely helps.

### Operations

- **Capacity**: monitor DB + object store disk; alert before ~85% full.
- **Backups**: two streams — PostgreSQL (`pg_dump` / snapshots) and Garage data directory (or provider export). Restore DB and objects together for consistency.
- **Secrets**: never commit keys; use env or a secret manager in production.
- **Limits**: add max upload size and content-type allowlists in the BFF when you harden (not enabled by default).

## Run locally

```bash
# Dev: PostgreSQL via Dev Services (Docker), Garage must be running separately with bucket + keys.
export DOC_STORAGE_ACCESS_KEY_ID=...    # from `garage key create` (not AWS)
export DOC_STORAGE_SECRET_ACCESS_KEY=...
./mvnw quarkus:dev
```

Garage does **not** emit API keys on its own when it starts—you **create** them with `garage key create …`, create the bucket with `garage bucket create …`, then `garage bucket allow …` (see [Garage quick start](https://garagehq.deuxfleurs.fr/documentation/quick-start/)). Use that Key ID + secret in `DOC_STORAGE_*` env vars.

Create the default bucket in Garage (example name: `documents`) and grant your key read/write.

## Build

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

Use `%prod` profile and set `QUARKUS_DATASOURCE_JDBC_URL` (and credentials) for a real PostgreSQL instance.
