package io.freemytv;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Looper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs on a TV without JUnit; only the recommendation and shelf settings are changed, then restored. */
public final class RecommendationsTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = getTargetContext();
        SharedPreferences prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String source = prefs.getString("recommendations", null), shelf = prefs.getString("shelf", null);
        int code = -1;
        CountDownLatch resumeFeed = new CountDownLatch(1);
        try {
            prepareNative(c, prefs);
            Field executor = NativeRecs.class.getDeclaredField("io");
            executor.setAccessible(true);
            CountDownLatch paused = new CountDownLatch(1);
            // Live provider updates must not replace the injected picks while their filters are checked.
            ((ExecutorService) executor.get(NativeRecs.get(c))).execute(() -> {
                paused.countDown();
                try { resumeFeed.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            check(paused.await(15, TimeUnit.SECONDS), "feed worker paused for fixtures");
            final Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try { checkRecommendations(c, prefs); }
                catch (Throwable e) { failure[0] = e; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            result.putString("stream", "Recommendation source, shelf filtering, tabs and intent checks passed.\n");
        } catch (Throwable e) {
            result.putString("stream", "FAILED: " + e + "\n");
            code = 0;
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (source == null) edit.remove("recommendations"); else edit.putString("recommendations", source);
            if (shelf == null) edit.remove("shelf"); else edit.putString("shelf", shelf);
            edit.commit();
            resumeFeed.countDown();
        }
        finish(code, result);
    }

    /** Fail if either the built-in shelf or a repeated UI read tries to resolve a package on the main thread. */
    private void prepareNative(Context c, SharedPreferences prefs) throws Exception {
        AtomicInteger queries = new AtomicInteger();
        Context guarded = new ContextWrapper(c) {
            @Override public Context getApplicationContext() { return this; }
            @Override public PackageManager getPackageManager() {
                check(Looper.myLooper() != Looper.getMainLooper(), "package resolution on UI thread");
                queries.incrementAndGet();
                return super.getPackageManager();
            }
        };
        NativeRecs nativeRecs = NativeRecs.get(guarded);
        Field field = NativeRecs.class.getDeclaredField("io");
        field.setAccessible(true);
        ExecutorService io = (ExecutorService) field.get(nativeRecs);
        for (boolean builtIn : new boolean[] {true, false}) {
            final Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    Prefs.setRecommendations("apps");
                    if (builtIn) prefs.edit().remove("shelf").commit();
                    else Prefs.setShelf(Collections.singletonList(c.getPackageName()));
                    nativeRecs.invalidate();
                } catch (Throwable e) { failure[0] = e; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            io.submit(() -> {}).get(15, TimeUnit.SECONDS);
        }
        check(queries.get() > 0, "shelf eligibility resolved on worker");
    }

    private static void checkRecommendations(Context c, SharedPreferences prefs) throws Exception {
        prefs.edit().remove("recommendations").commit();
        check(Recs.fromApps(), "key-independent default");
        Prefs.setRecommendations("off");
        Recs recs = Recs.get(c);
        check(!Recs.enabled() && recs.now().isEmpty() && recs.showcase().isEmpty(), "off hides both feeds");
        Prefs.setRecommendations("tmdb");
        check(Recs.enabled() && !Recs.fromApps(), "TMDB selection");
        if (!Recs.hasKey(c)) check(recs.now().isEmpty() && recs.showcase().isEmpty(), "TMDB requires a key");
        check("tmdb".equals(prefs.getString("recommendations", "")), "source persisted");
        Recs.Pick movie = new Recs.Pick(), show = new Recs.Pick();
        movie.id = show.id = 7;
        movie.kind = "movie";
        show.kind = "tv";
        check(!Recs.key(movie).equals(Recs.key(show)), "TMDB identities stay distinct");

        Prefs.setRecommendations("apps");
        Prefs.setShelf(Collections.singletonList(c.getPackageName()));
        NativeRecs nativeRecs = NativeRecs.get(c);
        Recs.Pick video = pick(c.getPackageName(), "preview", 1, "movie", false);
        Recs.Pick series = pick(c.getPackageName(), "preview", 2, "tv", false);
        Recs.Pick clip = pick(c.getPackageName(), "preview", 3, "video", false);
        Recs.Pick music = pick(c.getPackageName(), "watch-next", 1, "music", true);
        Recs.Pick hidden = pick("other.app", "preview", 2, "video", false);
        Field field = NativeRecs.class.getDeclaredField("picks");
        field.setAccessible(true);
        field.set(nativeRecs, new ArrayList<>(Arrays.asList(video, series, clip, music, hidden)));
        check(!Recs.key(video).equals(Recs.key(music)), "provider tables have separate identities");
        // Repeated artwork callbacks and screensaver tabs must only read the cached eligibility.
        for (int i = 0; i < 3; i++) {
            nativeRecs.now();
            nativeRecs.wanted();
            nativeRecs.showcase();
            nativeRecs.refreshIfStale();
        }
        prefs.edit().remove("shelf").commit();
        check(nativeRecs.now().isEmpty() && nativeRecs.wanted().isEmpty() && nativeRecs.showcase().isEmpty(),
                "reset to default does not reuse custom eligibility");
        Prefs.setShelf(Collections.singletonList(c.getPackageName()));
        if (Recs.canRead(c)) {
            check(nativeRecs.now().size() == 4, "only shelf apps contribute");
            check(nativeRecs.now("movie").equals(Collections.singletonList(video)), "Movies filter excludes clips and music");
            check(nativeRecs.now("tv").equals(Collections.singletonList(series)), "Shows filter");
            check(nativeRecs.now("continue").equals(Collections.singletonList(music)), "Watch Next filter");
            Prefs.setShelf(Collections.emptyList());
            check(nativeRecs.now().isEmpty() && nativeRecs.showcase().isEmpty(), "shelf removal hides cached picks");
        } else {
            check(nativeRecs.now().isEmpty() && nativeRecs.showcase().isEmpty(), "permission denial hides cached picks");
        }
        check(NativeRecs.kind(TvContract.PreviewPrograms.TYPE_MOVIE).equals("movie"), "published movie type");
        for (int type : new int[] {TvContract.PreviewPrograms.TYPE_TV_SERIES, TvContract.PreviewPrograms.TYPE_TV_SEASON,
                TvContract.PreviewPrograms.TYPE_TV_EPISODE}) check(NativeRecs.kind(type).equals("tv"), "published show types");
        check(NativeRecs.kind(TvContract.PreviewPrograms.TYPE_CLIP).equals("video"), "clips stay in Explore");
        check(NativeRecs.kind(TvContract.PreviewPrograms.TYPE_TRACK).equals("music"), "music stays in Explore");
        Intent safe = nativeRecs.intent(video);
        check(safe != null && c.getPackageName().equals(safe.getPackage()), "content opens in publisher");
        Intent crossApp = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName("other.app", "other.app.Player"));
        video.link = crossApp.toUri(Intent.URI_INTENT_SCHEME);
        check(nativeRecs.intent(video) == null, "cross-app component rejected");
        Intent selector = new Intent(Intent.ACTION_VIEW);
        selector.setSelector(new Intent(Intent.ACTION_VIEW).setPackage("other.app"));
        video.link = selector.toUri(Intent.URI_INTENT_SCHEME);
        safe = nativeRecs.intent(video);
        check(safe != null && safe.getSelector() == null && c.getPackageName().equals(safe.getPackage()), "selector removed");
        field.set(nativeRecs, new ArrayList<>());
    }

    private static Recs.Pick pick(String pkg, String source, long id, String kind, boolean resume) {
        Recs.Pick p = new Recs.Pick();
        p.pkg = pkg;
        p.source = source;
        p.id = id;
        p.kind = kind;
        p.resume = resume;
        p.link = "https://example.com/watch/" + id;
        return p;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
