package com.sudachi.wiibeamu;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.AsyncTask;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.zip.DeflaterOutputStream;

public class MainActivity extends AppCompatActivity {
    private static final int PORT = 4299;
    private TextView fileNameText;
    private EditText wiiIpInput;
    private Button sendButton;
    private Button browseButton;
    private Uri selectedFileUri;
    private SharedPreferences prefs;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    String fileName = selectedFileUri.getLastPathSegment();
                    fileNameText.setText(fileName);
                    sendButton.setEnabled(true);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("BeamU", MODE_PRIVATE);

        fileNameText = findViewById(R.id.fileNameText);
        wiiIpInput = findViewById(R.id.wiiIpInput);
        sendButton = findViewById(R.id.sendButton);
        browseButton = findViewById(R.id.browseButton);

        String lastIp = prefs.getString("last_ip", "");
        wiiIpInput.setText(lastIp);

        browseButton.setOnClickListener(v -> openFilePicker());
        sendButton.setOnClickListener(v -> sendFileToWii());

        checkPermissions();
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    1);
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void sendFileToWii() {
        String ip = wiiIpInput.getText().toString();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter Wii IP address", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit().putString("last_ip", ip).apply();

        new SendFileTask().execute(ip);
    }

    private class SendFileTask extends AsyncTask<String, String, Boolean> {
        @Override
        protected Boolean doInBackground(String... params) {
            String host = params[0];
            try {
                publishProgress("Connecting to Wii...");
                Socket socket = new Socket(host, PORT);

                publishProgress("Compressing data...");
                File compressedFile = compressFile(selectedFileUri);

                publishProgress("Sending file...");
                sendFileToSocket(socket, compressedFile);

                compressedFile.delete();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                publishProgress("Error: " + e.getMessage());
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            Toast.makeText(MainActivity.this, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                Toast.makeText(MainActivity.this, "File sent successfully!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private File compressFile(Uri fileUri) throws Exception {
        File compressedFile = new File(getCacheDir(), "compressed.BeamU.gz");
        InputStream in = getContentResolver().openInputStream(fileUri);
        OutputStream out = new DeflaterOutputStream(new FileOutputStream(compressedFile));

        byte[] buffer = new byte[1024];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }

        in.close();
        out.close();
        return compressedFile;
    }

    private void sendFileToSocket(Socket socket, File compressedFile) throws Exception {
        OutputStream os = socket.getOutputStream();
        DataOutputStream dos = new DataOutputStream(os);

        String fileName = selectedFileUri.getLastPathSegment();
        short argsLength = (short) (fileName.length() + 1);
        int compressedLength = (int) compressedFile.length();

        // Get original file size
        InputStream originalFile = getContentResolver().openInputStream(selectedFileUri);
        int originalLength = originalFile.available();
        originalFile.close();

        // Send header
        dos.writeBytes("HAXXp");
        dos.writeByte(0); // max version
        dos.writeByte(5); // min version
        dos.writeShort(argsLength);
        dos.writeInt(compressedLength);
        dos.writeInt(originalLength);

        // Send file data
        InputStream is = new FileInputStream(compressedFile);
        BufferedInputStream bis = new BufferedInputStream(is);
        byte[] buffer = new byte[128 * 1024];
        int numRead;

        while ((numRead = bis.read(buffer)) > 0) {
            dos.write(buffer, 0, numRead);
            dos.flush();
        }

        // Send filename
        dos.writeBytes(fileName + "\0");
        dos.flush();

        bis.close();
        is.close();
        dos.close();
        os.close();
    }
}