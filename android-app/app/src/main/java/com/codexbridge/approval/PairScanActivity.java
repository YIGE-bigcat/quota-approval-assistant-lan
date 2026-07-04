package com.codexbridge.approval;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

public class PairScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final int REQUEST_CAMERA = 410;

    private Camera camera;
    private boolean decoded;
    private TextView statusText;
    private SurfaceHolder surfaceHolder;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        buildLayout();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "需要相机权限才能扫码配对", Toast.LENGTH_LONG).show();
            finish();
        } else if (requestCode == REQUEST_CAMERA && surfaceHolder != null) {
            startCamera(surfaceHolder);
        }
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(this);
        title.setText("扫描手表配对二维码");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.TOP
        );
        root.addView(title, titleParams);

        TextView box = new TextView(this);
        box.setText("");
        box.setBackground(makeBorder());
        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER);
        root.addView(box, boxParams);

        statusText = new TextView(this);
        statusText.setText("请把手表上的二维码放入框内");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.BOTTOM
        );
        root.addView(statusText, statusParams);

        setContentView(root);
    }

    private android.graphics.drawable.GradientDrawable makeBorder() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(0x11000000);
        drawable.setStroke(dp(3), Color.rgb(98, 242, 167));
        drawable.setCornerRadius(dp(20));
        return drawable;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        startCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceHolder = null;
        stopCamera();
    }

    private void startCamera(SurfaceHolder holder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            camera = Camera.open();
            camera.setDisplayOrientation(90);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size best = choosePreviewSize(parameters);
            if (best != null) parameters.setPreviewSize(best.width, best.height);
            camera.setParameters(parameters);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            statusText.setText("正在识别手表二维码");
        } catch (Exception error) {
            statusText.setText("相机启动失败：" + error.getMessage());
        }
    }

    private Camera.Size choosePreviewSize(Camera.Parameters parameters) {
        Camera.Size best = null;
        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (best == null || Math.abs(size.width * size.height - 1280 * 720) < Math.abs(best.width * best.height - 1280 * 720)) {
                best = size;
            }
        }
        return best;
    }

    private void stopCamera() {
        if (camera == null) return;
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
        } catch (Exception ignored) {
        }
        camera = null;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (decoded || camera == null) return;
        Camera.Size size = camera.getParameters().getPreviewSize();
        String text = decode(data, size.width, size.height);
        if (text == null) {
            byte[] rotated = rotateClockwise(data, size.width, size.height);
            text = decode(rotated, size.height, size.width);
        }
        if (text != null) {
            decoded = true;
            handlePairText(text);
        }
    }

    private String decode(byte[] data, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(bitmap);
            return result.getText();
        } catch (NotFoundException error) {
            return null;
        } catch (Exception error) {
            return null;
        }
    }

    private byte[] rotateClockwise(byte[] data, int width, int height) {
        byte[] rotated = new byte[data.length];
        int index = 0;
        for (int x = 0; x < width; x++) {
            for (int y = height - 1; y >= 0; y--) {
                rotated[index++] = data[y * width + x];
            }
        }
        return rotated;
    }

    private void handlePairText(String text) {
        runOnUiThread(() -> {
            PairPayload payload = PairPayload.parse(text);
            if (payload == null || payload.token.isEmpty()) {
                decoded = false;
                statusText.setText("不是审批助手配对二维码");
                return;
            }
            Prefs.saveWatchPair(this, payload.token, payload.label);
            Prefs.setMonitorEnabled(this, true);
            ApprovalMonitorService.start(this);
            Toast.makeText(this, "手表已配对，手机中继已开启", Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class PairPayload {
        final String token;
        final String label;

        PairPayload(String token, String label) {
            this.token = token == null ? "" : token;
            this.label = label == null ? "" : label;
        }

        static PairPayload parse(String text) {
            try {
                Uri uri = Uri.parse(text);
                if (!"codex-bridge".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return null;
                return new PairPayload(uri.getQueryParameter("token"), uri.getQueryParameter("label"));
            } catch (Exception error) {
                return null;
            }
        }
    }
}
