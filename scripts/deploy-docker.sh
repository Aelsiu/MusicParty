#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
bundle_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
cd -- "$bundle_dir"
for command in docker sha256sum curl; do
    command -v "$command" >/dev/null || { echo "ERROR: $command is required" >&2; exit 1; }
done
engine_version=$(docker version --format '{{.Server.Version}}')
[[ "$engine_version" =~ ^([0-9]+)\. && "${BASH_REMATCH[1]}" -ge 24 ]] || { echo 'ERROR: Docker Engine 24+ is required' >&2; exit 1; }
compose_version=$(docker compose version --short)
compose_version=${compose_version#v}
[[ "$compose_version" =~ ^([0-9]+)\.([0-9]+) && ( "${BASH_REMATCH[1]}" -gt 2 || ( "${BASH_REMATCH[1]}" -eq 2 && "${BASH_REMATCH[2]}" -ge 20 ) ) ]] || { echo 'ERROR: Docker Compose 2.20+ is required' >&2; exit 1; }
sha256sum --check SHA256SUMS
[[ -f config/application.properties ]] || { echo 'ERROR: Copy config/application.properties.example to config/application.properties and set root-key/base-url before deployment.' >&2; exit 1; }
invalid_root_key() {
    echo 'ERROR: app.rooms.root-key must contain 8-16 printable ASCII characters without spaces.' >&2
    return 1
}
decode_root_key() {
    # This preflight supports app.rooms.root-key=VALUE on one physical line.
    # Java properties escapes count toward the decoded value, not the file text.
    # No JVM or other server-side parser is needed to validate ASCII-only keys.
    local LC_ALL=C raw=$1 decoded='' character escape hex code_point index
    for ((index = 0; index < ${#raw}; index++)); do
        character=${raw:index:1}
        if [[ "$character" != '\' ]]; then
            decoded+=$character
            continue
        fi
        index=$((index + 1))
        if ((index >= ${#raw})); then
            echo 'ERROR: Root-key preflight supports only a single-line Java properties value (no line continuations).' >&2
            return 1
        fi
        escape=${raw:index:1}
        case "$escape" in
            t|n|r|f)
                # Reject here: command substitution would otherwise trim a
                # decoded trailing newline before the final length check.
                invalid_root_key
                return 1
                ;;
            u)
                hex=${raw:index+1:4}
                if [[ ! "$hex" =~ ^[0-9a-fA-F]{4}$ ]]; then
                    echo 'ERROR: app.rooms.root-key contains an invalid Java properties Unicode escape.' >&2
                    return 1
                fi
                code_point=$((16#$hex))
                if ((code_point < 33 || code_point > 126)); then
                    invalid_root_key
                    return 1
                fi
                printf -v character '\\x%02x' "$code_point"
                printf -v character '%b' "$character"
                decoded+=$character
                index=$((index + 4))
                ;;
            *)
                # Java removes the slash for \\, \:, \=, \  and unknown
                # escapes such as \z; it does not re-decode the result.
                decoded+=$escape
                ;;
        esac
    done
    [[ "$decoded" =~ ^[\!-\~]{8,16}$ ]] || { invalid_root_key; return 1; }
    printf '%s' "$decoded"
}
root_key=$(sed -nE 's/^[[:space:]]*app\.rooms\.root-key[[:space:]]*=[[:space:]]*(.*)$/\1/p' config/application.properties | tail -n 1)
root_key=${root_key%$'\r'}
root_key=$(decode_root_key "$root_key") || exit 1
compose=(docker compose --project-directory "$bundle_dir" -f "$bundle_dir/docker-compose.yml")
# Full packages are for a first deployment; use build-update for an existing installation.
existing=$("${compose[@]}" ps -aq)
[[ -z "$existing" ]] || { echo 'ERROR: This Compose project already has containers. Use an incremental update for an existing deployment.' >&2; exit 1; }
docker load -i images.tar
app_image=$(tr -d '\r\n' < APP_IMAGE)
[[ "$app_image" =~ ^music-party-custom:([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?-)?[a-f0-9]{7,40}-(amd64|arm64)$ ]] || { echo 'ERROR: Invalid APP_IMAGE' >&2; exit 1; }
expected_platform=$(tr -d '\r\n' < PLATFORM)
actual_platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$app_image")
[[ "$actual_platform" == "$expected_platform" ]] || { echo "ERROR: Loaded image is $actual_platform, expected $expected_platform" >&2; exit 1; }
host_arch=$(docker info --format '{{.Architecture}}')
case "$host_arch" in x86_64|amd64) host_platform=linux/amd64 ;; aarch64|arm64) host_platform=linux/arm64 ;; *) echo "ERROR: Unsupported Docker server architecture: $host_arch" >&2; exit 1 ;; esac
[[ "$host_platform" == "$expected_platform" ]] || { echo "ERROR: Package $expected_platform does not match this server $host_platform" >&2; exit 1; }
docker tag "$app_image" music-party-custom:local
mkdir -p -- music_party/data music_party/cached_media
"${compose[@]}" up -d --no-build --pull never
echo 'Waiting up to 120 seconds for the application'
deadline=$((SECONDS + 120))
while ((SECONDS < deadline)); do
    if curl --fail --silent --max-time 2 http://127.0.0.1:8848/api/config >/dev/null; then
        echo 'Deployment complete. Open http://SERVER_IP:8848 in a browser.'
        exit 0
    fi
    sleep 3
done
"${compose[@]}" logs --tail 60 music-party
echo 'ERROR: Application readiness check failed. Check config/application.properties and the container logs.' >&2
exit 1
