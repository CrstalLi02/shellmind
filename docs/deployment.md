# Deployment

## Containers (MySQL + server)

```bash
cp .env.example .env
# required: MYSQL_ROOT_PASSWORD, SHELLMIND_SECRET_KEY, SHELLMIND_AI_API_KEY
make up            # docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
make logs          # server logs
```

- Server image: multi-stage `server/Dockerfile` (Maven build → JRE runtime, non-root), `prod` profile
- MySQL data: `mysql-data` volume; server logs: `server-data` (`/app/data/log`)
- Optional DB UI: `docker compose --env-file .env -f deploy/docker-compose.yml --profile tools up -d`, then `http://localhost:8899`
- If you need a registry mirror, set `MYSQL_IMAGE` (and similar) in `.env`

## Database init and migrations

| Directory | When it runs |
|-----------|----------------|
| `deploy/mysql/init/` | First MySQL start (empty volume): create `shellmind` and all tables |
| `deploy/mysql/migrations/` | Manual, numbered, for existing databases |

```bash
mysql -h 127.0.0.1 -P 13306 -u root -p < deploy/mysql/migrations/002-long-term-memory.sql
```

Migration scripts use `CREATE TABLE IF NOT EXISTS` and are idempotent.

## Upgrading from walicode / walissh

- **Database name**: older deploys used `walissh`. Rename it to `shellmind`, or keep the old name in `SHELLMIND_DB_URL`, then run scripts under `migrations/`
- **SSH password key**: if the old deploy had no key, it used a built-in default. Keep the same key or stored passwords will not decrypt:
  - Old `WALISSH_SECRET_KEY`: copy that value to `SHELLMIND_SECRET_KEY` (the server still reads the old name)
  - No old key: set `SHELLMIND_SECRET_KEY` to `PasswordEncryptor.DEFAULT_SECRET_KEY`; changing it later requires users to re-save SSH passwords
- **Desktop local data**: first launch copies `~/.walicode/data/walicode.mv.db` to `~/.shellmind/data/shellmind.mv.db`

## Secrets

- Real secrets belong in `.env` (gitignored) or your platform’s secret store
- `prod` has no default DB URL; missing values fail startup instead of connecting to a default database
- Historical commits may have contained plaintext API keys or DB passwords — treat those as leaked and rotate them

## Desktop packaging

```bash
make build-desktop   # server jar → client/resources/agent → bundled JRE → tauri build
```

Artifacts: `client/src-tauri/target/release/bundle/`.
