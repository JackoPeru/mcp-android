package com.example.androidmcp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;

/** Extra device observation: foreground app and network status. */
public final class DeviceStateController {
    private DeviceStateController() { }

    public static JSONObject currentApp(Context context) throws ApiException {
        McpAccessibilityService service = McpAccessibilityService.active();
        if (service == null) {
            throw new ApiException("ACCESSIBILITY_DISABLED", "Accessibility service is disabled");
        }
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) {
                return new JSONObject().put("packageName", "").put("known", false);
            }
            String packageName = String.valueOf(root.getPackageName());
            JSONObject result = new JSONObject();
            result.put("packageName", packageName);
            result.put("ownApp", context.getPackageName().equals(packageName));
            result.put("known", true);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode app state");
        } finally {
            if (root != null) root.recycle();
        }
    }

    public static JSONObject networkStatus(Context context) throws ApiException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new ApiException("NETWORK_UNAVAILABLE", "Connectivity service unavailable");
        try {
            JSONArray networks = new JSONArray();
            boolean metered = false;
            boolean vpn = false;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null) continue;
                JSONObject item = new JSONObject();
                item.put("wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                item.put("cellular", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                item.put("ethernet", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                item.put("vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                item.put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                item.put("metered", !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                item.put("downstreamKbps", capabilities.getLinkDownstreamBandwidthKbps());
                item.put("upstreamKbps", capabilities.getLinkUpstreamBandwidthKbps());
                LinkProperties link = manager.getLinkProperties(network);
                if (link != null) {
                    JSONArray addresses = new JSONArray();
                    for (LinkAddress address : link.getLinkAddresses()) {
                        if (address.getAddress() instanceof Inet4Address) {
                            addresses.put(address.getAddress().getHostAddress());
                        }
                    }
                    item.put("ipv4", addresses);
                    item.put("dns", new JSONArray(link.getDnsServers()));
                    item.put("interface", link.getInterfaceName() == null ? "" : link.getInterfaceName());
                }
                metered = metered || item.optBoolean("metered");
                vpn = vpn || item.optBoolean("vpn");
                networks.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("networks", networks);
            result.put("count", networks.length());
            result.put("metered", metered);
            result.put("vpn", vpn);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode network status");
        }
    }
}
