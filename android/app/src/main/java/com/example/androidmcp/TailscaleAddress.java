package com.example.androidmcp;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Finds an address already assigned by Tailscale; never returns wildcard/loopback. */
public final class TailscaleAddress {
    private TailscaleAddress() { }

    public static Inet4Address find(Context context) throws IOException {
        List<Inet4Address> candidates = new ArrayList<>();
        ConnectivityManager connectivity = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            throw new IOException("No connectivity service");
        }
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                continue;
            }
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null) {
                continue;
            }
            for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address
                        && SecurityValidators.isTailscaleIpv4(address.getHostAddress())) {
                    candidates.add((Inet4Address) address);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException("No Tailscale IPv4 address assigned");
        }
        if (candidates.size() != 1) throw new IOException("Ambiguous VPN addresses");
        return candidates.get(0);
    }
}
