package com.example.camerascanner.activitycamera.CameraActivity;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.camerascanner.R;

public class GalleryHandler {

    private final AppCompatActivity activity;
    private ActivityResultLauncher<String> galleryLauncher;
    private final GalleryCallbacks callbacks;

    public interface GalleryCallbacks {
        void onImageSelected(Uri imageUri);
        void onImageSelectionFailed(String message);
    }

    public GalleryHandler(AppCompatActivity activity, GalleryCallbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
        initLauncher();
    }

    private void initLauncher() {
        galleryLauncher = activity.registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                callbacks.onImageSelected(uri);
            } else {
                Toast.makeText(activity, activity.getString(R.string.failed_to_get_image_from_gallery), Toast.LENGTH_SHORT).show();
                callbacks.onImageSelectionFailed("URI rỗng sau khi xử lý kết quả thư viện.");
            }
        });
    }

    public void openGallery() {
        galleryLauncher.launch("image/*");
    }
}