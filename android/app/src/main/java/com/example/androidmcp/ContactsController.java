package com.example.androidmcp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only contact search by name or number. */
public final class ContactsController {
    private ContactsController() { }

    static boolean canRead(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    public static JSONObject search(Context context, String query, int limit) throws ApiException {
        if (!canRead(context)) {
            throw new ApiException("CONTACTS_PERMISSION_DENIED",
                    "Grant the contacts permission in MCP Android");
        }
        String term = query == null ? "" : query.trim();
        if (term.length() > 100) throw new ApiException("INVALID_ARGUMENT", "Invalid query");
        int max = Math.max(1, Math.min(limit, 50));
        String like = "%" + term + "%";
        String selection = term.isEmpty() ? null
                : ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR "
                + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
        String[] args = term.isEmpty() ? null : new String[]{like, like};
        JSONArray contacts = new JSONArray();
        try (android.database.Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                },
                selection, args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor == null) throw new ApiException("CONTACTS_UNAVAILABLE", "Contacts provider unavailable");
            int count = 0;
            while (cursor.moveToNext() && count < max) {
                contacts.put(new JSONObject()
                        .put("contactId", cursor.getLong(0))
                        .put("name", cursor.getString(1) == null ? "" : cursor.getString(1))
                        .put("number", cursor.getString(2) == null ? "" : cursor.getString(2))
                        .put("type", ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                context.getResources(),
                                cursor.getInt(3),
                                "").toString()));
                count++;
            }
            return new JSONObject()
                    .put("contacts", contacts)
                    .put("count", contacts.length())
                    .put("untrusted", true);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode contacts");
        } catch (SecurityException e) {
            throw new ApiException("CONTACTS_PERMISSION_DENIED", "Contacts permission revoked");
        }
    }
}
