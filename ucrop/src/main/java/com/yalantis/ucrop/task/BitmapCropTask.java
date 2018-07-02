package com.yalantis.ucrop.task;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.yalantis.ucrop.callback.BitmapCropCallback;
import com.yalantis.ucrop.model.CropParameters;
import com.yalantis.ucrop.model.ExifInfo;
import com.yalantis.ucrop.model.ImageState;
import com.yalantis.ucrop.util.FileUtils;
import com.yalantis.ucrop.util.ImageHeaderParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;

/**
 * Crops part of image that fills the crop bounds.
 * <p/>
 * First image is downscaled if max size was set and if resulting image is larger that max size.
 * Then image is rotated accordingly.
 * Finally new Bitmap object is created and saved to file.
 */
public class BitmapCropTask extends AsyncTask<Void, Void, Throwable> {

    private static final String TAG = "BitmapCropTask";

    static {
        System.loadLibrary("ucrop");
    }

    private Bitmap mViewBitmap;

    private final RectF mCropRect;
    private final RectF mCurrentImageRect;

    private float mCurrentScale, mCurrentAngle;
    private final int mMaxResultImageSizeX, mMaxResultImageSizeY;

    private final Bitmap.CompressFormat mCompressFormat;
    private final int mCompressQuality;
    private String mImageInputPath, mImageOutputPath;
    private final ExifInfo mExifInfo;
    private final BitmapCropCallback mCropCallback;

    private int mCroppedImageWidth, mCroppedImageHeight;
    private int cropOffsetX, cropOffsetY;
    private Boolean flip_horiz,flip_vert;
    private Context context;

    public BitmapCropTask(Context ctx,@Nullable Bitmap viewBitmap, @NonNull ImageState imageState, @NonNull CropParameters cropParameters,
                          @NonNull Boolean fliphoriz, @NonNull Boolean flipvert, @Nullable BitmapCropCallback cropCallback) {

        mViewBitmap = viewBitmap;
        mCropRect = imageState.getCropRect();
        mCurrentImageRect = imageState.getCurrentImageRect();

        mCurrentScale = imageState.getCurrentScale();
        mCurrentAngle = imageState.getCurrentAngle();
        mMaxResultImageSizeX = cropParameters.getMaxResultImageSizeX();
        mMaxResultImageSizeY = cropParameters.getMaxResultImageSizeY();

        mCompressFormat = cropParameters.getCompressFormat();
        mCompressQuality = cropParameters.getCompressQuality();

        mImageInputPath = cropParameters.getImageInputPath();
        mImageOutputPath = cropParameters.getImageOutputPath();
        mExifInfo = cropParameters.getExifInfo();

        mCropCallback = cropCallback;
        flip_horiz = fliphoriz;
        flip_vert = flipvert;
        context = ctx;

    }

    @Override
    @Nullable
    protected Throwable doInBackground(Void... params) {
        if (mViewBitmap == null) {
            return new NullPointerException("ViewBitmap is null");
        } else if (mViewBitmap.isRecycled()) {
            return new NullPointerException("ViewBitmap is recycled");
        } else if (mCurrentImageRect.isEmpty()) {
            return new NullPointerException("CurrentImageRect is empty");
        }

        float resizeScale = resize();

        try {
            crop(resizeScale);
            mViewBitmap = null;
        } catch (Throwable throwable) {
            return throwable;
        }

        return null;
    }

    private float resize() {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(mImageInputPath, options);

        boolean swapSides = mExifInfo.getExifDegrees() == 90 || mExifInfo.getExifDegrees() == 270;
        float scaleX = (swapSides ? options.outHeight : options.outWidth) / (float) mViewBitmap.getWidth();
        float scaleY = (swapSides ? options.outWidth : options.outHeight) / (float) mViewBitmap.getHeight();

        float resizeScale = Math.min(scaleX, scaleY);

        mCurrentScale /= resizeScale;

        resizeScale = 1;
        if (mMaxResultImageSizeX > 0 && mMaxResultImageSizeY > 0) {
            float cropWidth = mCropRect.width() / mCurrentScale;
            float cropHeight = mCropRect.height() / mCurrentScale;

            if (cropWidth > mMaxResultImageSizeX || cropHeight > mMaxResultImageSizeY) {

                scaleX = mMaxResultImageSizeX / cropWidth;
                scaleY = mMaxResultImageSizeY / cropHeight;
                resizeScale = Math.min(scaleX, scaleY);

                mCurrentScale /= resizeScale;
            }
        }
        return resizeScale;
    }

