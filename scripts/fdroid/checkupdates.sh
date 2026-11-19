#!/bin/sh
# Quickstart Q5: fdroid checkupdates for app.meetingrecorder (debian:trixie-slim with the fdroidserver package).
# Usage: scripts/fdroid/checkupdates.sh [<recipe>]   (default: the scratch recipe)
. "$(dirname "$0")/common.sh"

recipe=${1:-$SCRATCH_RECIPE}
[ -f "$recipe" ] || { echo "no recipe at $recipe (run make_scratch_recipe first)"; exit 2; }
ensure_fdroiddata
install_recipe "$recipe"
cp "$recipe" "$SCRATCH/recipe.checked.before"
# fdroid checkupdates refuses a metadata repository with uncommitted changes: commit the recipe in the scratch
# clone of fdroiddata (a throwaway local commit, never pushed anywhere).
git -C "$FDROIDDATA" add "metadata/$APP_ID.yml"
git -C "$FDROIDDATA" -c user.name=scratch -c user.email=scratch@example.invalid commit -q --allow-empty -m "scratch: recipe for the local checkupdates run"

set -- $(pinned_fdroidserver)
{ fdroidserver_setup; cat <<EOS
cd /fdroiddata
fdroid checkupdates -v $APP_ID
EOS
} > "$SCRATCH/checkupdates.container.sh"
docker run --rm -e FDS_SHA="$1" -e FDS_SINCE="$2" -v "$FDROIDDATA:/fdroiddata" -v "$SRC:/scratch-src:ro" -v "$SCRATCH/checkupdates.container.sh:/run.sh:ro" "$LINT_IMAGE" sh -eu /run.sh
diff -u "$SCRATCH/recipe.checked.before" "$FDROIDDATA/metadata/$APP_ID.yml" || echo "NOTE: checkupdates changed the recipe (see diff above)"
