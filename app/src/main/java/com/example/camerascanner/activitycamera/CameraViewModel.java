package com.example.camerascanner.activitycamera; // Đảm bảo đúng package này

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.opencv.core.MatOfPoint;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class CameraViewModel extends AndroidViewModel implements
        CameraManager.CameraCallbacks,
        GalleryHandler.GalleryCallbacks,
        ActivityLauncher.ActivityLauncherCallbacks {

    private static final String TAG = "CameraViewModel";

    public enum CameraMode {
        NORMAL_SCAN,
        ID_CARD_SCAN
    }

    public enum IdCardCaptureState {
        IDLE,
        CAPTURING_FRONT,
        FRONT_CAPTURED,
        CAPTURING_BACK,
        STITCHING_COMPLETE
    }

    // LiveData cho trạng thái UI và dữ liệu
    private final MutableLiveData<Boolean> _showCameraPreview = new MutableLiveData<>(true);
    public LiveData<Boolean> showCameraPreview = _showCameraPreview;

    private final MutableLiveData<MatOfPoint> _overlayQuadrilateral = new MutableLiveData<>();
    public LiveData<MatOfPoint> overlayQuadrilateral = _overlayQuadrilateral;

    private final MutableLiveData<Pair<Integer, Integer>> _overlayDimensions = new MutableLiveData<>();
    public LiveData<Pair<Integer, Integer>> overlayDimensions = _overlayDimensions;

    private final MutableLiveData<Uri> _imageToPreview = new MutableLiveData<>();
    public LiveData<Uri> imageToPreview = _imageToPreview;

    private final MutableLiveData<String> _errorMessage = new MutableLiveData<>();
    public LiveData<String> errorMessage = _errorMessage;

    private final MutableLiveData<Boolean> _autoCaptureTriggered = new MutableLiveData<>();
    public LiveData<Boolean> autoCaptureTriggered = _autoCaptureTriggered;

    private final MutableLiveData<Boolean> _cropActivityNeeded = new MutableLiveData<>();
    public LiveData<Boolean> cropActivityNeeded = _cropActivityNeeded;

    // LiveData cho chế độ camera hiện tại
    private final MutableLiveData<CameraMode> _currentCameraMode = new MutableLiveData<>(CameraMode.NORMAL_SCAN);
    public LiveData<CameraMode> currentCameraMode = _currentCameraMode;

    // LiveData cho trạng thái chụp thẻ ID
    private final MutableLiveData<IdCardCaptureState> _idCardCaptureState = new MutableLiveData<>(IdCardCaptureState.IDLE);
    public LiveData<IdCardCaptureState> idCardCaptureState = _idCardCaptureState;

    // LiveData mới cho trạng thái bật/tắt tự động chụp toàn cục
    private final MutableLiveData<Boolean> _isAutoCaptureEnabled = new MutableLiveData<>(true); // Mặc định bật
    public LiveData<Boolean> isAutoCaptureEnabled = _isAutoCaptureEnabled;

    private Uri frontIdCardImageUri;

    // Helper classes
    private CameraManager cameraManager;
    private ImageProcessor imageProcessor;
    private GalleryHandler galleryHandler;
    private ActivityLauncher activityLauncher;
    private ExecutorService cameraExecutor;

    public CameraViewModel(@NonNull Application application) {
        super(application);
        cameraExecutor = Executors.newSingleThreadExecutor();
        imageProcessor = new ImageProcessor();
    }

    // Phương thức khởi tạo các helper cần context/lifecycle. Sẽ được gọi từ Activity
    public void initialize(CameraActivity activity, PreviewView previewView, CustomOverlayView customOverlayView, ImageView imageView) {
        if (cameraManager == null) {
            cameraManager = new CameraManager(activity, activity, previewView, customOverlayView, imageProcessor, cameraExecutor, this);
            // Đảm bảo trạng thái tự động chụp ban đầu được thiết lập cho CameraManager
            cameraManager.setAutoCaptureGloballyEnabled(Boolean.TRUE.equals(_isAutoCaptureEnabled.getValue()));
        }
        if (galleryHandler == null) {
            galleryHandler = new GalleryHandler(activity, this);
        }
        if (activityLauncher == null) {
            activityLauncher = new ActivityLauncher(activity, this);
        }
    }

    public void startCamera() {
        if (cameraManager != null) {
            cameraManager.startCamera();
        } else {
            _errorMessage.postValue("Camera manager not initialized.");
        }
    }

    /**
     * Phương thức để thay đổi chế độ camera (quét tài liệu hoặc thẻ ID).
     * @param mode Chế độ camera mới.
     */
    public void setCameraMode(CameraMode mode) {
        _currentCameraMode.postValue(mode);
        if (cameraManager != null) {
            cameraManager.setIdCardMode(mode == CameraMode.ID_CARD_SCAN);
        }

        if (mode == CameraMode.NORMAL_SCAN) {
            _idCardCaptureState.postValue(IdCardCaptureState.IDLE);
            frontIdCardImageUri = null;
            _errorMessage.postValue("Chế độ quét tài liệu.");
        } else if (mode == CameraMode.ID_CARD_SCAN) {
            _idCardCaptureState.postValue(IdCardCaptureState.CAPTURING_FRONT);
            _errorMessage.postValue("Hãy chụp mặt trước của thẻ ID.");
        }
    }

    /**
     * Bật hoặc tắt chức năng tự động chụp toàn cục.
     * @param enabled True để bật, false để tắt.
     */
    public void setAutoCaptureEnabled(boolean enabled) {
        _isAutoCaptureEnabled.postValue(enabled);
        if (cameraManager != null) {
            cameraManager.setAutoCaptureGloballyEnabled(enabled);
        }
        _errorMessage.postValue(enabled ? "Tự động chụp đã BẬT." : "Tự động chụp đã TẮT.");
    }

    /**
     * Kích hoạt hành động chụp ảnh dựa trên chế độ camera hiện tại.
     * Nếu là chế độ ID_CARD_SCAN, sẽ quản lý quy trình chụp 2 lần.
     * @param imageView ImageView để hiển thị ảnh preview (nếu cần).
     */
    public void takePhoto(ImageView imageView) {
        if (cameraManager == null) {
            _errorMessage.postValue("Camera manager not initialized.");
            return;
        }

        if (_currentCameraMode.getValue() == CameraMode.ID_CARD_SCAN) {
            IdCardCaptureState currentState = _idCardCaptureState.getValue();
            if (currentState == IdCardCaptureState.CAPTURING_FRONT || currentState == IdCardCaptureState.FRONT_CAPTURED) {
                _idCardCaptureState.postValue(IdCardCaptureState.CAPTURING_FRONT);
                cameraManager.takePhoto(imageView);
                Log.d(TAG, "Đang chụp ảnh mặt trước của thẻ ID.");
            } else if (currentState == IdCardCaptureState.CAPTURING_BACK) {
                cameraManager.takePhoto(imageView);
                Log.d(TAG, "Đang chụp ảnh mặt sau của thẻ ID.");
            }
        } else {
            cameraManager.takePhoto(imageView);
            Log.d(TAG, "Đang chụp ảnh tài liệu thông thường.");
        }
    }

    public void openGallery() {
        if (galleryHandler != null) {
            galleryHandler.openGallery();
        } else {
            _errorMessage.postValue("Gallery handler not initialized.");
        }
    }

    public void launchImagePreviewActivity(Uri imageUri) {
        if (activityLauncher != null) {
            activityLauncher.launchImagePreviewActivity(imageUri);
        } else {
            _errorMessage.postValue("Activity launcher not initialized.");
        }
    }

    public void launchCropActivity(Uri imageUri) {
        if (activityLauncher != null) {
            activityLauncher.launchCropActivity(imageUri);
        } else {
            _errorMessage.postValue("Activity launcher not initialized.");
        }
    }

    //region CameraManager.CameraCallbacks
    @Override
    public void onImageCaptured(Uri imageUri) {
        _imageToPreview.postValue(imageUri);
        _showCameraPreview.postValue(false);

        if (_currentCameraMode.getValue() == CameraMode.ID_CARD_SCAN) {
            IdCardCaptureState currentState = _idCardCaptureState.getValue();
            if (currentState == IdCardCaptureState.CAPTURING_FRONT) {
                frontIdCardImageUri = imageUri;
                _idCardCaptureState.postValue(IdCardCaptureState.FRONT_CAPTURED);
                _showCameraPreview.postValue(true);
                _imageToPreview.postValue(null);
                Log.d(TAG, "Đã chụp mặt trước thẻ ID: " + imageUri.toString());
                _errorMessage.postValue("Hãy chụp mặt sau của thẻ ID.");
                _idCardCaptureState.postValue(IdCardCaptureState.CAPTURING_BACK);
            } else if (currentState == IdCardCaptureState.CAPTURING_BACK) {
                Uri backIdCardImageUri = imageUri;
                _showCameraPreview.postValue(false);
                _imageToPreview.postValue(null);
                Log.d(TAG, "Đã chụp mặt sau thẻ ID: " + backIdCardImageUri.toString());

                cameraExecutor.execute(() -> {
                    try {
                        Bitmap frontBitmap = MediaStore.Images.Media.getBitmap(getApplication().getContentResolver(), frontIdCardImageUri);
                        Bitmap backBitmap = MediaStore.Images.Media.getBitmap(getApplication().getContentResolver(), backIdCardImageUri);

                        Uri stitchedUri = imageProcessor.stitchImages(frontBitmap, backBitmap, getApplication().getApplicationContext());
                        if (stitchedUri != null) {
                            _imageToPreview.postValue(stitchedUri);
                            _idCardCaptureState.postValue(IdCardCaptureState.STITCHING_COMPLETE);
                            Log.d(TAG, "Đã ghép ảnh thẻ ID thành công: " + stitchedUri.toString());
                            _errorMessage.postValue("Đã ghép ảnh thẻ ID. Bạn có thể cắt hoặc xác nhận.");
                            launchImagePreviewActivity(stitchedUri);
                        } else {
                            _errorMessage.postValue("Lỗi khi ghép ảnh thẻ ID.");
                            _idCardCaptureState.postValue(IdCardCaptureState.IDLE);
                            _showCameraPreview.postValue(true);
                        }
                        if (frontBitmap != null) frontBitmap.recycle();
                        if (backBitmap != null) backBitmap.recycle();
                    } catch (IOException e) {
                        Log.e(TAG, "Lỗi khi đọc Bitmap để ghép ảnh: " + e.getMessage(), e);
                        _errorMessage.postValue("Lỗi khi xử lý ảnh để ghép.");
                        _idCardCaptureState.postValue(IdCardCaptureState.IDLE);
                        _showCameraPreview.postValue(true);
                    }
                });
            }
        } else {
            Log.d(TAG, "Ảnh tài liệu thông thường đã chụp: " + imageUri.toString());
            launchImagePreviewActivity(imageUri);
        }
    }

    @Override
    public void onCameraError(String message) {
        _errorMessage.postValue(message);
    }

    @Override
    public void onAutoCaptureTriggered() {
        _autoCaptureTriggered.postValue(true);
    }

    @Override
    public void updateOverlay(MatOfPoint quadrilateral, int effectiveWidth, int effectiveHeight) {
        _overlayQuadrilateral.postValue(quadrilateral);
        _overlayDimensions.postValue(new Pair<>(effectiveWidth, effectiveHeight));
    }

    @Override
    public void clearOverlay() {
        _overlayQuadrilateral.postValue(null);
        _overlayDimensions.postValue(null);
    }
    //endregion

    //region GalleryHandler.GalleryCallbacks
    @Override
    public void onImageSelected(Uri imageUri) {
        _imageToPreview.postValue(imageUri);
        _showCameraPreview.postValue(false);
        _currentCameraMode.postValue(CameraMode.NORMAL_SCAN);
        _idCardCaptureState.postValue(IdCardCaptureState.IDLE);
        frontIdCardImageUri = null;
        launchImagePreviewActivity(imageUri);
    }

    @Override
    public void onImageSelectionFailed(String message) {
        _errorMessage.postValue(message);
        _showCameraPreview.postValue(true);
    }
    //endregion

    //region ActivityLauncher.ActivityLauncherCallbacks
    @Override
    public void onImagePreviewResult(Uri imageUri) {
        _imageToPreview.postValue(imageUri);
        _cropActivityNeeded.postValue(true);
        Log.d(TAG, "Ảnh được xác nhận từ ImagePreviewActivity: " + imageUri.toString());
    }

    @Override
    public void onImagePreviewCanceled() {
        if (_currentCameraMode.getValue() == CameraMode.ID_CARD_SCAN) {
            if (_idCardCaptureState.getValue() == IdCardCaptureState.CAPTURING_BACK) {
                _idCardCaptureState.postValue(IdCardCaptureState.FRONT_CAPTURED);
                _errorMessage.postValue("Đã hủy chụp mặt sau. Hãy chụp lại mặt sau.");
                _showCameraPreview.postValue(true);
            } else {
                _idCardCaptureState.postValue(IdCardCaptureState.IDLE);
                frontIdCardImageUri = null;
                _errorMessage.postValue("Quá trình chụp thẻ ID đã bị hủy.");
                _showCameraPreview.postValue(true);
            }
        } else {
            _showCameraPreview.postValue(true);
            _imageToPreview.postValue(null);
        }
        onLauncherClosed();
    }

    @Override
    public void onImagePreviewFailed(String message) {
        _errorMessage.postValue(message);
        if (_currentCameraMode.getValue() == CameraMode.ID_CARD_SCAN) {
            _idCardCaptureState.postValue(IdCardCaptureState.IDLE);
            frontIdCardImageUri = null;
        }
        _showCameraPreview.postValue(true);
        _imageToPreview.postValue(null);
        onLauncherClosed();
    }

    @Override
    public void onLauncherClosed() {
        if (_currentCameraMode.getValue() == CameraMode.NORMAL_SCAN ||
                _idCardCaptureState.getValue() == IdCardCaptureState.STITCHING_COMPLETE ||
                _idCardCaptureState.getValue() == IdCardCaptureState.IDLE) {
            _showCameraPreview.postValue(true);
            _imageToPreview.postValue(null);
        }
    }
    //endregion

    public void autoCaptureHandled() {
        _autoCaptureTriggered.postValue(false);
    }
    public void cropActivityLaunched() {
        _cropActivityNeeded.postValue(false);
    }
    public void errorMessageHandled() {
        _errorMessage.postValue(null);
    }


    @Override
    protected void onCleared() {
        super.onCleared();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (cameraManager != null) {
            cameraManager.unbindCamera();
        }
        MatOfPoint currentOverlay = _overlayQuadrilateral.getValue();
        if (currentOverlay != null) {
            currentOverlay.release();
        }
        Log.d(TAG, "CameraViewModel onCleared");
    }
}
