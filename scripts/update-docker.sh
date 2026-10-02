#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
    echo 'Usage: bash update-docker.sh --project-dir /path/to/MusicParty [--rollback /path/to/backup]'
}

fail() { echo "ERROR: $*" >&2; return 1; }
project_dir=''
rollback_dir=''
while (($#)); do
    case "$1" in
        --project-dir) (($# >= 2)) || fail 'Missing project directory'; project_dir=$2; shift 2 ;;
        --rollback) (($# >= 2)) || fail 'Missing backup directory'; rollback_dir=$2; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) usage; fail "Unknown argument: $1" ;;
    esac
done
[[ -n "$project_dir" ]] || { usage; exit 1; }
project_dir=$(cd -- "$project_dir" && pwd -P)
bundle_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ -f "$project_dir/docker-compose.yml" ]] || fail 'docker-compose.yml not found'
for command in docker tar curl; do command -v "$command" >/dev/null || fail "$command is required"; done
docker compose version >/dev/null
compose=(docker compose --project-directory "$project_dir" -f "$project_dir/docker-compose.yml")

# This updater supports the bind mounts in the repository's Compose file.
app_id=$("${compose[@]}" ps -aq music-party)
[[ -n "$app_id" && "$app_id" != *$'\n'* ]] || fail 'Expected one existing music-party container'
for mapping in 'config:/app/config' 'music_party/data:/app/data' 'music_party/cached_media:/app/cached_media'; do
    relative=${mapping%%:*}
    destination=${mapping#*:}
    actual=$(docker inspect --format "{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Type}}:{{.Source}}{{end}}{{end}}" "$app_id")
    [[ "$actual" == "bind:$project_dir/$relative" ]] || fail "Expected bind mount $project_dir/$relative at $destination; found $actual"
done
api_id=$("${compose[@]}" ps -aq netease-api)
[[ -n "$api_id" ]] || fail 'Existing netease-api container not found'
old_image=$(docker inspect --format '{{.Image}}' "$app_id")
docker image inspect "$old_image" >/dev/null
backup_root="$project_dir/music_party/update-backups"
mkdir -p -- "$backup_root"
stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
backup_dir="$backup_root/$stamp"
mkdir -- "$backup_dir"
echo "$old_image" > "$backup_dir/image-id"
docker tag "$old_image" "music-party-rollback:$stamp"
phase='running'

on_error() {
    result=${1:-$?}
    trap - ERR INT TERM
    echo "Update failed (phase: $phase). Backup: $backup_dir" >&2
    if [[ "$phase" == 'restoring' ]]; then
        recovered=true
        for relative in config music_party/data; do
            name=${relative##*/}
            saved="$backup_dir/$name-before-restore"
            current="$project_dir/$relative"
            if [[ -d "$saved" ]]; then
                if [[ -e "$current" ]]; then
                    mv -- "$current" "$backup_dir/$name-failed-restore" || recovered=false
                fi
                if [[ ! -e "$current" ]]; then
                    mv -- "$saved" "$current" || recovered=false
                else
                    recovered=false
                fi
            fi
        done
        if [[ "$recovered" == true ]]; then
            "${compose[@]}" start music-party || true
            echo 'Original configuration and data were restored after interrupted rollback.' >&2
        else
            echo "Restore the missing directories from $backup_dir/state.tar.gz before restarting." >&2
        fi
    elif [[ "$phase" == 'stopped' ]]; then
        "${compose[@]}" start music-party || true
    elif [[ "$phase" == 'replaced' ]]; then
        printf 'Rollback: bash %q --project-dir %q --rollback %q\n' "$bundle_dir/update-docker.sh" "$project_dir" "$backup_dir" >&2
    fi
    exit "$result"
}
trap on_error ERR
trap 'on_error 130' INT
trap 'on_error 143' TERM

if [[ -n "$rollback_dir" ]]; then
    rollback_dir=$(cd -- "$rollback_dir" && pwd -P)
    [[ "$rollback_dir" == "$backup_root/"* && "$rollback_dir" != "$backup_dir" ]] || fail 'Rollback backup must be under this project update-backups directory'
    [[ -f "$rollback_dir/image-id" && -f "$rollback_dir/state.tar.gz" ]] || fail 'Incomplete rollback backup'
    rollback_image=$(cat -- "$rollback_dir/image-id")
    [[ "$rollback_image" =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'Invalid rollback image ID'
    docker image inspect "$rollback_image" >/dev/null
    target_image="music-party-rollback:$stamp-restore"
    docker tag "$rollback_image" "$target_image"
    mkdir -- "$backup_dir/restore"
    tar -xzf "$rollback_dir/state.tar.gz" -C "$backup_dir/restore"
    [[ -d "$backup_dir/restore/config" && -d "$backup_dir/restore/music_party/data" ]] || fail 'Rollback archive lacks configuration or data'
else
    for file in app.jar Dockerfile.incremental VERSION SHA256SUMS; do
        [[ -f "$bundle_dir/$file" ]] || fail "Bundle missing $file"
    done
    command -v sha256sum >/dev/null || fail 'sha256sum is required'
    (cd -- "$bundle_dir" && sha256sum --check SHA256SUMS)
    version=$(tr -d '\r\n' < "$bundle_dir/VERSION")
    [[ "$version" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid bundle commit'
    runtime_image="music-party-runtime:$stamp"
    target_image="music-party-incremental:${version:0:7}-$stamp"
    docker tag "$old_image" "$runtime_image"
    echo "Building application layer for ${version:0:7}, reusing $old_image"
    docker build --pull=false --network=none --build-arg "RUNTIME_IMAGE=$runtime_image" \
        -f "$bundle_dir/Dockerfile.incremental" -t "$target_image" "$bundle_dir"
fi

printf 'services:\n  music-party:\n    image: %s\n' "$target_image" > "$backup_dir/compose-image.yml"
echo 'Stopping only music-party and backing up configuration and SQLite data'
phase='stopped'
"${compose[@]}" stop -t 30 music-party
tar -czf "$backup_dir/state.tar.gz" -C "$project_dir" config music_party/data

if [[ -n "$rollback_dir" ]]; then
    # Replace only the two validated mount directories; current data is backed up above.
    phase='restoring'
    mv -- "$project_dir/config" "$backup_dir/config-before-restore"
    mv -- "$project_dir/music_party/data" "$backup_dir/data-before-restore"
    mv -- "$backup_dir/restore/config" "$project_dir/config"
    mv -- "$backup_dir/restore/music_party/data" "$project_dir/music_party/data"
fi

phase='replaced'
"${compose[@]}" -f "$backup_dir/compose-image.yml" up -d --no-deps --no-build --pull never music-party
new_app_id=$("${compose[@]}" ps -aq music-party)
installed_image=$(docker inspect --format '{{.Image}}' "$new_app_id")
expected_image=$(docker image inspect --format '{{.Id}}' "$target_image")
[[ "$installed_image" == "$expected_image" ]] || fail 'Container is not using the intended image'
[[ "$("${compose[@]}" ps -aq netease-api)" == "$api_id" ]] || fail 'netease-api container changed unexpectedly'

echo 'Waiting up to 120 seconds for the application'
ready=false
deadline=$((SECONDS + 120))
while ((SECONDS < deadline)); do
    if curl --fail --silent --max-time 2 http://127.0.0.1:8848/api/config >/dev/null; then
        ready=true
        break
    fi
    sleep 3
done
if [[ "$ready" != true ]]; then
    "${compose[@]}" logs --tail 60 music-party
    fail 'Application readiness check failed; use the printed rollback command'
fi

# Keep the standard local tag aligned for subsequent normal Compose invocations.
docker tag "$target_image" music-party-custom:local
trap - ERR INT TERM
echo "Update complete. Backup: $backup_dir"
echo 'NCM API was not pulled, restarted, or recreated. Existing FFmpeg and runtime were reused.'
printf 'Rollback: bash %q --project-dir %q --rollback %q\n' "$bundle_dir/update-docker.sh" "$project_dir" "$backup_dir"
