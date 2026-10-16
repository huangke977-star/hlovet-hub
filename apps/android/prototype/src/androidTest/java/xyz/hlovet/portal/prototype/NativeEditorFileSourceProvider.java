package xyz.hlovet.portal.prototype;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** 以测试应用独立 UID 提供有限文件，只接受系统临时读取授权，不公开目录或写权限。 */
public final class NativeEditorFileSourceProvider extends ContentProvider {
    /** 初始化独立文件来源，返回可用状态，不访问业务账号或数据库。 */
    @Override public boolean onCreate() {
        // 内容仅由同包测试 Activity 生成，提供器不预取其它目录。
        return true;
    }

    /** 将 uri 映射到唯一测试文件，非法名称和不存在的文件均拒绝。 */
    private File fixture(Uri uri) throws FileNotFoundException {
        // 不允许路径片段穿越或读取测试包的其它私有内容。
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.matches("native_system_[a-z0-9_-]+\\.(png|txt)")) {
            throw new FileNotFoundException("Unexpected fixture path");
        }
        File file = new File(new File(getContext().getFilesDir(), "editor-input-fixtures"), name);
        if (!file.isFile()) throw new FileNotFoundException("Fixture not prepared");
        return file;
    }

    /** 返回 uri 的原始 MIME，类型由夹具扩展名决定，不信任调用者额外参数。 */
    @Override public String getType(Uri uri) {
        // 系统用 MIME 决定富内容交付方式，不将图片伪装成文本地址。
        return uri.getPath().endsWith(".png") ? "image/png" : "text/plain";
    }

    /** 按 projection 返回 uri 的文件名和长度，缺失夹具返回空结果。 */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // 仅实现系统文件读取需要的元数据，不提供全目录枚举。
        String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        try {
            File file = fixture(uri);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row);
        } catch (FileNotFoundException ignored) {
            // 尚未准备的夹具不可被当成成功读到的空文件。
        }
        return result;
    }

    /** 只读打开 uri，mode 不是 r 时明确拒绝，权限本身仍由 Android 框架校验。 */
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // 即使错误地获得写 grant，测试来源也不能覆盖或删除文件。
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 拒绝通过 uri 和 values 创建文件，测试夹具只能由来源 Activity 写入。 */
    @Override public Uri insert(Uri uri, ContentValues values) {
        // 不为接收应用开放持久化接口。
        throw new UnsupportedOperationException("Read-only fixture");
    }

    /** 拒绝根据 uri 和筛选条件删除测试文件，保留验收证据。 */
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        // 禁止接收应用清理来源应用的数据。
        throw new UnsupportedOperationException("Read-only fixture");
    }

    /** 拒绝更新 uri 对应内容，不接受 values 或筛选条件造成的写入。 */
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // 不将临时读取授权升级为编辑来源文件的能力。
        throw new UnsupportedOperationException("Read-only fixture");
    }
}
