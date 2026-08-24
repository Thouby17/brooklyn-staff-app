package be.brooklynfood.staff;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * App staff Brooklyn Food : coquille Capacitor + pont d'impression.
 *
 * On expose à la page web un objet JS `window.NativePrinter` ({@link PrinterBridge})
 * qui sait imprimer par RÉSEAU (port 9100), BLUETOOTH (SPP) ou USB, et aider au
 * réglage (recherche des imprimantes du réseau, identification du fabricant).
 * Le site staff détecte la présence de `window.NativePrinter` et, si une
 * imprimante est configurée, imprime directement — sinon il retombe sur
 * l'impression navigateur habituelle.
 */
public class MainActivity extends BridgeActivity {

    /**
     * Vrai quand l'écran staff est au premier plan (app visible, écran allumé).
     * Lu par {@link OrderWatchService} : quand l'app est devant, c'est l'alarme
     * du SITE qui sonne (et se répète), donc le service natif se tait pour ne
     * pas doubler l'alarme. Le natif ne ré-alerte que écran éteint / en fond.
     */
    public static volatile boolean isForeground = false;

    @Override
    public void onResume() {
        super.onResume();
        isForeground = true;
    }

    @Override
    public void onPause() {
        isForeground = false;
        super.onPause();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Écran de cuisine : ne JAMAIS laisser l'écran s'éteindre tant que
        // l'app est affichée (équivalent natif — et fiable — du Wake Lock web).
        // Le bouton power permet toujours d'éteindre manuellement.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WebView webView = this.getBridge().getWebView();
        webView.addJavascriptInterface(new PrinterBridge(this), "NativePrinter");

        // App native = le son a le droit de démarrer sans geste de l'utilisateur
        // (contrairement au navigateur). L'alarme web sonne donc dès la première
        // commande et la bannière « Son bloqué, touchez pour activer » disparaît.
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // L'interface JS n'est visible qu'au prochain chargement de page : on
        // recharge une fois à CHAQUE création de l'activité (lancement initial
        // mais aussi recréation par le système) pour garantir que
        // `window.NativePrinter` existe toujours. reload() ne re-déclenche pas
        // onCreate : pas de boucle possible.
        webView.post(webView::reload);

        requestRuntimePermissions();

        // Surveillance des commandes (notification + alarme même écran verrouillé).
        Intent watch = new Intent(this, OrderWatchService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(watch);
        } else {
            startService(watch);
        }

        // Propose la mise à jour de l'app si une nouvelle version est publiée
        // sur le site (staff-app-version.json). Silencieux si pas de réseau.
        UpdateChecker.checkAtLaunch(this);
    }

    /**
     * Permissions demandées au lancement, en UN SEUL dialogue.
     *
     * - POST_NOTIFICATIONS (Android 13+) : sans elle, aucune alerte de commande.
     * - BLUETOOTH_CONNECT (Android 12+)  : sans elle, la liste des imprimantes
     *   Bluetooth revient VIDE, sans erreur explicite — le staff croirait que son
     *   imprimante n'est pas appairée. On la demande donc dès le départ, même si
     *   la plupart des restaurants impriment en réseau.
     *
     * On ne demande QUE ce qui manque : re-demander une permission déjà accordée
     * ne fait rien, mais autant garder le dialogue minimal.
     */
    private void requestRuntimePermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Android 12+ : le Bluetooth est passé en permission d'exécution.
        // Avant, les anciennes permissions du manifest suffisaient.
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                   != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 42);
        }
    }

    /**
     * Bouton Retour : navigation web uniquement, ne quitte JAMAIS l'app.
     * Écran de cuisine = une main pressée peut appuyer par erreur ; sortir de
     * l'app couperait l'affichage des commandes. (Pour quitter volontairement :
     * bouton Accueil, ou geste système.)
     */
    @Override
    public void onBackPressed() {
        WebView webView = this.getBridge().getWebView();
        if (webView.canGoBack()) {
            webView.goBack();
        }
        // sinon : rien — on reste sur l'écran des commandes.
    }
}
