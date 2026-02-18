#!/usr/bin/env zsh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-demo+$(date +%s)@example.com}"
PASSWORD="${PASSWORD:-Demo!Phase6#Pass123}"
NEW_PASSWORD="${NEW_PASSWORD:-Demo!Phase6#Pass456}"
CURL_BIN="${CURL_BIN:-$(command -v curl || true)}"
CURL_TIMEOUT_SECONDS="${CURL_TIMEOUT_SECONDS:-15}"

COOKIE_JAR="$(mktemp /tmp/signal-sentinel-cookies.XXXXXX)"
TMP_DIR="$(mktemp -d /tmp/signal-sentinel-demo.XXXXXX)"
trap 'rm -f "$COOKIE_JAR"; rm -rf "$TMP_DIR"' EXIT

HAVE_JQ=0
if command -v jq >/dev/null 2>&1; then
  HAVE_JQ=1
fi
if [ -z "$CURL_BIN" ]; then
  echo "curl is required for this demo script."
  exit 1
fi

print_step() {
  echo
  echo "==> $1"
}

normalize_json_file() {
  local file="$1"
  if [ "$HAVE_JQ" -eq 1 ]; then
    jq -cS . "$file"
  elif command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' "$file"
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
obj = json.loads(path.read_text(encoding="utf-8"))
print(json.dumps(obj, sort_keys=True, separators=(",", ":")))
PY
  else
    cat "$file"
  fi
}

pretty_print() {
  local file="$1"
  if [ "$HAVE_JQ" -eq 1 ]; then
    jq . "$file" 2>/dev/null || cat "$file"
  elif command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' "$file"
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
txt = path.read_text(encoding="utf-8")
try:
    print(json.dumps(json.loads(txt), indent=2))
except Exception:
    print(txt)
PY
  else
    cat "$file"
  fi
}

extract_reset_link() {
  local outbox_file="$1"
  local target_email="$2"
  python3 - <<'PY' "$outbox_file" "$target_email"
import json, pathlib, re, sys

outbox_path = pathlib.Path(sys.argv[1])
target = sys.argv[2]
try:
    rows = json.loads(outbox_path.read_text(encoding="utf-8"))
except Exception:
    print("")
    sys.exit(0)

for row in reversed(rows):
    if row.get("to") != target:
        continue
    for link in row.get("links") or []:
        if "/reset?token=" in link:
            print(link)
            sys.exit(0)
    body = row.get("body") or ""
    match = re.search(r"https?://[^\s\"']+/reset\?token=[^\\s\"']+", body)
    if match:
        print(match.group(0))
        sys.exit(0)

print("")
PY
}

extract_token() {
  local link="$1"
  python3 - <<'PY' "$link"
from urllib.parse import urlparse, parse_qs
import sys
url = sys.argv[1]
parsed = urlparse(url)
token = (parse_qs(parsed.query).get("token") or [""])[0]
print(token)
PY
}

request() {
  local method="$1"
  local path="$2"
  local body_file="$3"
  local data="${4:-}"
  local use_cookies="${5:-1}"

  local curl_args=(
    -sS
    --max-time "$CURL_TIMEOUT_SECONDS"
    -X "$method"
    -H "Content-Type: application/json"
    -o "$body_file"
    -w "%{http_code}"
  )

  if [ "$use_cookies" -eq 1 ]; then
    curl_args+=(-c "$COOKIE_JAR" -b "$COOKIE_JAR")
  fi
  if [ -n "$data" ]; then
    curl_args+=(-d "$data")
  fi

  "$CURL_BIN" "${curl_args[@]}" "$BASE_URL$path"
}

echo "Phase 6 demo starting"
echo "BASE_URL=$BASE_URL"
echo "EMAIL=$EMAIL"

