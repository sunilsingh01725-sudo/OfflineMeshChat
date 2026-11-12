package com.example.offlinemeshchat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_ID = "com.example.offlinemeshchat.MESH";
    private final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int PERMISSION_REQUEST_CODE = 101;

    private final Set<String> connectedEndpoints = new HashSet<>();
    private final Set<String> seenMessageIds = new HashSet<>();

    private TextView chatDisplay, statusText;
    private EditText messageInput;
    private final String myUserName = "User_" + (int)(Math.random() * 900 + 100);

    private MediaRecorder recorder;
    private String audioFilePath;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chatDisplay = findViewById(R.id.chatDisplay);
        statusText = findViewById(R.id.statusText);
        messageInput = findViewById(R.id.messageInput);
        Button sendBtn = findViewById(R.id.sendBtn);
        Button pttBtn = findViewById(R.id.pttBtn);

        audioFilePath = getExternalCacheDir().getAbsolutePath() + "/voice_note.3gp";

        checkAndRequestPermissions();

        // टेक्स्ट मैसेज भेजने का बटन
        sendBtn.setOnClickListener(v -> {
            String text = messageInput.getText().toString().trim();
            if (!text.isEmpty()) {
                String msgId = UUID.randomUUID().toString();
                String fullPacket = "TXT|" + msgId + "|" + myUserName + "|" + text;

                seenMessageIds.add(msgId);
                chatDisplay.append("\nमैं (" + myUserName + "): " + text);
                messageInput.setText("");

                broadcastToAllNeighbors(fullPacket, null);
            }
        });

        // वॉकी-टॉकी (दबाकर बोलने वाला बटन)
        pttBtn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startRecording();
                pttBtn.setText("🔴 बोल रहे हैं... (छोड़ने पर जाएगा)");
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                stopRecordingAndSend();
                pttBtn.setText("🎙️ दबाकर बोलें (Hold to Talk)");
            }
            return true;
        });
    }

    private void startRecording() {
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setOutputFile(audioFilePath);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            Toast.makeText(this, "माइक एरर: परमिशन दें", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecordingAndSend() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;

                File file = new File(audioFilePath);
                byte[] bytes = new byte[(int) file.length()];
                FileInputStream fis = new FileInputStream(file);
                fis.read(bytes);
                fis.close();

                String base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String msgId = UUID.randomUUID().toString();
                String fullPacket = "AUD|" + msgId + "|" + myUserName + "|" + base64Audio;

                seenMessageIds.add(msgId);
                chatDisplay.append("\nमैं (" + myUserName + "): 🎙️ [आवाज़ भेजी]");

                broadcastToAllNeighbors(fullPacket, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void playReceivedAudio(String base64Audio) {
        try {
            byte[] audioBytes = Base64.decode(base64Audio, Base64.NO_WRAP);
            File tempAudio = File.createTempFile("recv_audio", ".3gp", getCacheDir());
            FileOutputStream fos = new FileOutputStream(tempAudio);
            fos.write(audioBytes);
            fos.close();

            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempAudio.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String perm : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(perm);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String), PERMISSION_REQUEST_CODE);
        } else {
            startMeshNetwork();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startMeshNetwork();
        }
    }

    private void startMeshNetwork() {
        statusText.setText("नेटवर्क चालू है (आपकी ID: " + myUserName + ")");
        
        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions);

        AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startAdvertising(myUserName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions);
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            Nearby.getConnectionsClient(MainActivity.this)
                  .requestConnection(myUserName, endpointId, connectionLifecycleCallback);
        }
        @Override
        public void onEndpointLost(@NonNull String endpointId) {}
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            Nearby.getConnectionsClient(MainActivity.this).acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                connectedEndpoints.add(endpointId);
                statusText.setText("जुड़े हुए फ़ोन: " + connectedEndpoints.size());
                Toast.makeText(MainActivity.this, "नया फ़ोन जुड़ गया!", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedEndpoints.remove(endpointId);
            statusText.setText("जुड़े हुए फ़ोन: " + connectedEndpoints.size());
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                String receivedPacket = new String(payload.asBytes(), StandardCharsets.UTF_8);
                String[] parts = receivedPacket.split("\\|", 4);

                if (parts.length == 4) {
                    String type = parts[0];
                    String msgId = parts[1];
                    String sender = parts[2];
                    String data = parts[3];

                    if (!seenMessageIds.contains(msgId)) {
                        seenMessageIds.add(msgId);
                        
                        if (type.equals("TXT")) {
                            chatDisplay.append("\n" + sender + ": " + data);
          
