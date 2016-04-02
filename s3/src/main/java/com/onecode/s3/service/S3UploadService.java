package com.onecode.s3.service;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.mobileconnectors.s3.transfermanager.TransferManager;
import com.amazonaws.mobileconnectors.s3.transfermanager.Upload;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.onecode.s3.R;
import com.onecode.s3.callback.S3Callback;
import com.onecode.s3.model.S3BucketData;
import com.onecode.s3.model.S3Credentials;

import java.io.File;

/*
* It's important to understand that this service doesn't know ANYONE. It's decoupled from the
* rest of the app. It receives the minimum necessary information to perform an upload to an
* amazon bucket and IF AND ONLY IF the s3Callback object is not null, it sends a broadcast message
* to whomever it may concern.
* */
public class S3UploadService extends IntentService {

    private static final String TAG = "S3UploadService";

    private static final String ACTION_UPLOAD = "com.onecode.android.services.live.action.UPLOAD";

    public static final String EXTRA_SERIALIZABLE = "EXTRA_SERIALIZABLE";

    private static final String EXTRA_S3_BUCKET_DATA = "com.onecode.android.services.live.extra.S3_BUCKET_DATA";
    private static final String EXTRA_FILE = "com.onecode.android.services.live.extra.FILE";
    private static final String EXTRA_DELETE_FILE = "com.onecode.android.services.live.extra.DELETE_FILE";
    private static final String EXTRA_S3_CALLBACK = "com.onecode.android.services.live.extra.S3_CALLBACK";

    private static boolean VERBOSE = true;

    public S3UploadService() {
        super(TAG);
    }

    /*
    * Helper method to start service
    * */
    public static void upload(Context context, S3BucketData s3BucketData, File file, boolean deleteFileAfter, S3Callback s3Callback) {
        Intent intent = new Intent(context, S3UploadService.class);
        intent.setAction(ACTION_UPLOAD);
        intent.putExtra(EXTRA_S3_BUCKET_DATA, s3BucketData);
        intent.putExtra(EXTRA_FILE, file);
        intent.putExtra(EXTRA_DELETE_FILE, deleteFileAfter);
        intent.putExtra(EXTRA_S3_CALLBACK, s3Callback);
        context.startService(intent);
    }

    public static void upload(Context context, S3BucketData s3BucketData, File file, boolean deleteFileAfter) {
        upload(context, s3BucketData, file, deleteFileAfter, null);
    }

    //    region onHandleIntent()

    /*
    * We simply retrieve the extras and call handleUpload()
    * */
    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (!ACTION_UPLOAD.equals(action)) return;

        S3BucketData s3BucketData = (S3BucketData) intent.getSerializableExtra(EXTRA_S3_BUCKET_DATA);
        File file = (File) intent.getSerializableExtra(EXTRA_FILE);
        boolean deleteFileAfter = intent.getBooleanExtra(EXTRA_DELETE_FILE, true);
        S3Callback s3Callback = (S3Callback) intent.getSerializableExtra(EXTRA_S3_CALLBACK);
        handleUpload(s3BucketData, file, deleteFileAfter, s3Callback);
    }
//    endregion

    //    region handleUpload

    /*
    * This method performs the upload in three simple steps:
    * 1) Get the transfer manager which allows us to perform uploads to a bucket
    * 2) Create the request object
    * 3) Perform the request and wait for it to complete
    * */
    private void handleUpload(S3BucketData s3BucketData, File file, boolean deleteFileAfter, S3Callback s3Callback) {

        TransferManager transferManager = setUpAmazonClient(s3BucketData);
        PutObjectRequest request = buildPor(s3BucketData, file, deleteFileAfter);
        Upload upload = transferManager.upload(request);

        try {
            upload.waitForCompletion();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (AmazonS3Exception s3e) {
            // Possible Bad Digest. Retry
            if (VERBOSE) {
                Log.w(TAG, "AmazonS3Exception. retrying.");
                Log.d(TAG, s3e.toString());
            }
        }

//        It's only when the s3Callback provided isn't null that we will send the broadcast message
        if (s3Callback == null) return;
        Intent intent = new Intent(s3Callback.getActionCallback());
        intent.putExtra(EXTRA_SERIALIZABLE, s3Callback.getExtra());
        sendBroadcast(intent);
    }
//endregion

    //    region amazon client setup
    private TransferManager setUpAmazonClient(S3BucketData s3BucketData) {
        System.setProperty(getString(R.string.s3_system_property), "true");
        S3Credentials s3Credentials = s3BucketData.getS3Credentials();
        BasicSessionCredentials basicSessionCredentials =
                new BasicSessionCredentials(s3Credentials.getAccessKey(),
                        s3Credentials.getSecretKey(),
                        s3Credentials.getSessionToken());
        TransferManager transferManager = new TransferManager(basicSessionCredentials);
        Region region = Region.getRegion(Regions.fromName(s3BucketData.getRegion()));
        transferManager.getAmazonS3Client().setRegion(region);
        return transferManager;
    }
//    endregion

    //    region PutObjectRequest creation
    private PutObjectRequest buildPor(S3BucketData s3BucketData, final File file, final boolean deleteFileAfter) {

        final String bucket = s3BucketData.getBucket();
        final String key = s3BucketData.getKey();

        final PutObjectRequest por = new PutObjectRequest(bucket, key, file);
        por.setGeneralProgressListener(new ProgressListener() {
            final String url = String.format(getString(R.string.s3_format_url), bucket, key);
            private long uploadStartTime;

            @Override
            public void progressChanged(com.amazonaws.event.ProgressEvent progressEvent) {
                try {
                    if (progressEvent.getEventCode() == ProgressEvent.STARTED_EVENT_CODE) {
                        uploadStartTime = System.currentTimeMillis();
                    } else if (progressEvent.getEventCode() == com.amazonaws.event.ProgressEvent.COMPLETED_EVENT_CODE) {
                        long uploadDurationMillis = System.currentTimeMillis() - uploadStartTime;
                        int bytesPerSecond = (int) (file.length() / (uploadDurationMillis / 1000.0));
                        if (VERBOSE) {
                            double fileSize = file.length() / 1000.0;
                            double uploadDuration = uploadDurationMillis;
                            double uploadSpeed = bytesPerSecond / 1000.0;
                            Log.i(TAG, String.format(S3UploadService.this.getString(R.string.s3_format_uploaded), fileSize, uploadDuration, uploadSpeed));
                        }

                        if (deleteFileAfter) {
                            file.delete();
                        }
                    } else if (progressEvent.getEventCode() == ProgressEvent.FAILED_EVENT_CODE) {
                        if (VERBOSE) Log.e(TAG, String.format(S3UploadService.this.getString(R.string.s3_format_upload_failed), url));
                    }
                } catch (Exception excp) {
                    if (VERBOSE) Log.e(TAG, "ProgressListener error");
                    excp.printStackTrace();
                }
            }
        });
        por.setCannedAcl(CannedAccessControlList.PublicRead);
        return por;
    }
//    endregion

    public static void setVerbose(boolean verbose) {
        S3UploadService.VERBOSE = verbose;
    }
}