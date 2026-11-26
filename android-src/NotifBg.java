package id.dutakasir.tiket;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Jembatan web -> layanan latar belakang (BgService). */
@CapacitorPlugin(name = "NotifBg")
public class NotifBg extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        String api = call.getString("api"), key = call.getString("key"), agent = call.getString("agent", "");
        if (api == null || !api.startsWith("https://") || key == null || key.isEmpty()) { call.reject("data tidak lengkap"); return; }
        Context ctx = getContext();
        SharedPreferences p = ctx.getSharedPreferences(BgService.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (!key.equals(p.getString("key", ""))) e.remove("seen"); // akun lain: catat ulang kondisi awal
        e.putBoolean("on", true).putString("api", api).putString("key", key).putString("agent", agent).apply();
        BgService.fg = true;
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
        }
        try { ContextCompat.startForegroundService(ctx, new Intent(ctx, BgService.class)); } catch (Exception ex) { call.reject("layanan gagal dijalankan"); return; }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context ctx = getContext();
        ctx.getSharedPreferences(BgService.PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", false).remove("seen").apply();
        ctx.stopService(new Intent(ctx, BgService.class));
        call.resolve();
    }

    /** Minta Android tidak "menidurkan" aplikasi ini. Mengembalikan {ignored:true} bila sudah dikecualikan. */
    @PluginMethod
    public void battery(PluginCall call) {
        Context ctx = getContext();
        JSObject r = new JSObject();
        if (Build.VERSION.SDK_INT < 23) { r.put("ignored", true); call.resolve(r); return; }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean ok = pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        if (!ok) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ex) { /* HP tidak mendukung */ }
        }
        r.put("ignored", ok);
        call.resolve(r);
    }

    @Override
    protected void handleOnResume() {
        BgService.fg = true;
        try {
            NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications()) if (n.getId() >= 100) nm.cancel(n.getId());
        } catch (Exception e) { /* diabaikan */ }
    }

    @Override
    protected void handleOnPause() {
        BgService.fg = false;
        BgService.needBase = true;
        BgService.kick();
    }
}
