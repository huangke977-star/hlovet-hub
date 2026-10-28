// 核对好友通话生命周期、参与者权限及真实头像路由契约，不将隔离业务测试视为双端媒体成功。
import { createHmac } from "node:crypto";
import { CallStatus, CallType, FriendshipStatus } from "../src/generated/prisma/client";
import { PrismaService } from "../src/prisma/prisma.service";
import { CallsService } from "../src/social/calls.service";

describe("CallsService", () => {
  afterEach(() => {
    delete process.env.TURN_HOST;
    delete process.env.TURN_REALM;
    delete process.env.TURN_SECRET;
    delete process.env.TURN_PORT;
    delete process.env.TURN_CREDENTIAL_TTL_SECONDS;
    delete process.env.STUN_URLS;
  });

  it("creates short-lived coturn REST credentials without exposing the shared secret", () => {
    process.env.TURN_HOST = "turn.example.com";
    process.env.TURN_REALM = "turn.example.com";
    process.env.TURN_SECRET = "test-turn-secret";
    process.env.TURN_CREDENTIAL_TTL_SECONDS = "600";
    const service = new CallsService({} as PrismaService);

    const result = service.getIceServers(7);
    const turn = result.iceServers[1];
    const username = turn.username ?? "";

    expect(turn.urls).toEqual([
      "turn:turn.example.com:3478?transport=udp",
      "turn:turn.example.com:3478?transport=tcp",
    ]);
    expect(username.endsWith(":7")).toBe(true);
    expect(turn.credential).toBe(createHmac("sha1", "test-turn-secret").update(username).digest("base64"));
    expect(JSON.stringify(result)).not.toContain("test-turn-secret");
  });

  it("starts a call only inside an accepted friendship conversation", async () => {
    const create = jest.fn(async (args: { data: Record<string, unknown> }) => ({
      id: 31,
      status: CallStatus.ringing,
      ...args.data,
    }));
    const prisma = {
      conversation: {
        findUnique: jest.fn(async () => ({
          id: 5,
          friendship: { userOneId: 7, userTwoId: 8, status: FriendshipStatus.accepted },
        })),
      },
      callSession: { findFirst: jest.fn(async () => null), create },
    };
    const service = new CallsService(prisma as unknown as PrismaService);

    await expect(service.startCall(7, 5, CallType.video)).resolves.toEqual(expect.objectContaining({
      id: 31,
      callerId: 7,
      calleeId: 8,
      conversationId: 5,
      type: CallType.video,
      status: CallStatus.ringing,
    }));
    expect(create).toHaveBeenCalledWith({
      data: { conversationId: 5, callerId: 7, calleeId: 8, type: CallType.video },
    });
  });

  // 双方各自看到对方头像，不能返回自己的头像或指向未实现的按用户编号下载路由。
  it("returns the counterpart avatar through the existing public avatar route", async () => {
    const now = new Date();
    const caller = { id: 7, nickname: "发起者", username: "caller", avatarStoredName: "caller.png", createdAt: now };
    const callee = { id: 8, nickname: "接听者", username: "callee", avatarStoredName: "callee.webp", createdAt: now };
    const prisma = {
      callSession: { findUnique: jest.fn(async () => ({ id: 31, callerId: 7, calleeId: 8, caller, callee, createdAt: now, updatedAt: now })) },
    };
    const service = new CallsService(prisma as unknown as PrismaService);

    // 同一通电话按查看者切换对方，不以返回名称参与身份判断。
    expect((await service.getCallResponse(31, 7)).user).toEqual(expect.objectContaining({ id: 8, avatarUrl: "/auth/avatars/callee.webp" }));
    expect((await service.getCallResponse(31, 8)).user).toEqual(expect.objectContaining({ id: 7, avatarUrl: "/auth/avatars/caller.png" }));
  });

  // 无头像用户保留 null，不能为了生成 URL 泄露用户编号或制造不可访问的下载地址。
  it("keeps an absent counterpart avatar null", async () => {
    const now = new Date();
    const prisma = {
      callSession: { findUnique: jest.fn(async () => ({ id: 31, callerId: 7, calleeId: 8, caller: {}, callee: { id: 8, avatarStoredName: null, createdAt: now }, createdAt: now, updatedAt: now })) },
    };
    const service = new CallsService(prisma as unknown as PrismaService);

    // 缺省头像由各客户端统一处理，接口不发明占位文件或新的头像端点。
    expect((await service.getCallResponse(31, 7)).user.avatarUrl).toBeNull();
  });

  // 统一头像字段后仍必须先执行通话存在性和参与者校验。
  it("rejects missing calls and viewers outside the call", async () => {
    const findUnique = jest.fn().mockResolvedValueOnce(null).mockResolvedValueOnce({ callerId: 7, calleeId: 8 });
    const service = new CallsService({ callSession: { findUnique } } as unknown as PrismaService);

    // 非参与者不会进入身份转换，缺少日期或头像也不应暴露内部转换异常。
    await expect(service.getCallResponse(31, 7)).rejects.toThrow("通话记录不存在");
    await expect(service.getCallResponse(31, 9)).rejects.toThrow("没有查看这通电话的权限");
  });

  it("persists a terminal call state and a compact conversation record", async () => {
    const acceptedAt = new Date(Date.now() - 65_000);
    const existing = {
      id: 31,
      conversationId: 5,
      callerId: 7,
      calleeId: 8,
      endedById: null,
      type: CallType.voice,
      status: CallStatus.active,
      acceptedAt,
      endedAt: null,
      durationSeconds: null,
      failureReason: null,
      createdAt: new Date(),
      updatedAt: new Date(),
      caller: {},
      callee: {},
    };
    const chatMessageCreate = jest.fn(async () => ({ id: 44 }));
    const transaction = {
      callSession: {
        findUnique: jest.fn(async () => existing),
        updateMany: jest.fn(async () => ({ count: 1 })),
        findUniqueOrThrow: jest.fn(async () => ({
          ...existing,
          status: CallStatus.completed,
          endedAt: new Date(),
          durationSeconds: 65,
        })),
      },
      chatMessage: { create: chatMessageCreate },
      conversation: { update: jest.fn(async () => ({ id: 5 })) },
      conversationParticipantState: { updateMany: jest.fn(async () => ({ count: 2 })) },
    };
    const prisma = {
      $transaction: jest.fn(async (callback: (client: typeof transaction) => Promise<unknown>) => callback(transaction)),
    };
    const service = new CallsService(prisma as unknown as PrismaService);

    const result = await service.finishCall(31, CallStatus.completed, 7);

    expect(result.messageId).toBe(44);
    expect(chatMessageCreate).toHaveBeenCalledWith(expect.objectContaining({
      data: expect.objectContaining({
        conversationId: 5,
        senderId: 7,
        callSessionId: 31,
        type: "system",
        body: expect.stringMatching(/^语音通话 · 01:0[45]$/),
      }),
    }));
  });
});
