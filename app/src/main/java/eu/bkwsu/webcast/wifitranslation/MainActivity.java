package eu.bkwsu.webcast.wifitranslation;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static android.view.View.generateViewId;

public class MainActivity extends AppCompatActivity {

    private static String WAKELOCK_TAG;
    private static final String TAG = "MultTrsltnMainActivity";
    private static int BUILD_VERSION = Build.VERSION.SDK_INT;
    private static final int REQUEST_CODE = 0x2932;
    private static String PREF_DEF;
    private static int DISPLAY_THREAD_MS;
    private static int ACTION_THREAD_MS;
    private static boolean ALLOW_LOOPBACK;
    private static int MAX_CHANNELS;

    //Global variables
    public static Context context;
    //Used to head-count receivers
    public static String uuid;

    private static final Properties prop = new Properties();

    private static View optionsPanelLayout;

    private static volatile boolean relaySelect = false;
    private static boolean micPermission = true;
    private static boolean happenAfterMicPermission = false;
    private static TranslationRX translationRx;
    private static TranslationTX translationTx;
    private static ManagementRX managementRX;
    private static HubComms hubComms;

    //How long to stay waiting while playing for new valid packets
    private static int RX_WAIT_TIMEOUT_MIN;

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    //App states
    private AppState desiredState, displayState = new AppState(), activeState = new AppState();

    private static SurfaceHolder vuHolder;

    private static VuMeter vuMeter;

    //Sleep management
    private Object mPauseLock = new Object();
    private boolean mPaused = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {


        try {
            //load the properties file
            prop.load(getAssets().open("app.properties"));
        } catch (IOException ex) {
            throw new IllegalStateException("IOException " + ex.toString());
        }
        PREF_DEF = prop.getProperty("PREF_DEF");
        DISPLAY_THREAD_MS = Integer.parseInt(prop.getProperty("DISPLAY_THREAD_MS"));
        ACTION_THREAD_MS = Integer.parseInt(prop.getProperty("ACTION_THREAD_MS"));
        ALLOW_LOOPBACK = Boolean.parseBoolean(prop.getProperty("ALLOW_LOOPBACK"));
        RX_WAIT_TIMEOUT_MIN = Integer.parseInt(prop.getProperty("RX_WAIT_TIMEOUT_MIN"));
        MAX_CHANNELS = Integer.parseInt(prop.getProperty("MAX_CHANNELS"));
        WAKELOCK_TAG = prop.getProperty("WAKELOCK_TAG");

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);


        context = this;

        noDoze();

