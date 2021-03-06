/*
 * Copyright (c) 2013. wyouflf (wyouflf@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lidroid.xutils.bitmap.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import com.lidroid.xutils.BitmapUtils;
import com.lidroid.xutils.bitmap.BitmapCommonUtils;
import com.lidroid.xutils.bitmap.BitmapDisplayConfig;
import com.lidroid.xutils.bitmap.BitmapGlobalConfig;
import com.lidroid.xutils.util.IOUtils;
import com.lidroid.xutils.util.LogUtils;
import com.lidroid.xutils.util.core.LruDiskCache;
import com.lidroid.xutils.util.core.LruMemoryCache;


public class BitmapCache {

    private final int DISK_CACHE_INDEX = 0;

    private LruDiskCache mDiskLruCache;
    private LruMemoryCache<MemoryCacheKey, Bitmap> mMemoryCache;

    private final Object mDiskCacheLock = new Object();
    private boolean isDiskCacheReadied = false;

    private BitmapGlobalConfig globalConfig;

    /**
     * Creating a new ImageCache object using the specified parameters.
     *
     * @param globalConfig The cache parameters to use to initialize the cache
     */
    public BitmapCache(BitmapGlobalConfig globalConfig) {
        if (globalConfig == null) throw new IllegalArgumentException("globalConfig may not be null");
        this.globalConfig = globalConfig;
    }


    /**
     * Initialize the memory cache
     */
    public void initMemoryCache() {
        if (!globalConfig.isMemoryCacheEnabled()) return;

        // Set up memory cache
        if (mMemoryCache != null) {
            try {
                clearMemoryCache();
            } catch (Throwable e) {
            }
        }
        mMemoryCache = new LruMemoryCache<MemoryCacheKey, Bitmap>(globalConfig.getMemoryCacheSize()) {
            /**
             * Measure item size in bytes rather than units which is more practical
             * for a bitmap cache
             */
            @Override
            protected int sizeOf(MemoryCacheKey key, Bitmap bitmap) {
                if (bitmap == null) return 0;
                return bitmap.getRowBytes() * bitmap.getHeight();
            }
        };
    }

    /**
     * Initializes the disk cache.  Note that this includes disk access so this should not be
     * executed on the main/UI thread. By default an ImageCache does not initialize the disk
     * cache when it is created, instead you should call initDiskCache() to initialize it on a
     * background thread.
     */
    public void initDiskCache() {
        if (!globalConfig.isDiskCacheEnabled()) return;

        // Set up disk cache
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
                File diskCacheDir = new File(globalConfig.getDiskCachePath());
                if (!diskCacheDir.exists()) {
                    diskCacheDir.mkdirs();
                }
                long availableSpace = BitmapCommonUtils.getAvailableSpace(diskCacheDir);
                long diskCacheSize = globalConfig.getDiskCacheSize();
                diskCacheSize = availableSpace > diskCacheSize ? diskCacheSize : availableSpace;
                try {
                    mDiskLruCache = LruDiskCache.open(diskCacheDir, 1, 1, diskCacheSize);
                    mDiskLruCache.setDiskCacheFileNameGenerator(globalConfig.getDiskCacheFileNameGenerator());
                } catch (Throwable e) {
                    mDiskLruCache = null;
                    LogUtils.e(e.getMessage(), e);
                }
            }
            isDiskCacheReadied = true;
            mDiskCacheLock.notifyAll();
        }
    }

    public void setMemoryCacheSize(int maxSize) {
        if (mMemoryCache != null) {
            mMemoryCache.setMaxSize(maxSize);
        }
    }

    public void setDiskCacheSize(int maxSize) {
        if (mDiskLruCache != null) {
            mDiskLruCache.setMaxSize(maxSize);
        }
    }

    public void setDiskCacheFileNameGenerator(LruDiskCache.DiskCacheFileNameGenerator diskCacheFileNameGenerator) {
        if (mDiskLruCache != null && diskCacheFileNameGenerator != null) {
            mDiskLruCache.setDiskCacheFileNameGenerator(diskCacheFileNameGenerator);
        }
    }

    public Bitmap downloadBitmap(String uri, BitmapDisplayConfig config, final BitmapUtils.BitmapLoadTask<?> task) {

        BitmapMeta bitmapMeta = new BitmapMeta();

        OutputStream outputStream = null;
        LruDiskCache.Snapshot snapshot = null;

        try {

            Bitmap bitmap = null;
            // try download to disk
            if (globalConfig.isDiskCacheEnabled()) {
                synchronized (mDiskCacheLock) {
                    // Wait for disk cache to initialize
                    while (!isDiskCacheReadied) {
                        try {
                            mDiskCacheLock.wait();
                        } catch (Throwable e) {
                        }
                    }

                    if (mDiskLruCache != null) {
                        try {
                            snapshot = mDiskLruCache.get(uri);
                            if (snapshot == null) {
                                LruDiskCache.Editor editor = mDiskLruCache.edit(uri);
                                if (editor != null) {
                                    outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
                                    bitmapMeta.expiryTimestamp = globalConfig.getDownloader().downloadToStream(uri, outputStream, task);
                                    if (bitmapMeta.expiryTimestamp < 0) {
                                        editor.abort();
                                        return null;
                                    } else {
                                        editor.setEntryExpiryTimestamp(bitmapMeta.expiryTimestamp);
                                        editor.commit();
                                    }
                                    snapshot = mDiskLruCache.get(uri);
                                }
                            }
                            if (snapshot != null) {
                                bitmapMeta.inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
                                bitmap = decodeBitmapMeta(bitmapMeta, config);
                                if (bitmap == null) {
                                    bitmapMeta.inputStream = null;
                                    mDiskLruCache.remove(uri);
                                }
                            }
                        } catch (Throwable e) {
                            LogUtils.e(e.getMessage(), e);
                        }
                    }
                }
            }

            // try download to memory stream
            if (bitmap == null) {
                outputStream = new ByteArrayOutputStream();
                bitmapMeta.expiryTimestamp = globalConfig.getDownloader().downloadToStream(uri, outputStream, task);
                if (bitmapMeta.expiryTimestamp < 0) {
                    return null;
                } else {
                    bitmapMeta.data = ((ByteArrayOutputStream) outputStream).toByteArray();
                    bitmap = decodeBitmapMeta(bitmapMeta, config);
                }
            }

            if (bitmap != null) {
                bitmap = rotateBitmapIfNeeded(uri, config, bitmap);
                addBitmapToMemoryCache(uri, config, bitmap, bitmapMeta.expiryTimestamp);
            }
            return bitmap;
        } catch (Throwable e) {
            LogUtils.e(e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(outputStream);
            IOUtils.closeQuietly(snapshot);
        }

        return null;
    }

    private void addBitmapToMemoryCache(String uri, BitmapDisplayConfig config, Bitmap bitmap, long expiryTimestamp) throws IOException {
        if (uri != null && bitmap != null && globalConfig.isMemoryCacheEnabled() && mMemoryCache != null) {
            MemoryCacheKey key = new MemoryCacheKey(uri, config == null ? null : config.toString());
            mMemoryCache.put(key, bitmap, expiryTimestamp);
        }
    }

    /**
     * Get the bitmap from memory cache.
     *
     * @param uri    Unique identifier for which item to get
     * @param config
     * @return The bitmap if found in cache, null otherwise
     */
    public Bitmap getBitmapFromMemCache(String uri, BitmapDisplayConfig config) {
        if (mMemoryCache != null && globalConfig.isMemoryCacheEnabled()) {
            MemoryCacheKey key = new MemoryCacheKey(uri, config == null ? null : config.toString());
            return mMemoryCache.get(key);
        }
        return null;
    }

    /**
     * Get the bitmap file from disk cache.
     *
     * @param uri Unique identifier for which item to get
     * @return The file if found in cache.
     */
    public File getBitmapFileFromDiskCache(String uri) {
        if (mDiskLruCache != null) {
            return mDiskLruCache.getCacheFile(uri, DISK_CACHE_INDEX);
        }
        return null;
    }

    /**
     * Get the bitmap from disk cache.
     *
     * @param uri
     * @param config
     * @return
     */
    public Bitmap getBitmapFromDiskCache(String uri, BitmapDisplayConfig config) {
        if (uri == null || !globalConfig.isDiskCacheEnabled()) return null;
        synchronized (mDiskCacheLock) {
            while (!isDiskCacheReadied) {
                try {
                    mDiskCacheLock.wait();
                } catch (Throwable e) {
                }
            }
            if (mDiskLruCache != null) {
                LruDiskCache.Snapshot snapshot = null;
                try {
                    snapshot = mDiskLruCache.get(uri);
                    if (snapshot != null) {
                        Bitmap bitmap = null;
                        if (config == null || config.isShowOriginal()) {
                            bitmap = BitmapDecoder.decodeFileDescriptor(
                                    snapshot.getInputStream(DISK_CACHE_INDEX).getFD());
                        } else {
                            bitmap = BitmapDecoder.decodeSampledBitmapFromDescriptor(
                                    snapshot.getInputStream(DISK_CACHE_INDEX).getFD(),
                                    config.getBitmapMaxSize(),
                                    config.getBitmapConfig());
                        }

                        bitmap = rotateBitmapIfNeeded(uri, config, bitmap);
                        addBitmapToMemoryCache(uri, config, bitmap, mDiskLruCache.getExpiryTimestamp(uri));
                        return bitmap;
                    }
                } catch (Throwable e) {
                    LogUtils.e(e.getMessage(), e);
                } finally {
                    IOUtils.closeQuietly(snapshot);
                }
            }
            return null;
        }
    }
    
    /**
     * 根据View(主要是ImageView)的宽和高来获取图片的缩略图
     * 
     * @param path
     * @param viewWidth
     * @param viewHeight
     * @param isHighQuality 是否质量高
     * @return
     */
    private Bitmap decodeThumbBitmapForFile(String path, int viewWidth, int viewHeight,
            boolean isHighQuality) {
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        // 设置为true,表示解析Bitmap对象，该对象不占内存
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        // 设置缩放比例
        options.inSampleSize = computeScale(options, viewWidth, viewHeight);
        if (!isHighQuality) {
            // PS:为了减少内存，可以将缩放比例调的更大一些，这样就不会导致系统频繁GC的情况了
            options.inSampleSize += options.inSampleSize / 2 + 2;
        }
        // 图片质量
        options.inPreferredConfig = Config.RGB_565;

        // 设置为false,解析Bitmap对象加入到内存中
        options.inJustDecodeBounds = false;

        options.inPurgeable = true;
        options.inInputShareable = true;
        // 获取资源图片
        try {
            return BitmapFactory.decodeStream(new FileInputStream(f), null, options);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * <p>
     * 该方法来自google的图片缓存Demo
     * <p>
     * 请查看：http://developer.android.com/training/displaying-bitmaps/index.html
     * <p>
     * Calculate an inSampleSize for use in a {@link BitmapFactory.Options}
     * object when decoding bitmaps using the decode* methods from
     * {@link BitmapFactory}. This implementation calculates the closest
     * inSampleSize that will result in the final decoded bitmap having a width
     * and height equal to or larger than the requested width and height. This
     * implementation does not ensure a power of 2 is returned for inSampleSize
     * which can be faster when decoding but results in a larger bitmap which
     * isn't as useful for caching purposes.
     * 
     * @param options An options object with out* params already populated (run
     *            through a decode* method with inJustDecodeBounds==true
     * @param reqWidth The requested width of the resulting bitmap
     * @param reqHeight The requested height of the resulting bitmap
     * @return The value to be used for inSampleSize
     */
    private int computeScale(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            // Calculate ratios of height and width to requested height and
            // width
            final int heightRatio = Math.round((float)height / (float)reqHeight);
            final int widthRatio = Math.round((float)width / (float)reqWidth);

            // Choose the smallest ratio as inSampleSize value,
            // this will guarantee a final image
            // with both dimensions larger than or equal to the requested
            // height and width.
            inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;

            // This offers some additional logic in case the image has a strange
            // aspect ratio. For example, a panorama may have a much larger
            // width than height. In these cases the total pixels might still
            // end up being too large to fit comfortably in memory, so we should
            // be more aggressive with sample down the image (=larger
            // inSampleSize).

            final float totalPixels = width * height;

            // Anything more than 2x the requested pixels we'll sample down
            // further
            final float totalReqPixelsCap = reqWidth * reqHeight * 2;

            while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
                inSampleSize++;
            }
        }
        return inSampleSize;
    }

    /**
     * Clears both the memory and disk cache associated with this ImageCache object. Note that
     * this includes disk access so this should not be executed on the main/UI thread.
     */
    public void clearCache() {
        clearMemoryCache();
        clearDiskCache();
    }

    public void clearMemoryCache() {
        if (mMemoryCache != null) {
            mMemoryCache.evictAll();
        }
    }

    public void clearDiskCache() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.delete();
                } catch (Throwable e) {
                    LogUtils.e(e.getMessage(), e);
                }
                mDiskLruCache = null;
                isDiskCacheReadied = false;
            }
        }
        initDiskCache();
    }


    public void clearCache(String uri) {
        clearMemoryCache(uri);
        clearDiskCache(uri);
    }

    public void clearMemoryCache(String uri) {
        MemoryCacheKey key = new MemoryCacheKey(uri, null);
        if (mMemoryCache != null) {
            while (mMemoryCache.containsKey(key)) {
                mMemoryCache.remove(key);
            }
        }
    }

    public void clearDiskCache(String uri) {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.remove(uri);
                } catch (Throwable e) {
                    LogUtils.e(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Flushes the disk cache associated with this ImageCache object. Note that this includes
     * disk access so this should not be executed on the main/UI thread.
     */
    public void flush() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    mDiskLruCache.flush();
                } catch (Throwable e) {
                    LogUtils.e(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Closes the disk cache associated with this ImageCache object. Note that this includes
     * disk access so this should not be executed on the main/UI thread.
     */
    public void close() {
        synchronized (mDiskCacheLock) {
            if (mDiskLruCache != null) {
                try {
                    if (!mDiskLruCache.isClosed()) {
                        mDiskLruCache.close();
                        mDiskLruCache = null;
                    }
                } catch (Throwable e) {
                    LogUtils.e(e.getMessage(), e);
                }
            }
        }
    }

    private class BitmapMeta {
        public FileInputStream inputStream;
        public byte[] data;
        public long expiryTimestamp;
    }

    private Bitmap decodeBitmapMeta(BitmapMeta bitmapMeta, BitmapDisplayConfig config) throws IOException {
        if (bitmapMeta == null) return null;
        Bitmap bitmap = null;
        if (bitmapMeta.inputStream != null) {
            if (config == null || config.isShowOriginal()) {
                bitmap = BitmapDecoder.decodeFileDescriptor(bitmapMeta.inputStream.getFD());
            } else {
                bitmap = BitmapDecoder.decodeSampledBitmapFromDescriptor(
                        bitmapMeta.inputStream.getFD(),
                        config.getBitmapMaxSize(),
                        config.getBitmapConfig());
            }
        } else if (bitmapMeta.data != null) {
            if (config == null || config.isShowOriginal()) {
                bitmap = BitmapDecoder.decodeByteArray(bitmapMeta.data);
            } else {
                bitmap = BitmapDecoder.decodeSampledBitmapFromByteArray(
                        bitmapMeta.data,
                        config.getBitmapMaxSize(),
                        config.getBitmapConfig());
            }
        }
        return bitmap;
    }

    private Bitmap rotateBitmapIfNeeded(String uri, BitmapDisplayConfig config, Bitmap bitmap) {
        Bitmap result = bitmap;
        if (config != null && config.isAutoRotation()) {
            File bitmapFile = this.getBitmapFileFromDiskCache(uri);
            if (bitmapFile != null && bitmapFile.exists()) {
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(bitmapFile.getPath());
                } catch (Throwable e) {
                    return result;
                }
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
                int angle = 0;
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        angle = 90;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        angle = 180;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        angle = 270;
                        break;
                    default:
                        angle = 0;
                        break;
                }
                if (angle != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(angle);
                    result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                    bitmap.recycle();
                    bitmap = null;
                }
            }
        }
        return result;
    }

    public class MemoryCacheKey {
        private String uri;
        private String subKey;

        private MemoryCacheKey(String uri, String subKey) {
            this.uri = uri;
            this.subKey = subKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemoryCacheKey)) return false;

            MemoryCacheKey that = (MemoryCacheKey) o;

            if (!uri.equals(that.uri)) return false;

            if (subKey != null && that.subKey != null) {
                return subKey.equals(that.subKey);
            }

            return true;
        }

        @Override
        public int hashCode() {
            return uri.hashCode();
        }
    }
}
