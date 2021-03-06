package com.code4a.dueroslib;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.widget.Toast;

import com.code4a.dueroslib.androidsystemimpl.PlatformFactoryImpl;
import com.code4a.dueroslib.androidsystemimpl.webview.BaseWebView;
import com.code4a.dueroslib.devicemodule.cmccgateway.SmartGatewayDeviceModule;
import com.code4a.dueroslib.devicemodule.screen.ScreenDeviceModule;
import com.code4a.dueroslib.devicemodule.screen.message.RenderVoiceInputTextPayload;
import com.code4a.dueroslib.devicemodule.voiceinput.VoiceInputDeviceModule;
import com.code4a.dueroslib.framework.DcsFramework;
import com.code4a.dueroslib.framework.DeviceModuleFactory;
import com.code4a.dueroslib.http.HttpConfig;
import com.code4a.dueroslib.oauth.api.BaiduDialog;
import com.code4a.dueroslib.oauth.api.BaiduOauth;
import com.code4a.dueroslib.oauth.api.BaiduOauthClientCredentials;
import com.code4a.dueroslib.oauth.api.BaiduOauthImplicitGrant;
import com.code4a.dueroslib.oauth.api.OauthClientCredentialsInfo;
import com.code4a.dueroslib.oauth.api.OauthRequest;
import com.code4a.dueroslib.oauth.exception.OauthParameterInvalidException;
import com.code4a.dueroslib.oauth.exception.TokenInvalidException;
import com.code4a.dueroslib.systeminterface.IPlatformFactory;
import com.code4a.dueroslib.systeminterface.IWakeUp;
import com.code4a.dueroslib.systeminterface.IWebView;
import com.code4a.dueroslib.util.CommonUtil;
import com.code4a.dueroslib.util.LogUtil;
import com.code4a.dueroslib.util.NetWorkUtil;
import com.code4a.dueroslib.wakeup.WakeUp;
import com.code4a.dueroslib.wakeup.WakeUpSuccessCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Created by jiang on 2018/1/31.
 */

public final class DuerosConfig {

    static final String TAG = DuerosConfig.class.getSimpleName();

    // 需要开发者自己申请client_id
    // client_id，就是oauth的client_id
    private final String client_id;
    private final String client_secret;

    private static Application application;
    private static DuerosConfig duerosConfig;

    private BaiduOauth baiduOauth;
    private DcsFramework dcsFramework;
    private IPlatformFactory platformFactory;
    private DeviceModuleFactory deviceModuleFactory;
    private BaseWebView webView;
    // 唤醒
    private WakeUp wakeUp;
    private List<WakeUpSuccessCallback> wakeUpSuccessCallbacks;
    private boolean isStopListenReceiving = false;
    private long startTimeStopListen;

    private List<OnRecordingListener> recordingListeners;
    private List<DuerosCustomTaskCallback> customTaskCallbacks;

    private DuerosConfig(String clientId, String clientSecret) {
        client_id = clientId;
        client_secret = clientSecret;
    }

    private void initList() {
        recordingListeners = Collections.synchronizedList(new ArrayList<OnRecordingListener>());
        wakeUpSuccessCallbacks = Collections.synchronizedList(new ArrayList<WakeUpSuccessCallback>());
        customTaskCallbacks = Collections.synchronizedList(new ArrayList<DuerosCustomTaskCallback>());
    }

    protected static void init(Application application, String clientId, String clientSecret) {
        DuerosConfig.application = application;
        duerosConfig = new DuerosConfig(clientId, clientSecret);
    }

    public static Application getApplication() {
        return application;
    }

    protected static DuerosConfig getInstance() {
        return duerosConfig;
    }

    protected void clientCredentialsOauth(OauthRequest.OauthCallback<OauthClientCredentialsInfo> oauthCallback) {
        checkOauthParams();
        if (baiduOauth == null || !(baiduOauth instanceof BaiduOauthClientCredentials)) {
            baiduOauth = new BaiduOauthClientCredentials(client_id, client_secret, application);
        }
        baiduOauth.authorize(application, oauthCallback);
    }

    protected void implicitGrantOauth(BaiduDialog.BaiduDialogListener listener, boolean isForceLogin, boolean isConfirmLogin) {
        checkOauthParams();
        if (baiduOauth == null || !(baiduOauth instanceof BaiduOauthImplicitGrant)) {
            baiduOauth = new BaiduOauthImplicitGrant(client_id, application);
        }
        baiduOauth.authorize(application, listener, isForceLogin, isConfirmLogin);
    }

    private void checkOauthParams() {
        if (TextUtils.isEmpty(client_id) && TextUtils.isEmpty(client_secret)) {
            throw new OauthParameterInvalidException();
        }
    }

