#!/usr/bin/env bash
# Print the tag for this commit: vX.Y.Z on main, vX.Y.Z-rc.N on release.
# With no final tag, the version is v1.<merge-count>.0 from
# `git rev-list --merges --count HEAD` on this commit. A shallow checkout is
# unshallowed first. Any other branch, or any failed lookup, exits non-zero.
set -euo pipefail

export GIT_TERMINAL_PROMPT=0

die() {
  echo "next-version: $*" >&2
  exit 1
}

highest_matching() {
  local text="$1"
  local pattern="$2"
  printf '%s\n' "${text}" | grep -E "${pattern}" | sort -V | tail -n 1 || true
}

next_rc() {
  local version="$1"
  local prefix="v${version}-rc."
  local max=0
  local line n
  while IFS= read -r line || [ -n "${line}" ]; do
    [ -z "${line}" ] && continue
    case "${line}" in
      "${prefix}"*)
        n=${line#"${prefix}"}
        case "${n}" in
          ''|*[!0-9]*) continue ;;
        esac
        if [ "${n}" -gt "${max}" ]; then
          max=${n}
        fi
        ;;
    esac
  done <<< "${all_tags}"
  printf '%s\n' "$((max + 1))"
}

bump_version() {
  local version="$1"
  local kind="$2"
  local major minor patch
  IFS=. read -r major minor patch <<< "${version}"
  case "${kind}" in
    major)
      major=$((major + 1))
      minor=0
      patch=0
      ;;
    minor)
      minor=$((minor + 1))
      patch=0
      ;;
    patch)
      patch=$((patch + 1))
      ;;
    *)
      die "unknown bump ${kind}"
      ;;
  esac
  printf '%s.%s.%s\n' "${major}" "${minor}" "${patch}"
}

bump_kind() {
  local json="$1"
  command -v jq >/dev/null 2>&1 || die "jq is required"
  printf '%s' "${json}" | jq -er '
    def is_feat: test("^feat($|[^A-Za-z0-9])"; "i");
    if type != "array" then
      error("pulls payload is not an array")
    elif length == 0 then
      "patch"
    else
      ((map(select(.merged_at != null)) | .[0]) // .[0]) as $pr
      | (($pr.labels // []) | map(.name)) as $names
      | if any($names[]; . == "major") then "major"
        elif (any($names[]; . == "minor") or (($pr.title // "") | is_feat)) then "minor"
        else "patch"
        end
    end
  '
}

fetch_pulls() {
  local sha="$1"
  local repo="${GITHUB_REPOSITORY:-}"
  if [ -z "${repo}" ]; then
    repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)" || return 1
  fi
  gh api \
    --header "Accept: application/vnd.github.groot-preview+json" \
    "/repos/${repo}/commits/${sha}/pulls"
}

emit() {
  local tag="$1"
  if [[ ! "${tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$ ]]; then
    die "refusing invalid tag ${tag}"
  fi
  if [ "${branch}" = "main" ] && [[ "${tag}" == *-rc.* ]]; then
    die "main cannot emit a pre-release tag"
  fi
  if [ "${branch}" = "release" ] && [[ "${tag}" != *-rc.* ]]; then
    die "release cannot emit a final tag"
  fi
  printf '%s\n' "${tag}"
}

if [ "${NEXT_VERSION_OFFLINE:-}" = "1" ]; then
  all_tags="${NEXT_VERSION_TAGS-}"
  head_tags="${NEXT_VERSION_HEAD_TAGS-}"
  branch="${VERSION_BRANCH:-${GITHUB_REF_NAME:-}}"
  sha="${VERSION_SHA:-}"
else
  git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not a git repository"
  shallow="$(git rev-parse --is-shallow-repository)"
  if [ "${shallow}" = "true" ]; then
    git fetch --unshallow --tags origin \
      || die "could not unshallow and fetch tags"
  fi
  sha="${VERSION_SHA:-${GITHUB_SHA:-$(git rev-parse HEAD)}}"
  all_tags="$(git tag --list)"
  head_tags="$(git tag --points-at "${sha}")"
  branch="${VERSION_BRANCH:-${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD)}}"
fi

case "${branch}" in
  main|release) ;;
  *) die "branch ${branch:-unknown} is not main or release" ;;
esac

if [ "${branch}" = "main" ]; then
  existing="$(highest_matching "${head_tags}" '^v[0-9]+\.[0-9]+\.[0-9]+$')"
else
  existing="$(highest_matching "${head_tags}" '^v[0-9]+\.[0-9]+\.[0-9]+-rc\.[0-9]+$')"
fi
if [ -n "${existing}" ]; then
  emit "${existing}"
  exit 0
fi

latest_final="$(highest_matching "${all_tags}" '^v[0-9]+\.[0-9]+\.[0-9]+$')"

if [ -z "${latest_final}" ]; then
  if [ "${NEXT_VERSION_OFFLINE:-}" = "1" ]; then
    die "bootstrap needs git history"
  fi
  merge_count="$(git rev-list --merges --count HEAD)" || die "could not count merge commits"
  if [[ ! "${merge_count}" =~ ^[0-9]+$ ]]; then
    die "merge count is not a number"
  fi
  bootstrap="1.${merge_count}.0"
  if [ "${branch}" = "main" ]; then
    emit "v${bootstrap}"
  else
    emit "v${bootstrap}-rc.$(next_rc "${bootstrap}")"
  fi
  exit 0
fi

if [ "${NEXT_VERSION_OFFLINE:-}" = "1" ]; then
  pulls_json="${NEXT_VERSION_PULLS_JSON-}"
  [ -n "${pulls_json}" ] || die "NEXT_VERSION_PULLS_JSON is required to bump"
else
  pulls_json="$(fetch_pulls "${sha}")" || die "GitHub commits/${sha}/pulls failed"
fi

kind="$(bump_kind "${pulls_json}")" || die "could not read the merged pull request"
next="$(bump_version "${latest_final#v}" "${kind}")"

if [ "${branch}" = "main" ]; then
  emit "v${next}"
else
  emit "v${next}-rc.$(next_rc "${next}")"
fi
