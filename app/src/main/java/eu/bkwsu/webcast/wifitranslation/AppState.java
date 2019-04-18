package eu.bkwsu.webcast.wifitranslation;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static android.content.Context.MODE_PRIVATE;

/**
 * Created by simonb on 09/01/18.
 */

// Representation of the application state
final class AppState  {
    private static final String TAG = "AppState";
    private static final int PREFS_THREAD_PERIOD_MS = 2000;

    static class Chan {
        String name;
        int viewId;
        boolean busy;
        boolean open;
        boolean valid;
        List<String> allowedIds = new ArrayList<>();
    }

    //*****************************************
    //***** Persistent desired state **********
    //*****************************************
    volatile String uuid = "";

    volatile int selectedMainChannel = 0;
    volatile int selectedRelayChannel = 0;

    //True is TX, false is RX
    volatile boolean txMode = false;
    //Only applies to TX mode, true = relay, false is no relay
    volatile boolean relayMode = false;

    //TX Auto-level control user parameters
    volatile float maxGainDb = 0.0f;
    volatile float increaseDbPerSecond = 0.0f;
    volatile float holdTimeSeconds = 0.0f;
    volatile int networkPacketRedundancy = 0;

    //*****************************************
    //***** Non-persistent desired state ******
    //*****************************************
    volatile boolean mute = false;
    volatile boolean happening = false;
    volatile boolean headphonesMandatory = false;
    volatile boolean channelsManaged = false;
    volatile Map<Integer, Chan> channelMap = new ConcurrentHashMap<>();

    //*****************************************
    //***** Non-persistent reported state *****
    //*****************************************
    volatile boolean mainChannelFree = false;
    volatile boolean relayChannelFree = false;
    volatile boolean rxBusy = true;
    volatile boolean rxValid = false;
    volatile boolean txEnabled = false;
    volatile boolean rxMulticastMode = true;
    volatile boolean rxMulticastOk = false;
    volatile boolean rxMulticastTested = false;
    volatile boolean rxRtspOk = false;
    volatile boolean rxRtspTested = false;
    volatile boolean stateInitialised = false;
    volatile boolean headphones = false;
    volatile boolean appIsVisible = true;
    volatile boolean wifiOn = false;

    private static Context context;
    private int maxChannels;
    private SharedPreferences prefs = null;
    private TranslationTX translationTx = null;

    private boolean prefThreadRunning = false;

    private AppState thisObj = this;

    AppState() {

    }
    AppState(Context initContext, String prefDef, TranslationTX translationTxObj, int initMaxChannels) {
        maxChannels = initMaxChannels;
        context = initContext;
        prefs = context.getSharedPreferences(prefDef, MODE_PRIVATE);
        translationTx = translationTxObj;

        channelMap = defaultChannels();
        clipChannelToMapSize();
    }



    Map<Integer, Chan> defaultChannels () {
        Map<Integer, Chan> defaultChannelMap = new ConcurrentHashMap<>();

        for (int i =0; i < maxChannels; i++) {
            defaultChannelMap.put(i, defaultChannel(i));
        }
        return defaultChannelMap;
    }
    static AppState.Chan defaultChannel(int chanNum) {
        AppState.Chan chan = new AppState.Chan();
        chan.name = String.format("%s%4d", MainActivity.context.getString(R.string.channel_text), chanNum + 1);
        chan.viewId = -1;
        chan.busy = false;
        chan.valid = false;
        chan.allowedIds.add("-");
        return chan;
    }

    void fetchChannelMap () {
        if (HubComms.getChannelMap() != null) {
            channelsManaged = true;
            Map<Integer, Chan> newChannelMap = new ConcurrentHashMap<>(HubComms.getChannelMap());
            //Copy old ViewIds to received channel map
            for(Map.Entry<Integer, Chan> thisPair : newChannelMap.entrySet()) {
                Chan targetChan = thisPair.getValue();
                Chan thisChan = channelMap.get(thisPair.getKey().intValue());
                if ((thisChan != null) && (targetChan != null)){
                    targetChan.viewId = thisChan.viewId;
                } else {
                    targetChan.viewId = -1;
                }
            }
            channelMap = newChannelMap;
        } else {
            channelMap = defaultChannels();
            channelsManaged = false;
        }
        clipChannelToMapSize();
    }

    private void clipChannelToMapSize () {
        // Reset to first channel if new channel range is less than selected channel
        if (selectedMainChannel >= channelMap.size()) {
            selectedMainChannel = 0;
        }
        if (selectedRelayChannel >= channelMap.size()) {
            selectedRelayChannel = 0;
        }
    }

