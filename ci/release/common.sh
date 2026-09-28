# shellcheck shell=bash disable=SC2034  # sourced: what it sets, the scripts that source it read
# Shared by every script under ci/release: where the packages are built, how the version is read and
# checked, and how the inventory is walked. Sourced, never run.
#
# The version has one source, the root VERSION file. Nothing here calculates it twice: a script
# either reads it, or — Prepare Release only — writes it.
#
# Written for the bash 3.2 macOS ships as well as for Linux: the release's packages are built on a
# macOS runner, because the iOS libraries among them can only be built there.

set -euo pipefail

RELEASE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$RELEASE_ROOT"

# Every package is `$GROUP:<artifact>:<version>`, published to $PACKAGES_URL. The Gradle build says
# the same in its `gitHubPackages` repository; changing one means changing the other.
GROUP="dev.voir.reflector"
PACKAGES_URL="https://maven.pkg.github.com/VoirDev/reflector"

# Where build-packages leaves the Maven repository it builds and the manifest that says what it is,
# and where publication reads them from. The Gradle build's `release` repository is the first.
BUILD_DIR="$RELEASE_ROOT/build/release"
REPOSITORY_DIR="$BUILD_DIR/repository"

# Fails the step with a GitHub annotation, which is what a reader sees first.
fail() {
    echo "::error::$*" >&2
    exit 1
}

# Whether $1 is a plain MAJOR.MINOR.PATCH, without a leading `v`, pre-release or build suffix.
is_semver() {
    [[ "$1" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
}

# The version in VERSION, checked.
read_version() {
    [ -f VERSION ] || fail "VERSION is missing from the repository root"
    local version
    version="$(tr -d '[:space:]' < VERSION)"
    is_semver "$version" || fail "VERSION holds '$version', which is not MAJOR.MINOR.PATCH"
    echo "$version"
}

# Whether version $1 is strictly greater than version $2.
version_gt() {
    [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)" = "$1" ]
}

# The inventory, one `artifact task-stem kind` line per package, comments and blanks dropped.
packages() {
    grep -Ev '^[[:space:]]*(#|$)' ci/release/packages
}

# The directory of artifact $1 at version $2, inside the repository at $3 (the built one by default).
artifact_dir() {
    echo "${3:-$REPOSITORY_DIR}/${GROUP//.//}/$1/$2"
}

# The `reflector.revision` property of the POM on standard input: the commit it was built from.
pom_revision() {
    sed -n 's:.*<reflector\.revision>\([0-9a-f]*\)</reflector\.revision>.*:\1:p'
}

# The SHA-256 of file $1, on Linux and macOS alike.
sha256_of() {
    if command -v sha256sum > /dev/null; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Appends `key=value` to the step's outputs when running in Actions; prints it either way.
output() {
    echo "$1=$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1=$2" >> "$GITHUB_OUTPUT"
    fi
}
