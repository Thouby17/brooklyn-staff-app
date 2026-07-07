package be.brooklynfood.staff;

import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

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
        // recharge une fois au démarrage pour garantir que `window.NativePrinter`
        // existe dès l'ouverture (uniquement au vrai lancement, pas aux rotations).
        if (savedInstanceState == null) {
            webView.post(webView::reload);
        }
    }

    /** Pont d'impression appelé depuis le JavaScript de la page staff. */
    public static class PrinterBridge {
        /**
         * Envoie des octets ESC/POS à une imprimante réseau.
         * @param host IP de l'imprimante (ex. "192.168.1.47")
         * @param port port TCP (9100 en standard)
         * @param base64Data ticket encodé en base64
         * @return "ok" si envoyé, sinon "error:<détail>"
         */
        @JavascriptInterface
        public String print(String host, int port, String base64Data) {
            Socket socket = null;
            try {
                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 4000);
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();
                return "ok";
            } catch (Exception e) {
                return "error:" + e.getMessage();
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }
    }
}
