// 核对聊天认证定时器的提前提示、续期替换与断线清理，不代替真实 JWT 自然到期验收。
import { JwtService } from "@nestjs/jwt";
import type { Socket } from "socket.io";
import { RedisService } from "../src/redis/redis.service";
import { UsersService } from "../src/users/users.service";
import { CallsService } from "../src/social/calls.service";
import { ChatGateway } from "../src/social/chat.gateway";
import { SocialService } from "../src/social/social.service";
import { PushService } from "../src/push/push.service";

/** 仅暴露现有认证调度入口供隔离定时器断言，不修改产品状态或缩短真实服务令牌寿命。 */
type ExpiryGateway = { scheduleReauthentication(client: Socket, expiresAtSeconds: number): void };

// 固定时间只作用于本 Jest 进程，真实模拟器和本地 API 继续使用各自系统时钟。
describe("ChatGateway authentication expiry timers", () => {
  let gateway: ChatGateway;
  let expiry: ExpiryGateway;
  let client: Socket;
  let jwt: { verifyAsync: jest.Mock };

  // 为每项建立独立有效账号与会话，令续期请求实际走过归属、会话及账号有效性校验。
  beforeEach(() => {
    jest.useFakeTimers().setSystemTime(new Date("2026-10-27T00:00:00.000Z"));
    jwt = { verifyAsync: jest.fn() };
    const users = { findActiveById: jest.fn().mockResolvedValue({ id: 7 }) };
    const redis = { get: jest.fn().mockResolvedValue(JSON.stringify({ userId: 7 })), del: jest.fn().mockResolvedValue(1) };
    gateway = new ChatGateway(jwt as unknown as JwtService, users as unknown as UsersService,
      {} as SocialService, {} as CallsService, redis as unknown as RedisService, {} as PushService);
    expiry = gateway as unknown as ExpiryGateway;
    client = { id: "expiry-socket", connected: true, data: { userId: 7, sessionId: "original-session" },
      emit: jest.fn(), disconnect: jest.fn() } as unknown as Socket;
  });

  // 每项结束恢复本进程时钟并移除夹具计时器，避免后续用例继承虚拟时间或遗留任务。
  afterEach(() => {
    jest.clearAllTimers();
    jest.useRealTimers();
  });

  // 原令牌有效期十五分钟：提前三十秒提示，只有宽限期真实走完才主动关闭。
  it("prompts thirty seconds before expiration and closes after the full grace period", () => {
    expiry.scheduleReauthentication(client, Math.floor(Date.now() / 1000) + 900);
    jest.advanceTimersByTime(869_999);
    expect(client.emit).not.toHaveBeenCalled();
    expect(client.disconnect).not.toHaveBeenCalled();

    // 提示本身不是认证失败，宽限期内仍保持连接供正常宿主完成刷新。
    jest.advanceTimersByTime(1);
    expect(client.emit).toHaveBeenCalledTimes(1);
    expect(client.emit).toHaveBeenCalledWith("chat:reauthenticate");
    jest.advanceTimersByTime(29_999);
    expect(client.disconnect).not.toHaveBeenCalled();
    jest.advanceTimersByTime(1);
    expect(client.disconnect).toHaveBeenCalledTimes(1);
    expect(client.disconnect).toHaveBeenCalledWith(true);
  });

  // 续期必须经过公开认证处理函数，不能直接清空 timer 来伪造不会误断开的结果。
  it("cancels the old grace timer after successful reauthentication on the same socket", async () => {
    expiry.scheduleReauthentication(client, Math.floor(Date.now() / 1000) + 900);
    jest.advanceTimersByTime(870_000);
    jwt.verifyAsync.mockResolvedValue({ sub: 7, sid: "renewed-session", exp: Math.floor(Date.now() / 1000) + 900 });
    await expect(gateway.reauthenticate(client, { token: "fake-renewed-token" })).resolves.toEqual({ ok: true });
    expect(client.data.sessionId).toBe("renewed-session");

    // 跨过旧令牌到期点后不得断开，新到期提示仍按新令牌自身时间调度。
    jest.advanceTimersByTime(30_000);
    expect(client.disconnect).not.toHaveBeenCalled();
    expect(client.emit).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(840_000);
    expect(client.emit).toHaveBeenCalledTimes(2);
    expect(client.disconnect).not.toHaveBeenCalled();
  });

  // 分别覆盖提示尚未触发与已经进入宽限期；真实断线入口必须清除两类遗留计时器。
  it.each([0, 870_000])("clears authentication timers when the socket disconnects at %i ms", async (elapsed) => {
    expiry.scheduleReauthentication(client, Math.floor(Date.now() / 1000) + 900);
    jest.advanceTimersByTime(elapsed);
    const promptsBeforeDisconnect = jest.mocked(client.emit).mock.calls.length;
    await gateway.handleDisconnect(client);

    // 已退出连接不再收到迟到认证提示，也不能因旧宽限期重复触发强制断开。
    expect(jest.getTimerCount()).toBe(0);
    jest.advanceTimersByTime(900_000);
    expect(client.emit).toHaveBeenCalledTimes(promptsBeforeDisconnect);
    expect(client.disconnect).not.toHaveBeenCalled();
  });
});
