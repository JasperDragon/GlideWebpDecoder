package com.bumptech.glide.integration.webp.decoder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.Log;

import com.bumptech.glide.gifdecoder.GifDecoder;
import com.bumptech.glide.gifdecoder.GifHeader;
import com.bumptech.glide.util.LruCache;
import com.bumptech.glide.integration.webp.WebpFrameInfo;
import com.bumptech.glide.integration.webp.WebpImage;
import com.bumptech.glide.integration.webp.WebpFrame;

import java.io.InputStream;
import java.nio.ByteBuffer;


/**
 * 集成fresco webp部分代码
 * 用于支持Animated Webp图片展示
 *
 * @author liuchun
 */
public class WebpDecoder implements GifDecoder {
    private static final String TAG = "WebpDecoder";
    // 缓存最近的Bitmap帧用于渲染当前帧
    private static final int MAX_FRAME_BITMAP_SIZE = 5;

    /** Raw WebP data from input source. */
    private ByteBuffer rawData;
    /** WebpImage instance */
    private WebpImage mWebPImage;
    private GifDecoder.BitmapProvider mBitmapProvider;
    private int mFramePointer;
    private final int[] mFrameDurations;
    private final WebpFrameInfo[] mFrameInfos;
    private int sampleSize;
    private int downsampledHeight;
    private int downsampledWidth;
    private final Paint mBackgroundPaint;
    private final Paint mTransparentFillPaint;
    // 动画每一帧渲染后的Bitmap缓存
    private LruCache<Integer, Bitmap> mFrameBitmapCache;

    public WebpDecoder(GifDecoder.BitmapProvider provider, WebpImage webPImage, ByteBuffer rawData,
                       int sampleSize) {
        mBitmapProvider = provider;
        mWebPImage = webPImage;
        mFrameDurations = webPImage.getFrameDurations();
        mFrameInfos = new WebpFrameInfo[webPImage.getFrameCount()];
        for (int i = 0; i < mWebPImage.getFrameCount(); i++) {
            mFrameInfos[i] = mWebPImage.getFrameInfo(i);
            Log.i(TAG, "mFrameInfos: " + mFrameInfos[i].toString());
        }

        mBackgroundPaint = new Paint();
        mBackgroundPaint.setColor(mWebPImage.getBackgroundColor());
        mBackgroundPaint.setStyle(Paint.Style.FILL);
        mBackgroundPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

        mTransparentFillPaint = new Paint(mBackgroundPaint);
        mTransparentFillPaint.setColor(Color.TRANSPARENT);

        mFrameBitmapCache = new LruCache<>(MAX_FRAME_BITMAP_SIZE);

        setData(new GifHeader(), rawData, sampleSize);
    }

    @Override
    public int getWidth() {
        return mWebPImage.getWidth();
    }

    @Override
    public int getHeight() {
        return mWebPImage.getHeight();
    }

    @Override
    public ByteBuffer getData() {
        return rawData;
    }

    @Override
    public int getStatus() {
        return STATUS_OK;
    }

    @Override
    public void advance() {
        mFramePointer = (mFramePointer + 1) % mWebPImage.getFrameCount();
    }

    @Override
    public int getDelay(int n) {
        int delay = -1;
        if ((n >= 0) && (n < mFrameDurations.length)) {
            delay = mFrameDurations[n];
        }
        return delay;
    }

    @Override
    public int getNextDelay() {
        if (mFrameDurations.length == 0 || mFramePointer < 0) {
            return 0;
        }

        return getDelay(mFramePointer);
    }

    @Override
    public int getFrameCount() {
        return mWebPImage.getFrameCount();
    }

    @Override
    public int getCurrentFrameIndex() {
        return mFramePointer;
    }

    @Override
    public void resetFrameIndex() {
        mFramePointer = -1;
    }

    @Override
    public int getLoopCount() {
        return mWebPImage.getLoopCount();
    }

