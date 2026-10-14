package be.brooklynfood.staff;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Pont d'impression exposé au JavaScript de la page staff sous
 * {@code window.NativePrinter}.
 *
 * TROIS TRANSPORTS :
 *   - RÉSEAU (LAN, port 9100) : {@link #print} — historique, le plus fiable
 *   - BLUETOOTH (profil SPP)  : {@link #printBluetooth}
 *   - USB                     : {@link #printUsb}
 *
 * DEUX AIDES AU RÉGLAGE :
 *   - {@link #scanNetwork} : trouve les imprimantes du réseau (évite au staff
 *     d'imprimer un ticket d'état rien que pour lire l'IP)
 *   - {@link #probe} : identifie le fabricant via la page web embarquée de
 *     l'imprimante, ce qui permet au site de RECOMMANDER le bon langage.
 *
 * RÈGLES DE CE FICHIER (chacune vient d'un incident réel) :
 *   - JAMAIS de réseau/IO sur le thread appelant : certains constructeurs
 *     appliquent StrictMode au-delà du thread principal -> tout part dans un
 *     thread dédié, avec un timeout GLOBAL.
 *   - Toujours renvoyer une String : "ok" / du JSON / "error:<détail>". Le site
 *     traduit ensuite l'erreur brute en message clair pour le staff.
 *   - Le CONTENU du ticket n'est JAMAIS fabriqué ici : le site envoie des octets
 *     déjà composés (ESC/POS ou StarPRNT). Ce pont n'est qu'un tuyau — c'est ce
 *     qui permet de faire évoluer les tickets sans republier l'APK.
 */
public class PrinterBridge {

    /** UUID standard du profil port série (SPP), utilisé par les imprimantes Bluetooth. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /** Action de la demande d'autorisation USB (interne à l'app). */
    public static final String USB_PERMISSION = "be.brooklynfood.staff.USB_PERMISSION";

    private final Context context;

    public PrinterBridge(Context context) {
        // getApplicationContext() : ce pont survit à l'activité (thread réseau).
        this.context = context.getApplicationContext();
    }

    // ─────────────────────────── RÉSEAU (LAN) ───────────────────────────

    /**
     * Envoie des octets à une imprimante réseau (port 9100 en standard).
     *
     * @param host       IP de l'imprimante (ex. "192.168.1.47")
     * @param port       port TCP (9100)
     * @param base64Data ticket déjà composé (ESC/POS ou StarPRNT), en base64
     * @return "ok" ou "error:&lt;détail&gt;"
     */
    @JavascriptInterface
    public String print(String host, int port, String base64Data) {
        if (!estReseauLocal(host)) return refusHorsReseauLocal(host);
        return runWithTimeout(8, result -> {
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
                result[0] = "error:" + msgOf(e);
            } finally {
                closeQuietly(socket);
            }
        }, "error:délai dépassé — imprimante injoignable (IP ? même réseau ?)");
    }

    // ───────────────────────────── BLUETOOTH ─────────────────────────────

    /**
     * Liste les imprimantes Bluetooth DÉJÀ APPAIRÉES sur la tablette.
     *
     * On n'expose pas la découverte : l'appairage se fait une fois pour toutes
     * dans les réglages Android, c'est plus fiable et le staff n'a pas à le
     * refaire.
     *
     * @return JSON [{"name":"…","mac":"…"}] ou "error:&lt;détail&gt;"
     */
    @JavascriptInterface
    public String listBluetooth() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "error:Bluetooth non disponible sur cette tablette";
            if (!adapter.isEnabled()) return "error:Bluetooth désactivé";

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"name\":\"").append(esc(safeName(d)))
                        .append("\",\"mac\":\"").append(esc(d.getAddress())).append("\"}");
            }
            return json.append("]").toString();
        } catch (SecurityException e) {
            // Android 12+ : permission BLUETOOTH_CONNECT non accordée.
            return "error:autorisation Bluetooth refusée";
        } catch (Exception e) {
            return "error:" + msgOf(e);
        }
    }

    /**
     * Imprime via Bluetooth (profil SPP) sur une imprimante appairée.
     *
     * ⚠️ PIÈGE CONNU (déjà vécu) : le Bluetooth n'accepte QU'UNE connexion à la
     * fois. Si une autre app (Uber Eats…) tient l'imprimante, la connexion
     * échoue. Le réseau (LAN) n'a pas cette limite — c'est pourquoi il reste
     * recommandé quand une prise réseau est disponible.
     */
    @JavascriptInterface
    public String printBluetooth(String mac, String base64Data) {
        return runWithTimeout(15, result -> {
            BluetoothSocket socket = null;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) { result[0] = "error:Bluetooth non disponible"; return; }
                if (!adapter.isEnabled()) { result[0] = "error:Bluetooth désactivé"; return; }

                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                // Une découverte en cours ralentit/casse la connexion : on l'arrête.
                // ⚠️ Dans son PROPRE try/catch : sur Android 12+, cancelDiscovery()
                // exige BLUETOOTH_SCAN (qu'on ne demande pas — on ne lance jamais de
                // découverte nous-mêmes). Sans cette isolation, la SecurityException
                // faisait échouer TOUTE l'impression avec « autorisation refusée »
                // alors que la connexion, elle, ne demande que BLUETOOTH_CONNECT.
                try {
                    adapter.cancelDiscovery();
                } catch (SecurityException ignored) {
                    // Appel purement défensif : sans lui la connexion marche aussi.
                }
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();
                // Laisse le tampon partir avant de fermer : une fermeture
                // immédiate tronque la fin du ticket.
                Thread.sleep(400);
                result[0] = "ok";
            } catch (SecurityException e) {
                result[0] = "error:autorisation Bluetooth refusée";
            } catch (Exception e) {
                result[0] = "error:" + msgOf(e);
            } finally {
                closeQuietly(socket);
            }
        }, "error:délai dépassé — imprimante Bluetooth injoignable (appairée ? occupée par une autre app ?)");
    }

    // ─────────────────────────────── USB ───────────────────────────────

    /**
     * Liste les imprimantes USB branchées sur la TABLETTE (classe USB 7).
     *
     * @return JSON [{"id":123,"name":"…","granted":true}] ou "error:&lt;détail&gt;"
     */
    @JavascriptInterface
    public String listUsb() {
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) return "error:USB non disponible";

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (UsbDevice d : manager.getDeviceList().values()) {
                if (!isPrinter(d)) continue;
                if (!first) json.append(",");
                first = false;
                String name = nomUsb(d);
                json.append("{\"id\":").append(d.getDeviceId())
                        .append(",\"name\":\"").append(esc(name))
                        .append("\",\"granted\":").append(manager.hasPermission(d)).append("}");
            }
            return json.append("]").toString();
        } catch (Exception e) {
            return "error:" + msgOf(e);
        }
    }

    /**
     * Demande l'autorisation d'accès à une imprimante USB (Android affiche sa
     * propre boîte de dialogue). La réponse est ASYNCHRONE : le site relit
     * ensuite {@link #listUsb} pour voir si {@code granted} est passé à vrai.
     */
    @JavascriptInterface
    public String requestUsbPermission(int deviceId) {
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) return "error:USB non disponible";
            UsbDevice device = findUsb(manager, deviceId);
            if (device == null) return "error:imprimante USB introuvable (débranchée ?)";
            if (manager.hasPermission(device)) return "ok";

            // FLAG_IMMUTABLE obligatoire à partir d'Android 12.
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, 0,
                    new Intent(USB_PERMISSION).setPackage(context.getPackageName()),
                    flags);
            manager.requestPermission(device, pi);
            return "ok";
        } catch (Exception e) {
            return "error:" + msgOf(e);
        }
    }

    /** Imprime via USB sur une imprimante branchée en direct sur la tablette. */
    @JavascriptInterface
    public String printUsb(int deviceId, String base64Data) {
        return runWithTimeout(10, result -> {
            UsbDeviceConnection conn = null;
            UsbInterface intf = null;
            try {
                UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                if (manager == null) { result[0] = "error:USB non disponible"; return; }
                UsbDevice device = findUsb(manager, deviceId);
                if (device == null) { result[0] = "error:imprimante USB introuvable (débranchée ?)"; return; }
                if (!manager.hasPermission(device)) { result[0] = "error:autorisation USB non accordée"; return; }

                intf = printerInterface(device);
                if (intf == null) { result[0] = "error:ce périphérique USB n'est pas une imprimante"; return; }
                UsbEndpoint out = bulkOut(intf);
                if (out == null) { result[0] = "error:aucune sortie USB utilisable"; return; }

                conn = manager.openDevice(device);
                if (conn == null) { result[0] = "error:ouverture USB impossible"; return; }
                if (!conn.claimInterface(intf, true)) { result[0] = "error:imprimante USB déjà utilisée"; return; }

                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                // Envoi par blocs : certains contrôleurs USB tronquent les gros envois.
                int sent = 0;
                while (sent < data.length) {
                    int chunk = Math.min(4096, data.length - sent);
                    byte[] buf = new byte[chunk];
                    System.arraycopy(data, sent, buf, 0, chunk);
                    int n = conn.bulkTransfer(out, buf, chunk, 5000);
                    if (n < 0) { result[0] = "error:envoi USB interrompu"; return; }
                    sent += chunk;
                }
                result[0] = "ok";
            } catch (Exception e) {
                result[0] = "error:" + msgOf(e);
            } finally {
                if (conn != null) {
                    try { if (intf != null) conn.releaseInterface(intf); } catch (Exception ignored) {}
                    try { conn.close(); } catch (Exception ignored) {}
                }
            }
        }, "error:délai dépassé — imprimante USB ne répond pas");
    }

    // ──────────────── AIDES AU RÉGLAGE (scan / identification) ────────────────

    /**
     * Cherche les imprimantes du réseau local : teste le port 9100 sur tout le
     * sous-réseau, en parallèle. Évite au staff d'imprimer un ticket d'état
     * juste pour lire l'IP.
     *
     * @param subnet préfixe, ex. "192.168.1" (le site le déduit)
     * @return JSON ["192.168.1.47", …] ou "error:&lt;détail&gt;"
     */
    @JavascriptInterface
    public String scanNetwork(String subnet) {
        final String base = subnet != null ? subnet.trim() : "";
        if (!base.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            return "error:sous-réseau invalide (attendu : 192.168.1)";
        }
        if (!estReseauLocal(base)) return refusHorsReseauLocal(base);
        final List<String> found = new ArrayList<>();
        // 40 threads : tester 254 adresses une par une prendrait des minutes.
        ExecutorService pool = Executors.newFixedThreadPool(40);
        final CountDownLatch done = new CountDownLatch(254);
        for (int i = 1; i <= 254; i++) {
            final String ip = base + "." + i;
            pool.execute(() -> {
                Socket s = null;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(ip, 9100), 350);
                    synchronized (found) { found.add(ip); }
                } catch (Exception ignored) {
                    // pas d'imprimante à cette adresse : c'est le cas normal
                } finally {
                    closeQuietly(s);
                    done.countDown();
                }
            });
        }
        try {
            done.await(12, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        StringBuilder json = new StringBuilder("[");
        synchronized (found) {
            for (int i = 0; i < found.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(esc(found.get(i))).append("\"");
            }
        }
        return json.append("]").toString();
    }

    /**
     * Identifie une imprimante réseau via sa page web embarquée (Star et Epson en
     * ont une). Permet au site de RECOMMANDER le bon langage.
     *
     * @return JSON {"vendor":"star|epson|unknown","text":"…"} ou "error:&lt;détail&gt;"
     */
    @JavascriptInterface
    public String probe(String host) {
        if (!estReseauLocal(host)) return refusHorsReseauLocal(host);
        return runWithTimeout(8, result -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("http://" + host + "/").openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                StringBuilder body = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String l;
                    int lines = 0;
                    while ((l = r.readLine()) != null && lines++ < 200) body.append(l).append(" ");
                }
                String lower = body.toString().toLowerCase();
                String vendor;
                if (lower.contains("star micronics") || lower.contains("starprnt")
                        || lower.contains("mc-print") || lower.contains("tsp1")) {
                    vendor = "star";
                } else if (lower.contains("epson") || lower.contains("tm-m") || lower.contains("tm-t")) {
                    vendor = "epson";
                } else {
                    vendor = "unknown";
                }
                String extract = body.length() > 300 ? body.substring(0, 300) : body.toString();
                result[0] = "{\"vendor\":\"" + vendor + "\",\"text\":\"" + esc(extract) + "\"}";
            } catch (Exception e) {
                result[0] = "error:" + msgOf(e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "error:délai dépassé — pas de page web sur cette imprimante");
    }

    // ─────────────────────────────── OUTILS ───────────────────────────────

    private interface Job { void run(String[] result); }

    /** Exécute un travail réseau/IO dans un thread dédié, avec timeout global. */
    private String runWithTimeout(int seconds, Job job, String timeoutMessage) {
        final String[] result = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { job.run(result); } finally { done.countDown(); }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            if (!done.await(seconds, TimeUnit.SECONDS)) return timeoutMessage;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error:interrompu";
        }
        return result[0] != null ? result[0] : "error:inconnu";
    }

    private static String msgOf(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    private static void closeQuietly(Socket s) {
        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
    }

    /** Échappe une valeur pour l'insérer dans une chaîne JSON. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /**
     * ⭐ v1.9 (14/10/2026) — FABRICANTS D'IMPRIMANTES DE CAISSE, par identifiant USB.
     *
     * ⚠️⚠️ POURQUOI. Jusqu'à la v1.8, seul un appareil se déclarant « imprimante »
     * au sens standard (classe USB 7) était listé. Or les imprimantes de caisse —
     * l'Epson TM-m30II de Brooklyn en tête — se présentent souvent en classe
     * « propre au fabricant » (0xFF) : branchée en USB sur la tablette, l'Epson
     * n'apparaissait JAMAIS, et le staff lisait « aucune imprimante USB ».
     * ⇒ Un appareil de ces fabricants est accepté s'il offre une sortie de
     * données (« bulk OUT ») — c'est par elle que partent les octets ESC/POS.
     */
    private static final int[] FABRICANTS_CAISSE = {
            0x04B8, // Epson (TM-m30, TM-T20, TM-T88…)
            0x0519, // Star Micronics
            0x1D90, // Citizen
            0x2730, // Citizen
            0x1504, // Bixolon
            0x0619, // Seiko Instruments
            0x0416, // Winbond — nombreuses imprimantes génériques (Xprinter…)
            0x0483, // STMicro — idem
            0x0FE6, // ICS Advent — idem
            0x1FC9, // NXP — idem
            0x28E9, // GD32 — idem
    };

    private static boolean fabricantDeCaisse(UsbDevice d) {
        for (int v : FABRICANTS_CAISSE) if (d.getVendorId() == v) return true;
        return false;
    }

    private static boolean isPrinter(UsbDevice d) {
        if (d.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        }
        // v1.9 : une imprimante de caisse en classe « fabricant », si elle a une sortie.
        if (fabricantDeCaisse(d)) {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                if (bulkOut(d.getInterface(i)) != null) return true;
            }
        }
        return false;
    }

    /** Nom lisible, sans jamais lever (certaines versions d'Android exigent
     *  l'autorisation pour lire le nom du produit). */
    private static String nomUsb(UsbDevice d) {
        String n = null;
        try { n = d.getProductName(); } catch (Exception ignored) {}
        if (n != null && !n.trim().isEmpty()) return n.trim();
        if (d.getVendorId() == 0x04B8) return "Epson (USB)";
        if (d.getVendorId() == 0x0519) return "Star (USB)";
        return "USB " + d.getDeviceId();
    }

    /**
     * ⭐ v1.9 — CE QUE LA TABLETTE VOIT EN USB, sans rien filtrer.
     *
     * Pour comprendre À DISTANCE pourquoi une imprimante n'est pas listée :
     * le site l'envoie au journal des appareils (/dev/appareils) quand la liste
     * des imprimantes est vide. Format compact (le journal garde 300 caractères) :
     * « 04b8:0202 c0 [ff:bo] TM-m30II ; … » — identifiants, classe de l'appareil,
     * puis chaque interface (classe, « bo » = sortie bulk présente), puis le nom.
     */
    @JavascriptInterface
    public String diagnosticUsb() {
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) return "error:USB non disponible";
            StringBuilder sb = new StringBuilder();
            for (UsbDevice d : manager.getDeviceList().values()) {
                if (sb.length() > 0) sb.append(" ; ");
                sb.append(String.format("%04x:%04x c%d [", d.getVendorId(), d.getProductId(), d.getDeviceClass()));
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    UsbInterface it = d.getInterface(i);
                    if (i > 0) sb.append(",");
                    sb.append(Integer.toHexString(it.getInterfaceClass()));
                    if (bulkOut(it) != null) sb.append(":bo");
                }
                sb.append("] ").append(nomUsb(d));
            }
            return sb.length() == 0 ? "aucun appareil USB détecté (câble OTG ? prise de charge seule ?)" : sb.toString();
        } catch (Exception e) {
            return "error:" + msgOf(e);
        }
    }

    private static UsbDevice findUsb(UsbManager manager, int deviceId) {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getDeviceId() == deviceId) return d;
        }
        return null;
    }

    private static UsbInterface printerInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) return intf;
        }
        // v1.9 : sinon, la première interface qui a une SORTIE de données (classe
        // « fabricant » des imprimantes de caisse). L'ancienne règle prenait
        // aveuglément l'interface 0, qui peut ne pas en avoir.
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (bulkOut(intf) != null) return intf;
        }
        return d.getInterfaceCount() > 0 ? d.getInterface(0) : null;
    }

    private static UsbEndpoint bulkOut(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) return ep;
        }
        return null;
    }

    // ─────────────────── AJOUTS v1.6 ───────────────────

    /**
     * ⭐⭐ L'ADRESSE DE LA TABLETTE ELLE-MÊME.
     *
     * ⚠️⚠️ CE QUE ÇA RÉPARE, ET C'ÉTAIT CIRCULAIRE. L'écran de réglage déduisait
     * le sous-réseau à balayer… de l'adresse d'imprimante DÉJÀ SAISIE. Or on
     * lance une recherche précisément parce qu'on ne connaît pas cette adresse :
     * le champ est vide, et le code retombait sur « 192.168.1 » écrit en dur.
     * Chez un restaurant en 192.168.0.x ou en 10.0.0.x, la recherche ne trouvait
     * donc JAMAIS rien, et annonçait poliment « aucune imprimante trouvée ».
     *
     * ⇒ Le sous-réseau se déduit désormais de la tablette, qui est forcément sur
     * le même réseau que l'imprimante.
     *
     * @return l'IPv4 privée de la tablette, en JSON ("192.168.1.23"), ou une erreur
     */
    @JavascriptInterface
    public String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return "\"" + esc(a.getHostAddress()) + "\"";
                    }
                }
            }
            return "error:la tablette n'a pas d'adresse réseau (Wi-Fi coupé ?)";
        } catch (Exception e) {
            return "error:" + msgOf(e);
        }
    }

    /**
     * ⛔ LE PONT NE SORT PAS DU RÉSEAU LOCAL.
     *
     * ⚠️ POURQUOI (relecture de sécurité du 25/08). Ce pont est exposé en
     * JavaScript à la page chargée dans la WebView. Sans ce contrôle, un script
     * hostile — une faille du site, une publicité tierce, un lien détourné —
     * pourrait s'en servir pour écrire sur N'IMPORTE QUELLE adresse et en relire
     * le contenu, depuis le réseau du restaurant. Le risque est théorique
     * aujourd'hui (le site ne charge aucun script tiers), mais il coûte dix
     * lignes à fermer, et il devient réel au premier embarquement extérieur.
     *
     * ⭐ Corollaire assumé : une imprimante joignable seulement par une adresse
     * publique ne fonctionnera pas. Ça n'existe pas dans un restaurant, et le
     * refus est explicite plutôt que silencieux.
     *
     * @param hote une adresse complète ("192.168.1.47") ou un préfixe ("192.168.1")
     */
    private static boolean estReseauLocal(String hote) {
        if (hote == null) return false;
        String h = hote.trim();
        if (h.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) h = h + ".1"; // préfixe de balayage
        if (!h.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return false;
        String[] o = h.split("\\.");
        int a, b;
        try {
            a = Integer.parseInt(o[0]);
            b = Integer.parseInt(o[1]);
            for (String p : o) if (Integer.parseInt(p) > 255) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        if (a == 10) return true;                        // 10.0.0.0/8
        if (a == 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
        if (a == 192 && b == 168) return true;           // 192.168.0.0/16
        if (a == 169 && b == 254) return true;           // 169.254.0.0/16 (lien local)
        return false;
    }

    /** Le refus, écrit pour le staff et pas pour un journal technique. */
    private static String refusHorsReseauLocal(String hote) {
        return "error:adresse refusée (" + esc(String.valueOf(hote))
                + ") — seules les imprimantes du réseau local sont acceptées";
    }
}
