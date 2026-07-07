package be.brooklynfood.staff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Après un redémarrage de la tablette (coupure de courant au resto…), relance
 * automatiquement la surveillance des commandes : les notifications + alarme
 * reprennent sans intervention. (L'écran des commandes, lui, doit être rouvert
 * d'un tap — Android interdit d'ouvrir une interface depuis le boot.)
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Intent svc = new Intent(context, OrderWatchService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) {
            // Certaines versions récentes d'Android restreignent ce démarrage :
            // dans ce cas la surveillance reprendra à la 1re ouverture de l'app.
        }
    }
}
