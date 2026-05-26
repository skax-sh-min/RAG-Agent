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
    ln -sf "$src" "$dst"
    echo "installed: .git/hooks/$name -> $src"
done

echo "done."
