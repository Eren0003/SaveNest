#!/usr/bin/env bash
# Creates a local demo account and populates it with sample data via the public API.
# Usage: BASE=http://localhost:8080 EMAIL=demo@savenest.local PASSWORD=... scripts/seed-demo.sh
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
EMAIL="${EMAIL:-demo@savenest.local}"
PASSWORD="${PASSWORD:?set PASSWORD}"
curl() { command curl -m 15 "$@"; }
JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT

csrf() { curl -s -c "$JAR" -b "$JAR" "$BASE/api/auth/csrf" >/dev/null; awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }
post() { local t; t="$(csrf)"; curl -s -c "$JAR" -b "$JAR" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $t" -X "${3:-POST}" "$BASE$1" -d "$2"; echo; }

post /api/auth/register "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"displayName\":\"Demo User\"}" || true
post /api/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"

c1=$(post /api/collections '{"name":"Learning"}' | sed -n 's/.*"id":"\{0,1\}\([^",}]*\).*/\1/p' | head -1)
c2=$(post /api/collections '{"name":"Weekend"}' | sed -n 's/.*"id":"\{0,1\}\([^",}]*\).*/\1/p' | head -1)
q() { [ -n "$1" ] && printf '"%s"' "$1"; }

while IFS='|' read -r url tags col; do
  [ -z "$url" ] && continue
  cid=""; [ "$col" = 1 ] && cid=$(q "$c1"); [ "$col" = 2 ] && cid=$(q "$c2")
  post /api/bookmarks "{\"url\":\"$url\",\"tagNames\":[$tags],\"collectionIds\":[$cid]}" >/dev/null
done <<'EOF'
https://www.youtube.com/watch?v=dQw4w9WgXcQ|"music","classic"|2
https://www.youtube.com/watch?v=8aGhZQkoFbQ|"javascript","talks"|1
https://github.com/vercel/next.js|"react","frameworks"|1
https://github.com/spring-projects/spring-boot|"java","frameworks"|1
https://developer.mozilla.org/en-US/docs/Web/JavaScript|"javascript","docs"|1
https://www.postgresql.org/docs/current/index.html|"database","docs"|1
https://www.imdb.com/title/tt0111161/|"movies"|2
https://www.netflix.com/title/80057281|"series"|2
https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC|"music"|2
https://www.reddit.com/r/programming/|"news"|
https://news.ycombinator.com/|"news"|
https://www.amazon.com/dp/0132350882|"books","shopping"|1
https://twitter.com/nextjs|"social"|
https://www.instagram.com/natgeo/|"social","photography"|2
https://medium.com/@dan_abramov|"react","blog"|1
https://stackoverflow.com/questions/11227809|"performance"|1
https://en.wikipedia.org/wiki/Hash_table|"cs","reference"|1
https://www.coursera.org/learn/machine-learning|"ml","courses"|1
EOF
echo "Seeded $EMAIL"
