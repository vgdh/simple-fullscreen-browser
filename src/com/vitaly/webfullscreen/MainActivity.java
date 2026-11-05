package com.vitaly.webfullscreen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebRequestError;

/**
 * Fullscreen kiosk browser for Kodi, powered by a bundled GeckoView engine.
 *
 * Why not android.webkit.WebView: the engine then belongs to the device
 * firmware. On this TV that firmware is Chromium 85, which silently discards
 * any stylesheet wrapped in @layer (Chrome 99+) — modern sites render as
 * unstyled text. GeckoView ships the engine inside the APK, so the rendering
 * version no longer depends on what the vendor shipped years ago.
 *
 * URL resolution order:
 *   1. the launching intent (ACTION_VIEW data, or string extra "url") —
 *      Kodi keeps control over what opens
 *   2. the address saved on first run (SharedPreferences "start_url")
 *   3. nothing saved yet -> ask once, then never again
 *
 * Clearing the app's data wipes the preference, so the prompt comes back.
 *
 * Back: leaves page history first; at the page root it exits to the caller
 * (Kodi). No browser UI is ever drawn, and no lock task is used.
 */
public class MainActivity extends Activity {

    public static final String EXTRA_URL = "url";

    private static final String PREFS = "settings";
    private static final String KEY_URL = "start_url";

    /** One-time profile wipe marker: bump the tag to wipe once on next launch. */
    private static final String KEY_WIPE = "wipe_tag";
    private static final String WIPE_TAG = "2.5";

    /** Show a one-line boot state on screen: no console exists on the TV. */
    private static final boolean BOOT_DIAG = true;

    /** Diagnostic phase: show on screen which BACK branch ran and which build
     *  is actually installed. Flip to false once the behaviour is confirmed. */
    private static final boolean BACK_DIAGNOSTIC = false;

    private static final String TAG = "KioskBack";

    /**
     * GeckoRuntime is one-per-process: GeckoRuntime.create() calls init(), which
     * returns false and throws IllegalStateException("Failed to initialize
     * GeckoRuntime") when a runtime already exists in this process. Exiting the
     * activity does not kill the process, so creating a runtime in onCreate()
     * made the *second* launch of the app die instantly, and the third one
     * inherit a half-initialised engine (white screen). Keep one runtime alive
     * for the whole process and reuse it for every activity instance.
     */
    private static GeckoRuntime sRuntime;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoView view;
    private FrameLayout root;

    /** Kept up to date by NavigationDelegate; 159 dropped getSessionHistory(). */
    private boolean canGoBack;

    /** Set once the page has painted; gates the reload-on-resume below. */
    private boolean pageLoaded;

    /** The first onResume() follows onCreate(), which already started a load. */
    private boolean firstResume = true;

    /** Guards against BACK being dispatched twice (onKeyDown + onBackPressed). */
    private boolean exiting;

    /** Last committed location, fed by onLocationChange (159 has no getCurrentUri). */
    private String currentUri;

    /** URL the last load() asked for; compared against currentUri for BACK. */
    private String targetUrl;

    /** Retried once already after a load that never reported onPageStop. */
    private boolean retried;

    /** Pending "did the page actually arrive" check. */
    private Runnable loadWatchdog;

    private TextView diagView;
    private String label = "?";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new GeckoView(this);
        view.setBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (BOOT_DIAG) {
            diagView = new TextView(this);
            diagView.setTextColor(Color.WHITE);
            diagView.setTextSize(11f);
            diagView.setPadding(12, 8, 12, 8);
            diagView.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            root.addView(diagView, lp);
        }
        label = buildLabel();

        GeckoRuntimeSettings rtSettings = new GeckoRuntimeSettings.Builder()
                // Lets you attach Firefox desktop via about:debugging over USB,
                // which is the only practical way to inspect this TV remotely.
                // Harmless when unused: it opens no port and shows no UI.
                .remoteDebuggingEnabled(true)
                .aboutConfigEnabled(true)
                // Remote page console -> logcat (adb logcat -s GeckoView).
                .consoleOutput(false)
                .build();
        runtime = runtimeForProcess(this, rtSettings);

        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                // Desktop UA + desktop viewport: the TV is a big fixed screen,
                // so it should get the desktop layout, not a phone layout.
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
                // No browser chrome exists here, so viewport units must be
                // measured against the whole screen (100vh == screen height).
                .displayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
                .allowJavascript(true)
                .build();

