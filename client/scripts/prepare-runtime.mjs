import { createWriteStream, existsSync } from "node:fs";
import { cp, mkdir, mkdtemp, readdir, rm, chmod } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { pipeline } from "node:stream/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const version = process.env.SHELLMIND_JRE_VERSION || "17";
const target = process.env.SHELLMIND_JRE_TARGET || `${process.platform}-${process.arch}`;
const outputRoot = join(process.cwd(), "resources", "agent", "runtime");

const platformMap = {
  "darwin-x64": { image: "mac", arch: "x64", extension: "tar.gz" },
  "darwin-arm64": { image: "mac", arch: "aarch64", extension: "tar.gz" },
  "win32-x64": { image: "windows", arch: "x64", extension: "zip" },
  "win32-arm64": { image: "windows", arch: "aarch64", extension: "zip" },
  "linux-x64": { image: "linux", arch: "x64", extension: "tar.gz" },
  "linux-arm64": { image: "linux", arch: "aarch64", extension: "tar.gz" },
};

const platform = platformMap[target];
if (!platform) {
  throw new Error(`Unsupported JRE target platform: ${target}. Available: ${Object.keys(platformMap).join(", ")}`);
}

const temporaryRoot = await mkdtemp(join(tmpdir(), "shellmind-jre-"));
const archive = join(temporaryRoot, `java-runtime.${platform.extension}`);
const unpacked = join(temporaryRoot, "unpacked");

async function download(file, destination) {
  const response = await fetch(file, { redirect: "follow" });
  if (!response.ok || !response.body) {
    throw new Error(`Failed to download JRE: HTTP ${response.status} ${file}`);
  }
  await pipeline(response.body, createWriteStream(destination));
}

async function extract() {
  await mkdir(unpacked, { recursive: true });
  if (platform.extension === "zip") {
    await execFileAsync("tar", ["-xf", archive, "-C", unpacked]);
  } else {
    await execFileAsync("tar", ["-xzf", archive, "-C", unpacked]);
  }
}

async function findJava(directory) {
  const javaName = platform.image === "windows" ? "java.exe" : "java";
  const pending = [directory];
  while (pending.length > 0) {
    const current = pending.pop();
    const entries = await readdir(current, { withFileTypes: true });
    for (const entry of entries) {
      const candidate = join(current, entry.name);
      if (entry.isDirectory()) {
        pending.push(candidate);
      } else if (entry.isFile() && entry.name === javaName) {
        return dirname(dirname(candidate));
      }
    }
  }
  throw new Error(`${javaName} not found in the JRE archive`);
}

const javaHomeCandidates = process.env.JAVA_HOME
  ? [process.env.JAVA_HOME]
  : platform.image === "mac"
    ? ["/usr/libexec/java_home"]
    : [];

async function jlinkFromLocalJavaHome() {
  if (platform.image !== "mac") {
    return null;
  }
  const { stdout } = await execFileAsync("/usr/libexec/java_home", [`-v`, `${version}.0`]);
  const javaHome = stdout.trim();
  if (!existsSync(join(javaHome, "bin", "jlink"))) {
    return null;
  }
  const outputRoot = join(temporaryRoot, "jlink-runtime");
  await execFileAsync(join(javaHome, "bin", "jlink"), [
    "--add-modules",
    "ALL-MODULE-PATH",
    "--strip-debug",
    "--no-man-pages",
    "--no-header-files",
    "--compress=2",
    "--output",
    outputRoot,
  ]);
  return outputRoot;
}

async function downloadTemurin() {
  const url = `https://api.adoptium.net/v3/binary/latest/${version}/ga/${platform.image}/${platform.arch}/jre/hotspot/normal/eclipse?project=jdk`;
  const archive = join(temporaryRoot, "java-runtime." + platform.extension);
  console.log(`Downloading Java ${version} runtime for ${target}: ${url}`);
  await download(url, archive);
  await extract();
  return findJava(unpacked);
}

try {
  console.log(`Preparing Java ${version} runtime for ${target}`);
  let javaHome = null;
  try {
    javaHome = await jlinkFromLocalJavaHome();
  } catch (error) {
    console.warn(`Local jlink failed, falling back to JRE download: ${error.message}`);
  }
  if (!javaHome) {
    javaHome = await downloadTemurin();
  }
  await rm(outputRoot, { recursive: true, force: true });
  await mkdir(dirname(outputRoot), { recursive: true });
  await cp(javaHome, outputRoot, { recursive: true, dereference: true });
  if (platform.image !== "windows") {
    await chmod(join(outputRoot, "bin", "java"), 0o755);
  } else {
    await chmod(join(outputRoot, "bin", "java.exe"), 0o755);
  }
  // Files under legal/ and similar jlink output dirs are read-only (0444).
  // Leaving them read-only makes incremental tauri builds fail to overwrite
  // existing files under target (fs::copy reports Permission denied),
  // so grant user write permission on every file here.
  if (platform.image !== "windows") {
    await execFileAsync("chmod", ["-R", "u+w", outputRoot]);
  }
  console.log(`Java ${version} runtime prepared at ${outputRoot}`);
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}
