package io.freemytv;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Selects the Android recommendation source; each backend keeps its own feed and image cache. */
final class Recs {
    static final class Pick {
        long id;
        String kind, title, backdrop, meta = "", app, pkg;
        boolean detailed;
        String source, link, thumbnail;
        boolean resume;
        long engagementTime;
    }

    interface Listener { void onRecs(); }

    private static Recs instance;
    private final Context context;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, List<Pick>> hubCache = new HashMap<>();
    private int cachedRecLimit = -1;
    private String cachedSource = "";
    private int cachedAppsHash = 0;
    private TmdbRecs tmdb;
    private NativeRecs apps;
    private boolean light;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private Recs(Context c) { context = c; }

    static boolean fromApps() { return "apps".equals(Prefs.recommendations()) || "both".equals(Prefs.recommendations()); }
    static boolean fromTmdb() { return "tmdb".equals(Prefs.recommendations()) || "both".equals(Prefs.recommendations()); }
    static boolean enabled() { return !Prefs.recommendations().equals("off"); }
    static boolean hasKey(Context c) { return TmdbRecs.hasKey(c); }
    static boolean canRead(Context c) { return NativeRecs.canRead(c); }
    static String periodLabel() { return TmdbRecs.periodLabel(); }
    static String key(Pick p) { return p == null || p.source == null ? TmdbRecs.key(p) : NativeRecs.key(p); }
    static String serviceName(Pick p) { return p != null && p.source != null ? NativeRecs.serviceName(p) : TmdbRecs.serviceName(p); }
    static JSONObject status(Context c) {
        return !enabled() ? new JSONObject() : fromApps() ? NativeRecs.status(c) : TmdbRecs.status(c);
    }

    private NativeRecs apps() {
        if (apps == null) {
            apps = NativeRecs.get(context);
            apps.setLight(light);
            apps.addListener(() -> { clearCache(); if (fromApps() || enabled()) notifyListeners(); });
        }
        return apps;
    }

    private TmdbRecs tmdb() {
        if (tmdb == null) {
            tmdb = TmdbRecs.get(context);
            tmdb.setLight(light);
            tmdb.addListener(() -> { clearCache(); if (fromTmdb() || enabled()) notifyListeners(); });
        }
        return tmdb;
    }

    synchronized void clearCache() {
        hubCache.clear();
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    private void notifyListeners() { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); }

    List<Pick> now() { return now(null); }
    List<Pick> now(String kind) {
        if (!enabled()) return new ArrayList<>();
        if ("continue".equals(kind)) {
            return apps().now("continue");
        }
        int limit = Prefs.recLimit();
        String source = Prefs.recommendations();
        int appsHash = Prefs.disabledRecApps().hashCode();

        String key = (kind == null ? "explore" : kind);
        synchronized (hubCache) {
            if (limit != cachedRecLimit || !source.equals(cachedSource) || appsHash != cachedAppsHash) {
                hubCache.clear();
                cachedRecLimit = limit;
                cachedSource = source;
                cachedAppsHash = appsHash;
            } else {
                List<Pick> cached = hubCache.get(key);
                if (cached != null) return cached;
            }
        }

        List<Pick> out;
        if ("both".equals(source)) {
            List<Pick> extra = fromTmdb() ? tmdb().candidates(kind) : null;
            out = apps().now(kind, extra);
        } else if (fromApps()) {
            out = apps().now(kind);
        } else {
            List<Pick> t = tmdb().candidates(kind);
            out = new ArrayList<>();
            for (int i = 0; i < t.size() && out.size() < limit; i++) {
                out.add(t.get(i));
            }
            Collections.shuffle(out);
        }

        synchronized (hubCache) {
            hubCache.put(key, out);
        }
        return out;
    }
    boolean hasPicks() {
        if (!enabled()) return false;
        if (fromApps() && apps != null && apps.hasPicks()) return true;
        return fromTmdb() && hasKey(context);
    }
    List<Pick> wanted() {
        if (!enabled()) return new ArrayList<>();
        if ("both".equals(Prefs.recommendations())) {
            List<Pick> out = new ArrayList<>(apps().wanted());
            out.addAll(tmdb().wanted());
            return out;
        }
        if (fromApps()) return apps().wanted();
        List<Pick> out = new ArrayList<>(tmdb().wanted());
        if (canRead(context)) out.addAll(apps().now("continue"));
        return out;
    }
    List<Pick> showcase() {
        return !enabled() ? new ArrayList<>() : fromApps() ? apps().showcase() : tmdb().showcase();
    }
    Intent intent(Pick p) { return p == null || p.source == null ? null : apps().intent(p); }
    Backdrop backdrop(Pick p) {
        return p == null || !enabled() ? null : p.source == null ? tmdb().backdrop(p) : apps().backdrop(p);
    }
    File readyImage(Pick p) { return p == null || !enabled() ? null : p.source == null ? tmdb().readyImage(p) : apps().readyImage(p); }
    void refreshIfStale() {
        if (!enabled()) return;
        if (canRead(context) || fromApps()) apps().refreshIfStale();
        if (fromTmdb()) tmdb().refreshIfStale();
    }
    void invalidate() {
        clearCache();
        if (fromApps() || canRead(context)) apps().invalidate();
        if (fromTmdb()) tmdb().refreshIfStale();
    }
    void prefetchAll() {
        if (!enabled()) return;
        if (canRead(context) || fromApps()) apps().prefetchAll();
        if (fromTmdb()) tmdb().prefetchAll();
    }
    void prioritize(List<Pick> list, int from, int to) {
        if (fromApps() || canRead(context)) apps().prioritize(list, from, to);
    }
    void setLight(boolean value) {
        light = value;
        if (apps != null) apps.setLight(value);
        if (tmdb != null) tmdb.setLight(value);
    }
    void trim() {
        if (apps != null) apps.trim();
        if (tmdb != null) tmdb.trim();
    }
}
