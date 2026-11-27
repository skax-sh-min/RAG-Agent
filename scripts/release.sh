#!/bin/sh
# release.sh - build, verify and publish a RAG Agent release from this machine (no GitHub Actions needed).
# (Windows: scripts\windows_release.cmd runs this very script through Git for Windows' bash - same steps, same options.)
#
# What it does, in this order:
#   1. preflight   clean working tree, on main, not behind origin, tag not taken, mvn + Java 21, gh logged in.
#                  Nothing has been changed yet - a failure here costs nothing.
#   2. build       sets the pom version to the release version and runs "mvn clean package" with ALL tests - including the
#                  long-running ones a normal "mvn test" leaves out (@Tag("long-running"): this script's own tests and the launcher
#                  scenarios, pom profile all-tests) - unless -st/--skip-test. A failure puts pom.xml back.
#   3. verify      jar build.version must equal the release version (the app refuses an update whose jar disagrees
#                  with the tag), Start-Class must be the app's, and the manifest's Min-Launcher-Protocol (the launcher
#                  generation this version needs) must be a positive integer. Writes the artifacts to target/release/:
#                    rag-agent-<v>.jar            the file the running apps download
#                    rag-agent-<v>.jar.sha256     sha256sum format - without it apps announce the release but cannot update
#                    rag-agent-<v>-launcher.jar   the ~30 KB launcher a new install starts with (+ its .sha256);
#                                                 apps never mistake it for the application jar
#   4. git         commit "release: v<v>", annotated tag v<v>, then bump the pom to the next -SNAPSHOT and commit that
#                  (otherwise the freshly released build would keep announcing itself as an update).
#   5. publish     pushes the branch and the tag, creates the GitHub release as a DRAFT with its four files and publishes it
#                  once the upload is done, so nobody can see a half-uploaded release.
#
#   With --pr (the branch only accepts pull requests) steps 4-5 change: the commits go on a new branch release/<v>
#   instead of the current branch, which stays untouched; the script pushes that branch and the tag, creates the
#   release as a DRAFT and opens a pull request into the branch you started on. It does NOT publish: merge the PR
#   with "Create a merge commit" (a squash or rebase merge creates new commits, so the tag would no longer be part
#   of the branch history), then publish with: gh release edit v<v> --draft=false
#
# Usage:  scripts/release.sh [VERSION] [options]
#   VERSION         release version X.Y.Z. Default: the pom version without -SNAPSHOT.
#   --build-only    stop after step 3: artifacts are built and verified, pom.xml is restored, git is untouched.
#   --no-publish    do steps 1-4 locally; print the push / gh commands instead of running them.
#   --pr            release through a pull request (see above). Needs the GitHub CLI unless --no-publish is given.
#   -st, --skip-test  pass -DskipTests to Maven (skip the tests; the full suite takes minutes). Default: run all tests,
#                   the long-running ones too (-Pall-tests).
#                   (--skip-tests is still accepted.)
#   --next X.Y.Z-SNAPSHOT   version to continue with. Default: patch + 1.
#   --notes-file F  release notes from file F. Default: let GitHub generate them from the merged work.
#   --any-branch    allow releasing from a branch other than main.
#                   Every run except --build-only asks before building: is this a release, and is the version right?
#                   It asks once more before committing. The answers cannot be skipped (-y/--yes is no longer accepted).
#   -h, --help
# Environment: RELEASE_MVN, RELEASE_GH (command names/paths), RELEASE_BRANCH (default main).
# Publishing needs the GitHub CLI (https://cli.github.com, "gh auth login"); without it use --no-publish and upload the four
# files from target/release/ on the release page by hand.

POM="pom.xml"
RELEASE_BRANCH="${RELEASE_BRANCH:-main}"
GH="${RELEASE_GH:-gh}"
POM_DIRTY=0

die() { echo "release: ERROR: $*" >&2; exit 1; }
info() { echo "release: $*"; }

