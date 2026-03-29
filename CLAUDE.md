# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DocBucket is a multi-tenant document storage service built with Kotlin + Quarkus, backed by PostgreSQL (metadata) and an S3-compatible object store (Garage, MinIO, AWS S3, etc.).

## Commands

All commands run from `doc-bucket/`:

```bash
# Dev mode with hot reload (uses Quarkus Dev Services for PostgreSQL automatically)
./mvnw quarkus:dev

# Run tests
./mvnw test

# Run integration tests
./mvnw verify

# Build JAR
./mvnw package

# Run a single test class
./mvnw test -Dtest=DocumentResourceTest

# Run a single test method
./mvnw test -Dtest=DocumentResourceTest#uploadDocument
```

Dev mode requires a running S3-compatible endpoint. The default config points to `http://127.0.0.1:3900` (Garage). Set credentials via env vars:

```bash
export DOC_STORAGE_ACCESS_KEY_ID=<key>
export DOC_STORAGE_SECRET_ACCESS_KEY=<secret>
```

## Architecture

The application follows a layered architecture under `src/main/kotlin/com/docbucket/`:

### Layers

- **`api/`** — JAX-RS resources (`DocumentResource`, `ClientResource`), DTOs, and exception mappers. Resources delegate all logic to the service layer.
- **`service/`** — Business logic (`DocumentService`). Handles upload validation, S3 interaction, metadata persistence, soft/hard delete, presigned URL generation, and streaming downloads. `SoftDeletePurgeJob` runs on a 24-hour schedule.
- **`domain/`** — JPA entities (`DocumentEntity`, `ApiClient`) and Panache repositories. `DocumentRepository` handles all filtered/paginated queries.
- **`storage/`** — `ObjectStorage` interface with `S3ObjectStorage` implementation. `S3Producer` is a CDI producer for `S3Client` and `S3Presigner`.
- **`security/`** — `JwtBearerAuthFilter` is a JAX-RS `ContainerRequestFilter` at `AUTHENTICATION` priority. It validates ES256 Bearer JWTs issued by the auth-service by fetching EC public keys from its JWKS endpoint via `JwksCache`. `RateLimiter` applies fixed-window per-user quotas in memory. `ApiKeyHasher` is retained only for HMAC-based admin key verification in `ClientResource`. `ApiKeyAuthFilter` is disabled (retained for reference only).
- **`config/`** — ConfigMapping interfaces for upload, purge, and presign settings.

### Key Data Flow

**Upload**: `DocumentResource` → `DocumentService` (validate MIME/size, write temp file, `S3ObjectStorage.putObject`, persist `DocumentEntity`, increment metrics)

**Download**: `DocumentResource` → `DocumentService` (load entity, `S3ObjectStorage.getObject`, return `DocumentContentStream` streamed via JAX-RS)

**Auth**: Every `/api/*` request (except `/api/clients`) passes through `JwtBearerAuthFilter`. The filter validates the `Authorization: Bearer <jwt>` header using an EC public key fetched from the auth-service JWKS endpoint, then writes a `CallerContext` (tenantId = userId, appId) into the JAX-RS request context for downstream use.

### Multi-Tenancy

Documents are scoped by `(tenant_id, app_id)` enforced at the repository layer. Tenant/app values come from the resolved `CallerContext`, not from request headers directly (in per-app key mode).

### Database Migrations

Flyway manages schema in `src/main/resources/db/migration/`. Three migrations exist: V1 (document_metadata), V2 (api_client), V3 (original_filename column).

### Auth Modes

1. **JWT bearer** (production): Callers present an ES256 JWT issued by the auth-service. `JwtBearerAuthFilter` validates the signature against the JWKS endpoint (`DOC_BUCKET_AUTH_JWKS_URL`). The `userId` claim becomes `tenantId`; the `appId` claim becomes `appId` in `CallerContext`.
2. **Dev-open mode**: If no `Authorization` header is present, the filter allows the request through and reads tenant/app from `X-Tenant-Id` / `X-App-Id` headers.

### Rate Limiting

In-memory fixed-window limiter (default: 200 req/min per API key hash). Scales linearly with pod count — a Redis-backed shared store is a noted TODO for horizontal scaling.

## Configuration

Key properties (set via `application.yml` or env vars):

| Config path | Env var override | Purpose |
|---|---|---|
| `doc.storage.endpoint` | `DOC_STORAGE_ENDPOINT` | S3 endpoint URL |
| `doc.storage.access-key-id` | `DOC_STORAGE_ACCESS_KEY_ID` | S3 credentials |
| `doc.storage.secret-access-key` | `DOC_STORAGE_SECRET_ACCESS_KEY` | S3 credentials |
| `doc.bucket.admin-key` | `DOC_BUCKET_ADMIN_KEY` | Admin API key for client registration |
| `doc.bucket.auth.jwks-url` | `DOC_BUCKET_AUTH_JWKS_URL` | Auth-service JWKS endpoint (required in prod) |
| `doc.bucket.key-hmac-secret` | `DOC_BUCKET_KEY_HMAC_SECRET` | HMAC secret for admin key verification only |
| `doc.bucket.upload.max-bytes` | — | Max upload size (default 100 MB) |
| `doc.bucket.upload.mime-allowlist` | — | Comma-separated allowed MIME types (optional) |

SQLite is used in all profiles including production — no DB setup needed anywhere. PostgreSQL is supported but optional: set `QUARKUS_DATASOURCE_DB_KIND=postgresql` and the related env vars at runtime if you ever need it.
