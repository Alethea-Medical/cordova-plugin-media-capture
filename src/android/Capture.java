/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.mediacapture;

import static java.lang.Boolean.valueOf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;

import org.apache.cordova.file.FileUtils;
import org.apache.cordova.file.LocalFilesystemURL;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginManager;
import org.apache.cordova.mediacapture.PendingRequests.Request;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.RequiresApi;


public class Capture extends CordovaPlugin {

    private static final String VIDEO_3GPP = "video/3gpp";
    private static final String VIDEO_MP4 = "video/mp4";
    private static final String AUDIO_3GPP = "audio/3gpp";
    private static final String[] AUDIO_TYPES = new String[]{"audio/3gpp", "audio/aac", "audio/amr", "audio/wav"};
    private static final String IMAGE_JPEG = "image/jpeg";

    private static final int CAPTURE_AUDIO = 0;     // Constant for capture audio
    private static final int CAPTURE_IMAGE_OR_VIDEO = 1;     // Constant for capture image
    private static final String LOG_TAG = "Capture";

    private static final int CAPTURE_INTERNAL_ERR = 0;
    //    private static final int CAPTURE_APPLICATION_BUSY = 1;
//    private static final int CAPTURE_INVALID_ARGUMENT = 2;
    private static final int CAPTURE_NO_MEDIA_FILES = 3;
    private static final int CAPTURE_PERMISSION_DENIED = 4;
    private static final int CAPTURE_NOT_SUPPORTED = 20;

    private boolean multipleImageReq = false;
    private boolean multipleVideoReq = false;
    private static final String[] storagePermissions = new String[]{
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private boolean cameraPermissionInManifest;     // Whether or not the CAMERA permission is declared in AndroidManifest.xml

    private final PendingRequests pendingRequests = new PendingRequests();

    private int numPics;                            // Number of pictures before capture activity
    private Uri imageUri;
    private Uri videoUri;


    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();

        // CB-10670: The CAMERA permission does not need to be requested unless it is declared
        // in AndroidManifest.xml. This plugin does not declare it, but others may and so we must
        // check the package info to determine if the permission is present.

        cameraPermissionInManifest = false;
        try {
            PackageManager packageManager = this.cordova.getActivity().getPackageManager();
            String[] permissionsInPackage = packageManager.getPackageInfo(this.cordova.getActivity().getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (permissionsInPackage != null) {
                for (String permission : permissionsInPackage) {
                    if (permission.equals(Manifest.permission.CAMERA)) {
                        cameraPermissionInManifest = true;
                        break;
                    }
                }
            }
        } catch (NameNotFoundException e) {
            // We are requesting the info for our package, so this should
            // never be caught
            LOG.e(LOG_TAG, "Failed checking for CAMERA permission in manifest", e);
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("getFormatData")) {
            JSONObject obj = getFormatData(args.getString(0), args.getString(1));
            callbackContext.success(obj);
            return true;
        }

        JSONObject options = args.optJSONObject(0);
        multipleImageReq = Boolean.parseBoolean(options.getString("image"));
        multipleVideoReq = Boolean.parseBoolean(options.getString("video"));

        if (action.equals("captureAudio")) {
            this.captureAudio(pendingRequests.createRequest(CAPTURE_AUDIO, options, callbackContext));
        } else if (action.equals("captureImage")) {

            this.captureImageOrVideo(pendingRequests.createRequest(CAPTURE_IMAGE_OR_VIDEO, options, callbackContext));
        } else {
            return false;
        }

        return true;
    }

    /**
     * Provides the media data file data depending on it's mime type
     *
     * @param filePath path to the file
     * @param mimeType of the file
     * @return a MediaFileData object
     */
    private JSONObject getFormatData(String filePath, String mimeType) throws JSONException {
        Uri fileUrl = filePath.startsWith("file:") ? Uri.parse(filePath) : Uri.fromFile(new File(filePath));
        JSONObject obj = new JSONObject();
        // setup defaults
        obj.put("height", 0);
        obj.put("width", 0);
        obj.put("bitrate", 0);
        obj.put("duration", 0);
        obj.put("codecs", "");

        // If the mimeType isn't set the rest will fail
        // so let's see if we can determine it.
        if (mimeType == null || mimeType.equals("") || "null".equals(mimeType)) {
            mimeType = FileHelper.getMimeType(fileUrl, cordova);
        }
        LOG.d(LOG_TAG, "Mime type = " + mimeType);

        if (mimeType.equals(IMAGE_JPEG) || filePath.endsWith(".jpg")) {
            obj = getImageData(fileUrl, obj);
        } else if (Arrays.asList(AUDIO_TYPES).contains(mimeType)) {
            obj = getAudioVideoData(filePath, obj, false);
        } else if (mimeType.equals(VIDEO_3GPP) || mimeType.equals(VIDEO_MP4)) {
            obj = getAudioVideoData(filePath, obj, true);
        }
        return obj;
    }

    /**
     * Get the Image specific attributes
     *
     * @param fileUrl url pointing to the file
     * @param obj     represents the Media File Data
     * @return a JSONObject that represents the Media File Data
     * @throws JSONException
     */
    private JSONObject getImageData(Uri fileUrl, JSONObject obj) throws JSONException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(fileUrl.getPath(), options);
        obj.put("height", options.outHeight);
        obj.put("width", options.outWidth);
        return obj;
    }