    @Override
    public int getNetscapeLoopCount() {
        return mWebPImage.getLoopCount();
    }

    @Override
    public int getTotalIterationCount() {
        if (mWebPImage.getLoopCount() == 0) {
            return TOTAL_ITERATION_COUNT_FOREVER;
        }
        return mWebPImage.getFrameCount() + 1;
    }

    @Override
    public int getByteSize() {
        return mWebPImage.getSizeInBytes();
    }

    @Override
    public Bitmap getNextFrame() {
        int frameNumber = getCurrentFrameIndex();
        // Get the target Bitmap for Canvas
        Bitmap bitmap = mBitmapProvider.obtain(downsampledWidth, downsampledHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC);

        int nextIndex;
        // if blending is required, prepare the canvas with the nearest cached frame
        if (!isKeyFrame(frameNumber)) {
            // Blending is required, nextIndex points to the next index to render into the canvas
            nextIndex = prepareCanvasWithBlending(frameNumber - 1, canvas);
        } else {
            nextIndex = frameNumber;
        }

        Log.i(TAG, "frameNumber=" + frameNumber + ", nextIndex=" + nextIndex);

        for (int index = nextIndex; index < frameNumber; index++) {
            WebpFrameInfo frameInfo = mFrameInfos[index];
            if (!frameInfo.blendPreviousFrame) {
                noBlendingPreviousFrame(canvas, frameInfo);
            }

            // render the previous frame
            renderFrame(index, canvas);
            Log.i(TAG, "renderFrame, index=" + index + ", blend=" + frameInfo.blendPreviousFrame + ", dispose=" + frameInfo.disposeBackgroundColor);

            if (frameInfo.disposeBackgroundColor) {
                disposeToBackground(canvas, frameInfo);
            }
        }

        WebpFrameInfo frameInfo = mFrameInfos[frameNumber];
        if (!frameInfo.blendPreviousFrame) {
            noBlendingPreviousFrame(canvas, frameInfo);
        }

        // Finally, we render the current frame. We don't dispose it.
        renderFrame(frameNumber, canvas);
        Log.i(TAG, "renderFrame, index=" + frameNumber + ", blend=" + frameInfo.blendPreviousFrame + ", dispose=" + frameInfo.disposeBackgroundColor);
        // Then put the rendered frame into the BitmapCache
        mFrameBitmapCache.put(frameNumber, bitmap);

        return bitmap;
    }

    private void renderFrame(int frameNumber, Canvas canvas) {

        WebpFrameInfo frameInfo = mFrameInfos[frameNumber];

        int frameWidth = frameInfo.width / sampleSize;
        int frameHeight = frameInfo.height / sampleSize;
        int xOffset = frameInfo.xOffset / sampleSize;
        int yOffset = frameInfo.yOffset / sampleSize;

        Log.i(TAG, "render frame with native method");
        WebpFrame webpFrame = mWebPImage.getFrame(frameNumber);
        try {
            Bitmap frameBitmap = mBitmapProvider.obtain(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
            frameBitmap.eraseColor(Color.TRANSPARENT);
            webpFrame.renderFrame(frameWidth, frameHeight, frameBitmap);
            canvas.drawBitmap(frameBitmap, xOffset, yOffset, null);
            mBitmapProvider.release(frameBitmap);
        } finally {
            webpFrame.dispose();
        }
    }


    @Override
    public int read(InputStream inputStream, int i) {
        return GifDecoder.STATUS_OK;
    }

    @Override
    public void clear() {
        mWebPImage.dispose();
        mWebPImage = null;
        mFrameBitmapCache.clearMemory();
        rawData = null;
    }

    @Override
    public void setData(GifHeader header, byte[] data) {
        setData(header, ByteBuffer.wrap(data));
    }

    @Override
    public void setData(GifHeader header, ByteBuffer buffer) {
        setData(header, buffer, 1);
    }

    @Override
    public void setData(GifHeader header, ByteBuffer buffer, int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("Sample size must be >=0, not: " + sampleSize);
        }
        // Make sure sample size is a power of 2.
        sampleSize = Integer.highestOneBit(sampleSize);

        // Initialize the raw data buffer.
        rawData = buffer.asReadOnlyBuffer();
        rawData.position(0);

        this.sampleSize = sampleSize;
        downsampledWidth = mWebPImage.getWidth() / sampleSize;
        downsampledHeight = mWebPImage.getHeight() / sampleSize;
    }