        session = new GeckoSession(sessionSettings);
        session.setProgressDelegate(new ProgressDelegate());
        session.setNavigationDelegate(new NavigationDelegate());
        session.setContentDelegate(new ContentDelegate());
        session.open(runtime);
        view.setSession(session);
        view.setFocusable(true);
        view.requestFocus();

        enterImmersive();
        diag("created, runtime=" + (runtime != null));
        startWithIntentOrSaved(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlFrom(intent);
        if (url != null) {
            if (storedUrl() == null) persist(url);
            load(url);
        } else if (session != null && pageLoaded) {
            // Relaunched with no explicit URL: the page on screen may be hours
            // old, which is what a kiosk dashboard must never show. Bypass the
            // HTTP cache too, otherwise "reload" can repaint the same bytes.
            pageLoaded = false;
            session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
        }
    }

    @Override
    protected void onPause() {
        if (session != null) session.setActive(false);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean comingBack = !firstResume;
        firstResume = false;

        if (session != null) {
            session.setActive(true);
            session.setFocused(true);
        }
        enterImmersive();

        // Returning from the background (screen off, Kodi, another app) means
        // whatever the dashboard fetched last time is stale: reload it.
        if (comingBack && pageLoaded && session != null) {
            pageLoaded = false;
            session.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE);
        }
    }

    /** Return the process-wide runtime, creating it on first use only. */
    private static synchronized GeckoRuntime runtimeForProcess(
            Context ctx, GeckoRuntimeSettings settings) {
        if (sRuntime != null) return sRuntime;
        try {
            sRuntime = GeckoRuntime.create(ctx.getApplicationContext(), settings);
        } catch (IllegalStateException e) {
            // A runtime already owns this process; adopt it instead of dying.
            sRuntime = GeckoRuntime.getDefault(ctx);
            if (sRuntime == null) throw e;
        }
        Log.i(TAG, "GeckoRuntime: " + (sRuntime != null ? "ready" : "null"));
        return sRuntime;
    }

    @Override
    protected void onDestroy() {
        if (view != null) view.releaseSession();
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
            if (view != null) view.requestFocus();
        }
    }

    // ---------------------------------------------------------------- delegates

    private class ProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStop(GeckoSession s, boolean success) {
            // Gecko paints its own progress; we only care about restoring the
            // immersive bars, which video fullscreen can knock out.
            pageLoaded = true;
            if (loadWatchdog != null) {
                mainHandler.removeCallbacks(loadWatchdog);
                loadWatchdog = null;
            }
            diag("page-stop success=" + success + " " + shortUri(currentUri));
            enterImmersive();
        }
    }

    private class NavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        @Override
        public GeckoResult<String> onLoadError(GeckoSession s, String uri,
                                               WebRequestError error) {
            String code = "code=" + error.code + " cat=" + error.category;
            diag("load-error " + code + " " + shortUri(uri));
            // Returning null shows GeckoView's own Chrome-style error page, which
            // on a kiosk reads as a broken app. Draw our own instead: black
            // background, the failing address and the real Gecko code.
            return GeckoResult.fromValue(
                    "<html><body style='margin:0;background:#000;color:#ddd;"
                            + "font:16px/1.5 monospace;padding:40px'>"
                            + "<div style='font-size:22px;color:#fff'>Page did not load</div>"
                            + "<p>" + escape(uri) + "</p><p style='color:#f0a020'>"
                            + escape(code) + "</p>"
                            + "<p style='color:#888'>BACK returns to the dashboard.</p>"
                            + "</body></html>");
        }

        @Override
        public void onLocationChange(GeckoSession s, String url,
                                     java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     Boolean hasUserGesture) {
            currentUri = url;
            diag("location " + shortUri(url));
        }
    }

    private class ContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onFullScreen(GeckoSession s, boolean fullScreen) {
            // The activity is already fullscreen; HTML5 fullscreen only needs
            // the system bars re-hidden once the player takes over the view.
            enterImmersive();
        }

        @Override
        public void onTitleChange(GeckoSession s, String title) {
            // No title bar is drawn, but keeping it costs nothing and makes
            // "recent apps" on the TV readable.
            if (title != null && title.length() > 0) setTitle(title);
        }
    }

    // ---------------------------------------------------------------- url logic

    private void startWithIntentOrSaved(Intent intent) {
        String url = urlFrom(intent);
        if (url != null && storedUrl() == null) {
            // Explicit request (Kodi): remember it as the start page.
            persist(url);
        }
        final String target = url != null ? url : storedUrl();
        clearProfileOnce(() -> {
            if (target != null) load(target);
            else promptForUrl();
        });
    }

    /**
     * Wipe the Gecko profile once, before the first page load of a new build.
     *
     * Homer ships a service worker that precaches index.html with a fixed
     * revision and answers navigations from that cache. A stale worker on the
     * TV therefore serves the pre-patch page indefinitely: the injected
     * kiosk-keys.js tag never loads, remote arrows do nothing, and
     * force-stopping the app does not help because the worker lives in the
     * profile on disk. LOAD_FLAGS_BYPASS_CACHE does not reach the service
     * worker either. clearData(ALL) drops the worker, its caches and the HTTP
     * cache, so the next load comes straight off the network once.
     */
    private void clearProfileOnce(final Runnable next) {
        if (WIPE_TAG.equals(prefs().getString(KEY_WIPE, ""))) {
            next.run();
            return;
        }

        // Clear the marker *before* wiping: clearData can fail or hang, and a
        // marker written only on success made the wipe repeat on every launch,
        // which is what turns a slow first paint into a permanent white screen.
        prefs().edit().putString(KEY_WIPE, WIPE_TAG).apply();

        // clearData is async and this gates the first page load, so a callback
        // that never arrives would leave a black screen with no way back. Load
        // anyway after a short grace period; whichever path fires first wins.
        final boolean[] fired = {false};
        final Runnable go = () -> {
            if (fired[0]) return;
            fired[0] = true;
            mainHandler.post(next);
        };

        Log.i(TAG, "profile wipe for " + WIPE_TAG);
        diag("profile wipe");
        try {
            runtime.getStorageController()
                    .clearData(StorageController.ClearFlags.ALL)
                    .accept(
                            ignored -> {
                                Log.i(TAG, "profile wiped");
                                go.run();
                            },
                            err -> {
                                Log.w(TAG, "profile wipe failed: " + err);
                                go.run();
                            });
            mainHandler.postDelayed(go, 1500);
        } catch (Throwable t) {
            Log.w(TAG, "profile wipe unavailable: " + t);
            go.run();
        }
    }

    private void load(String url) {
        if (session == null) return;
        targetUrl = url;
        retried = false;
        pageLoaded = false;
        diag("load " + shortUri(url));
        session.loadUri(url);
        armLoadWatchdog();
        enterImmersive();
    }

    /**
     * A load that never reports onPageStop leaves an endless black screen and no
     * way to tell whether the engine, the network or the URL is at fault. Check
     * after 8s; if the page never arrived, re-issue it once with the HTTP cache
     * bypassed, then say so on screen instead of staying silent.
     */
    private void armLoadWatchdog() {
        if (loadWatchdog != null) mainHandler.removeCallbacks(loadWatchdog);
        loadWatchdog = () -> {
            loadWatchdog = null;
            if (pageLoaded || isFinishing() || session == null) return;
            if (!retried && targetUrl != null) {
                retried = true;
                diag("no page-stop after 8s, retrying");
                session.load(new GeckoSession.Loader()
                        .uri(targetUrl)
                        .flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE));
                armLoadWatchdog();
            } else {
                diag("still blank: " + shortUri(targetUrl));
            }
        };
        mainHandler.postDelayed(loadWatchdog, 8000);
    }

    /** One-line boot state in the corner. Costs nothing when BOOT_DIAG is off. */
    private void diag(String msg) {
        Log.i(TAG, "diag: " + msg);
        if (!BOOT_DIAG || diagView == null) return;
        diagView.setText(label + "\n" + msg);
    }

    private static String shortUri(String uri) {
        if (uri == null) return "(none)";
        return uri.length() > 48 ? uri.substring(0, 48) + "…" : uri;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String storedUrl() {
        String v = prefs().getString(KEY_URL, null);
        if (v == null) return null;
        v = v.trim();
        return v.length() == 0 ? null : v;
    }

    private void persist(String url) {
        prefs().edit().putString(KEY_URL, url).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String urlFrom(Intent intent) {
        if (intent == null) return null;
        String extra = intent.getStringExtra(EXTRA_URL);
        if (extra != null && extra.trim().length() > 0) return extra.trim();
        return intent.getDataString();
    }

    /** Ask once for the start page. Cancel = nothing to show, so exit. */
    private void promptForUrl() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        String prefill = getString(R.string.default_url);
        input.setText(prefill);
        input.setSelection(prefill.length());

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.setup_title)
                .setMessage(R.string.setup_message)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                exitApp();
                            }
                        })
                .create();

        // OK is wired manually so an empty field keeps the prompt up instead of
        // leaving a dead black screen.
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String url = normalize(input.getText().toString());
                                if (url == null) {
                                    Toast.makeText(MainActivity.this,
                                            R.string.setup_empty, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                persist(url);
                                dialog.dismiss();
                                load(url);
                            }
                        });
            }
        });

        // Enter on the soft keyboard = OK.
        input.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            }
        });

        dialog.show();
    }

    /** Trim, add http:// when the user typed a bare host, reject empties. */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.length() == 0) return null;
        if (!u.contains("://")) u = "http://" + u;
        int hostStart = u.indexOf("://") + 3;
        if (u.length() <= hostStart || u.charAt(hostStart) == '/') return null;
        return u;
    }

    // ---------------------------------------------------------------- chrome

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** Back: page history first, then exit to whoever launched us. */
    private void handleBack() {
        if (exiting) return;              // onKeyDown and onBackPressed both fire on some builds

        // Exit once we are on the start page again, instead of walking the
        // whole session history: a failed navigation leaves its own entry, so
        // "canGoBack" alone can stay true forever and BACK never exits.
        boolean offStart = currentUri != null && targetUrl != null
                && !samePage(currentUri, targetUrl);
        boolean history = offStart && session != null && canGoBack;
        String what = history ? "goBack (page history)" : "exitApp";
        Log.i(TAG, "BACK canGoBack=" + canGoBack + " offStart=" + offStart
                + " -> " + what + " build=" + label);
        diag("BACK offStart=" + offStart + " -> " + what);
        if (BACK_DIAGNOSTIC) {
            Toast.makeText(this, "BACK -> " + what + "\n" + buildLabel(),
                    Toast.LENGTH_LONG).show();
        }
        if (history) {
            session.goBack();
            return;
        }
        exitApp();
    }

    /** Same document? Trailing slash and empty path must not count as a change. */
    private static boolean samePage(String a, String b) {
        return trimSlash(a).equals(trimSlash(b));
    }

    private static String trimSlash(String u) {
        if (u == null) return "";
        int q = u.indexOf('?');
        String base = q >= 0 ? u.substring(0, q) : u;
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** versionName (versionCode) of the package that is actually installed. */
    private String buildLabel() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            int code = Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + " vc" + code;
        } catch (Exception e) {
            return "unknown build";
        }
    }

    /**
     * Leave for real. finish() alone only ends this activity and leaves the
     * task sitting behind Kodi — reopening then resumes the same instance with
     * the same stale page. finishAndRemoveTask() drops the task, so the next
     * launch is a cold start with a fresh load.
     */
    private void exitApp() {
        exiting = true;
        Log.i(TAG, "exitApp: finishAndRemoveTask(), task id " + getTaskId());
        if (Build.VERSION.SDK_INT >= 21) {
            finishAndRemoveTask();
            Log.i(TAG, "exitApp: isFinishing=" + isFinishing());
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Remotes and keyboards send KEYCODE_BACK without going through
        // onBackPressed on some devices.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