    /**
     * Get the Image specific attributes
     *
     * @param filePath path to the file
     * @param obj      represents the Media File Data
     * @param video    if true get video attributes as well
     * @return a JSONObject that represents the Media File Data
     * @throws JSONException
     */
    private JSONObject getAudioVideoData(String filePath, JSONObject obj, boolean video) throws JSONException {
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(filePath);
            player.prepare();
            obj.put("duration", player.getDuration() / 1000);
            if (video) {
                obj.put("height", player.getVideoHeight());
                obj.put("width", player.getVideoWidth());
            }
        } catch (IOException e) {
            LOG.d(LOG_TAG, "Error: loading video file");
        }
        return obj;
    }

    private boolean isMissingPermissions(Request req, ArrayList<String> permissions) {
        ArrayList<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (!PermissionHelper.hasPermission(this, permission)) {
                missingPermissions.add(permission);
            }
        }

        boolean isMissingPermissions = missingPermissions.size() > 0;
        if (isMissingPermissions) {
            String[] missing = missingPermissions.toArray(new String[missingPermissions.size()]);
            PermissionHelper.requestPermissions(this, req.requestCode, missing);
        }
        return isMissingPermissions;
    }

    private boolean isMissingStoragePermissions(Request req) {
        return isMissingPermissions(req, new ArrayList<>(Arrays.asList(storagePermissions)));
    }

    private boolean isMissingCameraPermissions(Request req) {
        ArrayList<String> cameraPermissions = new ArrayList<>(Arrays.asList(storagePermissions));
        if (cameraPermissionInManifest) {
            cameraPermissions.add(Manifest.permission.CAMERA);
        }
        return isMissingPermissions(req, cameraPermissions);
    }

    /**
     * Sets up an intent to capture audio.  Result handled by onActivityResult()
     */
    private void captureAudio(Request req) {
        if (isMissingStoragePermissions(req)) return;

        try {
            Intent intent = new Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION);
            this.cordova.startActivityForResult((CordovaPlugin) this, intent, req.requestCode);
        } catch (ActivityNotFoundException ex) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NOT_SUPPORTED, "No Activity found to handle Audio Capture."));
        }
    }

    private String getTempDirectoryPath() {
        File cache = null;

        // Use internal storage
        cache = cordova.getActivity().getCacheDir();

        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }

    /**
     * Sets up an intent to capture media.  Result handled by onActivityResult()
     */
    private void captureImageOrVideo(Request req) {
        if (isMissingCameraPermissions(req)) return;

        // Save the number of images currently on disk for later
        this.numPics = queryImgDB(whichContentStore()).getCount();

        try {
            ContentResolver contentResolver = this.cordova.getActivity().getContentResolver();
            Pair mediaTypes = setupContentValues();

            //create intents to be used in chooser
            Intent takeVideoIntent = setupVideoIntent(req, contentResolver, mediaTypes);
            Intent takePictureIntent = setupPictureIntent(contentResolver, mediaTypes);

            if (multipleImageReq) {
                this.cordova.startActivityForResult((CordovaPlugin) this, takePictureIntent, req.requestCode);
            } else if (multipleVideoReq) {
                this.cordova.startActivityForResult((CordovaPlugin) this, takeVideoIntent, req.requestCode);
            } else {
//               create the chooser to select between the camera and the video camera
                Intent chooserIntent = Intent.createChooser(takePictureIntent, "Capture Image or Video");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{takeVideoIntent});
                this.cordova.startActivityForResult((CordovaPlugin) this, chooserIntent, req.requestCode);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

/**
 * Create the intent to capture images using ANDROID_IMAGE_CAPTURE intent
 *
 *   @param mediaTypes The mediaTypes specify the type of file being saved in uri
 *   @param contentResolver
 *
 *  @return the intent required to initiate the native camera
 *
 * */
    private Intent setupPictureIntent(ContentResolver contentResolver, Pair mediaTypes) {
        imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, (ContentValues) mediaTypes.first);
        //intent to launch the camera for image caputure
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        //set the image save path in the intent
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        return takePictureIntent;
    }

    /**
     * Create the intent to capture videos using ANDROID_VIDEO_CAPTURE INTENT
     * 
     *  @param req
     *  @param mediaTypes The mediaTypes specify the type of file being saved in uri
     *  @param contentResolver
     *
     *  @return the intent required to initiate the native camera
     * */
    private Intent setupVideoIntent(Request req, ContentResolver contentResolver, Pair mediaTypes) {

        videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, (ContentValues) mediaTypes.second);
        //intent to launch the camera for video
        Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);

        //set video save path in the intent
        takeVideoIntent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri);
        takeVideoIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);


        if (Build.VERSION.SDK_INT > 7) {
            takeVideoIntent.putExtra("android.intent.extra.durationLimit", req.duration);
            takeVideoIntent.putExtra("android.intent.extra.videoQuality", req.quality);
        }
        return takeVideoIntent;
    }


    /**
     * create media type for content resolver
     * 
     * @return content resolver media types in a pair
     */
    private Pair setupContentValues() {
        //create content uri for image

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.MIME_TYPE, IMAGE_JPEG);
        //create content uri for video
        ContentValues cvVideo = new ContentValues();
        cvVideo.put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MP4);

        return new Pair(cv, cvVideo);
    }

    private static void createWritableFile(File file) throws IOException {
        file.createNewFile();
        file.setWritable(true, false);
    }


    /**
     * Called when the video view exits.
     *
     * @param requestCode The request code originally supplied to startActivityForResult(),
     *                    allowing you to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its setResult().
     * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     * @throws JSONException
     */
    public void onActivityResult(int requestCode, int resultCode, final Intent intent) {
        final Request req = pendingRequests.get(requestCode);
        // Result received okay
        if (resultCode == Activity.RESULT_OK) {
            Runnable processActivityResult = new Runnable() {
                @RequiresApi(api = Build.VERSION_CODES.N)
                @Override
                public void run() {
                    switch (req.action) {
                        case CAPTURE_AUDIO:
                            onAudioActivityResult(req, intent);
                            break;
                        case CAPTURE_IMAGE_OR_VIDEO:

                            if (checkURIResource(videoUri)) {
                                onVideoActivityResult(req);
                            } else {
                                onImageActivityResult(req);
                            }
                            break;
                    }
                }
            };

            this.cordova.getThreadPool().execute(processActivityResult);
        }
        // If canceled
        else if (resultCode == Activity.RESULT_CANCELED) {
            CleanUpEmptyVideo();
            // If we have partial results send them back to the user
            if (req.results.length() > 0 && req.results != null) {
                pendingRequests.resolveWithSuccess(req);
            }
            // user canceled the action
            else {
                pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Canceled."));
            }
        }
        // If something else
        else {
            CleanUpEmptyVideo();
            // If we have partial results send them back to the user
            if (req.results.length() > 0) {

                pendingRequests.resolveWithSuccess(req);
            }
            // something bad happened
            else {

                pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Did not complete!"));
            }
        }
    }

    /**
     * An empty video file is created when the video camera launches.
     * Delete this file if the user exits out of the camera before taking a video so we don't leave empty files on the phone
     */
    private void CleanUpEmptyVideo()
    {
        if(videoUri != null) {
            //Run in runnable, since we cannot call getResourceApi in the UI thread
            Runnable processActivityResult = new Runnable() {
                @Override
                public void run() {
                    File videoFile = webView.getResourceApi().mapUriToFile(videoUri);
                    if (videoFile.exists()) {
                        videoFile.delete();
                    }
                }
            };

            this.cordova.getThreadPool().execute(processActivityResult);
        }
    }

    /**
     * called to check if video was captured or not. need to do this because content resolver is being used
     * so you can't use the traditional method of checking the file to see if it exists or not
     *
     * @param uri the uri to check. checks to see if data has been written or not
     * @return return result indicating if uri is empty or not
     */

    public boolean checkURIResource(Uri uri) {
        ContentResolver contentResolver = this.cordova.getActivity().getContentResolver();
        String[] projection = {MediaStore.MediaColumns.DATA};
        Cursor cur = contentResolver.query(Uri.parse(String.valueOf(uri)), projection, null, null, null);
        if (cur != null) {
            if (cur.moveToFirst()) {
                String filePath = cur.getString(0);

                if (new File(filePath).exists()) {
                    cur.close();
                    return true;
                } else {
                    cur.close();
                    return false;
                }
            } else {
                cur.close();
                return false;
            }

        } else {
            cur.close();
            return false;
        }
    }

    public void onAudioActivityResult(Request req, Intent intent) {
        // Get the uri of the audio clip
        Uri data = intent.getData();

        // create a file object from the uri
        req.results.put(createMediaFile(data));

        if (req.results.length() >= req.limit) {
            // Send Uri back to JavaScript for listening to audio
            pendingRequests.resolveWithSuccess(req);
        } else {
            // still need to capture more audio clips
            captureAudio(req);
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    public void onImageActivityResult(Request req) {
   
        String path = null;
        try (InputStream imageStream = cordova.getActivity().getContentResolver().openInputStream(imageUri)){

            Bitmap bmp = BitmapFactory.decodeStream(imageStream);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream);
            byte[] byteArray = stream.toByteArray();
            bmp = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);

            bmp = rotateImage(bmp, 90);
            path = MediaStore.Images.Media.insertImage(cordova.getContext().getContentResolver(), bmp, "Title", null);
            copyExif(imageUri, path);

        } catch (IOException e) {
            e.printStackTrace();
        }

      
        req.results.put(createMediaFile(Uri.parse(path)));
        checkForDuplicateImage();

        // Send Uri back to JavaScript for viewing image
        pendingRequests.resolveWithSuccess(req);
    }
    
    public static Bitmap rotateImage(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(),
                matrix, true);

    }
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void copyExif(Uri imageUri, String newPath) throws IOException
    {
        try (InputStream inputStream = cordova.getContext().getContentResolver().openInputStream(imageUri)){
        ExifInterface oldExif = new ExifInterface(inputStream);

        String[] attributes = new String[]
                {
                        ExifInterface.TAG_APERTURE,
                        ExifInterface.TAG_DATETIME,
                        ExifInterface.TAG_DATETIME_DIGITIZED,
                        ExifInterface.TAG_EXPOSURE_TIME,
                        ExifInterface.TAG_FLASH,
                        ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_GPS_ALTITUDE,
                        ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_DATESTAMP,
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        ExifInterface.TAG_GPS_PROCESSING_METHOD,
                        ExifInterface.TAG_GPS_TIMESTAMP,
                        ExifInterface.TAG_IMAGE_LENGTH,
                        ExifInterface.TAG_IMAGE_WIDTH,
                        ExifInterface.TAG_ISO,
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.TAG_SUBSEC_TIME,
                        ExifInterface.TAG_SUBSEC_TIME_DIG,
                        ExifInterface.TAG_SUBSEC_TIME_ORIG,
                        ExifInterface.TAG_WHITE_BALANCE
                };

        ExifInterface newExif = new ExifInterface(newPath);
        for (int i = 0; i < attributes.length; i++)
        {
            String value = oldExif.getAttribute(attributes[i]);
            if (value != null)
                newExif.setAttribute(attributes[i], value);
        }
        newExif.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    public void onVideoActivityResult(Request req) {
        Uri data = null;
        // Get the uri of the video clip
        data = videoUri;

        if (data == null) {
            File movie = new File(getTempDirectoryPath(), "Capture.avi");
            data = Uri.fromFile(movie);
        }

        // create a file object from the uri
        if (data == null) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Error: data is null"));
        } else {

            req.results.put(createMediaFile(videoUri));
            // Send Uri back to JavaScript for viewing video

            //Add these commented lines back in if you want the plugin to control the reexecution of the camera intents instead of the external control
//            if (req.results.length() >= req.limit) {
                // Send Uri back to JavaScript for viewing image
                pendingRequests.resolveWithSuccess(req);
//            } else {
//                // still need to capture more images
//                captureImage(req);
//            }

        }
    }

    /**
     * Creates a JSONObject that represents a File from the Uri
     *
     * @param data the Uri of the audio/image/video
     * @return a JSONObject that represents a File
     * @throws IOException
     */
    private JSONObject createMediaFile(Uri data) {
        File fp = webView.getResourceApi().mapUriToFile(data);

        JSONObject obj = new JSONObject();

        Class webViewClass = webView.getClass();
        PluginManager pm = null;
        try {
            Method gpm = webViewClass.getMethod("getPluginManager");
            pm = (PluginManager) gpm.invoke(webView);
        } catch (NoSuchMethodException e) {
        } catch (IllegalAccessException e) {
        } catch (InvocationTargetException e) {
        }
        if (pm == null) {
            try {
                Field pmf = webViewClass.getField("pluginManager");
                pm = (PluginManager) pmf.get(webView);
            } catch (NoSuchFieldException e) {
            } catch (IllegalAccessException e) {
            }
        }
        FileUtils filePlugin = (FileUtils) pm.getPlugin("File");

        LocalFilesystemURL url = filePlugin.filesystemURLforLocalPath(fp.getAbsolutePath());

        try {
            // File properties
            obj.put("name", fp.getName());
            obj.put("fullPath", Uri.fromFile(fp));
            if (url != null) {
                obj.put("localURL", url.toString());
            }
            // Because of an issue with MimeTypeMap.getMimeTypeFromExtension() all .3gpp files
            // are reported as video/3gpp. I'm doing this hacky check of the URI to see if it
            // is stored in the audio or video content store.
            if (fp.getAbsoluteFile().toString().endsWith(".3gp") || fp.getAbsoluteFile().toString().endsWith(".3gpp")) {
                if (data.toString().contains("/audio/")) {
                    obj.put("type", AUDIO_3GPP);
                } else {
                    obj.put("type", VIDEO_3GPP);
                }
            } else {
                obj.put("type", FileHelper.getMimeType(Uri.fromFile(fp), cordova));
            }

            obj.put("lastModifiedDate", fp.lastModified());
            obj.put("size", fp.length());
        } catch (JSONException e) {
            // this will never happen
            e.printStackTrace();
        }
        return obj;
    }

    private JSONObject createErrorObject(int code, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("code", code);
            obj.put("message", message);
        } catch (JSONException e) {
            // This will never happen
        }
        return obj;
    }

    /**
     * Creates a cursor that can be used to determine how many images we have.
     *
     * @return a cursor
     */
    private Cursor queryImgDB(Uri contentStore) {
        return this.cordova.getActivity().getContentResolver().query(
                contentStore,
                new String[]{MediaStore.Images.Media._ID},
                null,
                null,
                null);
    }

    /**
     * Used to find out if we are in a situation where the Camera Intent adds to images
     * to the content store.
     */
    private void checkForDuplicateImage() {
        Uri contentStore = whichContentStore();
        Cursor cursor = queryImgDB(contentStore);
        int currentNumOfImages = cursor.getCount();

        // delete the duplicate file if the difference is 2
        if ((currentNumOfImages - numPics) == 2) {
            cursor.moveToLast();
            int id = Integer.parseInt(cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media._ID))) - 1;
            Uri uri = Uri.parse(contentStore + "/" + id);
            this.cordova.getActivity().getContentResolver().delete(uri, null, null);
        }
    }

    /**
     * Determine if we are storing the images in internal or external storage
     *
     * @return Uri
     */
    private Uri whichContentStore() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else {
            return android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI;
        }
    }

    private void executeRequest(Request req) {
        switch (req.action) {
            case CAPTURE_AUDIO:
                this.captureAudio(req);
                break;
            case CAPTURE_IMAGE_OR_VIDEO:
                this.captureImageOrVideo(req);
                break;
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        Request req = pendingRequests.get(requestCode);

        if (req != null) {
            boolean success = true;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_DENIED) {
                    success = false;
                    break;
                }
            }

            if (success) {
                executeRequest(req);
            } else {
                pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_PERMISSION_DENIED, "Permission denied."));
            }
        }
    }

    public Bundle onSaveInstanceState() {
        return pendingRequests.toBundle();
    }

    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        pendingRequests.setLastSavedState(state, callbackContext);
    }


}