    private boolean holdRelatedPermission() {
        int permissionCheck = ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO);
        return permissionCheck == PackageManager.PERMISSION_GRANTED;
    }

    protected boolean initDcsFramework() {
        if (baiduOauth == null || !baiduOauth.isSessionValid()) {
            throw new TokenInvalidException();
        } else {
            HttpConfig.setAccessToken(baiduOauth.getAccessToken());
        }
        if (!holdRelatedPermission()) throw new SecurityException("请授予App录音的权限！");
        initList();
        platformFactory = new PlatformFactoryImpl(application);
        dcsFramework = new DcsFramework(platformFactory);
        deviceModuleFactory = dcsFramework.getDeviceModuleFactory();
        deviceModuleFactory.createVoiceOutputDeviceModule();
        deviceModuleFactory.createVoiceInputDeviceModule();
        deviceModuleFactory.getVoiceInputDeviceModule().addVoiceInputListener(
                new VoiceInputDeviceModule.IVoiceInputListener() {
                    @Override
                    public void onStartRecord() {
                        LogUtil.d(TAG, "onStartRecord");
                        startRecording();
                        notifyRecording(true);
                    }

                    @Override
                    public void onFinishRecord() {
                        LogUtil.d(TAG, "onFinishRecord");
                        stopRecording();
                        notifyRecording(false);
                    }

                    @Override
                    public void onSucceed(int statusCode) {
                        LogUtil.d(TAG, "onSucceed-statusCode:" + statusCode);
                        if (statusCode != 200) {
                            stopRecording();
                            showShortToast("识别失败，请稍后再试");
                        }
                    }

                    @Override
                    public void onFailed(String errorMessage) {
                        LogUtil.d(TAG, "onFailed-errorMessage:" + errorMessage);
                        stopRecording();
                        showShortToast("识别失败，请稍后再试");
                    }
                });

        deviceModuleFactory.createAlertsDeviceModule();
        deviceModuleFactory.createSystemDeviceModule();
        deviceModuleFactory.createSpeakControllerDeviceModule();
        initWakeUp();
        return true;
    }

    private void initWakeUp() {
        // init唤醒
        wakeUp = new WakeUp(platformFactory.getWakeUp(), platformFactory.getAudioRecord());
        wakeUp.addWakeUpListener(wakeUpListener);
        // 开始录音，监听是否说了唤醒词
        wakeUp.startWakeUp();
    }

    public void stopAudioRecord() {
        if (platformFactory != null && wakeUp != null) {
            wakeUp.stopIAudioRecord();
            platformFactory.resetAudioRecord();
        }
    }

    public void startAudioRecord() {
        if (platformFactory != null && wakeUp != null) {
            wakeUp.startIAudioRecord(platformFactory.getAudioRecord());
        }
    }

    private void createScreenDeviceModule() {
        getDeviceModuleFactory().createScreenDeviceModule();
        getDeviceModuleFactory().getScreenDeviceModule()
                .addRenderVoiceInputTextListener(new ScreenDeviceModule.IRenderVoiceInputTextListener() {
                    @Override
                    public void onRenderVoiceInputText(RenderVoiceInputTextPayload payload) {
                        notifyOnRecording(payload.text);
                    }

                });
    }

    protected void createGatewayDeviceModule() {
        getDeviceModuleFactory().createSmartGatewayDeviceModule();
        getDeviceModuleFactory().getSmartGatewayDeviceModule()
                .addControlDeviceListener(new SmartGatewayDeviceModule.ControlDeviceListener() {
                    @Override
                    public void controlDevice(SmartGatewayDeviceModule.Command command, String deviceName, String type) {
                        LogUtil.e(TAG, "controlDevice : " + deviceName + type);
                        notifyControlDevice(command, deviceName, type);
                    }
                });
    }

    private void notifyControlDevice(SmartGatewayDeviceModule.Command command, String deviceName, String type) {
        if (customTaskCallbacks == null) return;
        for (DuerosCustomTaskCallback customTaskCallback : customTaskCallbacks) {
            if (customTaskCallback instanceof DuerosGatewayManager.GatewayControlListener) {
                ((DuerosGatewayManager.GatewayControlListener) customTaskCallback).controlDevice(command, deviceName, type);
            }
        }
    }

    protected void addDuerosCustomTaskCallback(DuerosCustomTaskCallback customTaskCallback) {
        if (customTaskCallbacks == null)
            customTaskCallbacks = Collections.synchronizedList(new ArrayList<DuerosCustomTaskCallback>());
        customTaskCallbacks.clear();
        customTaskCallbacks.add(customTaskCallback);
    }

    void showShortToast(String text) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show();
    }

    protected void addWakeUpSuccessCallback(WakeUpSuccessCallback wakeUpSuccessCallback) {
        if (wakeUpSuccessCallbacks == null)
            wakeUpSuccessCallbacks = Collections.synchronizedList(new ArrayList<WakeUpSuccessCallback>());
        this.wakeUpSuccessCallbacks.add(wakeUpSuccessCallback);
    }

    protected void setWebView(BaseWebView webView) {
        this.webView = webView;
        if (webView != null && platformFactory != null) {
            platformFactory.setWebView(webView);
            createScreenDeviceModule();
        }
    }

    public void startRecording() {
        if (wakeUp != null) wakeUp.stopWakeUp();
        isStopListenReceiving = true;
        doUserActivity();
    }

    public void stopRecording() {
        if (wakeUp != null) wakeUp.startWakeUp();
        isStopListenReceiving = false;
        long t = System.currentTimeMillis() - startTimeStopListen;
        LogUtil.e(DuerosConfig.class, String.format("耗时:click->StopListen:%dms", t));
    }

    public void startWakeUp() {
        if (wakeUp != null) {
            wakeUp.startWakeUp();
        }
    }

    public void stopWakeUp() {
        if (wakeUp != null) {
            wakeUp.stopWakeUp();
        }
    }

    private void doUserActivity() {
        getDeviceModuleFactory().getSystemProvider().userActivity();
    }

    public void changeRecord() {
        if (!NetWorkUtil.isNetworkConnected(application)) {
            Toast.makeText(application, "当前网络连接失败,请稍后重试", Toast.LENGTH_SHORT).show();
            if (wakeUp != null) wakeUp.startWakeUp();
            return;
        }
        if (CommonUtil.isFastDoubleClick()) {
            return;
        }
        if (dcsFramework == null || TextUtils.isEmpty(HttpConfig.getAccessToken())) {
            throw new TokenInvalidException();
        }
        if (!dcsFramework.getDcsClient().isConnected()) {
            dcsFramework.getDcsClient().startConnect();
            return;
        }
        if (isStopListenReceiving) {
            platformFactory.getVoiceInput().stopRecord();
            isStopListenReceiving = false;
            return;
        }
        isStopListenReceiving = true;
        startTimeStopListen = System.currentTimeMillis();
        platformFactory.getVoiceInput().startRecord();
        doUserActivity();
    }

    DeviceModuleFactory getDeviceModuleFactory() {
        return dcsFramework.getDeviceModuleFactory();
    }

    private IWakeUp.IWakeUpListener wakeUpListener = new IWakeUp.IWakeUpListener() {
        @Override
        public void onWakeUpSucceed() {
            notifyWakeUpSucceed();
            changeRecord();
        }
    };

    private void notifyWakeUpSucceed() {
        if (wakeUpSuccessCallbacks == null) return;
        for (WakeUpSuccessCallback wakeUpSuccessCallback : wakeUpSuccessCallbacks) {
            wakeUpSuccessCallback.onSuccess();
        }
    }

    protected boolean uninitFramework() {
        uninitWakeUp(true);
        if (dcsFramework != null) {
            dcsFramework.release();
            dcsFramework = null;
        }

        if (webView != null) {
            webView.setWebViewClientListen(null);
            webView.removeAllViews();
            webView.destroy();
        }
        if (recordingListeners != null) {
            recordingListeners.clear();
            recordingListeners = null;
        }
        if (customTaskCallbacks != null) {
            customTaskCallbacks.clear();
            customTaskCallbacks = null;
        }
        return true;
    }

    private void uninitWakeUp(boolean isFinally) {
        if (wakeUp != null) {
            // 先remove listener  停止唤醒,释放资源
            wakeUp.stopWakeUp();
            wakeUp.removeWakeUpListener(wakeUpListener);
            if (isFinally && wakeUpSuccessCallbacks != null) {
                wakeUpSuccessCallbacks.clear();
                wakeUpSuccessCallbacks = null;
            }
            wakeUp.releaseWakeUp();
            wakeUp = null;
        }
    }

    public IWebView getWebView() {
        return webView;
    }

    public void addOnRecordingListener(OnRecordingListener listener) {
        if (recordingListeners == null)
            recordingListeners = Collections.synchronizedList(new ArrayList<OnRecordingListener>());
        recordingListeners.add(listener);
    }

    void notifyRecording(boolean isStart) {
        if (recordingListeners == null) return;
        for (OnRecordingListener recordingListener : recordingListeners) {
            if (isStart) {
                recordingListener.startRecording();
            } else {
                recordingListener.stopRecording();
            }
        }
    }

    void notifyOnRecording(String text) {
        if (recordingListeners == null) return;
        for (OnRecordingListener recordingListener : recordingListeners) {
            recordingListener.onRecording(text);
        }
    }

    public interface OnRecordingListener {
        void startRecording();

        void stopRecording();

        void onRecording(String text);
    }
}
