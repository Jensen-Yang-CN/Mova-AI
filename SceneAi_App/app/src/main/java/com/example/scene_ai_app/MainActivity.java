package com.example.scene_ai_app;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_IMAGE = 100;
    private static final int REQ_CAMERA = 101;

    // ✅ 模拟器访问本机 FastAPI
    private static final String BASE_URL = "http://10.0.2.2:8000/analyze_image";

    // 真机调试请改成：
    // private static final String BASE_URL = "http://你电脑IP:8000/analyze_image";

    Button btnCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnCamera = findViewById(R.id.btnCamera);

        // ✅ 点按钮
        btnCamera.setOnClickListener(v -> checkCameraPermission());
    }

    // ✅ 第1步：检查相机权限
    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA
            );

        } else {
            // 已经有权限 → 打开相机
            openCamera();
        }
    }

    // ✅ 第2步：打开系统相机
    private void openCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    // ✅ 第3步：拍照后系统回调
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK && data != null) {
            Bundle extras = data.getExtras();
            Bitmap bitmap = (Bitmap) extras.get("data");

            // ✅ 拿到图片后上传
            uploadImage(bitmap);
        }
    }

    // ✅ 第4步：上传图片到云端
    private void uploadImage(Bitmap bitmap) {

        OkHttpClient client = new OkHttpClient();

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        byte[] bytes = stream.toByteArray();

        RequestBody imageBody = RequestBody.create(
                bytes,
                MediaType.parse("image/jpeg")
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "photo.jpg", imageBody)
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL)
                .post(requestBody)
                .build();

        // ✅ 异步请求
        client.newCall(request).enqueue(new Callback() {

            // 请求失败
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("UPLOAD", "失败: " + e.getMessage());
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "上传失败", Toast.LENGTH_SHORT).show());
            }

            // 请求成功
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {

                String result = response.body().string();
                Log.i("UPLOAD", "成功: " + result);

                try {
                    JSONObject json = new JSONObject(result);

                    String dish = json.optString("dish");
                    String tips = json.optString("tips");

                    JSONArray ingredients = json.getJSONArray("ingredients");
                    JSONArray steps = json.getJSONArray("steps");

                    StringBuilder content = new StringBuilder();

                    content.append("🍜 ").append(dish).append("\n\n");

                    content.append("食材：\n");
                    for (int i = 0; i < ingredients.length(); i++) {
                        content.append("- ").append(ingredients.getString(i)).append("\n");
                    }

                    content.append("\n步骤：\n");
                    for (int i = 0; i < steps.length(); i++) {
                        content.append(i + 1).append(". ")
                                .append(steps.getString(i)).append("\n");
                    }

                    content.append("\n小贴士：\n").append(tips);

                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    content.toString(),
                                    Toast.LENGTH_LONG).show());

                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "解析出错：" + result,
                                    Toast.LENGTH_LONG).show());
                }
            }

        });
    }

    // ✅ 第5步：权限结果回调
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this,
                        "未授权相机，无法使用",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
