import * as prettier from "prettier/standalone";
import * as html from "prettier/plugins/html";
import * as postcss from "prettier/plugins/postcss";
import * as babel from "prettier/plugins/babel";
import * as estree from "prettier/plugins/estree";
import * as typescript from "prettier/plugins/typescript";
import * as yaml from "prettier/plugins/yaml";
import * as markdown from "prettier/plugins/markdown";
import java from "prettier-plugin-java";
import { format as formatSql } from "sql-formatter";

const options = { printWidth: 96, tabWidth: 2 };
const parsers = {
  html: ["html", [html]],
  css: ["css", [postcss]],
  javascript: ["babel", [babel, estree]],
  typescript: ["typescript", [typescript, estree]],
  json: ["json", [babel, estree]],
  yaml: ["yaml", [yaml]],
  markdown: ["markdown", [markdown]],
};
const aliases = { xml: "html", js: "javascript", jsx: "javascript", ts: "typescript", tsx: "typescript", yml: "yaml", md: "markdown" };

async function formatJava(source) {
  const javaOptions = { ...options, parser: "java", plugins: [java] };
  try {
    return await prettier.format(source, javaOptions);
  } catch {
    let suffix = 0;
    while (source.includes(`__HLOVET_FORMAT_${suffix}_`)) suffix++;
    const start = `// __HLOVET_FORMAT_${suffix}_START__`;
    const end = `// __HLOVET_FORMAT_${suffix}_END__`;
    const wrapped = `class __HlovetFormatWrapper__ {\nvoid formatSnippet() {\n${start}\n${source}\n${end}\n}\n}`;
    const formatted = await prettier.format(wrapped, javaOptions);
    const lines = formatted.replace(/\r\n/g, "\n").split("\n");
    const first = lines.findIndex((line) => line.trim() === start);
    const last = lines.findIndex((line, index) => index > first && line.trim() === end);
    if (first < 0 || last < 0) throw new Error("Invalid Java snippet");
    const snippet = lines.slice(first + 1, last);
    const indentation = Math.min(...snippet.filter((line) => line.trim()).map((line) => line.match(/^\s*/)[0].length));
    return snippet.map((line) => line.slice(Math.min(indentation, line.length))).join("\n").trim();
  }
}

async function format(source, language) {
  if (source.length > 200000) return { error: "too_large" };
  if (!source.trim()) return { error: "invalid_syntax" };
  const normalized = language.trim().toLowerCase();
  const name = aliases[normalized] || normalized;
  if (!(name in parsers) && name !== "sql" && name !== "java") return { error: "unsupported_language" };
  try {
    const code = name === "sql"
      ? formatSql(source, { keywordCase: "upper", language: "mysql", tabWidth: 2 })
      : name === "java"
        ? await formatJava(source)
        : await prettier.format(source, { ...options, parser: parsers[name][0], plugins: parsers[name][1] });
    return { code };
  } catch {
    return { error: "invalid_syntax" };
  }
}

globalThis.HlovetCodeFormatter = { format };
