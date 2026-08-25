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
import android.graphics.BitmapFactory;
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

    /**
     * ⚠️ IDENTIFIANTS DE NOTIFICATION — HORS DE PORTÉE DES NUMÉROS DE COMMANDE.
     *
     * Ils valaient `1`, et les notifications de commande utilisent l'`id` de la
     * commande. Chez un restaurant qui démarre, la TOUTE PREMIÈRE commande porte
     * l'id 1 : son alerte écrasait donc la notification permanente du service,
     * ou l'inverse. Un défaut qui ne se produit qu'une fois par client, le jour
     * de sa mise en service — le pire moment.
     */
    private static final int SERVICE_NOTIF_ID = 1_000_001;
    private static final int SANTE_NOTIF_ID = 1_000_002;

    private static final String CH_SERVICE = "service_v1"; // notif permanente discrète
    private static final String CH_ORDERS = "orders_v1";   // alertes commandes (son + vibration)

    /** Nombre de tours ratés d'affilée avant de crier « serveur injoignable ». */
    private static final int ECHECS_AVANT_ALERTE = 3; // ~1 minute

    private HandlerThread thread;
    private Handler handler;
    private android.os.PowerManager.WakeLock veille;
    private final HashSet<Integer> seenIds = new HashSet<>();
    // Commandes encore "pending" à ré-alerter à chaque tour tant qu'elles ne
    // sont pas prises en charge (acceptées / refusées).
    private final HashSet<Integer> alerting = new HashSet<>();
    private boolean baselined = false;

    /**
     * ⭐⭐ L'ÉTAT DE SANTÉ — le cœur de la v1.6.
     *
     * Jusqu'ici, TOUTES les pannes de ce service étaient silencieuses : session
     * expirée, Wi-Fi coupé, cuisine non choisie, serveur en erreur. Le code
     * faisait `return;` et la notification permanente continuait d'afficher
     * « Surveillance des commandes active ». La cuisine voyait donc un message
     * rassurant pendant qu'elle était SOURDE.
     *
     * ⚠️ Le cas le plus fréquent n'a rien d'exotique : le cookie du personnel
     * dure 12 h. Il expire donc TOUS LES JOURS, souvent en plein service.
     *
     * ⭐ Règle retenue : *une app qui ne peut plus faire son travail doit le
     * dire plus fort qu'une commande.* Le silence est réservé au bon
     * fonctionnement.
     */
    private int echecsConsecutifs = 0;
    private String panneAffichee = null; // null = tout va bien

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

        // ⭐⭐ VEILLE PARTIELLE — sans elle, la surveillance s'arrête écran éteint.
        //
        // `Handler.postDelayed` compte en temps de FONCTIONNEMENT du processeur :
        // pendant que la tablette dort, ce compteur n'avance pas, et le tour
        // suivant n'arrive jamais. Or « sonner même écran verrouillé » est
        // précisément la promesse de ce service — elle était donc fausse dès que
        // l'écran s'éteignait.
        // ⚠️ Une veille permanente consomme de la batterie, et c'est ASSUMÉ : une
        // tablette de cuisine est branchée en permanence. Le compromis inverse —
        // économiser la batterie d'un appareil sur secteur au prix d'une commande
        // ratée — n'a aucun sens ici.
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            veille = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "brooklynstaff:commandes");
            veille.setReferenceCounted(false);
            veille.acquire();
        } catch (Exception ignored) {
            // Pas de veille possible : le service tourne quand même, simplement
            // moins fiable écran éteint. Ne jamais empêcher le démarrage pour ça.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_order)
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
        try { if (veille != null && veille.isHeld()) veille.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void pollLoop() {
        try {
            poll();
        } catch (Exception e) {
            // ⚠️ AVANT : `catch (Exception ignored) {}` — le réseau coupé, le
            // serveur en erreur et le JSON illisible se taisaient tous les trois.
            constaterEchec("Serveur injoignable");
        }
        handler.postDelayed(this::pollLoop, POLL_MS);
    }

    /**
     * Un tour a échoué. On ne crie qu'après {@link #ECHECS_AVANT_ALERTE} tours :
     * un hoquet réseau de vingt secondes ne doit pas déclencher une alarme en
     * plein service. ⭐ Même raisonnement que les réessais du contrôle des sites.
     */
    private void constaterEchec(String motif) {
        constaterEchec(motif, ECHECS_AVANT_ALERTE);
    }

    private void constaterEchec(String motif, int seuil) {
        echecsConsecutifs++;
        if (echecsConsecutifs >= seuil) alerterPanne(motif);
    }

    /** Un tour a réussi : on efface l'alerte si elle était affichée. */
    private void constaterSucces() {
        echecsConsecutifs = 0;
        if (panneAffichee != null) {
            panneAffichee = null;
            try { NotificationManagerCompat.from(this).cancel(SANTE_NOTIF_ID); } catch (Exception ignored) { }
            majNotificationPermanente("Surveillance des commandes active");
        }
    }

    /**
     * ⭐⭐ CRIER. C'est la fonction qui manquait à cette app.
     *
     * On réutilise le canal des COMMANDES, donc le son d'alarme : une tablette
     * devenue sourde est au moins aussi grave qu'une commande non vue. Et la
     * notification est `ongoing` — impossible de la balayer par mégarde en
     * essuyant l'écran.
     * ⚠️ Elle n'est publiée qu'une fois par panne (`panneAffichee`) : re-sonner
     * toutes les vingt secondes rendrait la tablette inutilisable, et une alarme
     * qu'on finit par ignorer ne protège plus de rien.
     */
    private void alerterPanne(String motif) {
        if (motif.equals(panneAffichee)) return;
        panneAffichee = motif;
        majNotificationPermanente("⚠️ " + motif);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, SANTE_NOTIF_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ORDERS)
                .setSmallIcon(R.drawable.ic_stat_order)
                .setColor(0xFFB31942)
                .setContentTitle("⚠️ Les commandes n'arrivent plus")
                .setContentText(motif + " — touchez pour rouvrir l'écran des commandes")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        motif + ".\n\nLa tablette ne reçoit plus les commandes. "
                        + "Touchez cette alerte, puis reconnectez-vous si l'écran le demande."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOngoing(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(this).notify(SANTE_NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
            // Notifications refusées : c'est justement le cas que `MainActivity`
            // signale à l'écran au lancement. Rien à faire de plus ici.
        }
    }

    /** Réécrit le texte de la notification permanente — elle doit dire la VÉRITÉ. */
    private void majNotificationPermanente(String texte) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_order)
                .setContentTitle("Brooklyn Food Staff")
                .setContentText(texte)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN);
        try {
            NotificationManagerCompat.from(this).notify(SERVICE_NOTIF_ID, b.build());
        } catch (Exception ignored) { }
    }

    private void poll() throws Exception {
        // Cookies partagés avec la WebView (staff_auth httpOnly compris).
        String cookie = CookieManager.getInstance().getCookie(BASE_URL);
        if (cookie == null || !cookie.contains("staff_auth")) {
            // ⚠️ DEUX SITUATIONS TRÈS DIFFÉRENTES DERRIÈRE LE MÊME SYMPTÔME :
            //  · on n'a JAMAIS reçu de commande (`baselined` faux) → c'est une
            //    installation en cours, quelqu'un est en train de se connecter.
            //    Crier au bout d'une minute serait du bruit pendant le réglage.
            //  · on en recevait, et le cookie a disparu → la session a EXPIRÉ.
            //    C'est le cas quotidien, et le plus dangereux : la cuisine croit
            //    surveiller et ne surveille plus.
            if (baselined) alerterPanne("Session expirée — reconnectez-vous");
            else constaterEchec("Personne n'est connecté sur cette tablette", 15);
            return;
        }

        String location = null;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("staff_location=")) location = p.substring("staff_location=".length());
        }
        if (location == null || location.isEmpty()) {
            constaterEchec("Aucune cuisine choisie sur cette tablette", 15);
            return;
        }

        HttpURLConnection c = (HttpURLConnection) new URL(
                BASE_URL + "/api/orders?live=1&location=" + URLEncoder.encode(location, "UTF-8")
        ).openConnection();
        c.setRequestProperty("Cookie", cookie);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                // ⚠️ 401 = la session du personnel n'est plus valable. C'est LA
                // panne quotidienne (le cookie dure 12 h) et elle était avalée
                // par un `return` muet : la tablette affichait « Surveillance
                // active » et ne sonnait plus jusqu'au lendemain.
                if (code == 401 || code == 403) alerterPanne("Session expirée — reconnectez-vous");
                else constaterEchec("Le serveur a répondu " + code);
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            boolean first = !baselined;

            // Commandes encore "pending" vues à CE tour (pour arrêter d'alerter
            // celles qui ont disparu du flux : terminées / supprimées).
            HashSet<Integer> stillPending = new HashSet<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int id = o.getInt("id");
                boolean pending = "pending".equals(o.optString("status"));

                if (first) {
                    // Photo de départ : on mémorise sans alerter — SAUF les
                    // commandes encore EN ATTENTE.
                    //
                    // ⚠️⚠️ CORRIGÉ EN v1.6, ET C'ÉTAIT UNE COMMANDE PERDUE. La
                    // photo de départ existe pour ne pas rejouer l'historique au
                    // démarrage : c'est juste pour les commandes déjà traitées.
                    // Mais elle avalait AUSSI celles qui attendaient encore —
                    // donc après un redémarrage de la tablette, une coupure de
                    // courant ou un simple redémarrage du service, une commande
                    // en attente ne sonnait **plus jamais**.
                    // ⭐ Une commande qui attend n'est pas de l'historique : c'est
                    // exactement ce que la cuisine doit voir en rallumant.
                    seenIds.add(id);
                    if (pending) {
                        stillPending.add(id);
                        alerting.add(id);
                        if (!MainActivity.isForeground) notifyNewOrder(o);
                    }
                    continue;
                }

                if (pending) {
                    stillPending.add(id);
                    if (!seenIds.contains(id)) { // nouvelle commande
                        seenIds.add(id);
                        alerting.add(id);
                    }
                    // Ré-alerte tant que la commande n'est pas prise en charge —
                    // MAIS seulement écran éteint / app pas au premier plan : quand
                    // l'app est ouverte, l'alarme du SITE prend le relais (elle se
                    // répète déjà), on évite ainsi la double alarme.
                    if (alerting.contains(id) && !MainActivity.isForeground) {
                        notifyNewOrder(o);
                    }
                } else {
                    // Acceptée / refusée : on arrête d'alerter et on retire la notif.
                    seenIds.add(id);
                    if (alerting.remove(id)) cancel(id);
                }
            }

            baselined = true;

            // Commandes qui ont quitté le flux sans repasser en "non-pending"
            // (ex. purge côté serveur) : on cesse de les alerter.
            if (!first) {
                java.util.Iterator<Integer> it = alerting.iterator();
                while (it.hasNext()) {
                    int id = it.next();
                    if (!stillPending.contains(id)) { it.remove(); cancel(id); }
                }
            }

            if (seenIds.size() > 2000) { // garde-fou mémoire (kiosque qui tourne des semaines)
                seenIds.clear();
                alerting.clear();
                baselined = false; // re-baseline au prochain poll
            }

            // ⭐ Un tour complet a réussi : s'il y avait une alerte de panne
            // affichée, elle disparaît d'elle-même. Le retour à la normale doit
            // être aussi visible que la panne — sinon le staff garde un bandeau
            // rouge sous les yeux et cesse de le lire.
            constaterSucces();
        } finally {
            c.disconnect();
        }
    }

    /** Retire la notification d'alarme d'une commande (prise en charge / disparue). */
    private void cancel(int id) {
        try {
            NotificationManagerCompat.from(this).cancel(id);
        } catch (Exception ignored) {
            // rien à faire : la notif disparaîtra au prochain nettoyage système
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

        // Identité de l'app dans la notification : cloche monochrome en barre de
        // statut (norme Android), logo Brooklyn en grand, accent rouge de marque —
        // avant, le dessin par défaut du modèle rendait la notif « anonyme ».
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ORDERS)
                .setSmallIcon(R.drawable.ic_stat_order)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setColor(0xFFB31942)
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
