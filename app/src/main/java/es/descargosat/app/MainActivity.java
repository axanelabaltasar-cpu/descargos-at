package es.descargosat.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Descargos AT · contenedor nativo de la aplicación web.
 * La app completa va dentro del APK (assets/www) y se sirve desde un origen
 * https local, de modo que funciona sin conexión y sin alojamiento.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 11;
    private static final int REQ_PERMS = 12;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private boolean pageReady = false;
    private final List<String> pendingImports = new ArrayList<>();

    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("webmanifest", "application/manifest+json");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("txt", "text/plain");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new LocalClient());
        web.setWebChromeClient(new Chrome());

        askPermissions();
        handleIncoming(getIntent());

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ---------------- permisos ---------------- */

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        String[] wanted = {Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private boolean hasLocation() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCamera() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        }
    }

    /* ---------------- servidor local de la app ---------------- */

    private class LocalClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
            if (path.contains("..")) return notFound();
            try {
                InputStream in = getAssets().open("www" + path);
                String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
                String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
                WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", in);
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
                return r;
            } catch (IOException e) {
                return notFound();
            }
        }

        private WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<String, String>(),
                    new java.io.ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) return true;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException e) {
                toast("No hay ninguna aplicación para abrir este enlace");
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            deliverImports();
        }
    }

    /* ---------------- cámara, galería, ficheros y ubicación ---------------- */

    private class Chrome extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (hasLocation()) {
                callback.invoke(origin, true, false);
            } else if (Build.VERSION.SDK_INT >= 23) {
                geoCallback = callback;
                geoOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERMS);
            } else {
                callback.invoke(origin, false, false);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            cameraUri = null;

            boolean images = false;
            String[] types = params.getAcceptTypes();
            if (types != null) {
                for (String t : types) if (t != null && t.startsWith("image")) images = true;
            }
            boolean multiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;

            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType(images ? "image/*" : "*/*");
            if (multiple) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            Intent chooser;
            if (images) {
                chooser = Intent.createChooser(pick, "Añadir foto");
                if (hasCamera()) {
                    try {
                        File dir = new File(getCacheDir(), "cam");
                        dir.mkdirs();
                        File f = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
                        cameraUri = FilesProvider.uriFor(MainActivity.this, "cam/" + f.getName());
                        Intent cam = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                        cam.setClipData(ClipData.newRawUri("", cameraUri));
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{cam});
                    } catch (Exception e) {
                        cameraUri = null;
                    }
                }
            } else {
                chooser = Intent.createChooser(pick, "Importar expediente");
            }
            try {
                startActivityForResult(chooser, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("No se puede abrir el selector de ficheros");
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE || fileCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0
                    && !(cameraUri != null && cameraUri.equals(data.getClipData().getItemAt(0).getUri()))) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraUri != null && FilesProvider.fileFor(this, cameraUri).length() > 0) {
                result = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    /* ---------------- recibir expedientes compartidos con la app ---------------- */

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            else if (intent.getStringExtra(Intent.EXTRA_TEXT) != null) addPending(intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        for (Uri u : uris) {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                addPending(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                toast("No se pudo leer el fichero recibido");
            }
        }
        intent.setAction(Intent.ACTION_MAIN);
        deliverImports();
    }

    private void addPending(String text) {
        synchronized (pendingImports) {
            pendingImports.add(text);
        }
    }

    private void deliverImports() {
        boolean any;
        synchronized (pendingImports) {
            any = !pendingImports.isEmpty();
        }
        if (pageReady && any) {
            web.evaluateJavascript("window.onAndroidImport && window.onAndroidImport()", null);
        }
    }

    /* ---------------- puente JavaScript ---------------- */

    private class Bridge {
        @JavascriptInterface
        public String takePendingImports() {
            JSONArray a = new JSONArray();
            synchronized (pendingImports) {
                for (String s : pendingImports) a.put(s);
                pendingImports.clear();
            }
            return a.toString();
        }

        @JavascriptInterface
        public void shareFile(final String name, final String content, final String message) {
            try {
                File dir = new File(getCacheDir(), "share");
                dir.mkdirs();
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                File f = new File(dir, safe);
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(content.getBytes(StandardCharsets.UTF_8));
                }
                final Uri uri = FilesProvider.uriFor(MainActivity.this, "share/" + safe);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("application/json");
                        send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_SUBJECT, name);
                        send.putExtra(Intent.EXTRA_TEXT, message);
                        send.setClipData(ClipData.newRawUri("", uri));
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "Compartir expediente"));
                    }
                });
            } catch (Exception e) {
                toast("No se pudo preparar el fichero para compartir");
            }
        }

        @JavascriptInterface
        public void print(final String title) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(title);
                    pm.print(title, adapter, new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
