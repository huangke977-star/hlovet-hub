#!/usr/bin/env node

import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const EXIT_MEMORY_GATE = 78;
const DEFAULT_MIN_FREE_GIB = 8;
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const androidDirectory = path.resolve(scriptDirectory, '..');

/**
 * 将命令行参数拆分为内存门禁参数和需要传给 Gradle Wrapper 的参数。
 * @returns {{checkOnly: boolean, minFreeGiB: number, gradleArgs: string[]}}
 */
function parseArguments() {
  const args = process.argv.slice(2);
  let checkOnly = false;
  let minFreeGiB = DEFAULT_MIN_FREE_GIB;
  const gradleArgs = [];

  // 只消费门禁自身的选项，其余参数必须原样交给 Gradle，避免改变测试命令语义。
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === '--check-only') {
      checkOnly = true;
      continue;
    }
    if (argument === '--min-free-gib') {
      const value = args[index + 1];
      if (value === undefined) {
        throw new Error('--min-free-gib 需要一个数值参数。');
      }
      minFreeGiB = Number(value);
      index += 1;
      continue;
    }
    if (argument.startsWith('--min-free-gib=')) {
      minFreeGiB = Number(argument.slice('--min-free-gib='.length));
      continue;
    }
    gradleArgs.push(argument);
  }

  // 门槛只能提高不能降低，避免命令行参数绕过已确认的 8 GiB 安全线。
  if (!Number.isFinite(minFreeGiB) || minFreeGiB < DEFAULT_MIN_FREE_GIB) {
    throw new Error(`--min-free-gib 不能低于已确认的 ${DEFAULT_MIN_FREE_GIB} GiB 安全线。`);
  }

  return { checkOnly, minFreeGiB, gradleArgs };
}

/**
 * 读取当前机器的可用物理内存，并返回 GiB 口径，便于和用户确认的安全线比较。
 * @returns {number} 当前可用物理内存 GiB。
 */
function getFreeMemoryGiB() {
  return os.freemem() / 1024 ** 3;
}

/**
 * 输出内存门禁结果。
 * @param {number} freeMemoryGiB 当前可用物理内存 GiB。
 * @param {number} minFreeGiB 允许启动任务的最低可用内存 GiB。
 * @returns {boolean} 是否允许继续执行。
 */
function printMemoryGateResult(freeMemoryGiB, minFreeGiB) {
  const allowed = freeMemoryGiB >= minFreeGiB;
  const status = allowed ? '通过' : '拦截';
  console.log(`[android-memory-gate] ${status}: 可用物理内存 ${freeMemoryGiB.toFixed(2)} GiB，安全线 ${minFreeGiB.toFixed(2)} GiB。`);
  if (!allowed) {
    console.error('[android-memory-gate] 为避免内存提交不足导致应用或测试进程退出，未启动 Gradle。');
  }
  return allowed;
}

/**
 * 在内存通过后启动 Android 工程的 Gradle Wrapper，并透传原始 Gradle 参数。
 * @param {string[]} gradleArgs 需要传给 Gradle Wrapper 的参数。
 * @returns {Promise<number>} Gradle Wrapper 的退出码。
 */
function runGradle(gradleArgs) {
  const wrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  return new Promise((resolve, reject) => {
    const child = spawn(wrapper, gradleArgs, {
      cwd: androidDirectory,
      stdio: 'inherit',
      shell: process.platform === 'win32',
    });
    child.on('error', reject);
    child.on('exit', (code, signal) => {
      if (signal) {
        reject(new Error(`Gradle Wrapper 被信号 ${signal} 终止。`));
        return;
      }
      resolve(code ?? 1);
    });
  });
}

/**
 * 执行内存检查或在通过后运行 Gradle。
 * @returns {Promise<void>} 执行完成。
 */
async function main() {
  let options;
  try {
    // 先解析门禁参数，保证非法门槛不会触发任何高内存任务。
    options = parseArguments();
  } catch (error) {
    console.error(`[android-memory-gate] ${error.message}`);
    process.exitCode = 2;
    return;
  }

  // 每次启动前重新读取内存，不使用上一次检查结果，避免检查和执行之间出现状态漂移。
  const freeMemoryGiB = getFreeMemoryGiB();
  if (!printMemoryGateResult(freeMemoryGiB, options.minFreeGiB)) {
    process.exitCode = EXIT_MEMORY_GATE;
    return;
  }

  // 只检查模式到此结束，适合在 CI 或人工执行 Gradle 前快速确认资源条件。
  if (options.checkOnly) {
    return;
  }

  try {
    // 仅在门禁通过后启动 Gradle，并保持 Gradle 参数、输出和退出码不变。
    process.exitCode = await runGradle(options.gradleArgs);
  } catch (error) {
    console.error(`[android-memory-gate] 无法启动 Gradle Wrapper：${error.message}`);
    process.exitCode = 1;
  }
}

main();
