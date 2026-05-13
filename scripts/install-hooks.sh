#!/bin/sh
# Install git hooks for this repository.
# Run once after cloning: sh scripts/install-hooks.sh
set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOKS_SRC="$REPO_ROOT/scripts/hooks"
HOOKS_DST="$REPO_ROOT/.git/hooks"

for src in "$HOOKS_SRC"/*; do
    name="$(basename "$src")"
    dst="$HOOKS_DST/$name"
    cp "$src" "$dst"
    chmod +x "$dst"
    echo "installed: .git/hooks/$name"
done

echo "done."
