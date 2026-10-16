/** 验证用户搜索和提及的 HTTP 参数边界，避免提及模式绕过类型、长度及会话编号校验。 */
import "reflect-metadata";
import { plainToInstance } from "class-transformer";
import { validateSync } from "class-validator";
import { SearchSocialUsersQueryDto } from "../src/social/dto/social.dto";

describe("SearchSocialUsersQueryDto", () => {
  // 省略关键词与空字符串代表同一提及意图，单字同样保留，不把它们判为非法参数。
  it("accepts omitted, empty and single-character mention keywords", () => {
    for (const input of [{ mention: "true" }, { mention: "true", q: "" }, { mention: "true", q: "a" }]) {
      const query = plainToInstance(SearchSocialUsersQueryDto, input);
      expect(validateSync(query)).toEqual([]);
      expect(typeof query.q).toBe("string");
      expect(query.mention).toBe(true);
    }
  });

  // 提及不能跳过关键词类型和长度限制，否则非文本值会在服务层 trim 时变成服务器错误。
  it("rejects non-string and oversized mention keywords", () => {
    for (const q of [null, 123, ["user"], "a".repeat(33)]) {
      const query = plainToInstance(SearchSocialUsersQueryDto, { mention: "true", q });
      expect(validateSync(query).some((error) => error.property === "q")).toBe(true);
    }
  });

  // 会话权限查询只接受正整数编号，不能让无效编号退化为全站候选搜索。
  it("rejects invalid conversation identifiers before user lookup", () => {
    for (const conversationId of ["0", "-1", "1.5", "invalid"]) {
      const query = plainToInstance(SearchSocialUsersQueryDto, { mention: "true", conversationId });
      expect(validateSync(query).some((error) => error.property === "conversationId")).toBe(true);
    }
  });
});
