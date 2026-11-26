package id.dutakasir.tiket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Nyalakan lagi pemantau chat setelah HP dihidupkan ulang atau aplikasi diperbarui. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent in) {
        if (!ctx.getSharedPreferences(BgService.PREFS, Context.MODE_PRIVATE).getBoolean("on", false)) return;
        try { ContextCompat.startForegroundService(ctx, new Intent(ctx, BgService.class)); } catch (Exception e) { /* diabaikan */ }
    }
}
