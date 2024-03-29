/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.util.ApConfigUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under "WifiStateMachine" thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    // Minimum limit to use for timeout delay if the value from overlay setting is too small.
    private static final int MIN_SOFT_AP_TIMEOUT_DELAY_MS = 600_000;  // 10 minutes

    @VisibleForTesting
    public static final String SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG = TAG
            + " Soft AP Send Message Timeout";

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNative mWifiNative;

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final WifiManager.SoftApCallback mCallback;

    private String mApInterfaceName;
    private String mDataInterfaceName;
    private boolean mIfaceIsUp;

    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;

    private final int mMode;
    private WifiConfiguration mApConfig;

    private int mReportedFrequency = -1;
    private int mReportedBandwidth = -1;

    private int mNumAssociatedStations = 0;
    private int mQCNumAssociatedStations = 0;
    private boolean mTimeoutEnabled = false;
    private String[] mdualApInterfaces;

    /**
     * Listener for soft AP events.
     */
    private final SoftApListener mSoftApListener = new SoftApListener() {
        @Override
        public void onNumAssociatedStationsChanged(int numStations) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_NUM_ASSOCIATED_STATIONS_CHANGED, numStations);
        }

        @Override
        public void onSoftApChannelSwitched(int frequency, int bandwidth) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_SOFT_AP_CHANNEL_SWITCHED, frequency, bandwidth);
        }

        @Override
        public void onStaConnected(String Macaddr) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_CONNECTED_STATIONS, Macaddr);
        }

        @Override
        public void onStaDisconnected(String Macaddr) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_DISCONNECTED_STATIONS, Macaddr);
        }
    };

    public SoftApManager(@NonNull Context context,
                         @NonNull Looper looper,
                         @NonNull FrameworkFacade framework,
                         @NonNull WifiNative wifiNative,
                         String countryCode,
                         @NonNull WifiManager.SoftApCallback callback,
                         @NonNull WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         @NonNull WifiMetrics wifiMetrics) {
        mContext = context;
        mFrameworkFacade = framework;
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mCallback = callback;
        mWifiApConfigStore = wifiApConfigStore;
        mMode = apConfig.getTargetMode();
        WifiConfiguration config = apConfig.getWifiConfiguration();
        if (config == null) {
            mApConfig = mWifiApConfigStore.getApConfiguration();
        } else {
            mApConfig = config;
        }
        mWifiMetrics = wifiMetrics;
        mdualApInterfaces = new String[2];
        mStateMachine = new SoftApStateMachine(looper);
    }

    /**
     * Start soft AP with the supplied config.
     */
    public void start() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START, mApConfig);
    }

    /**
     * Stop soft AP.
     */
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        if (mApInterfaceName != null) {
            if (mIfaceIsUp) {
                updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                        WifiManager.WIFI_AP_STATE_ENABLED, 0);
            } else {
                updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                        WifiManager.WIFI_AP_STATE_ENABLING, 0);
            }
        }
        mStateMachine.quitNow();
    }

    /**
     * Dump info about this softap manager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of SoftApManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mApInterfaceName: " + mApInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        pw.println("mMode: " + mMode);
        pw.println("mCountryCode: " + mCountryCode);
        if (mApConfig != null) {
            pw.println("mApConfig.SSID: " + mApConfig.SSID);
            pw.println("mApConfig.apBand: " + mApConfig.apBand);
            pw.println("mApConfig.hiddenSSID: " + mApConfig.hiddenSSID);
        } else {
            pw.println("mApConfig: null");
        }
        pw.println("mNumAssociatedStations: " + mNumAssociatedStations);
        pw.println("mTimeoutEnabled: " + mTimeoutEnabled);
        pw.println("mReportedFrequency: " + mReportedFrequency);
        pw.println("mReportedBandwidth: " + mReportedBandwidth);
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update AP state.
     * @param newState new AP state
     * @param currentState current AP state
     * @param reason Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        mCallback.onStateChanged(newState, reason);

        //send the AP state change broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, currentState);
        if (newState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        intent.putExtra(WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME, mDataInterfaceName);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mMode);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Start a soft AP instance with the given configuration.
     * @param config AP configuration
     * @return integer result code
     */
    private int startSoftAp(WifiConfiguration config) {
        if (config == null || config.SSID == null) {
            Log.e(TAG, "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }

        // Make a copy of configuration for updating AP band and channel.
        WifiConfiguration localConfig = new WifiConfiguration(config);

        int result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCountryCode,
                mWifiApConfigStore.getAllowed2GChannel(), localConfig);

        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        }

        // Setup country code if it is provided.
        if (mCountryCode != null) {
            // Country code is mandatory for 5GHz band, return an error if failed to set
            // country code when AP is configured for 5GHz band.
            if (!mWifiNative.setCountryCodeHal(
                    mApInterfaceName, mCountryCode.toUpperCase(Locale.ROOT))
                    && config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                Log.e(TAG, "Failed to set country code, required for setting up "
                        + "soft ap in 5GHz");
                return ERROR_GENERIC;
            }
        }
        if (localConfig.hiddenSSID) {
            Log.d(TAG, "SoftAP is a hidden network");
        }
        if (!mWifiNative.startSoftAp(mApInterfaceName, localConfig, mSoftApListener)) {
            Log.e(TAG, "Soft AP start failed");
            return ERROR_GENERIC;
        }
        Log.d(TAG, "Soft AP is started");

        return SUCCESS;
    }

    /**
     * Teardown soft AP and teardown the interface.
     */
    private void stopSoftAp() {
        if (mWifiApConfigStore.getDualSapStatus()) {
            mWifiNative.teardownInterface(mdualApInterfaces[0]);
            mWifiNative.teardownInterface(mdualApInterfaces[1]);
        }
        mWifiNative.teardownInterface(mApInterfaceName);
        Log.d(TAG, "Soft AP is stopped");
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_NUM_ASSOCIATED_STATIONS_CHANGED = 4;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT = 5;
        public static final int CMD_TIMEOUT_TOGGLE_CHANGED = 6;
        public static final int CMD_INTERFACE_DESTROYED = 7;
        public static final int CMD_INTERFACE_DOWN = 8;
        public static final int CMD_SOFT_AP_CHANNEL_SWITCHED = 9;
        public static final int CMD_CONNECTED_STATIONS = 10;
        public static final int CMD_DISCONNECTED_STATIONS = 11;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mDataInterfaceName != null && mDataInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mDataInterfaceName != null && mDataInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mDataInterfaceName != null && mDataInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        private final InterfaceCallback mWifiNativeDualIfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) { }

            @Override
            public void onUp(String ifaceName) { }

            @Override
            public void onDown(String ifaceName) { }
        };

        /**
         * Start Dual soft AP.
         */
        private boolean setupForDualSoftApMode(WifiConfiguration config) {
            mdualApInterfaces[0] = mWifiNative.setupInterfaceForSoftApMode(
                    mWifiNativeDualIfaceCallback);
            mdualApInterfaces[1] = mWifiNative.setupInterfaceForSoftApMode(
                    mWifiNativeDualIfaceCallback);

            String bridgeIfacename = mWifiNative.setupInterfaceForBridgeMode(
                    mWifiNativeInterfaceCallback);

            mApInterfaceName = bridgeIfacename;
            if (TextUtils.isEmpty(mdualApInterfaces[0]) ||
                    TextUtils.isEmpty(mdualApInterfaces[1]) ||
                    TextUtils.isEmpty(mApInterfaceName)) {
                Log.e(TAG, "setup failure when creating dual ap interface(s).");
                updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                        WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.SAP_START_FAILURE_GENERAL);
                mWifiMetrics.incrementSoftApStartResult(false,
                        WifiManager.SAP_START_FAILURE_GENERAL);
                return false;
            }
            mDataInterfaceName = mWifiNative.getFstDataInterfaceName();
            if (TextUtils.isEmpty(mDataInterfaceName)) {
                mDataInterfaceName = mApInterfaceName;
            }
            updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                    WifiManager.WIFI_AP_STATE_DISABLED, 0);

            WifiConfiguration localConfig = new WifiConfiguration(config);
            mApInterfaceName = mdualApInterfaces[0];
            localConfig.apBand =  WifiConfiguration.AP_BAND_2GHZ;
            int result = startSoftAp(localConfig);
            if (result == SUCCESS) {
                localConfig.apBand =  WifiConfiguration.AP_BAND_5GHZ;
                mApInterfaceName = mdualApInterfaces[1];
                result = startSoftAp(localConfig);
            }

            mApInterfaceName = bridgeIfacename;
            if (result != SUCCESS) {
                int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                if (result == ERROR_NO_CHANNEL) {
                    failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                }
                updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                              WifiManager.WIFI_AP_STATE_ENABLING,
                              failureReason);
                stopSoftAp();
                mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                return false;
            }
            if (!mWifiNative.setHostapdParams("softap bridge up " +mApInterfaceName)) {
               Log.e(TAG, "Failed to set interface up " +mApInterfaceName);
               return false;
            }

            return true;
        }

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mApInterfaceName = null;
                mDataInterfaceName = null;
                mIfaceIsUp = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        WifiConfiguration config = (WifiConfiguration) message.obj;
                        if (config != null && config.apBand == WifiConfiguration.AP_BAND_DUAL) {
                            if (!setupForDualSoftApMode(config)) {
                                Log.d(TAG, "Dual Sap start failed");
                                break;
                            }
                            transitionTo(mStartedState);
                            break;
                        }

                        mApInterfaceName = mWifiNative.setupInterfaceForSoftApMode(
                                mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mApInterfaceName)) {
                            Log.e(TAG, "setup failure when creating ap interface.");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            break;
                        }
                        mDataInterfaceName = mWifiNative.getFstDataInterfaceName();
                        if (TextUtils.isEmpty(mDataInterfaceName)) {
                            mDataInterfaceName = mApInterfaceName;
                        }
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        int result = startSoftAp((WifiConfiguration) message.obj);
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            }
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                          WifiManager.WIFI_AP_STATE_ENABLING,
                                          failureReason);
                            stopSoftAp();
                            mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                            break;
                        }
                        transitionTo(mStartedState);
                        break;
                    default:
                        // Ignore all other commands.
                        break;
                }

                return HANDLED;
            }
        }

        private class StartedState extends State {
            private int mTimeoutDelay;
            private WakeupMessage mSoftApTimeoutMessage;
            private SoftApTimeoutEnabledSettingObserver mSettingObserver;

            /**
            * Observer for timeout settings changes.
            */
            private class SoftApTimeoutEnabledSettingObserver extends ContentObserver {
                SoftApTimeoutEnabledSettingObserver(Handler handler) {
                    super(handler);
                }

                public void register() {
                    mFrameworkFacade.registerContentObserver(mContext,
                            Settings.Global.getUriFor(Settings.Global.SOFT_AP_TIMEOUT_ENABLED),
                            true, this);
                    mTimeoutEnabled = getValue();
                }

                public void unregister() {
                    mFrameworkFacade.unregisterContentObserver(mContext, this);
                }

                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    mStateMachine.sendMessage(SoftApStateMachine.CMD_TIMEOUT_TOGGLE_CHANGED,
                            getValue() ? 1 : 0);
                }

                private boolean getValue() {
                    boolean enabled = mFrameworkFacade.getIntegerSetting(mContext,
                            Settings.Global.SOFT_AP_TIMEOUT_ENABLED, 1) == 1;
                    return enabled;
                }
            }

            private int getConfigSoftApTimeoutDelay() {
                int delay = mContext.getResources().getInteger(
                        R.integer.config_wifi_framework_soft_ap_timeout_delay);
                if (delay < MIN_SOFT_AP_TIMEOUT_DELAY_MS) {
                    delay = MIN_SOFT_AP_TIMEOUT_DELAY_MS;
                    Log.w(TAG, "Overriding timeout delay with minimum limit value");
                }
                Log.d(TAG, "Timeout delay: " + delay);
                return delay;
            }

            private void scheduleTimeoutMessage() {
                if (!mTimeoutEnabled) {
                    return;
                }
                mSoftApTimeoutMessage.schedule(SystemClock.elapsedRealtime() + mTimeoutDelay);
                Log.d(TAG, "Timeout message scheduled");
            }

            private void cancelTimeoutMessage() {
                mSoftApTimeoutMessage.cancel();
                Log.d(TAG, "Timeout message canceled");
            }

            /**
             * Set number of stations associated with this soft AP
             * @param numStations Number of connected stations
             */
            private void setNumAssociatedStations(int numStations) {
                if (mNumAssociatedStations == numStations) {
                    return;
                }
                mNumAssociatedStations = numStations;
                Log.d(TAG, "Number of associated stations changed: " + mNumAssociatedStations);

                if (mCallback != null) {
                    mCallback.onNumClientsChanged(mNumAssociatedStations);
                } else {
                    Log.e(TAG, "SoftApCallback is null. Dropping NumClientsChanged event.");
                }
                mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(mNumAssociatedStations,
                        mMode);

                if (mNumAssociatedStations == 0) {
                    scheduleTimeoutMessage();
                } else {
                    cancelTimeoutMessage();
                }
            }

            /**
             * Set New connected stations with this soft AP
             * @param Macaddr Mac address of connected stations
             */
            private void setConnectedStations(String Macaddr) {

                mQCNumAssociatedStations++;
                if (mCallback != null) {
                    mCallback.onStaConnected(Macaddr,mQCNumAssociatedStations);
                } else {
                    Log.e(TAG, "SoftApCallback is null. Dropping onStaConnected event.");
                }
            }

            /**
             * Set Disconnected stations with this soft AP
             * @param Macaddr Mac address of Disconnected stations
             */
            private void setDisConnectedStations(String Macaddr) {

                if (mQCNumAssociatedStations > 0)
                     mQCNumAssociatedStations--;
                if (mCallback != null) {
                    mCallback.onStaDisconnected(Macaddr, mQCNumAssociatedStations);
                } else {
                    Log.e(TAG, "SoftApCallback is null. Dropping onStaDisconnected event.");
                }
            }

           private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "SoftAp is ready for use");
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                    if (mCallback != null) {
                        mCallback.onNumClientsChanged(mNumAssociatedStations);
                        mCallback.onStaConnected("", mQCNumAssociatedStations);
                    }
                } else {
                    // the interface was up, but goes down
                    sendMessage(CMD_INTERFACE_DOWN);
                }
                mWifiMetrics.addSoftApUpChangedEvent(isUp, mMode);
            }

            @Override
            public void enter() {
                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mDataInterfaceName));

                mTimeoutDelay = getConfigSoftApTimeoutDelay();
                Handler handler = mStateMachine.getHandler();
                mSoftApTimeoutMessage = new WakeupMessage(mContext, handler,
                        SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG,
                        SoftApStateMachine.CMD_NO_ASSOCIATED_STATIONS_TIMEOUT);
                mSettingObserver = new SoftApTimeoutEnabledSettingObserver(handler);

                if (mSettingObserver != null) {
                    mSettingObserver.register();
                }
                Log.d(TAG, "Resetting num stations on start");
                mNumAssociatedStations = 0;
                mQCNumAssociatedStations = 0;
                scheduleTimeoutMessage();
            }

            @Override
            public void exit() {
                if (mApInterfaceName != null) {
                    stopSoftAp();
                }
                if (mSettingObserver != null) {
                    mSettingObserver.unregister();
                }
                Log.d(TAG, "Resetting num stations on stop");
                mNumAssociatedStations = 0;
                mQCNumAssociatedStations = 0;
                cancelTimeoutMessage();
                // Need this here since we are exiting |Started| state and won't handle any
                // future CMD_INTERFACE_STATUS_CHANGED events after this point
                mWifiMetrics.addSoftApUpChangedEvent(false, mMode);
                updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.WIFI_AP_STATE_DISABLING, 0);
                mApInterfaceName = null;
                mDataInterfaceName = null;
                mIfaceIsUp = false;
                mStateMachine.quitNow();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_NUM_ASSOCIATED_STATIONS_CHANGED:
                        if (message.arg1 < 0) {
                            Log.e(TAG, "Invalid number of associated stations: " + message.arg1);
                            break;
                        }
                        Log.d(TAG, "Setting num stations on CMD_NUM_ASSOCIATED_STATIONS_CHANGED");
                        setNumAssociatedStations(message.arg1);
                        break;
                    case CMD_SOFT_AP_CHANNEL_SWITCHED:
                        mReportedFrequency = message.arg1;
                        mReportedBandwidth = message.arg2;
                        Log.d(TAG, "Channel switched. Frequency: " + mReportedFrequency
                                + " Bandwidth: " + mReportedBandwidth);
                        mWifiMetrics.addSoftApChannelSwitchedEvent(mReportedFrequency,
                                mReportedBandwidth, mMode);
                        int[] allowedChannels = new int[0];
                        if (mApConfig.apBand == WifiConfiguration.AP_BAND_2GHZ) {
                            allowedChannels =
                                    mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
                        } else if (mApConfig.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                            allowedChannels =
                                    mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
                        } else if (mApConfig.apBand == WifiConfiguration.AP_BAND_ANY) {
                            int[] allowed2GChannels =
                                    mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
                            int[] allowed5GChannels =
                                    mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
                            allowedChannels = Stream.concat(
                                    Arrays.stream(allowed2GChannels).boxed(),
                                    Arrays.stream(allowed5GChannels).boxed())
                                    .mapToInt(Integer::valueOf)
                                    .toArray();
                        }
                        if (!ArrayUtils.contains(allowedChannels, mReportedFrequency)) {
                            Log.e(TAG, "Channel does not satisfy user band preference: "
                                    + mReportedFrequency);
                            mWifiMetrics.incrementNumSoftApUserBandPreferenceUnsatisfied();
                        }
                        break;
                    case CMD_CONNECTED_STATIONS:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid Macaddr of connected station: " + message.obj);
                            break;
                        }
                        Log.d(TAG, "Setting Macaddr of stations on CMD_CONNECTED_STATIONS");
                        setConnectedStations((String) message.obj);
                        break;
                    case CMD_DISCONNECTED_STATIONS:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid Macaddr of disconnected station: " + message.obj);
                            break;
                        }
                        Log.d(TAG, "Setting Macaddr of stations on CMD_DISCONNECTED_STATIONS");
                        setDisConnectedStations((String) message.obj);
                        break;
                    case CMD_TIMEOUT_TOGGLE_CHANGED:
                        boolean isEnabled = (message.arg1 == 1);
                        if (mTimeoutEnabled == isEnabled) {
                            break;
                        }
                        mTimeoutEnabled = isEnabled;
                        if (!mTimeoutEnabled) {
                            cancelTimeoutMessage();
                        }
                        if (mTimeoutEnabled && mNumAssociatedStations == 0) {
                            scheduleTimeoutMessage();
                        }
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_NO_ASSOCIATED_STATIONS_TIMEOUT:
                        if (!mTimeoutEnabled) {
                            Log.wtf(TAG, "Timeout message received while timeout is disabled."
                                    + " Dropping.");
                            break;
                        }
                        if (mNumAssociatedStations != 0) {
                            Log.wtf(TAG, "Timeout message received but has clients. Dropping.");
                            break;
                        }
                        Log.i(TAG, "Timeout message received. Stopping soft AP.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "Interface was cleanly destroyed.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        mApInterfaceName = null;
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        Log.w(TAG, "interface error, stop and report failure");
                        updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                WifiManager.WIFI_AP_STATE_ENABLED,
                                WifiManager.SAP_START_FAILURE_GENERAL);
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_FAILED, 0);
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