    //Compare the full state
    boolean equals (AppState compareWith) {
        return persistEquals(compareWith) && transientEquals(compareWith) && reportedEquals(compareWith);
    }
    //Compare the desired state
    boolean desiredEquals (AppState compareWith) {
        return persistEquals(compareWith) && transientEquals(compareWith);
    }
    //Compare the state of persistent state
    boolean persistEquals (AppState compareWith) {
        if (selectedMainChannel != compareWith.selectedMainChannel) {
            return false;
        }
        if (selectedRelayChannel != compareWith.selectedRelayChannel) {
            return false;
        }
        if (txMode != compareWith.txMode) {
            return false;
        }
        if (relayMode != compareWith.relayMode) {
            return false;
        }
        if (maxGainDb != compareWith.maxGainDb) {
            return false;
        }
        if (increaseDbPerSecond != compareWith.increaseDbPerSecond) {
            return false;
        }
        if (holdTimeSeconds != compareWith.holdTimeSeconds) {
            return false;
        }
        if (networkPacketRedundancy != compareWith.networkPacketRedundancy) {
            return false;
        }
        return true;
    }
    //Compare the transient state
    boolean transientEquals (AppState compareWith) {
        if (mute != compareWith.mute) {
            return false;
        }
        if (happening != compareWith.happening) {
            return false;
        }
        if (headphonesMandatory != compareWith.headphonesMandatory) {
            return false;
        }
        if (channelsManaged != compareWith.channelsManaged) {
            return false;
        }
        if (channelMap != null) {
            for (Map.Entry<Integer, Chan> thisPair : channelMap.entrySet()) {
                Chan thisChan = thisPair.getValue();
                Chan thatChan = compareWith.channelMap.get(thisPair.getKey().intValue());
                if ((thatChan == null) ||
                        (thisChan.open != thatChan.open) ||
                        (!thisChan.name.equals(thatChan.name)) ||
                        (thisChan.viewId != thatChan.viewId)) {
                    return false;
                }
            }
        }
        return true;
    }
    //Compare the reported state
    boolean reportedEquals (AppState compareWith) {
        if (mainChannelFree != compareWith.mainChannelFree) {
            return false;
        }
        if (relayChannelFree != compareWith.relayChannelFree) {
            return false;
        }
        if (rxBusy != compareWith.rxBusy) {
            return false;
        }
        if (rxValid != compareWith.rxValid) {
            return false;
        }
        if (txEnabled != compareWith.txEnabled) {
            return false;
        }
        if (rxMulticastMode != compareWith.rxMulticastMode) {
            return false;
        }
        if (rxMulticastOk != compareWith.rxMulticastOk) {
            return false;
        }
        if (rxMulticastTested != compareWith.rxMulticastTested) {
            return false;
        }
        if (rxRtspOk != compareWith.rxRtspOk) {
            return false;
        }
        if (rxRtspTested != compareWith.rxRtspTested) {
            return false;
        }
        if (headphones != compareWith.headphones) {
            return false;
        }
        if (appIsVisible != compareWith.appIsVisible) {
            return false;
        }
        if (wifiOn != compareWith.wifiOn) {
            return false;
        }
        //Deep compare
        if (!channelsManaged && (channelMap != null)) {
            for (Map.Entry<Integer, Chan> thisPair : channelMap.entrySet()) {
                Chan thisChan = thisPair.getValue();
                Chan thatChan = compareWith.channelMap.get(thisPair.getKey().intValue());
                if ((thatChan == null) ||
                        (thisChan.valid != thatChan.valid) ||
                        (thisChan.busy != thatChan.busy)) {
                    return false;
                }
            }
        }
        return true;
    }

    void readPrefs () {
        //Do nothing if not constructed to handle prefs
        if (prefs == null) {
            return;
        }

        //Restore preferences
        uuid = prefs.getString("uuid", UUID.randomUUID().toString());
        selectedMainChannel = prefs.getInt("channel", 0);
        selectedRelayChannel = prefs.getInt("relayChannel", 0);
        txMode = prefs.getBoolean("txMode", false);
        relayMode = prefs.getBoolean("relayMode", false);
        maxGainDb = prefs.getFloat("txMaxGainDb", translationTx.getMaxGainDb());
        increaseDbPerSecond = prefs.getFloat("txIncreaseDbPerSecond", translationTx.getIncreaseDbPerSecond());
        holdTimeSeconds = prefs.getFloat("txHoldTimeSeconds", translationTx.getHoldTimeSeconds());
        networkPacketRedundancy = prefs.getInt("txNetworkPacketRedundancy", translationTx.getNetworkPacketRedundancy());

        //Start preference monitoring thread if not yet started
        if (!prefThreadRunning) {
            prefThreadRunning = true;
            prefsThread.start();
        }
        stateInitialised = true;
    }

