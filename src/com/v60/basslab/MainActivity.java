package com.v60.basslab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private AudioDspService dspService;
    private boolean isBound = false;
    private AiApiClient aiApiClient;

    // Header
    private TextView tvHeaderDacBadge;
    private Switch switchMasterDsp;

    // Visualizer & Master Dial
    private BassVisualizerView visualizerView;
    private SeekBar seekMasterBass;
    private TextView tvMasterPercent;

    // Profiles (Disabled by default: currentActiveProfile = -1)
    private Switch switchAcousticProfiles;
    private TextView tvAcousticProfilesStatus;
    private int currentActiveProfile = -1;
    private int lastSelectedProfileIndex = 0;
    private boolean isUpdatingProfileSwitchProgrammatically = false;

    private LinearLayout cardPresetRetro90s;
    private LinearLayout cardPresetCarSubwoofer;
    private LinearLayout cardPresetClub;
    private LinearLayout cardPresetVinyl;
    private LinearLayout cardPresetFlat;

    private TextView tvTitleRetro90s;
    private TextView tvTitleCarSubwoofer;
    private TextView tvTitleClub;
    private TextView tvTitleVinyl;
    private TextView tvTitleFlat;

    // Philips FW-M576 Suite
    private TachometerView tachometerView;
    private AudioCaptureManager audioCaptureManager;
    private Button btnToggleAudioCapture;
    private Button btnToggleDemoMode;
    private TextView tvCaptureStatus;
    private static final int REQUEST_CODE_AUDIO_CAPTURE = 301;

    // Live Dynamic EQ & AI
    private Switch switchLiveDynamicEq;
    private TextView tvDynamicStemMoment;
    private TextView tvDynamicIntensityVal;
    private TextView tvDynamicStemAction;
    private ProgressBar progressLiveDynamicIntensity;
    private TextView tvDynamicStemTelemetry;

    private LinearLayout btnPhilipsMaxSound;
    private TextView tvMaxSoundLed;
    private TextView tvMaxSoundTitle;
    private TextView tvMaxSoundDesc;
    private TextView tvMaxSoundStatusBadge;
    private TextView tvPhilipsBadge;

    // Sony LBT-XB66
    private LinearLayout btnSonyXb66Sound;
    private TextView tvSonyXb66Led;
    private TextView tvSonyXb66Title;
    private TextView tvSonyXb66Desc;
    private TextView tvSonyXb66StatusBadge;

    private Button btnDbbOff, btnDbb1, btnDbb2, btnDbb3;
    private TextView tvDbbDesc;

    private Button btnGameportOff, btnGameportSpeed, btnGameportPunch, btnGameportBlast;
    private TextView tvGameportDesc;

    private Button btnDscOptimal, btnDscTechno, btnDscRock, btnDscPop, btnDscJazz;

    // Sub-Harmonic Synthesizer (The Epicenter) & Hi-Res Player
    private SubharmonicSynthesizerEngine synthEngine;
    private HiResPlayerEngine playerEngine;
    private static final int REQUEST_CODE_OPEN_AUDIO = 201;

    private Button btnPlayerOpenFile;
    private TextView tvPlayerTrackTitle;
    private Button btnPlayerPlayPause;
    private SeekBar seekPlayerProgress;
    private TextView tvPlayerTime;
    private boolean isUserSeeking = false;

    private TextView tvSynthSweepDesc;
    private TextView tvSynthSweepVal;
    private SeekBar seekSynthSweep;
    private TextView tvSynthWideVal;
    private Switch switchSynthAuto;
    private SeekBar seekSynthWide;
    private TextView tvSynthSubLevelVal;
    private SeekBar seekSynthSubLevel;

    // Smart DJ (AI Song-Level Lock)
    private Switch switchSmartDj;
    private TextView tvSmartDjStatus;
    private TextView tvSmartDjDesc;
    private Button btnAskAiDialog;
    private Button btnReanalyzeTrack;

    // Advanced & Hardware (Full 8-Parameter Studio Parametric EQ & Dynamics Rack)
    private Button btnToggleAdvanced;
    private LinearLayout layoutAdvancedContent;
    private Button btnResetParametricEq;
    private SeekBar seekAdvSub;
    private TextView tvAdvSubDb;
    private SeekBar seekAdvPunch;
    private TextView tvAdvPunchDb;
    private SeekBar seekAdvClarity;
    private TextView tvAdvClarityDb;
    private SeekBar seekAdvPresence;
    private TextView tvAdvPresenceDb;
    private SeekBar seekAdvAir;
    private TextView tvAdvAirDb;
    private SeekBar seekAdvExciter;
    private TextView tvAdvExciterVal;
    private SeekBar seekAdvAlc;
    private TextView tvAdvAlcDb;
    private SeekBar seekAdvPreamp;
    private TextView tvAdvPreampDb;

    private TextView tvAdvImpedance;
    private Switch switchForceHighImp;
    private Spinner spinnerEssFilter;
    private Button btnRefreshDac;

    // Logs
    private ScrollView scrollLogs;
    private TextView tvConsoleLogs;
    private Button btnClearLogs;
    private Button btnCopyLogs;
    private Button btnExportLogs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioDspService.LocalBinder binder = (AudioDspService.LocalBinder) service;
            dspService = binder.getService();
            isBound = true;

            // Visualizer callback (calcula magnitud compleja real sqrt(re^2 + im^2) sin overhead de StrictMath.hypot)
            dspService.setVisualizerCallback(fft -> {
                visualizerView.setAudioData(fft);
                if (tachometerView != null && !audioCaptureManager.isCapturing()) {
                    if (fft != null && fft.length >= 64) {
                        float bassSum = 0f;
                        for (int k = 1; k <= 5; k++) {
                            float re = fft[2 * k];
                            float im = fft[2 * k + 1];
                            bassSum += (float) Math.sqrt(re * re + im * im);
                        }
                        float trebleSum = 0f;
                        for (int k = 10; k <= 30; k += 2) {
                            float re = fft[2 * k];
                            float im = fft[2 * k + 1];
                            trebleSum += (float) Math.sqrt(re * re + im * im);
                        }
                        float b = Math.min(1.0f, bassSum / 210.0f);
                        float t = Math.min(1.0f, trebleSum / 240.0f);
                        if (!tachometerView.isDemoMode() || b > 0.04f || t > 0.04f) {
                            tachometerView.setLevels(b, t);
                        }
                    }
                }
            });

            // AI Genre and Live Dynamic Stem listener (Full 8-Parameter AI Access)
            dspService.getAiAnalyzer().setAiGenreListener(new AiAudioAnalyzer.AiGenreListener() {
                @Override
                public void onGenreStatusChanged(
                    String statusTitle, String desc, boolean isLocked,
                    float recSub, float recPunch, float recClarity, float recMidPresence, float recAir,
                    int recHwBoost, float recMbcAlc, float recPreamp,
                    int recMasterPercent, int recEssFilter, int recProfileIndex,
                    int recDbbLevel, int recDscPreset,
                    boolean enablePhilipsMax, boolean enableSonyXb66
                ) {
                    mainHandler.post(() -> {
                        tvSmartDjStatus.setText(statusTitle);
                        tvSmartDjDesc.setText(desc);

                        if (isLocked && switchSmartDj.isChecked() && (switchLiveDynamicEq == null || !switchLiveDynamicEq.isChecked())) {
                            // Philips / Sony o Calibración Paramétrica completa de 8 parámetros
                            if (enablePhilipsMax && !enableSonyXb66) {
                                dspService.setMaxSound(true);
                                updateMaxSoundUi(true);
                                updateSonyXb66Ui(false);
                                syncSlidersFromDsp();
                            } else if (enableSonyXb66 && !enablePhilipsMax) {
                                dspService.setSonyXb66Sound(true);
                                updateSonyXb66Ui(true);
                                updateMaxSoundUi(false);
                                syncSlidersFromDsp();
                            } else {
                                if (dspService.isMaxSoundActive()) {
                                    dspService.setMaxSound(false);
                                    updateMaxSoundUi(false);
                                }
                                if (dspService.isSonyXb66Active()) {
                                    dspService.setSonyXb66Sound(false);
                                    updateSonyXb66Ui(false);
                                }
                                dspService.setFullStudioCalibration(
                                    recSub, recPunch, recClarity, recMidPresence, recAir,
                                    recHwBoost, recMbcAlc, recPreamp
                                );
                                updateAllAdvancedSeekBars(
                                    recSub, recPunch, recClarity, recMidPresence, recAir,
                                    recHwBoost, recMbcAlc, recPreamp
                                );
                            }

                            updateDbbUi(recDbbLevel);
                            updateDscUi(recDscPreset);

                            // Reflejo visual del Master Bass en UI
                            seekMasterBass.setProgress(recMasterPercent);
                            tvMasterPercent.setText(recMasterPercent + "%");

                            // ESS Sabre filter - cambiar sólo si es distinto al actual
                            if (spinnerEssFilter.getSelectedItemPosition() != recEssFilter) {
                                spinnerEssFilter.setSelection(recEssFilter, false);
                                executor.execute(() -> QuadDacManager.setEssFilter(recEssFilter));
                            }

                            // Resaltado visual del perfil acústico (respeta desactivado por defecto si es -1)
                            highlightSelectedProfile(recProfileIndex);

                            tvHeaderDacBadge.setText("⚡ " + statusTitle + " • 2.0V Active");
                        }
                    });
                }

                @Override
                public void onLiveDynamicStemChanged(
                    String stemTitle, String stemAction,
                    float subDb, float punchDb, float clarityDb, float midPresenceDb, float airDb,
                    int hwBoost, float mbcAlcDb, float preampDb,
                    int dynMasterPercent, int dynEssFilter
                ) {
                    mainHandler.post(() -> {
                        if (tvDynamicStemMoment != null) {
                            tvDynamicStemMoment.setText("Active Moment: " + stemTitle);
                        }
                        if (tvDynamicStemAction != null) {
                            tvDynamicStemAction.setText(stemAction);
                        }

                        if (dspService != null) {
                            int modIntensity = dspService.getAiAnalyzer().getCurrentModulationIntensity();
                            String telemetryLine = dspService.getAiAnalyzer().getCurrentTelemetryLine();
                            if (tvDynamicIntensityVal != null) {
                                tvDynamicIntensityVal.setText(
                                    (switchLiveDynamicEq != null && switchLiveDynamicEq.isChecked())
                                        ? ("Modulation: " + modIntensity + "%")
                                        : ("Detection: " + modIntensity + "%")
                                );
                            }
                            if (progressLiveDynamicIntensity != null) {
                                progressLiveDynamicIntensity.setProgress(modIntensity);
                            }
                            if (tvDynamicStemTelemetry != null) {
                                tvDynamicStemTelemetry.setText(telemetryLine);
                            }
                        }

                        if (switchLiveDynamicEq != null && switchLiveDynamicEq.isChecked()) {
                            // NOTA: AudioDspService ya aplica setDynamicEqGains directamente en segundo plano
                            // mediante DirectDspListener con deadband de hardware; aquí sólo sincronizamos la UI.
                            updateAllAdvancedSeekBars(
                                subDb, punchDb, clarityDb, midPresenceDb, airDb,
                                hwBoost, mbcAlcDb, preampDb
                            );

                            // Actualizar únicamente el indicador visual (sin llamar setMasterBassPercent para no destruir las ganancias)
                            seekMasterBass.setProgress(dynMasterPercent);
                            tvMasterPercent.setText(dynMasterPercent + "%");
                        }
                    });
                }
            });

            syncControlsWithService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            dspService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AppLogger.init(this);
        aiApiClient = new AiApiClient(this);

        synthEngine = new SubharmonicSynthesizerEngine();
        playerEngine = new HiResPlayerEngine(this, synthEngine);
        audioCaptureManager = new AudioCaptureManager(this);

        initViews();
        setupMasterControl();
        setupPhilipsSuite();
        setupTachometerCaptureSection();
        setupSubharmonicSynthesizerSection();
        setupSoundProfiles();
        setupSmartDjSection();
        setupAdvancedSection();
        requestAudioPermissions();

        // Start & bind Audio DSP service
        Intent intent = new Intent(this, AudioDspService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Fetch DAC status
        refreshDacHardwareStatus();
    }

    private void initViews() {
        tvHeaderDacBadge = findViewById(R.id.tv_header_dac_badge);
        switchMasterDsp = findViewById(R.id.switch_master_dsp);

        visualizerView = findViewById(R.id.visualizer_view);
        seekMasterBass = findViewById(R.id.seek_master_bass);
        tvMasterPercent = findViewById(R.id.tv_master_percent);

        // Philips FW-M576 Suite
        tachometerView = findViewById(R.id.tachometer_view);
        btnToggleAudioCapture = findViewById(R.id.btn_toggle_audio_capture);
        btnToggleDemoMode = findViewById(R.id.btn_toggle_demo_mode);
        tvCaptureStatus = findViewById(R.id.tv_capture_status);
        btnPhilipsMaxSound = findViewById(R.id.btn_philips_max_sound);
        tvMaxSoundLed = findViewById(R.id.tv_max_sound_led);
        tvMaxSoundTitle = findViewById(R.id.tv_max_sound_title);
        tvMaxSoundDesc = findViewById(R.id.tv_max_sound_desc);
        tvMaxSoundStatusBadge = findViewById(R.id.tv_max_sound_status_badge);
        tvPhilipsBadge = findViewById(R.id.tv_philips_badge);

        btnSonyXb66Sound = findViewById(R.id.btn_sony_xb66_sound);
        tvSonyXb66Led = findViewById(R.id.tv_sony_xb66_led);
        tvSonyXb66Title = findViewById(R.id.tv_sony_xb66_title);
        tvSonyXb66Desc = findViewById(R.id.tv_sony_xb66_desc);
        tvSonyXb66StatusBadge = findViewById(R.id.tv_sony_xb66_status_badge);

        btnDbbOff = findViewById(R.id.btn_dbb_off);
        btnDbb1 = findViewById(R.id.btn_dbb_1);
        btnDbb2 = findViewById(R.id.btn_dbb_2);
        btnDbb3 = findViewById(R.id.btn_dbb_3);
        tvDbbDesc = findViewById(R.id.tv_dbb_desc);

        btnGameportOff = findViewById(R.id.btn_gameport_off);
        btnGameportSpeed = findViewById(R.id.btn_gameport_speed);
        btnGameportPunch = findViewById(R.id.btn_gameport_punch);
        btnGameportBlast = findViewById(R.id.btn_gameport_blast);
        tvGameportDesc = findViewById(R.id.tv_gameport_desc);

        btnDscOptimal = findViewById(R.id.btn_dsc_optimal);
        btnDscTechno = findViewById(R.id.btn_dsc_techno);
        btnDscRock = findViewById(R.id.btn_dsc_rock);
        btnDscPop = findViewById(R.id.btn_dsc_pop);
        btnDscJazz = findViewById(R.id.btn_dsc_jazz);

        // Sub-Harmonic Synthesizer (The Epicenter) & Hi-Res Player
        btnPlayerOpenFile = findViewById(R.id.btn_player_open_file);
        tvPlayerTrackTitle = findViewById(R.id.tv_player_track_title);
        btnPlayerPlayPause = findViewById(R.id.btn_player_play_pause);
        seekPlayerProgress = findViewById(R.id.seek_player_progress);
        tvPlayerTime = findViewById(R.id.tv_player_time);

        tvSynthSweepDesc = findViewById(R.id.tv_synth_sweep_desc);
        tvSynthSweepVal = findViewById(R.id.tv_synth_sweep_val);
        seekSynthSweep = findViewById(R.id.seek_synth_sweep);
        tvSynthWideVal = findViewById(R.id.tv_synth_wide_val);
        switchSynthAuto = findViewById(R.id.switch_synth_auto);
        seekSynthWide = findViewById(R.id.seek_synth_wide);
        tvSynthSubLevelVal = findViewById(R.id.tv_synth_sub_level_val);
        seekSynthSubLevel = findViewById(R.id.seek_synth_sub_level);

        switchAcousticProfiles = findViewById(R.id.switch_acoustic_profiles);
        tvAcousticProfilesStatus = findViewById(R.id.tv_acoustic_profiles_status);

        cardPresetRetro90s = findViewById(R.id.card_preset_retro_90s);
        cardPresetCarSubwoofer = findViewById(R.id.card_preset_car_subwoofer);
        cardPresetClub = findViewById(R.id.card_preset_club);
        cardPresetVinyl = findViewById(R.id.card_preset_vinyl);
        cardPresetFlat = findViewById(R.id.card_preset_flat);

        tvTitleRetro90s = findViewById(R.id.tv_title_retro_90s);
        tvTitleCarSubwoofer = findViewById(R.id.tv_title_car_subwoofer);
        tvTitleClub = findViewById(R.id.tv_title_club);
        tvTitleVinyl = findViewById(R.id.tv_title_vinyl);
        tvTitleFlat = findViewById(R.id.tv_title_flat);

        switchSmartDj = findViewById(R.id.switch_smart_dj);
        tvSmartDjStatus = findViewById(R.id.tv_smart_dj_status);
        tvSmartDjDesc = findViewById(R.id.tv_smart_dj_desc);
        btnAskAiDialog = findViewById(R.id.btn_ask_ai_dialog);
        btnReanalyzeTrack = findViewById(R.id.btn_reanalyze_track);
        switchLiveDynamicEq = findViewById(R.id.switch_live_dynamic_eq);
        tvDynamicStemMoment = findViewById(R.id.tv_dynamic_stem_moment);
        tvDynamicIntensityVal = findViewById(R.id.tv_dynamic_intensity_val);
        tvDynamicStemAction = findViewById(R.id.tv_dynamic_stem_action);
        progressLiveDynamicIntensity = findViewById(R.id.progress_live_dynamic_intensity);
        tvDynamicStemTelemetry = findViewById(R.id.tv_dynamic_stem_telemetry);

        btnToggleAdvanced = findViewById(R.id.btn_toggle_advanced);
        layoutAdvancedContent = findViewById(R.id.layout_advanced_content);
        btnResetParametricEq = findViewById(R.id.btn_reset_parametric_eq);
        seekAdvSub = findViewById(R.id.seek_adv_sub);
        tvAdvSubDb = findViewById(R.id.tv_adv_sub_db);
        seekAdvPunch = findViewById(R.id.seek_adv_punch);
        tvAdvPunchDb = findViewById(R.id.tv_adv_punch_db);
        seekAdvClarity = findViewById(R.id.seek_adv_clarity);
        tvAdvClarityDb = findViewById(R.id.tv_adv_clarity_db);
        seekAdvPresence = findViewById(R.id.seek_adv_presence);
        tvAdvPresenceDb = findViewById(R.id.tv_adv_presence_db);
        seekAdvAir = findViewById(R.id.seek_adv_air);
        tvAdvAirDb = findViewById(R.id.tv_adv_air_db);
        seekAdvExciter = findViewById(R.id.seek_adv_exciter);
        tvAdvExciterVal = findViewById(R.id.tv_adv_exciter_val);
        seekAdvAlc = findViewById(R.id.seek_adv_alc);
        tvAdvAlcDb = findViewById(R.id.tv_adv_alc_db);
        seekAdvPreamp = findViewById(R.id.seek_adv_preamp);
        tvAdvPreampDb = findViewById(R.id.tv_adv_preamp_db);

        tvAdvImpedance = findViewById(R.id.tv_adv_impedance);
        switchForceHighImp = findViewById(R.id.switch_force_high_imp);
        spinnerEssFilter = findViewById(R.id.spinner_ess_filter);
        btnRefreshDac = findViewById(R.id.btn_refresh_dac);

        scrollLogs = findViewById(R.id.scroll_logs);
        tvConsoleLogs = findViewById(R.id.tv_console_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);
        btnExportLogs = findViewById(R.id.btn_export_logs);
    }

    private void setupMasterControl() {
        switchMasterDsp.setOnCheckedChangeListener((bv, isChecked) -> {
            if (dspService != null) dspService.setMasterEnabled(isChecked);
            visualizerView.setPlaying(isChecked);
            tvHeaderDacBadge.setText(isChecked ? "ESS Sabre ES9219 • 2.0V Active" : "DSP Off • Direct Mode");
        });

        seekMasterBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvMasterPercent.setText(progress + "%");
                if (fromUser && dspService != null) {
                    dspService.setMasterBassPercent(progress);
                    highlightSelectedProfile(-1);
                    updateAdvancedSeekBars(
                        dspService.getCurrentSubBassDb(),
                        dspService.getCurrentPunchDb(),
                        dspService.getCurrentClarityDb()
                    );
                }
                visualizerView.setSubBassBoostFactor(progress / 10.0f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupPhilipsSuite() {
        btnPhilipsMaxSound.setOnClickListener(v -> {
            if (dspService == null) return;
            boolean newState = !dspService.isMaxSoundActive();
            dspService.setMaxSound(newState);
            updateMaxSoundUi(newState);
            updateSonyXb66Ui(false);
            highlightSelectedProfile(-1);

            if (newState) {
                tvHeaderDacBadge.setText("PHILIPS MAX SOUND • 2.0V Active");
                Toast.makeText(this, "PHILIPS MAX SOUND ACTIVE\n+12.5dB Sub • +9.5dB Punch • 2.0 Vrms Quad DAC", Toast.LENGTH_SHORT).show();
            } else {
                tvHeaderDacBadge.setText("ESS Sabre ES9219 • 2.0V Active");
                Toast.makeText(this, "MAX SOUND Deactivated", Toast.LENGTH_SHORT).show();
            }

            updateAdvancedSeekBars(
                dspService.getCurrentSubBassDb(),
                dspService.getCurrentPunchDb(),
                dspService.getCurrentClarityDb()
            );
        });

        btnSonyXb66Sound.setOnClickListener(v -> {
            if (dspService == null) return;
            boolean newSonyState = !dspService.isSonyXb66Active();
            dspService.setSonyXb66Sound(newSonyState);
            updateSonyXb66Ui(newSonyState);
            updateMaxSoundUi(false);
            highlightSelectedProfile(-1);

            if (newSonyState) {
                tvHeaderDacBadge.setText("SONY LBT-XB66 SAW • 2.0V Active");
                Toast.makeText(this, "SONY LBT-XB66 ACTIVE\n+13.5dB SAW Sub • +10dB Punch • V-Groove • 2.0V DAC", Toast.LENGTH_SHORT).show();
            } else {
                tvHeaderDacBadge.setText("ESS Sabre ES9219 • 2.0V Active");
                Toast.makeText(this, "SONY LBT-XB66 Deactivated", Toast.LENGTH_SHORT).show();
            }

            updateAdvancedSeekBars(
                dspService.getCurrentSubBassDb(),
                dspService.getCurrentPunchDb(),
                dspService.getCurrentClarityDb()
            );
        });

        // DBB
        btnDbbOff.setOnClickListener(v -> selectDbb(0));
        btnDbb1.setOnClickListener(v -> selectDbb(1));
        btnDbb2.setOnClickListener(v -> selectDbb(2));
        btnDbb3.setOnClickListener(v -> selectDbb(3));

        // Gameport
        btnGameportOff.setOnClickListener(v -> selectGameport(0));
        btnGameportSpeed.setOnClickListener(v -> selectGameport(1));
        btnGameportPunch.setOnClickListener(v -> selectGameport(2));
        btnGameportBlast.setOnClickListener(v -> selectGameport(3));

        // DSC
        btnDscOptimal.setOnClickListener(v -> selectDsc(0));
        btnDscTechno.setOnClickListener(v -> selectDsc(1));
        btnDscRock.setOnClickListener(v -> selectDsc(2));
        btnDscPop.setOnClickListener(v -> selectDsc(3));
        btnDscJazz.setOnClickListener(v -> selectDsc(4));
    }

    private void updateMaxSoundUi(boolean active) {
        btnPhilipsMaxSound.setBackgroundResource(active ? R.drawable.philips_max_sound_active : R.drawable.philips_max_sound_idle);
        tvMaxSoundLed.setText("●");
        tvMaxSoundLed.setTextColor(getColor(active ? R.color.ios_text_primary : R.color.philips_max_stroke));
        tvMaxSoundStatusBadge.setText(active ? "ACTIVE" : "STANDBY");
        tvMaxSoundStatusBadge.setTextColor(getColor(active ? R.color.philips_max_active : R.color.ios_text_secondary));
    }

    private void updateSonyXb66Ui(boolean active) {
        btnSonyXb66Sound.setBackgroundResource(active ? R.drawable.sony_xb66_active : R.drawable.sony_xb66_idle);
        tvSonyXb66Led.setText("●");
        tvSonyXb66Led.setTextColor(getColor(active ? R.color.ios_text_primary : R.color.philips_amber));
        tvSonyXb66StatusBadge.setText(active ? "ACTIVE" : "STANDBY");
        tvSonyXb66StatusBadge.setTextColor(getColor(active ? R.color.cyber_amber : R.color.ios_text_secondary));
    }

    private void selectDbb(int level) {
        if (dspService != null) {
            if (level > 0 && (dspService.isMaxSoundActive() || dspService.isSonyXb66Active())) {
                updateMaxSoundUi(false);
                updateSonyXb66Ui(false);
            }
            dspService.setDbbLevel(level);
        }
        highlightSelectedProfile(-1);
        updateDbbUi(level);
        if (dspService != null) {
            updateAdvancedSeekBars(dspService.getCurrentSubBassDb(), dspService.getCurrentPunchDb(), dspService.getCurrentClarityDb());
        }
    }

    private void updateDbbUi(int level) {
        btnDbbOff.setBackgroundResource(level == 0 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
        btnDbb1.setBackgroundResource(level == 1 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
        btnDbb2.setBackgroundResource(level == 2 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
        btnDbb3.setBackgroundResource(level == 3 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);

        btnDbbOff.setTextColor(getColor(level == 0 ? R.color.philips_amber : R.color.ios_text_secondary));
        btnDbb1.setTextColor(getColor(level == 1 ? R.color.philips_amber : R.color.ios_text_secondary));
        btnDbb2.setTextColor(getColor(level == 2 ? R.color.philips_amber : R.color.ios_text_secondary));
        btnDbb3.setTextColor(getColor(level == 3 ? R.color.philips_amber : R.color.ios_text_secondary));

        switch (level) {
            case 1: tvDbbDesc.setText("DBB 1 (wOOx Deep): +4.5dB Sub (63Hz) + Bass ALC active • Combined with DSC"); break;
            case 2: tvDbbDesc.setText("DBB 2 (wOOx Punch): +5.5dB Punch (90-180Hz) + Bass ALC • Combined with DSC"); break;
            case 3: tvDbbDesc.setText("DBB 3 (wOOx Max): +7.0dB Sub + +5.5dB Punch + TDA7468D ALC • Combined with DSC"); break;
            default: tvDbbDesc.setText("DBB Off: DSC curve active without wOOx boost"); break;
        }
    }

    private void selectGameport(int mode) {
        if (dspService != null) {
            if (mode > 0 && (dspService.isMaxSoundActive() || dspService.isSonyXb66Active())) {
                updateMaxSoundUi(false);
                updateSonyXb66Ui(false);
            }
            dspService.setGameportMode(mode);
        }
        highlightSelectedProfile(-1);
        updateGameportUi(mode);
        if (dspService != null) {
            updateAdvancedSeekBars(dspService.getCurrentSubBassDb(), dspService.getCurrentPunchDb(), dspService.getCurrentClarityDb());
        }
    }

    private void updateGameportUi(int mode) {
        btnGameportOff.setBackgroundResource(mode == 0 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
        btnGameportSpeed.setBackgroundResource(mode == 1 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
        btnGameportPunch.setBackgroundResource(mode == 2 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
        btnGameportBlast.setBackgroundResource(mode == 3 ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);

        btnGameportOff.setTextColor(getColor(mode == 0 ? R.color.philips_amber : R.color.ios_text_secondary));
        btnGameportSpeed.setTextColor(getColor(mode == 1 ? R.color.philips_amber : R.color.ios_text_secondary));
        btnGameportPunch.setTextColor(getColor(mode == 2 ? R.color.philips_amber : R.color.ios_text_secondary));
        btnGameportBlast.setTextColor(getColor(mode == 3 ? R.color.philips_amber : R.color.ios_text_secondary));

        switch (mode) {
            case 1: tvGameportDesc.setText("SPEED: +3dB Mid/High and fast transients (Racing/Arcade)"); break;
            case 2: tvGameportDesc.setText("PUNCH: +5dB Physical low-mid impact (Action/Combat/FPS)"); break;
            case 3: tvGameportDesc.setText("BLAST: +6.5dB Sub-bass blast shockwave (Explosions/Cinema)"); break;
            default: tvGameportDesc.setText("Gameport Normal: No console EQ applied"); break;
        }
    }

    private void selectDsc(int preset) {
        if (dspService != null) {
            if (dspService.isMaxSoundActive() || dspService.isSonyXb66Active()) {
                updateMaxSoundUi(false);
                updateSonyXb66Ui(false);
            }
            dspService.setDscPreset(preset);
        }
        highlightSelectedProfile(-1);
        updateDscUi(preset);
        if (dspService != null) {
            updateAdvancedSeekBars(dspService.getCurrentSubBassDb(), dspService.getCurrentPunchDb(), dspService.getCurrentClarityDb());
        }
    }

    private void updateDscUi(int preset) {
        Button[] dscButtons = {btnDscOptimal, btnDscTechno, btnDscRock, btnDscPop, btnDscJazz};
        for (int i = 0; i < dscButtons.length; i++) {
            dscButtons[i].setBackgroundResource(i == preset ? R.drawable.philips_segment_active : R.drawable.philips_segment_idle);
            dscButtons[i].setTextColor(getColor(i == preset ? R.color.philips_amber : R.color.ios_text_secondary));
        }
    }

    private void setupTachometerCaptureSection() {
        audioCaptureManager.setListener(new AudioCaptureManager.OnAudioLevelsListener() {
            @Override
            public void onLevels(float bass, float treble) {
                if (tachometerView != null) {
                    tachometerView.setLevels(bass, treble);
                }
                if (dspService != null) {
                    float leftDb = (bass <= 0.001f) ? -20f : (float) (20 * Math.log10(bass * 1.5f));
                    float rightDb = (treble <= 0.001f) ? -20f : (float) (20 * Math.log10(treble * 1.5f));
                    dspService.updateStereoLevels(leftDb, rightDb);
                }
            }

            @Override
            public void onStateChanged(boolean isCapturing, String statusMessage) {
                tvCaptureStatus.setText(statusMessage);
                btnToggleAudioCapture.setText(isCapturing ? "⏹ Stop Capture" : "🎙️ Capture App Audio");
                btnToggleAudioCapture.setBackgroundResource(isCapturing ? R.drawable.philips_max_sound_active : R.drawable.btn_gradient_capture);
                btnToggleAudioCapture.setTextColor(getColor(R.color.ios_text_primary));

                if (isCapturing) {
                    tachometerView.setDemoMode(false);
                    btnToggleDemoMode.setText("✨ Demo Mode OFF");
                    btnToggleDemoMode.setBackgroundResource(R.drawable.btn_glass_demo);
                    btnToggleDemoMode.setTextColor(getColor(R.color.ios_text_secondary));
                }
            }

            @Override
            public void onError(String errorMessage) {
                tvCaptureStatus.setText("⚠️ " + errorMessage);
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                btnToggleAudioCapture.setText("🎙️ Capture App Audio");
                btnToggleAudioCapture.setBackgroundResource(R.drawable.btn_gradient_capture);
                btnToggleAudioCapture.setTextColor(getColor(R.color.ios_text_primary));
            }
        });

        btnToggleAudioCapture.setOnClickListener(v -> {
            if (audioCaptureManager.isCapturing()) {
                audioCaptureManager.stopCapture();
                tachometerView.setDemoMode(true);
                btnToggleDemoMode.setText("✨ Demo Mode ON");
                btnToggleDemoMode.setBackgroundResource(R.drawable.btn_glass_demo);
                btnToggleDemoMode.setTextColor(getColor(R.color.cyber_orange));
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent captureIntent = audioCaptureManager.createScreenCaptureIntent();
                    if (captureIntent != null) {
                        startActivityForResult(captureIntent, REQUEST_CODE_AUDIO_CAPTURE);
                    } else {
                        Toast.makeText(this, "Audio capture not supported on this device", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "AudioPlaybackCapture requires Android 10+", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnToggleDemoMode.setOnClickListener(v -> {
            boolean newDemoState = !tachometerView.isDemoMode();
            tachometerView.setDemoMode(newDemoState);
            btnToggleDemoMode.setText(newDemoState ? "✨ Demo Mode ON" : "✨ Demo Mode OFF");
            btnToggleDemoMode.setBackgroundResource(R.drawable.btn_glass_demo);
            btnToggleDemoMode.setTextColor(getColor(newDemoState ? R.color.cyber_orange : R.color.ios_text_secondary));
            tvCaptureStatus.setText(newDemoState ? "Demo Mode Active • Smooth cockpit ballistics" : "Live Mode Active • Awaiting audio signal");

            if (newDemoState && audioCaptureManager.isCapturing()) {
                audioCaptureManager.stopCapture();
            }
        });
    }

    private void setupSubharmonicSynthesizerSection() {
        // Player callbacks
        playerEngine.setCallback(new HiResPlayerEngine.PlayerCallback() {
            @Override
            public void onTrackLoaded(String title, long durationMs) {
                tvPlayerTrackTitle.setText(title);
                seekPlayerProgress.setMax((int) durationMs);
                seekPlayerProgress.setProgress(0);
                tvPlayerTime.setText("0:00 / " + formatTimeMs(durationMs));
                btnPlayerPlayPause.setText("⏸");
            }

            @Override
            public void onProgress(long positionMs, long durationMs) {
                if (!isUserSeeking) {
                    seekPlayerProgress.setProgress((int) positionMs);
                    tvPlayerTime.setText(formatTimeMs(positionMs) + " / " + formatTimeMs(durationMs));
                }
            }

            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                btnPlayerPlayPause.setText(isPlaying ? "⏸" : "▶");
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

        // Live stereo levels drive the Philips FW-M576 Tachometers & Quick Settings Tile
        playerEngine.setAudioLevelListener((leftDb, rightDb) -> {
            if (dspService != null) {
                dspService.updateStereoLevels(leftDb, rightDb);
            }
            if (tachometerView != null && !tachometerView.isDemoMode() && !audioCaptureManager.isCapturing()) {
                float normL = Math.max(0f, Math.min(1f, (leftDb + 20f) / 23.5f));
                float normR = Math.max(0f, Math.min(1f, (rightDb + 20f) / 23.5f));
                tachometerView.setLevels(normL, normR);
            }
        });

        // Open file picker
        btnPlayerOpenFile.setOnClickListener(v -> openAudioFilePicker());

        // Play / Pause
        btnPlayerPlayPause.setOnClickListener(v -> playerEngine.togglePlayPause());

        // Seekbar
        seekPlayerProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvPlayerTime.setText(formatTimeMs(progress) + " / " + formatTimeMs(playerEngine.getDurationMs()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                playerEngine.seekTo(seekBar.getProgress());
            }
        });

        // SWEEP (Target Frequency: 45 Hz to 120 Hz)
        seekSynthSweep.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float freq = 45.0f + progress;
                synthEngine.setSweepFreq(freq);
                tvSynthSweepVal.setText(String.format(Locale.US, "%.0f Hz", freq));
                tvSynthSweepDesc.setText(String.format(Locale.US, "%.0f Hz → Sub sintetizado: %.1f Hz", freq, freq / 2.0f));
                syncGlobalSubSynthToDsp();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // WIDE (Bandwidth Q: 0.5 to 3.0)
        seekSynthWide.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float q = 0.5f + (progress * 0.1f);
                synthEngine.setWideBandwidth(q);
                String desc = (q < 1.0f) ? "Amplio" : (q < 2.0f ? "Balanceado" : "Estrecho");
                tvSynthWideVal.setText(String.format(Locale.US, "%s (Q=%.1f)", desc, q));
                syncGlobalSubSynthToDsp();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // AUTO Mode Switch (dbx 120A 3-Band Tracker)
        switchSynthAuto.setOnCheckedChangeListener((bv, isChecked) -> {
            synthEngine.setAutoMode(isChecked);
            seekSynthSweep.setEnabled(!isChecked);
            seekSynthWide.setEnabled(!isChecked);
            seekSynthSweep.setAlpha(isChecked ? 0.35f : 1.0f);
            seekSynthWide.setAlpha(isChecked ? 0.35f : 1.0f);
            if (isChecked) {
                tvSynthSweepDesc.setText("Auto-seguimiento: 3 bandas activas (55Hz / 80Hz / 110Hz)");
                tvSynthWideVal.setText("Auto-sintonizado (dbx 120A)");
            } else {
                float freq = 45.0f + seekSynthSweep.getProgress();
                tvSynthSweepDesc.setText(String.format(Locale.US, "%.0f Hz → Sub sintetizado: %.1f Hz", freq, freq / 2.0f));
                float q = 0.5f + (seekSynthWide.getProgress() * 0.1f);
                String desc = (q < 1.0f) ? "Amplio" : (q < 2.0f ? "Balanceado" : "Estrecho");
                tvSynthWideVal.setText(String.format(Locale.US, "%s (Q=%.1f)", desc, q));
            }
            syncGlobalSubSynthToDsp();
        });

        // SUB LEVEL (Disabled & hidden for later: 0%)
        synthEngine.setSubLevel(0.0f);
        seekSynthSubLevel.setProgress(0);
        tvSynthSubLevelVal.setText("0%");
        seekSynthSubLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                synthEngine.setSubLevel(progress / 100.0f);
                tvSynthSubLevelVal.setText(progress + "%");
                syncGlobalSubSynthToDsp();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void syncGlobalSubSynthToDsp() {
        if (dspService != null) {
            // Sintetizador sub-armónico desactivado y oculto para uso futuro (nivel 0.0f)
            float freq = 45.0f + seekSynthSweep.getProgress();
            float q = 0.5f + (seekSynthWide.getProgress() * 0.1f);
            dspService.updateGlobalSubSynth(freq, q, 0.0f, false);
        }
    }

    private void openAudioFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        String[] mimeTypes = {"audio/*", "application/ogg"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        try {
            startActivityForResult(intent, REQUEST_CODE_OPEN_AUDIO);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("audio/*");
            startActivityForResult(fallback, REQUEST_CODE_OPEN_AUDIO);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_AUDIO_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                boolean started = audioCaptureManager.startCapture(resultCode, data);
                if (!started) {
                    tachometerView.setDemoMode(true);
                }
            } else {
                tvCaptureStatus.setText("Authorization cancelled by user (Demo Mode active)");
                tachometerView.setDemoMode(true);
                btnToggleDemoMode.setText("Demo Mode: ON");
                btnToggleDemoMode.setBackgroundResource(R.drawable.philips_segment_active);
                btnToggleDemoMode.setTextColor(getColor(R.color.philips_amber));
            }
        } else if (requestCode == REQUEST_CODE_OPEN_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String displayName = queryFileName(uri);
            playerEngine.loadAndPlay(uri, displayName);
            Toast.makeText(this, "Loading: " + displayName, Toast.LENGTH_SHORT).show();
        }
    }

    private String queryFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "Pista de audio";
    }

    private String formatTimeMs(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void setupSoundProfiles() {
        // Por defecto desactivados (currentActiveProfile = -1)
        highlightSelectedProfile(-1);

        if (switchAcousticProfiles != null) {
            switchAcousticProfiles.setOnCheckedChangeListener((bv, isChecked) -> {
                if (isUpdatingProfileSwitchProgrammatically) return;
                if (isChecked) {
                    int targetIndex = (lastSelectedProfileIndex >= 0 && lastSelectedProfileIndex <= 4)
                        ? lastSelectedProfileIndex : 0;
                    activateAcousticProfile(targetIndex);
                } else {
                    deactivateAcousticProfiles(true);
                }
            });
        }

        // Tocar cualquier tarjeta activa ese perfil; tocar el perfil ya activo lo desactiva
        cardPresetRetro90s.setOnClickListener(v -> toggleAcousticProfile(0));
        cardPresetCarSubwoofer.setOnClickListener(v -> toggleAcousticProfile(1));
        cardPresetClub.setOnClickListener(v -> toggleAcousticProfile(2));
        cardPresetVinyl.setOnClickListener(v -> toggleAcousticProfile(3));
        cardPresetFlat.setOnClickListener(v -> toggleAcousticProfile(4));
    }

    private void toggleAcousticProfile(int index) {
        if (currentActiveProfile == index) {
            // Si ya estaba activo, el usuario lo desactiva al tocarlo de nuevo
            deactivateAcousticProfiles(true);
        } else {
            activateAcousticProfile(index);
        }
    }

    private void activateAcousticProfile(int index) {
        this.lastSelectedProfileIndex = index;
        if (dspService != null) {
            dspService.setActiveAcousticProfile(index);
            updateMaxSoundUi(false);
            updateSonyXb66Ui(false);
        }
        highlightSelectedProfile(index);

        switch (index) {
            case 0:
                // 01 Estéreo 90s (Sony MegaBass / Kenwood Loudness):
                // Contorno Fletcher-Munson: +9.5dB (65Hz), +8.0dB (200Hz), -1.0dB (600Hz), +1.5dB (4kHz), +5.0dB (16-20kHz Air)
                applyFullStudioProfile(
                    9.5f, 8.0f, -1.0f, 1.5f, 5.0f,
                    550, 1.6f, 0.0f,
                    0, true, "Estéreo 90s (MegaBass / Kenwood)"
                );
                break;
            case 1:
                // 02 12'' Car Subwoofer (Cajón Porteado 35Hz + Rockford Fosgate 45Hz Punch EQ + Cabin Gain):
                // +14.0dB (65Hz Sub), +5.5dB (200Hz Crossover 80Hz), -1.5dB (600Hz), +2.0dB (4kHz), +4.5dB (20kHz Tweeter)
                applyFullStudioProfile(
                    14.0f, 5.5f, -1.5f, 2.0f, 4.5f,
                    850, 2.4f, 0.0f,
                    0, true, "12'' Car Subwoofer (Rockford 45Hz)"
                );
                break;
            case 2:
                // 03 Pegada Club (Funktion-One Dance Stack F218 Sub + F215 Kick-Bin):
                // +7.5dB (65Hz F218), +11.5dB (90-200Hz F215 Kick), -1.5dB (600Hz LR24), +3.0dB (4kHz Horns), +4.0dB (20kHz)
                applyFullStudioProfile(
                    7.5f, 11.5f, -1.5f, 3.0f, 4.0f,
                    650, 1.8f, 0.0f,
                    2, true, "Club Punch (Funktion-One)"
                );
                break;
            case 3:
                // 04 Vinyl & Tube Warmth 70s (McIntosh / Marantz Tube Output Transformer + RIAA):
                // +5.5dB (65Hz), +7.5dB (200Hz), +2.5dB (250-600Hz Cuerpo Valvular!), +1.0dB (4kHz), -1.5dB (Roll-Off Agudo)
                applyFullStudioProfile(
                    5.5f, 7.5f, 2.5f, 1.0f, -1.5f,
                    450, 1.2f, 0.5f,
                    1, false, "Vinyl & Tube Warmth 70s"
                );
                break;
            case 4:
                // 05 Audiófilo Directo (Bit-Perfect Studio Reference 0.0 dB):
                // 0.0 dB en las 5 bandas, 0% Excitador, 0.0 dB ALC, 0.0 dB Preamp, Filtro ESS Linear Phase (9)
                applyFullStudioProfile(
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0, 0.0f, 0.0f,
                    9, true, "Audiófilo Directo (Bit-Perfect 0.0 dB)"
                );
                break;
        }
    }

    private void deactivateAcousticProfiles(boolean notifyUser) {
        if (dspService != null) {
            dspService.deactivateAcousticProfile();
            syncSlidersFromDsp();
        }
        highlightSelectedProfile(-1);
        if (notifyUser) {
            tvHeaderDacBadge.setText("ESS Sabre ES9219 • 2.0V Active");
            Toast.makeText(this, "Acoustic Profiles disabled", Toast.LENGTH_SHORT).show();
        }
    }

    private void highlightSelectedProfile(int index) {
        this.currentActiveProfile = index;
        if (index >= 0 && index <= 4) {
            this.lastSelectedProfileIndex = index;
        }

        if (switchAcousticProfiles != null) {
            isUpdatingProfileSwitchProgrammatically = true;
            switchAcousticProfiles.setChecked(index >= 0);
            isUpdatingProfileSwitchProgrammatically = false;
        }

        if (tvAcousticProfilesStatus != null) {
            if (index < 0) {
                tvAcousticProfilesStatus.setText("Disabled by default • Tap a profile or toggle switch");
                tvAcousticProfilesStatus.setTextColor(getColor(R.color.ios_text_secondary));
            } else {
                String[] names = {
                    "90s Stereo (MegaBass)",
                    "12'' Car Subwoofer",
                    "Club Punch (Funktion-One)",
                    "Vinyl & Tube Warmth",
                    "Audiophile Direct (0.0 dB)"
                };
                tvAcousticProfilesStatus.setText("● Active: " + names[index] + " (Tap again to disable)");
                tvAcousticProfilesStatus.setTextColor(getColor(R.color.ios_cyan));
            }
        }

        tvTitleRetro90s.setTextColor(getColor(index == 0 ? R.color.ios_orange : R.color.ios_text_primary));
        tvTitleCarSubwoofer.setTextColor(getColor(index == 1 ? R.color.ios_cyan : R.color.ios_text_primary));
        tvTitleClub.setTextColor(getColor(index == 2 ? R.color.ios_blue : R.color.ios_text_primary));
        tvTitleVinyl.setTextColor(getColor(index == 3 ? R.color.ios_yellow : R.color.ios_text_primary));
        tvTitleFlat.setTextColor(getColor(index == 4 ? R.color.ios_green : R.color.ios_text_primary));

        cardPresetRetro90s.setBackgroundResource(index == 0 ? R.drawable.philips_segment_active : R.drawable.studio_inset_well);
        cardPresetCarSubwoofer.setBackgroundResource(index == 1 ? R.drawable.philips_segment_active : R.drawable.studio_inset_well);
        cardPresetClub.setBackgroundResource(index == 2 ? R.drawable.philips_segment_active : R.drawable.studio_inset_well);
        cardPresetVinyl.setBackgroundResource(index == 3 ? R.drawable.philips_segment_active : R.drawable.studio_inset_well);
        cardPresetFlat.setBackgroundResource(index == 4 ? R.drawable.philips_segment_active : R.drawable.studio_inset_well);

        cardPresetRetro90s.setAlpha(index == 0 ? 1.0f : (index < 0 ? 0.72f : 0.55f));
        cardPresetCarSubwoofer.setAlpha(index == 1 ? 1.0f : (index < 0 ? 0.72f : 0.55f));
        cardPresetClub.setAlpha(index == 2 ? 1.0f : (index < 0 ? 0.72f : 0.55f));
        cardPresetVinyl.setAlpha(index == 3 ? 1.0f : (index < 0 ? 0.72f : 0.55f));
        cardPresetFlat.setAlpha(index == 4 ? 1.0f : (index < 0 ? 0.72f : 0.55f));
    }

    private void applyFullStudioProfile(
            float subDb,
            float punchDb,
            float lowMidWarmthDb,
            float midPresenceDb,
            float airTrebleDb,
            int hwBassBoost,
            float mbcAlcGainDb,
            float preampGainDb,
            int essFilter,
            boolean force2V,
            String name) {
        if (dspService != null) {
            dspService.setFullStudioCalibration(
                subDb, punchDb, lowMidWarmthDb, midPresenceDb, airTrebleDb,
                hwBassBoost, mbcAlcGainDb, preampGainDb
            );
            updateAllAdvancedSeekBars(
                subDb, punchDb, lowMidWarmthDb, midPresenceDb, airTrebleDb,
                hwBassBoost, mbcAlcGainDb, preampGainDb
            );
        }

        executor.execute(() -> {
            QuadDacManager.setForceHighImpedance(force2V);
            QuadDacManager.setEssFilter(essFilter);
            mainHandler.post(() -> {
                refreshDacHardwareStatus();
                tvHeaderDacBadge.setText(name + (force2V ? " • 2.0V Active" : " • Tube Mode"));
                Toast.makeText(MainActivity.this, "Active profile: " + name, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void setupSmartDjSection() {
        switchSmartDj.setOnCheckedChangeListener((bv, isChecked) -> {
            if (dspService != null) {
                dspService.getAiAnalyzer().setAiActive(isChecked);
            }
            if (!isChecked) {
                tvSmartDjStatus.setText("Manual Mode Active");
                tvSmartDjDesc.setText("Activa el switch para que la IA auto-calibre cada canción sin saltos bruscos.");
            }
        });

        switchLiveDynamicEq.setOnCheckedChangeListener((bv, isChecked) -> {
            if (dspService != null) {
                dspService.getAiAnalyzer().setLiveDynamicEqActive(isChecked);
            }
            if (!isChecked) {
                tvDynamicStemMoment.setText("Active Moment: 🎵 Balanced Dynamics");
                tvDynamicStemAction.setText("Balanced curve. Turn switch ON for live 3D psychoacoustic modulation.");
                if (tvDynamicIntensityVal != null) tvDynamicIntensityVal.setText("Modulation: 0%");
                if (progressLiveDynamicIntensity != null) progressLiveDynamicIntensity.setProgress(0);
                if (tvDynamicStemTelemetry != null) tvDynamicStemTelemetry.setText("MODULATION STANDBY • BASE CURVE ACTIVE");
                if (dspService != null) {
                    dspService.setMasterBassPercent(dspService.getMasterPercent());
                    syncSlidersFromDsp();
                }
            } else {
                if (dspService != null) {
                    int modIntensity = dspService.getAiAnalyzer().getCurrentModulationIntensity();
                    if (tvDynamicIntensityVal != null) tvDynamicIntensityVal.setText("Modulation: " + modIntensity + "%");
                    if (progressLiveDynamicIntensity != null) progressLiveDynamicIntensity.setProgress(modIntensity);
                    if (tvDynamicStemTelemetry != null) tvDynamicStemTelemetry.setText(dspService.getAiAnalyzer().getCurrentTelemetryLine());
                }
                Toast.makeText(this, "🎛️ Live Dynamic EQ 3D Active: Continuous modulation (160ms) in background", Toast.LENGTH_SHORT).show();
            }
        });

        btnReanalyzeTrack.setOnClickListener(v -> {
            if (dspService != null) {
                dspService.getAiAnalyzer().unlockAndReanalyze();
                Toast.makeText(this, "Resetting analysis for this track...", Toast.LENGTH_SHORT).show();
            }
        });

        btnAskAiDialog.setOnClickListener(v -> showAskAiDialog());
    }

    private void showAskAiDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_ask_ai, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        EditText etPrompt = dialogView.findViewById(R.id.dialog_et_prompt);
        Spinner spinnerProvider = dialogView.findViewById(R.id.dialog_spinner_provider);
        EditText etApiKey = dialogView.findViewById(R.id.dialog_et_api_key);
        Button btnCancel = dialogView.findViewById(R.id.dialog_btn_cancel);
        Button btnGenerate = dialogView.findViewById(R.id.dialog_btn_generate);

        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            AiApiClient.PROVIDERS
        );
        spinnerProvider.setAdapter(providerAdapter);

        // Load saved provider & key
        String savedProv = aiApiClient.getSavedProvider();
        for (int i = 0; i < AiApiClient.PROVIDERS.length; i++) {
            if (AiApiClient.PROVIDERS[i].equals(savedProv)) {
                spinnerProvider.setSelection(i);
                break;
            }
        }
        etApiKey.setText(aiApiClient.getSavedApiKey());

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnGenerate.setOnClickListener(v -> {
            String prompt = etPrompt.getText().toString().trim();
            String key = etApiKey.getText().toString().trim();
            String prov = spinnerProvider.getSelectedItem().toString();

            if (prompt.isEmpty()) {
                Toast.makeText(this, "Please describe the sound you want to achieve", Toast.LENGTH_SHORT).show();
                return;
            }

            aiApiClient.saveSettings(prov, key, aiApiClient.getSavedCustomUrl());
            btnGenerate.setEnabled(false);
            btnGenerate.setText("⏳ Calibrando...");

            aiApiClient.generateEqFromPrompt(prompt, new AiApiClient.AiApiCallback() {
                @Override
                public void onSuccess(AiApiClient.AiEqResult result) {
                    btnGenerate.setEnabled(true);
                    dialog.dismiss();

                    // 1. Philips / Sony o Calibración Paramétrica completa de 8 parámetros
                    if (result.enablePhilipsMax && !result.enableSonyXb66) {
                        if (dspService != null) dspService.setMaxSound(true);
                        updateMaxSoundUi(true);
                        updateSonyXb66Ui(false);
                        syncSlidersFromDsp();
                    } else if (result.enableSonyXb66 && !result.enablePhilipsMax) {
                        if (dspService != null) dspService.setSonyXb66Sound(true);
                        updateSonyXb66Ui(true);
                        updateMaxSoundUi(false);
                        syncSlidersFromDsp();
                    } else {
                        if (dspService != null && dspService.isMaxSoundActive()) {
                            dspService.setMaxSound(false);
                            updateMaxSoundUi(false);
                        }
                        if (dspService != null && dspService.isSonyXb66Active()) {
                            dspService.setSonyXb66Sound(false);
                            updateSonyXb66Ui(false);
                        }
                        applyFullStudioProfile(
                            result.subBassDb,
                            result.punchDb,
                            result.warmthDb,
                            result.midPresenceDb,
                            result.airDb,
                            result.bassBoost,
                            result.mbcBassAlcDb,
                            result.preampGainDb,
                            result.essFilterIndex,
                            result.forceHighImp,
                            result.title
                        );
                    }

                    updateDbbUi(result.dbbLevel);
                    updateDscUi(result.dscPreset);

                    // 2. Master Bass percent (indicador visual en UI)
                    seekMasterBass.setProgress(result.masterBassPercent);
                    tvMasterPercent.setText(result.masterBassPercent + "%");

                    // 3. Perfil acústico highlight (-1 si es curva IA personalizada)
                    if (result.acousticProfileIndex >= 0 && result.acousticProfileIndex <= 4) {
                        highlightSelectedProfile(result.acousticProfileIndex);
                    } else {
                        highlightSelectedProfile(-1);
                    }

                    Toast.makeText(MainActivity.this, "✨ " + result.title + "\n" + result.description, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onError(String errorMessage) {
                    btnGenerate.setEnabled(true);
                    btnGenerate.setText("✨ Calibrar con IA");
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });

        dialog.show();
    }

    private void setupAdvancedSection() {
        btnToggleAdvanced.setOnClickListener(v -> {
            boolean isVisible = layoutAdvancedContent.getVisibility() == View.VISIBLE;
            layoutAdvancedContent.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            btnToggleAdvanced.setText(isVisible
                ? "Advanced Settings, 8-Ch Parametric EQ & Quad DAC ▼"
                : "Advanced Settings, 8-Ch Parametric EQ & Quad DAC ▲");
        });

        if (btnResetParametricEq != null) {
            btnResetParametricEq.setOnClickListener(v -> {
                if (dspService != null) {
                    dspService.setFullStudioCalibration(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0, 0.0f, 0.0f);
                }
                highlightSelectedProfile(-1);
                updateAllAdvancedSeekBars(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0, 0.0f, 0.0f);
                Toast.makeText(this, "Parametric EQ reset to Flat (0.0 dB)", Toast.LENGTH_SHORT).show();
            });
        }

        // 1. Sub-Bass 32-65Hz (-6.0 dB to +16.0 dB, step 0.5 dB, progress 12 = 0.0 dB)
        seekAdvSub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float db = (progress - 12) * 0.5f;
                tvAdvSubDb.setText(formatSignedDb(db));
                if (fromUser && dspService != null) {
                    highlightSelectedProfile(-1);
                    dspService.setSubBassGain(db);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 2. Punch 90-200Hz (-6.0 dB to +16.0 dB, step 0.5 dB, progress 12 = 0.0 dB)
        seekAdvPunch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float db = (progress - 12) * 0.5f;
                tvAdvPunchDb.setText(formatSignedDb(db));
                if (fromUser && dspService != null) {
                    highlightSelectedProfile(-1);
                    dspService.setPunchGain(db);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 3. Low-Mid Warmth / Anti-Mud 250-600Hz (-6.0 dB to +6.0 dB, step 0.5 dB, progress 12 = 0.0 dB)
        seekAdvClarity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float db = (progress - 12) * 0.5f;
                tvAdvClarityDb.setText(formatSignedDb(db));
                if (fromUser && dspService != null) {
                    highlightSelectedProfile(-1);
                    dspService.setClarityGain(db);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 4. Vocal & Instrument Presence 1-4kHz (-6.0 dB to +10.0 dB, step 0.5 dB, progress 12 = 0.0 dB)
        if (seekAdvPresence != null) {
            seekAdvPresence.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float db = (progress - 12) * 0.5f;
                    tvAdvPresenceDb.setText(formatSignedDb(db));
                    if (fromUser && dspService != null) {
                        highlightSelectedProfile(-1);
                        dspService.setMidPresenceGain(db);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // 5. Super-Tweeter Brilliance & Air 8-20kHz (-6.0 dB to +12.0 dB, step 0.5 dB, progress 12 = 0.0 dB)
        if (seekAdvAir != null) {
            seekAdvAir.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float db = (progress - 12) * 0.5f;
                    tvAdvAirDb.setText(formatSignedDb(db));
                    if (fromUser && dspService != null) {
                        highlightSelectedProfile(-1);
                        dspService.setAirGain(db);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // 6. Hardware Harmonic Exciter (0% to 100% -> 0 to 1000)
        if (seekAdvExciter != null) {
            seekAdvExciter.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvAdvExciterVal.setText(progress + "%");
                    if (fromUser && dspService != null) {
                        highlightSelectedProfile(-1);
                        dspService.setBassBoost(progress * 10);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // 7. Dynamic Bass Compressor ALC / DBFB Sustain (0.0 dB to +4.0 dB, step 0.1 dB)
        if (seekAdvAlc != null) {
            seekAdvAlc.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float db = progress * 0.1f;
                    tvAdvAlcDb.setText(String.format(Locale.US, "+%.1f dB", db));
                    if (fromUser && dspService != null) {
                        highlightSelectedProfile(-1);
                        dspService.setMbcBassAlcGain(db);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // 8. Pre-Amplifier / Headroom Input Gain (-6.0 dB to +3.0 dB, step 0.5 dB, progress 12 = 0.0 dB)
        if (seekAdvPreamp != null) {
            seekAdvPreamp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float db = (progress - 12) * 0.5f;
                    tvAdvPreampDb.setText(formatSignedDb(db));
                    if (fromUser && dspService != null) {
                        highlightSelectedProfile(-1);
                        dspService.setPreampGain(db);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Quad DAC
        btnRefreshDac.setOnClickListener(v -> refreshDacHardwareStatus());

        switchForceHighImp.setOnCheckedChangeListener((bv, isChecked) -> {
            executor.execute(() -> {
                boolean success = QuadDacManager.setForceHighImpedance(isChecked);
                mainHandler.post(() -> {
                    if (success) {
                        Toast.makeText(MainActivity.this,
                            isChecked ? "2.0 Vrms mode activated on ESS Sabre" : "Automatic mode restored",
                            Toast.LENGTH_SHORT).show();
                        refreshDacHardwareStatus();
                    }
                });
            });
        });

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            QuadDacManager.ESS_FILTERS
        );
        spinnerEssFilter.setAdapter(adapter);

        spinnerEssFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                executor.execute(() -> QuadDacManager.setEssFilter(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Logs
        tvConsoleLogs.setText(AppLogger.getAllLogs());
        AppLogger.setListener(line -> {
            tvConsoleLogs.setText(AppLogger.getAllLogs());
            if (scrollLogs != null) {
                scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
            }
        });

        btnClearLogs.setOnClickListener(v -> {
            AppLogger.clear();
            tvConsoleLogs.setText(AppLogger.getAllLogs());
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        btnCopyLogs.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("V60BassLab_Logs", AppLogger.getAllLogs());
            if (cm != null) cm.setPrimaryClip(clip);
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        btnExportLogs.setOnClickListener(v -> {
            File file = AppLogger.exportLogsToDownload();
            if (file != null) {
                Toast.makeText(this, "Logs saved to:\n" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Error saving logs", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String formatSignedDb(float db) {
        if (Math.abs(db) < 0.05f) return "0.0 dB";
        return String.format(Locale.US, db > 0 ? "+%.1f dB" : "%.1f dB", db);
    }

    private void syncSlidersFromDsp() {
        if (dspService == null) return;
        updateAllAdvancedSeekBars(
            dspService.getCurrentSubBassDb(),
            dspService.getCurrentPunchDb(),
            dspService.getCurrentClarityDb(),
            dspService.getCurrentMidPresenceDb(),
            dspService.getCurrentAirDb(),
            dspService.getCurrentBassBoost(),
            dspService.getCurrentMbcSubPostGainDb(),
            dspService.getCurrentPreampGainDb()
        );
    }

    private void updateAdvancedSeekBars(float sub, float punch, float clarity) {
        if (dspService != null) {
            updateAllAdvancedSeekBars(
                sub, punch, clarity,
                dspService.getCurrentMidPresenceDb(),
                dspService.getCurrentAirDb(),
                dspService.getCurrentBassBoost(),
                dspService.getCurrentMbcSubPostGainDb(),
                dspService.getCurrentPreampGainDb()
            );
        } else {
            updateAllAdvancedSeekBars(sub, punch, clarity, 1.5f, 2.5f, 450, 1.5f, 0.0f);
        }
    }

    private void updateAllAdvancedSeekBars(
            float sub,
            float punch,
            float clarity,
            float midPresence,
            float air,
            int hwBoost,
            float mbcAlcDb,
            float preampDb) {
        int subProg = Math.max(0, Math.min(44, Math.round((sub + 6.0f) * 2.0f)));
        int punchProg = Math.max(0, Math.min(44, Math.round((punch + 6.0f) * 2.0f)));
        int clarityProg = Math.max(0, Math.min(24, Math.round((clarity + 6.0f) * 2.0f)));
        int presenceProg = Math.max(0, Math.min(32, Math.round((midPresence + 6.0f) * 2.0f)));
        int airProg = Math.max(0, Math.min(36, Math.round((air + 6.0f) * 2.0f)));
        int exciterProg = Math.max(0, Math.min(100, Math.round(hwBoost / 10.0f)));
        int alcProg = Math.max(0, Math.min(40, Math.round(mbcAlcDb * 10.0f)));
        int preampProg = Math.max(0, Math.min(18, Math.round((preampDb + 6.0f) * 2.0f)));

        if (seekAdvSub != null) seekAdvSub.setProgress(subProg);
        if (seekAdvPunch != null) seekAdvPunch.setProgress(punchProg);
        if (seekAdvClarity != null) seekAdvClarity.setProgress(clarityProg);
        if (seekAdvPresence != null) seekAdvPresence.setProgress(presenceProg);
        if (seekAdvAir != null) seekAdvAir.setProgress(airProg);
        if (seekAdvExciter != null) seekAdvExciter.setProgress(exciterProg);
        if (seekAdvAlc != null) seekAdvAlc.setProgress(alcProg);
        if (seekAdvPreamp != null) seekAdvPreamp.setProgress(preampProg);

        if (tvAdvSubDb != null) tvAdvSubDb.setText(formatSignedDb(sub));
        if (tvAdvPunchDb != null) tvAdvPunchDb.setText(formatSignedDb(punch));
        if (tvAdvClarityDb != null) tvAdvClarityDb.setText(formatSignedDb(clarity));
        if (tvAdvPresenceDb != null) tvAdvPresenceDb.setText(formatSignedDb(midPresence));
        if (tvAdvAirDb != null) tvAdvAirDb.setText(formatSignedDb(air));
        if (tvAdvExciterVal != null) tvAdvExciterVal.setText(exciterProg + "%");
        if (tvAdvAlcDb != null) tvAdvAlcDb.setText(String.format(Locale.US, "+%.1f dB", Math.max(0.0f, mbcAlcDb)));
        if (tvAdvPreampDb != null) tvAdvPreampDb.setText(formatSignedDb(preampDb));
    }

    private void refreshDacHardwareStatus() {
        executor.execute(() -> {
            QuadDacManager.DacStatus status = QuadDacManager.getStatus();
            mainHandler.post(() -> {
                String impStr = (status.leftImpedance > 0 || status.rightImpedance > 0) ?
                    status.leftImpedance + " Ω / " + status.rightImpedance + " Ω" : "No headphones";
                String modeStr = status.getModeDescription();

                tvAdvImpedance.setText("Impedance: " + impStr + " • " + modeStr);
                tvHeaderDacBadge.setText("⚡ ESS Sabre • " + impStr + " (" + modeStr.split(" ")[0] + ")");

                if (status.currentFilter >= 0 && status.currentFilter < QuadDacManager.ESS_FILTERS.length) {
                    spinnerEssFilter.setSelection(status.currentFilter, false);
                }

                switchForceHighImp.setChecked(status.headsetType == 2);
            });
        });
    }

    private void syncControlsWithService() {
        if (dspService != null) {
            switchMasterDsp.setChecked(dspService.isMasterEnabled());
            visualizerView.setPlaying(dspService.isMasterEnabled());
            seekMasterBass.setProgress(dspService.getMasterPercent());
            tvMasterPercent.setText(dspService.getMasterPercent() + "%");

            // Sync Philips FW-M576 & Sony LBT-XB66 UI without mutating active DSP mode
            updateMaxSoundUi(dspService.isMaxSoundActive());
            updateSonyXb66Ui(dspService.isSonyXb66Active());
            updateDbbUi(dspService.getDbbLevel());
            updateGameportUi(dspService.getGameportMode());
            updateDscUi(dspService.getDscPreset());
            highlightSelectedProfile(dspService.getActiveAcousticProfile());

            updateAdvancedSeekBars(
                dspService.getCurrentSubBassDb(),
                dspService.getCurrentPunchDb(),
                dspService.getCurrentClarityDb()
            );

            syncGlobalSubSynthToDsp();

            // Sync AI & Live Dynamic EQ State
            if (switchSmartDj != null) switchSmartDj.setChecked(dspService.getAiAnalyzer().isAiActive());
            if (switchLiveDynamicEq != null) switchLiveDynamicEq.setChecked(dspService.getAiAnalyzer().isLiveDynamicEqActive());
        }
    }

    private void requestAudioPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 101);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (audioCaptureManager != null && audioCaptureManager.isCapturing()) {
            audioCaptureManager.stopCapture();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioCaptureManager != null) {
            audioCaptureManager.stopCapture();
        }
        if (playerEngine != null) {
            playerEngine.stop();
        }
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
