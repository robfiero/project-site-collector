# Signal Sentinel UI

React + Vite dashboard for the Signal Sentinel backend.

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
