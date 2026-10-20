// 覆盖 APK 身份解析和双种摘要格式，防止错误证书或格式被复制到生产配置。
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createApkLinkConfiguration,
  readApkPackage,
} from "./inspect-apk-links.mjs";

test("只读取 APK package 行并拒绝缺失或非法包名", () => {
  // 不能把文件名、标签或无效分段当成安装包的 applicationId。
  assert.equal(
    readApkPackage("package: name='xyz.hlovet.portal' versionCode='1'\n"),
    "xyz.hlovet.portal",
  );
  for (const value of [
    "application-label:'xyz.hlovet.portal'",
    "package: name='xyz..portal'",
    "package: name='xyz.1portal'",
  ]) {
    assert.throws(() => readApkPackage(value), /合法包名/);
  }
});

test("同一原始摘要生成冒号指纹与无填充的 Base64URL Origin", () => {
  // 全 FF 摘要可区分 URL-safe 编码与普通 Base64，同时证明不是编码十六进制文本。
  const config = createApkLinkConfiguration(
    "xyz.hlovet.portal",
    `Signer #1 certificate SHA-256 digest: ${"ff".repeat(32)}\n`,
  );
  assert.deepEqual(config.fingerprints, [Array(32).fill("FF").join(":")]);
  assert.deepEqual(config.androidOrigins, [
    `android:apk-key-hash:${"_".repeat(42)}8`,
  ]);
  assert.deepEqual(JSON.parse(config.environment.ANDROID_APP_LINKS_JSON), [
    { packageName: config.packageName, fingerprints: config.fingerprints },
  ]);
  assert.equal(
    config.environment.PASSKEY_ANDROID_ORIGINS,
    config.androidOrigins[0],
  );
});

test("多个实际签名去重但保留不同证书，忽略公钥和 Source Stamp 摘要", () => {
  // 只接受 apksigner 的当前证书行，不将其他 SHA-256 输出扩大为受信任签名。
  const output = [
    `Signer #1 certificate SHA-256 digest: ${"01".repeat(32)}`,
    `Signer #1 certificate public key SHA-256 digest: ${"02".repeat(32)}`,
    `Source Stamp Signer certificate SHA-256 digest: ${"03".repeat(32)}`,
    `Signer #2 certificate SHA-256 digest: ${"01".repeat(32)}`,
    `Signer #3 certificate SHA-256 digest: ${"04".repeat(32)}`,
  ].join("\r\n");
  const config = createApkLinkConfiguration("xyz.hlovet.portal", output);
  assert.equal(config.fingerprints.length, 2);
  assert.equal(config.androidOrigins.length, 2);
  assert.equal(config.environment.PASSKEY_ANDROID_ORIGINS.split(",").length, 2);
});

test("没有完整的当前证书摘要就拒绝生成配置", () => {
  // 旧 SHA-1、截断摘要和非十六进制字符均不能成为新发布包的可信依据。
  for (const output of [
    "",
    `Signer #1 certificate SHA-1 digest: ${"ab".repeat(20)}`,
    `Signer #1 certificate SHA-256 digest: ${"ab".repeat(31)}`,
    `Signer #1 certificate SHA-256 digest: ${"zz".repeat(32)}`,
  ]) {
    assert.throws(
      () => createApkLinkConfiguration("xyz.hlovet.portal", output),
      /签名证书/,
    );
  }
});
