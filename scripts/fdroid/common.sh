#!/bin/sh
# Shared settings and helpers of the local F-Droid verification (specs/004-fdroid-release, research R-7).
# Source this file from the other scripts; it changes nothing by itself. Nothing here edits the real recipe
# or pushes anything: all work happens in the scratch area, which is never committed.

set -eu

ROOT=$(git rev-parse --show-toplevel)
SCRATCH=${SCRATCH:-"$HOME/fdroid-work"}
BUILD_IMAGE=${BUILD_IMAGE:-registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie}
LINT_IMAGE=${LINT_IMAGE:-debian:trixie-slim}
APP_ID=app.meetingrecorder
RECIPE_COPY="$ROOT/specs/004-fdroid-release/contracts/$APP_ID.yml"
FDROIDDATA="$SCRATCH/fdroiddata"
SRC="$SCRATCH/src"
SCRATCH_RECIPE="$SCRATCH/recipes/$APP_ID.yml"

# Clone fdroiddata (shallow) once; later runs reuse it.
ensure_fdroiddata() {
    if [ ! -d "$FDROIDDATA/.git" ]; then
        mkdir -p "$SCRATCH"
        git clone --depth 1 https://gitlab.com/fdroid/fdroiddata.git "$FDROIDDATA"
    fi
}

# Create a scratch source repository with a real commit of the current working tree (committed and
# uncommitted changes), so that F-Droid can clone a commit without a commit in this repository.
# Usage: make_scratch_source [<ref>]   (<ref> = a tag or commit to use as is, without the working tree)
# Prints the commit hash of the scratch repository.
make_scratch_source() {
    ref=${1:-}
    rm -rf "$SRC"
    mkdir -p "$SCRATCH"
    git clone --quiet --local "$ROOT" "$SRC"
    if [ -n "$ref" ]; then
        git -C "$SRC" checkout --quiet "$ref"
    fi
    # The engine submodule is taken from the local checkout, not from the network.
    git -C "$SRC" config submodule.whisper/src/main/cpp/whisper.cpp.url "$ROOT/whisper/src/main/cpp/whisper.cpp"
    git -C "$SRC" -c protocol.file.allow=always submodule update --init --quiet
    if [ -z "$ref" ]; then
        git -C "$ROOT" diff HEAD --binary --ignore-submodules=all | git -C "$SRC" apply --allow-empty
        git -C "$ROOT" ls-files --others --exclude-standard -z \
            | tar -C "$ROOT" --null -T - -cf - | tar -xf - -C "$SRC"
        git -C "$SRC" add -A
        git -C "$SRC" -c user.name=scratch -c user.email=scratch@example.invalid commit --quiet --allow-empty -m "scratch: working tree for the local F-Droid build"
    fi
    git -C "$SRC" rev-parse HEAD
}

# Write the scratch recipe: the reviewed copy with Repo pointing at the scratch source (as mounted in the
# container) and commit set to the given hash. The real recipe is never edited.
# Usage: make_scratch_recipe <commit>
make_scratch_recipe() {
    commit=$1
    mkdir -p "$(dirname "$SCRATCH_RECIPE")"
    sed -e "s#^Repo: .*#Repo: /scratch-src#" -e "s#^\\( *commit:\\) .*#\\1 $commit#" "$RECIPE_COPY" > "$SCRATCH_RECIPE"
}

# Write the lint recipe: the reviewed copy unchanged (real https Repo) except that the commit placeholder is
# replaced by a 40-hex hash, because lint does not clone and rejects a local Repo path.
# Usage: make_lint_recipe <commit>
make_lint_recipe() {
    commit=$1
    LINT_RECIPE="$SCRATCH/recipes/lint/$APP_ID.yml"
    mkdir -p "$(dirname "$LINT_RECIPE")"
    sed -e "s#^\\( *commit:\\) TAG_COMMIT_PLACEHOLDER#\\1 $commit#" "$RECIPE_COPY" > "$LINT_RECIPE"
}

# Copy a recipe into the fdroiddata clone as metadata/<app id>.yml.
install_recipe() {
    cp "$1" "$FDROIDDATA/metadata/$APP_ID.yml"
}

# The fdroidserver revision that fdroiddata's CI pins (root of trust), read from its .gitlab-ci.yml:
# prints "<sha> <shallow-since date>". The CI installs the Debian package for its dependencies and puts this
# git revision first on the PATH; the Debian package alone is older and stricter about unrelated apps.
pinned_fdroidserver() {
    ci="$FDROIDDATA/.gitlab-ci.yml"
    sha=$(sed -n 's/.*checkout -B master \([0-9a-f]\{40\}\).*/\1/p' "$ci" | head -1)
    since=$(sed -n 's/.*--shallow-since=\([0-9-]*\).*/\1/p' "$ci" | head -1)
    echo "$sha $since"
}

# Container commands that set up fdroidserver like the CI does. Needs FDS_SHA and FDS_SINCE in the environment.
fdroidserver_setup() {
    cat <<'EOS'
export DEBIAN_FRONTEND=noninteractive LC_ALL=C.UTF-8
apt-get update -qq
apt-get install -qy --no-install-recommends fdroidserver apksigner mercurial git git-svn brz python3-launchpadlib locales curl ca-certificates >/dev/null 2>&1
git config --global --add safe.directory "*"
rm -rf /opt/fdroidserver-git
git clone -q --shallow-since="$FDS_SINCE" https://gitlab.com/fdroid/fdroidserver.git /opt/fdroidserver-git
git -C /opt/fdroidserver-git checkout -q -B master "$FDS_SHA"
export PATH="/opt/fdroidserver-git:$PATH" PYTHONPATH="/opt/fdroidserver-git:/opt/fdroidserver-git/examples" PYTHONUNBUFFERED=true serverwebroot=/tmp
chmod 0600 /fdroiddata/config.yml 2>/dev/null || true
echo "fdroidserver: git $FDS_SHA ($(fdroid --version 2>&1 | tail -1)); Debian package $(dpkg -s fdroidserver | sed -n 's/^Version: //p')"
EOS
}
