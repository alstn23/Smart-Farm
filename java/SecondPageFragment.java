package com.example.smartfarm;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AppCompatActivity;

public class SecondPageFragment extends Fragment {

    private WebView webView;

    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    private ValueCallback<Uri[]> filePathCallback;

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private PermissionRequest mPermissionRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_page_2, container, false);
        webView = view.findViewById(R.id.webview);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebChromeClient(new MyWebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/searchplant.html");

        return view;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (mPermissionRequest == null) {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                return;
            }

            boolean granted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }

            if (granted) {
                mPermissionRequest.grant(mPermissionRequest.getResources());
            } else {
                mPermissionRequest.deny();
                Toast.makeText(getContext(), "카메라 권한이 거부되어 기능을 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
            }
            mPermissionRequest = null;
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;

            Uri[] results = null;

            if (resultCode == AppCompatActivity.RESULT_OK) {
                if (data != null) {
                    if (data.getDataString() != null) {
                        results = new Uri[]{Uri.parse(data.getDataString())};
                    } else if (data.getClipData() != null) {
                        final int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    public boolean canGoBack() {
        if (webView != null) {
            return webView.canGoBack();
        }
        return false;
    }

    public void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    public class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                request.grant(request.getResources());
                return;
            }

            mPermissionRequest = request;
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, CAMERA_PERMISSION_REQUEST_CODE);
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            super.onPermissionRequestCanceled(request);
            Toast.makeText(getContext(), "카메라 권한 요청이 거부되었습니다.", Toast.LENGTH_SHORT).show();
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (SecondPageFragment.this.filePathCallback != null) {
                SecondPageFragment.this.filePathCallback.onReceiveValue(null);
            }
            SecondPageFragment.this.filePathCallback = filePathCallback;

            Intent intent = fileChooserParams.createIntent();
            try {
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                SecondPageFragment.this.filePathCallback = null;
                return false;
            }
            return true;
        }
    }
}