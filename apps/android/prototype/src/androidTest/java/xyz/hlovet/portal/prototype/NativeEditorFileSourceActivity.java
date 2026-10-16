package xyz.hlovet.portal.prototype;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** 在独立测试 UID 中创建小型文件，实际通过系统剪贴板或全局拖放交付，不伪造 DOM 事件。 */
public final class NativeEditorFileSourceActivity extends Activity {
    /** 读取 state 对应请求并建立文件来源；只向指定测试接收包回报元数据，不传递 URI grant。 */
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 来源数据仅限随机命名的夹具，不读取业务令牌或用户文件。
        Intent request = getIntent();
        String mode = request.getStringExtra("mode");
        String name = request.getStringExtra("name");
        if ("close".equals(mode)) { finish(); return; }
        if (name == null || !name.matches("native_system_[a-z0-9_-]+\\.(png|txt)")) { finish(); return; }
        try {
            File directory = new File(getFilesDir(), "editor-input-fixtures");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Fixture directory unavailable");
            File file = new File(directory, name);
            // 图片有确定的绿色像素；文本有确定正文，供接收端核对实际字节。
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (name.endsWith(".png")) {
                    Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.rgb(31, 170, 96));
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    bitmap.recycle();
                } else output.write(("Actual cross-app system file " + name + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Uri uri = Uri.parse("content://xyz.hlovet.portal.prototype.test.editor-files/" + name);
            ClipData clip = ClipData.newUri(getContentResolver(), "Editor system fixture", uri);
            if ("clipboard".equals(mode)) {
                // 用户真实点击后才写入剪贴板，确保来源有前台焦点，不依赖 onCreate 的窗口时序。
                TextView source = new TextView(this);
                source.setText("复制测试文件");
                source.setTextColor(Color.BLACK);
                source.setBackgroundColor(Color.WHITE);
                source.setGravity(Gravity.CENTER);
                source.setOnClickListener(view -> {
                    // URI 权限由系统在目标应用粘贴时交付，点击返回不额外授予权限。
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(clip);
                    finish();
                });
                setContentView(source);
                reply(request, uri, file.length());
            } else if ("drag".equals(mode)) {
                // 小窗口保留目标应用可见区域，长按后使用真正的跨窗口系统拖放。
                TextView source = new TextView(this);
                source.setText("拖放测试文件");
                source.setTextSize(16);
                source.setTextColor(Color.BLACK);
                source.setGravity(Gravity.CENTER);
                source.setBackgroundColor(Color.WHITE);
                source.setOnLongClickListener(view -> {
                    // 系统拖动开始后让来源小窗退出命中区域，避免透明 Activity 任务拦截下方真实目标。
                    boolean started = view.startDragAndDrop(clip, new View.DragShadowBuilder(view), null, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
                    if (started) getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                    return started;
                });
                source.setOnDragListener((view, event) -> {
                    // 真实文件管理器通常保持分屏；消费完成前不退出，避免尺寸重建取消目标上传。
                    if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                        source.setText(event.getResult() ? "拖放测试文件已交付" : "拖放测试文件未接收");
                    }
                    return true;
                });
                setContentView(source);
                reply(request, uri, file.length());
            } else finish();
        } catch (Exception exception) {
            // 夹具失败必须被测试观察到，不带着未授权或空文件继续执行。
            Intent failure = new Intent(request.getStringExtra("replyAction")).setPackage(request.getStringExtra("replyPackage"));
            sendBroadcast(failure.putExtra("error", exception.getClass().getSimpleName()));
            finish();
        }
    }

    /** 在窗口可见时限定拖放来源高度，保持下方真实目标编辑器可以接收全局拖动。 */
    @Override public void onResume() {
        super.onResume();
        // 不开启系统悬浮窗权限，仅调整本测试 Activity 自身窗口。
        if ("drag".equals(getIntent().getStringExtra("mode")) || "clipboard".equals(getIntent().getStringExtra("mode"))) {
            getWindow().setGravity(Gravity.TOP);
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, (int) (72 * getResources().getDisplayMetrics().density));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    /** 向 request 指定测试接收端回报 uri、size 和独立进程身份，不额外授予读取权限。 */
    private void reply(Intent request, Uri uri, long size) {
        // URI 只是可验证的标识，只有系统剪贴板或拖放才能赋予接收应用读取能力。
        sendBroadcast(new Intent(request.getStringExtra("replyAction")).setPackage(request.getStringExtra("replyPackage"))
            .putExtra("uri", uri.toString()).putExtra("size", size).putExtra("uid", Process.myUid()));
    }
}
