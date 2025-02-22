package fr.free.nrw.commons.contributions;

import static fr.free.nrw.commons.wikidata.WikidataConstants.PLACE_OBJECT;

import android.Manifest.permission;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.paging.DataSource.Factory;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.filepicker.DefaultCallback;
import fr.free.nrw.commons.filepicker.FilePicker;
import fr.free.nrw.commons.filepicker.FilePicker.ImageSource;
import fr.free.nrw.commons.filepicker.UploadableFile;
import fr.free.nrw.commons.kvstore.JsonKvStore;
import fr.free.nrw.commons.location.LatLng;
import fr.free.nrw.commons.location.LocationPermissionsHelper;
import fr.free.nrw.commons.location.LocationPermissionsHelper.LocationPermissionCallback;
import fr.free.nrw.commons.location.LocationServiceManager;
import fr.free.nrw.commons.nearby.Place;
import fr.free.nrw.commons.upload.UploadActivity;
import fr.free.nrw.commons.utils.DialogUtil;
import fr.free.nrw.commons.utils.PermissionUtils;
import fr.free.nrw.commons.utils.ViewUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class ContributionController {

    public static final String ACTION_INTERNAL_UPLOADS = "internalImageUploads";
    private final JsonKvStore defaultKvStore;
    private LatLng locationBeforeImageCapture;
    private boolean isInAppCameraUpload;
    public LocationPermissionCallback locationPermissionCallback;
    private LocationPermissionsHelper locationPermissionsHelper;
    // Temporarily disabled, see issue [https://github.com/commons-app/apps-android-commons/issues/5847]
    // LiveData<PagedList<Contribution>> failedAndPendingContributionList;
    LiveData<PagedList<Contribution>> pendingContributionList;
    LiveData<PagedList<Contribution>> failedContributionList;

    @Inject
    LocationServiceManager locationManager;

    @Inject
    ContributionsRepository repository;

    @Inject
    public ContributionController(@Named("default_preferences") JsonKvStore defaultKvStore) {
        this.defaultKvStore = defaultKvStore;
    }

    /**
     * Check for permissions and initiate camera click
     */
    public void initiateCameraPick(Activity activity,
        ActivityResultLauncher<String[]> inAppCameraLocationPermissionLauncher,
        ActivityResultLauncher<Intent> resultLauncher) {
        boolean useExtStorage = defaultKvStore.getBoolean("useExternalStorage", true);
        if (!useExtStorage) {
            initiateCameraUpload(activity, resultLauncher);
            return;
        }

        PermissionUtils.checkPermissionsAndPerformAction(activity,
            () -> {
                if (defaultKvStore.getBoolean("inAppCameraFirstRun")) {
                    defaultKvStore.putBoolean("inAppCameraFirstRun", false);
                    askUserToAllowLocationAccess(activity, inAppCameraLocationPermissionLauncher, resultLauncher);
                } else if (defaultKvStore.getBoolean("inAppCameraLocationPref")) {
                    createDialogsAndHandleLocationPermissions(activity,
                        inAppCameraLocationPermissionLauncher, resultLauncher);
                } else {
                    initiateCameraUpload(activity, resultLauncher);
                }
            },
            R.string.storage_permission_title,
            R.string.write_storage_permission_rationale,
            PermissionUtils.getPERMISSIONS_STORAGE());
    }

    /**
     * Asks users to provide location access
     *
     * @param activity
     */
    private void createDialogsAndHandleLocationPermissions(Activity activity,
        ActivityResultLauncher<String[]> inAppCameraLocationPermissionLauncher,
        ActivityResultLauncher<Intent> resultLauncher) {
        locationPermissionCallback = new LocationPermissionCallback() {
            @Override
            public void onLocationPermissionDenied(String toastMessage) {
                Toast.makeText(
                    activity,
                    toastMessage,
                    Toast.LENGTH_LONG
                ).show();
                initiateCameraUpload(activity, resultLauncher);
            }

            @Override
            public void onLocationPermissionGranted() {
                if (!locationPermissionsHelper.isLocationAccessToAppsTurnedOn()) {
                    showLocationOffDialog(activity, R.string.in_app_camera_needs_location,
                        R.string.in_app_camera_location_unavailable, resultLauncher);
                } else {
                    initiateCameraUpload(activity, resultLauncher);
                }
            }
        };

        locationPermissionsHelper = new LocationPermissionsHelper(
            activity, locationManager, locationPermissionCallback);
        if (inAppCameraLocationPermissionLauncher != null) {
            inAppCameraLocationPermissionLauncher.launch(
                new String[]{permission.ACCESS_FINE_LOCATION});
        }

    }

    /**
     * Shows a dialog alerting the user about location services being off and asking them to turn it
     * on
     * TODO: Add a seperate callback in LocationPermissionsHelper for this.
     *      Ref: https://github.com/commons-app/apps-android-commons/pull/5494/files#r1510553114
     *
     * @param activity           Activity reference
     * @param dialogTextResource Resource id of text to be shown in dialog
     * @param toastTextResource  Resource id of text to be shown in toast
     * @param resultLauncher
     */
    private void showLocationOffDialog(Activity activity, int dialogTextResource,
        int toastTextResource, ActivityResultLauncher<Intent> resultLauncher) {
        DialogUtil
            .showAlertDialog(activity,
                activity.getString(R.string.ask_to_turn_location_on),
                activity.getString(dialogTextResource),
                activity.getString(R.string.title_app_shortcut_setting),
                activity.getString(R.string.cancel),
                () -> locationPermissionsHelper.openLocationSettings(activity),
                () -> {
                    Toast.makeText(activity, activity.getString(toastTextResource),
                        Toast.LENGTH_LONG).show();
                    initiateCameraUpload(activity, resultLauncher);
                }
            );
    }

    public void handleShowRationaleFlowCameraLocation(Activity activity,
        ActivityResultLauncher<String[]> inAppCameraLocationPermissionLauncher,
        ActivityResultLauncher<Intent> resultLauncher) {
        DialogUtil.showAlertDialog(activity, activity.getString(R.string.location_permission_title),
            activity.getString(R.string.in_app_camera_location_permission_rationale),
            activity.getString(android.R.string.ok),
            activity.getString(android.R.string.cancel),
            () -> {
                createDialogsAndHandleLocationPermissions(activity,
                    inAppCameraLocationPermissionLauncher, resultLauncher);
            },
            () -> locationPermissionCallback.onLocationPermissionDenied(
                activity.getString(R.string.in_app_camera_location_permission_denied)),
            null
        );
    }

    /**
     * Suggest user to attach location information with pictures. If the user selects "Yes", then:
     * <p>
     * Location is taken from the EXIF if the default camera application does not redact location
     * tags.
     * <p>
     * Otherwise, if the EXIF metadata does not have location information, then location captured by
     * the app is used
     *
     * @param activity
     */
    private void askUserToAllowLocationAccess(Activity activity,
        ActivityResultLauncher<String[]> inAppCameraLocationPermissionLauncher,
        ActivityResultLauncher<Intent> resultLauncher) {
        DialogUtil.showAlertDialog(activity,
            activity.getString(R.string.in_app_camera_location_permission_title),
            activity.getString(R.string.in_app_camera_location_access_explanation),
            activity.getString(R.string.option_allow),
            activity.getString(R.string.option_dismiss),
            () -> {
                defaultKvStore.putBoolean("inAppCameraLocationPref", true);
                createDialogsAndHandleLocationPermissions(activity,
                    inAppCameraLocationPermissionLauncher, resultLauncher);
            },
            () -> {
                ViewUtil.showLongToast(activity, R.string.in_app_camera_location_permission_denied);
                defaultKvStore.putBoolean("inAppCameraLocationPref", false);
                initiateCameraUpload(activity, resultLauncher);
            },
            null
        );
    }

    /**
     * Initiate gallery picker
     */
    public void initiateGalleryPick(final Activity activity, ActivityResultLauncher<Intent> resultLauncher, final boolean allowMultipleUploads) {
        initiateGalleryUpload(activity, resultLauncher, allowMultipleUploads);
    }

    /**
     * Initiate gallery picker with permission
     */
    public void initiateCustomGalleryPickWithPermission(final Activity activity, ActivityResultLauncher<Intent> resultLauncher) {
        setPickerConfiguration(activity, true);

        PermissionUtils.checkPermissionsAndPerformAction(activity,
            () -> FilePicker.openCustomSelector(activity, resultLauncher, 0),
            R.string.storage_permission_title,
            R.string.write_storage_permission_rationale,
            PermissionUtils.getPERMISSIONS_STORAGE());
    }


    /**
     * Open chooser for gallery uploads
     */
    private void initiateGalleryUpload(final Activity activity, ActivityResultLauncher<Intent> resultLauncher,
        final boolean allowMultipleUploads) {
        setPickerConfiguration(activity, allowMultipleUploads);
        FilePicker.openGallery(activity, resultLauncher, 0, isDocumentPhotoPickerPreferred());
    }

    /**
     * Sets configuration for file picker
     */
    private void setPickerConfiguration(Activity activity,
        boolean allowMultipleUploads) {
        boolean copyToExternalStorage = defaultKvStore.getBoolean("useExternalStorage", true);
        FilePicker.configuration(activity)
            .setCopyTakenPhotosToPublicGalleryAppFolder(copyToExternalStorage)
            .setAllowMultiplePickInGallery(allowMultipleUploads);
    }

    /**
     * Initiate camera upload by opening camera
     */
    private void initiateCameraUpload(Activity activity, ActivityResultLauncher<Intent> resultLauncher) {
        setPickerConfiguration(activity, false);
        if (defaultKvStore.getBoolean("inAppCameraLocationPref", false)) {
            locationBeforeImageCapture = locationManager.getLastLocation();
        }
        isInAppCameraUpload = true;
        FilePicker.openCameraForImage(activity, resultLauncher, 0);
    }

    private boolean isDocumentPhotoPickerPreferred(){
        return defaultKvStore.getBoolean(
            "openDocumentPhotoPickerPref", true);
    }

    public void onPictureReturnedFromGallery(ActivityResult result, Activity activity, FilePicker.Callbacks callbacks){

        if(isDocumentPhotoPickerPreferred()){
            FilePicker.onPictureReturnedFromDocuments(result, activity, callbacks);
        } else {
            FilePicker.onPictureReturnedFromGallery(result, activity, callbacks);
        }
    }

    public void onPictureReturnedFromCustomSelector(ActivityResult result, Activity activity, @NonNull FilePicker.Callbacks callbacks) {
        FilePicker.onPictureReturnedFromCustomSelector(result, activity, callbacks);
    }

    public void onPictureReturnedFromCamera(ActivityResult result, Activity activity, @NonNull FilePicker.Callbacks callbacks) {
        FilePicker.onPictureReturnedFromCamera(result, activity, callbacks);
    }

    /**
     * Attaches callback for file picker.
     */
    public void handleActivityResultWithCallback(Activity activity, FilePicker.HandleActivityResult handleActivityResult) {

        handleActivityResult.onHandleActivityResult(new DefaultCallback() {

                @Override
                public void onCanceled(final ImageSource source, final int type) {
                    super.onCanceled(source, type);
                    defaultKvStore.remove(PLACE_OBJECT);
                }

                @Override
                public void onImagePickerError(Exception e, FilePicker.ImageSource source,
                    int type) {
                    ViewUtil.showShortToast(activity, R.string.error_occurred_in_picking_images);
                }

                @Override
                public void onImagesPicked(@NonNull List<UploadableFile> imagesFiles,
                    FilePicker.ImageSource source, int type) {
                    Intent intent = handleImagesPicked(activity, imagesFiles);
                    activity.startActivity(intent);
                }
            });
    }

    public List<UploadableFile> handleExternalImagesPicked(Activity activity,
        Intent data) {
        return FilePicker.handleExternalImagesPicked(data, activity);
    }

    /**
     * Returns intent to be passed to upload activity Attaches place object for nearby uploads and
     * location before image capture if in-app camera is used
     */
    private Intent handleImagesPicked(Context context,
        List<UploadableFile> imagesFiles) {
        Intent shareIntent = new Intent(context, UploadActivity.class);
        shareIntent.setAction(ACTION_INTERNAL_UPLOADS);
        shareIntent
            .putParcelableArrayListExtra(UploadActivity.EXTRA_FILES, new ArrayList<>(imagesFiles));
        Place place = defaultKvStore.getJson(PLACE_OBJECT, Place.class);

        if (place != null) {
            shareIntent.putExtra(PLACE_OBJECT, place);
        }

        if (locationBeforeImageCapture != null) {
            shareIntent.putExtra(
                UploadActivity.LOCATION_BEFORE_IMAGE_CAPTURE,
                locationBeforeImageCapture);
        }

        shareIntent.putExtra(
            UploadActivity.IN_APP_CAMERA_UPLOAD,
            isInAppCameraUpload
        );
        isInAppCameraUpload = false;    // reset the flag for next use
        return shareIntent;
    }

    /**
     * Fetches the contributions with the state "IN_PROGRESS", "QUEUED" and "PAUSED" and then it
     * populates the `pendingContributionList`.
     **/
    void getPendingContributions() {
        final PagedList.Config pagedListConfig =
            (new PagedList.Config.Builder())
                .setPrefetchDistance(50)
                .setPageSize(10).build();
        Factory<Integer, Contribution> factory;
        factory = repository.fetchContributionsWithStates(
            Arrays.asList(Contribution.STATE_IN_PROGRESS, Contribution.STATE_QUEUED,
                Contribution.STATE_PAUSED));

        LivePagedListBuilder livePagedListBuilder = new LivePagedListBuilder(factory,
            pagedListConfig);
        pendingContributionList = livePagedListBuilder.build();
    }

    /**
     * Fetches the contributions with the state "FAILED" and populates the
     * `failedContributionList`.
     **/
    void getFailedContributions() {
        final PagedList.Config pagedListConfig =
            (new PagedList.Config.Builder())
                .setPrefetchDistance(50)
                .setPageSize(10).build();
        Factory<Integer, Contribution> factory;
        factory = repository.fetchContributionsWithStates(
            Collections.singletonList(Contribution.STATE_FAILED));

        LivePagedListBuilder livePagedListBuilder = new LivePagedListBuilder(factory,
            pagedListConfig);
        failedContributionList = livePagedListBuilder.build();
    }

    /**
     * Temporarily disabled, see issue [https://github.com/commons-app/apps-android-commons/issues/5847]
     * Fetches the contributions with the state "IN_PROGRESS", "QUEUED", "PAUSED" and "FAILED" and
     * then it populates the `failedAndPendingContributionList`.
     **/
//    void getFailedAndPendingContributions() {
//        final PagedList.Config pagedListConfig =
//            (new PagedList.Config.Builder())
//                .setPrefetchDistance(50)
//                .setPageSize(10).build();
//        Factory<Integer, Contribution> factory;
//        factory = repository.fetchContributionsWithStates(
//            Arrays.asList(Contribution.STATE_IN_PROGRESS, Contribution.STATE_QUEUED,
//                Contribution.STATE_PAUSED, Contribution.STATE_FAILED));
//
//        LivePagedListBuilder livePagedListBuilder = new LivePagedListBuilder(factory,
//            pagedListConfig);
//        failedAndPendingContributionList = livePagedListBuilder.build();
//    }
}