usage() { sed -n '2,/^POM=/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

restore_pom() {
  if [ "$POM_DIRTY" -eq 1 ]; then
    git checkout -- "$POM" 2>/dev/null
    POM_DIRTY=0
    info "pom.xml restored"
  fi
}

# --- pom --------------------------------------------------------------------------------------------------------------

# Git Bash's sed and awk read files in "text mode" and silently DROP the CR of CRLF line endings (measured: a 80-byte CRLF file
# came back as 67 bytes), which would turn a one-line version change into a whole-file diff. GNU sed's -b stops that; BSD sed
# (macOS) has no -b and does not need it.
if printf x | sed -b p >/dev/null 2>&1; then SED="sed -b"; else SED="sed"; fi

# the project's own <version>: the line right after <artifactId>rag-agent</artifactId> (the parent's version comes before it)
pom_version() {
  $SED -n '/<artifactId>rag-agent<\/artifactId>/{n;s|.*<version>\([^<]*\)</version>.*|\1|p;}' "$POM" | tr -d '\r' | head -n 1
}

set_pom_version() {  # set_pom_version <new>
  tmp="$POM.tmp.$$"
  $SED "/<artifactId>rag-agent<\/artifactId>/{n;s|<version>[^<]*</version>|<version>$1</version>|;}" "$POM" > "$tmp" || { rm -f "$tmp"; return 1; }
  cat "$tmp" > "$POM" && rm -f "$tmp"
  [ "$(pom_version)" = "$1" ]
}

# --- versions ---------------------------------------------------------------------------------------------------------

is_release_version() { echo "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; }
is_snapshot_version() { echo "$1" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$'; }

strip_snapshot() { echo "${1%-SNAPSHOT}"; }

next_snapshot() {  # X.Y.Z -> X.Y.(Z+1)-SNAPSHOT
  major=${1%%.*}
  rest=${1#*.}
  minor=${rest%%.*}
  patch=${rest#*.}
  echo "$major.$minor.$((patch + 1))-SNAPSHOT"
}

# --- files ------------------------------------------------------------------------------------------------------------

# The file goes in through stdin on purpose: given a NAME, GNU sha256sum prefixes the hash with a backslash when the
# name contains one (a Windows path).
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum < "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 < "$1" | cut -d' ' -f1
  else
    openssl dgst -sha256 < "$1" | sed 's/^.*= //'
  fi
}

# print one entry of a zip/jar to stdout
jar_entry() {  # jar_entry <jar> <entry>
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$1" "$2" 2>/dev/null
  else
    d="$(mktemp -d)" || return 1
    (cd "$d" && jar xf "$OLDPWD/$1" "$2" 2>/dev/null) && cat "$d/$2"
    rm -rf "$d"
  fi
}

jar_build_version() { jar_entry "$1" META-INF/build-info.properties | tr -d '\r' | sed -n 's/^build\.version=//p' | head -n 1; }
jar_start_class() { jar_entry "$1" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Start-Class: //p' | head -n 1; }
jar_min_launcher_protocol() { jar_entry "$1" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Min-Launcher-Protocol: //p' | head -n 1; }

confirm() {  # confirm <question> - always asks; anything but y/yes (or end of input) is a no
  printf '%s [y/N] ' "$1"
  read -r answer || answer=""
  case "$answer" in y | Y | yes | YES) return 0 ;; *) return 1 ;; esac
}

# --- main -------------------------------------------------------------------------------------------------------------

main() {
  VERSION=""
  NEXT=""
  BUILD_ONLY=0
  NO_PUBLISH=0
  SKIP_TESTS=0
  ANY_BRANCH=0
  VIA_PR=0
  NOTES_FILE=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --build-only) BUILD_ONLY=1 ;;
      --no-publish) NO_PUBLISH=1 ;;
      --pr) VIA_PR=1 ;;
      -st | --skip-test | --skip-tests) SKIP_TESTS=1 ;;
      --any-branch) ANY_BRANCH=1 ;;
      -y | --yes) die "$1 is no longer accepted: a release always asks for confirmation (use --build-only to build without asking)" ;;
      --next) shift; NEXT="${1:-}" ;;
      --notes-file) shift; NOTES_FILE="${1:-}" ;;
      -h | --help) usage; exit 0 ;;
      -*) die "unknown option: $1 (see --help)" ;;
      *) [ -z "$VERSION" ] || die "more than one version given"; VERSION="$1" ;;
    esac
    shift
  done

  ROOT="$(cd "$(dirname "$0")/.." && pwd)" || exit 1
  cd "$ROOT" || exit 1
  trap 'restore_pom' EXIT
  trap 'restore_pom; exit 130' INT TERM

  PUBLISH=1
  if [ "$BUILD_ONLY" -eq 1 ] || [ "$NO_PUBLISH" -eq 1 ]; then PUBLISH=0; fi

  # ---- 1. preflight -----------------------------------------------------------------------------------------------
  command -v git >/dev/null 2>&1 || die "git not found"
  git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "$ROOT is not a git working tree"
  [ -f "$POM" ] || die "$POM not found in $ROOT"

  if [ -n "${RELEASE_MVN:-}" ]; then
    MVN="$RELEASE_MVN"
  elif command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
  elif [ -x ./mvnw ]; then
    MVN="./mvnw"
  else
    die "Maven not found (install Maven 3.9+ or use the mvnw wrapper)"
  fi
  java_major=$("$MVN" -v 2>&1 | sed -n 's/^Java version: \([0-9][0-9]*\).*/\1/p' | head -n 1)
  [ -n "$java_major" ] || die "could not read the Java version from '$MVN -v'"
  [ "$java_major" -ge 21 ] || die "Java 21+ is required to build (Maven runs on Java $java_major)"
  if [ "$PUBLISH" -eq 1 ]; then
    command -v "$GH" >/dev/null 2>&1 || die "GitHub CLI '$GH' not found - install it (https://cli.github.com) or use --no-publish and upload target/release/* by hand"
    "$GH" auth status >/dev/null 2>&1 || die "GitHub CLI is not logged in - run: $GH auth login"
  fi

  BRANCH=$(git rev-parse --abbrev-ref HEAD)
  [ "$BRANCH" != "HEAD" ] || die "detached HEAD - check out a branch first"
  if [ "$BRANCH" != "$RELEASE_BRANCH" ] && [ "$ANY_BRANCH" -ne 1 ]; then
    die "on branch '$BRANCH', releases are made from '$RELEASE_BRANCH' (use --any-branch to override)"
  fi
  [ -z "$(git status --porcelain)" ] || die "working tree is not clean - commit or stash first (a stray file would end up in the jar):
