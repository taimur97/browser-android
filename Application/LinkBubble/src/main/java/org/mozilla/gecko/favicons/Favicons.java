/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.favicons;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;

import com.linkbubble.MainApplication;
import com.linkbubble.MainService;
import com.linkbubble.R;
import com.linkbubble.util.NetworkConnectivity;

import org.mozilla.gecko.favicons.cache.FaviconCache;
import org.mozilla.gecko.util.NonEvictingLruCache;
import org.mozilla.gecko.util.ThreadUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class Favicons {
    private static final String LOGTAG = "GeckoFavicons";

    // A magic URL representing the app's own favicon, used for about: pages.
    //private static final String BUILT_IN_FAVICON_URL = "about:favicon";

    // Size of the favicon bitmap cache, in bytes (Counting payload only).
    public static final int FAVICON_CACHE_SIZE_BYTES = 512 * 1024;

    // Number of URL mappings from page URL to Favicon URL to cache in memory.
    public static final int NUM_PAGE_URL_MAPPINGS_TO_STORE = 128;

    public static final int NOT_LOADING  = 0;
    public static final int LOADED       = 1;
    public static final int FLAG_PERSIST = 2;
    public static final int FLAG_SCALE   = 4;
    public static final int FLAG_OFFLINE_NO_CACHE = 8;

    protected static Context sContext;

    // The default Favicon to show if no other can be found.
    public static Bitmap sDefaultFavicon;

    // The density-adjusted default Favicon dimensions.
    public static int sDefaultFaviconSize;

    @SuppressLint("UseSparseArrays")
    private final Map<Integer, LoadFaviconTask> mLoadTasks = Collections.synchronizedMap(new HashMap<Integer, LoadFaviconTask>());

    // Cache to hold mappings between page URLs and Favicon URLs. Used to avoid going to the DB when
    // doing so is not necessary.
    private final NonEvictingLruCache<String, String> mPageURLMappings = new NonEvictingLruCache<String, String>(NUM_PAGE_URL_MAPPINGS_TO_STORE);

    public String getFaviconURLForPageURLFromCache(String pageURL) {
        return mPageURLMappings.get(pageURL);
    }

    /**
     * Insert the given pageUrl->faviconUrl mapping into the memory cache of such mappings.
     * Useful for short-circuiting local database access.
     */
    public void putFaviconURLForPageURLInCache(String pageURL, String faviconURL) {
        mPageURLMappings.put(pageURL, faviconURL);
    }

    private FaviconCache mFaviconsCache;

    /**
     * Returns either NOT_LOADING, or LOADED if the onFaviconLoaded call could
     * be made on the main thread.
     * If no listener is provided, NOT_LOADING is returned.
     */
    int dispatchResult(final String pageUrl, final String faviconURL, final Bitmap image,
            final OnFaviconLoadedListener listener) {
        if (listener == null) {
            return NOT_LOADING;
        }

        /* LB_CHANGE
        if (ThreadUtils.isOnUiThread()) {
            listener.onFaviconLoaded(pageUrl, faviconURL, image);
            return LOADED;
        }

        // We want to always run the listener on UI thread.
        ThreadUtils.postToUiThread(new Runnable() {
            @Override
            public void run() {
                listener.onFaviconLoaded(pageUrl, faviconURL, image);
            }
        });
        */
        listener.onFaviconLoaded(pageUrl, faviconURL, image);
        return NOT_LOADING;
    }

    /**
     * Only returns a non-null Bitmap if the entire path is cached -- the
     * page URL to favicon URL, and the favicon URL to in-memory bitmaps.
     *
     * Returns null otherwise.
     */
    public Bitmap getCachedFaviconForSize(final String pageURL, int targetSize) {
        final String faviconURL = mPageURLMappings.get(pageURL);
        if (faviconURL == null) {
            return null;
        }
        return getSizedFaviconFromCache(faviconURL, targetSize);
    }

    /**
     * Get a Favicon as close as possible to the target dimensions for the URL provided.
     * If a result is instantly available from the cache, it is returned and the listener is invoked.
     * Otherwise, the result is drawn from the database or network and the listener invoked when the
     * result becomes available.
     *
     * @param pageURL Page URL for which a Favicon is desired.
     * @param faviconURL URL of the Favicon to be downloaded, if known. If none provided, an educated
     *                    guess is made by the system.
     * @param targetSize Target size of the returned Favicon
     * @param listener Listener to call with the result of the load operation, if the result is not
     *                  immediately available.
     * @return The id of the asynchronous task created, NOT_LOADING if none is created, or
     *         LOADED if the value could be dispatched on the current thread.
     */
    public int getFaviconForSize(String pageURL, String faviconURL, int targetSize, int flags, OnFaviconLoadedListener listener) {
        // Do we know the favicon URL for this page already?
        String cacheURL = faviconURL;
        if (cacheURL == null) {
            cacheURL = mPageURLMappings.get(pageURL);
        }

        // If there's no favicon URL given, try and hit the cache with the default one.
        if (cacheURL == null)  {
            cacheURL = guessDefaultFaviconURL(pageURL);
        }

        // If it's something we can't even figure out a default URL for, just give up.
        if (cacheURL == null) {
            return dispatchResult(pageURL, null, sDefaultFavicon, listener);
        }

        // If the device is offline, display the default favicon until the page reloads.
        if ((flags & FLAG_OFFLINE_NO_CACHE) != 0 && NetworkConnectivity.isConnected(sContext) == false) {
            return dispatchResult(pageURL, null, sDefaultFavicon, listener);
        }

        Bitmap cachedIcon = getSizedFaviconFromCache(cacheURL, targetSize);
        if (cachedIcon != null) {
            return dispatchResult(pageURL, cacheURL, cachedIcon, listener);
        }

        // Check if favicon has failed.
        if (mFaviconsCache.isFailedFavicon(cacheURL)) {
            return dispatchResult(pageURL, cacheURL, sDefaultFavicon, listener);
        }

        // Failing that, try and get one from the database or internet.
        return loadUncachedFavicon(pageURL, faviconURL, flags, targetSize, listener);
    }

    /**
     * Returns the cached Favicon closest to the target size if any exists or is coercible. Returns
     * null otherwise. Does not query the database or network for the Favicon is the result is not
     * immediately available.
     *
     * @param faviconURL URL of the Favicon to query for.
     * @param targetSize The desired size of the returned Favicon.
     * @return The cached Favicon, rescaled to be as close as possible to the target size, if any exists.
     *         null if no applicable Favicon exists in the cache.
     */
    public Bitmap getSizedFaviconFromCache(String faviconURL, int targetSize) {
        return mFaviconsCache.getFaviconForDimensions(faviconURL, targetSize);
    }

    /**
     * Attempts to find a Favicon for the provided page URL from either the mem cache or the database.
     * Does not need an explicit favicon URL, since, as we are accessing the database anyway, we
     * can query the history DB for the Favicon URL.
     * Handy for easing the transition from caching with page URLs to caching with Favicon URLs.
     *
     * A null result is passed to the listener if no value is locally available. The Favicon is not
     * added to the failure cache.
     *
     * @param pageURL Page URL for which a Favicon is wanted.
     * @param targetSize Target size of the desired Favicon to pass to the cache query
     * @param callback Callback to fire with the result.
     * @return The job ID of the spawned async task, if any.
     */
    public int getSizedFaviconForPageFromLocal(final String pageURL, final int targetSize, final OnFaviconLoadedListener callback) {
        // Firstly, try extremely hard to cheat.
        // Have we cached this favicon URL? If we did, we can consult the memcache right away.
        String targetURL = mPageURLMappings.get(pageURL);
        if (targetURL != null) {
            // Check if favicon has failed.
            if (mFaviconsCache.isFailedFavicon(targetURL)) {
                return dispatchResult(pageURL, targetURL, null, callback);
            }

            // Do we have a Favicon in the cache for this favicon URL?
            Bitmap result = getSizedFaviconFromCache(targetURL, targetSize);
            if (result != null) {
                // Victory - immediate response!
                return dispatchResult(pageURL, targetURL, result, callback);
            }
        }

        // No joy using in-memory resources. Go to background thread and ask the database.
        LoadFaviconTask task = new LoadFaviconTask(ThreadUtils.getBackgroundHandler(), this, pageURL, targetURL, 0, callback, targetSize, true);
        int taskId = task.getId();
        mLoadTasks.put(taskId, task);
        task.execute();
        return taskId;
    }

    public int getSizedFaviconForPageFromLocal(final String pageURL, final OnFaviconLoadedListener callback) {
        return getSizedFaviconForPageFromLocal(pageURL, sDefaultFaviconSize, callback);
    }
    /**
     * Helper method to determine the URL of the Favicon image for a given page URL by querying the
     * history database. Should only be called from the background thread - does database access.
     *
     * @param pageURL The URL of a webpage with a Favicon.
     * @return The URL of the Favicon used by that webpage, according to either the History database
     *         or a somewhat educated guess.
     */
    public String getFaviconUrlForPageUrl(String pageURL) {
        // Attempt to determine the Favicon URL from the Tabs datastructure. Can dodge having to use
        // the database sometimes by doing this.
        String targetURL = null;
        /* LB_CHANGE:
        Tab theTab = Tabs.getInstance().getTabForUrl(pageURL);
        if (theTab != null) {
            targetURL = theTab.getFaviconURL();
            if (targetURL != null) {
                return targetURL;
            }
        }

        targetURL = BrowserDB.getFaviconUrlForHistoryUrl(sContext.getContentResolver(), pageURL);
        */
        if (targetURL == null) {
            // Nothing in the history database. Fall back to the default URL and hope for the best.
            targetURL = guessDefaultFaviconURL(pageURL);
        }
        return targetURL;
    }

    /**
     * Helper function to create an async job to load a Favicon which does not exist in the memcache.
     * Contains logic to prevent the repeated loading of Favicons which have previously failed.
     * There is no support for recovery from transient failures.
     *
     * @param pageUrl URL of the page for which to load a Favicon. If null, no job is created.
     * @param faviconUrl The URL of the Favicon to load. If null, an attempt to infer the value from
     *                   the history database will be made, and ultimately an attempt to guess will
     *                   be made.
     * @param flags Flags to be used by the LoadFaviconTask while loading. Currently only one flag
     *              is supported, LoadFaviconTask.FLAG_PERSIST.
     *              If FLAG_PERSIST is set and the Favicon is ultimately loaded from the internet,
     *              the downloaded Favicon is subsequently stored in the local database.
     *              If FLAG_PERSIST is unset, the downloaded Favicon is stored only in the memcache.
     *              FLAG_PERSIST has no effect on loads which come from the database.
     * @param listener The OnFaviconLoadedListener to invoke with the result of this Favicon load.
     * @return The id of the LoadFaviconTask handling this job.
     */
    private int loadUncachedFavicon(String pageUrl, String faviconUrl, int flags, int targetSize, OnFaviconLoadedListener listener) {
        // Handle the case where we have no page url.
        if (TextUtils.isEmpty(pageUrl)) {
            dispatchResult(null, null, null, listener);
            return NOT_LOADING;
        }

        LoadFaviconTask task = new LoadFaviconTask(ThreadUtils.getBackgroundHandler(), this, pageUrl, faviconUrl, flags, listener, targetSize, false);

        int taskId = task.getId();
        mLoadTasks.put(taskId, task);

        task.execute();

        return taskId;
    }

    public void putFaviconInMemCache(String pageUrl, Bitmap image) {
        mFaviconsCache.putSingleFavicon(pageUrl, image);
    }

    public void putFaviconsInMemCache(String pageUrl, Iterator<Bitmap> images, boolean permanently) {
        mFaviconsCache.putFavicons(pageUrl, images, permanently);
    }

    public void clearMemCache() {
        mFaviconsCache.evictAll();
        mPageURLMappings.evictAll();
    }

    public void putFaviconInFailedCache(String faviconURL) {
        mFaviconsCache.putFailed(faviconURL);
    }

    public boolean cancelFaviconLoad(int taskId) {
        if (taskId == NOT_LOADING) {
            return false;
        }

        boolean cancelled;
        synchronized (mLoadTasks) {
            if (!mLoadTasks.containsKey(taskId))
                return false;

            Log.d(LOGTAG, "Cancelling favicon load (" + taskId + ")");

            LoadFaviconTask task = mLoadTasks.get(taskId);
            cancelled = task.cancel(false);
        }
        return cancelled;
    }

    public void close() {
        Log.d(LOGTAG, "Closing Favicons database");

        // Cancel any pending tasks
        synchronized (mLoadTasks) {
            Set<Integer> taskIds = mLoadTasks.keySet();
            Iterator<Integer> iter = taskIds.iterator();
            while (iter.hasNext()) {
                int taskId = iter.next();
                cancelFaviconLoad(taskId);
            }
            mLoadTasks.clear();
        }

        LoadFaviconTask.closeHTTPClient();
    }

    /**
     * Get the dominant colour of the Favicon at the URL given, if any exists in the cache.
     *
     * @param url The URL of the Favicon, to be used as the cache key for the colour value.
     * @return The dominant colour of the provided Favicon.
     */
    public int getFaviconColor(String url) {
        return mFaviconsCache.getDominantColor(url);
    }

    /**
     * Called by GeckoApp on startup to pass this class a reference to the GeckoApp object used as
     * the application's Context.
     * Consider replacing with references to a staticly held reference to the GeckoApp object.
     *
     * @param context A reference to the GeckoApp instance.
     */
    public static void attachToContext(Context context) {
        final Resources res = context.getResources();
        sContext = context;

        // Decode the default Favicon ready for use.
        sDefaultFavicon = BitmapFactory.decodeResource(res, R.drawable.fallback_favicon);

        sDefaultFaviconSize = res.getDimensionPixelSize(R.dimen.favicon_bg);
    }

    public Favicons() {
        this(FAVICON_CACHE_SIZE_BYTES);
    }

    public Favicons(int cacheSize) {
        final Resources res = sContext.getResources();
        mFaviconsCache = new FaviconCache(cacheSize, res.getDimensionPixelSize(R.dimen.favicon_largest_interesting_size));

        // Initialize page mappings for each of our special pages.
        // LB_CHANGE:
        //for (String url : AboutPages.getDefaultIconPages()) {
        //    mPageURLMappings.putWithoutEviction(url, BUILT_IN_FAVICON_URL);
        //}

        // Load and cache the built-in favicon in each of its sizes.
        // TODO: don't open the zip twice!
        /* LB_CHANGE:
        ArrayList<Bitmap> toInsert = new ArrayList<Bitmap>(2);
        toInsert.add(loadBrandingBitmap(context, "favicon64.png"));
        toInsert.add(loadBrandingBitmap(context, "favicon32.png"));
        putFaviconsInMemCache(BUILT_IN_FAVICON_URL, toInsert.iterator(), true);
        */
    }

    /**
     * Compute a string like:
     * "jar:jar:file:///data/app/org.mozilla.firefox-1.apk!/assets/omni.ja!/chrome/chrome/content/branding/favicon64.png"
     */
    /* LB_CHANGE:
    private static String getBrandingBitmapPath(Context context, String name) {
        final String apkPath = context.getPackageResourcePath();
        return "jar:jar:" + new File(apkPath).toURI() + "!/" +
               AppConstants.OMNIJAR_NAME + "!/" +
               "chrome/chrome/content/branding/" + name;
    }

    private static Bitmap loadBrandingBitmap(Context context, String name) {
        Bitmap b = GeckoJarReader.getBitmap(context.getResources(),
                                            getBrandingBitmapPath(context, name));
        if (b == null) {
            throw new IllegalStateException("Bitmap " + name + " missing from JAR!");
        }
        return b;
    }*/

    /**
     * Helper method to get the default Favicon URL for a given pageURL. Generally: somewhere.com/favicon.ico
     *
     * @param pageURL Page URL for which a default Favicon URL is requested
     * @return The default Favicon URL.
     */
    public String guessDefaultFaviconURL(String pageURL) {
        // Special-casing for about: pages. The favicon for about:pages which don't provide a link tag
        // is bundled in the database, keyed only by page URL, hence the need to return the page URL
        // here. If the database ever migrates to stop being silly in this way, this can plausibly
        // be removed.
        /* LB_CHANGE:
        if (AboutPages.isAboutPage(pageURL) || pageURL.startsWith("jar:")) {
            return pageURL;
        } */

        try {
            // Fall back to trying "someScheme:someDomain.someExtension/favicon.ico".
            URI u = new URI(pageURL);
            return new URI(u.getScheme(),
                           u.getAuthority(),
                           "/favicon.ico", null,
                           null).toString();
        } catch (URISyntaxException e) {
            Log.e(LOGTAG, "URISyntaxException getting default favicon URL", e);
            return null;
        }
    }

    public void removeLoadTask(int taskId) {
        mLoadTasks.remove(taskId);
    }

    /**
     * Method to wrap FaviconCache.isFailedFavicon for use by LoadFaviconTask.
     *
     * @param faviconURL Favicon URL to check for failure.
     */
    boolean isFailedFavicon(String faviconURL) {
        return mFaviconsCache.isFailedFavicon(faviconURL);
    }

    /**
     * Sidestep the cache and get, from either the database or the internet, the largest available
     * Favicon for the given page URL. Useful for creating homescreen shortcuts without being limited
     * by possibly low-resolution values in the cache.
     * Deduces the favicon URL from the history database and, ultimately, guesses.
     *
     * @param url Page URL to get a large favicon image fro.
     * @param onFaviconLoadedListener Listener to call back with the result.
     */
    public void getLargestFaviconForPage(String url, OnFaviconLoadedListener onFaviconLoadedListener) {
        loadUncachedFavicon(url, null, 0, -1, onFaviconLoadedListener);
    }
}
