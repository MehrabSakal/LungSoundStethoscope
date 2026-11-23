package com.example.lungsoundsteth;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int SAMPLE_RATE = 16000;

    // UI - Stream
    private EditText etStreamUrl;
    private TextView tvStatus;
    private MaterialButton btnConnect;
    private ProgressBar pbAudioLevel;
    private MaterialButton btnToggleSpeaker;
    private MaterialButton btnRecord;
    private TextView tvRecordTimer;

    // UI - Hear Player
    private TextView tvLatestRecordingInfo;
    private MaterialButton btnHearClean;
    private SeekBar sbPlayback;
    private TextView tvPlaybackTime;
    private MaterialButton btnPause;
    private MaterialButton btnStopAudio;

    // UI - Save & Share
    private TextView tvSaveStatus;
    private MaterialButton btnSaveToDevice;
    private MaterialButton btnShareAudio;

    // UI - History
    private Spinner spRecordings;
    private MaterialButton btnPlayHistory;
    private MaterialButton btnDelete;

    // Denoiser & Live Audio
    private NeuralDenoiser denoiser;
    private AudioTrack audioTrack;
    private boolean isSpeakerMuted = false;

    // Streaming Thread
    private volatile boolean isStreaming = false;
    private Thread streamThread;

    // Recording State
    private volatile boolean isRecording = false;
    private File currentRecordFile;
    private File latestRecordedFile;
    private FileOutputStream recordOutputStream;
    private long recordStartTime = 0;
    private final Handler recordTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                long elapsed = (System.currentTimeMillis() - recordStartTime) / 1000;
                long mins = elapsed / 60;
                long secs = elapsed % 60;
                tvRecordTimer.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
                recordTimerHandler.postDelayed(this, 500);
            }
        }
    };

    // Playback State
    private MediaPlayer mediaPlayer;
    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                int cur = mediaPlayer.getCurrentPosition();
                int dur = mediaPlayer.getDuration();
                sbPlayback.setProgress(cur);
                tvPlaybackTime.setText(formatTime(cur) + " / " + formatTime(dur));
                playbackHandler.postDelayed(this, 250);
            }
        }
    };

    private final List<File> recordingFiles = new ArrayList<>();
    private ArrayAdapter<String> recordingsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initNeuralDenoiser();
        initAudioTrack();
        setupRecordingsList();
        setupListeners();
    }

    private void bindViews() {
        // Stream Card
        etStreamUrl = findViewById(R.id.etStreamUrl);
        tvStatus = findViewById(R.id.tvStatus);
        btnConnect = findViewById(R.id.btnConnect);
        pbAudioLevel = findViewById(R.id.pbAudioLevel);
        btnToggleSpeaker = findViewById(R.id.btnToggleSpeaker);
        btnRecord = findViewById(R.id.btnRecord);
        tvRecordTimer = findViewById(R.id.tvRecordTimer);

        // Hear Card
        tvLatestRecordingInfo = findViewById(R.id.tvLatestRecordingInfo);
        btnHearClean = findViewById(R.id.btnHearClean);
        sbPlayback = findViewById(R.id.sbPlayback);
        tvPlaybackTime = findViewById(R.id.tvPlaybackTime);
        btnPause = findViewById(R.id.btnPause);
        btnStopAudio = findViewById(R.id.btnStopAudio);

        // Save & Share Card
        tvSaveStatus = findViewById(R.id.tvSaveStatus);
        btnSaveToDevice = findViewById(R.id.btnSaveToDevice);
        btnShareAudio = findViewById(R.id.btnShareAudio);

        // History Card
        spRecordings = findViewById(R.id.spRecordings);
        btnPlayHistory = findViewById(R.id.btnPlayHistory);
        btnDelete = findViewById(R.id.btnDelete);
    }

    private void initNeuralDenoiser() {
        try {
            denoiser = new NeuralDenoiser(this);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Model load error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initAudioTrack() {
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBufferSize, 16000 * 2);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                );
            }
            audioTrack.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupRecordingsList() {
        recordingsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        recordingsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRecordings.setAdapter(recordingsAdapter);
        refreshRecordingsList();
    }

    private void refreshRecordingsList() {
        recordingFiles.clear();
        File dir = getFilesDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".wav"));
        if (files != null) {
            for (File f : files) {
                recordingFiles.add(f);
            }
        }
        // Most recent first
        recordingFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        List<String> names = new ArrayList<>();
        if (recordingFiles.isEmpty()) {
            names.add("(No saved recordings)");
        } else {
            for (File f : recordingFiles) {
                names.add(f.getName());
            }
            if (latestRecordedFile == null) {
                latestRecordedFile = recordingFiles.get(0);
                tvLatestRecordingInfo.setText("Ready to play: " + latestRecordedFile.getName());
            }
        }
        recordingsAdapter.clear();
        recordingsAdapter.addAll(names);
        recordingsAdapter.notifyDataSetChanged();
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> {
            if (isStreaming) {
                stopStreaming();
            } else {
                startStreaming(etStreamUrl.getText().toString().trim());
            }
        });

        btnToggleSpeaker.setOnClickListener(v -> {
            isSpeakerMuted = !isSpeakerMuted;
            if (isSpeakerMuted) {
                btnToggleSpeaker.setText("🔇 Live Earphone/Speaker: MUTED");
                if (audioTrack != null) audioTrack.setVolume(0.0f);
            } else {
                btnToggleSpeaker.setText("🔊 Live Earphone/Speaker: ON");
                if (audioTrack != null) audioTrack.setVolume(1.0f);
            }
        });

        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        // Hear Clean Audio Playback
        btnHearClean.setOnClickListener(v -> {
            if (latestRecordedFile != null && latestRecordedFile.exists()) {
                playAudioFile(latestRecordedFile);
            } else if (!recordingFiles.isEmpty()) {
                playAudioFile(recordingFiles.get(0));
            } else {
                Toast.makeText(this, "No recording to hear yet! Tap 'Start Recording' first.", Toast.LENGTH_SHORT).show();
            }
        });

        btnPause.setOnClickListener(v -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        });

        btnStopAudio.setOnClickListener(v -> stopPlayback());

        sbPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Save to Phone Public Storage
        btnSaveToDevice.setOnClickListener(v -> saveCurrentFileToDevice());

        // Share Audio File
        btnShareAudio.setOnClickListener(v -> shareCurrentFile());

        // History
        btnPlayHistory.setOnClickListener(v -> {
            int pos = spRecordings.getSelectedItemPosition();
            if (pos >= 0 && pos < recordingFiles.size()) {
                playAudioFile(recordingFiles.get(pos));
            }
        });

        btnDelete.setOnClickListener(v -> {
            int pos = spRecordings.getSelectedItemPosition();
            if (pos >= 0 && pos < recordingFiles.size()) {
                File target = recordingFiles.get(pos);
                stopPlayback();
                if (target.delete()) {
                    Toast.makeText(this, "Deleted: " + target.getName(), Toast.LENGTH_SHORT).show();
                    if (target.equals(latestRecordedFile)) {
                        latestRecordedFile = null;
                        tvLatestRecordingInfo.setText("No recording selected");
                    }
                    refreshRecordingsList();
                }
            }
        });
    }

    private void startStreaming(final String urlString) {
        isStreaming = true;
        tvStatus.setText("Status: Connecting...");
        tvStatus.setTextColor(Color.parseColor("#F59E0B"));
        btnConnect.setText("Disconnect");

        streamThread = new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream inputStream = null;
            try {
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(12000);
                conn.connect();

                if (conn.getResponseCode() != 200) {
                    throw new Exception("HTTP " + conn.getResponseCode());
                }

                inputStream = conn.getInputStream();
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Connected & Denoising");
                    tvStatus.setTextColor(Color.parseColor("#00E676"));
                });

                byte[] byteBuffer = new byte[1024]; // 512 samples
                short[] shortBuffer = new short[512];

                while (isStreaming) {
                    int bytesRead = inputStream.read(byteBuffer);
                    if (bytesRead <= 0) break;

                    int samplesRead = bytesRead / 2;
                    ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuffer, 0, samplesRead);

                    short[] inputSamples = new short[samplesRead];
                    System.arraycopy(shortBuffer, 0, inputSamples, 0, samplesRead);

                    // Compute RMS for live meter
                    double sum = 0;
                    for (short s : inputSamples) {
                        sum += s * s;
                    }
                    double rms = Math.sqrt(sum / inputSamples.length);
                    int level = (int) Math.min(100, (rms / 32768.0) * 450);
                    runOnUiThread(() -> pbAudioLevel.setProgress(level));

                    // Run AI Denoising Pipeline
                    short[] cleanSamples = (denoiser != null) ? denoiser.process(inputSamples) : inputSamples;

                    if (cleanSamples.length > 0) {
                        // Play real-time clean audio to speaker/earphone
                        if (!isSpeakerMuted && audioTrack != null) {
                            audioTrack.write(cleanSamples, 0, cleanSamples.length);
                        }

                        // Save clean audio if recording
                        if (isRecording && recordOutputStream != null) {
                            byte[] outBytes = new byte[cleanSamples.length * 2];
                            ByteBuffer.wrap(outBytes)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer()
                                    .put(cleanSamples);
                            recordOutputStream.write(outBytes);
                        }
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Disconnected");
                    tvStatus.setTextColor(Color.parseColor("#EF4444"));
                    btnConnect.setText("Connect");
                    pbAudioLevel.setProgress(0);
                    Toast.makeText(MainActivity.this, "Stream stopped: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            } finally {
                isStreaming = false;
                try {
                    if (inputStream != null) inputStream.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Disconnected");
                    tvStatus.setTextColor(Color.parseColor("#EF4444"));
                    btnConnect.setText("Connect");
                    pbAudioLevel.setProgress(0);
                });
            }
        });
        streamThread.start();
    }

    private void stopStreaming() {
        isStreaming = false;
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
        if (isRecording) {
            stopRecording();
        }
        tvStatus.setText("Status: Disconnected");
        tvStatus.setTextColor(Color.parseColor("#EF4444"));
        btnConnect.setText("Connect");
        pbAudioLevel.setProgress(0);
    }

    private void startRecording() {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "lung_clean_" + timeStamp + ".wav";
            currentRecordFile = new File(getFilesDir(), fileName);
            recordOutputStream = new FileOutputStream(currentRecordFile);

            // Write 44-byte WAV dummy header
            byte[] dummyHeader = new byte[44];
            recordOutputStream.write(dummyHeader);

            if (denoiser != null) denoiser.reset();

            isRecording = true;
            recordStartTime = System.currentTimeMillis();
            btnRecord.setText("⏹ Stop Recording");
            btnRecord.setBackgroundColor(Color.parseColor("#334155"));
            recordTimerHandler.post(recordTimerRunnable);

            Toast.makeText(this, "Recording clean lung sound...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Record error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        recordTimerHandler.removeCallbacks(recordTimerRunnable);

        try {
            // Flush any remaining samples in neural denoiser
            if (denoiser != null && recordOutputStream != null) {
                short[] remaining = denoiser.flush();
                if (remaining.length > 0) {
                    byte[] outBytes = new byte[remaining.length * 2];
                    ByteBuffer.wrap(outBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .put(remaining);
                    recordOutputStream.write(outBytes);
                }
            }

            if (recordOutputStream != null) {
                recordOutputStream.flush();
                recordOutputStream.close();
                recordOutputStream = null;
            }

            // Finalize WAV Header
            if (currentRecordFile != null && currentRecordFile.exists()) {
                writeWavHeader(currentRecordFile);
                latestRecordedFile = currentRecordFile;
                tvLatestRecordingInfo.setText("✅ Clean Sound Ready: " + latestRecordedFile.getName());
                Toast.makeText(this, "Recorded successfully! Tap 'HEAR CLEAN LUNG SOUND' below.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error saving WAV: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        btnRecord.setText("● Start Recording");
        btnRecord.setBackgroundColor(Color.parseColor("#EF4444"));
        tvRecordTimer.setText("00:00");
        refreshRecordingsList();
    }

    private void writeWavHeader(File file) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        long totalDataLen = raf.length() - 44;
        long totalAudioLen = totalDataLen + 36;
        long byteRate = SAMPLE_RATE * 1 * 2; // 16000 * mono * 16bit

        raf.seek(0);
        raf.writeBytes("RIFF");
        raf.write(intToByteArray((int) totalAudioLen));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        raf.write(intToByteArray(16)); // Subchunk1Size
        raf.write(shortToByteArray((short) 1)); // AudioFormat (1=PCM)
        raf.write(shortToByteArray((short) 1)); // NumChannels (1=Mono)
        raf.write(intToByteArray(SAMPLE_RATE));
        raf.write(intToByteArray((int) byteRate));
        raf.write(shortToByteArray((short) 2)); // BlockAlign
        raf.write(shortToByteArray((short) 16)); // BitsPerSample
        raf.writeBytes("data");
        raf.write(intToByteArray((int) totalDataLen));
        raf.close();
    }

    private byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff),
                (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 24) & 0xff)
        };
    }

    private byte[] shortToByteArray(short value) {
        return new byte[]{
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff)
        };
    }

    // --- Audio Playback System ---
    private void playAudioFile(File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Audio file not found!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            stopPlayback();

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            latestRecordedFile = file;
            tvLatestRecordingInfo.setText("🔊 Playing: " + file.getName());

            sbPlayback.setMax(mediaPlayer.getDuration());
            playbackHandler.post(playbackRunnable);

            mediaPlayer.setOnCompletionListener(mp -> {
                sbPlayback.setProgress(0);
                tvPlaybackTime.setText("00:00 / " + formatTime(mp.getDuration()));
                tvLatestRecordingInfo.setText("Finished: " + file.getName());
            });
        } catch (Exception e) {
            Toast.makeText(this, "Playback error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        playbackHandler.removeCallbacks(playbackRunnable);
        sbPlayback.setProgress(0);
        tvPlaybackTime.setText("00:00 / 00:00");
    }

    // --- Save to Phone Storage (Music / Downloads) ---
    private void saveCurrentFileToDevice() {
        File fileToSave = (latestRecordedFile != null) ? latestRecordedFile : (!recordingFiles.isEmpty() ? recordingFiles.get(0) : null);
        if (fileToSave == null || !fileToSave.exists()) {
            Toast.makeText(this, "No recorded audio available to save!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileToSave.getName());
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LungStethoscope");

                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri);
                         FileInputStream in = new FileInputStream(fileToSave)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                    tvSaveStatus.setText("💾 Saved to: Music/LungStethoscope/" + fileToSave.getName());
                    Toast.makeText(this, "Saved to phone's Music folder!", Toast.LENGTH_LONG).show();
                }
            } else {
                File musicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "LungStethoscope");
                if (!musicDir.exists()) musicDir.mkdirs();
                File dest = new File(musicDir, fileToSave.getName());
                try (FileInputStream in = new FileInputStream(fileToSave);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                tvSaveStatus.setText("💾 Saved to: " + dest.getAbsolutePath());
                Toast.makeText(this, "Saved to Music/LungStethoscope!", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // --- Share Audio File ---
    private void shareCurrentFile() {
        File fileToShare = (latestRecordedFile != null) ? latestRecordedFile : (!recordingFiles.isEmpty() ? recordingFiles.get(0) : null);
        if (fileToShare == null || !fileToShare.exists()) {
            Toast.makeText(this, "No recorded audio available to share!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri contentUri = FileProvider.getUriForFile(this, "com.example.lungsoundsteth.fileprovider", fileToShare);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("audio/wav");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Clean Lung Sound"));
        } catch (Exception e) {
            Toast.makeText(this, "Share error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTime(int ms) {
        int seconds = (ms / 1000) % 60;
        int minutes = (ms / (1000 * 60));
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
        stopPlayback();

        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }

        if (denoiser != null) {
            denoiser.close();
            denoiser = null;
        }
    }
}
