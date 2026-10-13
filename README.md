# Smart Blood Bank Backend

## Deploy to Render

Create a **Web Service** from this backend repository and choose **Docker** as the runtime.
Render can use the repository-root `Dockerfile` and build context. The image builds with Java 17,
does not include `.env`, and runs the app as a non-root user. Render supplies `PORT`.

Set these environment variables in Render. Do not commit their values:

| Variable | Value |
|----------|-------|
| `DB_URL` | Full JDBC URL, e.g. `jdbc:mysql://<host>:3306/<database>?useSSL=true&serverTimezone=UTC` |
| `DB_USER` | Database username |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | Private random value, at least 32 characters |
| `CORS_ALLOWED_ORIGINS` | Comma-separated frontend origins, e.g. `https://your-site.vercel.app` |

The database must be hosted separately and reachable from Render. The service root (`/`) returns
API status information; `/health` verifies database connectivity and returns HTTP 503 with a
JSON error if the database is unavailable. The frontend is deployed separately on Vercel and
must be configured to send `/api` requests to this service.
