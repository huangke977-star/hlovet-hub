package xyz.hlovet.portal.prototype;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.OpenableColumns;
import java.io.InputStream;
import java.security.MessageDigest;

/** 仅在测试 APK 的独立 UID 中读取附件 URI，使用系统 Java 类避免依赖目标 APK 的 Kotlin 运行库。 */
public final class NativeAttachmentReceiverActivity extends Activity {
    /** 处理 state 对应首次启动请求，回报原名称、摘要及权限后关闭，不读取用户会话。 */
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // 回报限定为测试传入的随机 action，实际内容来源必须是目标应用 FileProvider。
        Intent request = getIntent();
        String action = request.getStringExtra("replyAction");
        String replyPackage = request.getStringExtra("replyPackage");
        if (action == null || replyPackage == null) {
            finish();
            return;
        }
        Intent result = new Intent(action).setPackage(replyPackage).putExtra("uid", Process.myUid());
        try {
            Uri uri = request.getData();
            if (uri == null || !"xyz.hlovet.portal.prototype.fileprovider".equals(uri.getAuthority())) {
                throw new IllegalArgumentException("Unexpected fixture URI");
            }
            // 跨 UID 查询也必须获得读取 grant，不能仅验证 Intent 的标志位。
            try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Missing fixture metadata");
                result.putExtra("name", cursor.getString(0));
            }
            // 流式读取限定的小型夹具并计算摘要，广播不包含附件原文或令牌。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("Missing fixture stream");
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 1024 * 1024) throw new IllegalStateException("Fixture too large");
                    digest.update(buffer, 0, count);
                }
            }
            result.putExtra("digest", digest.digest());
            result.putExtra("writeGranted", checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED);
        } catch (Exception exception) {
            // 无授权时由系统抛出拒绝异常，只记录类型以核对权限边界。
            result.putExtra("error", exception.getClass().getSimpleName());
        }
        // 完成读取后回报并退出，避免授权 Activity 停留影响后续界面测试。
        sendBroadcast(result);
        finish();
    }
}
