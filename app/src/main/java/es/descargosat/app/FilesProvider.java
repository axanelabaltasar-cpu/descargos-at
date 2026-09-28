package es.descargosat.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Proveedor mínimo para compartir ficheros de la caché de la app
 * (expedientes exportados y fotos de la cámara) con otras aplicaciones.
 * Solo da acceso a las carpetas cache/share y cache/cam.
 */
public class FilesProvider extends ContentProvider {

    static String authority(Context c) {
        return c.getPackageName() + ".files";
    }

    static Uri uriFor(Context c, String relPath) {
        return new Uri.Builder().scheme("content").authority(authority(c)).path(relPath).build();
    }

    static File fileFor(Context c, Uri uri) {
        String p = uri.getPath() == null ? "" : uri.getPath();
        return new File(c.getCacheDir(), p);
    }

    private File safeFile(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) throw new FileNotFoundException();
        try {
            File f = fileFor(c, uri).getCanonicalFile();
            String share = new File(c.getCacheDir(), "share").getCanonicalPath() + File.separator;
            String cam = new File(c.getCacheDir(), "cam").getCanonicalPath() + File.separator;
            String fp = f.getPath();
            if (!fp.startsWith(share) && !fp.startsWith(cam)) throw new FileNotFoundException("Ruta no permitida");
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = safeFile(uri);
        if (mode.contains("w")) {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = safeFile(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor mc = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        mc.addRow(row);
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
