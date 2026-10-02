#!/usr/bin/env bash
set -Eeuo pipefail

# Contract tests for a first-deployment bundle. No Docker daemon or network is used.
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
mkdir -p -- "$repo_dir/target"
test_root=$(mktemp -d "$repo_dir/target/deploy-docker-tests.XXXXXX")
cleanup() {
    [[ "$test_root" == "$repo_dir/target/deploy-docker-tests."* ]] || return 1
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*)
            # Keep Windows deletion in one native PowerShell operation, using
            # literal paths and checking the resolved target stays in target/.
            MP_DEPLOY_TEST_ROOT_WIN=$(cygpath -w "$test_root") \
            MP_DEPLOY_TEST_PARENT_WIN=$(cygpath -w "$repo_dir/target") \
            powershell.exe -NoProfile -NonInteractive -Command '
                $ErrorActionPreference = "Stop"
                $testPath = [IO.Path]::GetFullPath($env:MP_DEPLOY_TEST_ROOT_WIN)
                $parentPath = [IO.Path]::GetFullPath($env:MP_DEPLOY_TEST_PARENT_WIN).TrimEnd("\") + "\"
                if (-not $testPath.StartsWith($parentPath, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "Refusing test cleanup outside the project target directory"
                }
                if (Test-Path -LiteralPath $testPath) { Remove-Item -LiteralPath $testPath -Recurse -Force }
            '
            ;;
        *) rm -rf -- "$test_root" ;;
    esac
}
trap cleanup EXIT

mock_bin="$test_root/bin"
mkdir -- "$mock_bin"
cat > "$mock_bin/docker" <<'MOCK_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s ' "$@" >> "$MOCK_DIR/docker.log"
printf '\n' >> "$MOCK_DIR/docker.log"
case "$1" in
    version)
        [[ "$*" == 'version --format {{.Server.Version}}' ]] || exit 90
        printf '%s\n' "${MOCK_ENGINE_VERSION:-24.0.9}"
        ;;
    compose)
        shift
        if [[ "$1" == version ]]; then
            [[ "$*" == 'version --short' ]] || exit 91
            printf '%s\n' "${MOCK_COMPOSE_VERSION:-v2.20.0}"
            exit
        fi
        [[ "$1" == --project-directory && "$2" == "$MOCK_PROJECT" ]] || exit 92
        shift 2
        [[ "$1" == -f && "$2" == "$MOCK_PROJECT/docker-compose.yml" ]] || exit 93
        shift 2
        case "$1" in
            ps)
                [[ "$*" == 'ps -aq' ]] || exit 94
                [[ "${MOCK_EXISTING_PROJECT:-false}" == false ]] || printf 'existing-container\n'
                ;;
            up)
                [[ "$*" == 'up -d --no-build --pull never' ]] || exit 95
                [[ -f "$MOCK_DIR/tagged" && -d "$MOCK_PROJECT/music_party/data" && -d "$MOCK_PROJECT/music_party/cached_media" ]] || exit 95
                printf 'started\n' > "$MOCK_DIR/started"
                ;;
            logs)
                [[ "$*" == 'logs --tail 60 music-party' ]] || exit 96
                printf 'fixture application logs\n'
                ;;
            *) printf 'Unexpected Compose operation: %s\n' "$1" >&2; exit 97 ;;
        esac
        ;;
    load)
        [[ "$*" == 'load -i images.tar' && -f images.tar ]] || exit 98
        printf 'loaded\n' > "$MOCK_DIR/loaded"
        ;;
    image)
        [[ "$1" == image && "$2" == inspect && "$3" == --format && "$4" == '{{.Os}}/{{.Architecture}}' && "$5" == "$(tr -d '\r\n' < "$MOCK_PROJECT/APP_IMAGE")" ]] || exit 99
        [[ -f "$MOCK_DIR/loaded" ]] || exit 99
        printf '%s\n' "${MOCK_LOADED_PLATFORM:-linux/amd64}"
        ;;
    info)
        [[ "$*" == 'info --format {{.Architecture}}' ]] || exit 100
        printf '%s\n' "${MOCK_HOST_ARCH:-x86_64}"
        ;;
    tag)
        [[ "$2" == "$(tr -d '\r\n' < "$MOCK_PROJECT/APP_IMAGE")" && "$3" == music-party-custom:local ]] || exit 101
        printf 'tagged\n' > "$MOCK_DIR/tagged"
        ;;
    *) printf 'Unexpected Docker operation: %s\n' "$1" >&2; exit 102 ;;
esac
MOCK_DOCKER

cat > "$mock_bin/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$MOCK_DIR/curl.log"
[[ "$*" == '--fail --silent --max-time 2 http://127.0.0.1:8848/api/config' ]] || exit 90
[[ "${MOCK_READINESS:-ready}" == ready ]]
MOCK_CURL

cat > "$mock_bin/fast-clock.bash" <<'MOCK_CLOCK'
# Override only the deployed script's sleep, advancing its Bash clock instantly.
sleep() { SECONDS=$((SECONDS + 125)); }
MOCK_CLOCK
chmod +x "$mock_bin/docker" "$mock_bin/curl"

