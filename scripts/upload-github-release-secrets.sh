#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_properties="$repo_root/local.properties"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required. Install it and run gh auth login first." >&2
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not authenticated, or the stored token is invalid." >&2
  echo "Run: gh auth logout -h github.com && gh auth login -h github.com" >&2
  exit 1
fi

read_property() {
  local key="$1"
  grep -E "^${key}=" "$local_properties" | tail -n 1 | cut -d= -f2-
}

keystore_file="$(read_property ANDROID_KEYSTORE_FILE)"
keystore_password="$(read_property ANDROID_KEYSTORE_PASSWORD)"
key_alias="$(read_property ANDROID_KEY_ALIAS)"
key_password="$(read_property ANDROID_KEY_PASSWORD)"

if [[ -z "$keystore_file" || -z "$keystore_password" || -z "$key_alias" || -z "$key_password" ]]; then
  echo "Missing Android signing values in local.properties." >&2
  exit 1
fi

if [[ "$keystore_file" != /* ]]; then
  keystore_file="$repo_root/$keystore_file"
fi

if [[ ! -f "$keystore_file" ]]; then
  echo "Keystore not found: $keystore_file" >&2
  exit 1
fi

base64_secret="$(base64 -w 0 "$keystore_file")"

gh secret set ANDROID_KEYSTORE_BASE64 --body "$base64_secret"
gh secret set ANDROID_KEYSTORE_PASSWORD --body "$keystore_password"
gh secret set ANDROID_KEY_ALIAS --body "$key_alias"
gh secret set ANDROID_KEY_PASSWORD --body "$key_password"

echo "GitHub release signing secrets uploaded."
