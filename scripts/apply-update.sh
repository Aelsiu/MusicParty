#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Deployment-only guard; packaging binds this wrapper to its application commit.
# The adjacent update-docker.sh owns backup, replacement and rollback behavior.
target_commit='__APPLICATION_COMMIT__'
usage() {
    echo 'Usage: bash apply-update.sh --project-dir /path/to/MusicParty [--rollback /path/to/backup] [--check-only]'
}
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Resolve only the application's published 8080/tcp endpoint, bypassing proxies.
# A stopped container may retain fixed bindings only in HostConfig.
application_health_url() {
    local bindings host port extra endpoint rank best_rank=0 url=''
    local first second third fourth
    bindings=$(docker inspect --format '{{range (index .NetworkSettings.Ports "8080/tcp")}}{{printf "%s|%s\n" .HostIp .HostPort}}{{end}}' "$1") || return 1
    if [[ -z "$bindings" && "${2:-true}" == true ]]; then
        bindings=$(docker inspect --format '{{range (index .HostConfig.PortBindings "8080/tcp")}}{{printf "%s|%s\n" .HostIp .HostPort}}{{end}}' "$1") || return 1
    fi
    [[ -n "$bindings" ]] || return 1
    while IFS='|' read -r host port extra; do
        [[ -z "$extra" ]] || return 1
        # Routed IPv6 can expose a container port without a host port.
        [[ -n "$port" ]] || continue
        [[ "$port" =~ ^[0-9]{1,5}$ ]] || return 1
        port=$((10#$port))
        ((port >= 1 && port <= 65535)) || return 1
        case "$host" in
            ''|0.0.0.0) endpoint=127.0.0.1; rank=3 ;;
            ::|::1) endpoint='[::1]'; rank=2 ;;
            *)
                [[ "$host" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
                IFS='.' read -r first second third fourth <<< "$host"
                ((10#$first <= 255 && 10#$second <= 255 && 10#$third <= 255 && 10#$fourth <= 255)) || return 1
                endpoint="$((10#$first)).$((10#$second)).$((10#$third)).$((10#$fourth))"
                if ((10#$first == 127)); then rank=3; else rank=1; fi
                ;;
        esac
        if ((rank > best_rank)); then
            best_rank=$rank
            url="http://$endpoint:$port/api/config"
        fi
    done <<< "$bindings"
    [[ -n "$url" ]] || return 1
    printf '%s\n' "$url"
}

project_dir=''
rollback_dir=''
check_only=false
while (($#)); do
    case "$1" in
        --project-dir) (($# >= 2)) || fail 'Missing project directory'; project_dir=$2; shift 2 ;;
        --rollback) (($# >= 2)) || fail 'Missing rollback directory'; [[ -n "$2" ]] || fail 'Rollback directory must not be empty'; rollback_dir=$2; shift 2 ;;
        --check-only) check_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) usage; fail "Unknown argument: $1" ;;
    esac
done
[[ -n "$project_dir" ]] || { usage; fail 'Project directory is required'; }
[[ "$target_commit" =~ ^[a-f0-9]{40}$ ]] || fail 'Wrapper application commit was not initialized by packaging'
for command in docker bash tar sha256sum curl flock; do
    command -v "$command" >/dev/null || fail "Required tool is unavailable: $command"
done
project_dir=$(cd -- "$project_dir" && pwd -P) || fail 'Project directory is inaccessible'
bundle_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ -f "$project_dir/docker-compose.yml" ]] || fail 'docker-compose.yml not found in project directory'
[[ -f "$bundle_dir/update-docker.sh" ]] || fail 'Adjacent update-docker.sh is missing'
[[ ! -L "$project_dir/.musicparty-update.lock" ]] || fail 'Update lock must not be a symbolic link'
exec 9>"$project_dir/.musicparty-update.lock" || fail 'Cannot open deployment update lock'
flock -n 9 || fail 'Another update holds this deployment lock, or locking is unavailable'

verify_bundle() {
    [[ -f "$bundle_dir/SHA256SUMS" && ! -L "$bundle_dir/SHA256SUMS" ]] || fail 'SHA256SUMS is missing or is a symbolic link'
    declare -A listed=()
    local line name
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[0-9a-fA-F]{64}[[:space:]][[:space:]*](.+)$ ]] || fail 'Malformed SHA256SUMS entry'
        name=${BASH_REMATCH[1]}
        [[ "$name" != */* && "$name" != '.' && "$name" != '..' && "$name" != 'SHA256SUMS' ]] || fail 'Checksum entries must name files inside this flat bundle'
        [[ -z "${listed[$name]+present}" ]] || fail "Duplicate checksum entry: $name"
        listed["$name"]=true
    done < "$bundle_dir/SHA256SUMS"
    for name in app.jar Dockerfile.incremental VERSION APP_VERSION update-docker.sh apply-update.sh; do
        [[ -n "${listed[$name]+present}" ]] || fail "Required bundle file is not checksummed: $name"
    done
    shopt -s nullglob dotglob
    for name in "$bundle_dir"/*; do
        [[ -f "$name" && ! -L "$name" ]] || fail 'Update bundle must contain ordinary files only'
        name=${name##*/}
        [[ "$name" == 'SHA256SUMS' || -n "${listed[$name]+present}" ]] || fail "Unchecksummed bundle file: $name"
    done
    shopt -u nullglob dotglob
    (cd -- "$bundle_dir" && sha256sum --check --strict SHA256SUMS) || fail 'Bundle checksum verification failed'
    local version
    version=$(<"$bundle_dir/VERSION")
    [[ "$version" == "$target_commit" ]] || fail 'Bundle version does not match this wrapper application commit'
}
if [[ -z "$rollback_dir" ]]; then verify_bundle; fi

# Keep inspection to explicit public fields; do not print container configuration.
engine_os=$(docker info --format '{{.OSType}}') || fail 'Cannot contact Docker Engine'
[[ "$engine_os" == linux ]] || fail 'A Linux Docker Engine is required'
docker compose version >/dev/null || fail 'Docker Compose plugin is unavailable'
export BUILDX_BUILDER=default
builder_driver=$(
    LC_ALL=C docker buildx inspect default | {
        driver_count=0
        driver_value=''
        while IFS= read -r driver_line || [[ -n "$driver_line" ]]; do
            if [[ "$driver_line" == Driver:* ]]; then
                [[ "$driver_line" =~ ^Driver:[[:space:]]*([^[:space:]]+)[[:space:]]*$ ]] || exit 1
                driver_count=$((driver_count + 1))
                driver_value=${BASH_REMATCH[1]}
            fi
        done
        [[ "$driver_count" == 1 ]] || exit 1
        printf '%s\n' "$driver_value"
    }
) || fail 'Local default Buildx builder is unavailable or its driver could not be determined; install/configure it before updating'
[[ "$builder_driver" == docker ]] || fail 'Default Buildx builder must use the local docker driver'
compose=(docker compose --project-directory "$project_dir" -f "$project_dir/docker-compose.yml")
app_id=$("${compose[@]}" ps -aq music-party) || fail 'Cannot locate the music-party container'
api_id=$("${compose[@]}" ps -aq netease-api) || fail 'Cannot locate the netease-api container'
[[ -n "$app_id" && "$app_id" != *$'\n'* ]] || fail 'Expected exactly one existing music-party container'
[[ -n "$api_id" && "$api_id" != *$'\n'* ]] || fail 'Expected exactly one existing netease-api container'
if ! health_url=$(application_health_url "$app_id"); then
    [[ -n "$rollback_dir" ]] || fail 'Cannot resolve published application endpoint for 8080/tcp'
    echo 'Current application endpoint unavailable; will check the restored container after rollback.'
else
    printf 'Application health endpoint: %s\n' "$health_url"
fi
api_running=$(docker inspect --format '{{.State.Running}}' "$api_id") || fail 'Cannot inspect NCM API state'
[[ "$api_running" == true ]] || fail 'Existing NCM API container must already be running; this wrapper will not start or replace it'
for mapping in 'config:/app/config' 'music_party/data:/app/data' 'music_party/cached_media:/app/cached_media'; do
    relative=${mapping%%:*}
    destination=${mapping#*:}
    actual=$(docker inspect --format "{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Type}}:{{.Source}}{{end}}{{end}}" "$app_id") || fail "Cannot inspect mount $destination"
    [[ "$actual" == "bind:$project_dir/$relative" ]] || fail "Expected bind mount $project_dir/$relative at $destination"
done
old_image=$(docker inspect --format '{{.Image}}' "$app_id") || fail 'Cannot inspect actual application image'
[[ "$old_image" =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'Application container must reference an actual sha256 image ID'
found_image=$(docker image inspect --format '{{.Id}}' "$old_image") || fail 'Current application image is not available locally'
[[ "$found_image" == "$old_image" ]] || fail 'Local application image ID does not match the container image'
if [[ -n "$rollback_dir" ]]; then
    rollback_dir=$(cd -- "$rollback_dir" && pwd -P) || fail 'Rollback directory is inaccessible'
    [[ "$rollback_dir" == "$project_dir/music_party/update-backups/"* ]] || fail 'Rollback directory must be inside this deployment update-backups directory'
    [[ -f "$rollback_dir/image-id" && -f "$rollback_dir/state.tar.gz" ]] || fail 'Rollback backup is incomplete'
    rollback_image=$(<"$rollback_dir/image-id")
    [[ "$rollback_image" =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'Rollback backup has an invalid image ID'
    found_image=$(docker image inspect --format '{{.Id}}' "$rollback_image") || fail 'Rollback image is not available locally'
    [[ "$found_image" == "$rollback_image" ]] || fail 'Local rollback image ID does not match the backup'
fi

# A disposable, network-isolated version probe never runs app.jar or mounts data.
docker run --rm --pull=never --network none --entrypoint sh "$old_image" -c '
    set -eu
    java_version=$(java -version 2>&1) || { echo "Java version probe failed" >&2; exit 1; }
    case "$java_version" in
        *"version \"21."*|*"version \"21\""*|*"version \"21-"*|*"openjdk 21."*|*"openjdk 21 "*) ;;
        *) echo "Existing runtime must provide Java 21" >&2; exit 1 ;;
    esac
    ffmpeg -version >/dev/null 2>&1 || { echo "Existing runtime must provide FFmpeg" >&2; exit 1; }
' || fail 'Existing application image failed the Java 21 / FFmpeg version preflight'
echo 'Preflight passed: Linux Docker, local default builder, running NCM API, original mounts and Java 21 / FFmpeg.'
if [[ "$check_only" == true ]]; then
    echo 'Check only: application, NCM API, configuration and database were not switched or changed.'
    exit 0
fi
args=(--project-dir "$project_dir")
if [[ -n "$rollback_dir" ]]; then args+=(--rollback "$rollback_dir"); fi
updater_status=0
BUILDX_BUILDER=default bash "$bundle_dir/update-docker.sh" "${args[@]}" || updater_status=$?
echo 'For rollback, use this guarded wrapper with the backup directory printed by update-docker.sh above.' >&2
printf 'Rollback entry point: bash %q --project-dir %q --rollback %q\n' \
    "$bundle_dir/apply-update.sh" "$project_dir" '<backup directory printed above>' >&2
exit "$updater_status"
