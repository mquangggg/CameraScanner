package com.example.camerascanner.activitycamera; // Đảm bảo đúng package này

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView; // Thêm import cho TextView
import android.widget.Toast;
import android.graphics.Color; // Thêm import cho Color

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.example.camerascanner.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;


import org.opencv.android.OpenCVLoader;


public class CameraActivity extends AppCompatActivity implements
        AppPermissionHandler.PermissionCallbacks {

    private static final String TAG = "CameraActivity";

    private PreviewView previewView;
    private CustomOverlayView customOverlayView;
    private ImageView imageView;
    private FloatingActionButton btnTakePhoto;
    private ImageButton btnSelectImage;
    private TabLayout tabLayoutCameraModes;
    private TextView textViewCameraTitle;
    private TextView btnToggleAutoCapture; // Đã đổi từ ImageButton sang TextView

    private AppPermissionHandler appPermissionHandler;
    private CameraViewModel cameraViewModel;
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

        // Ánh xạ các View từ layout
        previewView = findViewById(R.id.previewView);
        customOverlayView = findViewById(R.id.customOverlayView);
        imageView = findViewById(R.id.imageView);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        btnSelectImage = findViewById(R.id.btnSelectImage);
        tabLayoutCameraModes = findViewById(R.id.tabLayoutCameraModes);
        textViewCameraTitle = findViewById(R.id.textViewCameraTitle);
        btnToggleAutoCapture = findViewById(R.id.btnToggleAutoCapture); // Ánh xạ TextView

        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        appPermissionHandler = new AppPermissionHandler(this, this);
        uiStateManager = new UiStateManager(previewView, customOverlayView, imageView, btnTakePhoto, btnSelectImage);

        // Khởi tạo ViewModel
        cameraViewModel = new ViewModelProvider(this).get(CameraViewModel.class);
        cameraViewModel.initialize(this, previewView, customOverlayView, imageView);

        // Quan sát LiveData từ ViewModel
        observeViewModel();

        // Kiểm tra và yêu cầu quyền camera khi khởi động Activity
        if (appPermissionHandler.checkCameraPermission()) {
            cameraViewModel.startCamera();
        } else {
            appPermissionHandler.requestCameraPermission();
        }

        // Thiết lập sự kiện click cho nút chụp ảnh
        btnTakePhoto.setOnClickListener(v -> cameraViewModel.takePhoto(imageView));

        // Thiết lập sự kiện click cho nút chọn ảnh từ thư viện
        btnSelectImage.setOnClickListener(v -> {
            if (appPermissionHandler.checkStoragePermission()) {
                cameraViewModel.openGallery();
            } else {
                appPermissionHandler.requestStoragePermission();
            }
        });

        // Thiết lập lắng nghe sự kiện chọn tab cho TabLayout
        tabLayoutCameraModes.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position == 0) {
                    cameraViewModel.setCameraMode(CameraViewModel.CameraMode.NORMAL_SCAN);
                    Log.d(TAG, "Chế độ: Quét tài liệu");
                } else if (position == 1) {
                    cameraViewModel.setCameraMode(CameraViewModel.CameraMode.ID_CARD_SCAN);
                    Log.d(TAG, "Chế độ: Thẻ ID");
                }
                customOverlayView.clearBoundingBox();
                customOverlayView.invalidate();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Chọn tab mặc định (ví dụ: tab "scan") khi khởi động
        if (tabLayoutCameraModes.getTabCount() > 0) {
            tabLayoutCameraModes.selectTab(tabLayoutCameraModes.getTabAt(0));
        }

        // Thiết lập sự kiện click cho nút bật/tắt tự động chụp
        btnToggleAutoCapture.setOnClickListener(v -> {
            boolean currentStatus = Boolean.TRUE.equals(cameraViewModel.isAutoCaptureEnabled.getValue());
            cameraViewModel.setAutoCaptureEnabled(!currentStatus); // Đảo ngược trạng thái
        });
    }

    /**
     * Quan sát các LiveData từ CameraViewModel để cập nhật UI.
     */
    private void observeViewModel() {
        cameraViewModel.showCameraPreview.observe(this, show -> {
            if (show) {
                uiStateManager.showCameraPreviewUi();
            } else {
                uiStateManager.showImageViewUi();
            }
        });

        cameraViewModel.overlayQuadrilateral.observe(this, quadrilateral -> {
            if (quadrilateral != null && cameraViewModel.overlayDimensions.getValue() != null) {
                Pair<Integer, Integer> dimensions = cameraViewModel.overlayDimensions.getValue();
                customOverlayView.setQuadrilateral(
                        CustomOverlayView.scalePointsToOverlayView(
                                quadrilateral,
                                dimensions.first,
                                dimensions.second,
                                previewView.getWidth(),
                                previewView.getHeight()
                        )
                );
                customOverlayView.invalidate();
            } else {
                customOverlayView.clearBoundingBox();
                customOverlayView.invalidate();
            }
        });

        cameraViewModel.imageToPreview.observe(this, imageUri -> {
            if (imageUri != null) {
                Glide.with(this).load(imageUri).into(imageView);
            } else {
                imageView.setImageDrawable(null);
            }
        });

        cameraViewModel.errorMessage.observe(this, message -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                textViewCameraTitle.setText(message);
                cameraViewModel.errorMessageHandled();
            } else {
                textViewCameraTitle.setText(getString(R.string.app_name));
            }
        });

        cameraViewModel.autoCaptureTriggered.observe(this, triggered -> {
            if (triggered != null && triggered) {
                Toast.makeText(CameraActivity.this, "Tự động chụp!", Toast.LENGTH_SHORT).show();
                cameraViewModel.takePhoto(imageView);
                cameraViewModel.autoCaptureHandled();
            }
        });

        cameraViewModel.cropActivityNeeded.observe(this, needed -> {
            if (needed != null && needed) {
                Uri currentImageUri = cameraViewModel.imageToPreview.getValue();
                if (currentImageUri != null) {
                    cameraViewModel.launchCropActivity(currentImageUri);
                } else {
                    Toast.makeText(this, "Không có ảnh để cắt.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Không có ảnh để cắt khi cropActivityNeeded là true");
                }
                cameraViewModel.cropActivityLaunched();
            }
        });

        cameraViewModel.currentCameraMode.observe(this, mode -> {
            if (mode == CameraViewModel.CameraMode.ID_CARD_SCAN) {
                Log.d(TAG, "UI đang ở chế độ THẺ ID");
            } else {
                Log.d(TAG, "UI đang ở chế độ QUÉT TÀI LIỆU");
            }
            textViewCameraTitle.setText(getString(R.string.app_name));
        });

        cameraViewModel.idCardCaptureState.observe(this, state -> {
            if (cameraViewModel.currentCameraMode.getValue() == CameraViewModel.CameraMode.ID_CARD_SCAN) {
                switch (state) {
                    case CAPTURING_FRONT:
                        textViewCameraTitle.setText("Chụp mặt trước thẻ ID");
                        break;
                    case FRONT_CAPTURED:
                        textViewCameraTitle.setText("Đã chụp mặt trước. Chụp mặt sau!");
                        break;
                    case CAPTURING_BACK:
                        textViewCameraTitle.setText("Chụp mặt sau thẻ ID");
                        break;
                    case STITCHING_COMPLETE:
                        textViewCameraTitle.setText("Đã ghép ảnh thẻ ID!");
                        break;
                    case IDLE:
                        textViewCameraTitle.setText("Sẵn sàng chụp thẻ ID.");
                        break;
                }
            } else {
                textViewCameraTitle.setText(getString(R.string.app_name));
            }
        });

        // Quan sát trạng thái bật/tắt tự động chụp để cập nhật màu chữ của TextView
        cameraViewModel.isAutoCaptureEnabled.observe(this, isEnabled -> {
            if (isEnabled) {
                btnToggleAutoCapture.setTextColor(ContextCompat.getColor(this, R.color.green_accent)); // Màu xanh khi bật
            } else {
                btnToggleAutoCapture.setTextColor(Color.WHITE); // Màu trắng khi tắt
            }
        });
    }

    @Override
    public void onCameraPermissionGranted() {
        cameraViewModel.startCamera();
    }

    @Override
    public void onCameraPermissionDenied() {
        Toast.makeText(this, getString(R.string.permission_denied_function_unavailable), Toast.LENGTH_LONG).show();
        Log.w(TAG, "Quyền camera bị từ chối.");
    }

    @Override
    public void onStoragePermissionGranted() {
        cameraViewModel.openGallery();
    }

    @Override
    public void onStoragePermissionDenied() {
        Toast.makeText(this, getString(R.string.permission_denied_function_unavailable), Toast.LENGTH_LONG).show();
        Log.w(TAG, "Quyền lưu trữ bị từ chối.");
    }
}
