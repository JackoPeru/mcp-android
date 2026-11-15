package com.example.androidmcp;

import android.content.Context;
import android.content.Intent;

import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord;
import androidx.health.connect.client.records.DistanceRecord;
import androidx.health.connect.client.records.ExerciseSessionRecord;
import androidx.health.connect.client.records.HeartRateRecord;
import androidx.health.connect.client.records.HeightRecord;
import androidx.health.connect.client.records.OxygenSaturationRecord;
import androidx.health.connect.client.records.Record;
import androidx.health.connect.client.records.RestingHeartRateRecord;
import androidx.health.connect.client.records.SleepSessionRecord;
import androidx.health.connect.client.records.StepsRecord;
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord;
import androidx.health.connect.client.records.Vo2MaxRecord;
import androidx.health.connect.client.records.WeightRecord;
import androidx.health.connect.client.request.ReadRecordsRequest;
import androidx.health.connect.client.response.ReadRecordsResponse;
import androidx.health.connect.client.time.TimeRangeFilter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;

/**
 * Read-only Health Connect access with per-type permissions.
 *
 * <p>Nothing is written or deleted. Every query is bounded in time and size,
 * and data is never logged: TraceJournal stores metadata only. Devices without
 * Health Connect get an explicit unavailable status instead of empty results.
 */
public final class HealthController {
    /** type name -> Record class. Keep in sync with healthTypes() and READ in bridge. */
    private static final Map<String, KClass<? extends Record>> TYPES = new LinkedHashMap<>();
    private static final List<String> ORDER = new ArrayList<>();

    static {
        put("steps", StepsRecord.class);
        put("heart_rate", HeartRateRecord.class);
        put("resting_heart_rate", RestingHeartRateRecord.class);
        put("sleep", SleepSessionRecord.class);
        put("exercise", ExerciseSessionRecord.class);
        put("distance", DistanceRecord.class);
        put("total_calories", TotalCaloriesBurnedRecord.class);
        put("active_calories", ActiveCaloriesBurnedRecord.class);
        put("oxygen_saturation", OxygenSaturationRecord.class);
        put("weight", WeightRecord.class);
        put("height", HeightRecord.class);
        put("vo2max", Vo2MaxRecord.class);
    }

    private static void put(String name, Class<? extends Record> cls) {
        TYPES.put(name, JvmClassMappingKt.getKotlinClass(cls));
        ORDER.add(name);
    }

    private HealthController() { }

    public static List<String> supportedTypes() { return Collections.unmodifiableList(ORDER); }

    public static JSONObject status(Context context) throws ApiException {
        try {
            int sdk = HealthConnectClient.Companion.sdkStatus(context);
            JSONObject result = new JSONObject();
            result.put("types", new JSONArray(ORDER));
            if (sdk == HealthConnectClient.Companion.SDK_UNAVAILABLE) {
                result.put("available", false);
                result.put("reason", "health_connect_not_installed");
                result.put("granted", new JSONArray());
                return result;
            }
            if (sdk == HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                result.put("available", false);
                result.put("reason", "health_connect_update_required");
                result.put("granted", new JSONArray());
                return result;
            }
            HealthConnectClient client = HealthConnectClient.getOrCreate(context);
            Set<String> granted = awaitSuspend(continuation ->
                    client.getPermissionController().getGrantedPermissions(continuation));
            JSONArray grantedTypes = new JSONArray();
            for (String type : ORDER) {
                if (granted.contains(readPermission(type))) grantedTypes.put(type);
            }
            result.put("available", true);
            result.put("mode", "read-only");
            result.put("granted", grantedTypes);
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("HEALTH_UNAVAILABLE", "Health Connect unavailable");
        }
    }

