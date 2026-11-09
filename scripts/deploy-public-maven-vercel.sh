#!/usr/bin/env bash
# Deploy public-maven to the public Maven host on Vercel.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${ROOT}/public-maven"
if [[ ! -d "${SRC}/com" ]]; then
  echo "Missing ${SRC}/com — run ./gradlew :reader-core:publishAllPublicationsToPublicMavenRepository first" >&2
  exit 1
fi

cat > "$SRC/vercel.json" <<'EOF'
{
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        { "key": "Access-Control-Allow-Origin", "value": "*" },
        { "key": "Access-Control-Allow-Methods", "value": "GET, HEAD, OPTIONS" }
      ]
    },
    {
      "source": "/(.*)\\.aar",
      "headers": [{ "key": "Content-Type", "value": "application/java-archive" }]
    },
    {
      "source": "/(.*)\\.jar",
      "headers": [{ "key": "Content-Type", "value": "application/java-archive" }]
    },
    {
      "source": "/(.*)\\.pom",
      "headers": [{ "key": "Content-Type", "value": "application/xml" }]
    },
    {
      "source": "/(.*)\\.module",
      "headers": [{ "key": "Content-Type", "value": "application/json" }]
    },
    {
      "source": "/(.*)\\.xml",
      "headers": [{ "key": "Content-Type", "value": "application/xml" }]
    }
  ]
}
EOF
cat > "$SRC/index.html" <<'EOF'
<!doctype html><meta charset=utf-8><title>reader-engine Maven</title>
<pre>Public Maven repository for reader-engine
maven { url = uri("https://reader-engine-maven.vercel.app/") }
implementation("com.readerengine:reader-core-android:0.3.1")
</pre>
EOF

cd "$SRC"
ARGS=(deploy --yes --prod --name reader-engine-maven)
if [[ -n "${VERCEL_TOKEN:-}" ]]; then
  ARGS+=(--token "$VERCEL_TOKEN")
fi
npx -y vercel@latest "${ARGS[@]}"

echo "OK: https://reader-engine-maven.vercel.app/"
echo "  maven { url = uri(\"https://reader-engine-maven.vercel.app/\") }"
echo "  implementation(\"com.readerengine:reader-core-android:0.3.1\")"
