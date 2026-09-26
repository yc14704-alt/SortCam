package com.sortcam.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.sortcam.app.importer.PhotoImportModel;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.sortcam.app.data.AppDatabase;
import com.sortcam.app.model.Category;
import com.sortcam.app.model.PhotoRecord;
import com.sortcam.app.ocr.OcrEngine;
import com.sortcam.app.search.SearchIndex;
import com.sortcam.app.ui.CategoryManagerDialog;
import com.sortcam.app.ui.PhotoAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAMERA = 1001;
    private static final int REQ_AUDIO = 1002;
    private static final int REQ_EDIT = 1003;

    private enum CaptureMode { PHOTO, VIDEO }

    private AppDatabase db;
    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private CaptureMode captureMode = CaptureMode.PHOTO;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;

    private PhotoAdapter photoAdapter;
    private long selectedCategoryId = -1L;
    private PhotoRecord pendingEditedMedia;
    private long quickMediaId = -1L;
    private String mediaFilter = "all";

    private View cameraPanel;
    private View libraryPanel;
    private View quickSaveBar;
    private View btnOpenLibrary;
    private Button btnCapture;
    private Button btnCategoryPicker;
    private Button btnModeToggle;
    private ImageButton btnSwitchCamera;
    private PhotoImportModel importModel;
    private boolean searchAny;
    // ACTION_OPEN_DOCUMENT returns the selected MediaStore/content URI and supports
    // persistable read permission.  SortCam stores only this URI; it never copies the
    // selected image into its own folder.
    private final ActivityResultLauncher<Intent> galleryPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                List<Uri> selected = new ArrayList<>();
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    for (int i = 0; i < clip.getItemCount(); i++) selected.add(clip.getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    selected.add(data.getData());
                }
                chooseImportCategory(selected);
            });
    private Button btnCloseLibrary;
    private Button btnFilterAll;
    private Button btnFilterPhotos;
    private Button btnFilterVideos;
    private Button btnQuickTag;
    private TextView txtCameraStatus;
    private TextView txtPhotoCount;
    private TextView txtEmpty;
    private TextView txtQuickSave;
    private TextView txtLatestType;
    private EditText editSearch;
    private ImageView imgLatest;
    private Chronometer recordingTimer;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final Runnable hideQuickBarRunnable = () -> quickSaveBar.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.rootLayout);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);

        db = new AppDatabase(this);
        db.ensureDefaults();

        bindViews();
        setupGallery();
        setupActions();
        importModel = new ViewModelProvider(this).get(PhotoImportModel.class);
        importModel.progress().observe(this, progress -> {
            TextView status = findViewById(R.id.txtImportStatus);
            status.setVisibility(View.VISIBLE);
            status.setText(progress.message);
            findViewById(R.id.btnImportPhotos).setEnabled(!progress.running);
            loadMedia();
        });
        reloadCategories();
        loadMedia();
        showCameraTab();
        updateModeUi();
        ensureCameraPermission();
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        cameraPanel = findViewById(R.id.cameraPanel);
        libraryPanel = findViewById(R.id.libraryPanel);
        quickSaveBar = findViewById(R.id.quickSaveBar);
        btnOpenLibrary = findViewById(R.id.btnOpenLibrary);
        btnCapture = findViewById(R.id.btnCapture);
        btnCategoryPicker = findViewById(R.id.btnCategoryPicker);
        btnModeToggle = findViewById(R.id.btnModeToggle);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnCloseLibrary = findViewById(R.id.btnCloseLibrary);
        btnFilterAll = findViewById(R.id.btnFilterAll);
        btnFilterPhotos = findViewById(R.id.btnFilterPhotos);
        btnFilterVideos = findViewById(R.id.btnFilterVideos);
        btnQuickTag = findViewById(R.id.btnQuickTag);
        txtCameraStatus = findViewById(R.id.txtCameraStatus);
        txtPhotoCount = findViewById(R.id.txtPhotoCount);
        txtEmpty = findViewById(R.id.txtEmpty);
        txtQuickSave = findViewById(R.id.txtQuickSave);
        txtLatestType = findViewById(R.id.txtLatestType);
        editSearch = findViewById(R.id.editSearch);
        imgLatest = findViewById(R.id.imgLatest);
        recordingTimer = findViewById(R.id.recordingTimer);
    }

    private void setupGallery() {
        RecyclerView photoRecycler = findViewById(R.id.photoRecycler);
        photoAdapter = new PhotoAdapter(this, new PhotoAdapter.Listener() {
            @Override public void onOpen(PhotoRecord media) { openMedia(media); }
            @Override public void onMore(PhotoRecord media) { showMediaActions(media); }
        });
        photoRecycler.setLayoutManager(new GridLayoutManager(this, 3));
        photoRecycler.setAdapter(photoAdapter);
    }

    private void setupActions() {
        findViewById(R.id.btnImportPhotos).setOnClickListener(v -> {
            if (importModel.isRunning()) return;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            galleryPicker.launch(pick);
        });
        ((RadioGroup) findViewById(R.id.searchMode)).setOnCheckedChangeListener((group, checked) -> {
            searchAny = checked == R.id.searchAny;
            loadMedia();
        });
        btnCategoryPicker.setOnClickListener(v -> showCategoryPicker());
        findViewById(R.id.btnManageCategories).setOnClickListener(v -> showCategoryManager());
        btnCapture.setOnClickListener(v -> {
            if (captureMode == CaptureMode.PHOTO) capturePhoto();
            else toggleVideoRecording();
        });
        btnModeToggle.setOnClickListener(v -> toggleCaptureMode());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
        btnOpenLibrary.setOnClickListener(v -> showLibraryTab());
        btnCloseLibrary.setOnClickListener(v -> showCameraTab());
        btnQuickTag.setOnClickListener(v -> {
            PhotoRecord current = db.getPhoto(quickMediaId);
            if (current != null) showEditMetadataDialog(current);
        });

        btnFilterAll.setOnClickListener(v -> setMediaFilter("all"));
        btnFilterPhotos.setOnClickListener(v -> setMediaFilter(PhotoRecord.TYPE_PHOTO));
        btnFilterVideos.setOnClickListener(v -> setMediaFilter(PhotoRecord.TYPE_VIDEO));

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { loadMedia(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void chooseImportCategory(List<Uri> uris) {
        if (uris.isEmpty() || isFinishing()) return;
        List<Category> categories = db.getCategories();
        String[] labels = new String[categories.size()];
        for (int i = 0; i < categories.size(); i++) labels[i] = categories.get(i).emoji + " " + categories.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(uris.size() + "장 · 저장할 분류 선택")
                .setItems(labels, (dialog, which) -> {
                    Category category = categories.get(which);
                    importModel.start(uris, category.id);
                })
                .setNegativeButton("취소", null)
                .show();
        Toast.makeText(this, "사진을 복사하지 않고 원본을 연결해 글자·숫자를 인식합니다.", Toast.LENGTH_LONG).show();
    }

    private void reloadCategories() {
        List<Category> categories = db.getCategories();
        if (categories.isEmpty()) return;
        boolean selectedStillExists = false;
        for (Category c : categories) {
            if (c.id == selectedCategoryId) {
                selectedStillExists = true;
                break;
            }
        }
        if (!selectedStillExists) selectedCategoryId = categories.get(0).id;
        updateSelectedCategoryLabel();
    }

    private void updateSelectedCategoryLabel() {
        Category c = db.getCategory(selectedCategoryId);
        if (c == null) return;
        btnCategoryPicker.setText(c.emoji + " " + c.name + "  ▾");
        if (activeRecording == null) {
            txtCameraStatus.setText(c.emoji + " " + c.name + " · " + (captureMode == CaptureMode.VIDEO ? "동영상" : "사진"));
        }
    }

    private void showCategoryPicker() {
        if (activeRecording != null) {
            Toast.makeText(this, "녹화를 끝낸 뒤 분류를 변경할 수 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Category> categories = db.getCategories();
        if (categories.isEmpty()) return;
        String[] labels = new String[categories.size() + 1];
        for (int i = 0; i < categories.size(); i++) {
            Category c = categories.get(i);
            labels[i] = c.emoji + "  " + c.name;
        }
        labels[categories.size()] = "⚙  분류 추가·수정";

        new AlertDialog.Builder(this)
                .setTitle("촬영할 분류 선택")
                .setItems(labels, (dialog, which) -> {
                    if (which == categories.size()) {
                        showCategoryManager();
                    } else {
                        selectedCategoryId = categories.get(which).id;
                        updateSelectedCategoryLabel();
                    }
                })
                .show();
    }

    private void showCameraTab() {
        libraryPanel.setVisibility(View.GONE);
        cameraPanel.setVisibility(View.VISIBLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        findViewById(R.id.rootLayout).setBackgroundColor(Color.BLACK);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        updateSelectedCategoryLabel();
    }

    private void showLibraryTab() {
        if (activeRecording != null) {
            Toast.makeText(this, "녹화 중에는 앨범을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        cameraPanel.setVisibility(View.GONE);
        libraryPanel.setVisibility(View.VISIBLE);
        getWindow().setStatusBarColor(getColor(R.color.sc_surface));
        getWindow().setNavigationBarColor(getColor(R.color.sc_surface));
        findViewById(R.id.rootLayout).setBackgroundColor(getColor(R.color.sc_surface));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        loadMedia();
    }

    private void ensureCameraPermission() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            txtCameraStatus.setText("이 기기에서 카메라를 사용할 수 없습니다.");
            btnCapture.setEnabled(false);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void maybeRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                txtCameraStatus.setText("카메라 권한이 필요합니다.");
                btnCapture.setEnabled(false);
                Toast.makeText(this, R.string.camera_permission, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_AUDIO) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.audio_permission, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                txtCameraStatus.setText("카메라 시작 실패");
                btnCapture.setEnabled(false);
                Toast.makeText(this, "카메라를 시작할 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        try {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
            cameraProvider.unbindAll();

            if (captureMode == CaptureMode.PHOTO) {
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                videoCapture = null;
                cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            } else {
                QualitySelector qualitySelector = QualitySelector.fromOrderedList(
                        Arrays.asList(Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
                Recorder recorder = new Recorder.Builder().setQualitySelector(qualitySelector).build();
                videoCapture = VideoCapture.withOutput(recorder);
                imageCapture = null;
                cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            }

            btnCapture.setEnabled(true);
            updateModeUi();
            updateSelectedCategoryLabel();
        } catch (Exception e) {
            btnCapture.setEnabled(false);
            txtCameraStatus.setText("카메라 설정 실패");
            Toast.makeText(this, "이 카메라 모드를 사용할 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleCaptureMode() {
        if (activeRecording != null) return;
        captureMode = captureMode == CaptureMode.PHOTO ? CaptureMode.VIDEO : CaptureMode.PHOTO;
        if (captureMode == CaptureMode.VIDEO) maybeRequestAudioPermission();
        updateModeUi();
        bindCameraUseCases();
    }

    private void updateModeUi() {
        if (activeRecording != null) {
            btnCapture.setBackgroundResource(R.drawable.bg_shutter_recording);
            btnCapture.setText("■");
            btnModeToggle.setText("사진 / ● 동영상");
            return;
        }
        btnCapture.setText("");
        if (captureMode == CaptureMode.PHOTO) {
            btnCapture.setBackgroundResource(R.drawable.bg_shutter_photo);
            btnModeToggle.setText("● 사진 / 동영상");
        } else {
            btnCapture.setBackgroundResource(R.drawable.bg_shutter_video);
            btnModeToggle.setText("사진 / ● 동영상");
        }
    }

    private void switchCamera() {
        if (cameraProvider == null || activeRecording != null) return;
        int target = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        CameraSelector targetSelector = new CameraSelector.Builder().requireLensFacing(target).build();
        try {
            if (!cameraProvider.hasCamera(targetSelector)) {
                Toast.makeText(this, "이 기기에는 선택한 카메라가 없습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            lensFacing = target;
            bindCameraUseCases();
        } catch (Exception e) {
            Toast.makeText(this, "카메라를 전환할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void capturePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "카메라가 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Category category = db.getCategory(selectedCategoryId);
        if (category == null) return;

        btnCapture.setEnabled(false);
        txtCameraStatus.setText("촬영 중…");

        long now = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(now));
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "SortCam_" + stamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, now);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, mediaPathFor(PhotoRecord.TYPE_PHOTO, category.name));

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri uri = outputFileResults.getSavedUri();
                if (uri == null) {
                    onCaptureFailed("저장된 사진 주소를 확인할 수 없습니다.");
                    return;
                }

                String tags = ensureAutoTag("", PhotoRecord.TYPE_PHOTO);
                PhotoRecord media = new PhotoRecord(
                        0L, uri.toString(), category.id, now,
                        tags, "", "", SearchIndex.build(PhotoRecord.TYPE_PHOTO, tags, "", ""),
                        PhotoRecord.TYPE_PHOTO, 0L
                );
                try {
                    media.id = db.insertPhoto(media);
                    afterMediaSaved(media);
                    recognizePhotoInBackground(media.id, uri);
                } catch (Exception e) {
                    btnCapture.setEnabled(true);
                    updateSelectedCategoryLabel();
                    Toast.makeText(MainActivity.this, "사진 정보 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                onCaptureFailed(exception.getMessage());
            }
        });
    }

    private void recognizePhotoInBackground(long mediaId, Uri uri) {
        OcrEngine.recognize(this, uri, new OcrEngine.Callback() {
            @Override
            public void onSuccess(String text) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    PhotoRecord current = db.getPhoto(mediaId);
                    if (current == null) return;
                    String ocr = text == null ? "" : text.trim();
                    String tags = ensureAutoTag(current.tags, current.mediaType);
                    db.updatePhotoMetadata(current.id, tags, current.memo, ocr,
                            SearchIndex.build(current.mediaType, tags, current.memo, ocr));
                    if (libraryPanel.getVisibility() == View.VISIBLE) loadMedia();
                });
            }

            @Override
            public void onError(Exception e) {
                // OCR failure does not interrupt shooting. The photo remains searchable by category and #사진.
            }
        });
    }

    private void toggleVideoRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            return;
        }
        if (videoCapture == null) {
            Toast.makeText(this, "동영상 카메라가 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Category category = db.getCategory(selectedCategoryId);
        if (category == null) return;

        long now = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(now));
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "SortCam_" + stamp + ".mp4");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.DATE_ADDED, now / 1000L);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, mediaPathFor(PhotoRecord.TYPE_VIDEO, category.name));

        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();

        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, outputOptions);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                recordingTimer.setBase(SystemClock.elapsedRealtime());
                recordingTimer.start();
                recordingTimer.setVisibility(View.VISIBLE);
                txtCameraStatus.setText("● REC");
                btnModeToggle.setEnabled(false);
                btnSwitchCamera.setEnabled(false);
                btnCategoryPicker.setEnabled(false);
                updateModeUi();
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalized = (VideoRecordEvent.Finalize) event;
                long durationMs = finalized.getRecordingStats().getRecordedDurationNanos() / 1_000_000L;
                Uri uri = finalized.getOutputResults().getOutputUri();

                recordingTimer.stop();
                recordingTimer.setVisibility(View.GONE);
                activeRecording = null;
                btnModeToggle.setEnabled(true);
                btnSwitchCamera.setEnabled(true);
                btnCategoryPicker.setEnabled(true);
                updateModeUi();
                updateSelectedCategoryLabel();

                if (finalized.hasError() || uri == null || Uri.EMPTY.equals(uri)) {
                    Toast.makeText(this, "동영상 저장에 실패했습니다.", Toast.LENGTH_LONG).show();
                    return;
                }

                String tags = ensureAutoTag("", PhotoRecord.TYPE_VIDEO);
                PhotoRecord media = new PhotoRecord(
                        0L, uri.toString(), category.id, now,
                        tags, "", "", SearchIndex.build(PhotoRecord.TYPE_VIDEO, tags, "", ""),
                        PhotoRecord.TYPE_VIDEO, durationMs
                );
                try {
                    media.id = db.insertPhoto(media);
                    afterMediaSaved(media);
                } catch (Exception e) {
                    Toast.makeText(this, "동영상 정보 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void onCaptureFailed(String message) {
        btnCapture.setEnabled(true);
        updateSelectedCategoryLabel();
        Toast.makeText(this, "촬영 실패: " + (message == null ? "알 수 없는 오류" : message), Toast.LENGTH_LONG).show();
    }

    private void afterMediaSaved(PhotoRecord media) {
        btnCapture.setEnabled(true);
        quickMediaId = media.id;
        showQuickSaved(media);
        loadMedia();
        updateSelectedCategoryLabel();
    }

    private void showQuickSaved(PhotoRecord media) {
        mainHandler.removeCallbacks(hideQuickBarRunnable);
        txtQuickSave.setText((media.isVideo() ? "동영상 저장됨 · #동영상" : "사진 저장됨 · #사진"));
        quickSaveBar.setVisibility(View.VISIBLE);
        mainHandler.postDelayed(hideQuickBarRunnable, 5500L);
    }

    private String ensureAutoTag(String tags, String mediaType) {
        String current = tags == null ? "" : tags.trim();
        String auto = PhotoRecord.TYPE_VIDEO.equals(mediaType) ? "#동영상" : "#사진";
        String normalizedCurrent = SearchIndex.normalize(current);
        String normalizedAuto = SearchIndex.normalize(auto);
        if (normalizedCurrent.contains(normalizedAuto)) return current;
        return (auto + (current.isEmpty() ? "" : " " + current)).trim();
    }

    private void setMediaFilter(String filter) {
        mediaFilter = filter;
        styleFilterButton(btnFilterAll, "all".equals(filter));
        styleFilterButton(btnFilterPhotos, PhotoRecord.TYPE_PHOTO.equals(filter));
        styleFilterButton(btnFilterVideos, PhotoRecord.TYPE_VIDEO.equals(filter));
        loadMedia();
    }

    private void styleFilterButton(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_secondary_button);
        button.setTextColor(selected ? Color.WHITE : getColor(R.color.sc_text));
    }

    private void loadMedia() {
        if (photoAdapter == null || editSearch == null) return;
        List<Category> categories = db.getCategories();
        Map<Long, Category> map = new HashMap<>();
        for (Category c : categories) map.put(c.id, c);

        String query = editSearch.getText().toString();
        List<PhotoRecord> all = db.getPhotos();
        List<PhotoRecord> filtered = new ArrayList<>();
        for (PhotoRecord p : all) {
            if (!"all".equals(mediaFilter) && !mediaFilter.equals(p.mediaType)) continue;
            if (SearchIndex.matches(p, map.get(p.categoryId), query, searchAny)) filtered.add(p);
        }

        photoAdapter.submit(filtered, categories);
        String prefix = query.trim().isEmpty() ? "" : "검색 결과 ";
        txtPhotoCount.setText(prefix + filtered.size() + "개 · 검색어는 띄어쓰기 또는 쉼표로 구분");
        txtEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.photoRecycler).setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
        updateLatestThumbnail(all);
    }

    private void updateLatestThumbnail(List<PhotoRecord> all) {
        if (all == null || all.isEmpty()) {
            imgLatest.setImageResource(R.drawable.ic_app_icon);
            txtLatestType.setText("");
            return;
        }
        PhotoRecord latest = all.get(0);
        txtLatestType.setText(latest.isVideo() ? "▶" : "");
        imgLatest.setTag(latest.uri);
        mediaExecutor.execute(() -> {
            try {
                android.graphics.Bitmap bitmap = getContentResolver().loadThumbnail(
                        Uri.parse(latest.uri), new Size(240, 240), null);
                mainHandler.post(() -> {
                    if (latest.uri.equals(imgLatest.getTag())) imgLatest.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> imgLatest.setImageResource(R.drawable.ic_app_icon));
            }
        });
    }

    private void openMedia(PhotoRecord media) {
        try {
            Uri uri = Uri.parse(media.uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, media.isVideo() ? "video/*" : "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "미디어를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareMedia(PhotoRecord media) {
        try {
            Uri uri = Uri.parse(media.uri);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(media.isVideo() ? "video/*" : "image/*");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            StringBuilder text = new StringBuilder();
            if (media.memo != null && !media.memo.trim().isEmpty()) text.append(media.memo.trim());
            if (media.tags != null && !media.tags.trim().isEmpty()) {
                if (text.length() > 0) text.append("\n");
                text.append(media.tags.trim());
            }
            if (text.length() > 0) send.putExtra(Intent.EXTRA_TEXT, text.toString());
            send.setClipData(ClipData.newRawUri("SortCam media", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, media.isVideo() ? "동영상 공유" : "사진 공유"));
        } catch (Exception e) {
            Toast.makeText(this, "공유할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void editMedia(PhotoRecord media) {
        if (db.isLinkedPhoto(media.id)) {
            new AlertDialog.Builder(this).setTitle("연결된 원본 사진")
                    .setMessage("원본 편집은 휴대폰 갤러리에서 해주세요. 편집 후에는 OCR 다시 읽기로 검색 내용을 갱신할 수 있습니다.")
                    .setPositiveButton("사진 열기", (d, w) -> openMedia(media)).setNegativeButton("취소", null).show();
            return;
        }
        try {
            Uri uri = Uri.parse(media.uri);
            Intent edit = new Intent(Intent.ACTION_EDIT);
            edit.setDataAndType(uri, media.isVideo() ? "video/*" : "image/*");
            edit.setClipData(ClipData.newRawUri("SortCam media", uri));
            edit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            pendingEditedMedia = media;
            startActivityForResult(Intent.createChooser(edit, media.isVideo() ? "동영상 편집" : "사진 편집"), REQ_EDIT);
        } catch (Exception e) {
            Toast.makeText(this, media.isVideo()
                    ? "사용 가능한 동영상 편집기를 찾지 못했습니다."
                    : "사용 가능한 사진 편집기를 찾지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void showMediaActions(PhotoRecord media) {
        String[] items;
        if (media.isVideo()) {
            items = new String[]{"동영상 보기", "공유", "동영상 편집", "태그·메모 수정", "분류 이동", "삭제"};
        } else {
            items = new String[]{"사진 보기", "공유", "사진 편집", "태그·메모 수정", "OCR 다시 읽기", "분류 이동", db.isLinkedPhoto(media.id) ? "연결 해제 (원본 유지)" : "삭제"};
        }
        new AlertDialog.Builder(this)
                .setTitle(media.isVideo() ? "동영상 관리" : "사진 관리")
                .setItems(items, (dialog, which) -> {
                    if (media.isVideo()) {
                        switch (which) {
                            case 0: openMedia(media); break;
                            case 1: shareMedia(media); break;
                            case 2: editMedia(media); break;
                            case 3: showEditMetadataDialog(media); break;
                            case 4: showMoveCategoryDialog(media); break;
                            case 5: confirmDeleteMedia(media); break;
                        }
                    } else {
                        switch (which) {
                            case 0: openMedia(media); break;
                            case 1: shareMedia(media); break;
                            case 2: editMedia(media); break;
                            case 3: showEditMetadataDialog(media); break;
                            case 4: refreshOcr(media); break;
                            case 5: showMoveCategoryDialog(media); break;
                            case 6: confirmDeleteMedia(media); break;
                        }
                    }
                })
                .show();
    }

    private void refreshOcr(PhotoRecord media) {
        if (media.isVideo()) return;
        Toast.makeText(this, "사진 속 글자를 다시 읽는 중…", Toast.LENGTH_SHORT).show();
        OcrEngine.recognize(this, Uri.parse(media.uri), new OcrEngine.Callback() {
            @Override
            public void onSuccess(String text) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    PhotoRecord current = db.getPhoto(media.id);
                    if (current == null) return;
                    String tags = ensureAutoTag(current.tags, current.mediaType);
                    String memo = current.memo == null ? "" : current.memo;
                    String ocr = text == null ? "" : text.trim();
                    db.updatePhotoMetadata(current.id, tags, memo, ocr,
                            SearchIndex.build(current.mediaType, tags, memo, ocr));
                    loadMedia();
                    Toast.makeText(MainActivity.this, "OCR 검색정보를 갱신했습니다.", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "OCR을 다시 읽지 못했습니다.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT && pendingEditedMedia != null) {
            PhotoRecord edited = pendingEditedMedia;
            pendingEditedMedia = null;
            if (edited.isPhoto()) refreshOcr(edited);
            else loadMedia();
        }
    }

    private void showEditMetadataDialog(PhotoRecord media) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_photo_metadata, null, false);
        TextView categoryText = view.findViewById(R.id.txtDialogCategory);
        TextView ocrLabel = view.findViewById(R.id.txtOcrLabel);
        EditText tags = view.findViewById(R.id.editTags);
        EditText memo = view.findViewById(R.id.editMemo);
        EditText ocr = view.findViewById(R.id.editOcr);
        TextView preview = view.findViewById(R.id.txtIndexPreview);
        Category category = db.getCategory(media.categoryId);
        String mediaName = media.isVideo() ? "동영상" : "사진";
        categoryText.setText((category == null ? "📁 미분류" : category.emoji + " " + category.name) + " · " + mediaName);
        tags.setText(ensureAutoTag(media.tags, media.mediaType));
        memo.setText(media.memo);
        ocr.setText(media.ocrText);
        preview.setText(SearchIndex.preview(media.mediaType, media.ocrText));

        if (media.isVideo()) {
            ocrLabel.setVisibility(View.GONE);
            ocr.setVisibility(View.GONE);
        } else {
            ocr.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    preview.setText(SearchIndex.preview(media.mediaType, s.toString()));
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(mediaName + " 정보 수정")
                .setView(view)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (d, w) -> {
                    String tagText = ensureAutoTag(tags.getText().toString().trim(), media.mediaType);
                    String memoText = memo.getText().toString().trim();
                    String ocrText = media.isVideo() ? "" : ocr.getText().toString().trim();
                    db.updatePhotoMetadata(media.id, tagText, memoText, ocrText,
                            SearchIndex.build(media.mediaType, tagText, memoText, ocrText));
                    loadMedia();
                })
                .show();
    }

    private void showMoveCategoryDialog(PhotoRecord media) {
        List<Category> categories = db.getCategories();
        if (categories.isEmpty()) return;
        String[] labels = new String[categories.size()];
        int checked = 0;
        for (int i = 0; i < categories.size(); i++) {
            Category c = categories.get(i);
            labels[i] = c.emoji + " " + c.name;
            if (c.id == media.categoryId) checked = i;
        }
        final int[] selected = {checked};
        new AlertDialog.Builder(this)
                .setTitle("분류 이동")
                .setSingleChoiceItems(labels, checked, (d, which) -> selected[0] = which)
                .setNegativeButton("취소", null)
                .setPositiveButton("이동", (d, w) -> {
                    Category target = categories.get(selected[0]);
                    if (target.id == media.categoryId) return;
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, mediaPathFor(media.mediaType, target.name));
                        if (!db.isLinkedPhoto(media.id)) getContentResolver().update(Uri.parse(media.uri), cv, null, null);
                    } catch (Exception ignored) {
                        // Database category still changes if an OEM blocks physical folder moves.
                    }
                    db.updatePhotoCategory(media.id, target.id);
                    loadMedia();
                    Toast.makeText(this, target.emoji + " " + target.name + "으로 이동했습니다.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmDeleteMedia(PhotoRecord media) {
        String mediaName = media.isVideo() ? "동영상" : "사진";
        boolean linked = db.isLinkedPhoto(media.id);
        new AlertDialog.Builder(this)
                .setTitle(linked ? "사진 연결 해제" : mediaName + " 삭제")
                .setMessage(linked ? "SortCam에서 연결과 검색정보만 제거합니다. 갤러리 원본 사진은 그대로 남습니다." : "휴대폰 갤러리의 원본 " + mediaName + "과 SortCam 검색정보를 함께 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton(linked ? "연결 해제" : "삭제", (d, w) -> {
                    if (linked) {
                        try { getContentResolver().releasePersistableUriPermission(Uri.parse(media.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                        catch (Exception ignored) { }
                    } else {
                        try {
                            if (getContentResolver().delete(Uri.parse(media.uri), null, null) < 1) {
                                Toast.makeText(this, "원본을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        } catch (Exception error) {
                            Toast.makeText(this, "원본을 삭제하지 못했습니다.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    db.deletePhoto(media.id);
                    if (quickMediaId == media.id) {
                        quickMediaId = -1L;
                        quickSaveBar.setVisibility(View.GONE);
                    }
                    loadMedia();
                    Toast.makeText(this, linked ? "연결을 해제했습니다. 원본은 유지됩니다." : mediaName + "을 삭제했습니다.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showCategoryManager() {
        new CategoryManagerDialog(this, new CategoryManagerDialog.Listener() {
            @Override
            public List<Category> loadCategories() {
                return db.getCategories();
            }

            @Override
            public boolean addCategory(String name, String emoji) {
                if (db.categoryNameExists(name, -1L)) {
                    Toast.makeText(MainActivity.this, "같은 이름의 분류가 이미 있습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                try {
                    long id = db.addCategory(name, emoji);
                    selectedCategoryId = id;
                    reloadCategories();
                    loadMedia();
                    return true;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "분류를 추가할 수 없습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean editCategory(Category category, String name, String emoji) {
                if (db.categoryNameExists(name, category.id)) {
                    Toast.makeText(MainActivity.this, "같은 이름의 분류가 이미 있습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (!category.name.equals(name)) moveMediaForCategory(category.id, name);
                db.updateCategory(category.id, name, emoji);
                reloadCategories();
                loadMedia();
                return true;
            }

            @Override
            public boolean deleteCategory(Category category) {
                if (category.isDefault) return false;
                long fallbackId = db.getFirstDefaultCategoryId();
                Category fallback = db.getCategory(fallbackId);
                if (fallback == null) return false;
                moveMediaForCategory(category.id, fallback.name);
                db.deleteCategory(category.id, fallbackId);
                if (selectedCategoryId == category.id) selectedCategoryId = fallbackId;
                reloadCategories();
                loadMedia();
                return true;
            }
        }).show();
    }

    private void moveMediaForCategory(long categoryId, String destinationCategoryName) {
        List<PhotoRecord> media = db.getPhotosByCategory(categoryId);
        for (PhotoRecord item : media) {
            if (db.isLinkedPhoto(item.id)) continue;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, mediaPathFor(item.mediaType, destinationCategoryName));
                getContentResolver().update(Uri.parse(item.uri), cv, null, null);
            } catch (Exception ignored) {
                // Search metadata remains usable even if an OEM blocks a gallery-folder move.
            }
        }
    }

    private String mediaPathFor(String mediaType, String categoryName) {
        String root = PhotoRecord.TYPE_VIDEO.equals(mediaType)
                ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        return root + "/SortCam/" + safeFolderName(categoryName);
    }

    private String safeFolderName(String name) {
        String cleaned = name == null ? "기타" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "기타";
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    @Override
    public void onBackPressed() {
        if (libraryPanel.getVisibility() == View.VISIBLE) {
            showCameraTab();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (photoAdapter != null) photoAdapter.close();
        mediaExecutor.shutdownNow();
        if (db != null) db.close();
        super.onDestroy();
    }
}
