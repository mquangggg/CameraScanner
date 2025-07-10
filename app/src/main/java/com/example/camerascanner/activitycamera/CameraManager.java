package com.example.camerascanner.activitycamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.bumptech.glide.Glide;
import com.example.camerascanner.R;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class CameraManager {

    private static final String TAG = "CameraManager";

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final CustomOverlayView customOverlayView;
    private final ImageProcessor imageProcessor;
    private final ExecutorService cameraExecutor;
    private final CameraCallbacks cameraCallbacks;

    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ProcessCameraProvider cameraProvider;

    // Biến trạng thái và tham số OpenCV cuối cùng được phát hiện
    private MatOfPoint lastDetectedQuadrilateral = null;
    private int lastImageProxyWidth = 0;
    private int lastImageProxyHeight = 0;
    private long lastDetectionTimestamp = 0L;
    private static final long QUAD_PERSISTENCE_TIMEOUT_MS = 1500; // 1.5 giây

    // Biến cho chế độ thẻ ID tự động chụp
    private boolean isIdCardMode = false; // Chế độ thẻ ID
    private long lastIdCardAutoCaptureTime = 0L; // Thời gian tự động chụp thẻ ID cuối cùng
    private static final long ID_CARD_AUTO_CAPTURE_COOLDOWN_MS = 10000; // Thời gian chờ giữa các lần tự động chụp thẻ ID
    private static final double ID_CARD_ASPECT_RATIO_MIN = 1.5;
    private static final double ID_CARD_ASPECT_RATIO_MAX = 1.85;
    private int consecutiveValidFramesIdCard = 0; // Số khung hợp lệ liên tiếp cho thẻ ID
    private static final int REQUIRED_CONSECUTIVE_FRAMES_ID_CARD = 15; // Số khung liên tiếp cần thiết cho thẻ ID

    // Biến cho chế độ quét tài liệu thông thường tự động chụp
    private boolean isAutoCaptureGloballyEnabled = true; // Bật/tắt tự động chụp toàn cục (mặc định BẬT)
    private MatOfPoint previousStableQuadrilateral = null; // Hình tứ giác ổn định trước đó
    private long lastNormalScanAutoCaptureTime = 0L; // <-- ĐÃ THÊM KHAI BÁO BIẾN NÀY
    private static final long NORMAL_SCAN_AUTO_CAPTURE_COOLDOWN_MS = 3000; // Thời gian chờ giữa các lần tự động chụp quét thông thường
    private static final double MIN_STABILITY_AREA_CHANGE_PERCENT = 0.1; // 1% thay đổi diện tích
    private static final double MIN_STABILITY_POINT_CHANGE_PIXELS = 10.0; // 5 pixel thay đổi vị trí điểm
    private int consecutiveStableFramesNormal = 0; // Số khung ổn định liên tiếp cho quét thông thường
    private static final int REQUIRED_CONSECUTIVE_STABLE_FRAMES_NORMAL = 10; // Số khung liên tiếp cần thiết cho quét thông thường
    private static final double NORMAL_SCAN_ASPECT_RATIO_MIN = 0.5; // Tỷ lệ khung hình tối thiểu cho tài liệu thông thường
    private static final double NORMAL_SCAN_ASPECT_RATIO_MAX = 2.0; // Tỷ lệ khung hình tối đa cho tài liệu thông thường


    private int frameCount = 0;
    private static final int PROCESS_FRAME_INTERVAL = 3; // Xử lý mỗi 3 khung hình

    public interface CameraCallbacks {
        void onImageCaptured(Uri imageUri);
        void onCameraError(String message);
        void onAutoCaptureTriggered(); // Vẫn dùng chung callback này
        void updateOverlay(MatOfPoint quadrilateral, int effectiveWidth, int effectiveHeight);
        void clearOverlay();
    }

    public CameraManager(Context context, LifecycleOwner lifecycleOwner, PreviewView previewView,
                         CustomOverlayView customOverlayView, ImageProcessor imageProcessor,
                         ExecutorService cameraExecutor, CameraCallbacks cameraCallbacks) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.customOverlayView = customOverlayView;
        this.imageProcessor = imageProcessor;
        this.cameraExecutor = cameraExecutor;
        this.cameraCallbacks = cameraCallbacks;
    }

    public void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Lỗi khi bắt đầu camera: " + e.getMessage(), e);
                cameraCallbacks.onCameraError("Lỗi khi bắt đầu camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            // Log.d(TAG, "DEBUG_DIM: ImageProxy original dimensions: " + imageProxy.getWidth() + "x" + imageProxy.getHeight() + " Rotation: " + imageProxy.getImageInfo().getRotationDegrees());

            MatOfPoint newlyDetectedQuadrilateral = null;
            Mat processedFrameForDimensions = null;

            try {
                final MatOfPoint finalQuadrilateralForOverlay;

                if (frameCount % PROCESS_FRAME_INTERVAL == 0) {
                    Pair<MatOfPoint, Mat> detectionResult = imageProcessor.processImageFrame(imageProxy);
                    newlyDetectedQuadrilateral = detectionResult.first;
                    processedFrameForDimensions = detectionResult.second;

                    // Log.d(TAG, "Đã xử lý khung hình đầy đủ. Khung: " + frameCount);

                    if (newlyDetectedQuadrilateral != null && processedFrameForDimensions != null) {
                        if (lastDetectedQuadrilateral != null) {
                            lastDetectedQuadrilateral.release();
                        }
                        lastDetectedQuadrilateral = new MatOfPoint(newlyDetectedQuadrilateral.toArray());
                        lastDetectionTimestamp = System.currentTimeMillis();

                        lastImageProxyWidth = processedFrameForDimensions.width();
                        lastImageProxyHeight = processedFrameForDimensions.height();

                        finalQuadrilateralForOverlay = newlyDetectedQuadrilateral;
                        // Log.d(TAG, "DEBUG_DIM: Processed Mat dimensions (from processedFrameForDimensions): " + processedFrameForDimensions.width() + "x" + processedFrameForDimensions.height());
                        // Log.d(TAG, "DEBUG_DIM: Stored lastImageProxyWidth: " + lastImageProxyWidth + " lastImageProxyHeight: " + lastImageProxyHeight);

                        // Xử lý tự động chụp tùy thuộc vào chế độ
                        if (isIdCardMode) {
                            handleIdCardAutoCapture(newlyDetectedQuadrilateral);
                        } else {
                            handleNormalScanAutoCapture(newlyDetectedQuadrilateral, processedFrameForDimensions.width(), processedFrameForDimensions.height());
                        }

                    } else {
                        // Nếu không phát hiện hình tứ giác, kiểm tra thời gian chờ để xóa lớp phủ
                        if (lastDetectedQuadrilateral != null && (System.currentTimeMillis() - lastDetectionTimestamp > QUAD_PERSISTENCE_TIMEOUT_MS)) {
                            Log.d(TAG, "lastDetectedQuadrilateral đã hết thời gian chờ. Giải phóng và đặt là null.");
                            lastDetectedQuadrilateral.release();
                            lastDetectedQuadrilateral = null;
                        }
                        finalQuadrilateralForOverlay = lastDetectedQuadrilateral; // Vẫn hiển thị cái cũ nếu chưa hết thời gian chờ
                        // Reset các bộ đếm khung liên tiếp nếu không có hình tứ giác nào được phát hiện
                        consecutiveValidFramesIdCard = 0;
                        consecutiveStableFramesNormal = 0;
                        if (previousStableQuadrilateral != null) {
                            previousStableQuadrilateral.release();
                            previousStableQuadrilateral = null;
                        }
                    }
                } else {
                    // Nếu bỏ qua xử lý khung hình đầy đủ, vẫn kiểm tra thời gian chờ để xóa lớp phủ
                    if (lastDetectedQuadrilateral != null && (System.currentTimeMillis() - lastDetectionTimestamp > QUAD_PERSISTENCE_TIMEOUT_MS)) {
                        Log.d(TAG, "lastDetectedQuadrilateral đã hết thời gian chờ trên khung bị bỏ qua. Giải phóng và đặt là null.");
                        lastDetectedQuadrilateral.release();
                        lastDetectedQuadrilateral = null;
                    }
                    finalQuadrilateralForOverlay = lastDetectedQuadrilateral;
                    // Log.d(TAG, "Bỏ qua xử lý khung hình đầy đủ. Khung: " + frameCount + ". Hiển thị khung cũ nếu có.");
                }

                // Cập nhật lớp phủ trên UI
                if (finalQuadrilateralForOverlay != null) {
                    cameraCallbacks.updateOverlay(finalQuadrilateralForOverlay, lastImageProxyWidth, lastImageProxyHeight);
                } else {
                    cameraCallbacks.clearOverlay();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in image analysis: " + e.getMessage(), e);
            } finally {
                imageProxy.close();
                if (newlyDetectedQuadrilateral != null) {
                    newlyDetectedQuadrilateral.release();
                }
                if (processedFrameForDimensions != null) {
                    processedFrameForDimensions.release();
                }
                frameCount++;
            }
        });

        imageCapture = new ImageCapture.Builder()
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();
        Log.d(TAG, "ImageCapture target rotation: " + imageCapture.getTargetRotation());

        cameraProvider.unbindAll();

        try {
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis, imageCapture);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi liên kết các trường hợp sử dụng camera: " + e.getMessage(), e);
            cameraCallbacks.onCameraError("Lỗi khi liên kết các trường hợp sử dụng camera: " + e.getMessage());
        }
    }

    /**
     * Xử lý logic tự động chụp cho chế độ quét thẻ ID.
     * @param newlyDetectedQuadrilateral Hình tứ giác mới được phát hiện.
     */
    private void handleIdCardAutoCapture(MatOfPoint newlyDetectedQuadrilateral) {
        if (isIdCardMode && isAutoCaptureGloballyEnabled) { // Kiểm tra cả chế độ và bật/tắt toàn cục
            long currentTime = System.currentTimeMillis();
            Point[] points = newlyDetectedQuadrilateral.toArray();
            if (points.length == 4) {
                MatOfPoint sortedPoints = imageProcessor.sortPoints(new MatOfPoint(points));
                Point[] sortedPts = sortedPoints.toArray();

                double widthTop = Math.sqrt(Math.pow(sortedPts[0].x - sortedPts[1].x, 2) + Math.pow(sortedPts[0].y - sortedPts[1].y, 2));
                double widthBottom = Math.sqrt(Math.pow(sortedPts[3].x - sortedPts[2].x, 2) + Math.pow(sortedPts[3].y - sortedPts[2].y, 2));
                double avgWidth = (widthTop + widthBottom) / 2.0;

                double heightLeft = Math.sqrt(Math.pow(sortedPts[0].x - sortedPts[3].x, 2) + Math.pow(sortedPts[0].y - sortedPts[3].y, 2));
                double heightRight = Math.sqrt(Math.pow(sortedPts[1].x - sortedPts[2].x, 2) + Math.pow(sortedPts[1].y - sortedPts[2].y, 2));
                double avgHeight = (heightLeft + heightRight) / 2.0;

                if (avgHeight > 0) {
                    double aspectRatio = avgWidth / avgHeight;
                    // Log.d(TAG, "Calculated Aspect Ratio (ID Card): " + String.format("%.2f", aspectRatio) + " (Min: " + ID_CARD_ASPECT_RATIO_MIN + ", Max: " + ID_CARD_ASPECT_RATIO_MAX + ")");

                    if (aspectRatio >= ID_CARD_ASPECT_RATIO_MIN && aspectRatio <= ID_CARD_ASPECT_RATIO_MAX) {
                        consecutiveValidFramesIdCard++;
                        // Log.d(TAG, "Valid frame (ID Card). Consecutive: " + consecutiveValidFramesIdCard + "/" + REQUIRED_CONSECUTIVE_FRAMES_ID_CARD);

                        if (consecutiveValidFramesIdCard >= REQUIRED_CONSECUTIVE_FRAMES_ID_CARD) {
                            if (currentTime - lastIdCardAutoCaptureTime > ID_CARD_AUTO_CAPTURE_COOLDOWN_MS) {
                                Log.d(TAG, "Phát hiện thẻ ID hợp lệ liên tục. Đang tự động chụp...");
                                cameraCallbacks.onAutoCaptureTriggered();
                                lastIdCardAutoCaptureTime = currentTime;
                                consecutiveValidFramesIdCard = 0; // Reset sau khi chụp
                            }
                        }
                    } else {
                        consecutiveValidFramesIdCard = 0;
                        // Log.d(TAG, "Aspect ratio (ID Card) out of range. Resetting consecutive frames.");
                    }
                } else {
                    consecutiveValidFramesIdCard = 0;
                    // Log.d(TAG, "AvgHeight (ID Card) is zero. Resetting consecutive frames.");
                }
                sortedPoints.release();
            } else {
                consecutiveValidFramesIdCard = 0;
                // Log.d(TAG, "Not a 4-point quadrilateral (ID Card). Resetting consecutive frames.");
            }
        } else {
            consecutiveValidFramesIdCard = 0; // Reset counter nếu chế độ không phải ID hoặc tự động chụp tắt
        }
    }

    /**
     * Xử lý logic tự động chụp cho chế độ quét tài liệu thông thường.
     * Dựa trên sự ổn định của hình tứ giác và tỷ lệ khung hình.
     * @param currentQuad Hình tứ giác hiện tại được phát hiện.
     * @param frameWidth Chiều rộng của khung hình.
     * @param frameHeight Chiều cao của khung hình.
     */
    private void handleNormalScanAutoCapture(MatOfPoint currentQuad, int frameWidth, int frameHeight) {
        if (!isIdCardMode && isAutoCaptureGloballyEnabled) { // Chỉ chạy khi không ở chế độ ID và tự động chụp bật
            long currentTime = System.currentTimeMillis();

            if (currentQuad == null || currentQuad.empty() || currentQuad.toArray().length != 4) {
                consecutiveStableFramesNormal = 0;
                if (previousStableQuadrilateral != null) {
                    previousStableQuadrilateral.release();
                    previousStableQuadrilateral = null;
                }
                return;
            }

            // Sắp xếp các điểm để so sánh nhất quán
            MatOfPoint sortedCurrentQuad = imageProcessor.sortPoints(new MatOfPoint(currentQuad.toArray()));
            Point[] currentPoints = sortedCurrentQuad.toArray();

            // Tính toán tỷ lệ khung hình của tài liệu
            double widthTop = Math.sqrt(Math.pow(currentPoints[0].x - currentPoints[1].x, 2) + Math.pow(currentPoints[0].y - currentPoints[1].y, 2));
            double widthBottom = Math.sqrt(Math.pow(currentPoints[3].x - currentPoints[2].x, 2) + Math.pow(currentPoints[3].y - currentPoints[2].y, 2));
            double avgWidth = (widthTop + widthBottom) / 2.0;

            double heightLeft = Math.sqrt(Math.pow(currentPoints[0].x - currentPoints[3].x, 2) + Math.pow(currentPoints[0].y - currentPoints[3].y, 2));
            double heightRight = Math.sqrt(Math.pow(currentPoints[1].x - currentPoints[2].x, 2) + Math.pow(currentPoints[1].y - currentPoints[2].y, 2));
            double avgHeight = (heightLeft + heightRight) / 2.0;

            boolean isAspectRatioValid = false;
            if (avgHeight > 0) {
                double aspectRatio = avgWidth / avgHeight;
                isAspectRatioValid = (aspectRatio >= NORMAL_SCAN_ASPECT_RATIO_MIN && aspectRatio <= NORMAL_SCAN_ASPECT_RATIO_MAX);
                // Log.d(TAG, "Calculated Aspect Ratio (Normal Scan): " + String.format("%.2f", aspectRatio) + " (Valid: " + isAspectRatioValid + ")");
            }

            if (!isAspectRatioValid) {
                consecutiveStableFramesNormal = 0;
                if (previousStableQuadrilateral != null) {
                    previousStableQuadrilateral.release();
                    previousStableQuadrilateral = null;
                }
                sortedCurrentQuad.release();
                return;
            }

            if (previousStableQuadrilateral == null) {
                previousStableQuadrilateral = new MatOfPoint(currentPoints); // Tạo bản sao
                consecutiveStableFramesNormal = 1;
                // Log.d(TAG, "Normal Scan: Initializing previousStableQuadrilateral.");
            } else {
                Point[] prevPoints = previousStableQuadrilateral.toArray();
                boolean isStable = true;

                // Kiểm tra sự thay đổi vị trí của các điểm
                for (int i = 0; i < 4; i++) {
                    double dist = Math.sqrt(Math.pow(currentPoints[i].x - prevPoints[i].x, 2) + Math.pow(currentPoints[i].y - prevPoints[i].y, 2));
                    if (dist > MIN_STABILITY_POINT_CHANGE_PIXELS) {
                        isStable = false;
                        break;
                    }
                }

                // Kiểm tra sự thay đổi diện tích
                double prevArea = Imgproc.contourArea(previousStableQuadrilateral);
                double currentArea = Imgproc.contourArea(sortedCurrentQuad);
                if (Math.abs(currentArea - prevArea) / prevArea > MIN_STABILITY_AREA_CHANGE_PERCENT) {
                    isStable = false;
                }

                if (isStable) {
                    consecutiveStableFramesNormal++;
                    // Log.d(TAG, "Normal Scan: Stable frame. Consecutive: " + consecutiveStableFramesNormal + "/" + REQUIRED_CONSECUTIVE_STABLE_FRAMES_NORMAL);
                    if (consecutiveStableFramesNormal >= REQUIRED_CONSECUTIVE_STABLE_FRAMES_NORMAL) {
                        if (currentTime - lastNormalScanAutoCaptureTime > NORMAL_SCAN_AUTO_CAPTURE_COOLDOWN_MS) {
                            Log.d(TAG, "Phát hiện tài liệu ổn định liên tục. Đang tự động chụp (Quét thông thường)...");
                            cameraCallbacks.onAutoCaptureTriggered();
                            lastNormalScanAutoCaptureTime = currentTime;
                            consecutiveStableFramesNormal = 0; // Reset sau khi chụp
                        }
                    }
                } else {
                    consecutiveStableFramesNormal = 0;
                    // Log.d(TAG, "Normal Scan: Not stable. Resetting consecutive frames.");
                }
                // Cập nhật previousStableQuadrilateral cho lần lặp tiếp theo
                previousStableQuadrilateral.release(); // Giải phóng cái cũ
                previousStableQuadrilateral = new MatOfPoint(currentPoints); // Tạo bản sao của cái hiện tại
            }
            sortedCurrentQuad.release();
        } else {
            consecutiveStableFramesNormal = 0; // Reset counter nếu không ở chế độ quét thông thường hoặc tự động chụp tắt
            if (previousStableQuadrilateral != null) {
                previousStableQuadrilateral.release();
                previousStableQuadrilateral = null;
            }
        }
    }


    public void takePhoto(android.widget.ImageView imageView) {
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture chưa được khởi tạo.");
            Toast.makeText(context, context.getString(R.string.camera_not_ready), Toast.LENGTH_SHORT).show();
            return;
        }

        File photoFile = new File(context.getCacheDir(), "captured_image_" + System.currentTimeMillis() + ".jpeg");

        ImageCapture.OutputFileOptions outputFileOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputFileOptions, ContextCompat.getMainExecutor(context), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = outputFileResults.getSavedUri();
                if (savedUri != null) {
                    Toast.makeText(context, context.getString(R.string.photo_captured_saved), Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Ảnh đã chụp và lưu: " + savedUri);

                    Bitmap originalFullBitmap = null;
                    try {
                        originalFullBitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), savedUri);
                        // Xoay ảnh nếu chiều rộng lớn hơn chiều cao (ảnh ngang)
                        if (originalFullBitmap.getWidth() > originalFullBitmap.getHeight()) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(90);
                            originalFullBitmap = Bitmap.createBitmap(originalFullBitmap, 0, 0,
                                    originalFullBitmap.getWidth(), originalFullBitmap.getHeight(), matrix, true);
                        }
                    } catch (IOException e) {
                        Toast.makeText(context, context.getString(R.string.failed_to_load_captured_image), Toast.LENGTH_SHORT).show();
                        Glide.with(context).load(savedUri).into(imageView); // Vẫn hiển thị ảnh gốc nếu lỗi
                        cameraCallbacks.onImageCaptured(savedUri); // Gửi ảnh gốc nếu lỗi
                        return;
                    }

                    if (originalFullBitmap != null) {
                        // Chỉ áp dụng biến đổi phối cảnh nếu có hình tứ giác được phát hiện gần đây
                        if (lastDetectedQuadrilateral != null && !lastDetectedQuadrilateral.empty() &&
                                (System.currentTimeMillis() - lastDetectionTimestamp < QUAD_PERSISTENCE_TIMEOUT_MS)) {

                            int originalDetectionWidth = lastImageProxyWidth;
                            int originalDetectionHeight = lastImageProxyHeight;

                            int capturedBitmapWidth = originalFullBitmap.getWidth();
                            int capturedBitmapHeight = originalFullBitmap.getHeight();

                            double analysisAspectRatio = (double) originalDetectionWidth / originalDetectionHeight;
                            double captureAspectRatio = (double) capturedBitmapWidth / capturedBitmapHeight;

                            double effectiveSrcWidth = originalDetectionWidth;
                            double effectiveSrcHeight = originalDetectionHeight;
                            double offsetX = 0;
                            double offsetY = 0;

                            // Điều chỉnh tỷ lệ để ánh xạ đúng từ khung phân tích sang bitmap đã chụp
                            if (Math.abs(analysisAspectRatio - captureAspectRatio) > 0.01) {
                                if (analysisAspectRatio > captureAspectRatio) { // Khung phân tích rộng hơn ảnh chụp
                                    effectiveSrcHeight = (double) originalDetectionWidth / captureAspectRatio;
                                    offsetY = (originalDetectionHeight - effectiveSrcHeight) / 2.0;
                                    // Log.d(TAG, "Điều chỉnh cho tỷ lệ khung hình phân tích rộng hơn, effectiveSrcHeight mới: " + effectiveSrcHeight + ", offsetY: " + offsetY);
                                } else { // Khung phân tích hẹp hơn ảnh chụp
                                    effectiveSrcWidth = (double) originalDetectionHeight * captureAspectRatio;
                                    offsetX = (originalDetectionWidth - effectiveSrcWidth) / 2.0;
                                    // Log.d(TAG, "Điều chỉnh cho tỷ lệ khung hình phân tích hẹp hơn, effectiveSrcWidth mới: " + effectiveSrcWidth + ", offsetX: " + offsetX);
                                }
                            }

                            double scaleX = (double) capturedBitmapWidth / effectiveSrcWidth;
                            double scaleY = (double) capturedBitmapHeight / effectiveSrcHeight;

                            Point[] detectedPoints = lastDetectedQuadrilateral.toArray();

                            Point[] scaledPoints = new Point[4];
                            for (int i = 0; i < 4; i++) {
                                scaledPoints[i] = new Point(
                                        (detectedPoints[i].x - offsetX) * scaleX,
                                        (detectedPoints[i].y - offsetY) * scaleY
                                );
                            }

                            MatOfPoint sortedScaledPoints = imageProcessor.sortPoints(new MatOfPoint(scaledPoints));
                            Point[] sortedPts = sortedScaledPoints.toArray();

                            double widthTop = Math.sqrt(Math.pow(sortedPts[0].x - sortedPts[1].x, 2) + Math.pow(sortedPts[0].y - sortedPts[1].y, 2));
                            double widthBottom = Math.sqrt(Math.pow(sortedPts[3].x - sortedPts[2].x, 2) + Math.pow(sortedPts[3].y - sortedPts[2].y, 2));
                            int targetWidth = (int) Math.max(widthTop, widthBottom);

                            double heightLeft = Math.sqrt(Math.pow(sortedPts[0].x - sortedPts[3].x, 2) + Math.pow(sortedPts[0].y - sortedPts[3].y, 2));
                            double heightRight = Math.sqrt(Math.pow(sortedPts[1].x - sortedPts[2].x, 2) + Math.pow(sortedPts[1].y - sortedPts[2].y, 2));
                            int targetHeight = (int) Math.max(heightLeft, heightRight);

                            if (targetWidth <= 0 || targetHeight <= 0) {
                                Log.e(TAG, "Kích thước đích không hợp lệ cho biến đổi phối cảnh. Sử dụng ảnh gốc.");
                                Glide.with(context).load(savedUri).into(imageView);
                                if (originalFullBitmap != null) originalFullBitmap.recycle();
                                cameraCallbacks.onImageCaptured(savedUri);
                                return;
                            }

                            MatOfPoint2f srcPoints = new MatOfPoint2f(
                                    sortedPts[0],
                                    sortedPts[1],
                                    sortedPts[2],
                                    sortedPts[3]
                            );

                            MatOfPoint2f dstPoints = new MatOfPoint2f(
                                    new Point(0, 0),
                                    new Point(targetWidth - 1, 0),
                                    new Point(targetWidth - 1, targetHeight - 1),
                                    new Point(0, targetHeight - 1)
                            );

                            Mat originalMat = new Mat(originalFullBitmap.getHeight(), originalFullBitmap.getWidth(), CvType.CV_8UC4);
                            Utils.bitmapToMat(originalFullBitmap, originalMat);

                            Mat transformedMat = new Mat();
                            Mat perspectiveTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
                            Imgproc.warpPerspective(originalMat, transformedMat, perspectiveTransform, new org.opencv.core.Size(targetWidth, targetHeight));

                            Bitmap resultBitmap = Bitmap.createBitmap(transformedMat.cols(), transformedMat.rows(), Bitmap.Config.ARGB_8888);
                            Utils.matToBitmap(transformedMat, resultBitmap);

                            Uri processedUri = saveBitmapToCache(resultBitmap);
                            Glide.with(context).load(processedUri).into(imageView);

                            if (originalFullBitmap != null) originalFullBitmap.recycle();
                            if (resultBitmap != null && resultBitmap != originalFullBitmap)
                                resultBitmap.recycle();
                            originalMat.release();
                            transformedMat.release();
                            perspectiveTransform.release();
                            srcPoints.release();
                            dstPoints.release();
                            sortedScaledPoints.release();

                            cameraCallbacks.onImageCaptured(processedUri);

                        } else {
                            Log.w(TAG, "Không phát hiện khung giới hạn OpenCV hợp lệ hoặc đã hết thời gian chờ. Hiển thị ảnh gốc đầy đủ.");
                            Glide.with(context).load(savedUri).into(imageView);
                            if (originalFullBitmap != null) originalFullBitmap.recycle();
                            cameraCallbacks.onImageCaptured(savedUri);
                        }
                    } else {
                        Log.e(TAG, "originalFullBitmap rỗng sau khi chụp.");
                        Glide.with(context).load(savedUri).into(imageView);
                        cameraCallbacks.onImageCaptured(savedUri);
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.failed_to_save_image), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Không thể lưu ảnh: Uri null");
                }
            }

            @Override
            public void onError(ImageCaptureException exception) {
                Log.e(TAG, "Lỗi khi chụp ảnh: " + exception.getMessage(), exception);
                Toast.makeText(context, "Lỗi khi chụp ảnh: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                cameraCallbacks.onCameraError("Lỗi khi chụp ảnh: " + exception.getMessage());
            }
        });
    }

    private Uri saveBitmapToCache(Bitmap bitmap) {
        String fileName = "temp_processed_image_" + System.currentTimeMillis() + ".jpeg";
        File cachePath = new File(context.getCacheDir(), "processed_images");
        cachePath.mkdirs();
        File file = new File(cachePath, fileName);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
            return Uri.fromFile(file);
        } catch (IOException e) {
            Log.e(TAG, "Lỗi khi lưu bitmap đã xử lý vào cache: " + e.getMessage(), e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Lỗi khi đóng FileOutputStream: " + e.getMessage(), e);
                }
            }
        }
    }

    public void unbindCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            Log.d(TAG, "Camera unbound.");
        }
        if (lastDetectedQuadrilateral != null) {
            lastDetectedQuadrilateral.release();
            lastDetectedQuadrilateral = null;
        }
        if (previousStableQuadrilateral != null) { // Giải phóng cả previousStableQuadrilateral
            previousStableQuadrilateral.release();
            previousStableQuadrilateral = null;
        }
    }

    /**
     * Đặt chế độ camera (quét tài liệu thông thường hoặc thẻ ID).
     * @param idCardMode True nếu là chế độ thẻ ID, false nếu là quét thông thường.
     */
    public void setIdCardMode(boolean idCardMode) {
        isIdCardMode = idCardMode;
        consecutiveValidFramesIdCard = 0; // Reset counter khi chế độ thay đổi
        consecutiveStableFramesNormal = 0; // Reset counter khi chế độ thay đổi
        if (previousStableQuadrilateral != null) {
            previousStableQuadrilateral.release();
            previousStableQuadrilateral = null;
        }
        Log.d(TAG, "isIdCardMode set to: " + isIdCardMode);
    }

    /**
     * Bật hoặc tắt chức năng tự động chụp toàn cục.
     * @param enabled True để bật, false để tắt.
     */
    public void setAutoCaptureGloballyEnabled(boolean enabled) {
        isAutoCaptureGloballyEnabled = enabled;
        consecutiveValidFramesIdCard = 0; // Reset counter khi trạng thái thay đổi
        consecutiveStableFramesNormal = 0; // Reset counter khi trạng thái thay đổi
        if (previousStableQuadrilateral != null) {
            previousStableQuadrilateral.release();
            previousStableQuadrilateral = null;
        }
        Log.d(TAG, "Auto capture globally enabled set to: " + isAutoCaptureGloballyEnabled);
    }
}
