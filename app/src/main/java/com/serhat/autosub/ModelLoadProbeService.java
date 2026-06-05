package com.serhat.autosub;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;

import com.whispercpp.whisper.WhisperContext;

import org.vosk.Model;

import java.io.File;

public class ModelLoadProbeService extends Service {
    public static final String EXTRA_MODEL_ID = "model_id";
    public static final String EXTRA_RECEIVER = "receiver";
    public static final String EXTRA_ERROR = "error";
    public static final int RESULT_OK = 1;
    public static final int RESULT_ERROR = 2;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> probeModel(intent, startId), "vosk-model-probe").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void probeModel(Intent intent, int startId) {
        ResultReceiver receiver = intent != null ? intent.getParcelableExtra(EXTRA_RECEIVER) : null;
        String modelId = intent != null ? intent.getStringExtra(EXTRA_MODEL_ID) : null;
        VoskModelManager modelManager = null;
        VoskModelInfo modelInfo = null;

        try {
            modelManager = new VoskModelManager(this);
            modelManager.loadCatalog();
            modelInfo = modelManager.findById(modelId);
            if (modelInfo == null) {
                sendError(receiver, "Unknown model");
                return;
            }

            if (modelInfo.isWhisper()) {
                WhisperContext whisperContext;
                if (modelInfo.isBundled()) {
                    whisperContext = WhisperContext.createContextFromAsset(
                            getAssets(), modelInfo.getBundledAssetName());
                } else {
                    File modelDirectory = modelManager.getModelDirectory(modelInfo);
                    File modelFile = new File(modelDirectory, modelInfo.getId() + ".bin");
                    if (!modelFile.isFile()) {
                        sendError(receiver, "Model is not downloaded");
                        return;
                    }
                    whisperContext = WhisperContext.createContextFromFile(modelFile.getAbsolutePath());
                }
                whisperContext.release();
                if (receiver != null) {
                    receiver.send(RESULT_OK, Bundle.EMPTY);
                }
                return;
            }

            modelManager.restoreOptionalFolders(modelInfo);
            File modelDirectory = modelManager.getModelDirectory(modelInfo);
            if (!modelDirectory.isDirectory()) {
                sendError(receiver, "Model is not downloaded");
                return;
            }

            Model model = new Model(modelDirectory.getAbsolutePath());
            model.close();
            if (receiver != null) {
                receiver.send(RESULT_OK, Bundle.EMPTY);
            }
        } catch (Throwable throwable) {
            sendError(receiver, throwable.getMessage() == null ? "Model failed to load" : throwable.getMessage());
        } finally {
            if (modelManager != null && modelInfo != null) {
                modelManager.prepareForMobileLoad(modelInfo);
            }
            stopSelf(startId);
        }
    }

    private void sendError(ResultReceiver receiver, String message) {
        if (receiver == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_ERROR, message);
        receiver.send(RESULT_ERROR, bundle);
    }
}
