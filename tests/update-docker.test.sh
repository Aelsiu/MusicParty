#!/usr/bin/env bash
set -Eeuo pipefail

# Run with Bash on Linux or Git Bash. All Docker state and application files are fake.
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
mkdir -p -- "$repo_dir/target"
test_root=$(mktemp -d "$repo_dir/target/update-docker-tests.XXXXXX")
cleanup() {
    [[ "$test_root" == "$repo_dir/target/update-docker-tests."* ]] || return 1
    rm -rf -- "$test_root"
}
trap cleanup EXIT

old_image="sha256:$(printf 'a%.0s' {1..64})"
new_image="sha256:$(printf 'b%.0s' {1..64})"
export MOCK_OLD_IMAGE="$old_image" MOCK_NEW_IMAGE="$new_image"
export MOCK_REAL_MV
MOCK_REAL_MV=$(command -v mv)
mock_bin="$test_root/bin"
mkdir -- "$mock_bin"

cat > "$mock_bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s ' "$@" >> "$MOCK_DIR/docker.log"
printf '\n' >> "$MOCK_DIR/docker.log"
image_path() { printf '%s/images/%s' "$MOCK_DIR" "${1//[^a-zA-Z0-9._-]/_}"; }
resolve_image() { cat -- "$(image_path "$1")"; }
case "$1" in
    inspect)
        format=$3
        case "$format" in
            '{{.Image}}') cat -- "$MOCK_DIR/current-image" ;;
            *'/app/config'*)
                printf 'bind:%s/config\n' "${MOCK_WRONG_MOUNT:-$MOCK_PROJECT}"
                ;;
            *'/app/cached_media'*) printf 'bind:%s/music_party/cached_media\n' "$MOCK_PROJECT" ;;
            *'/app/data'*) printf 'bind:%s/music_party/data\n' "$MOCK_PROJECT" ;;
            *) exit 90 ;;
        esac
        ;;
    image)
        [[ "$2" == inspect ]] || exit 90
        if [[ "${3:-}" == --format ]]; then resolve_image "$5"; else resolve_image "$3"; fi
        ;;
    tag)
        resolve_image "$2" > "$(image_path "$3")"
        ;;
    build)
        shift
        offline=false
        no_pull=false
        runtime=''
        target=''
        dockerfile=''
        while (($#)); do
            case "$1" in
                --pull=false) no_pull=true; shift ;;
                --network=none) offline=true; shift ;;
                --build-arg) runtime=${2#RUNTIME_IMAGE=}; shift 2 ;;
                -t) target=$2; shift 2 ;;
                -f) dockerfile=$2; shift 2 ;;
                *) context=$1; shift ;;
            esac
        done
        [[ "$offline" == true && "$no_pull" == true && -f "$dockerfile" ]] || exit 91
        resolve_image "$runtime" > "$MOCK_DIR/build-base-image"
        [[ "$(cat "$MOCK_DIR/build-base-image")" == "$MOCK_OLD_IMAGE" ]] || exit 92
        printf '%s\n' "$MOCK_NEW_IMAGE" > "$(image_path "$MOCK_NEW_IMAGE")"
        printf '%s\n' "$MOCK_NEW_IMAGE" > "$(image_path "$target")"
        ;;
    compose)
        shift
        if [[ "$1" == version ]]; then printf 'Docker Compose version v2.mock\n'; exit; fi
        override=''
        while (($#)); do
            case "$1" in
                --project-directory) [[ "$2" == "$MOCK_PROJECT" ]] || exit 93; shift 2 ;;
                -f) override=$2; shift 2 ;;
                *) break ;;
            esac
        done
        action=$1
        shift
        case "$action" in
            ps)
                [[ "$1" == -aq ]] || exit 94
                case "$2" in
                    music-party) printf 'music-party-container\n' ;;
                    netease-api) printf 'unchanged-netease-container\n' ;;
                    *) exit 94 ;;
                esac
                ;;
            stop)
                [[ "$*" == '-t 30 music-party' ]] || exit 95
                printf 'true\n' > "$MOCK_DIR/stopped"
                if [[ "$(cat "$MOCK_DIR/current-image")" == "$MOCK_OLD_IMAGE" ]]; then
                    printf 'quiesced-old-database\n' > "$MOCK_PROJECT/music_party/data/app.db"
                    printf 'quiesced-old-wal\n' > "$MOCK_PROJECT/music_party/data/app.db-wal"
                fi
                ;;
            up)
                [[ "$*" == '-d --no-deps --no-build --pull never music-party' ]] || exit 96
                [[ -f "$override" && "$(cat "$MOCK_DIR/stopped")" == true ]] || exit 96
                target=''
                while IFS= read -r line; do
                    if [[ "$line" == '    image: '* ]]; then target=${line#'    image: '}; fi
                done < "$override"
                installed=$(resolve_image "$target")
                printf '%s\n' "$installed" > "$MOCK_DIR/current-image"
                printf 'false\n' > "$MOCK_DIR/stopped"
                if [[ "$installed" == "$MOCK_NEW_IMAGE" ]]; then
                    printf 'migrated-database\n' > "$MOCK_PROJECT/music_party/data/app.db"
                    printf 'migrated-wal\n' > "$MOCK_PROJECT/music_party/data/app.db-wal"
                    printf 'updated-config\n' > "$MOCK_PROJECT/config/settings.json"
                    printf 'new-cached-media\n' > "$MOCK_PROJECT/music_party/cached_media/song.mp3"
                fi
                ;;
            start)
                [[ "$*" == music-party ]] || exit 97
                printf 'false\n' > "$MOCK_DIR/stopped"
                ;;
            logs) [[ "$*" == '--tail 60 music-party' ]] || exit 98 ;;
            *) exit 99 ;;
        esac
        ;;
    *) printf 'Unexpected Docker operation\n' >&2; exit 100 ;;
