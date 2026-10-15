package be.brooklynfood.staff;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⭐ v1.10 (15/10/2026) — CHERCHER UNE IMPRIMANTE BLUETOOTH DEPUIS L'APP.
 *
 * Avant, seules les imprimantes DÉJÀ associées dans les réglages d'Android
 * apparaissaient : il fallait quitter l'app, chercher dans Android, associer,
 * revenir. Désormais l'écran Imprimantes cherche lui-même autour de lui et
 * associe d'un geste.
 *
 * ⚠️⚠️ ASYNCHRONE comme la recherche réseau : Android annonce les appareils
 * un par un pendant ~12 s ; le site relit {@link #resultats} au fil de l'eau.
 *
 * ⚠️ LES AUTORISATIONS — c'est le piège de toute recherche Bluetooth :
 *  - Android 12 et plus : BLUETOOTH_SCAN (déclarée « neverForLocation » : on
 *    ne s'en sert pas pour localiser qui que ce soit) ;
 *  - Android 11 et moins : la LOCALISATION, accordée ET allumée — sans elle,
 *    Android rend une recherche vide, sans un mot.
 * Une autorisation qui manque est DEMANDÉE à cet instant (dialogue d'Android)
 * et le site affiche « autorisez, puis touchez à nouveau Chercher » — jamais
 * une liste vide qui laisserait croire qu'il n'y a rien autour.
 */
final class RechercheBluetooth {

    private static final Object VERROU = new Object();
    private static boolean enCours = false;
    private static String erreur = null;
    private static final Map<String, String[]> vus = new LinkedHashMap<>();
    // vus : mac → {nom, rssi, associé ("1"/"0"), imprimante ("1"/"0"), type}
    private static BroadcastReceiver ecouteur = null;

    private RechercheBluetooth() {}

    static String demarrer(Context ctx, WeakReference<Activity> activite) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "error:Bluetooth non disponible sur cette tablette";
        if (!adapter.isEnabled()) return "error:Bluetooth désactivé";

        String manque = autorisationManquante(ctx);
        if (manque != null) {
            demander(activite, manque);
            return "error:autorisation Bluetooth refusée";
        }
        if (Build.VERSION.SDK_INT <= 30 && !localisationAllumee(ctx)) {
            return "error:localisation désactivée";
        }

        synchronized (VERROU) {
            if (enCours) return "ok";
            vus.clear();
            erreur = null;
            enCours = true;
        }
        brancher(ctx);
        try {
            try { adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
            if (!adapter.startDiscovery()) {
                synchronized (VERROU) { enCours = false; }
                return "error:la recherche Bluetooth n'a pas pu démarrer";
            }
        } catch (SecurityException e) {
            synchronized (VERROU) { enCours = false; }
            return "error:autorisation Bluetooth refusée";
        }
        // Filet : Android annonce la fin, mais certaines ROM l'oublient.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (VERROU) { enCours = false; }
        }, 20000);
        return "ok";
    }

    static String resultats() {
        synchronized (VERROU) {
            StringBuilder sb = new StringBuilder("{\"running\":").append(enCours).append(",\"devices\":[");
            boolean premier = true;
            for (Map.Entry<String, String[]> e : vus.entrySet()) {
                String[] v = e.getValue();
                if (!premier) sb.append(',');
                premier = false;
                sb.append("{\"mac\":\"").append(e.getKey()).append('"');
                if (v[0] != null) sb.append(",\"name\":\"").append(esc(v[0])).append('"');
                if (v[1] != null) sb.append(",\"rssi\":").append(v[1]);
                sb.append(",\"bonded\":").append("1".equals(v[2]))
                        .append(",\"printer\":").append("1".equals(v[3]));
                if (v[4] != null) sb.append(",\"kind\":\"").append(v[4]).append('"');
                sb.append('}');
            }
            sb.append(']');
            if (erreur != null) sb.append(",\"error\":\"").append(esc(erreur)).append('"');
            return sb.append('}').toString();
        }
    }

    /** Lance l'association ; le site relit {@link #resultats} (« bonded » passe à vrai). */
    static String associer(Context ctx, String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "error:Bluetooth non disponible sur cette tablette";
            if (!BluetoothAdapter.checkBluetoothAddress(mac)) return "error:adresse Bluetooth invalide";
            brancher(ctx);
            BluetoothDevice d = adapter.getRemoteDevice(mac);
            try { adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) { noter(d, null, null); return "ok"; }
            return d.createBond() ? "ok" : "error:l'association n'a pas pu démarrer";
        } catch (SecurityException e) {
            return "error:autorisation Bluetooth refusée";
        } catch (Exception e) {
            return "error:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ───────────────────────── écoute d'Android ─────────────────────────

    private static void brancher(Context ctx) {
        synchronized (VERROU) {
            if (ecouteur != null) return;
            ecouteur = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                        synchronized (VERROU) { enCours = false; }
                        return;
                    }
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                        short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                        noter(d, i.getStringExtra(BluetoothDevice.EXTRA_NAME), rssi == Short.MIN_VALUE ? null : String.valueOf(rssi));
                    } else {
                        noter(d, null, null); // association ou nom qui change
                    }
                }
            };
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        // Annonces du SYSTÈME (le Bluetooth d'Android) : elles viennent d'un autre
        // processus, d'où « exportée ».
        ContextCompat.registerReceiver(ctx.getApplicationContext(), ecouteur, f, ContextCompat.RECEIVER_EXPORTED);
    }

    private static void noter(BluetoothDevice d, String nomAnnonce, String rssi) {
        String mac;
        String nom = nomAnnonce;
        boolean associe = false;
        boolean imprimante = false;
        String type = null;
        try {
            mac = d.getAddress();
            if (nom == null) nom = d.getName();
            associe = d.getBondState() == BluetoothDevice.BOND_BONDED;
            BluetoothClass k = d.getBluetoothClass();
            // Classe « imagerie », bit « imprimante » — ce qu'annoncent la plupart
            // des imprimantes de tickets. Le site reconnaît aussi les autres à leur nom.
            imprimante = k != null && k.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING
                    && (k.getDeviceClass() & 0x80) != 0;
            type = k != null ? typeDe(k.getMajorDeviceClass()) : null;
        } catch (SecurityException e) {
            mac = d.getAddress();
        }
        if (mac == null) return;
        synchronized (VERROU) {
            String[] avant = vus.get(mac);
            if (avant != null && avant.length < 5) avant = null;
            vus.put(mac, new String[] {
                    nom != null && !nom.trim().isEmpty() ? nom.trim() : (avant != null ? avant[0] : null),
                    rssi != null ? rssi : (avant != null ? avant[1] : null),
                    associe ? "1" : "0",
                    imprimante || (avant != null && "1".equals(avant[3])) ? "1" : "0",
                    type != null ? type : (avant != null ? avant[4] : null),
            });
        }
    }

    /**
     * ⭐ v1.11 — LE GENRE de chaque appareil (téléphone, enceinte…). Voir des
     * appareils qui ne sont PAS des imprimantes prouve que la recherche marche :
     * s'il manque l'imprimante, c'est elle qu'il faut regarder.
     */
    private static String typeDe(int majeure) {
        switch (majeure) {
            case BluetoothClass.Device.Major.PHONE: return "phone";
            case BluetoothClass.Device.Major.COMPUTER: return "computer";
            case BluetoothClass.Device.Major.AUDIO_VIDEO: return "audio";
            case BluetoothClass.Device.Major.IMAGING: return "imaging";
            case BluetoothClass.Device.Major.PERIPHERAL: return "peripheral";
            case BluetoothClass.Device.Major.WEARABLE: return "wearable";
            case BluetoothClass.Device.Major.HEALTH: return "health";
            case BluetoothClass.Device.Major.TOY: return "toy";
            case BluetoothClass.Device.Major.NETWORKING: return "network";
            default: return "other";
        }
    }

    // ───────────────────────── v1.11 : état et allumage ─────────────────────────

    /** {"state":"on|off|absent","permission":…,"location":…,"canEnable":…} — sans rien demander. */
    static String etat(Context ctx, WeakReference<Activity> activite) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "{\"state\":\"absent\",\"permission\":true,\"location\":true,\"canEnable\":false}";
        boolean allume;
        try {
            allume = adapter.isEnabled();
        } catch (SecurityException e) {
            allume = false;
        }
        boolean autorise = autorisationManquante(ctx) == null;
        boolean localisation = Build.VERSION.SDK_INT > 30 || localisationAllumee(ctx);
        boolean peutAllumer = activite != null && activite.get() != null;
        return "{\"state\":\"" + (allume ? "on" : "off") + "\",\"permission\":" + autorise
                + ",\"location\":" + localisation + ",\"canEnable\":" + peutAllumer + "}";
    }

    /**
     * Demande à Android d'allumer le Bluetooth : Android affiche « Une
     * application souhaite activer le Bluetooth » — on n'allume JAMAIS sans
     * que la personne le voie. Exige BLUETOOTH_CONNECT (Android 12+), demandée
     * au lancement de l'app ; si elle manque, on la redemande.
     */
    static String allumer(Context ctx, WeakReference<Activity> ref) {
        final Activity a = ref != null ? ref.get() : null;
        if (a == null) return "error:app native indisponible";
        if (BluetoothAdapter.getDefaultAdapter() == null) return "error:Bluetooth non disponible sur cette tablette";
        if (Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            demander(ref, Manifest.permission.BLUETOOTH_CONNECT);
            return "error:autorisation Bluetooth refusée";
        }
        a.runOnUiThread(() -> {
            try {
                a.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
                // Certaines ROM refusent : l'écran propose alors les réglages rapides.
            }
        });
        return "ok";
    }

    // ───────────────────────── autorisations ─────────────────────────

    private static String autorisationManquante(Context ctx) {
        String p = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION;
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED ? null : p;
    }

    private static void demander(WeakReference<Activity> ref, String permission) {
        final Activity a = ref != null ? ref.get() : null;
        if (a == null || Build.VERSION.SDK_INT < 23) return;
        a.runOnUiThread(() -> {
            try { a.requestPermissions(new String[] {permission}, 43); } catch (Exception ignored) {}
        });
    }

    private static boolean localisationAllumee(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true; // dans le doute, on essaie : Android dira s'il refuse
        }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
