# Today's Overview UI

React + Vite dashboard for the Today's Overview backend service.

## Requirements

- Node 18+
- Backend running on `http://localhost:8080`

## Run UI

From `ui/`:

```bash
npm install
npm run dev
```

Vite runs on `http://localhost:5173` by default.

## Test + Build

From `ui/`:

```bash
npm test
npm run build
```

## API/Proxy

The app calls `/api/*` paths and relies on Vite proxy config to forward requests to:

- `http://localhost:8080`

This includes SSE via `EventSource('/api/stream')`.

## Verify Live SSE

1. Start backend and UI.
2. Open the dashboard in browser.
3. Trigger backend activity (collectors/scheduler).
4. Watch the **Live Event Feed** section:
   - events should append in near real-time
   - if stream drops, UI shows **Reconnecting...** and retries with backoff

## Routes

- Home (default): `#/`
- Login: `#/login`
- Sign up: `#/signup`
- Forgot password: `#/forgot`
- Reset password: `#/reset?token=...`
- Settings (authenticated only): `#/settings`
- Admin / Diagnostics: `#/admin`

Use the top navigation to switch pages.

## Preferences Behavior

- Anonymous users:
  - Home/Admin available
  - defaults + local browser preferences
- Logged-in users:
  - Settings route enabled
  - Places/watchlist/preferences loaded from and saved to `/api/me/preferences`
  - settings are server-side (not localStorage-driven)
