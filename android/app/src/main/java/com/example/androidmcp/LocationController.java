package com.example.androidmcp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One-shot GPS/network location read. No background tracking, no geocoding. */
public final class LocationController {
    private LocationController() { }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static JSONObject get(Context context, long timeoutMs, boolean highAccuracy) throws ApiException {
        if (!hasPermission(context)) {
            throw new ApiException("LOCATION_PERMISSION_DENIED", "Grant the location permission in MCP Android");
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) throw new ApiException("LOCATION_UNAVAILABLE", "Location service unavailable");
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        String provider = highAccuracy && fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) {
            throw new ApiException("LOCATION_DISABLED", "Enable location services on the phone");
        }
        Location last = lastKnown(manager, provider);
        if (last != null && System.currentTimeMillis() - last.getTime() < 120_000) {
            return toJson(last, provider, true);
        }
        AtomicReference<Location> fresh = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try {
            manager.getCurrentLocation(provider, null, context.getMainExecutor(), location -> {
                fresh.set(location);
                latch.countDown();
            });
        } catch (SecurityException e) {
            throw new ApiException("LOCATION_PERMISSION_DENIED", "Location permission revoked");
        } catch (RuntimeException e) {
            throw new ApiException("LOCATION_UNAVAILABLE", "Unable to request a location");
        }
        try {
            latch.await(Math.max(1000, Math.min(timeoutMs, 12_000)), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Location location = fresh.get();
        if (location == null) {
            if (last != null) return toJson(last, provider, false);
            throw new ApiException("LOCATION_TIMEOUT", "No fix acquired; try again outdoors or with network location on");
        }
        return toJson(location, provider, true);
    }

    // Permission is checked by get(); SecurityException is handled here too
    // because the grant can be revoked between check and call.
    @SuppressLint("MissingPermission")
    private static Location lastKnown(LocationManager manager, String provider) {
        try {
            return manager.getLastKnownLocation(provider);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JSONObject toJson(Location location, String provider, boolean fresh) throws ApiException {
        try {
            JSONObject result = new JSONObject();
            result.put("latitude", location.getLatitude());
            result.put("longitude", location.getLongitude());
            result.put("accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            result.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
            result.put("speedMps", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
            result.put("provider", provider);
            result.put("time", location.getTime());
            result.put("fresh", fresh);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode location");
        }
    }
}
