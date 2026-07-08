package be.brooklynfood.staff;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour de l'app en 2 gestes pour le restaurateur.
 *
 * Au lancement, on lit https://<site>/staff-app-version.json :
 *   { "versionCode": 3, "versionName": "1.5", "url": "https://<site>/staff-app.apk" }
 * Si versionCode > celui de l'app installée, on propose « Mettre à jour » :
 * téléchargement de l'APK dans le cache, puis ouverture de l'installateur
 * Android (le système demande une confirmation « Installer » — obligatoire
 * pour toute app hors Play Store, impossible à contourner).
 *
 * Prérequis : les APK doivent être signés avec la MÊME clé permanente
 * (voir signingConfigs dans build.gradle) — sinon Android refuse l'installation
 * par-dessus. Échec silencieux si pas de réseau : on ne bloque jamais l'app.
 */
final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String VERSION_URL = OrderWatchService.BASE_URL + "/staff-app-version.json";
    private static final int TIMEOUT_MS = 6000;

    /**
     * Anti-rafale : au plus une vérification par minute. PAS « une fois par
     * processus » : le service de notifications garde le processus vivant en
     * permanence, donc « une fois par processus » signifierait « une seule fois
     * jusqu'au redémarrage de la tablette » — le dialogue ne réapparaîtrait
     * jamais en rouvrant l'app (bug attrapé par le test à la maison).
     */
    private static long lastCheckMs = 0;

    private UpdateChecker() {}

    static void checkAtLaunch(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < 60_000) return;
        lastCheckMs = now;

        Thread t = new Thread(() -> {
            try {
                JSONObject info = fetchVersionInfo();
                if (info == null) return;
                int remoteCode = info.optInt("versionCode", 0);
                String remoteName = info.optString("versionName", "?");
                String apkUrl = info.optString("url", OrderWatchService.BASE_URL + "/staff-app.apk");
                if (remoteCode <= BuildConfig.VERSION_CODE) return; // à jour

                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    new AlertDialog.Builder(activity)
                            .setTitle("Mise à jour disponible")
                            .setMessage("La version " + remoteName + " de l'app est disponible "
                                    + "(vous avez la " + BuildConfig.VERSION_NAME + ").\n\n"
                                    + "L'installation prend moins d'une minute ; "
                                    + "vos réglages sont conservés.")
                            .setPositiveButton("Mettre à jour", (d, w) -> download(activity, apkUrl))
                            .setNegativeButton("Plus tard", null)
                            .show();
                });
            } catch (Exception e) {
                Log.w(TAG, "Vérification de mise à jour impossible : " + e);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static JSONObject fetchVersionInfo() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(VERSION_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != 200) return null; // pas (encore) publié : silencieux
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null; // pas de réseau / JSON invalide : on n'embête jamais l'utilisateur
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Télécharge l'APK dans le cache (dialogue non annulable) puis lance l'installateur. */
    private static void download(Activity activity, String apkUrl) {
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Téléchargement…")
                .setMessage("Récupération de la mise à jour, quelques secondes.")
                .setCancelable(false)
                .show();

        Thread t = new Thread(() -> {
            File apk = new File(activity.getCacheDir(), "staff-app-update.apk");
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(30_000);
                if (conn.getResponseCode() != 200) {
                    error = "Le serveur a répondu " + conn.getResponseCode();
                } else {
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(apk)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                error = (msg != null ? msg : e.getClass().getSimpleName());
            } finally {
                if (conn != null) conn.disconnect();
            }

            final String err = error;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                if (activity.isFinishing()) return;
                if (err != null) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Téléchargement impossible")
                            .setMessage("La mise à jour n'a pas pu être téléchargée ("
                                    + err + ").\n\nVérifiez la connexion internet "
                                    + "et réessayez en fermant/rouvrant l'app.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(
                            activity, activity.getPackageName() + ".fileprovider", apk);
                    Intent install = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(install);
                } catch (Exception e) {
                    Log.e(TAG, "Ouverture de l'installateur impossible", e);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }
}