    public static JSONObject read(Context context, String type, String startIso, String endIso,
                                  int limit) throws ApiException {
        if (!TYPES.containsKey(type)) {
            throw new ApiException("INVALID_ARGUMENT", "Unsupported health type: " + type);
        }
        Instant start = parseInstant(startIso, Instant.now().minus(Duration.ofDays(1)));
        Instant end = parseInstant(endIso, Instant.now());
        if (!start.isBefore(end)) throw new ApiException("INVALID_ARGUMENT", "Invalid time range");
        if (Duration.between(start, end).toDays() > 366) {
            throw new ApiException("INVALID_ARGUMENT", "Range too large (max 366 days)");
        }
        int max = Math.max(1, Math.min(limit, 1000));
        HealthConnectClient client = requireClient(context);
        requireGranted(client, type);
        List<? extends Record> records = readRecords(client, TYPES.get(type), start, end, max);
        JSONArray samples = new JSONArray();
        for (Record record : records) {
            try {
                samples.put(toJson(record));
            } catch (JSONException e) {
                throw new ApiException("INTERNAL", "Unable to encode health record");
            }
        }
        try {
            return new JSONObject()
                    .put("type", type)
                    .put("start", start.toString())
                    .put("end", end.toString())
                    .put("samples", samples)
                    .put("count", samples.length())
                    .put("readOnly", true)
                    .put("untrusted", true);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode health result");
        }
    }

