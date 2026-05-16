#!/usr/bin/env bash
set -euo pipefail

INPUT_DIR="${PORTL_INPUT_DIR:-/work/input}"
OUTPUT_DIR="${PORTL_OUTPUT_DIR:-/work/output}"
METADATA_PATH="${PORTL_METADATA_PATH:-${INPUT_DIR}/portl-build.json}"
WEB_DIST_DIR="${PORTL_WEB_DIST_DIR:-${INPUT_DIR}/dist}"
SHELL_TEMPLATE_DIR="/opt/portl/portl-shell"

err() {
  echo "portl-builder: $*" >&2
  exit 1
}

require_file() {
  local file="$1"
  [[ -f "$file" ]] || err "Missing required file: $file"
}

require_dir() {
  local dir="$1"
  [[ -d "$dir" ]] || err "Missing required directory: $dir"
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || err "Required command not found in image: $cmd"
}

json_read() {
  local key="$1"
  jq -er "$key" "$METADATA_PATH"
}

validate_package_name() {
  local value="$1"
  if [[ "${#value}" -gt 255 ]]; then
    err "packageName exceeds 255 chars."
  fi
  if [[ ! "$value" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
    err "Invalid packageName: $value"
  fi
}

validate_version_name() {
  local value="$1"
  if [[ -z "$value" || "${#value}" -gt 64 ]]; then
    err "versionName must be 1..64 characters."
  fi
}

xml_escape() {
  local value="$1"
  value="${value//&/&amp;}"
  value="${value//</&lt;}"
  value="${value//>/&gt;}"
  value="${value//\"/&quot;}"
  value="${value//\'/&apos;}"
  printf '%s' "$value"
}

main() {
  require_cmd jq
  require_cmd zipalign
  require_cmd apksigner
  require_cmd aapt

  require_file "$METADATA_PATH"
  require_dir "$WEB_DIST_DIR"
  require_dir "$SHELL_TEMPLATE_DIR"

  mkdir -p "$OUTPUT_DIR"
  rm -f "$OUTPUT_DIR/app.apk" "$OUTPUT_DIR/app-aligned.apk" "$OUTPUT_DIR/app-release-unsigned.apk" "$OUTPUT_DIR/build-report.json"

  local package_name app_label version_name version_code keystore_path keystore_alias
  package_name="$(json_read '.packageName')"
  app_label="$(json_read '.appLabel')"
  version_name="$(json_read '.versionName')"
  version_code="$(json_read '.versionCode')"
  keystore_path="$(json_read '.keystore.path')"
  keystore_alias="$(json_read '.keystore.alias')"
  local keystore_password key_password
  keystore_password="$(json_read '.keystore.storePassword')"
  key_password="$(json_read '.keystore.keyPassword')"

  [[ "$version_code" =~ ^[0-9]+$ ]] || err "versionCode must be a positive integer."
  (( version_code > 0 )) || err "versionCode must be > 0."
  [[ -n "$app_label" ]] || err "appLabel must not be empty."
  [[ "${#app_label}" -le 64 ]] || err "appLabel must be <= 64 characters."
  validate_package_name "$package_name"
  validate_version_name "$version_name"

  if [[ "$keystore_path" != /* ]]; then
    keystore_path="${INPUT_DIR}/${keystore_path}"
  fi
  require_file "$keystore_path"

  local build_root shell_dir
  build_root="$(mktemp -d /tmp/portl-shell-build.XXXXXX)"
  trap 'if [ -n "${build_root:-}" ]; then rm -rf "$build_root"; fi' EXIT
  shell_dir="${build_root}/portl-shell"
  cp -R "${SHELL_TEMPLATE_DIR}" "$shell_dir"

  local app_build_file app_manifest_file strings_file
  app_build_file="${shell_dir}/app/build.gradle.kts"
  app_manifest_file="${shell_dir}/app/src/main/AndroidManifest.xml"
  strings_file="${shell_dir}/app/src/main/res/values/strings.xml"

  require_file "$app_build_file"
  require_file "$app_manifest_file"
  require_file "$strings_file"

  sed -i \
    -e "s|^\\([[:space:]]*applicationId = \\).*|\\1\"${package_name}\"|" \
    -e "s|^\\([[:space:]]*versionCode = \\).*|\\1${version_code}|" \
    -e "s|^\\([[:space:]]*versionName = \\).*|\\1\"${version_name}\"|" \
    "$app_build_file"

  sed -i \
    -e 's|android:label="[^"]*"|android:label="@string/app_name"|' \
    "$app_manifest_file"

  cat > "$strings_file" <<EOF
<resources>
    <string name="app_name">$(xml_escape "$app_label")</string>
</resources>
EOF

  mkdir -p "${shell_dir}/app/src/main/assets/www"
  find "${shell_dir}/app/src/main/assets/www" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -R "${WEB_DIST_DIR}/." "${shell_dir}/app/src/main/assets/www/"

  printf "sdk.dir=%s\n" "${ANDROID_SDK_ROOT}" > "${shell_dir}/local.properties"

  (
    cd "$shell_dir"
    ./gradlew --no-daemon clean :app:assembleRelease >/tmp/portl-gradle-build.log 2>&1 || {
      cat /tmp/portl-gradle-build.log >&2
      err "Gradle build failed."
    }
  )

  local unsigned_apk aligned_apk final_apk
  unsigned_apk="${shell_dir}/app/build/outputs/apk/release/app-release-unsigned.apk"
  aligned_apk="${OUTPUT_DIR}/app-aligned.apk"
  final_apk="${OUTPUT_DIR}/app.apk"

  require_file "$unsigned_apk"
  cp "$unsigned_apk" "${OUTPUT_DIR}/app-release-unsigned.apk"

  zipalign -f -p 4 "$unsigned_apk" "$aligned_apk"

  local ks_type=""
  case "${keystore_path}" in
    *.p12|*.P12|*.pfx|*.PFX) ks_type="PKCS12" ;;
    *.jks|*.JKS) ks_type="JKS" ;;
  esac

  apksigner sign \
    --ks "$keystore_path" \
    ${ks_type:+--ks-type "$ks_type"} \
    --ks-key-alias "$keystore_alias" \
    --ks-pass "pass:${keystore_password}" \
    --key-pass "pass:${key_password}" \
    --out "$final_apk" \
    "$aligned_apk"

  apksigner verify --verbose "$final_apk"
  aapt dump badging "$final_apk" | grep -q "package: name='${package_name}'" \
    || err "Final APK package name does not match expected packageName."

  jq -n \
    --arg status "ok" \
    --arg apkPath "app.apk" \
    --arg unsignedApkPath "app-release-unsigned.apk" \
    --arg alignedApkPath "app-aligned.apk" \
    --arg packageName "${package_name}" \
    --argjson versionCode "${version_code}" \
    --arg versionName "${version_name}" \
    '{
      status: $status,
      apkPath: $apkPath,
      unsignedApkPath: $unsignedApkPath,
      alignedApkPath: $alignedApkPath,
      packageName: $packageName,
      versionCode: $versionCode,
      versionName: $versionName
    }' > "${OUTPUT_DIR}/build-report.json"

  echo "portl-builder: APK ready at ${final_apk}"
}

main "$@"
