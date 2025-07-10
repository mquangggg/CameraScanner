package com.example.camerascanner.activitycamera; // Đảm bảo đúng package này

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView; // Thêm import cho TextView nếu chưa có
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
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
    private TextView textViewCameraTitle; // Thêm TextView cho tiêu đề để hiển thị thông báo

    private AppPermissionHandler appPermissionHandler;
    private CameraViewModel cameraViewModel;
    private UiStateManager uiStateManager; // UiStateManager vẫn hữu ích cho quản lý View visibility

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
        tabLayoutCameraModes = findViewById(R.id.tabLayoutCameraModes);
        textViewCameraTitle = findViewById(R.id.textViewCameraTitle); // Ánh xạ TextView

        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        appPermissionHandler = new AppPermissionHandler(this, this);
        uiStateManager = new UiStateManager(previewView, customOverlayView, imageView, btnTakePhoto, btnSelectImage);

        // Khởi tạo ViewModel
        cameraViewModel = new ViewModelProvider(this).get(CameraViewModel.class);
        // Truyền các View và Activity cho ViewModel để khởi tạo các Helper
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
                // Dựa vào vị trí tab để đặt chế độ camera trong ViewModel
                if (position == 0) {
                    cameraViewModel.setCameraMode(CameraViewModel.CameraMode.NORMAL_SCAN);
                    Log.d(TAG, "Chế độ: Quét tài liệu");
                } else if (position == 1) {
                    cameraViewModel.setCameraMode(CameraViewModel.CameraMode.ID_CARD_SCAN);
                    Log.d(TAG, "Chế độ: Thẻ ID");
                }
                // Xóa lớp phủ khi chuyển chế độ
                customOverlayView.clearBoundingBox();
                customOverlayView.invalidate();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // Không cần xử lý gì khi tab không được chọn
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // Có thể thêm logic ở đây nếu muốn xử lý khi tab được chọn lại
            }
        });

        // Chọn tab mặc định (ví dụ: tab "Quét tài liệu") khi khởi động
        if (tabLayoutCameraModes.getTabCount() > 0) {
            tabLayoutCameraModes.selectTab(tabLayoutCameraModes.getTabAt(0));
        }
    }

    /**
     * Quan sát các LiveData từ CameraViewModel để cập nhật UI.
     */
    private void observeViewModel() {
        // Quan sát trạng thái hiển thị camera preview
        cameraViewModel.showCameraPreview.observe(this, show -> {
            if (show) {
                uiStateManager.showCameraPreviewUi();
            } else {
                uiStateManager.showImageViewUi();
            }
        });

        // Quan sát dữ liệu hình tứ giác để vẽ lớp phủ
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

        // Quan sát Uri của ảnh để hiển thị trong ImageView
        cameraViewModel.imageToPreview.observe(this, imageUri -> {
            if (imageUri != null) {
                Glide.with(this).load(imageUri).into(imageView);
            } else {
                imageView.setImageDrawable(null); // Xóa ảnh nếu URI null
            }
        });

        // Quan sát thông báo lỗi/hướng dẫn từ ViewModel và hiển thị Toast
        cameraViewModel.errorMessage.observe(this, message -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                // Bạn cũng có thể hiển thị thông báo này trên textViewCameraTitle
                textViewCameraTitle.setText(message);
                cameraViewModel.errorMessageHandled(); // Đánh dấu đã xử lý thông báo lỗi
            } else {
                // Nếu không có thông báo lỗi, đặt lại tiêu đề mặc định
                textViewCameraTitle.setText(getString(R.string.app_name)); // Hoặc một chuỗi mặc định khác
            }
        });

        // Quan sát khi tự động chụp được kích hoạt (chủ yếu cho chế độ thẻ ID)
        cameraViewModel.autoCaptureTriggered.observe(this, triggered -> {
            if (triggered != null && triggered) {
                Toast.makeText(CameraActivity.this, "Tự động chụp!", Toast.LENGTH_SHORT).show();
                cameraViewModel.takePhoto(imageView); // Gọi takePhoto để bắt đầu quá trình chụp
                cameraViewModel.autoCaptureHandled(); // Đánh dấu đã xử lý sự kiện
            }
        });

        // Quan sát khi cần khởi chạy CropActivity
        cameraViewModel.cropActivityNeeded.observe(this, needed -> {
            if (needed != null && needed) {
                Uri currentImageUri = cameraViewModel.imageToPreview.getValue();
                if (currentImageUri != null) {
                    cameraViewModel.launchCropActivity(currentImageUri);
                } else {
                    Toast.makeText(this, "Không có ảnh để cắt.", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Không có ảnh để cắt khi cropActivityNeeded là true");
                }
                cameraViewModel.cropActivityLaunched(); // Đánh dấu đã xử lý sự kiện
            }
        });

        // Quan sát chế độ camera hiện tại để cập nhật UI (nếu cần thêm logic UI phức tạp)
        cameraViewModel.currentCameraMode.observe(this, mode -> {
            // Ví dụ: thay đổi màu sắc hoặc biểu tượng dựa trên chế độ
            if (mode == CameraViewModel.CameraMode.ID_CARD_SCAN) {
                // Có thể làm nổi bật một số phần UI hoặc hiển thị hướng dẫn cụ thể
                Log.d(TAG, "UI đang ở chế độ THẺ ID");
            } else {
                Log.d(TAG, "UI đang ở chế độ QUÉT TÀI LIỆU");
            }
            // Đặt lại tiêu đề mặc định khi chuyển chế độ
            textViewCameraTitle.setText(getString(R.string.app_name));
        });

        // Quan sát trạng thái chụp thẻ ID để hiển thị hướng dẫn cụ thể hơn
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
                // Đảm bảo tiêu đề trở lại mặc định khi không ở chế độ ID_CARD_SCAN
                textViewCameraTitle.setText(getString(R.string.app_name));
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
