package com.example.lungsoundsteth;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Neural network-based lung sound denoiser using TensorFlow Lite.
 * Takes noisy 16-bit PCM audio from ESP32, runs STFT, applies the trained
 * U-Net denoising mask, reconstructs clean audio via inverse STFT,
 * applies clinical bandpass (80-750 Hz) and volume normalization.
 */
public class NeuralDenoiser {

    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 512;
    private static final int HOP_LENGTH = 256;
    private static final int FREQ_BINS = N_FFT / 2 + 1; // 257
    private static final int TIME_FRAMES = 120;
    private static final int CHUNK_SAMPLES = TIME_FRAMES * HOP_LENGTH + N_FFT; // ~31k samples

    private Interpreter tflite;

    // Accumulation buffer for incoming PCM samples
    private float[] inputBuffer = new float[CHUNK_SAMPLES * 2];
    private int inputPos = 0;

    // Hann window for STFT
    private final float[] hannWindow = new float[N_FFT];

    public NeuralDenoiser(Context context) {
        try {
            MappedByteBuffer model = loadModel(context, "lung_denoiser.tflite");
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(2);
            tflite = new Interpreter(model, opts);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Pre-compute Hann window
        for (int i = 0; i < N_FFT; i++) {
            hannWindow[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (N_FFT - 1))));
        }
    }

    private MappedByteBuffer loadModel(Context context, String modelFile) throws Exception {
        AssetFileDescriptor afd = context.getAssets().openFd(modelFile);
        FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
        FileChannel fc = fis.getChannel();
        long startOff = afd.getStartOffset();
        long len = afd.getDeclaredLength();
        return fc.map(FileChannel.MapMode.READ_ONLY, startOff, len);
    }

    /**
     * Feed raw 16-bit PCM samples from ESP32.
     * Returns denoised samples when enough data is accumulated, else returns empty array.
     */
    public short[] process(short[] rawSamples) {
        if (tflite == null || rawSamples == null) return new short[0];

        // Convert to float and accumulate
        for (short s : rawSamples) {
            if (inputPos < inputBuffer.length) {
                inputBuffer[inputPos++] = s / 32768.0f;
            }
        }

        // Not enough for a full STFT chunk yet
        if (inputPos < CHUNK_SAMPLES) {
            return new short[0];
        }

        return processOneChunk();
    }

    /**
     * Flush and process any remaining audio samples in the buffer (even if < CHUNK_SAMPLES).
     * Zero-pads the remaining frames so no audio is lost at the end of recording.
     */
    public short[] flush() {
        if (tflite == null || inputPos == 0) return new short[0];

        int validSamples = inputPos;
        // Pad with zeros to CHUNK_SAMPLES
        while (inputPos < CHUNK_SAMPLES) {
            inputBuffer[inputPos++] = 0.0f;
        }

        short[] processed = processOneChunk();
        inputPos = 0;

        int returnLen = Math.min(validSamples, processed.length);
        short[] result = new short[returnLen];
        System.arraycopy(processed, 0, result, 0, returnLen);
        return result;
    }

    private short[] processOneChunk() {
        float[] chunk = new float[CHUNK_SAMPLES];
        System.arraycopy(inputBuffer, 0, chunk, 0, CHUNK_SAMPLES);

        // Shift buffer: advance by consumed hop length
        int consumed = TIME_FRAMES * HOP_LENGTH;
        int remaining = inputPos - consumed;
        if (remaining > 0) {
            System.arraycopy(inputBuffer, consumed, inputBuffer, 0, remaining);
        }
        inputPos = Math.max(remaining, 0);

        // --- STFT ---
        float[][] magFrames = new float[TIME_FRAMES][FREQ_BINS];
        float[][] phaseFrames = new float[TIME_FRAMES][FREQ_BINS];

        for (int t = 0; t < TIME_FRAMES; t++) {
            int start = t * HOP_LENGTH;
            float[] real = new float[N_FFT];
            float[] imag = new float[N_FFT];

            for (int i = 0; i < N_FFT; i++) {
                real[i] = chunk[start + i] * hannWindow[i];
                imag[i] = 0;
            }

            fft(real, imag, false);

            for (int k = 0; k < FREQ_BINS; k++) {
                magFrames[t][k] = (float) Math.sqrt(real[k] * real[k] + imag[k] * imag[k]);
                phaseFrames[t][k] = (float) Math.atan2(imag[k], real[k]);
            }
        }

        // --- Prepare TFLite input: log1p(magnitude), shape [1, 120, 257, 1] ---
        float[][][][] inputTensor = new float[1][TIME_FRAMES][FREQ_BINS][1];
        for (int t = 0; t < TIME_FRAMES; t++) {
            for (int f = 0; f < FREQ_BINS; f++) {
                inputTensor[0][t][f][0] = (float) Math.log1p(magFrames[t][f]);
            }
        }

        // --- Run TFLite inference ---
        float[][][][] outputTensor = new float[1][TIME_FRAMES][FREQ_BINS][1];
        tflite.run(inputTensor, outputTensor);

        // --- Apply predicted mask ---
        float[][] cleanMag = new float[TIME_FRAMES][FREQ_BINS];
        for (int t = 0; t < TIME_FRAMES; t++) {
            for (int f = 0; f < FREQ_BINS; f++) {
                float mask = outputTensor[0][t][f][0];
                cleanMag[t][f] = magFrames[t][f] * mask;
            }
        }

        // --- Inverse STFT with overlap-add ---
        float[] output = new float[CHUNK_SAMPLES];
        float[] windowSum = new float[CHUNK_SAMPLES];

        for (int t = 0; t < TIME_FRAMES; t++) {
            float[] real = new float[N_FFT];
            float[] imag = new float[N_FFT];

            // Reconstruct complex spectrum
            for (int k = 0; k < FREQ_BINS; k++) {
                real[k] = cleanMag[t][k] * (float) Math.cos(phaseFrames[t][k]);
                imag[k] = cleanMag[t][k] * (float) Math.sin(phaseFrames[t][k]);
            }
            // Mirror for full FFT
            for (int k = FREQ_BINS; k < N_FFT; k++) {
                real[k] = real[N_FFT - k];
                imag[k] = -imag[N_FFT - k];
            }

            fft(real, imag, true);

            int start = t * HOP_LENGTH;
            for (int i = 0; i < N_FFT; i++) {
                if (start + i < output.length) {
                    output[start + i] += real[i] * hannWindow[i];
                    windowSum[start + i] += hannWindow[i] * hannWindow[i];
                }
            }
        }

        // Normalize by window sum
        for (int i = 0; i < output.length; i++) {
            if (windowSum[i] > 1e-6f) {
                output[i] /= windowSum[i];
            }
        }

        // --- Clinical bandpass: keep 80-750 Hz for lung acoustics ---
        output = bandpass(output, 80, 750, SAMPLE_RATE);

        // --- Peak Normalization & Gain Boost (Ensures lung sound is clearly audible) ---
        float maxVal = 0.0f;
        for (int i = 0; i < consumed && i < output.length; i++) {
            float abs = Math.abs(output[i]);
            if (abs > maxVal) maxVal = abs;
        }

        float gain = 1.0f;
        if (maxVal > 1e-5f) {
            // Target peak at 85% of full scale (loud, clear, no clipping)
            gain = Math.min(25.0f, 0.85f / maxVal);
        }

        // Convert back to 16-bit PCM
        short[] result = new short[consumed];
        for (int i = 0; i < consumed && i < output.length; i++) {
            float v = output[i] * gain * 32768.0f;
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            result[i] = (short) v;
        }
        return result;
    }

    /** Simple 2nd-order Butterworth bandpass filter */
    private float[] bandpass(float[] data, float lowHz, float highHz, int sr) {
        double wLow = 2.0 * Math.PI * lowHz / sr;
        double alphaLow = Math.sin(wLow) / (2.0 * 0.707);
        double b0h = (1 + Math.cos(wLow)) / 2, b1h = -(1 + Math.cos(wLow)), b2h = b0h;
        double a0h = 1 + alphaLow, a1h = -2 * Math.cos(wLow), a2h = 1 - alphaLow;

        double wHigh = 2.0 * Math.PI * highHz / sr;
        double alphaHigh = Math.sin(wHigh) / (2.0 * 0.707);
        double b0l = (1 - Math.cos(wHigh)) / 2, b1l = 1 - Math.cos(wHigh), b2l = b0l;
        double a0l = 1 + alphaHigh, a1l = -2 * Math.cos(wHigh), a2l = 1 - alphaHigh;

        float[] filtered = new float[data.length];

        // Highpass pass
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < data.length; i++) {
            double x0 = data[i];
            double y0 = (b0h * x0 + b1h * x1 + b2h * x2 - a1h * y1 - a2h * y2) / a0h;
            x2 = x1; x1 = x0; y2 = y1; y1 = y0;
            filtered[i] = (float) y0;
        }

        // Lowpass pass
        x1 = 0; x2 = 0; y1 = 0; y2 = 0;
        for (int i = 0; i < filtered.length; i++) {
            double x0 = filtered[i];
            double y0 = (b0l * x0 + b1l * x1 + b2l * x2 - a1l * y1 - a2l * y2) / a0l;
            x2 = x1; x1 = x0; y2 = y1; y1 = y0;
            filtered[i] = (float) y0;
        }

        return filtered;
    }

    /** In-place radix-2 Cooley-Tukey FFT */
    private void fft(float[] real, float[] imag, boolean inverse) {
        int n = real.length;
        int bits = (int) (Math.log(n) / Math.log(2));

        for (int i = 0; i < n; i++) {
            int j = bitReverse(i, bits);
            if (j > i) {
                float tr = real[i]; real[i] = real[j]; real[j] = tr;
                float ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double angle = 2.0 * Math.PI / len * (inverse ? 1 : -1);
            float wRe = (float) Math.cos(angle);
            float wIm = (float) Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                float curRe = 1.0f, curIm = 0.0f;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    float uRe = real[u], uIm = imag[u];
                    float vRe = real[v] * curRe - imag[v] * curIm;
                    float vIm = real[v] * curIm + imag[v] * curRe;
                    real[u] = uRe + vRe; imag[u] = uIm + vIm;
                    real[v] = uRe - vRe; imag[v] = uIm - vIm;
                    float newCurRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newCurRe;
                }
            }
        }

        if (inverse) {
            for (int i = 0; i < n; i++) {
                real[i] /= n;
                imag[i] /= n;
            }
        }
    }

    private int bitReverse(int x, int bits) {
        int result = 0;
        for (int i = 0; i < bits; i++) {
            result = (result << 1) | (x & 1);
            x >>= 1;
        }
        return result;
    }

    public void reset() {
        inputPos = 0;
    }

    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
    }
}
