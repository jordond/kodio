#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

usage() {
  cat <<'USAGE'
Usage:
  scripts/publish-maven-central.sh [version]

Loads Maven Central and signing credentials from .env, then runs:
  ./gradlew publishToMavenCentral --no-configuration-cache -Pkodio.version=<version>

If version is omitted, the script uses kodio.version from gradle.properties.

Required .env keys:
  MAVEN_CENTRAL_USERNAME
  MAVEN_CENTRAL_PASSWORD
  SIGNING_KEY_ID
  SIGNING_PASSWORD
  GPG_KEY_CONTENTS

Optional:
  ENV_FILE=/path/to/.env scripts/publish-maven-central.sh 0.1.5-jordond.1
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing .env file: $ENV_FILE" >&2
  echo "Create it with the required keys, or set ENV_FILE=/path/to/.env." >&2
  exit 1
fi

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

required_vars=(
  MAVEN_CENTRAL_USERNAME
  MAVEN_CENTRAL_PASSWORD
  SIGNING_KEY_ID
  SIGNING_PASSWORD
  GPG_KEY_CONTENTS
)

missing_vars=()
for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    missing_vars+=("$var_name")
  fi
done

if (( ${#missing_vars[@]} > 0 )); then
  echo "Missing required .env keys: ${missing_vars[*]}" >&2
  exit 1
fi

version="${1:-}"
if [[ -z "$version" ]]; then
  echo "Could not determine kodio.version. Pass a version explicitly." >&2
  exit 1
fi

export ORG_GRADLE_PROJECT_mavenCentralUsername="$MAVEN_CENTRAL_USERNAME"
export ORG_GRADLE_PROJECT_mavenCentralPassword="$MAVEN_CENTRAL_PASSWORD"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId="$SIGNING_KEY_ID"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$SIGNING_PASSWORD"
export ORG_GRADLE_PROJECT_signingInMemoryKey="$GPG_KEY_CONTENTS"

cd "$ROOT_DIR"
./gradlew publishAllPublicationsToMavenCentral --no-configuration-cache -Pkodio.version="$version"
