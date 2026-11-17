#!/usr/bin/env bash
# Dry-run of .github/scripts/next-version.sh.
# Bump rules use fixture tags. Bootstrap uses a temporary git repository.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
script="${root}/.github/scripts/next-version.sh"
failures=0
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

expect_tag() {
  local name="$1"
  local expected="$2"
  local branch="$3"
  local tags="$4"
  local head="$5"
  local pulls="$6"
  local out code
  set +e
  out="$(
    NEXT_VERSION_OFFLINE=1 \
      VERSION_BRANCH="${branch}" \
      NEXT_VERSION_TAGS="${tags}" \
      NEXT_VERSION_HEAD_TAGS="${head}" \
      NEXT_VERSION_PULLS_JSON="${pulls}" \
      bash "${script}"
  )"
  code=$?
  set -e
  if [ "${code}" -ne 0 ] || [ "${out}" != "${expected}" ]; then
    echo "FAIL ${name}: expected [${expected}] exit 0, got [${out}] exit ${code}" >&2
    failures=$((failures + 1))
  else
    echo "ok ${name}"
  fi
}

expect_fail() {
  local name="$1"
  shift
  local out code
  set +e
  out="$(
    NEXT_VERSION_OFFLINE=1 \
      VERSION_BRANCH=main \
      NEXT_VERSION_TAGS=$'v1.2.0\n' \
      NEXT_VERSION_HEAD_TAGS= \
      NEXT_VERSION_PULLS_JSON='[]' \
      "$@" \
      bash "${script}"
  )"
  code=$?
  set -e
  if [ "${code}" -eq 0 ]; then
    echo "FAIL ${name}: expected failure, got [${out}]" >&2
    failures=$((failures + 1))
  else
    echo "ok ${name}"
  fi
}

expect_in_repo() {
  local name="$1"
  local dir="$2"
  local branch="$3"
  local expected="$4"
  local out code
  set +e
  out="$(
    cd "${dir}"
    unset GITHUB_SHA GITHUB_REF_NAME GITHUB_REPOSITORY GH_TOKEN GITHUB_TOKEN
    unset NEXT_VERSION_OFFLINE NEXT_VERSION_TAGS NEXT_VERSION_HEAD_TAGS NEXT_VERSION_PULLS_JSON
    VERSION_BRANCH="${branch}" bash "${script}"
  )"
  code=$?
  set -e
  if [ -n "${expected}" ]; then
    if [ "${code}" -ne 0 ] || [ "${out}" != "${expected}" ]; then
      echo "FAIL ${name}: expected [${expected}] exit 0, got [${out}] exit ${code}" >&2
      failures=$((failures + 1))
    else
      echo "ok ${name}"
    fi
  elif [ "${code}" -eq 0 ]; then
    echo "FAIL ${name}: expected failure, got [${out}]" >&2
    failures=$((failures + 1))
  else
    echo "ok ${name}"
  fi
}

lines() {
  printf '%s\n' "$@"
}