    private boolean crop(float resizeScale) throws IOException {
        ExifInterface originalExif = new ExifInterface(mImageInputPath);

        cropOffsetX = Math.round((mCropRect.left - mCurrentImageRect.left) / mCurrentScale);
        cropOffsetY = Math.round((mCropRect.top - mCurrentImageRect.top) / mCurrentScale);
        mCroppedImageWidth = Math.round(mCropRect.width() / mCurrentScale);
        mCroppedImageHeight = Math.round(mCropRect.height() / mCurrentScale);

        boolean shouldCrop = shouldCrop(mCroppedImageWidth, mCroppedImageHeight);
        Log.i(TAG, "Should crop: " + shouldCrop);
        Log.e("img path",mImageInputPath);
        if(flip_vert || flip_horiz)
        {
            mImageInputPath = flipImage(mImageInputPath,flip_vert,flip_horiz);
            Log.e("flip path",mImageInputPath);
        }

        if (shouldCrop) {
            boolean cropped = cropCImg(mImageInputPath, mImageOutputPath,
                    cropOffsetX, cropOffsetY, mCroppedImageWidth, mCroppedImageHeight,
                    mCurrentAngle, resizeScale, mCompressFormat.ordinal(), mCompressQuality,
                    mExifInfo.getExifDegrees(), mExifInfo.getExifTranslation());
            if (cropped && mCompressFormat.equals(Bitmap.CompressFormat.JPEG)) {
                ImageHeaderParser.copyExif(originalExif, mCroppedImageWidth, mCroppedImageHeight, mImageOutputPath);
            }
            return cropped;
        } else {
            FileUtils.copyFile(mImageInputPath, mImageOutputPath);
            return false;
        }
    }

    private String flipImage(String mImageInputPath, Boolean flip_vert, Boolean flip_horiz) {
        Matrix matrix = new Matrix();
        String path="";
        if (flip_horiz) matrix.postScale(-1.0f, 1.0f);
        if (flip_vert) matrix.postScale(1.0f, -1.0f);
        Bitmap myBitmap = BitmapFactory.decodeFile(mImageInputPath);
        Log.e("bit: ",myBitmap.toString());
        try {
            Bitmap image_bitmap = Bitmap.createBitmap(myBitmap, 0, 0, myBitmap.getWidth(), myBitmap.getHeight(), matrix, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            image_bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes);
            File wallpaperDirectory = new File(
                    Environment.getExternalStorageDirectory() + "/.temp");
            // have the object build the directory structure, if needed.
            if (!wallpaperDirectory.exists()) {
                wallpaperDirectory.mkdirs();
            }

            try {
                File f = new File(wallpaperDirectory, Calendar.getInstance()
                        .getTimeInMillis() + ".jpg");
                f.createNewFile();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(bytes.toByteArray());
                MediaScannerConnection.scanFile(context,
                        new String[]{f.getPath()},
                        new String[]{"image/jpeg"}, null);
                fo.close();
                Log.d("TAG", "File Saved::--->" + f.getAbsolutePath());

                path= f.getAbsolutePath();
                return path;
            } catch (IOException e1) {
                e1.printStackTrace();
                Log.e("Errorr: ",e1.getMessage());
            }
        } catch (Exception e) {
            Log.e("Error", e.getMessage());

            return mImageInputPath;
        }
        return mImageInputPath;
    }

    /**
     * Check whether an image should be cropped at all or just file can be copied to the destination path.
     * For each 1000 pixels there is one pixel of error due to matrix calculations etc.
     *
     * @param width  - crop area width
     * @param height - crop area height
     * @return - true if image must be cropped, false - if original image fits requirements
     */
    private boolean shouldCrop(int width, int height) {
        int pixelError = 1;
        pixelError += Math.round(Math.max(width, height) / 1000f);
        return (mMaxResultImageSizeX > 0 && mMaxResultImageSizeY > 0)
                || Math.abs(mCropRect.left - mCurrentImageRect.left) > pixelError
                || Math.abs(mCropRect.top - mCurrentImageRect.top) > pixelError
                || Math.abs(mCropRect.bottom - mCurrentImageRect.bottom) > pixelError
                || Math.abs(mCropRect.right - mCurrentImageRect.right) > pixelError
                || mCurrentAngle != 0;
    }

    @SuppressWarnings("JniMissingFunction")
    native public static boolean
    cropCImg(String inputPath, String outputPath,
             int left, int top, int width, int height,
             float angle, float resizeScale,
             int format, int quality,
             int exifDegrees, int exifTranslation) throws IOException, OutOfMemoryError;

    @Override
    protected void onPostExecute(@Nullable Throwable t) {
        if (mCropCallback != null) {
            if (t == null) {
                Uri uri = Uri.fromFile(new File(mImageOutputPath));
                mCropCallback.onBitmapCropped(uri, cropOffsetX, cropOffsetY, mCroppedImageWidth, mCroppedImageHeight);
            } else {
                mCropCallback.onCropFailure(t);
            }
        }
    }

}
