#!/bin/sh
# Quickstart Q4/Q9: fdroid build in the image that fdroiddata's CI uses (Debian trixie, JDK 21), following the
# steps of the CI job "fdroid build" (build as user vagrant, pinned fdroidserver, --on-server --test).
# Usage: scripts/fdroid/build.sh [<recipe>]   (default: the scratch recipe)
# The scratch recipe points at $SCRATCH/src (see prepare.sh); pass the reviewed copy instead only for the final
# dry run against GitHub (quickstart Q9). The unsigned APK is copied to $SCRATCH/build-out.
. "$(dirname "$0")/common.sh"

recipe=${1:-$SCRATCH_RECIPE}
[ -f "$recipe" ] || { echo "no recipe at $recipe (run scripts/fdroid/prepare.sh first)"; exit 2; }
ensure_fdroiddata
install_recipe "$recipe"
# The container runs as root, so earlier output is root-owned: remove it with a container, not with the host user.
if [ -d "$SCRATCH/build-out" ]; then
    docker run --rm -v "$SCRATCH:/s" "$LINT_IMAGE" rm -rf /s/build-out
fi
mkdir -p "$SCRATCH/build-out"
set -- $(pinned_fdroidserver)
vercode=$(sed -n 's/^    versionCode: *//p' "$recipe" | head -1)

cat > "$SCRATCH/build.container.sh" <<EOS
export DEBIAN_FRONTEND=noninteractive
. /etc/profile.d/bsenv.sh
apt-get update -qq
apt-get install -y -qq sudo openjdk-21-jdk-headless git >/dev/null
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
java -version 2>&1 | head -1
rm -rf \$fdroidserver
git clone -q --shallow-since="\$FDS_SINCE" https://gitlab.com/fdroid/fdroidserver.git \$fdroidserver
git -C \$fdroidserver checkout -q -B master "\$FDS_SHA"
export PATH="\$fdroidserver:\$PATH" PYTHONPATH="\$fdroidserver:\$fdroidserver/examples" PYTHONUNBUFFERED=true serverwebroot=/tmp
sdkmanager "platform-tools" "build-tools;31.0.0" >/dev/null
git -C \$home_vagrant/gradlew-fdroid pull -q || true
for d in logs tmp unsigned build .android .gradle metadata; do mkdir -p \$home_vagrant/\$d; done
cp /fdroiddata/metadata/$APP_ID.yml \$home_vagrant/metadata/
git config --system --add safe.directory '*'
chown -R vagrant \$home_vagrant
export GRADLE_USER_HOME=\$home_vagrant/.gradle
vfdroid() {
    sudo --preserve-env --user vagrant env PATH="\$PATH" PYTHONPATH="\$PYTHONPATH" PYTHONUNBUFFERED=true TERM=dumb HOME=\$home_vagrant fdroid "\$@"
}
cd \$home_vagrant
vfdroid fetchsrclibs $APP_ID:$vercode --verbose
status=0
(unset CI; vfdroid build --verbose --test --refresh-scanner --on-server --no-tarball $APP_ID:$vercode) || status=\$?
echo "fdroid build exit status: \$status"
ls -l \$home_vagrant/tmp \$home_vagrant/unsigned 2>/dev/null
cp -v \$home_vagrant/tmp/*.apk \$home_vagrant/unsigned/*.apk /out/ 2>/dev/null || true
cp -r \$home_vagrant/logs /out/logs 2>/dev/null || true
chown -R $(id -u):$(id -g) /out
exit \$status
EOS

docker run --rm \
    -e ANDROID_HOME=/opt/android-sdk -e FDS_SHA="$1" -e FDS_SINCE="$2" \
    -v "$FDROIDDATA:/fdroiddata:ro" \
    -v "$SRC:/scratch-src:ro" \
    -v "$SCRATCH/build-out:/out" \
    -v "$SCRATCH/build.container.sh:/run.sh:ro" \
    "$BUILD_IMAGE" bash -eu /run.sh