print_step "Preflight: backend health"
if ! "$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -f "$BASE_URL/api/health" >/dev/null; then
  echo "Backend not reachable at BASE_URL=$BASE_URL"
  echo "BASE_URL defaults to http://localhost:8080"
  echo "Override example:"
  echo "  BASE_URL=http://localhost:8081 ./scripts/demo-phase6.sh"
  echo "Start backend first, for example:"
  echo "  cd backend && ./run-service.sh"
  exit 1
fi
echo "Backend is reachable."

print_step "Preflight: dev outbox availability"
OUTBOX_PREFLIGHT="$TMP_DIR/outbox_preflight.json"
outbox_code="$("$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -o "$OUTBOX_PREFLIGHT" -w "%{http_code}" "$BASE_URL/api/dev/outbox")"
if [ "$outbox_code" != "200" ]; then
  echo "Dev outbox endpoint is not available (status $outbox_code)."
  echo "Expected /api/dev/outbox in dev mode for this demo."
  echo "Response:"
  cat "$OUTBOX_PREFLIGHT"
  exit 1
fi
echo "Dev outbox endpoint is available."

print_step "A) Anonymous health check"
HEALTH_FILE="$TMP_DIR/health.json"
"$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -o "$HEALTH_FILE" "$BASE_URL/api/health"
pretty_print "$HEALTH_FILE"
echo "Expected: {\"status\":\"ok\"} (or similar JSON)."

print_step "B) Signup"
SIGNUP_FILE="$TMP_DIR/signup.json"
signup_payload="{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
signup_code="$(request POST /api/auth/signup "$SIGNUP_FILE" "$signup_payload" 1)"
echo "HTTP $signup_code"
pretty_print "$SIGNUP_FILE"
if [ "$signup_code" != "200" ]; then
  echo "Signup failed."
  exit 1
fi
echo "Expected: 200 and auth cookie set."

print_step "C) Verify logged-in"
ME_FILE="$TMP_DIR/me.json"
me_code="$("$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o "$ME_FILE" -w "%{http_code}" "$BASE_URL/api/me")"
echo "HTTP $me_code"
pretty_print "$ME_FILE"
if [ "$me_code" != "200" ]; then
  echo "Expected /api/me to succeed after signup."
  exit 1
fi

print_step "D) Preferences round-trip"
PUT_PREFS_FILE="$TMP_DIR/put_prefs.json"
prefs_payload='{"zipCodes":["02108","98101"],"watchlist":["AAPL","MSFT","BTC-USD"],"newsSourceIds":[]}'
put_code="$(request PUT /api/me/preferences "$PUT_PREFS_FILE" "$prefs_payload" 1)"
echo "PUT /api/me/preferences -> HTTP $put_code"
pretty_print "$PUT_PREFS_FILE"
if [ "$put_code" != "200" ]; then
  echo "Preferences update failed."
  exit 1
fi

GET_PREFS_FILE="$TMP_DIR/get_prefs.json"
get_code="$("$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o "$GET_PREFS_FILE" -w "%{http_code}" "$BASE_URL/api/me/preferences")"
echo "GET /api/me/preferences -> HTTP $get_code"
pretty_print "$GET_PREFS_FILE"
if [ "$get_code" != "200" ]; then
  echo "Preferences read failed."
  exit 1
fi

put_normalized="$(normalize_json_file "$PUT_PREFS_FILE")"
get_normalized="$(normalize_json_file "$GET_PREFS_FILE")"
if [ "$put_normalized" != "$get_normalized" ]; then
  echo "FAIL: preferences mismatch after round-trip."
  echo "PUT payload:"
  pretty_print "$PUT_PREFS_FILE"
  echo "GET payload:"
  pretty_print "$GET_PREFS_FILE"
  exit 1
fi
echo "Preferences round-trip matches PUT payload."

print_step "E) Logout"
LOGOUT_FILE="$TMP_DIR/logout.json"
logout_code="$(request POST /api/auth/logout "$LOGOUT_FILE" "" 1)"
echo "POST /api/auth/logout -> HTTP $logout_code"
if [ "$logout_code" != "200" ]; then
  echo "Logout failed."
  exit 1
fi

