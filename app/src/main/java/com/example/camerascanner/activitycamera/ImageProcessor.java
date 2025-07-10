package com.example.camerascanner.activitycamera;

import android.graphics.Bitmap; // Thêm import cho Bitmap
import android.content.Context; // Thêm import cho Context
import android.net.Uri; // Thêm import cho Uri
import android.util.Log;
import android.util.Pair;

import androidx.camera.core.ImageProxy;
import androidx.annotation.NonNull; // Thêm import cho NonNull nếu chưa có

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.android.Utils; // Thêm import cho Utils để chuyển đổi Bitmap-Mat

import java.io.File; // Thêm import cho File
import java.io.FileOutputStream; // Thêm import cho FileOutputStream
import java.io.IOException; // Thêm import cho IOException
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImageProcessor {

    private static final String TAG = "ImageProcessor";

    // --- Các hằng số và biến OpenCV ---
    private static final double CANNY_THRESHOLD1 = 40;
    private static final double CANNY_THRESHOLD2 = 120;
    private double dynamicCannyThreshold1 = CANNY_THRESHOLD1;
    private double dynamicCannyThreshold2 = CANNY_THRESHOLD2;
    private static final double APPROX_POLY_DP_EPSILON_FACTOR = 0.05;
    private static final double MIN_COSINE_ANGLE = 0.5;
    private static final double MIN_AREA_PERCENTAGE = 0.02;
    private static final double MAX_AREA_PERCENTAGE = 0.90;


    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    public Pair<MatOfPoint, Mat> processImageFrame(ImageProxy imageProxy) {
        Mat gray = null;
        Mat edges = null;
        Mat hierarchy = null;
        List<MatOfPoint> contours = null;
        MatOfPoint bestQuadrilateral = null;
        Mat matForDimensionStorage = null;

        try {
            ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int yRowStride = yPlane.getRowStride();
            int yPixelStride = yPlane.getPixelStride();

            int originalFrameWidth = imageProxy.getWidth();
            int originalFrameHeight = imageProxy.getHeight();

            adjustOpenCVParametersForResolution(originalFrameWidth, originalFrameHeight);

            gray = new Mat(originalFrameHeight, originalFrameWidth, CvType.CV_8UC1);

            byte[] data = new byte[originalFrameWidth * originalFrameHeight];
            int bufferOffset = 0;

            for (int row = 0; row < originalFrameHeight; ++row) {
                int bytesToReadInRow = originalFrameWidth;
                if (yBuffer.remaining() < bytesToReadInRow) {
                    Log.e(TAG, "BufferUnderflow: Not enough bytes for row " + row + ". Remaining: " + yBuffer.remaining() + ", Needed: " + bytesToReadInRow + ". Skipping frame.");
                    return new Pair<>(null, null);
                }
                yBuffer.get(data, bufferOffset, bytesToReadInRow);
                bufferOffset += bytesToReadInRow;

                int paddingBytes = yRowStride - originalFrameWidth;
                if (paddingBytes > 0) {
                    if (yBuffer.remaining() >= paddingBytes) {
                        yBuffer.position(yBuffer.position() + paddingBytes);
                    } else {
                        Log.w(TAG, "Not enough buffer remaining to skip padding for row " + row + ". Remaining: " + yBuffer.remaining() + ", Expected padding: " + paddingBytes + ". Further rows might be misaligned.");
                        break;
                    }
                }
            }
            gray.put(0, 0, data);

            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            Log.d(TAG, "ImageAnalysis rotationDegrees: " + rotationDegrees);
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                Mat rotatedGray = new Mat();
                Core.transpose(gray, rotatedGray);
                Core.flip(rotatedGray, rotatedGray, (rotationDegrees == 90) ? 1 : 0);
                gray.release();
                gray = rotatedGray;
            }

            Imgproc.medianBlur(gray, gray, 5);
            CLAHE clahe = Imgproc.createCLAHE(2.0, new org.opencv.core.Size(8, 8));
            clahe.apply(gray, gray);
            edges = new Mat();
            Imgproc.Canny(gray, edges, dynamicCannyThreshold1, dynamicCannyThreshold2);
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(3, 3));
            Imgproc.dilate(edges, edges, kernel);
            kernel.release();
            contours = new ArrayList<>();
            hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            bestQuadrilateral = findBestQuadrilateral(contours, gray.width(), gray.height());

            if (bestQuadrilateral != null && !bestQuadrilateral.empty()) {
                matForDimensionStorage = gray.clone();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing image frame: " + e.getMessage(), e);
            return new Pair<>(null, null);
        } finally {
            if (edges != null) edges.release();
            if (hierarchy != null) hierarchy.release();
            if (contours != null) {
                for (MatOfPoint m : contours) {
                    m.release();
                }
            }
            // Không giải phóng 'gray' ở đây nếu nó được gán cho 'matForDimensionStorage'
            // Nó sẽ được giải phóng cùng với matForDimensionStorage khi không còn cần thiết.
            // Nếu matForDimensionStorage là null, gray vẫn cần được giải phóng.
            if (matForDimensionStorage == null && gray != null) {
                gray.release();
            }
        }
        return new Pair<>(bestQuadrilateral, matForDimensionStorage);
    }

    private void adjustOpenCVParametersForResolution(int frameWidth, int frameHeight) {
        if (frameWidth <= 480) {
            dynamicCannyThreshold1 = 20;
            dynamicCannyThreshold2 = 60;
        } else if (frameWidth <= 640) {
            dynamicCannyThreshold1 = 30;
            dynamicCannyThreshold2 = 90;
        } else if (frameWidth <= 1280) {
            dynamicCannyThreshold1 = 40;
            dynamicCannyThreshold2 = 120;
        } else {
            dynamicCannyThreshold1 = 50;
            dynamicCannyThreshold2 = 150;
        }
        Log.d(TAG, "Canny thresholds adjusted to: " + dynamicCannyThreshold1 + ", " + dynamicCannyThreshold2 + " for resolution " + frameWidth + "x" + frameHeight);
    }

    private MatOfPoint findBestQuadrilateral(List<MatOfPoint> contours, int imageWidth, int imageHeight) {
        MatOfPoint bestQuadrilateral = null;
        double maxArea = 0;
        double totalArea = imageWidth * imageHeight;
        double minAllowedArea = totalArea * MIN_AREA_PERCENTAGE;
        double maxAllowedArea = totalArea * MAX_AREA_PERCENTAGE;

        for (MatOfPoint contour : contours) {
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            double perimeter = Imgproc.arcLength(contour2f, true);
            MatOfPoint2f approxCurve = new MatOfPoint2f();
            Imgproc.approxPolyDP(contour2f, approxCurve, APPROX_POLY_DP_EPSILON_FACTOR * perimeter, true);

            long numVertices = approxCurve.total();
            double currentArea = Imgproc.contourArea(approxCurve);

            if (numVertices == 4 &&
                    currentArea > minAllowedArea &&
                    currentArea < maxAllowedArea) {

                if (Imgproc.isContourConvex(new MatOfPoint(approxCurve.toArray()))) {
                    double maxCosine = 0;
                    Point[] points = approxCurve.toArray();

                    for (int i = 0; i < 4; i++) {
                        Point p1 = points[i];
                        Point p2 = points[(i + 1) % 4];
                        Point p3 = points[(i + 2) % 4];

                        double cosineAngle = Math.abs(angle(p1, p2, p3));
                        maxCosine = Math.max(maxCosine, cosineAngle);
                    }

                    if (maxCosine < MIN_COSINE_ANGLE) {
                        if (currentArea > maxArea) {
                            maxArea = currentArea;
                            bestQuadrilateral = new MatOfPoint(points);
                        }
                    }
                }
            }
            contour2f.release();
            approxCurve.release();
        }
        return bestQuadrilateral;
    }

    private double angle(Point p1, Point p2, Point p3) {
        double dx1 = p1.x - p2.x;
        double dy1 = p1.y - p2.y;
        double dx2 = p3.x - p2.x;
        double dy2 = p3.y - p2.y;
        return (dx1 * dx2 + dy1 * dy2) / (Math.sqrt(dx1 * dx1 + dy1 * dy1) * Math.sqrt(dx2 * dx2 + dy2 * dy2) + 1e-10);
    }

    public MatOfPoint sortPoints(MatOfPoint pointsMat) {
        Point[] pts = pointsMat.toArray();
        Point[] rect = new Point[4];

        Arrays.sort(pts, (p1, p2) -> Double.compare(p1.y, p2.y));

        Point[] topPoints = Arrays.copyOfRange(pts, 0, 2);
        Point[] bottomPoints = Arrays.copyOfRange(pts, 2, 4);

        Arrays.sort(topPoints, (p1, p2) -> Double.compare(p1.x, p2.x));
        rect[0] = topPoints[0];
        rect[1] = topPoints[1];

        Arrays.sort(bottomPoints, (p1, p2) -> Double.compare(p1.x, p2.x));
        rect[3] = bottomPoints[0]; // bottom-left
        rect[2] = bottomPoints[1]; // bottom-right

        return new MatOfPoint(rect);
    }

    /**
     * Ghép hai Bitmap thành một, xếp chồng theo chiều dọc.
     * Các ảnh sẽ được resize để có cùng chiều rộng trước khi ghép.
     * @param frontImage Bitmap của ảnh mặt trước.
     * @param backImage Bitmap của ảnh mặt sau.
     * @param context Context của ứng dụng để lưu file.
     * @return Uri của ảnh đã được ghép, hoặc null nếu có lỗi.
     */
    public Uri stitchImages(@NonNull Bitmap frontImage, @NonNull Bitmap backImage, @NonNull Context context) {
        if (frontImage == null || backImage == null) {
            Log.e(TAG, "Một hoặc cả hai Bitmap đầu vào rỗng cho chức năng ghép ảnh.");
            return null;
        }

        Mat matFront = new Mat(frontImage.getHeight(), frontImage.getWidth(), CvType.CV_8UC4);
        Utils.bitmapToMat(frontImage, matFront);

        Mat matBack = new Mat(backImage.getHeight(), backImage.getWidth(), CvType.CV_8UC4);
        Utils.bitmapToMat(backImage, matBack);

        // Đảm bảo cả hai ảnh có cùng chiều rộng để ghép dọc dễ dàng.
        int targetWidth = Math.max(matFront.width(), matBack.width());

        Mat resizedFront = new Mat();
        Mat resizedBack = new Mat();

        // Resize ảnh để chúng có cùng chiều rộng, giữ nguyên tỷ lệ khung hình
        Imgproc.resize(matFront, resizedFront, new org.opencv.core.Size(targetWidth, matFront.height() * targetWidth / matFront.width()));
        Imgproc.resize(matBack, resizedBack, new org.opencv.core.Size(targetWidth, matBack.height() * targetWidth / matBack.width()));

        // Tạo một Mat mới có đủ không gian cho cả hai ảnh xếp dọc
        Mat stitchedMat = new Mat(resizedFront.rows() + resizedBack.rows(), targetWidth, CvType.CV_8UC4);

        // Sao chép ảnh mặt trước vào phần trên của stitchedMat
        resizedFront.copyTo(stitchedMat.rowRange(0, resizedFront.rows()));
        // Sao chép ảnh mặt sau vào phần dưới của stitchedMat
        resizedBack.copyTo(stitchedMat.rowRange(resizedFront.rows(), stitchedMat.rows()));

        Bitmap stitchedBitmap = Bitmap.createBitmap(stitchedMat.cols(), stitchedMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(stitchedMat, stitchedBitmap);

        // Giải phóng các đối tượng Mat không còn cần thiết
        matFront.release();
        matBack.release();
        resizedFront.release();
        resizedBack.release();
        stitchedMat.release();

        // Lưu Bitmap đã ghép vào bộ nhớ cache và trả về Uri
        return saveProcessedBitmapToCache(stitchedBitmap, context);
    }

    /**
     * Lưu Bitmap đã xử lý vào bộ nhớ cache của ứng dụng.
     * @param bitmap Bitmap cần lưu.
     * @param context Context của ứng dụng.
     * @return Uri của file đã lưu, hoặc null nếu có lỗi.
     */
    private Uri saveProcessedBitmapToCache(@NonNull Bitmap bitmap, @NonNull Context context) {
        String fileName = "stitched_id_image_" + System.currentTimeMillis() + ".jpeg";
        File cachePath = new File(context.getCacheDir(), "stitched_images");
        cachePath.mkdirs(); // Tạo thư mục nếu nó chưa tồn tại

        File file = new File(cachePath, fileName);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos); // Nén ảnh JPEG với chất lượng 90%
            fos.flush();
            Log.d(TAG, "Ảnh ghép đã lưu vào: " + file.getAbsolutePath());
            return Uri.fromFile(file);
        } catch (IOException e) {
            Log.e(TAG, "Lỗi khi lưu bitmap đã ghép vào cache: " + e.getMessage(), e);
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
}
