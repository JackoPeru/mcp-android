package com.example.androidmcp;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** File operations rooted exclusively in persisted user-selected SAF grants. */
public final class SafFileStore {
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final int MAX_PATH_SCAN = 100_000;
    private static final int MAX_SEARCH_SCAN = 10_000;
    private final ContentResolver resolver;
    private final FileRootStore roots;

    public SafFileStore(android.content.Context context, FileRootStore roots) {
        resolver = context.getApplicationContext().getContentResolver();
        this.roots = roots;
    }

    public JSONObject list(String rootId, String path, long offset, int limit) throws ApiException {
        requirePath(path);
        if (!SecurityValidators.isValidPage(offset, limit)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid page");
        }
        DocumentInfo directory = resolve(rootId, path);
        if (!directory.isDirectory()) {
            throw new ApiException("NOT_A_DIRECTORY", "Path is not a directory");
        }
        JSONArray entries = new JSONArray();
        boolean hasMore = false;
        long index = 0;
        try (Cursor cursor = children(directory.treeUri, directory.uri, rootId)) {
            while (cursor.moveToNext()) {
                RequestScope.checkCurrent();
                if (index++ < offset) {
                    continue;
                }
                if (entries.length() >= limit) {
                    hasMore = true;
                    break;
                }
                entries.put(toJson(readInfo(cursor, directory.uri, directory.treeUri)));
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ioError(e);
        }
        JSONObject result = new JSONObject();
        try {
            result.put("entries", entries);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("eof", !hasMore);
            if (hasMore && offset <= Long.MAX_VALUE - entries.length()) {
                result.put("nextOffset", offset + entries.length());
            } else {
                result.put("nextOffset", JSONObject.NULL);
            }
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file list");
        }
        return result;
    }

    public JSONObject stat(String rootId, String path) throws ApiException {
        requirePath(path);
        JSONObject result = toJson(resolve(rootId, path));
        try {
            result.put("writableRoot", roots.canWrite(rootId));
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file metadata");
        }
        return result;
    }

    public JSONObject read(String rootId, String path, long offset, long length) throws ApiException {
        requirePath(path);
        if (!SecurityValidators.isValidReadRange(offset, length) || length < 1) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid read range");
        }
        DocumentInfo file = resolve(rootId, path);
        if (file.isDirectory()) {
            throw new ApiException("NOT_A_FILE", "Path is a directory");
        }
        byte[] data = new byte[(int) length];
        int bytesRead = 0;
        boolean eof = false;
        ParcelFileDescriptor descriptor;
        try {
            RequestScope scope = RequestScope.CURRENT.get();
            descriptor = resolver.openFileDescriptor(file.uri, "r", scope == null ? null : scope.signal());
        } catch (SecurityException e) {
            throw new ApiException("ROOT_REVOKED", "Root permission revoked");
        } catch (IOException e) {
            throw new ApiException("READ_FAILED", "File could not be opened");
        }
        if (descriptor == null) {
            throw new ApiException("READ_FAILED", "File could not be opened");
        }
        RequestScope scope = RequestScope.CURRENT.get();
        if (scope != null) scope.attach(descriptor);
        try (ParcelFileDescriptor.AutoCloseInputStream input
                     = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            FileChannel channel = input.getChannel();
            try {
                channel.position(offset);
            } catch (IOException unsupportedSeek) {
                if (offset > 0) {
                    throw new ApiException("SEEK_UNSUPPORTED", "Provider does not support offsets");
                }
            }
            while (!eof && bytesRead < data.length) {
                RequestScope.checkCurrent();
                int count;
                try {
                    count = input.read(data, bytesRead, data.length - bytesRead);
                } catch (IOException e) {
                    throw new ApiException("READ_FAILED", "File read failed");
                }
                if (count < 0) {
                    eof = true;
                } else if (count == 0) {
                    // Avoid a provider returning an endless empty read.
                    int one = input.read();
                    if (one < 0) {
                        eof = true;
                    } else {
                        data[bytesRead++] = (byte) one;
                    }
                } else {
                    bytesRead += count;
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("READ_FAILED", "File read failed");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("data", Base64.encodeToString(data, 0, bytesRead, Base64.NO_WRAP));
            result.put("bytesRead", bytesRead);
            result.put("eof", eof);
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file data");
        }
        return result;
    }

    public JSONObject search(String rootId, String basePath, String query, int maxDepth, int limit)
            throws ApiException {
        requirePath(basePath);
        if (query == null || query.length() > 255 || maxDepth < 0 || maxDepth > 16
                || limit < 1 || limit > SecurityValidators.MAX_SEARCH_RESULTS) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid search");
        }
        DocumentInfo base = resolve(rootId, basePath);
        if (!base.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Search base is not a directory");
        String needle = query.toLowerCase(Locale.ROOT);
        JSONArray matches = new JSONArray();
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        queue.add(new SearchNode(base, basePath, 0));
        int scanned = 0;
        boolean truncated = false;
        while (!queue.isEmpty() && matches.length() < limit) {
            SearchNode parent = queue.removeFirst();
            if (parent.depth >= maxDepth) continue;
            try (Cursor cursor = children(parent.info.treeUri, parent.info.uri, rootId)) {
                while (cursor.moveToNext()) {
                    RequestScope.checkCurrent();
                    if (++scanned > MAX_SEARCH_SCAN) {
                        truncated = true;
                        queue.clear();
                        break;
                    }
                    DocumentInfo child = readInfo(cursor, parent.info.uri, parent.info.treeUri);
                    String childPath = parent.path.isEmpty() ? child.name : parent.path + "/" + child.name;
                    if (needle.isEmpty() || child.name.toLowerCase(Locale.ROOT).contains(needle)) {
                        JSONObject entry = toJson(child);
                        try { entry.put("path", childPath); }
                        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode search"); }
                        matches.put(entry);
                        if (matches.length() >= limit) {
                            truncated = true;
                            break;
                        }
                    }
                    if (child.isDirectory() && parent.depth + 1 < maxDepth) {
                        queue.addLast(new SearchNode(child, childPath, parent.depth + 1));
                    }
                }
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                throw ioError(e);
            }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("matches", matches);
            result.put("scanned", scanned);
            result.put("truncated", truncated || !queue.isEmpty());
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode search");
        }
    }

    public JSONObject write(String rootId, String path, String base64, long offset,
                            boolean truncate, String mimeType) throws ApiException {
        requirePath(path);
        if (path.isEmpty() || base64 == null || base64.length() > 400_000
                || mimeType == null || mimeType.isEmpty() || mimeType.length() > 200) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid write");
        }
        byte[] data;
        try { data = Base64.decode(base64, Base64.DEFAULT); }
        catch (IllegalArgumentException e) { throw new ApiException("INVALID_ARGUMENT", "Invalid base64"); }
        if (!SecurityValidators.isValidWriteRange(offset, data.length) || (truncate && offset != 0)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid write range");
        }
        roots.requireWriteUri(rootId);
        DocumentInfo file = tryResolve(rootId, path);
        boolean created = false;
        if (file == null) {
            ParentAndName target = parentAndName(rootId, path);
            Uri createdUri;
            try {
                createdUri = DocumentsContract.createDocument(resolver, target.parent.uri, mimeType, target.name);
            } catch (java.io.FileNotFoundException e) {
                throw new ApiException("WRITE_FAILED", "Provider refused file creation");
            } catch (RuntimeException e) {
                throw ioError(e);
            }
            if (createdUri == null) throw new ApiException("WRITE_FAILED", "Provider refused file creation");
            file = readDocument(createdUri, "", rootId, target.parent.treeUri);
            created = true;
            truncate = true;
        }
        if (file.isDirectory()) throw new ApiException("NOT_A_FILE", "Path is a directory");
        ParcelFileDescriptor descriptor;
        try {
            RequestScope scope = RequestScope.CURRENT.get();
            descriptor = resolver.openFileDescriptor(file.uri, truncate ? "rwt" : "rw",
                    scope == null ? null : scope.signal());
        } catch (SecurityException e) {
            throw new ApiException("ROOT_READ_ONLY", "Root does not allow writing");
        } catch (IOException e) {
            throw new ApiException("WRITE_FAILED", "File could not be opened for writing");
        }
        if (descriptor == null) throw new ApiException("WRITE_FAILED", "File could not be opened for writing");
        RequestScope scope = RequestScope.CURRENT.get();
        if (scope != null) scope.attach(descriptor);
        try (ParcelFileDescriptor.AutoCloseOutputStream output
                     = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            FileChannel channel = output.getChannel();
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                RequestScope.checkCurrent();
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new ApiException("WRITE_FAILED", "File write failed");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("created", created);
            result.put("bytesWritten", data.length);
            result.put("offset", offset);
            return result;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode write result");
        }
    }

    public JSONObject mkdir(String rootId, String path) throws ApiException {
        requirePath(path);
        if (path.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "Cannot create root");
        roots.requireWriteUri(rootId);
        if (tryResolve(rootId, path) != null) throw new ApiException("ALREADY_EXISTS", "Path already exists");
        ParentAndName target = parentAndName(rootId, path);
        Uri uri;
        try {
            uri = DocumentsContract.createDocument(resolver, target.parent.uri,
                    DocumentsContract.Document.MIME_TYPE_DIR, target.name);
        } catch (java.io.FileNotFoundException e) {
            throw new ApiException("WRITE_FAILED", "Provider refused directory creation");
        } catch (RuntimeException e) { throw ioError(e); }
        if (uri == null) throw new ApiException("WRITE_FAILED", "Provider refused directory creation");
        return okResult();
    }

    public JSONObject rename(String rootId, String path, String newName) throws ApiException {
        requirePath(path);
        if (path.isEmpty() || !SecurityValidators.isValidFileName(newName))
            throw new ApiException("INVALID_ARGUMENT", "Invalid rename");
        roots.requireWriteUri(rootId);
        DocumentInfo source = resolve(rootId, path);
        Uri renamed;
        try { renamed = DocumentsContract.renameDocument(resolver, source.uri, newName); }
        catch (java.io.FileNotFoundException e) { throw new ApiException("WRITE_FAILED", "Provider refused rename"); }
        catch (RuntimeException e) { throw ioError(e); }
        if (renamed == null) throw new ApiException("WRITE_FAILED", "Provider refused rename");
        return okResult();
    }

    public JSONObject move(String rootId, String path, String targetDirectory) throws ApiException {
        requirePath(path);
        requirePath(targetDirectory);
        if (path.isEmpty() || targetDirectory.equals(path)
                || (!path.isEmpty() && targetDirectory.startsWith(path + "/"))) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid move target");
        }
        roots.requireWriteUri(rootId);
        DocumentInfo source = resolve(rootId, path);
        ParentAndName sourceParent = parentAndName(rootId, path);
        DocumentInfo target = resolve(rootId, targetDirectory);
        if (!target.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Move target is not a directory");
        Uri moved;
        try {
            moved = DocumentsContract.moveDocument(resolver, source.uri, sourceParent.parent.uri, target.uri);
        } catch (java.io.FileNotFoundException e) {
            throw new ApiException("WRITE_FAILED", "Provider refused move");
        } catch (UnsupportedOperationException e) {
            throw new ApiException("OPERATION_UNSUPPORTED", "Provider does not support move");
        } catch (RuntimeException e) { throw ioError(e); }
        if (moved == null) throw new ApiException("WRITE_FAILED", "Provider refused move");
        return okResult();
    }

    public JSONObject copy(String rootId, String path, String targetDirectory) throws ApiException {
        requirePath(path);
        requirePath(targetDirectory);
        if (path.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "Cannot copy root");
        roots.requireWriteUri(rootId);
        DocumentInfo source = resolve(rootId, path);
        DocumentInfo target = resolve(rootId, targetDirectory);
        if (!target.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Copy target is not a directory");
        Uri copied;
        try { copied = DocumentsContract.copyDocument(resolver, source.uri, target.uri); }
        catch (java.io.FileNotFoundException e) { throw new ApiException("WRITE_FAILED", "Provider refused copy"); }
        catch (UnsupportedOperationException e) {
            throw new ApiException("OPERATION_UNSUPPORTED", "Provider does not support copy");
        } catch (RuntimeException e) { throw ioError(e); }
        if (copied == null) throw new ApiException("WRITE_FAILED", "Provider refused copy");
        return okResult();
    }

    public JSONObject delete(String rootId, String path) throws ApiException {
        requirePath(path);
        if (path.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "Cannot delete authorized root");
        roots.requireWriteUri(rootId);
        DocumentInfo source = resolve(rootId, path);
        boolean deleted;
        try { deleted = DocumentsContract.deleteDocument(resolver, source.uri); }
        catch (java.io.FileNotFoundException e) { throw new ApiException("WRITE_FAILED", "Provider refused delete"); }
        catch (RuntimeException e) { throw ioError(e); }
        if (!deleted) throw new ApiException("WRITE_FAILED", "Provider refused delete");
        return okResult();
    }

    private DocumentInfo resolve(String rootId, String path) throws ApiException {
        Uri tree = roots.requireUri(rootId);
        final String rootDocumentId;
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            throw new ApiException("ROOT_REVOKED", "Root permission revoked");
        }
        Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocumentId);
        DocumentInfo info = readDocument(current, rootDocumentId, rootId, tree);
        if (path.isEmpty()) {
            return info;
        }
        for (String segment : path.split("/", -1)) {
            DocumentInfo next = null;
            int scanned = 0;
            try (Cursor cursor = children(tree, current, rootId)) {
                while (cursor.moveToNext()) {
                    RequestScope.checkCurrent();
                    if (++scanned > MAX_PATH_SCAN) {
                        throw new ApiException("DIRECTORY_TOO_LARGE", "Directory traversal limit reached");
                    }
                    DocumentInfo candidate = readInfo(cursor, current, tree);
                    if (segment.equals(candidate.name)) {
                        next = candidate;
                        break;
                    }
                }
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                throw ioError(e);
            }
            if (next == null) {
                throw new ApiException("NOT_FOUND", "Path not found");
            }
            current = next.uri;
            info = next;
        }
        return info;
    }

