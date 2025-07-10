package com.example.camerascanner.activitycamera.CameraActivity;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;

import com.example.camerascanner.R;
import com.example.camerascanner.activitycamera.CustomOverlayView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.MatOfPoint;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity implements
        AppPermissionHandler.PermissionCallbacks,
        CameraManager.CameraCallbacks,
        GalleryHandler.GalleryCallbacks,
        ActivityLauncher.ActivityLauncherCallbacks {

    private static final String TAG = "CameraActivity";

    private PreviewView previewView;
    private CustomOverlayView customOverlayView;
    private ImageView imageView;
    private FloatingActionButton btnTakePhoto;
    private ImageButton btnSelectImage;
    private TabLayout tabLayoutCameraModes;

    private ExecutorService cameraExecutor;
    private AppPermissionHandler appPermissionHandler;

    private CameraManager cameraManager;
    private ImageProcessor imageProcessor;
    private GalleryHandler galleryHandler;
    private ActivityLauncher activityLauncher;
    private UiStateManager uiStateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialization failed!");
            Toast.makeText(this, "OpenCV failed to load!", Toast.LENGTH_LONG).show();
            return;
        } else {
            Log.d(TAG, "OpenCV initialization successful!");
        }

        previewView = findViewById(R.id.previewView);
        customOverlayView = findViewById(R.id.customOverlayView);
        imageView = findViewById(R.id.imageView);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        btnSelectImage = findViewById(R.id.btnSelectImage);

        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        cameraExecutor = Executors.newSingleThreadExecutor();

        appPermissionHandler = new AppPermissionHandler(this, this);

        imageProcessor = new ImageProcessor();
        cameraManager = new CameraManager(this, this, previewView, customOverlayView, imageProcessor, cameraExecutor, this);
        galleryHandler = new GalleryHandler(this, this);
        activityLauncher = new ActivityLauncher(this, this);
        uiStateManager = new UiStateManager(previewView, customOverlayView, imageView, btnTakePhoto, btnSelectImage);


        if (appPermissionHandler.checkCameraPermission()) {
            cameraManager.startCamera();
        } else {
            appPermissionHandler.requestCameraPermission();
        }

        btnTakePhoto.setOnClickListener(v -> cameraManager.takePhoto(imageView));

        btnSelectImage.setOnClickListener(v -> {
            if (appPermissionHandler.checkStoragePermission()) {
                galleryHandler.openGallery();
            } else {
                appPermissionHandler.requestStoragePermission();
            }
        });

        uiStateManager.showCameraPreviewUi();

        tabLayoutCameraModes = findViewById(R.id.tabLayoutCameraModes);

        tabLayoutCameraModes.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                boolean isIdCardMode = (position == 1);
                cameraManager.setIdCardMode(isIdCardMode);

                switch (position) {
                    case 0:
                        Log.d(TAG, "Chế độ: Quét");
                        break;
                    case 1:
                        Log.d(TAG, "Chế độ: Thẻ ID");
                        Toast.makeText(CameraActivity.this, "Đã chuyển sang chế độ Thẻ ID. Tự động chụp nếu phát hiện.", Toast.LENGTH_SHORT).show();
                        break;
                }
                customOverlayView.clearBoundingBox();
                customOverlayView.invalidate();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        if (tabLayoutCameraModes.getTabCount() > 0) {
            tabLayoutCameraModes.selectTab(tabLayoutCameraModes.getTabAt(0));
        }
    }

    @Override
    public void onCameraPermissionGranted() {
        cameraManager.startCamera();
    }

    @Override
    public void onCameraPermissionDenied() {
        Toast.makeText(this, getString(R.string.permission_denied_function_unavailable), Toast.LENGTH_LONG).show();
        Log.w(TAG, "Quyền camera bị từ chối.");
    }

    @Override
    public void onStoragePermissionGranted() {
        galleryHandler.openGallery();
    }

    @Override
    public void onStoragePermissionDenied() {
        Toast.makeText(this, getString(R.string.permission_denied_function_unavailable), Toast.LENGTH_LONG).show();
        Log.w(TAG, "Quyền lưu trữ bị từ chối.");
    }

    @Override
    public void onImageCaptured(Uri imageUri) {
        uiStateManager.showImageViewUi();
        activityLauncher.launchImagePreviewActivity(imageUri);
    }

    @Override
    public void onCameraError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAutoCaptureTriggered() {
        runOnUiThread(() -> {
            Toast.makeText(CameraActivity.this, "Tự động chụp thẻ ID!", Toast.LENGTH_SHORT).show();
            cameraManager.takePhoto(imageView);
        });
    }

    @Override
    public void updateOverlay(MatOfPoint quadrilateral, int effectiveWidth, int effectiveHeight) {
        runOnUiThread(() -> {
            customOverlayView.setQuadrilateral(
                    CustomOverlayView.scalePointsToOverlayView(
                            quadrilateral,
                            effectiveWidth,
                            effectiveHeight,
                            previewView.getWidth(),
                            previewView.getHeight()
                    )
            );
            customOverlayView.invalidate();
        });
    }

    @Override
    public void clearOverlay() {
        runOnUiThread(() -> {
            customOverlayView.clearBoundingBox();
            customOverlayView.invalidate();
        });
    }

    @Override
    public void onImageSelected(Uri imageUri) {
        Log.d(TAG, "Ảnh được tải từ thư viện, URI gốc: " + imageUri);
        uiStateManager.showImageViewUi();
        activityLauncher.launchImagePreviewActivity(imageUri);
    }

    @Override
    public void onImageSelectionFailed(String message) {
        Log.e(TAG, message);
        uiStateManager.showCameraPreviewUi(); // Quay lại preview nếu chọn ảnh thất bại
    }

    @Override
    public void onImagePreviewResult(Uri imageUri) {
        activityLauncher.launchCropActivity(imageUri);
    }

    @Override
    public void onImagePreviewCanceled() {
        // Xử lý khi người dùng hủy bỏ ImagePreviewActivity
        // Có thể quay lại camera preview
    }

    @Override
    public void onImagePreviewFailed(String message) {
        Log.e(TAG, message);
        // Xử lý khi ImagePreviewActivity gặp lỗi
    }

    @Override
    public void onLauncherClosed() {
        uiStateManager.showCameraPreviewUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        cameraManager.unbindCamera();
    }
}