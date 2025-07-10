package com.example.camerascanner.activitycamera.CameraActivity;

import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.camera.view.PreviewView;

import com.example.camerascanner.activitycamera.CustomOverlayView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class UiStateManager {

    private final PreviewView previewView;
    private final CustomOverlayView customOverlayView;
    private final ImageView imageView;
    private final FloatingActionButton btnTakePhoto;
    private final ImageButton btnSelectImage;

    public UiStateManager(PreviewView previewView, CustomOverlayView customOverlayView, ImageView imageView,
                          FloatingActionButton btnTakePhoto, ImageButton btnSelectImage) {
        this.previewView = previewView;
        this.customOverlayView = customOverlayView;
        this.imageView = imageView;
        this.btnTakePhoto = btnTakePhoto;
        this.btnSelectImage = btnSelectImage;
    }

    public void showCameraPreviewUi() {
        previewView.setVisibility(View.VISIBLE);
        customOverlayView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
        btnTakePhoto.setVisibility(View.VISIBLE);
        btnSelectImage.setVisibility(View.VISIBLE);
    }

    public void showImageViewUi() {
        previewView.setVisibility(View.GONE);
        customOverlayView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        btnTakePhoto.setVisibility(View.GONE);
        btnSelectImage.setVisibility(View.GONE);
    }
}