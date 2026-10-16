import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const bundle = new URL("../prototype/src/main/assets/hlovet-code-format.min.js", import.meta.url);
const context = { console };
vm.createContext(context);
vm.runInContext(fs.readFileSync(bundle, "utf8"), context, { filename: bundle.pathname });

const format = context.HlovetCodeFormatter?.format;
assert.equal(typeof format, "function", "formatter bundle must expose HlovetCodeFormatter.format");

const supportedCases = [
  ["json", '{"enabled":true,"items":[1,2]}', '"enabled": true'],
  ["javascript", "const value={enabled:true,items:[1,2]};", "const value ="],
  ["typescript", "type Item={id:number};const item:Item={id:1};", "type Item"],
  ["html", "<main><p>Hello</p></main>", "<main>"],
  ["xml", "<root><item id=\"1\" /></root>", "<root>"],
  ["css", "body{color:red;margin:0}", "body"],
  ["sql", "select * from users where id=1 and active=1;", "SELECT"],
  ["java", "class Demo{void run(){System.out.println(1);}}", "class Demo"],
  ["yaml", "name: demo\nitems:\n  - one", "name: demo"],
  ["markdown", "# Title\n\n- one\n- two", "# Title"],
];

for (const [language, source, expected] of supportedCases) {
  const result = await format(source, language);
  assert.ok(result.code, `${language} should return formatted code`);
  assert.match(result.code, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
}

const assertFailure = async (source, language, error) => {
  const result = await format(source, language);
  assert.equal(result.error, error, `${language} should return ${error}`);
};

await assertFailure("{invalid", "json", "invalid_syntax");
await assertFailure("print('hello')", "python", "unsupported_language");
await assertFailure("", "json", "invalid_syntax");
await assertFailure("x".repeat(200_001), "json", "too_large");

console.log(`offline formatter contract passed: ${supportedCases.length} languages + 4 failure cases`);