    @Override
    public int read(byte[] bytes) {
        return GifDecoder.STATUS_OK;
    }


    private int prepareCanvasWithBlending(int previousFrameNumber, Canvas canvas) {
        for (int index = previousFrameNumber; index >= 0; index--) {
            WebpFrameInfo frameInfo = mFrameInfos[index];
            if (!frameInfo.disposeBackgroundColor || !isFullFrame(frameInfo)) {
                // need to draw this frame
                Bitmap bitmap = mFrameBitmapCache.get(index);
                if (bitmap != null && !bitmap.isRecycled()) {
                    Log.i(TAG, "draw previous frame to the canvas, index=" + index);
                    canvas.drawBitmap(bitmap, 0, 0, null);
                    if (frameInfo.disposeBackgroundColor) {
                        disposeToBackground(canvas, frameInfo);
                    }
                    return index + 1;
                } else if (isKeyFrame(index)) {
                    return index;
                } /* else keep going */
            } else {
                return index + 1;
            }
        }
        return 0;
    }

    /**
     * 不需要与前一帧进行Alpha-Blending，
     * 使用透明像素填充当前帧所在区域即可
     *
     * @param canvas
     * @param frameInfo
     */
    private void noBlendingPreviousFrame(Canvas canvas, WebpFrameInfo frameInfo) {
        final float left = frameInfo.xOffset / sampleSize;
        final float top = frameInfo.yOffset / sampleSize;
        final float right = (frameInfo.xOffset + frameInfo.width) / sampleSize;
        final float bottom = (frameInfo.yOffset + frameInfo.height) / sampleSize;
        canvas.drawRect(left, top, right, bottom, mTransparentFillPaint);
    }

    /**
     * 当前帧显示之后，渲染下一帧时，需要使用背景色填充当前帧所在区域
     *
     * @param canvas
     * @param frameInfo
     */
    private void disposeToBackground(Canvas canvas, WebpFrameInfo frameInfo) {
        final float left = frameInfo.xOffset / sampleSize;
        final float top = frameInfo.yOffset / sampleSize;
        final float right = (frameInfo.xOffset + frameInfo.width) / sampleSize;
        final float bottom = (frameInfo.yOffset + frameInfo.height) / sampleSize;
        canvas.drawRect(left, top, right, bottom, mBackgroundPaint);
    }

    /**
     * 当前帧是否是关键帧
     *
     * @param index
     * @return
     */
    private boolean isKeyFrame(int index) {
        if (index == 0) {
            // first Frame
            return true;
        }

        WebpFrameInfo curFrameInfo = mFrameInfos[index];
        WebpFrameInfo prevFrameInfo = mFrameInfos[index - 1];
        if (!curFrameInfo.blendPreviousFrame && isFullFrame(curFrameInfo)) {
            return true;
        } else {
            return prevFrameInfo.disposeBackgroundColor && isFullFrame(prevFrameInfo);
        }
    }

    /**
     * 当前帧是否充满画布
     *
     * @param frameInfo
     * @return
     */
    private boolean isFullFrame(WebpFrameInfo frameInfo) {
        return frameInfo.xOffset == 0 &&
                frameInfo.yOffset == 0 &&
                frameInfo.width == mWebPImage.getWidth() &&
                frameInfo.height == mWebPImage.getHeight();
    }

}