    void writePrefs () {
        SharedPreferences.Editor editor;

        //Do nothing if not constructed to handle prefs
        if (prefs == null) {
            return;
        }

        editor = prefs.edit();

        editor.putString("uuid", uuid);
        editor.putInt("channel",selectedMainChannel);
        editor.putInt("relayChannel",selectedRelayChannel);
        editor.putBoolean("txMode", txMode);
        editor.putBoolean("relayMode", relayMode);
        editor.putFloat("txMaxGainDb", maxGainDb);
        editor.putFloat("txIncreaseDbPerSecond", increaseDbPerSecond);
        editor.putFloat("txHoldTimeSeconds", holdTimeSeconds);
        editor.putInt("txNetworkPacketRedundancy", networkPacketRedundancy);

        editor.commit();
    }
    //Monitor state changes and write to prefs if changed
    private Thread prefsThread = new Thread() {
        public void run() {
            Log.d(TAG, "Preference monitor thread started");
            AppState prevState = new AppState();

            //Set to current values to start with
            copyAllTo(prevState);

            while ((!Thread.currentThread().isInterrupted()) && prefThreadRunning) {
                if (! thisObj.persistEquals(prevState)) {
                    Log.d(TAG, "Preferences update");
                    writePrefs();
                    copyAllTo(prevState);
                }
                //Sleep for a bit
                try {
                    Thread.sleep(PREFS_THREAD_PERIOD_MS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Preference monitor thread interrupted");
                }
            }
        }
    };
    void copyAllTo (AppState targetState) {
        copyDesiredTo(targetState);
        copyReportedTo(targetState);
    }
    void copyDesiredTo (AppState targetState) {
        targetState.uuid = uuid;
        targetState.selectedMainChannel = selectedMainChannel;
        targetState.selectedRelayChannel = selectedRelayChannel;
        targetState.txMode = txMode;
        targetState.relayMode = relayMode;
        targetState.maxGainDb = maxGainDb;
        targetState.increaseDbPerSecond = increaseDbPerSecond;
        targetState.holdTimeSeconds = holdTimeSeconds;
        targetState.networkPacketRedundancy = networkPacketRedundancy;
        targetState.mute = mute;
        targetState.happening = happening;
        targetState.headphonesMandatory = headphonesMandatory;
        targetState.channelsManaged = channelsManaged;
        //Copy valid and busy states back unless managed
        if (!channelsManaged) {
            for (Map.Entry<Integer, Chan> thisPair : channelMap.entrySet()) {
                Chan thisChan = thisPair.getValue();
                Chan targetChan = targetState.channelMap.get(thisPair.getKey().intValue());
                if (targetChan != null) {
                    thisChan.busy = targetChan.busy;
                    thisChan.valid = targetChan.valid;
                }
            }
        }
        targetState.channelMap = null;
        targetState.channelMap = new ConcurrentHashMap<>(channelMap);
    }
    void copyReportedTo (AppState targetState) {
        targetState.mainChannelFree = mainChannelFree;
        targetState.relayChannelFree = relayChannelFree;
        targetState.rxBusy = rxBusy;
        targetState.rxValid = rxValid;
        targetState.txEnabled = txEnabled;
        targetState.rxMulticastMode = rxMulticastMode;
        targetState.rxMulticastOk = rxMulticastOk;
        targetState.rxMulticastTested = rxMulticastTested;
        targetState.rxRtspOk = rxRtspOk;
        targetState.rxRtspTested = rxRtspTested;
        targetState.headphones = headphones;
        targetState.appIsVisible = appIsVisible;
        targetState.wifiOn = wifiOn;
        //Copy over existing only for affected properties
        if (!channelsManaged && !targetState.channelsManaged && channelMap != null) {
            for (Map.Entry<Integer, Chan> thisPair : channelMap.entrySet()) {
                Chan thisChan = thisPair.getValue();
                Chan targetChan = targetState.channelMap.get(thisPair.getKey().intValue());
                if (targetChan != null) {
                    targetChan.valid = thisChan.valid;
                    targetChan.busy = thisChan.busy;
                }
            }
        }
    }

    //Merge Hub status into state


}