    /**
     * Aggregation computed on-device over the same bounded read set, so results
     * are identical to what the samples show (no hidden server aggregation).
     */
    public static JSONObject aggregate(Context context, String type, String startIso, String endIso,
                                       int limit) throws ApiException {
        JSONObject raw = read(context, type, startIso, endIso, Math.min(limit, 5000));
        try {
            JSONArray samples = raw.getJSONArray("samples");
            JSONObject result = new JSONObject();
            double total = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE, count = 0;
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                String field = NUMERIC_FIELDS.get(type);
                if (field == null || !sample.has(field)) continue;
                double value = sample.getDouble(field);
                total += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                count++;
            }
            result.put("type", type);
            result.put("count", samples.length());
            result.put("total", total);
            result.put("avg", count > 0 ? total / count : 0);
            result.put("min", count > 0 ? min : 0);
            result.put("max", count > 0 ? max : 0);
            result.put("unit", UNITS.get(type));
            result.put("readOnly", true);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode aggregate");
        }
    }

    private static final Map<String, String> NUMERIC_FIELDS = new HashMap<>();
    private static final Map<String, String> UNITS = new HashMap<>();
    static {
        NUMERIC_FIELDS.put("steps", "count");
        NUMERIC_FIELDS.put("heart_rate", "bpm");
        NUMERIC_FIELDS.put("resting_heart_rate", "bpm");
        NUMERIC_FIELDS.put("sleep", "durationMinutes");
        NUMERIC_FIELDS.put("exercise", "durationMinutes");
        NUMERIC_FIELDS.put("distance", "meters");
        NUMERIC_FIELDS.put("total_calories", "kilocalories");
        NUMERIC_FIELDS.put("active_calories", "kilocalories");
        NUMERIC_FIELDS.put("oxygen_saturation", "percent");
        NUMERIC_FIELDS.put("weight", "kilograms");
        NUMERIC_FIELDS.put("height", "meters");
        NUMERIC_FIELDS.put("vo2max", "mlPerMinPerKg");
        UNITS.put("steps", "steps");
        UNITS.put("heart_rate", "bpm");
        UNITS.put("resting_heart_rate", "bpm");
        UNITS.put("sleep", "minutes");
        UNITS.put("exercise", "minutes");
        UNITS.put("distance", "meters");
        UNITS.put("total_calories", "kcal");
        UNITS.put("active_calories", "kcal");
        UNITS.put("oxygen_saturation", "percent");
        UNITS.put("weight", "kg");
        UNITS.put("height", "m");
        UNITS.put("vo2max", "ml/kg/min");
    }

    public static String readPermission(String type) {
        KClass<? extends Record> cls = TYPES.get(type);
        if (cls == null) throw new IllegalArgumentException("Unsupported health type");
        return HealthPermission.getReadPermission(cls);
    }

    public static JSONObject requestPermissions(Context context, List<String> types) throws ApiException {
        List<String> requested = new ArrayList<>();
        for (String type : types) {
            if (!TYPES.containsKey(type)) throw new ApiException("INVALID_ARGUMENT", "Unsupported health type: " + type);
            requested.add(readPermission(type));
        }
        if (requested.isEmpty()) {
            for (String type : ORDER) requested.add(readPermission(type));
        }
        try {
            context.startActivity(new Intent(context, HealthConnectActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("permissions", requested.toArray(new String[0])));
        } catch (RuntimeException e) {
            throw new ApiException("HEALTH_UNAVAILABLE", "Unable to open the Health Connect screen");
        }
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("requested", new JSONArray(requested))
                    .put("state", "awaiting_user_approval")
                    .put("note", "Check health_status after the operator approves");
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode result");
        }
    }

    private static HealthConnectClient requireClient(Context context) throws ApiException {
        int sdk = HealthConnectClient.Companion.sdkStatus(context);
        if (sdk == HealthConnectClient.Companion.SDK_UNAVAILABLE) {
            throw new ApiException("HEALTH_UNAVAILABLE", "Health Connect is not installed");
        }
        if (sdk == HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            throw new ApiException("HEALTH_UPDATE_REQUIRED", "Health Connect needs an update");
        }
        try {
            return HealthConnectClient.getOrCreate(context);
        } catch (RuntimeException e) {
            throw new ApiException("HEALTH_UNAVAILABLE", "Health Connect unavailable");
        }
    }

    private static void requireGranted(HealthConnectClient client, String type) throws ApiException {
        try {
            Set<String> granted = awaitSuspend(continuation ->
                    client.getPermissionController().getGrantedPermissions(continuation));
            if (!granted.contains(readPermission(type))) {
                throw new ApiException("HEALTH_PERMISSION_DENIED",
                        "Grant read permission for " + type + " in Health Connect");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("HEALTH_UNAVAILABLE", "Unable to read Health Connect permissions");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> List<T> readRecords(HealthConnectClient client,
                                                          KClass<? extends Record> type,
                                                          Instant start, Instant end, int limit)
            throws ApiException {
        try {
            ReadRecordsRequest<? extends Record> request = new ReadRecordsRequest<>(
                    (KClass) type, TimeRangeFilter.between(start, end),
                    Collections.emptySet(), false, limit, null);
            ReadRecordsResponse<? extends Record> response = awaitSuspend(continuation ->
                    client.readRecords((ReadRecordsRequest) request, continuation));
            return (List<T>) response.getRecords();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("HEALTH_READ_FAILED", "Health Connect read failed");
        }
    }

    private static JSONObject toJson(Record record) throws JSONException {
        JSONObject item = new JSONObject();
        if (record instanceof StepsRecord) {
            StepsRecord value = (StepsRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            item.put("count", value.getCount());
        } else if (record instanceof HeartRateRecord) {
            HeartRateRecord value = (HeartRateRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            JSONArray samples = new JSONArray();
            for (HeartRateRecord.Sample sample : value.getSamples()) {
                samples.put(new JSONObject()
                        .put("time", sample.getTime().toString())
                        .put("bpm", sample.getBeatsPerMinute()));
            }
            item.put("samples", samples);
        } else if (record instanceof SleepSessionRecord) {
            SleepSessionRecord value = (SleepSessionRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            item.put("durationMinutes", Duration.between(
                    value.getStartTime(), value.getEndTime()).toMinutes());
            item.put("title", value.getTitle() == null ? "" : value.getTitle());
        } else if (record instanceof ExerciseSessionRecord) {
            ExerciseSessionRecord value = (ExerciseSessionRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            item.put("durationMinutes", Duration.between(
                    value.getStartTime(), value.getEndTime()).toMinutes());
            item.put("exerciseType", value.getExerciseType());
            item.put("title", value.getTitle() == null ? "" : value.getTitle());
        } else if (record instanceof DistanceRecord) {
            DistanceRecord value = (DistanceRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            item.put("meters", value.getDistance().getMeters());
        } else if (record instanceof TotalCaloriesBurnedRecord) {
            TotalCaloriesBurnedRecord value = (TotalCaloriesBurnedRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            item.put("kilocalories", value.getEnergy().getKilocalories());
        } else if (record instanceof ActiveCaloriesBurnedRecord) {
            ActiveCaloriesBurnedRecord value = (ActiveCaloriesBurnedRecord) record;
            item.put("startTime", value.getStartTime().toString());
            item.put("endTime", value.getEndTime().toString());
            item.put("kilocalories", value.getEnergy().getKilocalories());
        } else if (record instanceof OxygenSaturationRecord) {
            OxygenSaturationRecord value = (OxygenSaturationRecord) record;
            item.put("time", value.getTime().toString());
            item.put("percent", value.getPercentage().getValue());
        } else if (record instanceof RestingHeartRateRecord) {
            RestingHeartRateRecord value = (RestingHeartRateRecord) record;
            item.put("time", value.getTime().toString());
            item.put("bpm", value.getBeatsPerMinute());
        } else if (record instanceof WeightRecord) {
            WeightRecord value = (WeightRecord) record;
            item.put("time", value.getTime().toString());
            item.put("kilograms", value.getWeight().getKilograms());
        } else if (record instanceof HeightRecord) {
            HeightRecord value = (HeightRecord) record;
            item.put("time", value.getTime().toString());
            item.put("meters", value.getHeight().getMeters());
        } else if (record instanceof Vo2MaxRecord) {
            Vo2MaxRecord value = (Vo2MaxRecord) record;
            item.put("time", value.getTime().toString());
            item.put("mlPerMinPerKg", value.getVo2MillilitersPerMinuteKilogram());
        }
        return item;
    }

    private static Instant parseInstant(String value, Instant fallback) throws ApiException {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new ApiException("INVALID_ARGUMENT", "Use ISO-8601 instants, e.g. 2026-01-31T00:00:00Z");
        }
    }

    // ---- Kotlin suspend bridge: bounded, single-shot continuations ----------

    /**
     * Calls a Kotlin suspend function from Java with a bounded wait. Health
     * Connect's API is suspend-only; this keeps the RPC thread synchronous
     * without pulling a coroutine runtime into the app.
     */
    @SuppressWarnings("unchecked")
    static <T> T awaitSuspend(SuspendCall<T> call) throws Exception {
        ResultContinuation<T> continuation = new ResultContinuation<>();
        Object immediate = call.call(continuation);
        if (immediate != kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
            return (T) immediate;
        }
        return continuation.await(20_000);
    }

    interface SuspendCall<T> {
        Object call(Continuation<? super T> continuation) throws Exception;
    }

    static final class ResultContinuation<T> implements Continuation<T> {
        private boolean done;
        private T value;
        private Throwable error;

        @Override public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }

        @Override public void resumeWith(Object result) {
            synchronized (this) {
                try {
                    kotlin.ResultKt.throwOnFailure(result);
                    @SuppressWarnings("unchecked")
                    T typed = (T) result;
                    value = typed;
                } catch (Throwable failure) {
                    error = failure;
                }
                done = true;
                notifyAll();
            }
        }

        T await(long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (this) {
                while (!done) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) throw new ApiException("HEALTH_TIMEOUT", "Health Connect call timed out");
                    wait(Math.min(remaining, 500));
                }
                if (error != null) {
                    if (error instanceof Exception) throw (Exception) error;
                    throw new ApiException("HEALTH_READ_FAILED", "Health Connect call failed");
                }
                return value;
            }
        }
    }
}
