package be.brooklynfood.staff;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⭐ v1.10 (15/10/2026) — LA RECHERCHE RÉSEAU QUI DONNE LES NOMS.
 *
 * Demande de l'exploitant : voir TOUS les appareils du réseau, pas seulement
 * ceux qui écoutent le port 9100, et lire leur NOM au lieu d'une liste
 * d'adresses. L'ancienne {@code scanNetwork} reste en place, inchangée : le
 * site s'en sert encore quand l'app est plus ancienne.
 *
 * ⚠️⚠️ ASYNCHRONE, EXPRÈS. Un appel du pont JavaScript bloque la page tant
 * qu'il ne rend pas la main. Trouver un nom prend du temps (une question par
 * appareil, chacune avec son délai) : on DÉMARRE ici, et le site relit l'état
 * toutes les demi-secondes — la liste se remplit sous ses yeux.
 *
 * Pour chaque adresse du sous-réseau :
 *  1. on frappe à quelques portes (impression directe 9100, LPD 515, IPP 631,
 *     pages web 80/443/8080). Une porte FERMÉE qui répond « refusé » prouve
 *     aussi que l'appareil existe — c'est ce qui fait apparaître un ordinateur
 *     ou une box qui n'imprime rien ;
 *  2. pour un appareil présent, on demande son nom, dans cet ordre :
 *     mDNS (le nom que l'appareil se donne : « EPSON TM-m30II-123456 ») ·
 *     SNMP (la description du modèle, que presque toutes les imprimantes
 *     donnent) · le titre de sa page web · le DNS du réseau.
 *
 * ⛔ Rien ne sort du réseau local : le sous-réseau est contrôlé par le pont
 * AVANT d'appeler {@link #demarrer} (même règle que l'impression).
 * ⚠️ L'adresse matérielle (MAC) n'est PAS lisible sur Android 10+ : la table
 * ARP est fermée aux applications. Le suivi d'imprimante s'appuie donc sur le
 * NOM ici ; le poste Windows, lui, sait lire la MAC.
 */
final class RechercheReseau {

    // La page web d'abord, puis l'impression directe : deux silences de suite = personne.
    private static final int[] PORTS = {80, 9100, 443, 515, 631, 8080};
    private static final int DELAI_PORT_MS = 350;
    private static final int DELAI_NOM_MS = 900;
    private static final int TOTAL = 254;

    private static final Object VERROU = new Object();
    private static boolean enCours = false;
    private static int faits = 0;
    private static final List<String> appareils = new ArrayList<>();

    private RechercheReseau() {}