ME_AFTER_LOGOUT="$TMP_DIR/me_after_logout.json"
me_after_code="$("$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o "$ME_AFTER_LOGOUT" -w "%{http_code}" "$BASE_URL/api/me")"
echo "GET /api/me after logout -> HTTP $me_after_code"
cat "$ME_AFTER_LOGOUT"
if [ "$me_after_code" != "401" ]; then
  echo "Expected /api/me to return 401 after logout."
  exit 1
fi

print_step "F) Forgot password"
FORGOT_FILE="$TMP_DIR/forgot.json"
forgot_payload="{\"email\":\"$EMAIL\"}"
forgot_code="$(request POST /api/auth/forgot "$FORGOT_FILE" "$forgot_payload" 0)"
echo "HTTP $forgot_code"
cat "$FORGOT_FILE"
if [ "$forgot_code" != "200" ]; then
  echo "Expected forgot-password endpoint to return 200."
  exit 1
fi

print_step "G) Dev outbox token link extraction"
OUTBOX_FILE="$TMP_DIR/outbox.json"
outbox_code2="$("$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -o "$OUTBOX_FILE" -w "%{http_code}" "$BASE_URL/api/dev/outbox")"
echo "GET /api/dev/outbox -> HTTP $outbox_code2"
if [ "$outbox_code2" != "200" ]; then
  echo "Outbox read failed."
  exit 1
fi
reset_link="$(extract_reset_link "$OUTBOX_FILE" "$EMAIL")"
if [ -z "$reset_link" ]; then
  echo "Could not find reset link for $EMAIL in dev outbox."
  echo "Outbox payload:"
  pretty_print "$OUTBOX_FILE"
  exit 1
fi
echo "Reset link: $reset_link"
echo "Expected: a /reset?token=... link."

print_step "H) Reset password"
token="$(extract_token "$reset_link")"
if [ -z "$token" ]; then
  echo "Could not parse token from reset link."
  exit 1
fi
RESET_FILE="$TMP_DIR/reset.json"
reset_payload="{\"token\":\"$token\",\"newPassword\":\"$NEW_PASSWORD\"}"
reset_code="$(request POST /api/auth/reset "$RESET_FILE" "$reset_payload" 0)"
echo "HTTP $reset_code"
cat "$RESET_FILE"
if [ "$reset_code" != "200" ]; then
  echo "Password reset failed."
  exit 1
fi

print_step "I) Login with new password"
LOGIN_FILE="$TMP_DIR/login.json"
login_payload="{\"email\":\"$EMAIL\",\"password\":\"$NEW_PASSWORD\"}"
login_code="$(request POST /api/auth/login "$LOGIN_FILE" "$login_payload" 1)"
echo "HTTP $login_code"
pretty_print "$LOGIN_FILE"
if [ "$login_code" != "200" ]; then
  echo "Login with new password failed."
  exit 1
fi

ME_AFTER_RESET="$TMP_DIR/me_after_reset.json"
me_reset_code="$("$CURL_BIN" -sS --max-time "$CURL_TIMEOUT_SECONDS" -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o "$ME_AFTER_RESET" -w "%{http_code}" "$BASE_URL/api/me")"
echo "GET /api/me after reset-login -> HTTP $me_reset_code"
pretty_print "$ME_AFTER_RESET"
if [ "$me_reset_code" != "200" ]; then
  echo "Expected /api/me to succeed after login with new password."
  exit 1
fi

print_step "J) Optional SSE auth events"
echo "Run in another terminal:"
echo "curl -N \"$BASE_URL/api/stream\" | grep -E \"UserRegistered|LoginSucceeded|PasswordReset\""

echo
echo "PASS ✅ Phase 6 demo completed"
echo "  ✅ Health OK"
echo "  ✅ Auth (signup/login/logout)"
echo "  ✅ Auth cookie OK (/api/me)"
echo "  ✅ Preferences (PUT/GET match)"
echo "  ✅ Forgot/reset (token via dev outbox)"
echo "  ✅ Login with new password"
