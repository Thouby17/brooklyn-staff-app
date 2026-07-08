package be.brooklynfood.staff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.webkit.CookieManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;

/**
 * Surveillance des commandes en arrière-plan.
 *
 * Service au premier plan (notification discrète permanente) qui interroge
 * l'API du site toutes les 20 s avec les cookies du staff (partagés avec la
 * WebView). À chaque NOUVELLE commande "pending", il déclenche une
 * notification Android prioritaire avec l'alarme sonore embarquée
 * (res/raw/alarm.wav) — audible même tablette verrouillée / app en arrière-plan.
 *
 * L'app web, elle, garde son alarme habituelle quand l'écran est allumé :
 * les deux mécanismes détectent par id, donc pas de double comptage gênant
 * (au pire, notification + alarme web en même temps = encore plus audible).
 */
public class OrderWatchService extends Service {

    // Package-private : aussi utilisée par UpdateChecker (une seule URL à
    // changer lors de la duplication pour un autre client).
    static final String BASE_URL = "https://brooklynfood.be";
    private static final long POLL_MS = 20_000;
    private static final int SERVICE_NOTIF_ID = 1;
    private static final String CH_SERVICE = "service_v1"; // notif permanente discrète
    private static final String CH_ORDERS = "orders_v1";   // alertes commandes (son + vibration)

    private HandlerThread thread;
    private Handler handler;
    private final HashSet<Integer> seenIds = new HashSet<>();
    private boolean baselined = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        thread = new HandlerThread("order-watch");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Brooklyn Food Staff")
                .setContentText("Surveillance des commandes active")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SERVICE_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(SERVICE_NOTIF_ID, n);
        }
        handler.removeCallbacksAndMessages(null);
        handler.post(this::pollLoop);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private void pollLoop() {
        try {
            poll();
        } catch (Exception ignored) {
            // réseau coupé, cookie expiré… on retentera au prochain tick
        }
        handler.postDelayed(this::pollLoop, POLL_MS);
    }

    private void poll() throws Exception {
        // Cookies partagés avec la WebView (staff_auth httpOnly compris).
        String cookie = CookieManager.getInstance().getCookie(BASE_URL);
        if (cookie == null || !cookie.contains("staff_auth")) return; // pas connecté

        String location = null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("staff_location=")) location = p.substring("staff_location=".length());
        }
        if (location == null || location.isEmpty()) return; // cuisine pas choisie

        HttpURLConnection c = (HttpURLConnection) new URL(
                BASE_URL + "/api/orders?live=1&location=" + URLEncoder.encode(location, "UTF-8")
        ).openConnection();
        c.setRequestProperty("Cookie", cookie);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            boolean first = !baselined;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int id = o.getInt("id");
                if (seenIds.contains(id)) continue;
                seenIds.add(id);
                if (!first && "pending".equals(o.optString("status"))) {
                    notifyNewOrder(o);
                }
            }
            baselined = true;
            if (seenIds.size() > 2000) { // garde-fou mémoire (kiosque qui tourne des semaines)
                seenIds.clear();
                baselined = false; // re-baseline au prochain poll, sans alerter
            }
        } finally {
            c.disconnect();
        }
    }

    private void notifyNewOrder(JSONObject o) {
        // Volume « anti-bêtise » : si quelqu'un a baissé le volume d'alarme de
        // la tablette, on le remonte à 80 % minimum avant de sonner — LE grand
        // classique du « ça ne sonne plus » en restaurant.
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int floor = Math.round(max * 0.8f);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < floor) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0);
            }
        } catch (Exception ignored) {
            // Ne pas sonner est pire que ne pas régler le volume : on continue.
        }

        int id = o.optInt("id", 0);
        int shown = o.isNull("dailyNumber") ? id : o.optInt("dailyNumber", id);
        String customer = o.optString("customerName", "");

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ORDERS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("🍔 Nouvelle commande #" + shown)
                .setContentText(customer)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(this).notify(id, b.build());
        } catch (SecurityException ignored) {
            // permission notifications refusée (Android 13+) — rien à faire ici
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "Surveillance (technique)", NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel ord = new NotificationChannel(
                CH_ORDERS, "Nouvelles commandes", NotificationManager.IMPORTANCE_HIGH);
        Uri sound = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alarm);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ord.setSound(sound, attrs);
        ord.enableVibration(true);
        ord.setShowBadge(true);
        nm.createNotificationChannel(ord);
    }
}
