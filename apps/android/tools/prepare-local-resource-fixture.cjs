// 本地 Android 资源专项夹具：仅经真实 API 准备失败/退款状态，保留全部测试记录。
const assert = require('node:assert/strict');

const base = 'http://127.0.0.1:3001';
let stage = 'validate-local-fixture';

/** 向固定回环 API 发送 path、token、method 和 body，返回状态及 JSON，不输出认证值或正文。 */
async function request(path, token, method = 'GET', body) {
  // 禁止外部来源和协议相对路径；令牌只在当前进程内传递。
  assert(path.startsWith('/') && !path.startsWith('//'));
  const response = await fetch(base + path, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(15000),
  });
  // 附件拒绝响应只保留状态，不能将原文件或服务端错误正文写入报告。
  const json = await response.json().catch(() => null);
  return { status: response.status, json };
}

/** 按 buyerId、articleId 和 mode 精确准备夹具；缺少显式本地授权时，在加载数据库模块前停止。 */
async function main() {
  // 操作者必须明确选中本地 Docker 环境，且不能使用省略账号的历史默认值。
  const [buyerArgument, articleArgument, mode] = process.argv.slice(2);
  const buyerId = Number(buyerArgument);
  const articleId = Number(articleArgument);
  assert.equal(process.env.LOCAL_ANDROID_RESOURCE_FIXTURE, '1');
  assert(Number.isSafeInteger(buyerId) && buyerId > 0);
  assert(Number.isSafeInteger(articleId) && articleId > 0);
  assert(['prepare-retry', 'prepare-refund', 'verify-refunded'].includes(mode));
  assert.equal(new URL(process.env.DATABASE_URL).hostname, 'mysql');

  // 依赖只从已运行的本地 API 容器加载，不向主机安装依赖或写入数据库。
  const { PrismaService } = require('/app/apps/api/dist/src/prisma/prisma.service.js');
  const { JwtService } = require('/app/apps/api/node_modules/@nestjs/jwt');
  const prisma = new PrismaService();
  try {
    // 固定测试管理员名称并校验买方、独立作者和文章前缀，避免触及真实用户资源。
    const select = { id: true, username: true, authVersion: true, status: true, isSuperAdmin: true };
    const buyer = await prisma.user.findUnique({ where: { id: buyerId }, select });
    const admin = await prisma.user.findUnique({ where: { username: 'android_parity_admin' }, select });
    assert(buyer?.username.startsWith('native_restore_') && !buyer.isSuperAdmin && buyer.status === 'active');
    assert(admin?.isSuperAdmin && admin.status === 'active');
    const source = await prisma.articleResourceExchange.findFirst({
      where: { articleId, buyerId, pointCost: 1 },
      include: { article: { include: { author: { select: { username: true } }, attachments: true } } },
    });
    assert(source?.article.title.startsWith('native_resource_20261015_'));
    assert(source.article.author.username.startsWith('native_resource_'));
    assert.notEqual(source.authorId, buyerId);
    const jwt = new JwtService();
    const tokens = [];
    for (const user of [buyer, admin]) {
      tokens.push(await jwt.signAsync({ sub: user.id, username: user.username, av: user.authVersion }, {
        secret: process.env.JWT_ACCESS_SECRET, expiresIn: '5m',
      }));
    }
    const [buyerToken, adminToken] = tokens;
    const report = { apiOrigin: base, buyerId, articleId, mode, testDataRemoved: false, interactiveLoginTested: false };

    // 重试夹具只选当前文章未用于正常兑换的第二个两积分区块，首次兑换和标记失败均走正式接口。
    if (mode === 'prepare-retry') {
      stage = 'prepare-second-block-failure';
      const article = await request(`/articles/visible/${source.article.slug}`, buyerToken);
      assert.equal(article.status, 200);
      const block = article.json.resource.blocks.find(item => item.key !== source.blockKey && item.pointCost === 2);
      assert(block);
      let exchange = await prisma.articleResourceExchange.findFirst({ where: { articleId, buyerId, blockKey: block.key } });
      const needsRedemption = !exchange;
      const pointsBefore = (await request('/reputation/me', buyerToken)).json.points;
      if (needsRedemption) {
        assert.equal((await request(`/articles/${articleId}/redeem`, buyerToken, 'POST', { blockKey: block.key })).status, 201);
        exchange = await prisma.articleResourceExchange.findFirst({ where: { articleId, buyerId, blockKey: block.key } });
      }
      assert(exchange && exchange.deliveryStatus !== 'refunded');
      const pointsAfterRedemption = (await request('/reputation/me', buyerToken)).json.points;
      assert.equal(pointsAfterRedemption, pointsBefore - (needsRedemption ? 2 : 0));
      assert.equal((await request(`/resources/admin/deliveries/${exchange.id}/fail`, buyerToken, 'POST', { error: 'Unauthorized local probe' })).status, 403);
      if (exchange.deliveryStatus !== 'failed') {
        assert.equal((await request(`/resources/admin/deliveries/${exchange.id}/fail`, adminToken, 'POST', { error: 'Retained Android native retry verification' })).status, 201);
      }
      const locked = await request(`/articles/visible/${source.article.slug}`, buyerToken);
      const segment = locked.json.contentSegments.find(item => item.type === 'resource' && item.key === block.key);
      assert.equal(segment.unlocked, false);
      assert(!segment.content);
      assert.equal((await request(`/resources/deliveries/${exchange.id}/download`, buyerToken, 'POST')).status, 400);
      assert.equal((await request('/reputation/me', buyerToken)).json.points, pointsAfterRedemption);
      Object.assign(report, { exchangeId: exchange.id, pointsBefore, pointsAfter: pointsAfterRedemption, status: 'failed', failedBodyLocked: true });
    } else {
      // 退款只作用于指定测试文章第一块；重复退款必须返回幂等结果，不重复返还积分。
      stage = 'refund-first-block-and-check-permissions';
      const before = (await request('/reputation/me', buyerToken)).json.points;
      if (mode === 'prepare-refund') {
        assert.equal((await request(`/resources/admin/deliveries/${source.id}/refund`, buyerToken, 'POST')).status, 403);
        assert.equal((await request(`/resources/admin/deliveries/${source.id}/refund`, adminToken, 'POST')).status, 201);
      } else {
        assert.equal(source.deliveryStatus, 'refunded');
      }
      const after = (await request('/reputation/me', buyerToken)).json.points;
      assert.equal(after, before + (mode === 'prepare-refund' && source.deliveryStatus !== 'refunded' ? 1 : 0));
      const duplicate = await request(`/resources/admin/deliveries/${source.id}/refund`, adminToken, 'POST');
      assert.equal(duplicate.status, 201);
      assert.equal(duplicate.json.idempotent, true);
      assert.equal((await request('/reputation/me', buyerToken)).json.points, after);

      // 正文、原文件、缩略图和下载登记分别核验，不能只凭订单状态推定资源权限已撤销。
      const article = await request(`/articles/visible/${source.article.slug}`, buyerToken);
      assert.equal(article.status, 200);
      const block = article.json.contentSegments.find(item => item.type === 'resource' && item.key === source.blockKey);
      assert.equal(block.unlocked, false);
      assert(!block.content);
      const file = source.article.attachments.find(item => item.kind === 'file');
      const image = source.article.attachments.find(item => item.kind === 'image');
      assert(file && image);
      const originalHttp = (await request(`/articles/attachments/${file.id}/download`, buyerToken)).status;
      const thumbnailHttp = (await request(`/articles/attachments/${image.id}/thumbnail`, buyerToken)).status;
      const downloadHttp = (await request(`/resources/deliveries/${source.id}/download`, buyerToken, 'POST')).status;
      const redeemHttp = (await request(`/articles/${articleId}/redeem`, buyerToken, 'POST', { blockKey: source.blockKey })).status;
      assert.deepEqual([originalHttp, thumbnailHttp, downloadHttp, redeemHttp], [403, 403, 400, 400]);
      const refunded = await prisma.articleResourceExchange.findUnique({ where: { id: source.id } });
      assert.equal(refunded.deliveryStatus, 'refunded');
      assert.equal((await request('/reputation/me', buyerToken)).json.points, after);
      Object.assign(report, { exchangeId: source.id, pointsBefore: before, pointsAfter: after, status: refunded.deliveryStatus, refundIdempotent: true, originalHttp, thumbnailHttp, downloadHttp, redeemHttp });
    }
    // 报告仅包含定位夹具所需的业务编号和断言结果，不包含令牌、凭据或文章正文。
    console.log(JSON.stringify(report, null, 2));
  } finally {
    // 只关闭本进程数据库连接，不删除夹具、会话或审计记录。
    await prisma.$disconnect();
  }
}

// 失败只输出阶段和断言类型，避免依赖库异常回显数据库连接串或认证内容。
main().catch(error => {
  console.error(`Local Android resource fixture failed at ${stage}; type=${error.name}; credentials omitted.`);
  process.exitCode = 1;
});
