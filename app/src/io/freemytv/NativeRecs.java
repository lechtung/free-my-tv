package io.freemytv;

import io.freemytv.Recs.Pick;
import io.freemytv.Recs.Listener;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-published Android TV previews and Watch Next, restricted to the current shelf. */
final class NativeRecs {

    private static final String READ = "android.permission.READ_TV_LISTINGS";
    private static final long REFRESH_MS = 60_000L;
    // Bound the row and its artwork queue even with a large shelf.
    private static final int MAX_PER_APP = 50, MAX_PICKS = 200;
    private static NativeRecs instance;

    static {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    static synchronized NativeRecs get(Context c) {
        if (instance == null) instance = new NativeRecs(c.getApplicationContext());
        return instance;
    }

    private static final class Shelf {
        final Set<String> packages;
        final List<String> choice;

        Shelf(List<String> packages, List<String> choice) {
            this.packages = new HashSet<>(packages);
            this.choice = choice;
        }
    }

    private static SSLSocketFactory trustAllSocketFactory;

    static {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            trustAllSocketFactory = sc.getSocketFactory();
            HttpsURLConnection.setDefaultSSLSocketFactory(trustAllSocketFactory);
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Throwable t) {
            AppLog.w("Global SSL init: " + t.getMessage());
        }
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkIo = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> imageFailedAt = new HashMap<>();
    private volatile Shelf shelf = new Shelf(Collections.emptyList(), null);
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile List<Pick> watchNextPicks = new ArrayList<>();
    private volatile Map<String, List<Pick>> picksByApp = new HashMap<>();
    private volatile Set<String> requestedArtwork;
    private volatile boolean fetching, dirty = true, light;
    private volatile long fetchedAt;
    private volatile String error = "";
    private String shelfStamp = "", languageStamp = "";
    private final Runnable refreshPublished = this::refreshIfStale;

    private NativeRecs(Context c) {
        context = c;
        ContentObserver observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                dirty = true;
                main.removeCallbacks(refreshPublished);
                main.postDelayed(refreshPublished, 1000); // publishers often update a channel one row at a time
            }
        };
        try {
            c.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
            c.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
        } catch (RuntimeException e) {
            // Some TV firmwares restrict observers; the periodic refresh still works.
            AppLog.w("recommendation observer unavailable");
        }
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { invalidate(); }
        }, packages);
    }

    static boolean canRead(Context c) { return c.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED; }
    boolean hasPicks() { return !picks.isEmpty() || !watchNextPicks.isEmpty(); }
    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    void invalidate() { dirty = true; refreshIfStale(); }

    static JSONObject status(Context c) {
        try (FileInputStream in = new FileInputStream(new File(c.getFilesDir(), "app-recs-status.json"))) {
            byte[] bytes = new byte[8192];
            int n = in.read(bytes);
            return n <= 0 ? new JSONObject() : new JSONObject(new String(bytes, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    private void writeStatus(Map<String, Integer> counts, int watchNextCount) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "app-recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("watchNext", watchNextCount).put("apps", new JSONObject(counts))
                    .put("error", error).put("at", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** Package resolution stays on the feed executor, including the built-in shelf. */
    private List<String> shelfApps(List<String> choice) {
        if (choice == null) return HomeApps.defaultShelfPackages(context);
        List<String> packages = new ArrayList<>(choice);
        packages.removeIf(pkg -> HomeApps.launchIntent(context.getPackageManager(), pkg) == null);
        return packages;
    }

    private Set<String> shelfPackages() {
        Shelf cached = shelf;
        List<String> choice = Prefs.shelf();
        // A reset to the built-in shelf waits for its eligibility check; edits hide removed apps immediately.
        if (choice == null) return cached.choice == null ? cached.packages : Collections.emptySet();
        Set<String> packages = new HashSet<>(cached.packages);
        packages.retainAll(choice);
        return packages;
    }

    List<Pick> now() { return now(null); }

    List<Pick> now(String kind) {
        return now(kind, null);
    }

    List<Pick> now(String kind, List<Pick> extraQueue) {
        List<Pick> out = new ArrayList<>();
        int limit = Prefs.recLimit();
        if ("continue".equals(kind)) {
            if (!canRead(context)) return out;
            for (Pick p : watchNextPicks) {
                if (Prefs.isRecAppEnabled(p.pkg)) {
                    out.add(p);
                    if (out.size() >= limit) break;
                }
            }
            return out;
        }

        Set<String> shelf = shelfPackages();
        Map<String, List<Pick>> appQueues = new LinkedHashMap<>();
        if (canRead(context)) {
            for (String pkg : shelf) {
                if (!Prefs.isRecAppEnabled(pkg)) continue;
                List<Pick> allAppPicks = picksByApp.get(pkg);
                if (allAppPicks == null) continue;
                List<Pick> matching = new ArrayList<>();
                for (Pick p : allAppPicks) {
                    if (kind == null || kind.equals(p.kind)) {
                        matching.add(p);
                    }
                }
                if (!matching.isEmpty()) {
                    appQueues.put(pkg, matching);
                }
            }
        }
        if (extraQueue != null && !extraQueue.isEmpty()) {
            appQueues.put("tmdb", new ArrayList<>(extraQueue));
        }

        List<String> activePkgs = new ArrayList<>(appQueues.keySet());
        int[] indices = new int[activePkgs.size()];
        boolean addedAny = true;
        while (out.size() < limit && addedAny) {
            addedAny = false;
            for (int i = 0; i < activePkgs.size() && out.size() < limit; i++) {
                String pkg = activePkgs.get(i);
                List<Pick> queue = appQueues.get(pkg);
                if (indices[i] < queue.size()) {
                    out.add(queue.get(indices[i]));
                    indices[i]++;
                    addedAny = true;
                }
            }
        }
        Collections.shuffle(out);
        return out;
    }

    static String key(Pick p) { return p.source + ":" + p.id; }
    static String serviceName(Pick p) { return p == null || p.app == null ? "" : p.app; }

    /** Restrict published intents to their owning app, including explicit components and selectors. */
    Intent intent(Pick p) {
        if (p == null || p.pkg == null) return null;
        if (p.link == null || p.link.isEmpty() || p.link.startsWith("package:")) {
            return HomeApps.launchIntent(context.getPackageManager(), p.pkg);
        }

        // Modern Plex client deep linking:
        // Plex writes legacy internal plugin URIs like:
        // plex://server://<SERVER_ID>/com.plexapp.plugins.library/library/metadata/<RATING_KEY>?...
        // Which the modern React Native Plex client rejects (dropping back to the home screen).
        if (p.pkg.toLowerCase(Locale.ROOT).contains("plex") && p.link.contains("server://")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("server://([a-zA-Z0-9_-]+)/.*metadata/([0-9]+)").matcher(p.link);
            if (m.find()) {
                String serverId = m.group(1);
                String key = m.group(2);
                String modernUri;
                if (p.resume) {
                    modernUri = "plex://play/?metadataKey=%2Flibrary%2Fmetadata%2F" + key + "&server=" + serverId;
                } else {
                    modernUri = "plex://libraries/" + serverId + "/unmatched/" + ("tv".equals(p.kind) ? "tv/" : "") + "%2Flibrary%2Fmetadata%2F" + key;
                }
                Intent plexIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(modernUri));
                plexIntent.setPackage(p.pkg);
                plexIntent.putExtra("metadataKey", "/library/metadata/" + key);
                plexIntent.putExtra("key", "/library/metadata/" + key);
                plexIntent.putExtra("ratingKey", key);
                plexIntent.putExtra("server", serverId);
                plexIntent.putExtra("serverId", serverId);
                plexIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return plexIntent;
            }
        }

        try {
            Intent i = Intent.parseUri(p.link, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() != null && !p.pkg.equals(i.getComponent().getPackageName())) {
                i.setComponent(null);
            }
            if (!p.pkg.isEmpty()) i.setPackage(p.pkg);
            i.setSelector(null);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.getAction() == null) i.setAction(Intent.ACTION_VIEW);
            return i;
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(p.link));
                if (!p.pkg.isEmpty()) fallback.setPackage(p.pkg);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return fallback;
            } catch (Exception ignored) {
                return HomeApps.launchIntent(context.getPackageManager(), p.pkg);
            }
        }
    }

    void refreshIfStale() {
        if ((!Recs.fromApps() && !Recs.enabled()) || fetching) return;
        List<String> choice = Prefs.shelf();
        String stamp = (choice == null ? "default" : choice.toString()) + canRead(context);
        long now = System.currentTimeMillis();
        if (!dirty && stamp.equals(shelfStamp) && Ui.language.equals(languageStamp)
                && now >= fetchedAt && now - fetchedAt < REFRESH_MS) return;
        shelfStamp = stamp;
        languageStamp = Ui.language;
        dirty = false;
        fetching = true;
        io.execute(() -> {
            try {
                List<String> shelf = shelfApps(choice);
                Map<String, List<Pick>> byApp = new LinkedHashMap<>();
                Map<String, Integer> counts = new LinkedHashMap<>();
                List<String> failures = new ArrayList<>();
                List<Pick> watchNext = new ArrayList<>();
                for (String pkg : shelf) { byApp.put(pkg, new ArrayList<>()); counts.put(pkg, 0); }
                if (canRead(context)) {
                    readWatchNext(watchNext, failures);
                    readPreviewChannelsResume(watchNext, failures);
                    readPreview(shelf, byApp, failures);
                    injectPlexWatchNext(byApp, watchNext);
                }
                Collections.sort(watchNext, (a, b) -> {
                    if (a.engagementTime > 0 && b.engagementTime > 0) {
                        int cmp = Long.compare(b.engagementTime, a.engagementTime);
                        if (cmp != 0) return cmp;
                    } else if (a.engagementTime > 0) {
                        long thirtyDaysAgo = System.currentTimeMillis() - 30L * 86_400_000L;
                        if (a.engagementTime < thirtyDaysAgo) return 1;
                        return -1;
                    } else if (b.engagementTime > 0) {
                        long thirtyDaysAgo = System.currentTimeMillis() - 30L * 86_400_000L;
                        if (b.engagementTime < thirtyDaysAgo) return -1;
                        return 1;
                    }
                    return Long.compare(b.id, a.id);
                });
                this.shelf = new Shelf(shelf, choice);
                this.picksByApp = byApp;
                this.watchNextPicks = watchNext;
                this.picks = now(null);
                for (String pkg : shelf) counts.put(pkg, byApp.get(pkg).size());
                Set<String> artwork = new HashSet<>();
                for (Pick p : this.picks) if (p.backdrop != null) artwork.add(p.backdrop);
                for (Pick p : watchNext) if (p.backdrop != null) artwork.add(p.backdrop);
                synchronized (images) { imageFailedAt.keySet().retainAll(artwork); }
                error = failures.isEmpty() ? "" : String.join("; ", failures);
                fetchedAt = System.currentTimeMillis();
                writeStatus(counts, watchNext.size());
                AppLog.w("app recommendations: " + counts + " watch-next: " + watchNext.size() + (error.isEmpty() ? "" : " (" + error + ")"));
                notifyListeners();
            } catch (RuntimeException e) {
                error = "App recommendations unavailable";
                fetchedAt = System.currentTimeMillis();
                AppLog.w("app recommendations: " + e.getClass().getSimpleName());
            } finally {
                fetching = false;
            }
            if (dirty) main.postDelayed(refreshPublished, 1000);
        });
    }

    private void readWatchNext(List<Pick> out, List<String> failures) {
        String[] projection = {"_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri", "type",
                "last_engagement_time_utc_millis"};
        try (Cursor rows = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null,
                "last_engagement_time_utc_millis DESC, _id DESC")) {
            if (rows == null) return;
            int idCol = rows.getColumnIndex("_id");
            int pkgCol = rows.getColumnIndex("package_name");
            int titleCol = rows.getColumnIndex("title");
            int posterCol = rows.getColumnIndex("poster_art_uri");
            int thumbCol = rows.getColumnIndex("thumbnail_uri");
            int linkCol = rows.getColumnIndex("intent_uri");
            int typeCol = rows.getColumnIndex("type");
            int timeCol = rows.getColumnIndex("last_engagement_time_utc_millis");

            Map<String, String> labels = new HashMap<>();
            while (rows.moveToNext()) {
                String pkg = pkgCol >= 0 ? rows.getString(pkgCol) : null;
                if (pkg != null && !Prefs.isRecAppEnabled(pkg)) continue;

                String title = titleCol >= 0 ? rows.getString(titleCol) : null;
                String link = linkCol >= 0 ? rows.getString(linkCol) : null;

                if (pkg != null && !labels.containsKey(pkg)) {
                    try {
                        labels.put(pkg, context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(pkg, 0)).toString());
                    } catch (Exception e) {
                        labels.put(pkg, pkg);
                    }
                }
                String appLabel = pkg != null ? labels.get(pkg) : "";
                String displayTitle = (title != null && !title.trim().isEmpty()) ? title : appLabel;

                if (link == null || link.trim().isEmpty()) {
                    link = (pkg != null && !pkg.isEmpty()) ? "package:" + pkg : "";
                }

                boolean duplicate = false;
                for (Pick other : out) {
                    if (link.equals(other.link) || (pkg != null && pkg.equals(other.pkg) && displayTitle.equals(other.title))) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                Pick p = new Pick();
                p.id = idCol >= 0 ? rows.getLong(idCol) : 0;
                p.source = "watch-next";
                p.pkg = pkg != null ? pkg : "";
                p.app = appLabel;
                if (p.app == null || p.app.isEmpty()) p.app = p.pkg;
                p.title = displayTitle;
                p.link = link;
                p.resume = true;

                long engagement = (timeCol >= 0 && !rows.isNull(timeCol)) ? rows.getLong(timeCol) : 0;
                long now = System.currentTimeMillis();
                if (engagement > 10_000_000_000_000L) {
                    engagement /= 1000L;
                } else if (engagement >= 1_000_000_000L && engagement < 100_000_000_000L) {
                    engagement *= 1000L;
                }
                if (engagement > now + 86_400_000L) engagement = now;
                if (engagement < 1_000_000_000L) engagement = 0;
                p.engagementTime = engagement;

                p.kind = kind(typeCol >= 0 ? rows.getInt(typeCol) : 0);
                String poster = posterCol >= 0 ? optimizeArtworkUrl(rows.getString(posterCol)) : null;
                String thumb = thumbCol >= 0 ? optimizeArtworkUrl(rows.getString(thumbCol)) : null;
                p.backdrop = (thumb != null && !thumb.isEmpty()) ? thumb : poster;
                p.thumbnail = (poster != null && !poster.isEmpty()) ? poster : thumb;
                p.meta = p.app;

                if (intent(p) != null) {
                    out.add(p);
                    AppLog.w("added watch-next: " + p.pkg + " | " + p.title + " | time=" + p.engagementTime);
                }
            }
        } catch (RuntimeException e) {
            failures.add("Watch Next unavailable");
            AppLog.w("app watch next query: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void readPreviewChannelsResume(List<Pick> out, List<String> failures) {
        Map<Long, String> resumeChannels = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[] {"_id", "package_name", "display_name"}, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String pkg = c.getString(1);
                    String name = c.getString(2);
                    String nameLow = name != null ? name.toLowerCase(Locale.ROOT) : "";
                    boolean isResume = nameLow.contains("deck") || nameLow.contains("continue") || nameLow.contains("seguir")
                            || nameLow.contains("watching") || nameLow.contains("reanudar") || nameLow.contains("resume")
                            || nameLow.contains("en cola") || nameLow.contains("play next") || nameLow.contains("watch next")
                            || nameLow.contains("próximo") || nameLow.contains("proximo");
                    if (isResume && pkg != null) {
                        resumeChannels.put(id, pkg);
                        AppLog.w("detected resume channel: id=" + id + " pkg=" + pkg + " name=" + name);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("channel query: " + e.getMessage());
        }

        if (resumeChannels.isEmpty()) return;

        String[] projection = {"_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri", "type",
                "weight", "channel_id", "last_engagement_time_utc_millis"};
        try (Cursor rows = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, projection, null, null,
                "weight DESC, _id DESC")) {
            if (rows == null) return;
            int idCol = rows.getColumnIndex("_id");
            int pkgCol = rows.getColumnIndex("package_name");
            int titleCol = rows.getColumnIndex("title");
            int posterCol = rows.getColumnIndex("poster_art_uri");
            int thumbCol = rows.getColumnIndex("thumbnail_uri");
            int linkCol = rows.getColumnIndex("intent_uri");
            int typeCol = rows.getColumnIndex("type");
            int channelCol = rows.getColumnIndex("channel_id");
            int timeCol = rows.getColumnIndex("last_engagement_time_utc_millis");

            Map<String, String> labels = new HashMap<>();
            Map<String, Integer> appCount = new HashMap<>();
            while (rows.moveToNext()) {
                long chId = channelCol >= 0 ? rows.getLong(channelCol) : -1;
                if (!resumeChannels.containsKey(chId)) continue;

                String pkg = pkgCol >= 0 ? rows.getString(pkgCol) : null;
                if (pkg != null && !Prefs.isRecAppEnabled(pkg)) continue;
                if (pkg == null) pkg = resumeChannels.get(chId);

                int count = appCount.getOrDefault(pkg, 0);
                if (count >= 3) continue;

                String title = titleCol >= 0 ? rows.getString(titleCol) : null;
                String link = linkCol >= 0 ? rows.getString(linkCol) : null;

                if (pkg != null && !labels.containsKey(pkg)) {
                    try {
                        labels.put(pkg, context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(pkg, 0)).toString());
                    } catch (Exception e) {
                        labels.put(pkg, pkg);
                    }
                }
                String appLabel = pkg != null ? labels.get(pkg) : "";
                String displayTitle = (title != null && !title.trim().isEmpty()) ? title : appLabel;

                if (link == null || link.trim().isEmpty()) {
                    link = (pkg != null && !pkg.isEmpty()) ? "package:" + pkg : "";
                }

                boolean duplicate = false;
                for (Pick other : out) {
                    if (link.equals(other.link) || (pkg != null && pkg.equals(other.pkg) && displayTitle.equals(other.title))) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                Pick p = new Pick();
                p.id = idCol >= 0 ? rows.getLong(idCol) : 0;
                p.source = "watch-next";
                p.pkg = pkg != null ? pkg : "";
                p.app = appLabel;
                if (p.app == null || p.app.isEmpty()) p.app = p.pkg;
                p.title = displayTitle;
                p.link = link;
                p.resume = true;

                long engagement = (timeCol >= 0 && !rows.isNull(timeCol)) ? rows.getLong(timeCol) : 0;
                long now = System.currentTimeMillis();
                if (engagement > 10_000_000_000_000L) {
                    engagement /= 1000L;
                } else if (engagement >= 1_000_000_000L && engagement < 100_000_000_000L) {
                    engagement *= 1000L;
                }
                if (engagement > now + 86_400_000L) engagement = now;
                if (engagement < 1_000_000_000L) {
                    engagement = now - (out.size() * 60_000L);
                }
                p.engagementTime = engagement;

                p.kind = kind(typeCol >= 0 ? rows.getInt(typeCol) : 0);
                String poster = posterCol >= 0 ? optimizeArtworkUrl(rows.getString(posterCol)) : null;
                String thumb = thumbCol >= 0 ? optimizeArtworkUrl(rows.getString(thumbCol)) : null;
                p.backdrop = (thumb != null && !thumb.isEmpty()) ? thumb : poster;
                p.thumbnail = (poster != null && !poster.isEmpty()) ? poster : thumb;
                p.meta = p.app;

                if (intent(p) != null) {
                    out.add(p);
                    appCount.put(pkg, count + 1);
                    AppLog.w("added channel resume pick: " + p.pkg + " | " + p.title);
                }
            }
        } catch (Exception e) {
            AppLog.w("preview channels resume query: " + e.getMessage());
        }
    }

    private void injectPlexWatchNext(Map<String, List<Pick>> byApp, List<Pick> watchNext) {
        watchNext.removeIf(p -> p.pkg != null && p.pkg.toLowerCase(Locale.ROOT).contains("plex"));
        for (Map.Entry<String, List<Pick>> entry : byApp.entrySet()) {
            String pkg = entry.getKey();
            if (pkg != null && pkg.toLowerCase(Locale.ROOT).contains("plex")) {
                List<Pick> plexPicks = entry.getValue();
                if (plexPicks != null && !plexPicks.isEmpty()) {
                    long now = System.currentTimeMillis();
                    int count = Math.min(2, plexPicks.size());
                    for (int i = 0; i < count; i++) {
                        Pick orig = plexPicks.get(i);
                        Pick p = new Pick();
                        p.id = orig.id;
                        p.source = "watch-next";
                        p.pkg = orig.pkg;
                        p.app = orig.app;
                        p.title = orig.title;
                        p.link = orig.link;
                        p.resume = true;
                        p.kind = orig.kind;
                        p.backdrop = orig.backdrop;
                        p.thumbnail = orig.thumbnail;
                        p.meta = orig.meta;
                        p.engagementTime = now - (i * 1000L);
                        if (intent(p) != null) {
                            watchNext.add(0, p);
                            AppLog.w("injected recent Plex pick into watch-next: " + p.title + " (backdrop=" + p.backdrop + ")");
                        }
                    }
                }
                break;
            }
        }
    }

    private static String optimizeArtworkUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        // Disney+ (bamgrid/ripcut CDN) passes tiny thumbnail dimensions (e.g. width=300 or 400).
        // Upscale to width=1920 & height=1080 (native 1080p Full HD density matching Apple TV / Prime Video).
        if (url.contains("bamgrid.com") || url.contains("disney") || url.contains("ripcut")) {
            url = url.replaceAll("([?&]width=)[0-9]+", "$11920");
            url = url.replaceAll("([?&]height=)[0-9]+", "$11080");
        }
        return url;
    }

    private void readPreview(List<String> shelf, Map<String, List<Pick>> byApp, List<String> failures) {
        Cursor rows = null;
        try {
            rows = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, "weight DESC");
        } catch (RuntimeException e) {
            failures.add("Preview channels unavailable");
            AppLog.w("app recommendation query: " + e.getClass().getSimpleName());
        }
        if (rows == null) return;
        try (Cursor c = rows) {
            int idCol = c.getColumnIndex("_id");
            int pkgCol = c.getColumnIndex("package_name");
            int titleCol = c.getColumnIndex("title");
            int posterCol = c.getColumnIndex("poster_art_uri");
            int thumbCol = c.getColumnIndex("thumbnail_uri");
            int logoCol = c.getColumnIndex("logo_uri");
            int linkCol = c.getColumnIndex("intent_uri");
            int typeCol = c.getColumnIndex("type");
            int epTitleCol = c.getColumnIndex("episode_title");
            int descCol = c.getColumnIndex("short_description");
            int seasonCol = c.getColumnIndex("season_display_number");
            int epCol = c.getColumnIndex("episode_display_number");

            Map<String, String> labels = new HashMap<>();
            int targetLimit = Prefs.recLimit();
            Map<String, int[]> appKindCounts = new HashMap<>(); // [movies, tv]
            while (c.moveToNext()) {
                String pkg = pkgCol >= 0 ? c.getString(pkgCol) : null;
                if (pkg != null && !Prefs.isRecAppEnabled(pkg)) continue;
                List<Pick> app = byApp.get(pkg);
                if (app == null) continue;

                int rawType = typeCol >= 0 ? c.getInt(typeCol) : 0;
                int seasonNum = seasonCol >= 0 ? c.getInt(seasonCol) : 0;
                int epNum = epCol >= 0 ? c.getInt(epCol) : -1;
                String epTitle = epTitleCol >= 0 ? c.getString(epTitleCol) : null;
                String title = titleCol >= 0 ? c.getString(titleCol) : null;
                String kind = classifyKind(rawType, seasonNum, epNum, epTitle, title);

                int[] counts = appKindCounts.get(pkg);
                if (counts == null) {
                    counts = new int[2];
                    appKindCounts.put(pkg, counts);
                }
                boolean isMovie = "movie".equals(kind);
                boolean isTv = "tv".equals(kind);
                if (isMovie && counts[0] >= targetLimit) continue;
                if (isTv && counts[1] >= targetLimit) continue;
                if (!isMovie && !isTv && app.size() >= targetLimit * 2) continue;

                String desc = descCol >= 0 ? c.getString(descCol) : null;
                String link = linkCol >= 0 ? c.getString(linkCol) : null;

                if (pkg != null && !labels.containsKey(pkg)) {
                    try {
                        labels.put(pkg, context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(pkg, 0)).toString());
                    } catch (Exception e) {
                        labels.put(pkg, pkg);
                    }
                }
                String appLabel = pkg != null ? labels.get(pkg) : "";

                String displayTitle = title;
                if (displayTitle != null && epTitle != null && !epTitle.trim().isEmpty() && !displayTitle.contains(epTitle)) {
                    displayTitle = displayTitle + " - " + epTitle;
                } else if (displayTitle == null || displayTitle.trim().isEmpty()) {
                    displayTitle = (epTitle != null && !epTitle.trim().isEmpty()) ? epTitle :
                                   (desc != null && !desc.trim().isEmpty()) ? desc : appLabel;
                }
                if (displayTitle == null || displayTitle.trim().isEmpty()) continue;

                if (link == null || link.trim().isEmpty()) {
                    link = (pkg != null && !pkg.isEmpty()) ? "package:" + pkg : "";
                }

                boolean duplicate = false;
                for (Pick other : app) {
                    if (link.equals(other.link) || (pkg != null && pkg.equals(other.pkg) && displayTitle.equals(other.title))) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;

                Pick p = new Pick();
                p.id = idCol >= 0 ? c.getLong(idCol) : 0;
                p.source = "preview";
                p.pkg = pkg != null ? pkg : "";
                p.app = appLabel;
                if (p.app == null || p.app.isEmpty()) p.app = p.pkg;
                p.title = displayTitle;
                p.link = link;
                p.resume = false;
                p.engagementTime = 0;
                p.kind = kind;
                String poster = posterCol >= 0 ? optimizeArtworkUrl(c.getString(posterCol)) : null;
                String thumb = thumbCol >= 0 ? optimizeArtworkUrl(c.getString(thumbCol)) : null;
                String logo = logoCol >= 0 ? optimizeArtworkUrl(c.getString(logoCol)) : null;

                String art = (thumb != null && !thumb.isEmpty()) ? thumb :
                             (poster != null && !poster.isEmpty()) ? poster :
                             (logo != null && !logo.isEmpty()) ? logo : null;

                if (art == null && pkg != null && pkg.toLowerCase(Locale.ROOT).contains("plex")) {
                    for (int col = 0; col < c.getColumnCount(); col++) {
                        try {
                            String val = c.getString(col);
                            if (val != null && (val.startsWith("http://") || val.startsWith("https://") || val.startsWith("content://"))) {
                                art = val;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                p.backdrop = art;
                p.thumbnail = art;
                p.meta = p.app;

                if (intent(p) != null) {
                    app.add(p);
                    if (isMovie) counts[0]++;
                    if (isTv) counts[1]++;

                    boolean allFull = true;
                    for (String sPkg : shelf) {
                        if (!Prefs.isRecAppEnabled(sPkg)) continue;
                        int[] kc = appKindCounts.get(sPkg);
                        if (kc == null || kc[0] < targetLimit || kc[1] < targetLimit) {
                            allFull = false;
                            break;
                        }
                    }
                    if (allFull) break;
                }
            }
        } catch (RuntimeException e) {
            failures.add("Preview channels unavailable");
            AppLog.w("app recommendation query: " + e.getClass().getSimpleName());
        }
    }

    static String classifyKind(int type, int season, int episode, String epTitle, String title) {
        if (season > 0 || episode >= 0
                || type == TvContract.PreviewPrograms.TYPE_TV_SERIES
                || type == TvContract.PreviewPrograms.TYPE_TV_SEASON
                || type == TvContract.PreviewPrograms.TYPE_TV_EPISODE) {
            return "tv";
        }
        if (epTitle != null && !epTitle.trim().isEmpty() && title != null && !epTitle.trim().equalsIgnoreCase(title.trim())) {
            return "tv";
        }
        if (type == TvContract.PreviewPrograms.TYPE_MOVIE) return "movie";
        if (type >= TvContract.PreviewPrograms.TYPE_TRACK && type <= TvContract.PreviewPrograms.TYPE_STATION) return "music";
        return "movie";
    }

    static String kind(int type) {
        return classifyKind(type, 0, -1, null, null);
    }

    List<Pick> wanted() {
        Map<String, Pick> out = new LinkedHashMap<>();
        for (String kind : new String[] {null, "movie", "tv", "continue"}) {
            List<Pick> list = now(kind);
            for (int i = 0; i < Math.min(10, list.size()); i++) out.put(key(list.get(i)), list.get(i));
        }
        return new ArrayList<>(out.values());
    }

    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        Set<String> shelf = shelfPackages();
        if (canRead(context)) for (Pick p : picks) if (shelf.contains(p.pkg) && readyImage(p) != null) out.add(p);
        return out;
    }

    void prefetchAll() {
        // Limit background work to visible cards; providers can publish hundreds of items.
        List<Pick> wanted = wanted();
        prioritize(wanted, 0, wanted.size());
        for (Pick p : wanted) backdrop(p);
    }

    void prioritize(List<Pick> list, int from, int to) {
        Set<String> keys = new HashSet<>();
        for (int i = from; i < to; i++) if (list.get(i).backdrop != null) keys.add(list.get(i).backdrop);
        requestedArtwork = keys;
    }

    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) { images.clear(); }
    }

    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty()) return null;
        synchronized (images) {
            Backdrop b = images.get(p.backdrop);
            if (b != null) return b;
            if (p.thumbnail != null) {
                b = images.get(p.thumbnail);
                if (b != null) return b;
            }
            Long failed = imageFailedAt.get(p.backdrop);
            if (failed != null && System.currentTimeMillis() - failed < 5_000L) return null;
            if (!pending.add(p.backdrop)) return null;
        }
        artworkIo.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(p.backdrop); }
            }
        });
        return null;
    }

    private File imageFile(Pick p) {
        try {
            String key = p.backdrop != null && !p.backdrop.isEmpty() ? p.backdrop :
                         p.thumbnail != null && !p.thumbnail.isEmpty() ? p.thumbnail : (p.pkg + ":" + p.id);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new File(new File(context.getCacheDir(), "native-recs"), name.toString());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    File readyImage(Pick p) {
        if (p.backdrop == null || p.backdrop.isEmpty()) return null;
        File f = imageFile(p);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    private static void configureConnection(HttpURLConnection conn) {
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko)");
        conn.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setInstanceFollowRedirects(false);
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setHostnameVerifier((hostname, session) -> true);
            try {
                if (trustAllSocketFactory != null) {
                    https.setSSLSocketFactory(trustAllSocketFactory);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void downloadStream(String artwork, File dest) throws IOException {
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("image cache unavailable");
        Uri uri = Uri.parse(artwork);
        String scheme = uri.getScheme();
        HttpURLConnection connection = null;
        InputStream stream = null;
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            int redirects = 0;
            String currentUrl = artwork.replace(" ", "%20");
            while (redirects < 4) {
                connection = (HttpURLConnection) new URL(currentUrl).openConnection();
                configureConnection(connection);
                int code = connection.getResponseCode();
                if (currentUrl.contains("32400") || currentUrl.contains("plex")) {
                    AppLog.w("    [HTTP response] code=" + code + " for " + currentUrl);
                }
                if (code >= 300 && code < 400) {
                    String loc = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (loc != null) {
                        currentUrl = new URL(new URL(currentUrl), loc).toString();
                        redirects++;
                        continue;
                    }
                }
                if (code != 200) {
                    connection.disconnect();
                    throw new IOException("image unavailable: HTTP " + code + " for " + artwork);
                }
                stream = connection.getInputStream();
                break;
            }
        } else if ("content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)) {
            try {
                stream = context.getContentResolver().openInputStream(uri);
                if (stream != null) {
                    AppLog.w("ContentResolver.openInputStream SUCCESS for: " + uri);
                } else {
                    AppLog.w("ContentResolver.openInputStream returned null for: " + uri);
                }
            } catch (SecurityException se) {
                AppLog.w("SecurityException opening " + uri + ": " + se.getMessage());
                throw new IOException("SecurityException: " + se.getMessage(), se);
            } catch (java.io.FileNotFoundException fnf) {
                AppLog.w("FileNotFoundException opening " + uri + ": " + fnf.getMessage());
                throw fnf;
            }
        } else if ("file".equalsIgnoreCase(scheme)) {
            stream = new FileInputStream(new File(uri.getPath()));
        } else {
            throw new IOException("unsupported artwork URI: " + scheme + " (" + artwork + ")");
        }
        File tmp = new File(dir, dest.getName() + ".part");
        try (InputStream in = stream; FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("image unavailable");
            byte[] buffer = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > 8 * 1024 * 1024) throw new IOException("image too large");
                out.write(buffer, 0, n);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
        dest.delete();
        if (!tmp.renameTo(dest)) {
            try (FileInputStream in = new FileInputStream(tmp); FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            } finally {
                tmp.delete();
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            long bytes = 0;
            for (File file : files) bytes += file.length();
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int count = files.length;
            for (File file : files) {
                if (count <= 128 && bytes <= 64 * 1024 * 1024L) break;
                if (file.equals(dest)) continue;
                long size = file.length();
                if (file.delete()) { count--; bytes -= size; }
            }
        }
    }

    private void download(String artwork, File dest) throws IOException {
        if (dest.isFile() && dest.length() > 0) {
            dest.setLastModified(System.currentTimeMillis());
            return;
        }

        List<String> candidates = new ArrayList<>();
        // If it's a Plex server URL (on port 32400 or .plex.direct):
        if (artwork != null && (artwork.contains(":32400") || artwork.contains(".plex.direct"))) {
            // Case A: Extract LAN IP from *.plex.direct
            String ipHttp = artwork.replaceAll(
                "https?://([0-9]+)-([0-9]+)-([0-9]+)-([0-9]+)\\.[^/]+\\.plex\\.direct(:[0-9]+)?(/.*)?",
                "http://$1.$2.$3.$4$5$6");
            String ipHttps = artwork.replaceAll(
                "https?://([0-9]+)-([0-9]+)-([0-9]+)-([0-9]+)\\.[^/]+\\.plex\\.direct(:[0-9]+)?(/.*)?",
                "https://$1.$2.$3.$4$5$6");

            // Direct HTTP first (fast, bypasses all SSL/cert issues completely if PMS allows)
            if (ipHttp.startsWith("http://") && !candidates.contains(ipHttp)) candidates.add(ipHttp);
            if (artwork.startsWith("https://")) {
                String plainHttp = "http://" + artwork.substring(8);
                if (!candidates.contains(plainHttp)) candidates.add(plainHttp);
            }

            // Direct HTTPS next (with our trust-all SSL engine)
            if (ipHttps.startsWith("https://") && !candidates.contains(ipHttps)) candidates.add(ipHttps);
            if (artwork.startsWith("http://")) {
                String secureHttps = "https://" + artwork.substring(7);
                if (!candidates.contains(secureHttps)) candidates.add(secureHttps);
            }
            if (!candidates.contains(artwork)) candidates.add(artwork);
        } else if (artwork != null && artwork.startsWith("content://com.plexapp.android.RecommendationsProvider/")) {
            candidates.add(artwork);
            String path = artwork.substring("content://com.plexapp.android.RecommendationsProvider/".length());
            if (path.length() > 1 && Character.isDigit(path.charAt(path.length() - 1))) {
                candidates.add("content://com.plexapp.android.RecommendationsProvider/" + path.substring(0, path.length() - 1));
            }
        } else {
            candidates.add(artwork);
        }

        boolean isPlexArt = artwork != null && (artwork.contains(":32400") || artwork.contains("plex"));
        if (isPlexArt) {
            AppLog.w("=== [PLEX ARTWORK DOWNLOAD START] ===");
            AppLog.w("  original uri: " + artwork);
            AppLog.w("  candidates to try: " + candidates);
        }
        IOException lastError = null;
        for (String candidate : candidates) {
            try {
                if (isPlexArt) AppLog.w("  -> attempting: " + candidate);
                downloadStream(candidate, dest);
                if (isPlexArt) AppLog.w("  -> SUCCESS! downloaded " + dest.length() + " bytes from: " + candidate);
                return; // Download succeeded!
            } catch (IOException e) {
                lastError = e;
                if (isPlexArt) AppLog.w("  -> FAILED [" + candidate + "]: " + e.getClass().getSimpleName() + " " + e.getMessage());
                else AppLog.w("download candidate failed [" + candidate + "]: " + e.getMessage());
            }
        }
        if (lastError != null) throw lastError;
    }

    private Bitmap decode(File file) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            AppLog.w("decode failed: outWidth=" + opts.outWidth + " outHeight=" + opts.outHeight + " fileLen=" + file.length());
            throw new IOException("invalid image");
        }
        opts.inSampleSize = 1;
        while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > 2048) opts.inSampleSize *= 2;
        opts.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
        if (bitmap == null) throw new IOException("invalid image");
        return bitmap;
    }

    private Bitmap tryArtwork(String url, File file) {
        if (url == null || url.isEmpty()) return null;
        try {
            download(url, file);
            return decode(file);
        } catch (Exception e) {
            AppLog.w("tryArtwork FAILED for [" + url + "]: " + e.getClass().getSimpleName() + " " + e.getMessage());
            file.delete();
            new File(file.getPath() + ".part").delete();
            return null;
        }
    }

    private static final Map<String, String> tmdbArtCache = new HashMap<>();

    private String fetchTmdbArtwork(Pick p) {
        if (p == null || p.title == null || p.title.trim().isEmpty()) return null;
        String clean = cleanTitle(p.title);
        if (clean.isEmpty()) return null;
        synchronized (tmdbArtCache) {
            if (tmdbArtCache.containsKey(clean)) return tmdbArtCache.get(clean);
        }
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) return null;
        try {
            String q = URLEncoder.encode(clean, "UTF-8");
            String lang = Ui.es ? "es-ES" : (Ui.language != null && !Ui.language.isEmpty() ? Ui.language : "en-US");
            String urlStr = "https://api.themoviedb.org/3/search/multi?api_key=" + key
                    + "&language=" + lang + "&query=" + q;
            URL u = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                String jsonStr = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(jsonStr);
                JSONArray results = obj.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    String mediaType = first.optString("media_type", null);
                    if ("tv".equals(mediaType) || "movie".equals(mediaType)) {
                        p.kind = mediaType;
                    }
                    String poster = first.optString("poster_path", null);
                    String backdrop = first.optString("backdrop_path", null);
                    String path = (backdrop != null && !backdrop.isEmpty() && !"null".equals(backdrop)) ? backdrop : poster;
                    if (path != null && !path.isEmpty() && !"null".equals(path)) {
                        String fullUrl = "https://image.tmdb.org/t/p/w1280" + path;
                        AppLog.w("TMDB artwork found for [" + clean + "] (" + p.kind + "): " + fullUrl);
                        synchronized (tmdbArtCache) {
                            tmdbArtCache.put(clean, fullUrl);
                        }
                        return fullUrl;
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("TMDB artwork search failed for [" + clean + "]: " + e.getMessage());
        }
        synchronized (tmdbArtCache) {
            tmdbArtCache.put(clean, null);
        }
        return null;
    }

    private static String cleanTitle(String t) {
        if (t == null) return "";
        int cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    private void loadImage(Pick p) {
        File file = imageFile(p);
        try {
            Bitmap bitmap = tryArtwork(p.backdrop, file);
            if (bitmap == null && p.thumbnail != null && !p.thumbnail.equals(p.backdrop)) {
                bitmap = tryArtwork(p.thumbnail, file);
            }
            if (bitmap == null) {
                String tmdbArt = fetchTmdbArtwork(p);
                if (tmdbArt != null) {
                    bitmap = tryArtwork(tmdbArt, file);
                }
            }
            if (bitmap == null) throw new IOException("artwork unavailable: " + p.backdrop);
            boolean forLight = light;
            Backdrop b = Backdrop.make(bitmap, forLight);
            if (forLight != light) return;
            synchronized (images) {
                images.put(p.backdrop, b);
                if (p.thumbnail != null) images.put(p.thumbnail, b);
                imageFailedAt.remove(p.backdrop);
                if (p.thumbnail != null) imageFailedAt.remove(p.thumbnail);
                while (images.size() > (forLight ? 40 : 15)) images.remove(images.keySet().iterator().next());
            }
            p.meta = p.app;
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            file.delete();
            new File(file.getPath() + ".part").delete();
            synchronized (images) {
                imageFailedAt.put(p.backdrop, System.currentTimeMillis());
                if (p.thumbnail != null) imageFailedAt.put(p.thumbnail, System.currentTimeMillis());
            }
            p.meta = p.app;
            AppLog.w("app recommendation artwork unavailable: " + p.pkg + " (" + e.getMessage() + ")");
        }
    }

    void trim() {
        Set<String> keep = new HashSet<>();
        for (Pick p : wanted()) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) { images.keySet().retainAll(keep); imageFailedAt.clear(); }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }
}
