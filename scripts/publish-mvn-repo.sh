#!/usr/bin/env bash
# Publish public-maven/ contents to the mvn-repo branch (preserving prior versions).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${ROOT}/public-maven"
if [[ ! -d "${SRC}/com" ]]; then
  echo "Missing ${SRC}/com — run ./gradlew :reader-core:publishAllPublicationsToPublicMavenRepository first" >&2
  exit 1
fi

VERSION_LABEL="${VERSION:-${1:-0.3.2}}"
WORKTREE_DIR="$(mktemp -d)"
cleanup() {
  git -C "$ROOT" worktree remove --force "$WORKTREE_DIR" >/dev/null 2>&1 || rm -rf "$WORKTREE_DIR"
}
trap cleanup EXIT

if git -C "$ROOT" ls-remote --exit-code --heads origin mvn-repo >/dev/null 2>&1; then
  git -C "$ROOT" fetch origin mvn-repo
  git -C "$ROOT" worktree add "$WORKTREE_DIR" origin/mvn-repo
  git -C "$WORKTREE_DIR" checkout -B mvn-repo
else
  git -C "$ROOT" worktree add --detach "$WORKTREE_DIR"
  git -C "$WORKTREE_DIR" checkout --orphan mvn-repo
  git -C "$WORKTREE_DIR" rm -rf . >/dev/null 2>&1 || true
fi

cp -R "${SRC}/." "${WORKTREE_DIR}/"
git -C "$WORKTREE_DIR" add -A
if ! git -C "$WORKTREE_DIR" diff --cached --quiet; then
  git -C "$WORKTREE_DIR" commit -m "chore(maven): publish reader-core ${VERSION_LABEL}"
  git -C "$WORKTREE_DIR" push origin HEAD:refs/heads/mvn-repo
  echo "OK: published ${VERSION_LABEL} to mvn-repo branch."
else
  echo "No changes to publish on mvn-repo branch."
fi
