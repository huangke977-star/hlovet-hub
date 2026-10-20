// 只读检查 APK 的实际签名与包名，生成 App Links 和 Passkey 所需的非敏感配置。
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

/** 参数 badging 是 aapt2 的清单输出；返回实际包名，缺失或非法时拒绝生成配置。 */
export function readApkPackage(badging) {
  // 仅使用 APK 清单中的 package 行，不从文件名或构建参数推断发布身份。
  const name = /^package: name='([^']+)'/m.exec(badging)?.[1];
  if (
    !name ||
    !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(name)
  ) {
    throw new Error("无法从 APK 清单读取合法包名。");
  }
  return name;
}

/** 参数为已校验的包名和 apksigner 证书输出；返回 App Links 指纹与 WebAuthn Origin 配置。 */
export function createApkLinkConfiguration(packageName, certificateOutput) {
  // 只接受当前签名证书的完整摘要，不把公钥摘要、Source Stamp 或历史证书当成当前签名。
  const digests = [
    ...certificateOutput.matchAll(
      /^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})\r?$/gim,
    ),
  ].map((match) => match[1].toUpperCase());
  const uniqueDigests = [...new Set(digests)];
  if (uniqueDigests.length === 0)
    throw new Error("APK 没有可读取的 SHA-256 签名证书。");

  // Android Origin 使用摘要原始字节的 Base64URL，不是十六进制字符串的 Base64。
  const fingerprints = uniqueDigests.map((digest) =>
    digest.match(/.{2}/g).join(":"),
  );
  const androidOrigins = uniqueDigests.map(
    (digest) =>
      `android:apk-key-hash:${Buffer.from(digest, "hex").toString("base64url")}`,
  );
  return {
    packageName,
    fingerprints,
    androidOrigins,
    environment: {
      ANDROID_APP_LINKS_JSON: JSON.stringify([{ packageName, fingerprints }]),
      PASSKEY_ANDROID_ORIGINS: androidOrigins.join(","),
    },
  };
}

/** 参数为 SDK 可执行文件和独立参数数组；返回标准输出，失败时拒绝生成信任配置。 */
function runSdkTool(command, args) {
  // 不经 shell 拼接 APK 路径，路径中的空格或特殊符号不能变成额外命令。
  const result = spawnSync(command, args, {
    encoding: "utf8",
    timeout: 60000,
    windowsHide: true,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `APK 检查失败：${result.error?.message || result.stderr?.trim() || "SDK 命令未成功完成"}`,
    );
  }
  return result.stdout;
}

/** 参数 sdkRoot 是 SDK 根目录；返回最新完整 build-tools 目录，没有可用版本时抛错。 */
function findBuildTools(sdkRoot) {
  // 排除预览或未安装完整的工具目录，避免以空目录或版本字符串顺序误选工具。
  const root = path.join(sdkRoot, "build-tools");
  const versions = fs
    .readdirSync(root, { withFileTypes: true })
    .filter(
      (entry) => entry.isDirectory() && /^\d+\.\d+\.\d+$/.test(entry.name),
    )
    .map((entry) => entry.name)
    .sort((left, right) => right.localeCompare(left, "en", { numeric: true }));
  const directory = versions
    .map((version) => path.join(root, version))
    .find(
      (candidate) =>
        fs.existsSync(
          path.join(
            candidate,
            process.platform === "win32" ? "aapt2.exe" : "aapt2",
          ),
        ) && fs.existsSync(path.join(candidate, "lib", "apksigner.jar")),
    );
  if (!directory) throw new Error("Android SDK 中没有完整的 build-tools。");
  return directory;
}

/** 参数 apkPath 是待检查 APK 路径；返回公开身份配置，不访问 keystore 或密码。 */
export function inspectApk(apkPath) {
  // 必须检查现有 APK，不能让 SDK 命令回退到默认包或仅检查构建配置。
  const apk = path.resolve(apkPath);
  if (!fs.statSync(apk).isFile()) throw new Error("APK 路径不是文件。");
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error("请先设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。");
  const tools = findBuildTools(sdk);
  const java = process.env.JAVA_HOME
    ? path.join(
        process.env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "java.exe" : "java",
      )
    : "java";

  // 先验签再读取配置；未签名或被篡改的 APK 必须失败，不输出可部署的环境变量。
  const certificates = runSdkTool(java, [
    "-jar",
    path.join(tools, "lib", "apksigner.jar"),
    "verify",
    "--print-certs",
    apk,
  ]);
  const badging = runSdkTool(
    path.join(tools, process.platform === "win32" ? "aapt2.exe" : "aapt2"),
    ["dump", "badging", apk],
  );
  return createApkLinkConfiguration(readApkPackage(badging), certificates);
}

// CLI 仅接收一个待检查的 APK 路径，输出供人工合并的配置，不自动覆盖线上环境。
if (
  process.argv[1] &&
  pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url
) {
  try {
    if (process.argv.length !== 3)
      throw new Error(
        "用法：node apps/android/tools/inspect-apk-links.mjs <已签名的.apk>",
      );
    process.stdout.write(
      `${JSON.stringify(inspectApk(process.argv[2]), null, 2)}\n`,
    );
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
