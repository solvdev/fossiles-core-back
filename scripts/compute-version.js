#!/usr/bin/env node
"use strict";

const { execFileSync, spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const FEAT_TITLE = /^feat(\(|:|\s|$)/i;
const FINAL_TAG = /^v?(\d+)\.(\d+)\.(\d+)$/i;
const RC_TAG = /^v?(\d+)\.(\d+)\.(\d+)-rc\.(\d+)$/i;

function labelName(label) {
  if (!label) return "";
  if (typeof label === "string") return label;
  return String(label.name || "");
}

function bumpTypeFromPr({ labels = [], title = "" } = {}) {
  const names = (Array.isArray(labels) ? labels : []).map((item) => labelName(item).toLowerCase());
  if (names.includes("major")) return "major";
  const trimmedTitle = String(title || "").trim();
  if (names.includes("minor") || FEAT_TITLE.test(trimmedTitle)) return "minor";
  return "patch";
}

function parseTag(tag) {
  const raw = String(typeof tag === "string" ? tag : (tag && tag.name) || "").trim();
  const rc = raw.match(RC_TAG);
  if (rc) {
    const version = `${rc[1]}.${rc[2]}.${rc[3]}`;
    return {
      kind: "rc",
      major: Number(rc[1]),
      minor: Number(rc[2]),
      patch: Number(rc[3]),
      rc: Number(rc[4]),
      version,
      name: `v${version}-rc.${Number(rc[4])}`,
    };
  }
  const final = raw.match(FINAL_TAG);
  if (!final) return null;
  const version = `${final[1]}.${final[2]}.${final[3]}`;
  return {
    kind: "final",
    major: Number(final[1]),
    minor: Number(final[2]),
    patch: Number(final[3]),
    version,
    name: `v${version}`,
  };
}

function parseSemver(tag) {
  const parsed = parseTag(tag);
  if (!parsed || parsed.kind !== "final") return null;
  return { major: parsed.major, minor: parsed.minor, patch: parsed.patch };
}

function formatSemver({ major, minor, patch }) {
  return `${major}.${minor}.${patch}`;
}

function stripV(value) {
  return String(value || "").trim().replace(/^v/i, "");
}

function compareFinal(a, b) {
  return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

function highestFinalTag(tags) {
  const finals = (tags || []).map(parseTag).filter((tag) => tag && tag.kind === "final");
  if (finals.length === 0) return null;
  finals.sort(compareFinal);
  return finals[finals.length - 1].name;
}

function highestTag(tags) {
  return highestFinalTag(tags);
}

function bootstrapVersion(bootstrapMinor) {
  if (bootstrapMinor == null || bootstrapMinor === "") {
    throw new Error("merge count is required to bootstrap the first version");
  }
  const minor = Number(bootstrapMinor);
  if (!Number.isInteger(minor) || minor < 0) {
    throw new Error("merge count is required to bootstrap the first version");
  }
  return `1.${minor}.0`;
}

function nextVersion(latestFinalTag, bumpType, bootstrapMinor) {
  const parsed = parseTag(latestFinalTag);
  if (!parsed || parsed.kind !== "final") return bootstrapVersion(bootstrapMinor);
  if (bumpType === "major") return formatSemver({ major: parsed.major + 1, minor: 0, patch: 0 });
  if (bumpType === "minor") return formatSemver({ major: parsed.major, minor: parsed.minor + 1, patch: 0 });
  return formatSemver({ major: parsed.major, minor: parsed.minor, patch: parsed.patch + 1 });
}

function nextRcNumber(tags, next) {
  const base = stripV(next);
  let highest = 0;
  (tags || []).forEach((tag) => {
    const parsed = parseTag(tag);
    if (parsed && parsed.kind === "rc" && parsed.version === base && parsed.rc > highest) {
      highest = parsed.rc;
    }
  });
  return highest + 1;
}

function planRelease({ branch, tags = [], bumpType = "patch", headTags = [], bootstrapMinor } = {}) {
  const isReleaseBranch = branch === "release";
  const parsedHead = (headTags || []).map(parseTag).filter(Boolean);
  const matchingHead = parsedHead.filter((tag) => (isReleaseBranch ? tag.kind === "rc" : tag.kind === "final"));

  if (matchingHead.length > 0) {
    if (isReleaseBranch) {
      matchingHead.sort((a, b) => a.rc - b.rc);
    } else {
      matchingHead.sort(compareFinal);
    }
    const current = matchingHead[matchingHead.length - 1];
    return {
      skip: true,
      version: stripV(current.name),
      tag: current.name,
      bump: "none",
      prerelease: isReleaseBranch,
    };
  }

  const latestFinal = highestFinalTag(tags);
  const next = nextVersion(latestFinal, bumpType, bootstrapMinor);
  const bump = latestFinal ? bumpType : "initial";

  if (isReleaseBranch) {
    const version = `${next}-rc.${nextRcNumber(tags, next)}`;
    return {
      skip: false,
      version,
      tag: `v${version}`,
      bump,
      prerelease: true,
    };
  }

  return {
    skip: false,
    version: next,
    tag: `v${next}`,
    bump,
    prerelease: false,
  };
}

function execGit(args) {
  try {
    return execFileSync("git", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return "";
  }
}

function gitHeadSha() {
  return process.env.GITHUB_SHA || execGit(["rev-parse", "HEAD"]) || "";
}

function gitShortSha(fullSha = gitHeadSha()) {
  if (fullSha) return fullSha.slice(0, 7);
  return execGit(["rev-parse", "--short=7", "HEAD"]) || "unknown";
}

function gitBranch() {
  const fromEnv = process.env.GITHUB_REF_NAME || process.env.GITHUB_REF || "";
  const name = String(fromEnv).replace(/^refs\/heads\//, "").trim();
  if (name) return name;
  return execGit(["rev-parse", "--abbrev-ref", "HEAD"]) || "main";
}

function listLocalTags() {
  const out = execGit(["tag", "-l", "v*"]);
  if (!out) return [];
  return out.split("\n").map((line) => line.trim()).filter(Boolean);
}

function ensureTags() {
  if (listLocalTags().length > 0) return;
  try {
    execFileSync("git", ["fetch", "--tags", "--force", "--quiet"], {
      stdio: "ignore",
    });
  } catch {
    // Shallow clones / missing remotes must not break the build.
  }
}

function isShallowRepo() {
  return execGit(["rev-parse", "--is-shallow-repository"]) === "true";
}

function unshallowIfNeeded() {
  if (!isShallowRepo()) return;
  try {
    execFileSync("git", ["fetch", "--unshallow", "--quiet"], {
      stdio: "ignore",
    });
  } catch {
    // Not shallow, missing remotes, or unshallow unsupported: ignore.
  }
}

function mergeCount() {
  const out = execGit(["rev-list", "--merges", "--count", "HEAD"]);
  if (!/^\d+$/.test(out)) {
    throw new Error("could not count merge commits for bootstrap version");
  }
  return Number(out);
}

function localTagsOnHead() {
  const out = execGit(["tag", "--points-at", "HEAD"]);
  if (!out) return [];
  return out.split("\n").map((line) => line.trim()).filter((tag) => parseTag(tag));
}

function gitCommitMessage() {
  return execGit(["log", "-1", "--pretty=%B"]);
}

function titleFromCommitMessage(message) {
  const lines = String(message || "").split("\n").map((line) => line.trim()).filter(Boolean);
  const featLine = lines.find((line) => FEAT_TITLE.test(line));
  return featLine || lines[0] || "";
}

function log(message) {
  process.stderr.write(`${message}\n`);
}

async function githubRequest(pathname) {
  const repo = process.env.GITHUB_REPOSITORY;
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (!repo) return null;
  const url = pathname.startsWith("http")
    ? pathname
    : `https://api.github.com/repos/${repo}${pathname}`;
  const headers = {
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "fossiles-core-back-versioning",
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  try {
    const response = await fetch(url, { headers });
    if (!response.ok) {
      log(`versioning: GitHub API ${response.status} for ${pathname}`);
      return null;
    }
    return await response.json();
  } catch (error) {
    log(`versioning: GitHub API error (${error.message})`);
    return null;
  }
}

async function listGithubTags() {
  const tags = [];
  for (let page = 1; page <= 10; page += 1) {
    const batch = await githubRequest(`/tags?per_page=100&page=${page}`);
    if (!Array.isArray(batch) || batch.length === 0) break;
    tags.push(...batch);
    if (batch.length < 100) break;
  }
  return tags;
}

async function fetchAssociatedPr(sha) {
  if (!sha) return null;
  const pulls = await githubRequest(`/commits/${encodeURIComponent(sha)}/pulls`);
  if (!Array.isArray(pulls) || pulls.length === 0) return null;
  return pulls.find((pr) => pr && pr.merged_at) || pulls[0];
}

function writeGithubOutput(result) {
  const lines = [
    `skip=${result.skip ? "true" : "false"}`,
    `version=${result.version}`,
    `tag=${result.tag}`,
    `commit=${result.commit}`,
    `bump=${result.bump}`,
    `prerelease=${result.prerelease ? "true" : "false"}`,
  ];
  const text = `${lines.join("\n")}\n`;
  process.stdout.write(text);
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(process.env.GITHUB_OUTPUT, text);
  }
}

function sameCommit(sha, head) {
  if (!sha || !head) return false;
  const left = String(sha).toLowerCase();
  const right = String(head).toLowerCase();
  return left === right || left.startsWith(right) || right.startsWith(left);
}

async function computeRelease() {
  const head = gitHeadSha();
  const commit = gitShortSha(head);
  const branch = gitBranch();

  ensureTags();

  const githubTags = await listGithubTags();
  const tags = [
    ...listLocalTags(),
    ...githubTags.map((tag) => tag && tag.name).filter(Boolean),
  ];
  const headTags = [
    ...localTagsOnHead(),
    ...githubTags
      .filter((tag) => tag && tag.commit && sameCommit(tag.commit.sha, head))
      .map((tag) => tag.name)
      .filter((name) => parseTag(name)),
  ];

  let bootstrapMinor;
  if (!highestFinalTag(tags)) {
    if (listLocalTags().length === 0) {
      unshallowIfNeeded();
    }
    bootstrapMinor = mergeCount();
  }

  const pr = await fetchAssociatedPr(head);
  const title = (pr && pr.title) || titleFromCommitMessage(gitCommitMessage());
  const labels = (pr && pr.labels) || [];
  const bumpType = bumpTypeFromPr({ labels, title });
  const planned = planRelease({ branch, tags, bumpType, headTags, bootstrapMinor });
  return { ...planned, commit };
}

function runBuild({ version, commit }) {
  const env = {
    ...process.env,
    APP_VERSION: version,
    APP_COMMIT: commit,
  };
  log(`Building back ${version} (${commit})`);
  const isWindows = process.platform === "win32";
  const gradlew = path.join(__dirname, "..", isWindows ? "gradlew.bat" : "gradlew");
  const result = spawnSync(gradlew, ["clean", "bootJar", "-x", "test"], {
    stdio: "inherit",
    env,
    shell: isWindows,
  });
  process.exit(result.status === null ? 1 : result.status);
}

async function resolveBuildEnv(compute = computeRelease) {
  const commit = process.env.APP_COMMIT || gitShortSha();
  if (process.env.APP_VERSION && String(process.env.APP_VERSION).trim()) {
    return { version: stripV(process.env.APP_VERSION), commit };
  }
  if (process.env.GITHUB_ACTIONS) {
    try {
      const release = await compute();
      return { version: release.version, commit: release.commit || commit };
    } catch (error) {
      const detail = error && error.message ? error.message : error;
      log(`versioning: warning: ${detail}; falling back to dev`);
      return { version: "dev", commit: commit || "unknown" };
    }
  }
  return { version: "dev", commit: commit || "unknown" };
}

async function main() {
  const build = process.argv.includes("--build");
  if (build) {
    const env = await resolveBuildEnv();
    runBuild(env);
    return;
  }
  const result = await computeRelease();
  writeGithubOutput(result);
}

module.exports = {
  bumpTypeFromPr,
  nextVersion,
  nextRcNumber,
  parseSemver,
  parseTag,
  highestTag,
  highestFinalTag,
  planRelease,
  stripV,
  computeRelease,
  resolveBuildEnv,
};

if (require.main === module) {
  main().catch((error) => {
    log(`versioning: ${error.message}`);
    process.exit(1);
  });
}
