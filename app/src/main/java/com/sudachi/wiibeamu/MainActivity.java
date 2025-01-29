package com.sudachi.wiibeamu;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.widget.PopupMenu;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;

public class MainActivity extends AppCompatActivity {
    private static final int WII_PORT = 4299;
    private static final int BUFFER_SIZE = 8192;
    private static final String PREFS_NAME = "BeamUPrefs";
    private static final String LAST_IP_KEY = "lastUsedIP";
    private static final String THEME_PREF_KEY = "theme_mode";
    
    private TextView statusText;
    private EditText ipAddressField;
    private Button selectFileButton;
    private Button transmitButton;
    private Uri selectedFile;
    private SharedPreferences preferences;
    private ProgressDialog progressDialog;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ImageButton themeToggle;
    
    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleFileSelection(result.getData().getData());
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applyTheme();
        setContentView(R.layout.activity_main);
        initializeViews();
        setupPreferences();
        verifyPermissions();
        setupClickListeners();
    }

    private void initializeViews() {
        statusText = findViewById(R.id.fileNameText);
        ipAddressField = findViewById(R.id.wiiIpInput);
        selectFileButton = findViewById(R.id.browseButton);
        transmitButton = findViewById(R.id.sendButton);
        transmitButton.setEnabled(false);
        
        progressDialog = new ProgressDialog(this);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setCancelable(false);
        themeToggle = findViewById(R.id.themeToggle);
    }

    private void setupPreferences() {
        String savedIP = preferences.getString(LAST_IP_KEY, "");
        ipAddressField.setText(savedIP);
    }

    private void verifyPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
        }
    }

    private void setupClickListeners() {
        selectFileButton.setOnClickListener(v -> launchFilePicker());
        transmitButton.setOnClickListener(v -> initiateFileTransfer());
        themeToggle.setOnClickListener(v -> showThemeMenu());
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePicker.launch(intent);
    }

    private void handleFileSelection(Uri uri) {
        selectedFile = uri;
        String fileName = getFileName(uri);
        statusText.setText(fileName);
        transmitButton.setEnabled(true);
    }

    private String getFileName(Uri uri) {
        String result = uri.getLastPathSegment();
        int cut = result.lastIndexOf('/');
        if (cut != -1) {
            result = result.substring(cut + 1);
        }
        return result;
    }

    private void initiateFileTransfer() {
        String ipAddress = ipAddressField.getText().toString().trim();
        if (ipAddress.isEmpty()) {
            showMessage("Please enter Wii IP address");
            return;
        }

        preferences.edit().putString(LAST_IP_KEY, ipAddress).apply();
        startFileTransfer(ipAddress);
    }

    private void startFileTransfer(String ipAddress) {
        progressDialog.setMessage("Preparing transfer...");
        progressDialog.setProgress(0);
        progressDialog.show();

        executorService.execute(() -> {
            try {
                byte[] compressedData = compressFileData();
                sendToWii(ipAddress, compressedData);
                showTransferComplete();
            } catch (Exception e) {
                handleTransferError(e);
            }
        });
    }

    private byte[] compressFileData() throws IOException {
        ByteArrayOutputStream compressedStream = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        byte[] buffer = new byte[BUFFER_SIZE];
        
        try (InputStream inputStream = getContentResolver().openInputStream(selectedFile)) {
            int totalBytes = inputStream.available();
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                deflater.setInput(buffer, 0, bytesRead);
                
                byte[] compressedBuffer = new byte[BUFFER_SIZE];
                while (!deflater.needsInput()) {
                    int compressedBytes = deflater.deflate(compressedBuffer);
                    compressedStream.write(compressedBuffer, 0, compressedBytes);
                }
                
                updateProgress("Compressing...", (int) (compressedStream.size() * 100L / totalBytes));
            }
            
            deflater.finish();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                compressedStream.write(buffer, 0, count);
            }
    }

        return compressedStream.toByteArray();
    }

    private void sendToWii(String ipAddress, byte[] compressedData) throws IOException {
        try (Socket socket = new Socket(ipAddress, WII_PORT);
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
        
            String fileName = getFileName(selectedFile);
            writeHeader(output, fileName, compressedData.length);

            int chunkSize = BUFFER_SIZE;
            int totalChunks = (compressedData.length + chunkSize - 1) / chunkSize;
        
        for (int i = 0; i < totalChunks; i++) {
                int start = i * chunkSize;
                int length = Math.min(chunkSize, compressedData.length - start);
                output.write(compressedData, start, length);
            output.flush();
            
            updateProgress("Sending file...", (i + 1) * 100 / totalChunks);
        }

            output.writeBytes(fileName + '\0');
        output.flush();
        }
    }

    private void writeHeader(DataOutputStream output, String fileName, int compressedLength) throws IOException {
        output.writeBytes("1027");  // magic number
        output.writeByte(0);  // max version
        output.writeByte(5);  // min version
        output.writeShort(fileName.length() + 1);
        output.writeInt(compressedLength);
        
        try (InputStream inputStream = getContentResolver().openInputStream(selectedFile)) {
            output.writeInt(inputStream.available());  // original size
            }
    }

    private void updateProgress(String message, int progress) {
        mainHandler.post(() -> {
            progressDialog.setMessage(message);
            progressDialog.setProgress(progress);
        });
    }

    private void showTransferComplete() {
        mainHandler.post(() -> {
            progressDialog.dismiss();
            showMessage("Transfer completed successfully!");
        });
    }

    private void handleTransferError(Exception e) {
        mainHandler.post(() -> {
            progressDialog.dismiss();
            showMessage("Transfer failed: " + e.getMessage());
        });
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showThemeMenu() {
        PopupMenu popup = new PopupMenu(this, themeToggle);
        popup.getMenuInflater().inflate(R.menu.theme_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_light_mode) {
                setThemeMode(AppCompatDelegate.MODE_NIGHT_NO);
                return true;
            } else if (itemId == R.id.menu_dark_mode) {
                setThemeMode(AppCompatDelegate.MODE_NIGHT_YES);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void setThemeMode(int mode) {
        preferences.edit().putInt(THEME_PREF_KEY, mode).apply();
            AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void applyTheme() {
        int savedTheme = preferences.getInt(THEME_PREF_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(savedTheme);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}