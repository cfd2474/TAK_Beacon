package com.taksolutions.takbeacon.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.TAKServerListener;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakAuthenticationDatabase;
import com.atakmap.net.AtakCertificateDatabase;
import com.atakmap.net.AtakCertificateDatabaseIFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads ATAK's server connections, and builds the profile handed to TAK Beacon:
 * the server, the certificates ATAK uses for it, and the operator's identity.
 */
final class AtakServers {

    private AtakServers() {
    }

    static final class Server {
        final String description;
        final String host;
        final int port;
        final String proto;
        final boolean connected;

        Server(String description, String host, int port, String proto, boolean connected) {
            this.description = description;
            this.host = host;
            this.port = port;
            this.proto = proto;
            this.connected = connected;
        }

        String label() {
            final String name = description != null && !description.isEmpty() ? description : host;
            return name + "  (" + host + ":" + port + (connected ? ", connected)" : ")");
        }
    }

    /** ATAK's configured streaming connections, connected ones first. */
    static List<Server> list() {
        final List<Server> out = new ArrayList<>();
        final TAKServerListener l = TAKServerListener.getInstance();
        // getServers() returns null when there are no streams at all.
        final TAKServer[] servers = l == null ? null : l.getServers();
        if (servers == null)
            return out;
        for (TAKServer s : servers) {
            if (s == null)
                continue;
            final Server parsed = parse(s.getConnectString(), s.getDescription(), s.isConnected());
            if (parsed != null)
                out.add(parsed.connected ? 0 : out.size(), parsed);
        }
        return out;
    }

    /** "host:port:proto"; the host may itself contain colons (IPv6). */
    static Server parse(String connectString, String description, boolean connected) {
        if (connectString == null)
            return null;
        final int last = connectString.lastIndexOf(':');
        final int mid = last > 0 ? connectString.lastIndexOf(':', last - 1) : -1;
        if (mid <= 0)
            return null;
        try {
            final String host = connectString.substring(0, mid);
            final int port = Integer.parseInt(connectString.substring(mid + 1, last));
            final String proto = connectString.substring(last + 1);
            return new Server(description, host, port, proto, connected);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The bridge profile for [s]. Certificates are looked up the way ATAK itself
     * does: per server and port, then per server, then ATAK's default.
     */
    static Bundle profile(Server s, MapView mapView) {
        final Bundle b = new Bundle();
        b.putString(BridgeProtocol.KEY_SERVER_NAME,
                s.description != null && !s.description.isEmpty() ? s.description : s.host);
        b.putString(BridgeProtocol.KEY_HOST, s.host);
        b.putInt(BridgeProtocol.KEY_PORT, s.port);
        b.putString(BridgeProtocol.KEY_TRANSPORT, "tcp".equalsIgnoreCase(s.proto) ? "tcp" : "ssl");

        if (!"tcp".equalsIgnoreCase(s.proto)) {
            putCert(b, s, AtakCertificateDatabaseIFace.TYPE_TRUST_STORE_CA,
                    AtakAuthenticationCredentials.TYPE_caPassword,
                    BridgeProtocol.KEY_TRUST_P12, BridgeProtocol.KEY_TRUST_PASSWORD);
            putCert(b, s, AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE,
                    AtakAuthenticationCredentials.TYPE_clientPassword,
                    BridgeProtocol.KEY_CLIENT_P12, BridgeProtocol.KEY_CLIENT_PASSWORD);
        }

        if (mapView != null) {
            b.putString(BridgeProtocol.KEY_CALLSIGN, mapView.getDeviceCallsign());
            b.putString(BridgeProtocol.KEY_UID, MapView.getDeviceUid());
            final Context ctx = mapView.getContext();
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            b.putString(BridgeProtocol.KEY_TEAM, prefs.getString("locationTeam", "Cyan"));
            b.putString(BridgeProtocol.KEY_ROLE, prefs.getString("atakRoleType", "Team Member"));
        }
        return b;
    }

    static boolean hasClientCert(Bundle profile) {
        return profile.getByteArray(BridgeProtocol.KEY_CLIENT_P12) != null;
    }

    private static void putCert(Bundle b, Server s, String certType, String pwType,
            String certKey, String pwKey) {
        byte[] cert = AtakCertificateDatabase.getCertificateForServerAndPort(certType, s.host, s.port);
        AtakAuthenticationCredentials pw = AtakAuthenticationDatabase.getCredentials(pwType, s.host);
        if (cert == null)
            cert = AtakCertificateDatabase.getCertificateForServer(certType, s.host);
        if (cert == null) {
            cert = AtakCertificateDatabase.getCertificate(certType);
            pw = AtakAuthenticationDatabase.getCredentials(pwType);
        }
        if (cert == null)
            return;
        b.putByteArray(certKey, cert);
        if (pw != null && pw.password != null)
            b.putString(pwKey, pw.password);
    }
}
