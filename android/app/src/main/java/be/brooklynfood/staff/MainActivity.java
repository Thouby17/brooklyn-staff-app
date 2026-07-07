package be.brooklynfood.staff;

import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * App staff Brooklyn : coquille Capacitor + pont d'impression réseau.
 *
 * On expose à la page web un objet JS `window.NativePrinter` avec une méthode
 * `print(host, port, base64)` qui ouvre une connexion TCP vers l'imprimante
 * (ESC/POS, port 9100) et lui envoie les octets du ticket. Le site staff
 * détecte la présence de `window.NativePrinter` et, si une IP est configurée,
 * imprime directement — sinon il retombe sur l'impression navigateur habituelle.
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = this.getBridge().getWebView();
        webView.addJavascriptInterface(new PrinterBridge(), "NativePrinter");

        // L'interface JS n'est visible qu'au prochain chargement de page : on
        // recharge une fois à CHAQUE création de l'activité (lancement initial
        // mais aussi recréation par le système) pour garantir que
        // `window.NativePrinter` existe toujours. reload() ne re-déclenche pas
        // onCreate : pas de boucle possible.
        webView.post(webView::reload);
    }

    /** Pont d'impression appelé depuis le JavaScript de la page staff. */
    public static class PrinterBridge {
        /**
         * Envoie des octets ESC/POS à une imprimante réseau.
         *
         * Le réseau est fait dans un thread dédié (jamais sur un thread
         * surveillé par StrictMode — certains constructeurs l'appliquent au-delà
         * du thread principal) ; on attend le résultat avec un timeout global.
         *
         * @param host IP de l'imprimante (ex. "192.168.1.47")
         * @param port port TCP (9100 en standard)
         * @param base64Data ticket encodé en base64
         * @return "ok" si envoyé, sinon "error:<détail>"
         */
        @JavascriptInterface
        public String print(String host, int port, String base64Data) {
            final String[] result = new String[1];
            final CountDownLatch done = new CountDownLatch(1);

            Thread worker = new Thread(() -> {
                Socket socket = null;
                try {
                    byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 4000);
                    socket.setSoTimeout(4000);
                    OutputStream out = socket.getOutputStream();
                    out.write(data);
                    out.flush();
                    result[0] = "ok";
                } catch (Exception e) {
                    String msg = e.getMessage();
                    result[0] = "error:" + (msg != null ? msg : e.getClass().getSimpleName());
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                }
                done.countDown();
            });
            worker.setDaemon(true);
            worker.start();

            try {
                if (!done.await(8, TimeUnit.SECONDS)) {
                    return "error:délai dépassé — imprimante injoignable (IP ? même réseau ?)";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "error:interrompu";
            }
            return result[0] != null ? result[0] : "error:inconnu";
        }
    }
}
