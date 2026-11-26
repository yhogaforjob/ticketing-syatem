package id.dutakasir.tiket;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Mengunduh APK pembaruan dari rilis GitHub milik repo ini, lalu membuka layar pasang Android. */
@CapacitorPlugin(name = "ApkInstaller")
public class ApkInstaller extends Plugin {
    private static final String ALLOWED = "https://github.com/yhogaforjob/ticketing-syatem/releases/download/";
    private volatile boolean busy = false;

    @PluginMethod
    public void install(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || !url.startsWith(ALLOWED)) {
            call.reject("url tidak diizinkan");
            return;
        }
        final Context ctx = getContext();
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            JSObject r = new JSObject();
            r.put("needPermission", true);
            call.resolve(r);
            return;
        }
        if (busy) {
            call.reject("sedang mengunduh");
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(ctx.getCacheDir(), "update");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("folder cache gagal dibuat");
                    File out = new File(dir, "tiket-dutakasir.apk");
                    if (out.exists()) out.delete();

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    if (c.getResponseCode() != 200) throw new Exception("server membalas " + c.getResponseCode());
                    long total = c.getContentLengthLong();
                    long got = 0;
                    int lastPct = -1;
                    InputStream in = c.getInputStream();
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                JSObject p = new JSObject();
                                p.put("pct", pct);
                                notifyListeners("progress", p);
                            }
                        }
                    }
                    os.close();
                    in.close();
                    c.disconnect();

                    if (out.length() < 1024 * 1024) throw new Exception("file terlalu kecil");
                    byte[] head = new byte[2];
                    FileInputStream fi = new FileInputStream(out);
                    int k = fi.read(head);
                    fi.close();
                    if (k != 2 || head[0] != 'P' || head[1] != 'K') throw new Exception("bukan file APK");

                    Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", out);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);

                    JSObject r = new JSObject();
                    r.put("opened", true);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(e.getMessage() == null ? "gagal" : e.getMessage());
                } finally {
                    busy = false;
                }
            }
        }).start();
    }
}
