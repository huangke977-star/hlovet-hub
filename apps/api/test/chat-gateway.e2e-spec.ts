// 核对聊天网关的有效会话归属与错误回执，不将数据库诊断暴露给终端用户。
import { BadRequestException } from "@nestjs/common";
import { JwtService } from "@nestjs/jwt";
import type { Namespace, Socket } from "socket.io";
import { RedisService } from "../src/redis/redis.service";
import { UsersService } from "../src/users/users.service";
import { CallsService } from "../src/social/calls.service";
import { ChatGateway } from "../src/social/chat.gateway";
import { SocialService } from "../src/social/social.service";
import { PushService } from "../src/push/push.service";
import { CallStatus, CallType, Prisma } from "../src/generated/prisma/client";

describe("ChatGateway session validation", () => {
  // 只补发新连接错过的被叫响铃邀请，不能重放主叫、已接听或已结束的通话。
  it("replays only a still-ringing incoming call to its authenticated callee", async () => {
    // 使用同一运行态对象模拟真实网关索引，返回体仍来自现有的参与者权限查询。
    const getCallResponse = jest.fn().mockResolvedValue({ id: 91, status: "ringing" });
    const gateway = new ChatGateway({} as JwtService, {} as UsersService, {} as SocialService,
      { getCallResponse } as unknown as CallsService, {} as RedisService, {} as PushService);
    const call = { id: 91, conversationId: 74, callerId: 1, calleeId: 2, type: CallType.voice, status: CallStatus.ringing as CallStatus };
    const state = gateway as unknown as { runtimeCalls: Map<number, typeof call>; activeCallByUser: Map<number, number>;
      socketsByUser: Map<number, Set<string>>; deliverPendingIncomingCall(client: Socket, userId: number): Promise<void> };
    state.runtimeCalls.set(91, call);
    state.activeCallByUser.set(1, 91);
    state.activeCallByUser.set(2, 91);
    state.socketsByUser.set(2, new Set(["new-callee"]));
    const client = { id: "new-callee", connected: true, emit: jest.fn() } as unknown as Socket;
    await state.deliverPendingIncomingCall(client, 2);
    expect(client.emit).toHaveBeenCalledWith("call:incoming", { id: 91, status: "ringing" });

    // 同一编号但非被叫或已经接听时不查询/发送，避免另一台设备重新弹出来电面板。
    jest.mocked(client.emit).mockClear();
    getCallResponse.mockClear();
    await state.deliverPendingIncomingCall(client, 1);
    call.status = CallStatus.accepted;
    await state.deliverPendingIncomingCall(client, 2);
    expect(getCallResponse).not.toHaveBeenCalled();
    expect(client.emit).not.toHaveBeenCalled();

    // 数据库先完成接听、运行态还未更新的短暂窗口也不能把 accepted 回读包装成新邀请。
    call.status = CallStatus.ringing;
    getCallResponse.mockResolvedValue({ id: 91, status: "accepted" });
    await state.deliverPendingIncomingCall(client, 2);
    expect(client.emit).not.toHaveBeenCalled();
  });

  // 邀请查询挂起期间可能被对方取消，新连接不能在取消后收到迟到响铃。
  it("does not replay an invitation ended while its response is being read", async () => {
    // 在实际异步查询点移除运行态，检验补发前的第二次身份和阶段检查。
    const call = { id: 92, conversationId: 74, callerId: 1, calleeId: 2, type: CallType.voice, status: CallStatus.ringing };
    const getCallResponse = jest.fn();
    const gateway = new ChatGateway({} as JwtService, {} as UsersService, {} as SocialService,
      { getCallResponse } as unknown as CallsService, {} as RedisService, {} as PushService);
    const state = gateway as unknown as { runtimeCalls: Map<number, typeof call>; activeCallByUser: Map<number, number>;
      socketsByUser: Map<number, Set<string>>; deliverPendingIncomingCall(client: Socket, userId: number): Promise<void> };
    state.runtimeCalls.set(92, call);
    state.activeCallByUser.set(2, 92);
    state.socketsByUser.set(2, new Set(["new-callee"]));
    getCallResponse.mockImplementation(async () => { state.runtimeCalls.delete(92); return { id: 92 }; });
    const client = { id: "new-callee", connected: true, emit: jest.fn() } as unknown as Socket;
    await state.deliverPendingIncomingCall(client, 2);
    expect(client.emit).not.toHaveBeenCalled();
  });

  // 实际发送入口遇到已回滚数据库异常时只返回默认提示；合法业务错误仍准确显示。
  it("hides database details while preserving business validation in send acknowledgements", async () => {
    // 夹具保留有效当前用户，使请求确实执行到消息业务层而非提前被认证拒绝。
    const createMessage = jest.fn()
      .mockRejectedValueOnce(new Prisma.PrismaClientKnownRequestError("private database detail", { code: "P2034", clientVersion: "test" }))
      .mockRejectedValueOnce(new BadRequestException("消息文字和附件不能同时为空。"));
    const gateway = new ChatGateway(
      {} as JwtService, {} as UsersService, { createMessage } as unknown as SocialService,
      {} as CallsService, {} as RedisService, {} as PushService,
    );
    const client = { data: { userId: 7 } } as unknown as Socket;
    // 不吞掉错误或假装已发送，两种失败均以 ok=false 回执返回。
    await expect(gateway.sendMessage(client, { conversationId: 5, body: "hello" })).resolves.toEqual({ ok: false, error: "消息发送失败。" });
    await expect(gateway.sendMessage(client, { conversationId: 5, body: "" })).resolves.toEqual({ ok: false, error: "消息文字和附件不能同时为空。" });
  });

  it("resolves connected sockets from the configured namespace", async () => {
    const redis = {
      get: jest.fn(async () => JSON.stringify({ userId: 7 })),
    };
    const gateway = new ChatGateway(
      {} as JwtService,
      {} as UsersService,
      {} as SocialService,
      {} as CallsService,
      redis as unknown as RedisService,
      {} as PushService,
    );
    const socket = {
      data: { userId: 7, sessionId: "session-1" },
      emit: jest.fn(),
      disconnect: jest.fn(),
    } as unknown as Socket;
    const internals = gateway as unknown as {
      server: Namespace;
      socketsByUser: Map<number, Set<string>>;
      disconnectRevokedSessions(): Promise<void>;
    };
    internals.server = {
      sockets: new Map([["socket-1", socket]]),
    } as unknown as Namespace;
    internals.socketsByUser.set(7, new Set(["socket-1"]));

    await internals.disconnectRevokedSessions();

    expect(redis.get).toHaveBeenCalledWith("refresh_token:session-1");
    expect(socket.disconnect).not.toHaveBeenCalled();
  });
});