esac
MOCK_DOCKER

cat > "$mock_bin/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$MOCK_DIR/curl.log"
[[ "$*" == '--fail --silent --max-time 2 http://127.0.0.1:8848/api/config' ]] || exit 99
[[ "${MOCK_READINESS:-ready}" == ready ]]
MOCK_CURL

cat > "$mock_bin/mv" <<'MOCK_MV'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${MOCK_FAIL_MOVE_ONCE:-false}" == true && "$2" == "$MOCK_PROJECT/music_party/data" && "$3" == */data-before-restore && ! -e "$MOCK_DIR/move-failed" ]]; then
    touch -- "$MOCK_DIR/move-failed"
    exit 1
fi
exec "$MOCK_REAL_MV" "$@"
MOCK_MV

cat > "$mock_bin/fast-clock.bash" <<'MOCK_CLOCK'
# Advance the updater's own Bash clock so readiness timeout tests never sleep.
sleep() { SECONDS=$((SECONDS + 125)); }
MOCK_CLOCK
chmod +x "$mock_bin/docker" "$mock_bin/curl" "$mock_bin/mv"

fixture() {
    fixture_dir="$test_root/$1"
    project="$fixture_dir/deployed project"
    bundle="$fixture_dir/update bundle"
    state="$fixture_dir/state"
    mkdir -p -- "$project/config" "$project/music_party/data" "$project/music_party/cached_media" "$bundle" "$state/images"
    printf 'services: {}\n' > "$project/docker-compose.yml"
    printf 'original-config\n' > "$project/config/settings.json"
    printf 'live-old-database\n' > "$project/music_party/data/app.db"
    printf 'live-old-wal\n' > "$project/music_party/data/app.db-wal"
    printf 'original-cached-media\n' > "$project/music_party/cached_media/song.mp3"
    printf '%s\n' "$old_image" > "$state/current-image"
    printf '%s\n' "$old_image" > "$state/images/${old_image//[^a-zA-Z0-9._-]/_}"
    printf '%s\n' "$old_image" > "$state/images/music-party-custom_local"
    printf 'false\n' > "$state/stopped"
    : > "$state/docker.log"
    cp -- "$repo_dir/scripts/update-docker.sh" "$bundle/update-docker.sh"
    printf 'fake jar for checksum tests\n' > "$bundle/app.jar"
    printf 'ARG RUNTIME_IMAGE\nFROM ${RUNTIME_IMAGE}\nCOPY app.jar /app/app.jar\n' > "$bundle/Dockerfile.incremental"
    printf '64f8fb6%s\n' "$(printf '0%.0s' {1..33})" > "$bundle/VERSION"
    (cd -- "$bundle" && sha256sum app.jar Dockerfile.incremental VERSION update-docker.sh > SHA256SUMS)
    readiness=ready
    fail_move=false
    wrong_mount=''
}