fixture() {
    fixture_dir="$test_root/$1"
    bundle="$fixture_dir/first deployment bundle"
    state="$fixture_dir/state"
    mkdir -p -- "$bundle/config" "$state"
    cp -- "$repo_dir/scripts/deploy-docker.sh" "$bundle/deploy-docker.sh"
    printf 'fixture exported app and API images\n' > "$bundle/images.tar"
    printf 'music-party-custom:1.3.9-0123456789abcdef-amd64\n' > "$bundle/APP_IMAGE"
    printf 'linux/amd64\n' > "$bundle/PLATFORM"
    printf 'services: {}\n' > "$bundle/docker-compose.yml"
    printf 'app.rooms.root-key=REPLACE_ME\napp.base-url=http://localhost:8848\n' > "$bundle/config/application.properties.example"
    if [[ "${2:-with_config}" != no_config ]]; then
        printf 'app.rooms.root-key=RootKey2026!\napp.base-url=http://localhost:8848\n' > "$bundle/config/application.properties"
    fi
    (cd -- "$bundle" && sha256sum images.tar APP_IMAGE PLATFORM docker-compose.yml config/application.properties.example deploy-docker.sh > SHA256SUMS)
    : > "$state/docker.log"
    readiness=ready
    existing_project=false
    loaded_platform=linux/amd64
    host_arch=x86_64
    engine_version=24.0.9
    compose_version=v2.20.0
}
run_deployer() {
    if PATH="$mock_bin:$PATH" MOCK_DIR="$state" MOCK_PROJECT="$bundle" MOCK_READINESS="$readiness" \
        MOCK_EXISTING_PROJECT="$existing_project" MOCK_LOADED_PLATFORM="$loaded_platform" MOCK_HOST_ARCH="$host_arch" \
        MOCK_ENGINE_VERSION="$engine_version" MOCK_COMPOSE_VERSION="$compose_version" BASH_ENV="$mock_bin/fast-clock.bash" \
        bash "$bundle/deploy-docker.sh" > "$fixture_dir/output" 2>&1; then
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
assert_failed() { [[ "$status" != 0 ]] || fail_test "$1"; }
assert_no_mutation() {
    assert_absent "$state/docker.log" 'load -i'
    assert_absent "$state/docker.log" 'tag '
    assert_absent "$state/docker.log" 'up -d'
    [[ ! -e "$bundle/music_party" ]] || fail_test 'Rejected deployment created application data directories'
}
assert_no_start() {
    assert_absent "$state/docker.log" 'tag '
    assert_absent "$state/docker.log" 'up -d'
    [[ ! -e "$bundle/music_party" ]] || fail_test 'Platform mismatch created application data directories'
}

fixture success
run_deployer
assert_equal "$status" 0 'Successful first deployment exit status'
assert_contains "$state/docker.log" 'load -i images.tar'
assert_contains "$state/docker.log" 'tag music-party-custom:1.3.9-0123456789abcdef-amd64 music-party-custom:local'
assert_contains "$state/docker.log" 'up -d --no-build --pull never'
if grep -Eq '^(pull|build)([[:space:]]|$)' "$state/docker.log"; then fail_test 'Deployment invoked a network pull or Docker build'; fi
assert_absent "$state/docker.log" 'down '
assert_contains "$state/curl.log" '--fail --silent --max-time 2 http://127.0.0.1:8848/api/config'
assert_contains "$fixture_dir/output" 'Deployment complete.'
[[ -d "$bundle/music_party/data" && -d "$bundle/music_party/cached_media" ]] || fail_test 'Persistent data directories missing'
printf 'PASS: first deployment loads exported images and starts Compose without build or pull\n'

fixture checksum
printf 'corrupt transfer\n' >> "$bundle/images.tar"
run_deployer
assert_failed 'Corrupt archive checksum accepted'
assert_contains "$fixture_dir/output" 'FAILED'
assert_no_mutation
printf 'PASS: checksum corruption is rejected before Docker mutation\n'

fixture missing_config no_config
run_deployer
assert_failed 'Missing configuration accepted'
assert_contains "$fixture_dir/output" 'Copy config/application.properties.example'
assert_no_mutation
printf 'PASS: a missing deployment configuration is rejected\n'

invalid_roots=('' '1234567' '12345678901234567' 'abc defgh' $'abc\tdefgh' 'ROOTKEY中文')
for index in "${!invalid_roots[@]}"; do
    fixture "invalid_root_$index"
    printf 'app.rooms.root-key=%s\n' "${invalid_roots[$index]}" > "$bundle/config/application.properties"
    run_deployer
    assert_failed "Invalid root key accepted (case $index)"
    assert_contains "$fixture_dir/output" 'app.rooms.root-key must contain 8-16 printable ASCII characters without spaces.'
    assert_no_mutation
done
fixture missing_root
printf 'app.base-url=http://localhost:8848\n' > "$bundle/config/application.properties"
run_deployer
assert_failed 'Missing root key accepted'
assert_contains "$fixture_dir/output" 'app.rooms.root-key must contain'
assert_no_mutation
printf 'PASS: missing, short, long, spaced, tabbed and non-ASCII root keys are rejected\n'

fixture escaped_backslash_16
printf '%s\r\n' 'app.rooms.root-key=12345678901234\\\\' > "$bundle/config/application.properties"
run_deployer
assert_equal "$status" 0 'Escaped backslashes must be counted after Java properties decoding (16 actual characters)'
assert_contains "$fixture_dir/output" 'Deployment complete.'
fixture escaped_ascii_unicode
printf '%s\n' 'app.rooms.root-key=\u0041\u0042\u0043\u0044\u0031\u0032\u0033\u0034' > "$bundle/config/application.properties"
run_deployer
assert_equal "$status" 0 'ASCII Unicode escapes must be decoded before the 8-character minimum check'
fixture escaped_punctuation
printf '%s\n' 'app.rooms.root-key=Root\=\:\!\#Key' > "$bundle/config/application.properties"
run_deployer
assert_equal "$status" 0 'Java properties punctuation escapes must be accepted'
printf 'PASS: escaped backslashes, ASCII Unicode and punctuation use decoded key lengths\n'

invalid_decoded_roots=('RootKey1\t' 'RootKey1\n' 'RootKey1\r' 'RootKey1\f' 'RootKey1\u0020' 'RootKey1\u000a' 'RootKey1\u0000' 'RootKey1\u4e2d' 'Root\ Key1')
for index in "${!invalid_decoded_roots[@]}"; do
    fixture "invalid_decoded_root_$index"
    printf '%s\n' "app.rooms.root-key=${invalid_decoded_roots[$index]}" > "$bundle/config/application.properties"
    run_deployer
    assert_failed "Invalid decoded Java properties value accepted (case $index)"
    assert_contains "$fixture_dir/output" 'app.rooms.root-key must contain 8-16 printable ASCII characters without spaces.'
    assert_no_mutation
done
printf 'PASS: decoded whitespace, controls, NUL and non-ASCII root values are rejected\n'

invalid_unicode_roots=('RootKey1\u12' 'RootKey1\u00ZZ' 'RootKey1\uu0041')
for index in "${!invalid_unicode_roots[@]}"; do
    fixture "invalid_unicode_root_$index"
    printf '%s\n' "app.rooms.root-key=${invalid_unicode_roots[$index]}" > "$bundle/config/application.properties"
    run_deployer
    assert_failed "Malformed Unicode escape accepted (case $index)"
    assert_contains "$fixture_dir/output" 'invalid Java properties Unicode escape'
    assert_no_mutation
done
fixture continued_root
printf '%s\n' 'app.rooms.root-key=RootKey1\' 'continued' > "$bundle/config/application.properties"
run_deployer
assert_failed 'Unsupported continued root property was accepted'
assert_contains "$fixture_dir/output" 'single-line Java properties value (no line continuations)'
assert_no_mutation
printf 'PASS: malformed Unicode escapes and root property line continuations are rejected\n'

fixture existing_project
existing_project=true
run_deployer
assert_failed 'Existing Compose project overwritten'
assert_contains "$fixture_dir/output" 'This Compose project already has containers.'
assert_no_mutation
printf 'PASS: an existing deployment is rejected before image load or data mutation\n'

fixture loaded_platform_mismatch
loaded_platform=linux/arm64
run_deployer
assert_failed 'Wrong image platform accepted'
assert_contains "$fixture_dir/output" 'Loaded image is linux/arm64, expected linux/amd64'
assert_no_start
fixture host_platform_mismatch
host_arch=aarch64
run_deployer
assert_failed 'Wrong Docker server platform accepted'
assert_contains "$fixture_dir/output" 'Package linux/amd64 does not match this server linux/arm64'
assert_no_start
printf 'PASS: image and host architecture mismatches are rejected before start\n'

fixture timeout
readiness=fail
run_deployer
assert_failed 'Readiness timeout reported success'
assert_contains "$state/docker.log" 'up -d --no-build --pull never'
assert_contains "$state/docker.log" 'logs --tail 60 music-party'
assert_contains "$fixture_dir/output" 'fixture application logs'
assert_contains "$fixture_dir/output" 'Application readiness check failed.'
assert_absent "$fixture_dir/output" 'Deployment complete.'
printf 'PASS: readiness timeout returns failure with application logs\n'

fixture old_engine
engine_version=23.0.6
run_deployer
assert_failed 'Unsupported Docker Engine accepted'
assert_contains "$fixture_dir/output" 'Docker Engine 24+ is required'
assert_no_mutation
fixture old_compose
compose_version=v2.19.1
run_deployer
assert_failed 'Unsupported Docker Compose accepted'
assert_contains "$fixture_dir/output" 'Docker Compose 2.20+ is required'
assert_no_mutation
printf 'PASS: unsupported Docker Engine and Compose versions are rejected\n'

printf 'All first-deployment Docker script tests passed.\n'
