package andro.marchy;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.zip.GZIPInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * First launch: what Omarchy's setup form asks that still applies here (keyboard layouts, name and email; the
 * language and time zone come from Android), then the rootfs archive and extraction. The archive is either
 * downloaded from the release or a .tar.gz the user picks with the system file picker. SessionService then runs
 * first-boot.sh, which applies the answers (setup.conf).
 */
public class InstallActivity extends Activity {
    /** The rootfs built by build/build-rootfs.sh, published as a GitHub release asset: the online source. */
    static final String ROOTFS_URL = "https://github.com/lotfibennour/andromarchy/releases/latest/download/lotfix-rootfs.tar.gz";
    /** A copy in the app's external files dir (Android/data/andro.marchy/files/, no permission needed), or in files/
     *  (adb run-as, or a rootfs built on the device), is offered as the local archive without the file picker:
     *  offline installs and tests. */
    static final String LOCAL_NAME = "lotfix-rootfs.tar.gz";
    private static final long NEEDED_BYTES = 12L << 30; // archive + extracted rootfs, with room for apps
    private static final int PICK_ARCHIVE = 1; // onActivityResult request code

    // The local archive chosen with "Local archive" (a pre-staged copy or a picked file); null: ROOTFS_URL.
    // volatile: staged on the UI thread or a staging thread, used from the page's JavaScript bridge thread and the
    // install thread.
    private volatile Uri archive;
    private volatile String archiveName;
    private volatile long archiveBytes;
    private volatile boolean installing; // one extraction at a time (an Activity restart must not start a second)

    // {xkb layout, label}: the common ones; the first selected is the physical keyboard's.
    private static final String[][] LAYOUTS = {
            {"us", "English (US)"}, {"gb", "English (UK)"}, {"fr", "Français (AZERTY)"}, {"ara", "العربية"},
            {"de", "Deutsch"}, {"es", "Español"}, {"it", "Italiano"}, {"pt", "Português"}, {"br", "Português (BR)"},
            {"be", "Belgique"}, {"ch", "Suisse"}, {"ca", "Canada (FR)"}, {"nl", "Nederlands"}, {"tr", "Türkçe"},
            {"ru", "Русский"}, {"ua", "Українська"}, {"pl", "Polski"}, {"gr", "Ελληνικά"}, {"il", "עברית"},
            {"ir", "فارسی"}, {"jp", "日本語"}, {"kr", "한국어"}, {"cn", "中文"}, {"in", "हिन्दी"},
    };

    private WebView page;

