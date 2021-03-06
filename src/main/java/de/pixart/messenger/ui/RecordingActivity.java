package de.pixart.messenger.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.persistance.FileBackend;

public class RecordingActivity extends Activity implements View.OnClickListener {

    private TextView mTimerTextView;
    private Button mCancelButton;
    private Button mStopButton;

    private MediaRecorder mRecorder;
    private long mStartTime = 0;

    private int[] amplitudes = new int[100];
    private int i = 0;

    private Handler mHandler = new Handler();
    private Runnable mTickExecutor = new Runnable() {
        @Override
        public void run() {
            tick();
            mHandler.postDelayed(mTickExecutor, 100);
        }
    };
    private File mOutputFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording);
        this.mTimerTextView = this.findViewById(R.id.timer);
        this.mCancelButton = this.findViewById(R.id.cancel_button);
        this.mCancelButton.setOnClickListener(this);
        this.mStopButton = this.findViewById(R.id.share_button);
        this.mStopButton.setOnClickListener(this);
        this.setFinishOnTouchOutside(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(Config.LOGTAG, "output: " + getOutputFile());
        if (!startRecording()) {
            mStopButton.setEnabled(false);
            mStopButton.setTextColor(0x8a000000);
            Toast.makeText(this, R.string.unable_to_start_recording, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mRecorder != null) {
            stopRecording(false);
        }
    }

    private boolean startRecording() {
        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioEncodingBitRate(48000);
        } else {
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioEncodingBitRate(48000);
        }
        mRecorder.setAudioSamplingRate(48000);
        mOutputFile = getOutputFile();
        mOutputFile.getParentFile().mkdirs();
        mRecorder.setOutputFile(mOutputFile.getAbsolutePath());

        try {
            mRecorder.prepare();
            mRecorder.start();
            mStartTime = SystemClock.elapsedRealtime();
            mHandler.postDelayed(mTickExecutor, 100);
            Log.d(Config.LOGTAG, "started recording to " + mOutputFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "prepare() failed " + e.getMessage());
            return false;
        }
    }

    protected void stopRecording(boolean saveFile) {
        mRecorder.stop();
        mRecorder.release();
        mRecorder = null;
        mStartTime = 0;
        mHandler.removeCallbacks(mTickExecutor);
        if (!saveFile && mOutputFile != null) {
            mOutputFile.delete();
        }
    }

    private File getOutputFile() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US);
        return new File(FileBackend.getConversationsDirectory("Audios", true) + "/"
                + dateFormat.format(new Date())
                + ".m4a");
    }

    private void tick() {
        long time = (mStartTime < 0) ? 0 : (SystemClock.elapsedRealtime() - mStartTime);
        int minutes = (int) (time / 60000);
        int seconds = (int) (time / 1000) % 60;
        int milliseconds = (int) (time / 100) % 10;
        mTimerTextView.setText(minutes + ":" + (seconds < 10 ? "0" + seconds : seconds) + "." + milliseconds);
        if (mRecorder != null) {
            amplitudes[i] = mRecorder.getMaxAmplitude();
            //Log.d(Config.LOGTAG,"amplitude: "+(amplitudes[i] * 100 / 32767));
            if (i >= amplitudes.length - 1) {
                i = 0;
            } else {
                ++i;
            }
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.cancel_button:
                stopRecording(false);
                setResult(RESULT_CANCELED);
                finish();
                break;
            case R.id.share_button:
                stopRecording(true);
                Uri uri = Uri.parse("file://" + mOutputFile.getAbsolutePath());
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(uri);
                sendBroadcast(scanIntent);
                setResult(Activity.RESULT_OK, new Intent().setData(uri));
                finish();
                break;
        }
    }
}