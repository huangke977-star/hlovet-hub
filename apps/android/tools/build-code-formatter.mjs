import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs/promises";

const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, "../../..");
const require = createRequire(path.join(root, "package.json"));
const pnpm = path.join(root, "node_modules/.pnpm");
const esbuild = require(path.join(pnpm, "esbuild@0.28.1/node_modules/esbuild"));
const output = path.resolve(directory, "../prototype/src/main/assets/hlovet-code-format.min.js");
const result = await esbuild.build({
  entryPoints: [path.join(directory, "hlovet-code-format-entry.js")],
  outfile: output,
  nodePaths: [
    path.join(pnpm, "prettier-plugin-java@2.8.1_prettier@3.9.6/node_modules"),
    path.join(pnpm, "sql-formatter@15.6.8/node_modules"),
    path.join(pnpm, "prettier@3.9.6/node_modules"),
  ],
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "chrome80",
  minify: true,
  legalComments: "external",
  metafile: true,
});

// Ship the upstream licenses alongside the offline bundle.
const packages = new Map();
for (const input of Object.keys(result.metafile.inputs)) {
  let parent = path.dirname(path.resolve(input));
  while (parent !== path.dirname(parent)) {
    try {
      const manifest = JSON.parse(await fs.readFile(path.join(parent, "package.json"), "utf8"));
      if (manifest.name && !packages.has(manifest.name)) {
        const licenseName = (await fs.readdir(parent)).find((name) => /^licen[cs]e(?:\.|$)/i.test(name));
        if (licenseName) packages.set(manifest.name, `${manifest.name} ${manifest.version}\n${await fs.readFile(path.join(parent, licenseName), "utf8")}`);
      }
      break;
    } catch {
      parent = path.dirname(parent);
    }
  }
}
const notices = [...packages.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([, value]) => value);
await fs.writeFile(output + ".LICENSE.txt", notices.join("\n\n--------------------\n\n") + "\n");
console.log(`Built offline code formatter: ${path.relative(root, output)}`);
