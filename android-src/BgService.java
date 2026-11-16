package id.nuvera.tiket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;

/**
 * Layanan latar belakang: tetap hidup walau aplikasi ditutup, mengecek tiket/chat baru
 * dan menampilkan notifikasi. Web (WebView) tidak bisa polling saat aplikasi di latar belakang.
 */
public class BgService extends Service {
    static final String PREFS = "bg";
    static final String CH_RUN = "bg_run", CH_MSG = "bg_msg";
    static final long EVERY = 10000;          // jeda antar pengecekan
    static volatile boolean fg = false;        // aplikasi sedang terbuka di layar
    static volatile boolean needBase = true;   // pengecekan berikut hanya mencatat kondisi (tanpa notifikasi)
    private static final Object LOCK = new Object();

    private volatile boolean run = false;
    private Thread worker;
    private PowerManager.WakeLock wl;

    /** Bangunkan pengecekan sekarang juga (dipakai saat aplikasi masuk latar belakang). */
    static void kick() { synchronized (LOCK) { LOCK.notifyAll(); } }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean("on", false) || p.getString("key", "").isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        channels();
        Notification n = base().setContentTitle("Tiket Nuvera aktif")
                .setContentText("Memantau tiket dan chat baru")
                .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, 0x40000000 /* specialUse */);
            else startForeground(1, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tiket:bg");
            wl.setReferenceCounted(false);
        }
        if (!wl.isHeld()) wl.acquire();
        if (worker == null || !worker.isAlive()) {
            run = true;
            worker = new Thread(new Runnable() { @Override public void run() { loop(); } }, "bg-poll");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        run = false; kick();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    private void loop() {
        int fail = 0;
        while (run) {
            long wait = EVERY;
            try { poll(); fail = 0; }
            catch (Exception e) { fail++; wait = Math.min(60000, EVERY * (1L << Math.min(fail, 3))); }
            if (!run) break;
            synchronized (LOCK) { try { LOCK.wait(wait); } catch (InterruptedException ie) { /* lanjut */ } }
        }
    }

    private void poll() throws Exception {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String api = p.getString("api", ""), key = p.getString("key", ""), agent = p.getString("agent", "");
        if (api.isEmpty() || key.isEmpty()) { stopSelf(); return; }
        if (fg) return; // aplikasi terbuka: WebView yang mengurus, jangan dobel
        JSONObject body = new JSONObject();
        body.put("ak", key); body.put("agent", agent); body.put("action", "list");
        JSONObject j = new JSONObject(post(api, body.toString()));
        if ("key".equals(j.optString("code"))) { p.edit().putBoolean("on", false).apply(); stopSelf(); return; }
        if (!j.optBoolean("ok")) return;
        JSONArray ts = j.optJSONArray("tickets");
        if (ts == null) return;

        boolean base = needBase || !p.contains("seen");
        JSONObject seen = new JSONObject(p.getString("seen", "{}")), now = new JSONObject();
        for (int i = 0; i < ts.length(); i++) {
            JSONObject t = ts.getJSONObject(i);
            String id = t.optString("ticket_id"), lm = t.optString("last_message_at"), ls = t.optString("last_sender"), st = t.optString("status");
            if (id.isEmpty()) continue;
            now.put(id, lm);
            if (base || !"customer".equals(ls) || "resolved".equals(st)) continue;
            boolean isNew = !seen.has(id);
            if (isNew || !lm.equals(seen.optString(id))) {
                String who = t.optString("customer_name", ""); if (who.isEmpty()) who = t.optString("business_name", "Customer");
                alert(id, (isNew ? "Tiket baru · " : "Pesan baru · ") + who, t.optString("last_text", ""));
            }
        }
        p.edit().putString("seen", now.toString()).apply();
        if (base) needBase = false;
    }

    private void alert(String id, String title, String text) {
        if (text.length() > 160) text = text.substring(0, 160) + "…";
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_MSG) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < 26) { b.setPriority(Notification.PRIORITY_HIGH); b.setDefaults(Notification.DEFAULT_ALL); }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(100 + Math.abs(id.hashCode() % 100000), b.build());
    }

    private Notification.Builder base() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_RUN) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b;
    }

    private void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel run = new NotificationChannel(CH_RUN, "Layanan latar belakang", NotificationManager.IMPORTANCE_MIN);
        run.setShowBadge(false);
        NotificationChannel msg = new NotificationChannel(CH_MSG, "Tiket dan chat baru", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(run); nm.createNotificationChannel(msg);
    }

    private static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream(); o.write(json.getBytes("UTF-8")); o.close();
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