    /** Démarre une recherche ; ne fait rien si une recherche tourne déjà. */
    static void demarrer(final String base) {
        synchronized (VERROU) {
            if (enCours) return;
            enCours = true;
            faits = 0;
            appareils.clear();
        }
        Thread chef = new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(48);
            try {
                for (int i = 1; i <= TOTAL; i++) {
                    final String ip = base + "." + i;
                    pool.execute(() -> {
                        try {
                            String json = sonder(ip);
                            if (json != null) synchronized (VERROU) { appareils.add(json); }
                        } catch (Throwable ignored) {
                            // une adresse qui pose problème ne doit pas arrêter les autres
                        } finally {
                            synchronized (VERROU) { faits++; }
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdownNow();
                synchronized (VERROU) { enCours = false; }
            }
        });
        chef.setDaemon(true);
        chef.start();
    }

    /** L'état, en JSON : {"running":…,"done":…,"total":254,"devices":[…]}. */
    static String resultats() {
        synchronized (VERROU) {
            StringBuilder sb = new StringBuilder("{\"running\":").append(enCours)
                    .append(",\"done\":").append(Math.min(faits, TOTAL))
                    .append(",\"total\":").append(TOTAL)
                    .append(",\"devices\":[");
            for (int i = 0; i < appareils.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(appareils.get(i));
            }
            return sb.append("]}").toString();
        }
    }

    // ───────────────────────── une adresse ─────────────────────────

    private static String sonder(String ip) {
        List<Integer> ouverts = new ArrayList<>();
        boolean present = false;
        int silences = 0;
        for (int port : PORTS) {
            int r = frapper(ip, port);
            if (r == 1) { ouverts.add(port); present = true; }
            else if (r == 0) present = true; // « refusé » : quelqu'un a répondu
            else if (!present && ++silences >= 2) return null; // personne : on passe
        }
        if (!present) return null;

        String nom = null, source = null, texte = "";
        String m = nomMdns(ip);
        if (m != null) { nom = m; source = "mdns"; }
        String[] snmp = snmp(ip);
        if (snmp != null) {
            texte = snmp[0];
            if (nom == null) {
                String d = courte(snmp[0]);
                if (d != null) { nom = d; source = "snmp"; }
                else if (snmp[1] != null && !snmp[1].isEmpty()) { nom = snmp[1]; source = "snmp"; }
            }
        }
        String macPage = null;
        if (ouverts.contains(80)) {
            macPage = macDeLaPage(ip);
            String titre = titreWeb(ip);
            if (titre != null) {
                texte = texte + " " + titre;
                if (nom == null) { nom = titre; source = "web"; }
            }
        }
        if (nom == null) {
            String d = nomDns(ip);
            if (d != null) { nom = d; source = "dns"; }
        }

        boolean imprimante = ouverts.contains(9100) || ouverts.contains(515) || ouverts.contains(631);
        StringBuilder sb = new StringBuilder("{\"ip\":\"").append(ip).append("\",\"ports\":[");
        for (int i = 0; i < ouverts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ouverts.get(i));
        }
        sb.append(']');
        if (nom != null) {
            sb.append(",\"name\":\"").append(esc(nom)).append("\",\"nameSource\":\"").append(source).append('"');
        }
        String vendeur = (texte + " " + (nom != null ? nom : "")).trim();
        if (!vendeur.isEmpty()) sb.append(",\"vendor\":\"").append(esc(borner(vendeur, 160))).append('"');
        if (macPage != null) sb.append(",\"mac\":\"").append(macPage).append('"');
        return sb.append(",\"printer\":").append(imprimante).append('}').toString();
    }

    /** 1 = ouvert · 0 = refusé (l'appareil existe) · -1 = silence. */
    private static int frapper(String ip, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), DELAI_PORT_MS);
            return 1;
        } catch (SocketTimeoutException e) {
            return -1;
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            return m.contains("refused") ? 0 : -1;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    // ───────────────────────── mDNS (question directe) ─────────────────────────

    /**
     * Demande à l'appareil lui-même « quel est ton nom ? » (requête PTR sur son
     * adresse inversée, envoyée à SON port 5353). Les appareils Bonjour — la
     * plupart des imprimantes Epson, Star, Brother, HP — répondent directement.
     */
    private static String nomMdns(String ip) {
        DatagramSocket s = null;
        try {
            String[] o = ip.split("\\.");
            String inverse = o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
            byte[] q = requeteDns(inverse, 12);
            s = new DatagramSocket();
            s.setSoTimeout(DELAI_NOM_MS);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 5353));
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            String nom = premierPtr(buf, p.getLength());
            if (nom == null) return null;
            if (nom.endsWith(".")) nom = nom.substring(0, nom.length() - 1);
            if (nom.toLowerCase(Locale.ROOT).endsWith(".local")) nom = nom.substring(0, nom.length() - 6);
            return propre(nom);
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    private static byte[] requeteDns(String nom, int type) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int id = (int) (Math.random() * 65535);
        b.write(id >> 8); b.write(id & 0xff);
        b.write(0); b.write(0);   // drapeaux : simple question
        b.write(0); b.write(1);   // 1 question
        for (int i = 0; i < 6; i++) b.write(0);
        for (String l : nom.split("\\.")) {
            byte[] e = l.getBytes(StandardCharsets.US_ASCII);
            b.write(e.length);
            b.write(e, 0, e.length);
        }
        b.write(0);
        b.write(type >> 8); b.write(type & 0xff);
        b.write(0); b.write(1);   // classe IN
        return b.toByteArray();
    }

    /** Le premier enregistrement PTR d'une réponse DNS, ou null. */
    private static String premierPtr(byte[] d, int n) {
        if (n < 12) return null;
        int questions = ((d[4] & 0xff) << 8) | (d[5] & 0xff);
        int reponses = ((d[6] & 0xff) << 8) | (d[7] & 0xff);
        int p = 12;
        for (int i = 0; i < questions; i++) { p = sauterNom(d, p, n); if (p < 0) return null; p += 4; }
        for (int i = 0; i < reponses && p < n; i++) {
            p = sauterNom(d, p, n);
            if (p < 0 || p + 10 > n) return null;
            int type = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
            int lg = ((d[p + 8] & 0xff) << 8) | (d[p + 9] & 0xff);
            p += 10;
            if (type == 12) return lireNom(d, p, n, 0);
            p += lg;
        }
        return null;
    }

    private static int sauterNom(byte[] d, int p, int n) {
        while (p < n) {
            int l = d[p] & 0xff;
            if (l == 0) return p + 1;
            if ((l & 0xc0) == 0xc0) return p + 2;
            p += l + 1;
        }
        return -1;
    }

    private static String lireNom(byte[] d, int p, int n, int profondeur) {
        if (profondeur > 8) return null;
        StringBuilder sb = new StringBuilder();
        while (p < n) {
            int l = d[p] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) {
                if (p + 1 >= n) return null;
                String reste = lireNom(d, ((l & 0x3f) << 8) | (d[p + 1] & 0xff), n, profondeur + 1);
                if (reste == null) return null;
                if (sb.length() > 0) sb.append('.');
                return sb.append(reste).toString();
            }
            if (p + 1 + l > n) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, p + 1, l, StandardCharsets.UTF_8));
            p += l + 1;
        }
        return sb.toString();
    }

    // ───────────────────────── SNMP (description du modèle) ─────────────────────────

    /** Requête SNMPv1 « public » : sysDescr.0 et sysName.0. */
    private static final byte[] REQUETE_SNMP = {
            0x30, 0x35, 0x02, 0x01, 0x00, 0x04, 0x06, 'p', 'u', 'b', 'l', 'i', 'c',
            (byte) 0xa0, 0x28, 0x02, 0x02, 0x10, 0x01, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00,
            0x30, 0x1c,
            0x30, 0x0c, 0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00, 0x05, 0x00,
            0x30, 0x0c, 0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x05, 0x00, 0x05, 0x00,
    };

    /** {description, nom} ou null. Lecture minimale : les chaînes de la réponse, dans l'ordre. */
    private static String[] snmp(String ip) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(DELAI_NOM_MS);
            s.send(new DatagramPacket(REQUETE_SNMP, REQUETE_SNMP.length, InetAddress.getByName(ip), 161));
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            List<String> chaines = new ArrayList<>();
            chainesBer(buf, 0, p.getLength(), chaines, 0);
            // [0] = « public » (la communauté), puis sysDescr, puis sysName.
            if (chaines.size() < 2) return null;
            String desc = propre(chaines.get(1));
            String nom = chaines.size() > 2 ? propre(chaines.get(2)) : null;
            if (desc == null && nom == null) return null;
            return new String[] {desc != null ? desc : "", nom};
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    private static void chainesBer(byte[] d, int p, int fin, List<String> out, int profondeur) {
        if (profondeur > 6) return;
        while (p + 2 <= fin) {
            int type = d[p] & 0xff;
            int lg = d[p + 1] & 0xff;
            int debut = p + 2;
            if ((lg & 0x80) != 0) {
                int k = lg & 0x7f;
                if (k < 1 || k > 2 || debut + k > fin) return;
                lg = 0;
                for (int i = 0; i < k; i++) lg = (lg << 8) | (d[debut + i] & 0xff);
                debut += k;
            }
            if (debut + lg > fin) return;
            if (type == 0x30 || (type >= 0xa0 && type <= 0xa4)) {
                chainesBer(d, debut, debut + lg, out, profondeur + 1);
            } else if (type == 0x04) {
                out.add(new String(d, debut, lg, StandardCharsets.UTF_8));
            }
            p = debut + lg;
        }
    }

    // ───────────────────────── page web, DNS ─────────────────────────

    private static final Pattern TITRE = Pattern.compile("<title[^>]*>([^<]{1,120})</title>", Pattern.CASE_INSENSITIVE);

    private static String titreWeb(String ip) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + "/").openConnection();
            c.setConnectTimeout(DELAI_NOM_MS);
            c.setReadTimeout(DELAI_NOM_MS);
            c.setInstanceFollowRedirects(true);
            byte[] buf = new byte[8192];
            int n = 0;
            try (InputStream in = c.getInputStream()) {
                int r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            }
            Matcher m = TITRE.matcher(new String(buf, 0, n, StandardCharsets.UTF_8));
            return m.find() ? propre(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String nomDns(String ip) {
        try {
            String n = InetAddress.getByName(ip).getCanonicalHostName();
            if (n == null || n.equals(ip)) return null;
            int point = n.indexOf('.');
            return propre(point > 0 ? n.substring(0, point) : n);
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── v1.11 : la MAC écrite par l'imprimante ─────────────────────────

    /**
     * ⭐ v1.11 (17/10/2026) — L'ADRESSE MATÉRIELLE QUE L'IMPRIMANTE ÉCRIT ELLE-MÊME
     * sur sa page de réglages. Android interdit aux applications de lire la
     * table ARP : c'est le seul moyen, pour une tablette, de reconnaître une
     * imprimante SANS NOM dont la box a changé l'adresse (vu sur l'imprimante
     * achetée pour Krusty Koekelberg, module « J-Speed » : page /ip_info.htm).
     * ⚠️ Ces petits serveurs répondent en HTTP/0.9 (aucun en-tête) : la lecture
     * passe par une simple connexion, pas par HttpURLConnection, qui les refuse.
     * Rien trouvé = rien rendu : jamais une MAC devinée.
     */
    private static String macDeLaPage(String ip) {
        // Ces petits serveurs ratent une requête sur deux juste après un redémarrage : un 2e essai.
        String accueil = lirePage(ip, "/");
        if (accueil == null || accueil.length() < 300) accueil = lirePage(ip, "/");
        String mac = premiereMac(accueil);
        if (mac == null && accueil != null && accueil.contains("ip_info.htm")) mac = premiereMac(lirePage(ip, "/ip_info.htm"));
        return mac;
    }

    /** Une page, lue en HTTP/1.0 brut, 32 Ko au plus. */
    private static String lirePage(String ip, String chemin) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, 80), DELAI_PORT_MS * 2);
            s.setSoTimeout(DELAI_NOM_MS * 2);
            s.getOutputStream().write(("GET " + chemin + " HTTP/1.0\r\nHost: " + ip + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            byte[] buf = new byte[32768];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return null;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * La première adresse matérielle d'un texte (« 00-61-A3-48-24-8E » ou
     * « 00:61:a3:48:24:8e »), rendue en minuscules avec des « : ». Écrit sans
     * expression régulière, exprès : un motif à antislashs s'abîme en passant
     * par les outils qui écrivent ce fichier.
     */
    static String premiereMac(String t) {
        if (t == null) return null;
        for (int i = 0; i + 17 <= t.length(); i++) {
            if (i > 0 && hexa(t.charAt(i - 1))) continue;
            boolean ok = true;
            char sep = t.charAt(i + 2);
            if (sep != '-' && sep != ':') continue;
            for (int k = 0; k < 6 && ok; k++) {
                int p = i + k * 3;
                ok = hexa(t.charAt(p)) && hexa(t.charAt(p + 1)) && (k == 5 || t.charAt(p + 2) == sep);
            }
            if (!ok || (i + 17 < t.length() && hexa(t.charAt(i + 17)))) continue;
            String mac = t.substring(i, i + 17).replace('-', ':').toLowerCase(Locale.ROOT);
            if (mac.equals("00:00:00:00:00:00") || mac.equals("ff:ff:ff:ff:ff:ff")) continue;
            return mac;
        }
        return null;
    }

    private static boolean hexa(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ───────────────────────── outils ─────────────────────────

    /** Une description courte et lisible, sinon null (les « HP ETHERNET MULTI-ENVIRONMENT, ROM … »). */
    private static String courte(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 60) return null;
        return t;
    }

    /** Sans caractères de contrôle, espaces compactés, borné ; null si vide. */
    private static String propre(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(ch < 0x20 || ch == 0x7f ? ' ' : ch);
        }
        String t = sb.toString().replaceAll("\\s+", " ").trim();
        // « localhost » : un appareil qui ne sait pas son nom ne nous l’apprend pas.
        if (t.isEmpty() || t.equalsIgnoreCase("localhost")) return null;
        return borner(t, 80);
    }

    private static String borner(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