$(git status --short)"

  git fetch --quiet origin || die "git fetch origin failed"
  if git rev-parse -q --verify "refs/remotes/origin/$BRANCH" >/dev/null; then
    behind=$(git rev-list --count "HEAD..origin/$BRANCH")
    [ "$behind" -eq 0 ] || die "branch is $behind commit(s) behind origin/$BRANCH - pull first"
    ahead=$(git rev-list --count "origin/$BRANCH..HEAD")
    [ "$ahead" -eq 0 ] || info "note: $ahead local commit(s) not on origin yet - they are pushed together with the release"
  fi

  CURRENT=$(pom_version)
  [ -n "$CURRENT" ] || die "could not read the project version from $POM"
  if [ -z "$VERSION" ]; then
    is_snapshot_version "$CURRENT" || die "pom version is '$CURRENT' (not X.Y.Z-SNAPSHOT) - pass the release version explicitly"
    VERSION=$(strip_snapshot "$CURRENT")
  fi
  is_release_version "$VERSION" || die "release version must look like 1.2.3 (got '$VERSION')"
  [ -n "$NEXT" ] || NEXT=$(next_snapshot "$VERSION")
  is_snapshot_version "$NEXT" || die "--next must look like 1.2.4-SNAPSHOT (got '$NEXT')"
  if [ -n "$NOTES_FILE" ]; then [ -f "$NOTES_FILE" ] || die "notes file not found: $NOTES_FILE"; fi

  TAG="v$VERSION"
  JAR_NAME="rag-agent-$VERSION.jar"
  LAUNCHER_NAME="rag-agent-$VERSION-launcher.jar"
  if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then die "tag $TAG already exists locally"; fi
  if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then die "tag $TAG already exists on origin"; fi
  RBRANCH="release/$VERSION"
  if [ "$VIA_PR" -eq 1 ]; then
    if git rev-parse -q --verify "refs/heads/$RBRANCH" >/dev/null; then die "branch $RBRANCH already exists locally (a previous --pr run? delete it or finish that release)"; fi
    if git ls-remote --exit-code --heads origin "refs/heads/$RBRANCH" >/dev/null 2>&1; then die "branch $RBRANCH already exists on origin (a previous --pr run? delete it or finish that release)"; fi
  fi

  if [ "$VIA_PR" -eq 1 ]; then
    info "releasing $VERSION through a pull request: commits go on $RBRANCH, '$BRANCH' stays untouched (pom is $CURRENT, next will be $NEXT)"
  else
    info "releasing $VERSION from '$BRANCH' (pom is $CURRENT, next will be $NEXT)"
  fi

  # ask before the build (minutes) - a wrong version or an accidental run stops here with nothing changed
  if [ "$BUILD_ONLY" -ne 1 ]; then
    if [ "$VIA_PR" -eq 1 ] && [ "$PUBLISH" -eq 1 ]; then
      what="build, commit on $RBRANCH, push it with tag $TAG, create a DRAFT GitHub release and open a pull request into '$BRANCH'"
    elif [ "$VIA_PR" -eq 1 ]; then
      what="build and commit on $RBRANCH locally (nothing is pushed)"
    elif [ "$PUBLISH" -eq 1 ]; then
      what="build, commit, push '$BRANCH' + tag $TAG to origin and PUBLISH the GitHub release"
    else
      what="build and commit on '$BRANCH' locally (nothing is pushed)"
    fi
    confirm "Make a release now? ($what)" || die "aborted before building (nothing changed)"
    confirm "Release version $VERSION (tag $TAG; pom is $CURRENT, next will be $NEXT) - is this the right version?"       || die "aborted before building (nothing changed) - pass the version explicitly: scripts/release.sh X.Y.Z"
  fi

  # ---- 2. build ---------------------------------------------------------------------------------------------------
  set_pom_version "$VERSION" || { git checkout -- "$POM" 2>/dev/null; die "could not set the pom version"; }
  POM_DIRTY=1
  if [ "$SKIP_TESTS" -eq 1 ]; then
    info "building (tests skipped): $MVN -B clean package -DskipTests"
    "$MVN" -B clean package -DskipTests || die "build failed"
  else
    # -Pall-tests: also the long-running tests a normal "mvn test" skips (@Tag("long-running") - release scripts, launcher scenarios)
    info "building with all tests: $MVN -B clean package -Pall-tests   (this takes a while - the long-running tests run too)"
    "$MVN" -B clean package -Pall-tests || die "build failed"
  fi

  # ---- 3. verify + artifacts --------------------------------------------------------------------------------------
  OUT="target/release"
  rm -rf "$OUT"
  mkdir -p "$OUT" || die "cannot create $OUT"
  [ -f "target/$JAR_NAME" ] || die "the build did not produce target/$JAR_NAME"
  cp "target/$JAR_NAME" "$OUT/$JAR_NAME" || die "cannot copy the jar"
  built=$(jar_build_version "$OUT/$JAR_NAME")
  [ "$built" = "$VERSION" ] || die "the jar says build.version='$built', expected '$VERSION' - not releasing"
  start=$(jar_start_class "$OUT/$JAR_NAME")
  [ "$start" = "com.example.ragagent.RagAgentApplication" ] || die "unexpected Start-Class '$start' in the jar - not releasing"
  # which launcher generation this version needs (pom property min.launcher.protocol -> jar manifest). Launchers and apps read it from
  # the downloaded jar; a missing or non-numeric value would make them treat the release as "any launcher will do".
  minproto=$(jar_min_launcher_protocol "$OUT/$JAR_NAME")
  case "$minproto" in
    ''|*[!0-9]*|0) die "the jar's Min-Launcher-Protocol is '$minproto' (expected a positive integer; pom property min.launcher.protocol) - not releasing" ;;
  esac
  if [ "$minproto" -gt 1 ]; then
    info "NOTE: this release needs launcher protocol $minproto - an older launcher stops the update with 'launcher-too-old'; say so in the release notes"
  fi
  sum=$(sha256_of "$OUT/$JAR_NAME")
  printf '%s  %s\n' "$sum" "$JAR_NAME" > "$OUT/$JAR_NAME.sha256" || die "cannot write the checksum file"
  [ "$(sha256_of "$OUT/$JAR_NAME")" = "$sum" ] || die "checksum changed while writing"
  size=$(wc -c < "$OUT/$JAR_NAME" | tr -d ' ')
  info "built and verified: $OUT/$JAR_NAME ($size bytes, sha256 $(echo "$sum" | cut -c1-16)...)"

  # the launcher jar (built next to the app jar by the second maven-jar-plugin execution): a new install starts with it
  [ -f "target/$LAUNCHER_NAME" ] || die "the build did not produce target/$LAUNCHER_NAME (maven-jar-plugin 'launcher-jar' execution)"
  cp "target/$LAUNCHER_NAME" "$OUT/$LAUNCHER_NAME" || die "cannot copy the launcher jar"
  lmain=$(jar_entry "$OUT/$LAUNCHER_NAME" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Main-Class: //p' | head -n 1)
  [ "$lmain" = "com.example.ragagent.launcher.Launcher" ] || die "unexpected Main-Class '$lmain' in the launcher jar - not releasing"
  lsum=$(sha256_of "$OUT/$LAUNCHER_NAME")
  printf '%s  %s\n' "$lsum" "$LAUNCHER_NAME" > "$OUT/$LAUNCHER_NAME.sha256" || die "cannot write the launcher checksum file"
  lsize=$(wc -c < "$OUT/$LAUNCHER_NAME" | tr -d ' ')
  info "built and verified: $OUT/$LAUNCHER_NAME ($lsize bytes)"

  if [ "$BUILD_ONLY" -eq 1 ]; then
    restore_pom
    info "--build-only: git was not touched; artifacts are in $OUT/"
    exit 0
  fi

  # ---- 4. git -----------------------------------------------------------------------------------------------------
  if [ "$VIA_PR" -eq 1 ] && [ "$PUBLISH" -eq 1 ]; then
    confirm "Create branch $RBRANCH with the release commit, tag $TAG and the bump to $NEXT, push the branch + tag, create the DRAFT release and open a pull request into '$BRANCH'?" || die "aborted (nothing committed)"
  elif [ "$VIA_PR" -eq 1 ]; then
    confirm "Create branch $RBRANCH with the release commit, tag $TAG and the bump to $NEXT locally (nothing is pushed)?" || die "aborted (nothing committed)"
  elif [ "$PUBLISH" -eq 1 ]; then
    confirm "Commit, tag $TAG, bump to $NEXT, push '$BRANCH' + $TAG to origin and publish the GitHub release?" || die "aborted (nothing committed)"
  else
    confirm "Commit, tag $TAG and bump to $NEXT locally (nothing is pushed)?" || die "aborted (nothing committed)"
  fi
  if [ "$VIA_PR" -eq 1 ]; then
    # the uncommitted pom change comes along; the starting branch is not touched
    git checkout -q -b "$RBRANCH" || die "could not create branch $RBRANCH"
  fi
  git add "$POM" && git commit -q -m "release: $TAG" || die "git commit failed (is user.name / user.email set?)"
  POM_DIRTY=0
  git tag -a "$TAG" -m "Release $VERSION" || die "git tag failed - the release commit is already made, tag it by hand: git tag -a $TAG -m 'Release $VERSION'"
  set_pom_version "$NEXT" || die "could not set the next version - set the pom to $NEXT by hand and commit"
  git add "$POM" && git commit -q -m "chore: begin $NEXT" || die "git commit failed for the version bump - commit the pom ($NEXT) by hand"
  if [ "$VIA_PR" -eq 1 ]; then
    info "committed release: $TAG on $RBRANCH, now on $NEXT ('$BRANCH' is untouched)"
  else
    info "committed release: $TAG, now on $NEXT"
  fi

  RELEASE_FILES="$OUT/$JAR_NAME $OUT/$JAR_NAME.sha256 $OUT/$LAUNCHER_NAME $OUT/$LAUNCHER_NAME.sha256"
  if [ -n "$NOTES_FILE" ]; then
    notes_args="--notes-file $NOTES_FILE"
  else
    notes_args="--generate-notes"
  fi
  PR_BODY="Release $VERSION ($TAG). Merge this pull request with \"Create a merge commit\" - not squash or rebase: the tag $TAG points at the release commit of this branch and has to stay reachable from $BRANCH.

After merging, publish the draft release: $GH release edit $TAG --draft=false"

  if [ "$PUBLISH" -eq 0 ]; then
    info "not publishing (--no-publish). To finish by hand:"
    if [ "$VIA_PR" -eq 1 ]; then
      echo "  git push origin $RBRANCH && git push origin $TAG"
      echo "  $GH release create $TAG $RELEASE_FILES --title $VERSION --generate-notes --verify-tag --draft"
      echo "  $GH pr create --base $BRANCH --head $RBRANCH --title \"release: $TAG\" --body \"Merge with 'Create a merge commit' (not squash/rebase) so $TAG stays reachable from $BRANCH\""
      echo "  (merge the pull request with 'Create a merge commit', then publish the draft:)"
      echo "  $GH release edit $TAG --draft=false"
      git checkout -q "$BRANCH" || info "could not switch back to '$BRANCH' - run: git checkout $BRANCH"
    else
      echo "  git push origin $BRANCH && git push origin $TAG"
      echo "  $GH release create $TAG $RELEASE_FILES --title $VERSION --generate-notes --verify-tag --draft"
      echo "  $GH release edit $TAG --draft=false"
      echo "  (or upload the four files from $OUT/ on the release page of tag $TAG)"
      echo "  ('$BRANCH' only accepts pull requests? run the whole script with --pr instead, or:"
      echo "   git branch $RBRANCH && git push origin $RBRANCH $TAG, open a pull request into $BRANCH and merge it with 'Create a merge commit')"
    fi
    exit 0
  fi

  # ---- 5. publish -------------------------------------------------------------------------------------------------
  if [ "$VIA_PR" -eq 1 ]; then
    git push origin "$RBRANCH" || die "pushing $RBRANCH failed. Everything is committed and tagged locally on $RBRANCH; retry: git push origin $RBRANCH && git push origin $TAG, then create the draft release and the pull request (see --no-publish output)"
    git push origin "$TAG" || die "pushing the tag failed. Retry: git push origin $TAG, then create the draft release and the pull request (files are in $OUT/)"
  else
    git push origin "$BRANCH" || die "pushing the branch failed. The release is committed and tagged locally. If '$BRANCH' only accepts pull requests (a protected branch): git branch $RBRANCH && git push origin $RBRANCH $TAG, open a pull request into $BRANCH and merge it with 'Create a merge commit' (not squash/rebase), then create the release (see --no-publish output) - or start over with --pr. Otherwise fix the push, then: git push origin $BRANCH && git push origin $TAG && create the release"
    git push origin "$TAG" || die "pushing the tag failed. Retry: git push origin $TAG, then create the release (files are in $OUT/)"
  fi
  # shellcheck disable=SC2086
  "$GH" release create "$TAG" $RELEASE_FILES \
    --title "$VERSION" $notes_args --verify-tag --draft \
    || die "creating the draft release failed. The branch and tag are pushed; retry: $GH release create $TAG $RELEASE_FILES --title $VERSION $notes_args --verify-tag --draft"
  if [ "$VIA_PR" -eq 1 ]; then
    # the release stays a DRAFT until the pull request is merged: the tag is only part of '$BRANCH' after a merge commit
    "$GH" pr create --base "$BRANCH" --head "$RBRANCH" --title "release: $TAG" --body "$PR_BODY" \
      || die "the draft release $TAG was created but the pull request was not - open it by hand ($RBRANCH -> $BRANCH), merge it with 'Create a merge commit', then: $GH release edit $TAG --draft=false"
    git checkout -q "$BRANCH" || info "could not switch back to '$BRANCH' - run: git checkout $BRANCH"
    info "pull request opened ($RBRANCH -> $BRANCH); the release $TAG is still a DRAFT"
    info "next: merge it with 'Create a merge commit' (not squash/rebase), then: $GH release edit $TAG --draft=false"
    exit 0
  fi
  "$GH" release edit "$TAG" --draft=false \
    || die "the draft release $TAG was created with its files but not published - publish it on GitHub (Releases > Draft) or: $GH release edit $TAG --draft=false"
  info "published $TAG: $(git remote get-url origin 2>/dev/null)"
}

# `RELEASE_LIB_ONLY=1 . scripts/release.sh` loads the functions without running anything (the tests do that).
if [ -z "${RELEASE_LIB_ONLY:-}" ]; then
  main "$@"
fi
