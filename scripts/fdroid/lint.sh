#!/bin/sh
# Quickstart Q3: fdroid readmeta, rewritemeta and lint for app.meetingrecorder in debian:trixie-slim with
# the fdroidserver package, as the CI of fdroiddata does.
# Usage: scripts/fdroid/lint.sh [<recipe>]
# Default: the reviewed copy with the real https Repo and the scratch commit (lint rejects a local Repo path).
. "$(dirname "$0")/common.sh"

recipe=${1:-}
if [ -z "$recipe" ]; then
    commit=$(git -C "$SRC" rev-parse HEAD 2>/dev/null || echo 0000000000000000000000000000000000000000)
    make_lint_recipe "$commit"
    recipe=$LINT_RECIPE
fi
ensure_fdroiddata
install_recipe "$recipe"
cp "$recipe" "$SCRATCH/recipe.linted.before"

set -- $(pinned_fdroidserver)
{ fdroidserver_setup; cat <<EOS
cd /fdroiddata
fdroid readmeta
fdroid rewritemeta $APP_ID
fdroid lint $APP_ID
EOS
} > "$SCRATCH/lint.container.sh"
docker run --rm -e FDS_SHA="$1" -e FDS_SINCE="$2" -v "$FDROIDDATA:/fdroiddata" -v "$SCRATCH/lint.container.sh:/run.sh:ro" "$LINT_IMAGE" sh -eu /run.sh
if ! cmp -s "$SCRATCH/recipe.linted.before" "$FDROIDDATA/metadata/$APP_ID.yml"; then
    echo "NOTE: fdroid rewritemeta changed the recipe; diff follows (take it into the reviewed copy):"
    diff -u "$SCRATCH/recipe.linted.before" "$FDROIDDATA/metadata/$APP_ID.yml" || true
fi
