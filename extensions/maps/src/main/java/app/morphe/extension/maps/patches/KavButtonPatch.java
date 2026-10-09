package app.morphe.extension.maps.patches;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * GigiMaps: a button in the place sheet header (next to Share) that opens GigiKav
 * with a bus route to the selected place.
 *
 * The bytecode patch calls {@link #onPlace(Object)} whenever the place-sheet header
 * view model is given a place, and {@link #onActivity(Activity)} from MapsActivity.
 * Everything here goes through debug strings Maps keeps across versions
 * ("Placemark [name]@..", "lat/lng: (a,b)") rather than obfuscated names.
 */
public final class KavButtonPatch {
    private static final String TAG = "GigiMaps";
    private static final String KAV_PACKAGE = "uk.noammm.kav";
    private static final String BUTTON_TAG = "gigimaps-kav-button";
    private static final long SCAN_THROTTLE_MS = 350;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static volatile double lat = Double.NaN;
    private static volatile double lng = Double.NaN;
    private static volatile String placeName;

    private static Class<?> cachedPlaceClass;
    private static Method cachedLatLngGetter;
    private static String shareLabel;
    private static long lastScan;
    private static boolean scanPosted;

    private KavButtonPatch() {
    }

    // ---- hooks ----

    public static void onActivity(final Activity activity) {
        activityRef = new WeakReference<>(activity);
        // Called at the top of onCreate: touching the window now would install the decor
        // before Maps sets up its window features, so attach once onCreate has finished.
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                attach(activity);
            }
        });
    }

    private static void attach(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            requestScan(false);
                        }
                    });
            Log.i(TAG, "attached to " + activity.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "onActivity failed", t);
        }
    }

    public static void onPlace(Object placemark) {
        if (placemark == null) return;
        try {
            double[] ll = latLngOf(placemark);
            if (ll == null) {
                Log.w(TAG, "no coordinates on " + placemark);
                return;
            }
            lat = ll[0];
            lng = ll[1];
            placeName = nameOf(placemark);
            Log.d(TAG, "place: " + placeName + " @ " + lat + "," + lng);
            requestScan(true);
        } catch (Throwable t) {
            Log.e(TAG, "onPlace failed", t);
        }
    }

    // ---- place extraction ----

    /** "Placemark [Some Name]@1a2b3c" -> "Some Name". */
    private static String nameOf(Object placemark) {
        String s = String.valueOf(placemark);
        int start = s.indexOf('[');
        int end = s.lastIndexOf("]@");
        if (start < 0 || end <= start) return null;
        String name = s.substring(start + 1, end).trim();
        return name.isEmpty() ? null : name;
    }

    private static double[] latLngOf(Object placemark) throws Exception {
        Class<?> cls = placemark.getClass();
        if (cls != cachedPlaceClass) {
            cachedPlaceClass = cls;
            cachedLatLngGetter = findLatLngGetter(cls);
            Log.i(TAG, "lat/lng getter: " + cachedLatLngGetter);
        }
        if (cachedLatLngGetter == null) return null;
        Object point = cachedLatLngGetter.invoke(placemark);
        return point == null ? null : parseLatLng(String.valueOf(point));
    }

    /** The no-arg getter whose return type looks like Maps' LatLng (two double fields). */
    private static Method findLatLngGetter(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers())) continue;
            if (isLatLngType(m.getReturnType())) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static boolean isLatLngType(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.getName().startsWith("java")) return false;
        int doubles = 0;
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != double.class) return false;
            doubles++;
        }
        return doubles == 2;
    }

    /** "lat/lng: (32.08,34.78)" */
    private static double[] parseLatLng(String s) {
        int open = s.indexOf('(');
        int comma = s.indexOf(',', open);
        int close = s.indexOf(')', comma);
        if (!s.startsWith("lat/lng") || open < 0 || comma < 0 || close < 0) return null;
        try {
            return new double[]{
                    Double.parseDouble(s.substring(open + 1, comma).trim()),
                    Double.parseDouble(s.substring(comma + 1, close).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- button injection ----

    private static void requestScan(boolean force) {
        if (force) lastScan = 0;
        if (scanPosted) return;
        scanPosted = true;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                scanPosted = false;
                long now = System.currentTimeMillis();
                if (now - lastScan < SCAN_THROTTLE_MS) return;
                lastScan = now;
                scan();
            }
        });
        if (force) {
            // The sheet may not be laid out yet when the place arrives.
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    lastScan = 0;
                    scan();
                }
            }, 600);
        }
    }

    private static void scan() {
        try {
            if (Double.isNaN(lat)) return;
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            String label = shareLabel(activity);
            if (label == null) return;
            findAndInject(activity.getWindow().getDecorView(), label);
        } catch (Throwable t) {
            Log.e(TAG, "scan failed", t);
        }
    }

    private static void findAndInject(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (isHeaderShare(view, label)) {
            inject(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findAndInject(group.getChildAt(i), label);
            }
        }
    }

    /** The header's Share is icon-only; the action chips also say Share but show text. */
    private static boolean isHeaderShare(View v, String label) {
        CharSequence desc = v.getContentDescription();
        if (desc == null || !label.equalsIgnoreCase(desc.toString().trim())) return false;
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) return false;
        return v.getWidth() > 0 && v.getHeight() > 0;
    }

    private static void inject(View share) {
        // Climb to the horizontal row holding the Share icon's container.
        View child = share;
        ViewParent parent = share.getParent();
        for (int depth = 0; depth < 4 && parent instanceof ViewGroup; depth++) {
            if (parent instanceof LinearLayout
                    && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL) {
                break;
            }
            child = (View) parent;
            parent = parent.getParent();
        }
        if (!(parent instanceof LinearLayout)) {
            Log.w(TAG, "no horizontal row around Share; parent chain starts at "
                    + share.getParent().getClass().getName());
            return;
        }
        LinearLayout row = (LinearLayout) parent;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (BUTTON_TAG.equals(row.getChildAt(i).getTag())) return; // already there
        }

        Context ctx = share.getContext();
        int size = Math.max(share.getWidth(), share.getHeight());
        ImageButton button = new ImageButton(ctx);
        button.setTag(BUTTON_TAG);
        button.setContentDescription("Bus route in Kav");
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = size / 4;
        button.setPadding(pad, pad, pad, pad);
        button.setImageDrawable(kavIcon(ctx));
        button.setBackground(borderlessRipple(ctx));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openKav(v.getContext());
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(button, row.indexOfChild(child), lp);
        Log.i(TAG, "button added to " + row.getClass().getName());
    }

    private static Drawable kavIcon(Context ctx) {
        try {
            return ctx.getPackageManager().getApplicationIcon(KAV_PACKAGE);
        } catch (Throwable ignored) {
            return ctx.getResources().getDrawable(android.R.drawable.ic_menu_directions, ctx.getTheme());
        }
    }

    private static Drawable borderlessRipple(Context ctx) {
        TypedArray a = ctx.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackgroundBorderless});
        try {
            return a.getDrawable(0);
        } finally {
            a.recycle();
        }
    }

    /** Maps' own "Share" string, so this works in any UI language. */
    private static String shareLabel(Context ctx) {
        if (shareLabel != null) return shareLabel;
        String[] packages = {ctx.getPackageName(), "com.google.android.apps.maps"};
        for (String pkg : packages) {
            int id = ctx.getResources().getIdentifier("SHARE", "string", pkg);
            if (id != 0) return shareLabel = ctx.getString(id).trim();
        }
        Log.w(TAG, "SHARE string not found");
        return null;
    }

    // ---- launch ----

    private static void openKav(Context ctx) {
        if (Double.isNaN(lat)) return;
        Uri.Builder uri = new Uri.Builder().scheme("moovit").authority("directions")
                .appendQueryParameter("dest_lat", String.format(Locale.US, "%.6f", lat))
                .appendQueryParameter("dest_lon", String.format(Locale.US, "%.6f", lng));
        if (placeName != null) uri.appendQueryParameter("dest_name", placeName);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri.build())
                .setPackage(KAV_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
            Log.i(TAG, "opened Kav: " + intent.getData());
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "GigiKav isn't installed", Toast.LENGTH_SHORT).show();
        }
    }
}
