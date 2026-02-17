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
- Admin / Diagnostics: `#/admin`

Use the top navigation to switch pages.

## Places + Watchlist (Phase 5 behavior)

- Places ZIPs (up to 10) and Markets watchlist are stored in browser `localStorage`.
- These preferences are not stored on the backend yet.
- AQI / Local Happenings / Markets cards can render demo values when backend integrations are unavailable.