    private DocumentInfo tryResolve(String rootId, String path) throws ApiException {
        try { return resolve(rootId, path); }
        catch (ApiException e) {
            if ("NOT_FOUND".equals(e.code)) return null;
            throw e;
        }
    }

    private ParentAndName parentAndName(String rootId, String path) throws ApiException {
        requirePath(path);
        int slash = path.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : path.substring(0, slash);
        String name = slash < 0 ? path : path.substring(slash + 1);
        if (!SecurityValidators.isValidFileName(name)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid file name");
        }
        DocumentInfo parent = resolve(rootId, parentPath);
        if (!parent.isDirectory()) throw new ApiException("NOT_A_DIRECTORY", "Parent is not a directory");
        return new ParentAndName(parent, name);
    }

    private Cursor children(Uri tree, Uri parent, String rootId) throws ApiException {
        final String documentId;
        try {
            documentId = DocumentsContract.getDocumentId(parent);
        } catch (IllegalArgumentException e) {
            throw new ApiException("ROOT_REVOKED", "Root permission revoked");
        }
        try {
            Cursor cursor = resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId),
                    COLUMNS, null, null, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE",
                    RequestScope.CURRENT.get() == null ? null : RequestScope.CURRENT.get().signal());
            if (cursor == null) {
                throw new ApiException("FILE_IO", "Provider returned no directory");
            }
            return cursor;
        } catch (SecurityException e) {
            throw new ApiException("ROOT_REVOKED", "Root permission revoked");
        } catch (RuntimeException e) {
            throw ioError(e);
        }
    }

    private DocumentInfo readDocument(Uri documentUri, String documentId, String rootId, Uri treeUri)
            throws ApiException {
        try (Cursor cursor = resolver.query(documentUri, COLUMNS, null, null, null,
                RequestScope.CURRENT.get() == null ? null : RequestScope.CURRENT.get().signal())) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new ApiException("NOT_FOUND", "Path not found");
            }
            return readInfo(cursor, documentUri, treeUri);
        } catch (SecurityException e) {
            throw new ApiException("ROOT_REVOKED", "Root permission revoked");
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ioError(e);
        }
    }

    private DocumentInfo readInfo(Cursor cursor, Uri parentUri, Uri treeUri) {
        String documentId = cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID));
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
        int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
        int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
        String name = nameIndex >= 0 && !cursor.isNull(nameIndex) ? cursor.getString(nameIndex) : "";
        String mime = mimeIndex >= 0 && !cursor.isNull(mimeIndex) ? cursor.getString(mimeIndex) : "";
        long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex) ? cursor.getLong(sizeIndex) : -1;
        long modified = modifiedIndex >= 0 && !cursor.isNull(modifiedIndex) ? cursor.getLong(modifiedIndex) : 0;
        return new DocumentInfo(name, mime, size, modified, uri, treeUri);
    }

    private JSONObject toJson(DocumentInfo info) throws ApiException {
        JSONObject object = new JSONObject();
        try {
            object.put("name", info.name);
            object.put("mimeType", info.mimeType);
            object.put("size", info.size);
            object.put("lastModified", info.lastModified);
            object.put("isDirectory", info.isDirectory());
            return object;
        } catch (JSONException e) {
            throw new ApiException("INTERNAL", "Unable to encode file metadata");
        }
    }

    private static JSONObject okResult() throws ApiException {
        JSONObject result = new JSONObject();
        try { result.put("ok", true); return result; }
        catch (JSONException e) { throw new ApiException("INTERNAL", "Unable to encode result"); }
    }

    private static void requirePath(String path) throws ApiException {
        if (!SecurityValidators.isValidRelativePath(path)) {
            throw new ApiException("INVALID_ARGUMENT", "Invalid relative path");
        }
    }

    private static ApiException ioError(RuntimeException e) {
        if (e instanceof SecurityException) {
            return new ApiException("ROOT_REVOKED", "Root permission revoked");
        }
        return new ApiException("FILE_IO", "Storage provider error");
    }

    private static final class DocumentInfo {
        final String name;
        final String mimeType;
        final long size;
        final long lastModified;
        final Uri uri;
        final Uri treeUri;

        DocumentInfo(String name, String mimeType, long size, long lastModified, Uri uri, Uri treeUri) {
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.lastModified = lastModified;
            this.uri = uri;
            this.treeUri = treeUri;
        }

        boolean isDirectory() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        }
    }

    private static final class ParentAndName {
        final DocumentInfo parent;
        final String name;
        ParentAndName(DocumentInfo parent, String name) {
            this.parent = parent;
            this.name = name;
        }
    }

    private static final class SearchNode {
        final DocumentInfo info;
        final String path;
        final int depth;
        SearchNode(DocumentInfo info, String path, int depth) {
            this.info = info;
            this.path = path;
            this.depth = depth;
        }
    }
}