        translationRx = new TranslationRX(prop);
        translationTx = new TranslationTX(prop);
        managementRX = new ManagementRX(prop);
        hubComms = new HubComms(prop);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        final SurfaceView vuMeterSurface = (SurfaceView) findViewById(R.id.vuMeter);
        vuMeterSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder vuHolderParam) {
                Log.d(TAG, "VU Meter surface created");
                vuHolder = vuHolderParam;

                vuMeter = new VuMeter(vuHolder);

                vuMeter.init();
            }

            @Override
            public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                Log.d(TAG, "First surface destroyed!");
            }
        });

        //Restore preferences
        desiredState = new AppState(this, PREF_DEF, translationTx, MAX_CHANNELS);
        desiredState.readPrefs();
        desiredState.writePrefs();
        uuid = desiredState.uuid;

        nudge_relay_channel();

        translationTx.setMaxGainDb(desiredState.maxGainDb);
        translationTx.setIncreaseDbPerSecond(desiredState.increaseDbPerSecond);
        translationTx.setHoldTimeSeconds(desiredState.holdTimeSeconds);
        translationTx.setNetworkPacketRedundancy(desiredState.networkPacketRedundancy);

        final Button buttonStop = (Button) findViewById(R.id.button_stop);
        buttonStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (statesOK()) {
                    Log.d(TAG, "Stop button was pressed");
                    desiredState.happening = false;
                    desiredState.mute = false;
                }
            }
        });
        final Button buttonChannelSelect = (Button) findViewById(R.id.button_channel);
        buttonChannelSelect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (statesOK()) {
                    Log.d(TAG, "Channel Selector was pressed");
                    relaySelect = false;
                    registerForContextMenu(v);
                    openContextMenu(v);
                }
            }
        });
        final Button buttonRelayChannelSelect = (Button) findViewById(R.id.button_relay_channel);
        buttonRelayChannelSelect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (statesOK()) {
                    Log.d(TAG, "Relay Channel Selector was pressed");
                    relaySelect = true;
                    registerForContextMenu(v);
                    openContextMenu(v);
                }
            }
        });
        final Button buttonStatus = (Button) findViewById(R.id.button_status);
        buttonStatus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (statesOK()) {
                    Log.d(TAG, "Status button was pressed");
                    canDoMulticast();
                    if (desiredState.txMode) {
                        //If already transmitting, then this button is a mute button
                        if (desiredState.happening) {
                            desiredState.mute = !desiredState.mute;
                            Log.d(TAG, "mute state requested : " + desiredState.mute);
                        } else {
                            //Only start transmitting if channel is free and mic permissions OK
                            if (micPermission) {
                                if (activeState.txEnabled){
                                    desiredState.happening = true;
                                    Log.d(TAG, "TX requested");
                                }
                            } else {
                                LayoutInflater noMicPermsInflater = getLayoutInflater();
                                View noMicPermsLayout = noMicPermsInflater.inflate(R.layout.no_mic_perms, null);
                                AlertDialog.Builder noMicPermsAlert = new AlertDialog.Builder(context);
                                noMicPermsAlert.setView(noMicPermsLayout);
                                AlertDialog noMicPermsDialog = noMicPermsAlert.create();
                                noMicPermsDialog.show();
                            }
                        }
                    } else {
                        //Only allow reception of valid audio and with headphones, if headphones mandated
                        if (activeState.rxValid && (!desiredState.headphonesMandatory || activeState.headphones)) {
                            desiredState.happening = true;
                            Log.d(TAG, "RX requested");
                        }
                    }
                }
            }
        });

        displayUpdateThreadStart();
        actionStateThreadStart();
        HubComms.pollHubStart();
        hubComms.broadcastStart();
        //managementRX.action(ManagementRX.Command.START); TEST TEST
        canDoMulticast();
    }

    //Options menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);

        return true;
    }
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem tx = menu.findItem(R.id.options_tx_switch);
        MenuItem relay = menu.findItem(R.id.options_tx_relay);
        MenuItem settings = menu.findItem(R.id.options_settings);

        //Can only change TX mode if in standby
        //If in RX mode then only if channel is free
        tx.setEnabled(! desiredState.happening &&
                (desiredState.txMode || activeState.txEnabled));

        tx.setChecked(desiredState.txMode);
        relay.setChecked(desiredState.relayMode);
        //Settings and relay only apply to TX mode
        relay.setVisible(desiredState.txMode);
        settings.setVisible(desiredState.txMode);

        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SharedPreferences.Editor editor;
        Button buttonRelay = (Button) findViewById(R.id.button_relay_channel);

        switch (item.getItemId()) {
            case R.id.options_tx_switch:
                desiredState.txMode = !desiredState.txMode;
                item.setChecked(desiredState.txMode);
                Log.d(TAG, "txMode set to " + desiredState.txMode);
                if (desiredState.relayMode) {
                    nudge_relay_channel ();
                }
                break;
            case R.id.options_tx_relay:
                desiredState.relayMode = !desiredState.relayMode;
                item.setChecked(desiredState.relayMode);
                Log.d(TAG, "relayMode set to " + desiredState.relayMode);
                nudge_relay_channel ();
                break;
            case R.id.options_settings:
                LayoutInflater inflater = getLayoutInflater();
                optionsPanelLayout = inflater.inflate(R.layout.settings_panel, null);
                AlertDialog.Builder alert = new AlertDialog.Builder(this);
                alert.setView(optionsPanelLayout);
                AlertDialog dialog = alert.create();
                dialog.show();

                setGainControlView(optionsPanelLayout);

                SeekBar.OnSeekBarChangeListener seekBars = new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        switch (seekBar.getId()) {
                            case R.id.gain_controls_max_gain_db_seekBar:
                                float maxGainDb = ((float)progress * (translationTx.getMaxGainDbMax() - translationTx.getMaxGainDbMin()) / 100.0f) + translationTx.getMaxGainDbMin();
                                TextView maxGainDbTextView = optionsPanelLayout.findViewById(R.id.gain_controls_max_gain_db_textview);
                                maxGainDbTextView.setText(String.format("%2.2f", maxGainDb));
                                desiredState.maxGainDb = maxGainDb;
                                Log.d(TAG, "maxGainDb set to " + maxGainDb);
                                break;
                            case R.id.gain_controls_increase_db_per_second_seekBar:
                                float increaseDbPerSecond = progressToLog(translationTx.getIncreaseDbPerSecondMin(), translationTx.getIncreaseDbPerSecondMax(), progress);
                                TextView increaseDbPerSecondTextView = optionsPanelLayout.findViewById(R.id.gain_controls_increase_db_per_second_textview);
                                increaseDbPerSecondTextView.setText(String.format("%2.2f", increaseDbPerSecond));
                                desiredState.increaseDbPerSecond = increaseDbPerSecond;
                                Log.d(TAG, "increaseDbPerSecond set to " + increaseDbPerSecond);
                                break;
                            case R.id.gain_controls_hold_time_seekBar:
                                float holdTimeSeconds = progressToLog(translationTx.getHoldTimeSecondsMin(), translationTx.getHoldTimeSecondsMax(), progress);
                                TextView holdTimeSecondsTextView = optionsPanelLayout.findViewById(R.id.gain_controls_hold_time_textview);
                                holdTimeSecondsTextView.setText(String.format("%2.2f", holdTimeSeconds));
                                desiredState.holdTimeSeconds = holdTimeSeconds;
                                Log.d(TAG, "holdTimeSeconds set to " + holdTimeSeconds);
                                break;
                            case R.id.network_controls_network_redundancy_seekbar:
                                TextView networkRedundancyTextView = optionsPanelLayout.findViewById(R.id.network_controls_network_redundancy_textview);
                                progress++;
                                networkRedundancyTextView.setText(String.format("%d", progress));
                                desiredState.networkPacketRedundancy = progress;
                                Log.d(TAG, "txnetworkRedundancy set to " + progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {

                    }
                };

                SeekBar maxGainDbSeekBar = optionsPanelLayout.findViewById(R.id.gain_controls_max_gain_db_seekBar);
                SeekBar increaseDbPerSecondSeekBar = optionsPanelLayout.findViewById(R.id.gain_controls_increase_db_per_second_seekBar);
                SeekBar holdTimeSecondsSeekBar = optionsPanelLayout.findViewById(R.id.gain_controls_hold_time_seekBar);
                SeekBar networkRedundancySeekBar = optionsPanelLayout.findViewById(R.id.network_controls_network_redundancy_seekbar);
                maxGainDbSeekBar.setOnSeekBarChangeListener(seekBars);
                increaseDbPerSecondSeekBar.setOnSeekBarChangeListener(seekBars);
                holdTimeSecondsSeekBar.setOnSeekBarChangeListener(seekBars);
                networkRedundancySeekBar.setOnSeekBarChangeListener(seekBars);
                break;
            case R.id.options_about:
                LayoutInflater aboutInflater = getLayoutInflater();
                View aboutPanelLayout = aboutInflater.inflate(R.layout.about, null);
                AlertDialog.Builder aboutAlert = new AlertDialog.Builder(this);
                aboutAlert.setView(aboutPanelLayout);
                AlertDialog aboutDialog = aboutAlert.create();
                aboutDialog.show();
                TextView versionTextView = aboutPanelLayout.findViewById(R.id.about_version);
                versionTextView.setText("Version: " + BuildConfig.VERSION_NAME);
                break;
        }
        return true;
    }


    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.channel_selector,menu);
        int currentChannel, otherChannel;

        currentChannel = (desiredState.relayMode & relaySelect)?desiredState.selectedRelayChannel:desiredState.selectedMainChannel;
        otherChannel = (relaySelect)?desiredState.selectedMainChannel:desiredState.selectedRelayChannel;

        //Read through the channels and display them. Assigning ids as we go.
        // TODO Channel IDs don't match when Hub disconnected and defaults re-established
        for(Map.Entry<Integer, AppState.Chan> pair : new TreeMap<>(desiredState.channelMap).entrySet()) {
            int newId = Tools.generateViewId();;
            AppState.Chan chan = pair.getValue();
            MenuItem nm = menu.add(R.id.channel_selector_group, newId, Menu.FLAG_APPEND_TO_GROUP, chan.name);
            nm.setCheckable(true);
            //If the channels are managed then enable/disable according to channel state
            if (desiredState.channelsManaged &&
                    !desiredState.txMode && !chan.valid &&
                    !chan.allowedIds.contains(uuid) && !chan.open) {
                    nm.setEnabled(false);
            }
            chan.viewId = newId;
            pair.setValue(chan);
        }
        menu.setGroupCheckable(R.id.channel_selector_group, true, true);
        final MenuItem item = menu.findItem(getChannelDisplayId(currentChannel));
        if (item != null) {
            item.setChecked(true);
        }
        if (desiredState.relayMode && desiredState.txMode && !ALLOW_LOOPBACK) {
            final MenuItem otherItem = menu.findItem(getChannelDisplayId(otherChannel));
            if (otherItem != null) {
                otherItem.setEnabled(false);
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int newChannel = -1;

        for (Map.Entry<Integer, AppState.Chan> pair : desiredState.channelMap.entrySet()) {
            AppState.Chan chan = pair.getValue();
            if (chan.viewId == item.getItemId()) {
                newChannel = pair.getKey().intValue();
                break;
            }
        }
        if (newChannel == -1) {
            return super.onContextItemSelected(item);
        }
        item.setChecked(true);
        if (desiredState.relayMode & relaySelect) {
            desiredState.selectedRelayChannel = newChannel;
            Log.d(TAG, "Relay channel " + (desiredState.selectedRelayChannel + 1) + " selected");

        } else {
            desiredState.selectedMainChannel = newChannel;
            Log.d(TAG, "Channel " + (desiredState.selectedMainChannel + 1) + " selected");
        }
        return true;
    }

    private int getChannelDisplayId (int channel) {
            AppState.Chan chan = desiredState.channelMap.get(channel);

            return (chan == null)?0:chan.viewId;
    }

    //Move relay channel out of the way if it clashes with main channel
    private void nudge_relay_channel () {
        if (!ALLOW_LOOPBACK && (desiredState.selectedRelayChannel == desiredState.selectedMainChannel)) {
            if (desiredState.selectedRelayChannel == 0) {
                desiredState.selectedRelayChannel++;
            } else {
                desiredState.selectedRelayChannel--;
            }
        }
    }

    @TargetApi(23)
    private void noDoze () {
        if (Build.VERSION.SDK_INT >= 23) {
            Log.i(TAG, "Requesting relief from battery optimisations");
            Intent intent = new Intent();
            String packageName = context.getPackageName();
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "Ignoring battery optimisations");
                //intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                //context.startActivity(intent);
            } else {
                Log.d(TAG, "Request to ignore battery optimisations");
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                context.startActivity(intent);
            }
        }
    }

    private void canDoMulticast() {
        File igmpFile = new File( "/proc/net/igmp" );
        if (!igmpFile.exists()) {
            LayoutInflater noMicPermsInflater = getLayoutInflater();
            View noMulticastLayout = noMicPermsInflater.inflate(R.layout.no_multicast, null);
            AlertDialog.Builder noMulticastAlert = new AlertDialog.Builder(context);
            noMulticastAlert.setView(noMulticastLayout);
            AlertDialog noMicPermsDialog = noMulticastAlert.create();
            noMicPermsDialog.show();
        }
    }

    //**********************************************************************
    //************************ Display state *******************************
    //**********************************************************************

    private Thread displayUpdateThread = new Thread() {
        public void run() {
            Log.d(TAG, "Display update thread starting");
            //Initialise display
            desiredState.copyDesiredTo(displayState);
            activeState.copyReportedTo(displayState);

            //Keep an eye on changes
            while (!Thread.currentThread().isInterrupted()) {
                AppState stateSnapshot = new AppState();

                desiredState.copyDesiredTo(stateSnapshot);
                activeState.copyReportedTo(stateSnapshot);
                if (!stateSnapshot.desiredEquals(displayState) || !stateSnapshot.reportedEquals(displayState)
                        || !displayState.stateInitialised) {


                    setMainChanText(stateSnapshot.selectedMainChannel);
                    setRelay(stateSnapshot);
                    setRelayVisibilty(stateSnapshot.txMode && stateSnapshot.relayMode);
                    setVuVisibility(stateSnapshot.txMode & stateSnapshot.happening);
                    setButton(stateSnapshot);
                    setMainChannelEnabled(!stateSnapshot.happening || !stateSnapshot.txMode);
                    setStopButtonVisibility(stateSnapshot.happening);

                    stateSnapshot.copyAllTo(displayState);
                    displayState.stateInitialised = true;
                }
                //Sleep for a bit
                try {
                    Thread.sleep(DISPLAY_THREAD_MS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Display status interrupted");
                }

                //Pause if requested
                synchronized (mPauseLock) {
                    while (mPaused) {
                        try {
                            mPauseLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Display status interrupted during pause");
                        }
                    }
                }
            }
        }
    };
    private void displayUpdateThreadStart() {
        displayUpdateThread.start();
    }

    //The "button" is the main button to start/stop/mute things
    private void setButton (AppState state) {
        Log.d(TAG, "Setting button state for txMode:" + state.txMode + ", mute:" +
                state.mute + ", rxTestedBusy:" + state.rxBusy + ", rxTestedValid :" +
                state.rxValid + ", txEnabled : " + state.txEnabled + ", wifi : " + state.wifiOn);
        if (!state.wifiOn) {
            setMainButtonColorText(Color.LTGRAY, getString(R.string.status_no_wifi));
            return;
        }
        if (state.txMode) {
            if (state.happening) {
                if (state.mute) {
                    setMainButtonColorText(Color.parseColor("teal"), getString(R.string.status_muted));
                } else {
                    setMainButtonColorText(Color.RED, getString(R.string.status_mute));
                }
            } else {
                if (state.txEnabled) {
                    setMainButtonColorText(Color.GREEN, getString(R.string.status_tx_start));
                } else {
                    setMainButtonColorText(Color.LTGRAY, getString(R.string.status_unavailable));
                }
            }
        } else {
            if (state.happening) {
                if (state.rxValid) {
                    setMainButtonColorText(Color.GREEN, getString(R.string.status_rx_run));
                } else {
                    setMainButtonColorText(Color.CYAN, getString(R.string.status_waiting));
                }
            } else {
                if (state.rxValid) {
                    if (state.headphonesMandatory && !state.headphones) {
                        setMainButtonColorText(Color.LTGRAY, getString(R.string.status_headphones));
                    } else {
                        setMainButtonColorText(Color.LTGRAY, getString(R.string.status_rx_start));
                    }
                } else {
                    setMainButtonColorText(Color.LTGRAY, getString(R.string.status_unavailable));
                }
            }
        }
    }
    private void setRelay (AppState state) {
        boolean visible = state.txMode && state.relayMode;

        if (visible) {
            if (state.happening) {
                if (state.rxValid) {
                    setRelayChanColorText(Color.GREEN, state.selectedRelayChannel);
                } else {
                    setRelayChanColorText(Color.CYAN, state.selectedRelayChannel);
                }
            } else {
                setRelayChanColorText(Color.TRANSPARENT, state.selectedRelayChannel);
            }
        }
        setRelayVisibilty(visible);
    }

    private void setMainChanText (int selectedChannel) {
        final TextView chanText = (TextView)findViewById(R.id.text_channel);;
        final AppState.Chan chan = desiredState.channelMap.get(selectedChannel);
        final String selectedChannelText = (chan == null)?"----":chan.name;
        runOnUiThread(new Runnable() {
            public void run() {
                chanText.setText(selectedChannelText);
            }
        });
    }
    private void setRelayChanColorText (int setColor, int selectedRelayChannel) {
        final TextView chanText = (TextView) findViewById(R.id.text_relay_channel);;
        final AppState.Chan chan = desiredState.channelMap.get(selectedRelayChannel);
        final String selectedRelayChannelText = chan.name;
        final int color = setColor;
        runOnUiThread(new Runnable() {
            public void run() {
                chanText.setBackgroundColor(color);
                chanText.setText(" " + selectedRelayChannelText + " ");
            }
        });
    }

    private void setMainButtonColorText (int setColor, String setText) {
        final TextView statusButton = (TextView)findViewById(R.id.button_status);
        final int color = setColor;
        final String text = setText;

        runOnUiThread(new Runnable() {
            public void run () {
                statusButton.setBackgroundColor(color);
                statusButton.setText(text);
            }
        });
    }
    private void setMainChannelEnabled (final boolean enabled) {
        runOnUiThread(new Runnable() {
            public void run () {
                findViewById(R.id.button_channel).setEnabled(enabled);
            }
        });
    }
    private void setStopButtonVisibility (final boolean visibility) {
        runOnUiThread(new Runnable() {
            public void run () {
                if (visibility) {
                    findViewById(R.id.button_stop).setVisibility(View.VISIBLE);
                } else {
                    findViewById(R.id.button_stop).setVisibility(View.INVISIBLE);
                }
            }
        });
    }
    private void setVuVisibility (final boolean visibility) {
        runOnUiThread(new Runnable() {
            public void run () {
                if (visibility) {
                    findViewById(R.id.vuMeter).setVisibility(View.VISIBLE);
                } else {
                    findViewById(R.id.vuMeter).setVisibility(View.INVISIBLE);
                }
            }
        });
    }
    private void setRelayVisibilty (final boolean visibilty) {
        final Button buttonRelay = (Button) findViewById(R.id.button_relay_channel);
        final TextView chanText = (TextView)findViewById(R.id.text_relay_channel);
        runOnUiThread(new Runnable() {
            public void run() {
                buttonRelay.setVisibility((visibilty) ? View.VISIBLE : View.GONE);
                chanText.setVisibility((visibilty) ? View.VISIBLE : View.GONE);
            }
        });
    }


    //**********************************************************************
    //************************ Active state *******************************
    //**********************************************************************

    private Thread actionStateThread = new Thread() {
        public void run() {
            Log.d(TAG, "Active state thread starting");
            //Initialise display
            desiredState.copyDesiredTo(activeState);
            TranslationRX.Status rxState;
            TranslationTX.Status txState;
            boolean rxTestedBusy = false;
            boolean rxTestedValid = false;
            boolean rxTestMulticastMode = true;
            boolean rxTestComplete = false;
            boolean rxReTest = false;

            long rxLastGoodTime = System.currentTimeMillis();

            //Keep an eye on changes
            while (!Thread.currentThread().isInterrupted()) {

                AppState stateSnapshot = new AppState();
                rxState = translationRx.state();
                txState = translationTx.state();
                //Stop if no Wi-fi and not rebooting wi-fi
                if (!Tools.isWifiOn() && stateSnapshot.happening) {
                    Log.d(TAG, "Stopping all due to no Wifi");
                    desiredState.happening = false;
                }

                desiredState.fetchChannelMap();
                desiredState.copyDesiredTo(stateSnapshot);
                activeState.copyReportedTo(stateSnapshot);
                if (stateSnapshot.channelsManaged != activeState.channelsManaged) {
                    translationRx.setChannelsManaged(desiredState.channelsManaged);
                    translationTx.setChannelsManaged(desiredState.channelsManaged);
                    rxReTest = true;
                }
                if (!stateSnapshot.desiredEquals(activeState) || !activeState.stateInitialised) {

                    if (stateSnapshot.happening != activeState.happening) {
                        if (stateSnapshot.happening) {
                            Log.d(TAG, "Aquiring WakeLock");
                            wakeLock.acquire();
                            if (stateSnapshot.txMode) {
                                Log.d(TAG, "Requesting keep screen on");
                                setscreenOn(true);
                            }
                        } else {
                            Log.d(TAG, "Releasing WakeLock");
                            wakeLock.release();
                            if (stateSnapshot.txMode) {
                                Log.d(TAG, "Releasing keep screen on");
                                setscreenOn(false);
                            }
                        }
                    }

                    if ((stateSnapshot.happening != activeState.happening)
                            && stateSnapshot.happening) {
                        translationRx.action(TranslationRX.Command.STOP);
                    }
                    if (!stateSnapshot.txMode || !stateSnapshot.relayMode || !stateSnapshot.happening) {
                        translationRx.channelSelect(stateSnapshot.selectedMainChannel);
                    }
                    if ((stateSnapshot.happening != activeState.happening)
                            && stateSnapshot.happening) {
                        if (stateSnapshot.relayMode) {
                            translationRx.action(TranslationRX.Command.START_NO_UUID_SEND);
                        } else {
                            translationRx.action(TranslationRX.Command.START);
                        }
                    }
                    translationTx.channelSelect(stateSnapshot.selectedMainChannel);

                    if ((stateSnapshot.txMode != activeState.txMode) || !activeState.stateInitialised) {
                        //Stop everything
                        translationRx.action(TranslationRX.Command.STOP);
                        translationTx.action(TranslationTX.Command.STOP);
                    }
                    if (stateSnapshot.happening != activeState.happening) {
                        if (stateSnapshot.happening) {
                            //Start function
                            if (stateSnapshot.txMode) {
                                if (micPermission) {
                                    if (BUILD_VERSION > Build.VERSION_CODES.LOLLIPOP_MR1) {
                                        if (!checkIfAlreadyhavePermission()) {
                                            Log.d(TAG, "Permissions requested");
                                            //Postpone start until mic permission granted
                                            stateSnapshot.happening = false;
                                            desiredState.happening = false;
                                            happenAfterMicPermission = true;
                                            requestForSpecificPermission();
                                            //micPermission will be false at this point
                                        }
                                    }
                                    if (micPermission) {
                                        translationRx.action(TranslationRX.Command.STOP);
                                        translationTx.action(TranslationTX.Command.START);
                                        if (stateSnapshot.relayMode) {
                                            translationRx.action(TranslationRX.Command.START_NO_UUID_SEND);
                                        }
                                    }
                                }
                            } else {
                                translationTx.action(TranslationTX.Command.STOP);
                                translationRx.action(TranslationRX.Command.STOP);
                                translationRx.action(TranslationRX.Command.START);
                            }
                        } else {
                            //Stop function
                            if (stateSnapshot.txMode) {
                                translationTx.action(TranslationTX.Command.STOP);
                            } else {
                                translationRx.action(TranslationRX.Command.STOP);
                            }
                        }
                    }

                    //TX controls
                    if (stateSnapshot.mute != activeState.mute) {
                        translationTx.mute(stateSnapshot.mute);
                    }
                    if (stateSnapshot.maxGainDb != activeState.maxGainDb) {
                        translationTx.setMaxGainDb(stateSnapshot.maxGainDb);
                    }
                    if (stateSnapshot.increaseDbPerSecond != activeState.increaseDbPerSecond) {
                        translationTx.setIncreaseDbPerSecond(stateSnapshot.increaseDbPerSecond);
                    }
                    if (stateSnapshot.holdTimeSeconds != activeState.holdTimeSeconds) {
                        translationTx.setHoldTimeSeconds(stateSnapshot.holdTimeSeconds);
                    }
                    if (stateSnapshot.networkPacketRedundancy != activeState.networkPacketRedundancy) {
                        translationTx.setNetworkPacketRedundancy(stateSnapshot.networkPacketRedundancy);
                    }

                    //Stop to restart on channel change
                    if (stateSnapshot.txMode) {
                        if ((desiredState.selectedRelayChannel != activeState.selectedRelayChannel) && stateSnapshot.relayMode) {
                            translationRx.action(TranslationRX.Command.STOP);
                            translationRx.action(TranslationRX.Command.START);
                            rxReTest = true;
                        }
                    } else {
                        if ((desiredState.selectedMainChannel != activeState.selectedMainChannel)) {
                            translationRx.action(TranslationRX.Command.STOP);
                            translationRx.action(TranslationRX.Command.START);
                            rxReTest = true;
                        }
                    }
                }

                //Perform action based on RX/TX state if RX needs to be active
                if (!stateSnapshot.txMode || !stateSnapshot.happening || stateSnapshot.relayMode) {
                    switch(rxState) {
                        case STOPPED:
                            boolean doTest = false;
                            // If in a managed state then try multicast then RTSP to confirm busy state
                            if (activeState.channelsManaged) {
                                if ((TranslationRX.getChannel() < activeState.channelMap.size()) && activeState.channelMap.get(TranslationRX.getChannel()).busy) {
                                    if (!stateSnapshot.happening) {
                                        // Find working mode, first try multicast then try RTSP
                                        if (!stateSnapshot.rxMulticastTested) {
                                            if (!stateSnapshot.rxMulticastOk && !stateSnapshot.rxRtspOk) {
                                                rxTestMulticastMode = true;
                                                doTest = true;
                                            }
                                        } else {
                                            if (!stateSnapshot.rxRtspTested && !stateSnapshot.rxRtspOk && !stateSnapshot.rxMulticastOk) {
                                                rxTestMulticastMode = false;
                                                doTest = true;
                                            }
                                        }
                                    }
                                }
                            } else {
                                rxTestMulticastMode = true;
                                doTest = true;
                            }
                            // Test channel
                            // TODO sometimes starts test mode after play selected resulting in no sound
                            if (doTest) {
                                translationRx.setMulticastMode(rxTestMulticastMode);
                                translationRx.action(TranslationRX.Command.TEST);
                                rxTestComplete = false;
                                Log.d(TAG, "Starting RX test with multicast mode : " + rxTestMulticastMode);
                            }
                            break;
                        case STARTING:
                            break;
                        case WAITING:
                            rxTestedBusy = false;
                            rxTestedValid = false;
                            break;
                        case RUNNING:
                            rxTestedBusy = true;
                            rxTestedValid = true;
                            break;
                        case UNAVAILABLE:
                            rxTestedBusy = true;
                            rxTestedValid = false;
                            break;
                    }
                    if (!stateSnapshot.happening) {
                        switch (rxState) {
                            //Test complete
                            case WAITING:
                            case RUNNING:
                            case UNAVAILABLE:
                                // If not "happening" then RX was only a test and should be stopped
                                translationRx.action(TranslationRX.Command.STOP);
                                Log.d(TAG, "Completed RX test : RXBusy : " + rxTestedBusy + ", RXValid : " + rxTestedValid + ", Multicast mode : " + rxTestMulticastMode);
                                if (stateSnapshot.channelsManaged) {
                                    rxTestComplete = true;
                                }
                                break;
                        }
                    }
                }
                if (desiredState.happening && !stateSnapshot.txMode) {
                    if (rxTestedValid) {
                        rxLastGoodTime = System.currentTimeMillis();
                    } else {
                        //Timeout on no good packets
                        if (System.currentTimeMillis() > (rxLastGoodTime + (RX_WAIT_TIMEOUT_MIN * 60 * 1000))) {
                            Log.w(TAG, "RX timeout waiting for audio after " + RX_WAIT_TIMEOUT_MIN + " minutes");
                            desiredState.happening = false;
                        }
                    }
                }

                // If in managed mode then only test once
                if (stateSnapshot.channelsManaged && (TranslationRX.getChannel() < stateSnapshot.channelMap.size())) {
                    AppState.Chan chan = stateSnapshot.channelMap.get(TranslationRX.getChannel());
                    // If Hub can't see it. We can't see it.
                    if (!chan.busy) {
                        activeState.rxBusy = false;
                        activeState.rxValid = false;
                        rxReTest = true;
                    } else {
                        // Process result
                        if (rxTestComplete) {
                            if (rxTestMulticastMode) {
                                if (rxTestedBusy) {
                                    activeState.rxMulticastOk = true;
                                    activeState.rxBusy = rxTestedBusy;
                                    activeState.rxValid = rxTestedValid;
                                } else {
                                    // Multicast failed so try RTSP
                                    activeState.rxMulticastMode = false;
                                }
                                activeState.rxMulticastTested = true;
                            } else {
                                if (rxTestedBusy) {
                                    activeState.rxRtspOk = true;
                                }
                                activeState.rxRtspTested = true;
                                activeState.rxBusy = rxTestedBusy;
                                activeState.rxValid = rxTestedValid;                            }
                        }
                    }

                    // Only allow transmit if multicast is working and we are in the list of UUIDs allowed to or the channel is open
                    if ((chan.allowedIds.contains(uuid) || chan.open) && activeState.rxMulticastMode) {
                        activeState.txEnabled = !activeState.rxBusy;
                    } else {
                        // Not allowed
                        activeState.txEnabled = false;
                    }

                    // Organise retest if pending
                    if (rxReTest && rxTestComplete) {
                        Log.d(TAG, "Triggering TX retest");
                        activeState.rxMulticastOk = false;
                        activeState.rxMulticastTested = false;
                        activeState.rxRtspOk = false;
                        activeState.rxRtspTested = false;
                        rxReTest = false;
                    }
                } else {
                    activeState.rxBusy = rxTestedBusy;
                    activeState.rxValid = rxTestedValid;
                    activeState.txEnabled = !rxTestedBusy;
                }



                //Record Wifi state
                activeState.wifiOn = Tools.isWifiOn();
                //Record headphone state
                activeState.headphones = Tools.phones_check();
                //Act on headphone state change
                if ((stateSnapshot.headphones != activeState.headphones) && !activeState.txMode) {
                    Tools.phones_mode_set();
                }
                if ((stateSnapshot.headphones != activeState.headphones)
                        || (stateSnapshot.rxValid != activeState.rxValid)
                        || (stateSnapshot.txMode != activeState.txMode)
                        || !activeState.stateInitialised) {
                    //Start if headphones plugged in
                    if (!stateSnapshot.txMode && activeState.headphones && activeState.rxValid) {
                        Log.i(TAG, "Start tiggered by presence of headphones");
                        desiredState.happening = true;
                    }
                    //Stop if headphones unplugged
                    if ((stateSnapshot.headphones != activeState.headphones)
                            && !activeState.headphones) {
                        Log.i(TAG, "Stop tiggered by absence of headphones");
                        desiredState.happening = false;
                    }
                }


                //Go to sleep if lost focus and nothing happening
                if (!stateSnapshot.appIsVisible && !stateSnapshot.happening) {
                    Log.i(TAG, "Going to sleep");
                    //Stop any RX test
                    translationRx.action(TranslationRX.Command.STOP);
                    // Stop any Hub comms
                    HubComms.pollHubStop();
                    hubComms.broadcastStop();
                    //Throw on the breaks unless we have woken back up
                    synchronized (mPauseLock) {
                        if (!activeState.appIsVisible) {
                            mPaused = true;
                        } else {
                            Log.i(TAG, "Sleep cancelled");
                        }
                    }
                } else if (stateSnapshot.appIsVisible) {
                    // Does nothing if already running
                    HubComms.pollHubStart();
                    hubComms.broadcastStart();
                }

                stateSnapshot.copyDesiredTo(activeState);
                activeState.stateInitialised = true;

                //Pause if requested
                synchronized (mPauseLock) {
                    while (mPaused) {
                        try {
                            mPauseLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Active state thread interrupted during pause");
                        }
                    }
                }
                //Sleep for a bit
                try {
                    Thread.sleep(ACTION_THREAD_MS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Active state thread interrupted");
                }
            }
        }
    };
    private void actionStateThreadStart() {
        if (actionStateThread.getState() == Thread.State.NEW) {
            Log.d(TAG, "Starting main action thread");
            actionStateThread.start();
        } else {
            Log.d(TAG, "Not starting main action thread which is in state : " + actionStateThread.getState());
        }
    }


    private void setscreenOn (final boolean screenOn) {

        runOnUiThread(new Runnable() {
            public void run() {
                if (screenOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                }
            }
        });
    }



    static boolean isBackground() {
        ActivityManager.RunningAppProcessInfo myProcess = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(myProcess);
        return (myProcess.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
    }

    private boolean checkIfAlreadyhavePermission() {
        int result = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        micPermission = (result == PackageManager.PERMISSION_GRANTED);

        return micPermission;
    }

    private void requestForSpecificPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE);
    }


    private void setGainControlView (View layout) {
        SeekBar maxGainDbSeekBar = layout.findViewById(R.id.gain_controls_max_gain_db_seekBar);
        maxGainDbSeekBar.setProgress((int)(((desiredState.maxGainDb - translationTx.getMaxGainDbMin()) * 100.0f)/(translationTx.getMaxGainDbMax() - translationTx.getMaxGainDbMin())));
        TextView maxGainDbTextView = layout.findViewById(R.id.gain_controls_max_gain_db_textview);
        maxGainDbTextView.setText(String.format("%2.2f", desiredState.maxGainDb));
        Log.d(TAG, "maxGainDb recovered as " + desiredState.maxGainDb);

        SeekBar increaseDbPerSecondSeekBar = layout.findViewById(R.id.gain_controls_increase_db_per_second_seekBar);
        increaseDbPerSecondSeekBar.setProgress(logToProgress(translationTx.getIncreaseDbPerSecondMin(), translationTx.getIncreaseDbPerSecondMax(), desiredState.increaseDbPerSecond));
        TextView increaseDbPerSecondTextView = layout.findViewById(R.id.gain_controls_increase_db_per_second_textview);
        increaseDbPerSecondTextView.setText(String.format("%1.3f",desiredState.increaseDbPerSecond));
        Log.d(TAG, "increaseDbPerSecond recovered as " + desiredState.increaseDbPerSecond);

        SeekBar holdTimeSecondsSeekBar = layout.findViewById(R.id.gain_controls_hold_time_seekBar);
        holdTimeSecondsSeekBar.setProgress(logToProgress(translationTx.getHoldTimeSecondsMin(), translationTx.getHoldTimeSecondsMax(), desiredState.holdTimeSeconds));
        TextView holdTimeSecondsTextView = layout.findViewById(R.id.gain_controls_hold_time_textview);
        holdTimeSecondsTextView.setText(String.format("%2.2f", desiredState.holdTimeSeconds));
        Log.d(TAG, "holdTimeSeconds recovered as " + desiredState.holdTimeSeconds);

        SeekBar networkRedundancySeekBar = layout.findViewById(R.id.network_controls_network_redundancy_seekbar);
        networkRedundancySeekBar.setProgress(desiredState.networkPacketRedundancy - 1);
        networkRedundancySeekBar.setMax(translationTx.getNetworkRedundancyMax() - 1);
        TextView networkRedundancyTextView = layout.findViewById(R.id.network_controls_network_redundancy_textview);
        networkRedundancyTextView.setText(String.format("%d", desiredState.networkPacketRedundancy));
        Log.d(TAG, "networkRedundancyT recovered as " + desiredState.networkPacketRedundancy);
    }

    //Convert logarithmic value to linear slider position
    private int logToProgress (float min, float max, float value) {

        float logMin = (float)Math.log10((double)min);
        float logMax = (float)Math.log10((double)max);
        float logValue = (float)Math.log10((double)value);

        int progress = (int)((logValue - logMin)/(logMax - logMin)*100.0f);

        return progress;
    }
    //Convert linear slider position to logarithmic value
    private float progressToLog (float min, float max, int progress) {

        float logMin = (float)Math.log10((double)min);
        float logMax = (float)Math.log10((double)max);

        float value = (((float)progress * (logMax - logMin)) / 100.0f) + logMin;
        value = (float)Math.pow(10.0, (double)value);

        if (value < min) {
            value = min;
        }
        if (value > max) {
            value = max;
        }
        return value;
    }

    //Check that states have been initialised
    private boolean statesOK () {
        return ((desiredState != null) && (displayState != null) && (activeState != null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    micPermission = true;
                    if (happenAfterMicPermission) {
                        desiredState.happening = true;
                    }
                    happenAfterMicPermission = false;
                    Log.i(TAG, "Permission for mic granted");
                } else {
                    Log.w(TAG, "Permission for mic denied");
                    micPermission = false;
                }
                return;
            }
        }

        Log.i(TAG, "Permission request code : " + requestCode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "Waking up");

        synchronized (mPauseLock) {
            if (activeState != null) {
                activeState.appIsVisible = true;
            }
            mPaused = false;
            mPauseLock.notifyAll();
        }
    }

    @Override
    protected void onPause() {
        if (activeState != null) {
            activeState.appIsVisible = false;
        }
        // Wait for threads to be shut down
        Log.i(TAG, "Waiting to enter lost-focus mode");
        while (!mPaused) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Log.d(TAG, "Wait for pause thread interrupted");
            }
        }
        Log.i(TAG, "Entering lost-focus mode");
        super.onPause();
    }
}
