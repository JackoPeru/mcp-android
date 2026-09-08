package com.example.androidmcp;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Strict JSON object access: no string coercion, unknown keys, or unbounded values. */
public final class JsonArgs {
    private JsonArgs() { }

    public static void only(JSONObject object, String... allowed) throws ApiException {
        Set<String> names = new HashSet<>();
        for (String name : allowed) {
            names.add(name);
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!names.contains(key)) {
                throw new ApiException("INVALID_ARGUMENT", "Unknown parameter");
            }
        }
    }

    public static String requiredString(JSONObject object, String key, int maxLength) throws ApiException {
        return requiredString(object, key, maxLength, false);
    }

    public static String requiredStringAllowEmpty(JSONObject object, String key, int maxLength)
            throws ApiException {
        return requiredString(object, key, maxLength, true);
    }

    private static String requiredString(JSONObject object, String key, int maxLength, boolean allowEmpty)
            throws ApiException {
        Object value = value(object, key);
        if (!(value instanceof String) || (!allowEmpty && ((String) value).isEmpty())
                || ((String) value).length() > maxLength) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        }
        return (String) value;
    }

    public static String optionalString(JSONObject object, String key, String fallback, int maxLength)
            throws ApiException {
        if (!object.has(key)) {
            return fallback;
        }
        return requiredString(object, key, maxLength);
    }

    public static String optionalStringAllowEmpty(JSONObject object, String key, String fallback, int maxLength)
            throws ApiException {
        if (!object.has(key)) {
            return fallback;
        }
        return requiredStringAllowEmpty(object, key, maxLength);
    }

    public static long requiredLong(JSONObject object, String key) throws ApiException {
        Object value = value(object, key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        }
        return ((Number) value).longValue();
    }

    public static long optionalLong(JSONObject object, String key, long fallback) throws ApiException {
        return object.has(key) ? requiredLong(object, key) : fallback;
    }

    public static double requiredDouble(JSONObject object, String key) throws ApiException {
        Object value = value(object, key);
        if (!(value instanceof Number)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        }
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result)) throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        return result;
    }

    public static double optionalDouble(JSONObject object, String key, double fallback) throws ApiException {
        return object.has(key) ? requiredDouble(object, key) : fallback;
    }

    public static boolean requiredBoolean(JSONObject object, String key) throws ApiException {
        Object value = value(object, key);
        if (!(value instanceof Boolean)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        }
        return (Boolean) value;
    }

    public static boolean optionalBoolean(JSONObject object, String key, boolean fallback) throws ApiException {
        return object.has(key) ? requiredBoolean(object, key) : fallback;
    }

    public static JSONObject requiredObject(JSONObject object, String key) throws ApiException {
        Object value = value(object, key);
        if (!(value instanceof JSONObject)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        }
        return (JSONObject) value;
    }

    public static JSONObject optionalObject(JSONObject object, String key) throws ApiException {
        if (!object.has(key)) return new JSONObject();
        return requiredObject(object, key);
    }

    public static JSONArray requiredArray(JSONObject object, String key) throws ApiException {
        Object value = value(object, key);
        if (!(value instanceof JSONArray)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
        }
        return (JSONArray) value;
    }

    private static Object value(JSONObject object, String key) throws ApiException {
        try {
            Object value = object.get(key);
            if (value == JSONObject.NULL) {
                throw new ApiException("INVALID_ARGUMENT", "Invalid parameter");
            }
            return value;
        } catch (JSONException e) {
            throw new ApiException("INVALID_ARGUMENT", "Missing parameter");
        }
    }
}
