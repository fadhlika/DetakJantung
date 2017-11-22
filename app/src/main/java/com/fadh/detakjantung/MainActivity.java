package com.fadh.detakjantung;


import android.Manifest;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.github.lzyzsd.circleprogress.DonutProgress;
import com.paramsen.noise.Noise;
import com.paramsen.noise.NoiseOptimized;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

import uk.me.berndporr.iirj.Butterworth;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private final double FPS = 20.0; // 10.0 FPS
    private final double BPM_L = 40.0/60.0; // 50 BPM
    private final double BPM_H = 230.0/60.0; // 230 BPM
    private final double CENTER_FREQ = Math.sqrt(BPM_L*BPM_H);
    private final double BANDWIDTH = BPM_H-BPM_L;
    private final int N_SAMPLE = 256; //samples
    private final int DELAY = 20;

    private HRCamera camera;
    private Mat mat;
    List<Mat> channels;

    private Butterworth butterworth;

    private TextView bpmTextview;
    private DonutProgress progressBar;

    int x = 0, d = 0, progress;
    double b;
    float filtered;

    private float[] brightness;

    private boolean isMeasuring = false;

    private Thread t2 = new Thread(new Runnable() {
        @Override
        public void run() {
            doFFT();
        }
    });

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status){
                case LoaderCallbackInterface.SUCCESS:
                    camera.enableView();
                    break;
                default:
                    super.onManagerConnected(status);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);
        }

        camera = (HRCamera) findViewById(R.id.cameraView);
        camera.setCvCameraViewListener(this);
        camera.setBackgroundColor(0xFFFAFAFA);

        progressBar = (DonutProgress) findViewById(R.id.progressBar);
        progressBar.setProgress(0);
        progressBar.setMax(N_SAMPLE);

        bpmTextview = (TextView) findViewById(R.id.bpmTextView);
        bpmTextview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isMeasuring = true;
                x = 0;
                d = 0;
                camera.setFlashOn();
                progressBar.setProgress(0);
                bpmTextview.setText("Mengukur...");
            }
        });

        channels = new ArrayList<>();

        brightness = new float[N_SAMPLE];
        butterworth = new Butterworth();
        butterworth.bandPass(9, FPS, CENTER_FREQ, BANDWIDTH);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(camera != null) camera.disableView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!OpenCVLoader.initDebug()){
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(camera != null) camera.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        camera.rotateCamera();
        camera.setFPS();
    }

    @Override
    public void onCameraViewStopped() {
        camera.setFlashOff();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        mat = inputFrame.rgba();
        if(!isMeasuring) return mat;

        if(x == N_SAMPLE) {
            camera.setFlashOff();
            t2.run();
            isMeasuring = false;
        } else if(x < N_SAMPLE){
            b = Core.sumElems(mat).val[0] / mat.size().area();

            filtered = (float)butterworth.filter(b);
            if(d++ < DELAY) {
                return mat;
            }

            brightness[x] = filtered;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    progressBar.setProgress(x);
                }
            });
        }
        x++;

        return mat;
    }

    private void doFFT(){
        Log.i("FFT", "Do FFT");
        //Apply hann window
        for(int i=0; i < brightness.length; i++){
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / brightness.length);
            brightness[i] *= hann;
        }

        NoiseOptimized noise = Noise.real().optimized().init(N_SAMPLE, true);
        float[] fft = noise.fft(brightness);

        float peak = 0, peak_index = 0;
        for(int i = 0; i < fft.length/2; i++) {
            float gain = (float) Math.sqrt((fft[i*2]*fft[i*2]) + (fft[i*2+1]*fft[i*2+1]));
            float freq = (float)(((float)i*FPS/(float)N_SAMPLE)*60.0);
            if(freq > 40 && freq < 240) {
                if(gain > peak) { peak = gain; peak_index = freq;}
            }
        }
        Log.i("FFT", "Peak: " + peak_index);
        final int bpm = (int)peak_index;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bpmTextview.setText(bpm + " BPM");
            }
        });
        Log.i("FFT", "FFT Done");
    }
}