run_updater() {
    if PATH="$mock_bin:$PATH" MOCK_DIR="$state" MOCK_PROJECT="$project" MOCK_READINESS="$readiness" \
        MOCK_FAIL_MOVE_ONCE="$fail_move" MOCK_WRONG_MOUNT="$wrong_mount" BASH_ENV="$mock_bin/fast-clock.bash" \
        bash "$bundle/update-docker.sh" --project-dir "$project" "$@" > "$fixture_dir/output" 2>&1; then
        status=0
    else
        status=$?
    fi
}

fail_test() {
    printf 'FAIL: %s\n' "$*" >&2
    cat -- "$fixture_dir/output" >&2
    exit 1
}
assert_equal() { [[ "$1" == "$2" ]] || fail_test "$3: expected [$2], got [$1]"; }
assert_contains() { grep -F -- "$2" "$1" >/dev/null || fail_test "Missing [$2] in $1"; }
assert_absent() { if grep -F -- "$2" "$1" >/dev/null; then fail_test "Unexpected [$2] in $1"; fi; }
backup_with_state() {
    for archive in "$project"/music_party/update-backups/*/state.tar.gz; do
        if [[ -f "$archive" ]]; then printf '%s\n' "${archive%/state.tar.gz}"; return; fi
    done
    return 1
}
assert_offline() {
    assert_absent "$state/docker.log" ' pull '
    assert_absent "$state/docker.log" ' down '
    assert_absent "$state/docker.log" ' restart '
    assert_absent "$state/docker.log" 'stop -t 30 netease-api'
    assert_absent "$state/docker.log" 'up -d netease-api'
}

fixture success
run_updater
assert_equal "$status" 0 'Successful update exit status'
assert_equal "$(cat "$state/build-base-image")" "$old_image" 'Exact running image reused as build base'
assert_equal "$(cat "$state/current-image")" "$new_image" 'New image deployed'
assert_equal "$(cat "$state/images/music-party-custom_local")" "$new_image" 'Compose local tag advanced'
assert_contains "$state/docker.log" 'build --pull=false --network=none --build-arg RUNTIME_IMAGE=music-party-runtime:'
assert_contains "$state/docker.log" 'up -d --no-deps --no-build --pull never music-party'
assert_offline
first_backup=$(backup_with_state)
assert_equal "$(cat "$first_backup/image-id")" "$old_image" 'Rollback image retained'
mkdir -- "$fixture_dir/inspect-backup"
tar -xzf "$first_backup/state.tar.gz" -C "$fixture_dir/inspect-backup"
assert_equal "$(cat "$fixture_dir/inspect-backup/config/settings.json")" original-config 'Configuration backed up'
assert_equal "$(cat "$fixture_dir/inspect-backup/music_party/data/app.db")" quiesced-old-database 'Database backed up after stop'
assert_equal "$(cat "$fixture_dir/inspect-backup/music_party/data/app.db-wal")" quiesced-old-wal 'WAL backed up after stop'
[[ ! -e "$fixture_dir/inspect-backup/music_party/cached_media" ]] || fail_test 'Audio cache included in backup'
printf 'PASS: offline update preserves exact image base and backs up stopped state\n'

# Execute the actual printed command, including paths with spaces, to verify copy/paste use.
rollback_command=$(sed -n 's/^Rollback: //p' "$fixture_dir/output" | tail -n 1)
[[ -n "$rollback_command" ]] || fail_test 'No rollback command printed'
if PATH="$mock_bin:$PATH" MOCK_DIR="$state" MOCK_PROJECT="$project" BASH_ENV="$mock_bin/fast-clock.bash" \
    bash -c "$rollback_command" > "$fixture_dir/output" 2>&1; then status=0; else status=$?; fi
assert_equal "$status" 0 'Printed rollback command exit status'
assert_equal "$(cat "$state/current-image")" "$old_image" 'Rollback uses original image ID'
assert_equal "$(cat "$project/config/settings.json")" original-config 'Rollback restores configuration'
assert_equal "$(cat "$project/music_party/data/app.db")" quiesced-old-database 'Rollback restores old database'
assert_equal "$(cat "$project/music_party/cached_media/song.mp3")" new-cached-media 'Rollback preserves current media cache'
assert_equal "$(grep -c '^build ' "$state/docker.log")" 1 'Rollback does not build another image'
assert_offline
printf 'PASS: printed rollback command restores image/config/database and retains media\n'

fixture checksum
printf 'corrupt transfer\n' >> "$bundle/app.jar"
run_updater
[[ "$status" != 0 ]] || fail_test 'Corrupt checksum accepted'
assert_contains "$fixture_dir/output" 'FAILED'
assert_absent "$state/docker.log" 'build '
assert_absent "$state/docker.log" 'stop -t '
assert_absent "$state/docker.log" 'up -d '
assert_equal "$(cat "$state/current-image")" "$old_image" 'Checksum refusal retains deployed image'
assert_equal "$(cat "$project/music_party/data/app.db")" live-old-database 'Checksum refusal retains live database'
assert_equal "$(cat "$project/config/settings.json")" original-config 'Checksum refusal retains configuration'
printf 'PASS: checksum refusal leaves the deployed application running and untouched\n'

fixture readiness
readiness=failed
run_updater
[[ "$status" != 0 ]] || fail_test 'Readiness failure accepted'
assert_contains "$fixture_dir/output" 'Application readiness check failed'
assert_contains "$fixture_dir/output" '--rollback '
failed_backup=$(backup_with_state)
assert_equal "$(cat "$state/images/music-party-custom_local")" "$old_image" 'Failure preserves known-good Compose local tag'
readiness=ready
run_updater --rollback "$failed_backup"
assert_equal "$status" 0 'Rollback after readiness failure succeeds'
assert_equal "$(cat "$state/current-image")" "$old_image" 'Failed update can restore original image'
assert_equal "$(cat "$project/music_party/data/app.db")" quiesced-old-database 'Failed update can restore original state'
assert_offline
printf 'PASS: readiness timeout reports rollback and the backup remains usable\n'

fixture restore-failure
run_updater
assert_equal "$status" 0 'Recovery fixture update succeeds'
restore_backup=$(backup_with_state)
: > "$state/docker.log"
fail_move=true
run_updater --rollback "$restore_backup"
[[ "$status" != 0 ]] || fail_test 'Injected restore failure accepted'
assert_contains "$fixture_dir/output" 'Original configuration and data were restored'
assert_contains "$state/docker.log" 'start music-party'
assert_absent "$state/docker.log" 'up -d '
assert_equal "$(cat "$state/current-image")" "$new_image" 'Interrupted rollback retains current image'
assert_equal "$(cat "$state/stopped")" false 'Interrupted rollback restarts app'
assert_equal "$(cat "$project/config/settings.json")" updated-config 'Interrupted rollback restores current config'
assert_equal "$(cat "$project/music_party/data/app.db")" migrated-database 'Interrupted rollback retains current data'
assert_equal "$(cat "$project/music_party/cached_media/song.mp3")" new-cached-media 'Interrupted rollback retains media'
printf 'PASS: partial directory restore failure recovers current state and restarts app\n'

fixture invalid-backup
run_updater
assert_equal "$status" 0 'Invalid archive fixture update succeeds'
invalid_backup=$(backup_with_state)
printf 'not a tar archive\n' > "$invalid_backup/state.tar.gz"
: > "$state/docker.log"
run_updater --rollback "$invalid_backup"
[[ "$status" != 0 ]] || fail_test 'Invalid rollback archive accepted'
assert_absent "$state/docker.log" 'stop -t '
assert_absent "$state/docker.log" 'up -d '
assert_equal "$(cat "$project/config/settings.json")" updated-config 'Invalid backup retains current config'
assert_equal "$(cat "$project/music_party/data/app.db")" migrated-database 'Invalid backup retains current data'
printf 'PASS: invalid rollback archive is refused before stopping app\n'

fixture wrong-mount
wrong_mount='/unexpected/deployment'
run_updater
[[ "$status" != 0 ]] || fail_test 'Unexpected mount accepted'
assert_contains "$fixture_dir/output" 'Expected bind mount'
assert_absent "$state/docker.log" 'build '
assert_absent "$state/docker.log" 'stop -t '
assert_equal "$(cat "$state/current-image")" "$old_image" 'Mount refusal retains image'
printf 'PASS: unsupported mount layout is refused before update\n'
printf 'All 7 updater workflow checks passed.\n'