bootstrap_repo() {
  local src="${work}/src"
  git init -b main "${src}" >/dev/null
  git -C "${src}" config user.email test@example.com
  git -C "${src}" config user.name Test
  echo root > "${src}/file"
  git -C "${src}" add file
  git -C "${src}" commit -q -m root
  local i
  for i in 1 2 3; do
    git -C "${src}" checkout -q -b "feature-${i}"
    echo "change ${i}" >> "${src}/file"
    git -C "${src}" add file
    git -C "${src}" commit -q -m "feature ${i}"
    git -C "${src}" checkout -q main
    git -C "${src}" merge -q --no-ff "feature-${i}" -m "merge ${i}"
  done
  local count
  count="$(git -C "${src}" rev-list --merges --count HEAD)"
  if [ "${count}" != "3" ]; then
    echo "FAIL fixture merge count is ${count}, expected 3" >&2
    failures=$((failures + 1))
    return
  fi

  expect_in_repo "bootstrap is v1.<merge count>.0" "${src}" main "v1.3.0"
  expect_in_repo "release bootstrap is rc.1 of that version" "${src}" release "v1.3.0-rc.1"

  git -C "${src}" tag v1.3.0-rc.1 HEAD~1
  expect_in_repo "rc number increments while no final tag exists" "${src}" release "v1.3.0-rc.2"
  expect_in_repo "main ignores rc tags and still bootstraps" "${src}" main "v1.3.0"

  git clone --quiet --depth 1 "file://${src}" "${work}/shallow"
  if [ "$(git -C "${work}/shallow" rev-parse --is-shallow-repository)" != "true" ]; then
    echo "FAIL shallow fixture is not shallow" >&2
    failures=$((failures + 1))
  else
    expect_in_repo "shallow clone unshallows before counting merges" "${work}/shallow" main "v1.3.0"
  fi

  git clone --quiet --depth 1 "file://${src}" "${work}/broken"
  git -C "${work}/broken" remote set-url origin /nonexistent/fossiles-origin
  expect_in_repo "shallow fetch failure does not invent a version" "${work}/broken" main ""
}

patch='[{"title":"fix: stock","labels":[]}]'
feat='[{"title":"feat: ledger","labels":[]}]'
feat_paren='[{"title":"feat(api): ledger","labels":[]}]'
feat_upper='[{"title":"FEAT: ledger","labels":[]}]'
feature='[{"title":"feature: ledger","labels":[]}]'
major='[{"title":"feat: break","labels":[{"name":"major"},{"name":"minor"}]}]'
minor='[{"title":"fix: small","labels":[{"name":"minor"}]}]'
empty='[]'

bootstrap_repo

finals="$(lines v1.9.0 v1.10.0 v1.999.0-rc.4 v1.10.0-rc.3 release-1 v1.2)"
expect_tag "rc tags are not the latest final" "v1.10.1" main "${finals}" "" "${patch}"
expect_tag "release patch starts at rc.1" "v1.10.1-rc.1" release "${finals}" "" "${patch}"
expect_tag "feat title is minor" "v1.11.0" main "${finals}" "" "${feat}"
expect_tag "feat( scope is minor" "v1.11.0" main "${finals}" "" "${feat_paren}"
expect_tag "FEAT: is minor" "v1.11.0" main "${finals}" "" "${feat_upper}"
expect_tag "feature: is patch" "v1.10.1" main "${finals}" "" "${feature}"
expect_tag "minor label is minor" "v1.11.0" main "${finals}" "" "${minor}"
expect_tag "major label wins" "v2.0.0" main "${finals}" "" "${major}"
expect_tag "release major is rc.1" "v2.0.0-rc.1" release "${finals}" "" "${major}"
expect_tag "direct push is patch" "v1.10.1" main "${finals}" "" "${empty}"

rc_tags="$(lines v1.10.0 v1.10.1-rc.1 v1.10.1-rc.2 v1.10.1-rc.9 v1.10.1-rc.10 v1.11.0-rc.7)"
expect_tag "rc number is one past the highest for that version" \
  "v1.10.1-rc.11" release "${rc_tags}" "" "${patch}"
expect_tag "main after rc tags cuts the final from the latest final" \
  "v1.10.1" main "${rc_tags}" "" "${patch}"
expect_tag "minor rc keeps its own sequence" \
  "v1.11.0-rc.8" release "${rc_tags}" "" "${minor}"

expect_tag "main commit that already has a final tag is unchanged" \
  "v1.10.0" main "$(lines v1.10.0 v1.10.1-rc.3)" "v1.10.0" "${major}"
expect_tag "release commit that already has an rc tag is unchanged" \
  "v1.10.1-rc.2" release "${rc_tags}" "$(lines v1.10.1-rc.1 v1.10.1-rc.2)" "${patch}"

expect_fail "other branches fail closed" env VERSION_BRANCH=develop

if [ "${failures}" -ne 0 ]; then
  echo "${failures} failed" >&2
  exit 1
fi
echo "all passed"
