package com.example.camerascanner.activitycamera.CameraActivity;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.camerascanner.R;
import com.example.camerascanner.activitycamera.ImagePreviewActivity;
import com.example.camerascanner.activitycrop.CropActivity;

public class ActivityLauncher {

    private static final String TAG = "ActivityLauncher";

    private final AppCompatActivity activity;
    private ActivityResultLauncher<Intent> imagePreviewLauncher;
    private final ActivityLauncherCallbacks callbacks;

    public interface ActivityLauncherCallbacks {
        void onImagePreviewResult(Uri imageUri);
        void onImagePreviewCanceled();
        void onImagePreviewFailed(String message);
        void onLauncherClosed(); // Callback khi ActivityLauncher đã xử lý xong và có thể quay lại trạng thái trước đó
    }

    public ActivityLauncher(AppCompatActivity activity, ActivityLauncherCallbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
        initLaunchers();
    }

    private void initLaunchers() {
        imagePreviewLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == AppCompatActivity.RESULT_OK) {
                if (result.getData() != null) {
                    Uri confirmedImageUri = result.getData().getData();
                    if (confirmedImageUri != null) {
                        Log.d(TAG, "URI ảnh đã xác nhận từ ImagePreviewActivity: " + confirmedImageUri);
                        callbacks.onImagePreviewResult(confirmedImageUri);
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.no_cropped_image_received), Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "URI đã xác nhận rỗng từ ImagePreviewActivity.");
                        callbacks.onImagePreviewFailed("URI đã xác nhận rỗng từ ImagePreviewActivity.");
                    }
                }
            } else if (result.getResultCode() == AppCompatActivity.RESULT_CANCELED) {
                Toast.makeText(activity, activity.getString(R.string.crop_canceled), Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Thao tác ImagePreviewActivity bị hủy.");
                callbacks.onImagePreviewCanceled();
            } else {
                Toast.makeText(activity, activity.getString(R.string.crop_failed), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Thao tác ImagePreviewActivity thất bại với mã kết quả: " + result.getResultCode());
                callbacks.onImagePreviewFailed("Thao tác ImagePreviewActivity thất bại với mã kết quả: " + result.getResultCode());
            }
            callbacks.onLauncherClosed(); // Thông báo rằng launcher đã hoàn thành công việc
        });
    }

    public void launchImagePreviewActivity(Uri imageUri) {
        Intent intent = new Intent(activity, ImagePreviewActivity.class);
        intent.putExtra("imageUri", imageUri.toString());
        imagePreviewLauncher.launch(intent);
    }

    public void launchCropActivity(Uri imageUri) {
        Intent cropIntent = new Intent(activity, CropActivity.class);
        cropIntent.putExtra("imageUri", imageUri.toString());
        activity.startActivity(cropIntent);
    }
}