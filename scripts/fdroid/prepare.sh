#!/bin/sh
# Creates the scratch source repository (a commit of the current working tree, or of <ref>) and the scratch
# recipe that points at it. Run this before lint.sh, checkupdates.sh and build.sh.
# Usage: scripts/fdroid/prepare.sh [<ref>]
. "$(dirname "$0")/common.sh"

commit=$(make_scratch_source "${1:-}")
make_scratch_recipe "$commit"
echo "scratch source: $SRC at $commit"
echo "scratch recipe: $SCRATCH_RECIPE"