    // The page (assets/install.html, design: "first launch.html" at the repo root) calls these, as "A".
    private final Object bridge = new Object() {
        /** [{id, label, on}]: Android's language's layout first and on, then US English (on). */
        @JavascriptInterface
        public String layouts() {
            String lang = Locale.getDefault().getLanguage();
            String suggested = lang.equals("ar") ? "ara" : lang.equals("en") ? "us" : lang.equals("uk") ? "ua"
                    : lang.equals("he") ? "il" : lang.equals("fa") ? "ir" : lang.equals("ja") ? "jp"
                    : lang.equals("ko") ? "kr" : lang.equals("zh") ? "cn" : lang.equals("hi") ? "in"
                    : lang.equals("el") ? "gr" : lang.equals("pt") && Locale.getDefault().getCountry().equals("BR") ? "br"
                    : lang;
            JSONArray out = new JSONArray();
            try {
                for (String[] l : LAYOUTS)
                    if (l[0].equals(suggested))
                        out.put(new JSONObject().put("id", l[0]).put("label", l[1]).put("on", true));
                for (String[] l : LAYOUTS)
                    if (!l[0].equals(suggested))
                        out.put(new JSONObject().put("id", l[0]).put("label", l[1]).put("on", l[0].equals("us")));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            return out.toString();
        }

        /** {name, size} of the local archive available now (a pre-staged copy or a picked file), or null. */
        @JavascriptInterface
        public String localArchive() {
            if (archive == null)
                return "null";
            try {
                return new JSONObject().put("name", archiveName).put("size", archiveBytes).toString();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }

        /** Opens the system file picker for the rootfs archive; the answer arrives as archiveChosen/...Failed. */
        @JavascriptInterface
        public void chooseArchive() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*"); // the providers report .tar.gz as gzip, tar, or just octet-stream
                pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/gzip", "application/x-gzip",
                        "application/x-compressed-tar", "application/x-tar", "application/octet-stream"});
                try {
                    startActivityForResult(pick, PICK_ARCHIVE);
                } catch (ActivityNotFoundException e) { // no document picker: the download stays available
                    call("archiveFailed", "this device has no file picker");
                }
            });
        }

        @JavascriptInterface
        public void install(String name, String email, String layouts, String source) {
            start(name, email, layouts, "local".equals(source));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { // the Activity was recreated (not for a rotation: see configChanges): keep the pick
            String uri = state.getString("archive");
            if (uri != null) {
                archive = Uri.parse(uri);
                archiveName = state.getString("archiveName", LOCAL_NAME);
                archiveBytes = state.getLong("archiveBytes", -1);
            }
        }
        if (archive == null)
            stageLocalCopy(); // a rootfs copy already on the device: no file picker needed for the local source
        page = new WebView(this);
        page.setBackgroundColor(Color.rgb(0x12, 0x13, 0x1d));
        page.getSettings().setJavaScriptEnabled(true);
        page.addJavascriptInterface(bridge, "A");
        page.loadUrl("file:///android_asset/install.html");
        setContentView(page);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (archive != null) {
            out.putString("archive", archive.toString());
            out.putString("archiveName", archiveName);
            out.putLong("archiveBytes", archiveBytes);
        }
    }

    /** Pre-stages a copy put there by adb or built on the device, so "Local archive" can use it directly. */
    private void stageLocalCopy() {
        File external = new File(getExternalFilesDir(null), LOCAL_NAME);
        File copy = external.canRead() ? external : new File(getFilesDir(), LOCAL_NAME);
        if (!copy.canRead())
            return;
        try {
            stage(Uri.fromFile(copy), LOCAL_NAME, copy.length());
        } catch (IOException ignored) { // not a gzip archive: the page just asks for another file
        }
    }

    /** Calls a function of the page with JSON-encoded arguments. */
    private void call(String fn, Object... args) {
        JSONArray a = new JSONArray();
        for (Object o : args)
            a.put(o);
        String js = fn + ".apply(null, " + a + ")";
        runOnUiThread(() -> page.evaluateJavascript(js, null));
    }

    private void start(String name, String email, String layouts, boolean local) {
        File files = getFilesDir();
        if (new StatFs(files.getPath()).getAvailableBytes() < NEEDED_BYTES) {
            call("failed", "not enough free space: about " + (NEEDED_BYTES >> 30) + " GB are needed");
            return;
        }
        if (local && archive == null) {
            call("failed", "choose the rootfs archive file first");
            return;
        }
        if (installing) { // the page was reloaded while an earlier extraction still runs
            call("failed", "an installation is already running");
            return;
        }
        installing = true;
        String conf = "KB_LAYOUTS=" + layouts.replaceAll("[^a-z,]", "") + "\nFULL_NAME=" + quote(name)
                + "\nEMAIL=" + quote(email) + "\n";
        new Thread(() -> {
            try {
                installRootfs(conf, local);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Throwable e) { // shown, never a crash: the user can retry
                call("failed", String.valueOf(e.getMessage()));
            } finally {
                installing = false;
            }
        }).start();
    }

    private static String quote(String s) { // setup.conf is sourced by bash
        return "'" + s.trim().replace("'", "'\\''") + "'";
    }

    private void installRootfs(String conf, boolean local) throws IOException {
        File rootfs = SessionService.rootfs(this);
        File partial = new File(rootfs.getPath() + ".partial");
        deleteTree(partial); // a previous attempt that stopped halfway
        final InputStream raw;
        final long total;
        if (local) {
            raw = openArchive(archive);
            total = archiveBytes;
        } else {
            HttpURLConnection c = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
            c.setInstanceFollowRedirects(true); // GitHub release assets redirect to their storage host
            c.connect();
            if (c.getResponseCode() != 200)
                throw new IOException("download: HTTP " + c.getResponseCode());
            raw = c.getInputStream();
            total = c.getContentLengthLong();
        }
        final long size = total;
        long[] read = {0};
        long[] shown = {0};
        try (InputStream stream = raw) { // closed even when the gzip header turns out to be corrupt
            InputStream counting = new FilterInputStream(new BufferedInputStream(stream, 1 << 20)) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0)
                        read[0] += n;
                    return n;
                }
            };
            try (InputStream in = new GZIPInputStream(counting, 1 << 16)) {
                TarExtractor.extract(in, partial, entry -> {
                    long now = System.currentTimeMillis();
                    if (now - shown[0] < 200)
                        return;
                    shown[0] = now;
                    String what = (local ? "Installing" : "Downloading and installing") + " ("
                            + (read[0] >> 20) + (size > 0 ? " / " + (size >> 20) : "") + " MB)\n" + entry;
                    call("progress", size > 0 ? read[0] * 1000 / size : -1, what);
                });
            }
        }
        // Any .tar.gz can be picked: only an Andromarchy rootfs has the loader first boot (and rootfsReady()) runs.
        if (!new File(partial, "opt/lotfix/arch-glibc/ld-linux-aarch64.so.1").exists()) {
            deleteTree(partial);
            throw new IOException("this archive is not an Andromarchy rootfs");
        }
        File setup = new File(partial, "root/.config/andromarchy/setup.conf");
        setup.getParentFile().mkdirs();
        if (!setup.getCanonicalPath().startsWith(partial.getCanonicalPath() + File.separator)) {
            deleteTree(partial);
            throw new IOException("the archive's setup path leaves the rootfs");
        }
        try (FileWriter w = new FileWriter(setup)) {
            w.write(conf);
        }
        if (!partial.renameTo(rootfs)) {
            // A stale tree from an older, unusable install cannot be renamed over. A working install is never replaced.
            if (SessionService.rootfsReady(this))
                throw new IOException("could not move the rootfs into place");
            deleteTree(rootfs);
            if (!partial.renameTo(rootfs))
                throw new IOException("could not move the rootfs into place");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != PICK_ARCHIVE) {
            super.onActivityResult(request, result, data);
            return;
        }
        Uri picked = result == RESULT_OK && data != null ? data.getData() : null;
        if (picked == null) {
            call("archiveCancelled");
            return;
        }
        try {
            // keeps the pick usable if the Activity is recreated (the page then gets it from localArchive())
            getContentResolver().takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { // a provider that only grants for this session
        }
        new Thread(() -> { // a cloud provider answers by IPC/network: never block the UI thread on it
            try {
                String name = LOCAL_NAME;
                long bytes = -1; // providers are free not to report a size: the progress then only counts up
                try (Cursor c = getContentResolver().query(picked,
                        new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameCol >= 0 && !c.isNull(nameCol))
                            name = c.getString(nameCol);
                        int sizeCol = c.getColumnIndex(OpenableColumns.SIZE);
                        if (sizeCol >= 0 && !c.isNull(sizeCol))
                            bytes = c.getLong(sizeCol);
                    }
                }
                stage(picked, name, bytes);
                call("archiveChosen", archiveName, archiveBytes);
            } catch (Throwable e) { // unreadable or not a .tar.gz: the page keeps its previous choice and says why
                call("archiveFailed", String.valueOf(e.getMessage()));
            }
        }).start();
    }

    /** Makes uri the local archive after checking its gzip magic, so a wrong file fails before extraction. */
    private void stage(Uri uri, String name, long bytes) throws IOException {
        try (InputStream in = openArchive(uri)) {
            if (in.read() != 0x1f || in.read() != 0x8b)
                throw new IOException("not a gzip archive (.tar.gz)");
        }
        archive = uri;
        archiveName = name;
        archiveBytes = bytes;
    }

    private InputStream openArchive(Uri uri) throws IOException {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()))
            return new FileInputStream(uri.getPath()); // the pre-staged copy in the app's own directories
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null)
            throw new IOException("could not open the file");
        return in;
    }

    private static void deleteTree(File f) {
        File[] children = f.isDirectory() && !isSymlink(f) ? f.listFiles() : null;
        if (children != null)
            for (File c : children)
                deleteTree(c);
        f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }
}
