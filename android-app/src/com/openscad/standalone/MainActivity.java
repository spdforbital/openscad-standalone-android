package com.openscad.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BUILD_MARKER = "2026-02-26_00:01_v13";

    private static final int C_BG = Color.parseColor("#0c111a");
    private static final int C_BG_2 = Color.parseColor("#111a27");
    private static final int C_SURFACE = Color.parseColor("#152131");
    private static final int C_SURFACE_2 = Color.parseColor("#1d2c40");
    private static final int C_TOOLBAR = Color.parseColor("#0f1724");
    private static final int C_BORDER = Color.parseColor("#30445f");
    private static final int C_TEXT = Color.parseColor("#e9f0fb");
    private static final int C_TEXT_2 = Color.parseColor("#9fb2cb");
    private static final int C_ACCENT = Color.parseColor("#5fd6ff");
    private static final int C_ACCENT_2 = Color.parseColor("#3f9dff");
    private static final int C_GREEN = Color.parseColor("#7de4b2");
    private static final int C_RED = Color.parseColor("#ff8e9f");
    private static final int C_YELLOW = Color.parseColor("#ffd68d");

    private static final String DEFAULT_FILE = "example.scad";
    private static final int RC_IMPORT_LIBRARY = 4001;

    private static final String DEFAULT_CODE = "// OpenSCAD Example - Parametric Box\n" +
            "box_width = 30;\n" +
            "box_depth = 20;\n" +
            "box_height = 15;\n" +
            "wall_thickness = 2;\n" +
            "corner_radius = 3;\n\n" +
            "module rounded_box(w, d, h, r) {\n" +
            "    hull() {\n" +
            "        for (x = [r, w-r])\n" +
            "            for (y = [r, d-r])\n" +
            "                translate([x, y, 0])\n" +
            "                    cylinder(h=h, r=r, $fn=32);\n" +
            "    }\n" +
            "}\n\n" +
            "module hollow_box() {\n" +
            "    difference() {\n" +
            "        rounded_box(box_width, box_depth, box_height, corner_radius);\n" +
            "        translate([wall_thickness, wall_thickness, wall_thickness])\n" +
            "            rounded_box(\n" +
            "                box_width - 2*wall_thickness,\n" +
            "                box_depth - 2*wall_thickness,\n" +
            "                box_height,\n" +
            "                corner_radius - wall_thickness/2\n" +
            "            );\n" +
            "    }\n" +
            "}\n\n" +
            "hollow_box();\n";

    private final List<String> fileNames = new ArrayList<String>();
    private final SpannableStringBuilder logBuilder = new SpannableStringBuilder();

    private OpenScadRuntime runtime;
    private LibraryManager libraryManager;
    private RuntimeUpdateManager runtimeUpdateManager;
    private ExecutorService executor;
    private Handler mainHandler;
    private RuntimeUpdateManager.RuntimeStatus lastRuntimeStatus;

    private boolean compactLayout;
    private boolean rendering;
    private boolean wireframeMode;
    private boolean axisLinesVisible = true;
    private boolean libraryPreviewMode;
    private String activeLibraryPath;

    private LinearLayout sidebar;
    private LinearLayout consolePanel;
    private TextView statusText;
    private TextView currentTab;
    private TextView consoleOutput;
    private ScrollView consoleScroll;
    private EditText editor;
    private ListView fileList;
    private ArrayAdapter<String> fileAdapter;
    private Button renderButton;
    private Button viewerModeButton;
    private Button axisLinesButton;
    private TextView previewHint;
    private StlGlSurfaceView previewSurface;

    private String currentFile = DEFAULT_FILE;
    private File lastRenderedStl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        runtime = new OpenScadRuntime(this);
        libraryManager = new LibraryManager(this, runtime);
        runtimeUpdateManager = new RuntimeUpdateManager(this, runtime);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        compactLayout = getResources().getConfiguration().screenWidthDp < 700;

        buildUi();
        appendLog("Build " + BUILD_MARKER, C_ACCENT);
        ensureDefaultProject();
        refreshFiles();
        openFile(DEFAULT_FILE);
        warmUpRuntime();
        checkRuntimeUpdateOnBoot();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewSurface != null) {
            previewSurface.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (previewSurface != null) {
            previewSurface.onPause();
        }
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(makePanelGradient(C_BG, C_BG_2, 0, C_BG, false));

        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(compactLayout ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);

        sidebar = buildSidebar();
        if (compactLayout) {
            sidebar.setVisibility(View.GONE);
            main.addView(sidebar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        } else {
            main.addView(sidebar, new LinearLayout.LayoutParams(dp(220),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout editorPanel = buildEditorPanel();
        LinearLayout previewPanel = buildPreviewPanel();

        if (compactLayout) {
            main.addView(editorPanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            main.addView(previewPanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            main.addView(editorPanel, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            main.addView(previewPanel, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        root.addView(main, mainParams);

        consolePanel = buildConsolePanel();
        consolePanel.setVisibility(View.GONE);
        root.addView(consolePanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        setContentView(root);
    }

    private View buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), dp(8), dp(10), dp(8));
        toolbar.setBackground(makePanelGradient(C_TOOLBAR, C_SURFACE, 0, C_BORDER, true));

        TextView logo = new TextView(this);
        logo.setText("OPENSCAD");
        logo.setTextColor(C_ACCENT);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        logo.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        logo.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoParams.rightMargin = dp(10);
        toolbar.addView(logo, logoParams);

        Button filesButton = makeToolbarButton("Files", false);
        filesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleFiles();
            }
        });
        toolbar.addView(filesButton);

        renderButton = makeToolbarButton("Render", true);
        renderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doRender();
            }
        });
        toolbar.addView(renderButton);

        Button saveButton = makeToolbarButton("Save", false);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveCurrentFile(false);
            }
        });
        toolbar.addView(saveButton);

        Button exportButton = makeToolbarButton("Export", false);
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportLastStl();
            }
        });
        toolbar.addView(exportButton);

        Button libsButton = makeToolbarButton("Libs", false);
        libsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLibrariesDialog();
            }
        });
        toolbar.addView(libsButton);

        Button runtimeButton = makeToolbarButton("Runtime", false);
        runtimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRuntimeDialog();
            }
        });
        toolbar.addView(runtimeButton);

        View spacer = new View(this);
        toolbar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setTextColor(C_TEXT_2);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setSingleLine(true);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusText.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusText.setBackground(makePanelGradient(C_SURFACE_2, C_SURFACE, 12, C_BORDER, false));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(190),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.rightMargin = dp(10);
        toolbar.addView(statusText, statusParams);

        Button logButton = makeToolbarButton("Log", false);
        logButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleConsole();
            }
        });
        toolbar.addView(logButton);

        return toolbar;
    }

    private LinearLayout buildSidebar() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackground(makePanelGradient(C_TOOLBAR, C_SURFACE, 0, C_BORDER, false));

        TextView header = new TextView(this);
        header.setText("PROJECT FILES");
        header.setTextColor(C_TEXT);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setLetterSpacing(0.05f);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.setPadding(dp(12), dp(12), dp(12), dp(8));
        side.addView(header);

        Button newButton = makeToolbarButton("+ New", false);
        newButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        newButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                newFile();
            }
        });
        LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        newParams.setMargins(dp(8), 0, dp(8), dp(8));
        side.addView(newButton, newParams);

        fileList = new ListView(this);
        fileList.setDividerHeight(0);
        fileList.setBackgroundColor(Color.TRANSPARENT);
        fileAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, fileNames) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                String name = getItem(position);
                boolean active = name != null && name.equals(currentFile);
                tv.setTextColor(active ? C_BG : C_TEXT);
                tv.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
                tv.setBackground(active
                        ? makePanelGradient(C_ACCENT, C_ACCENT_2, 10, C_ACCENT_2, false)
                        : makePanelGradient(Color.TRANSPARENT, Color.TRANSPARENT, 10, C_BORDER, false));
                tv.setPadding(dp(12), dp(9), dp(12), dp(9));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                return tv;
            }
        };
        fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                openFile(fileNames.get(i));
            }
        });
        fileList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= fileNames.size()) {
                    return true;
                }
                promptDeleteFile(fileNames.get(position));
                return true;
            }
        });
        side.addView(fileList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button refreshButton = makeToolbarButton("Refresh", false);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshFiles();
            }
        });
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        side.addView(refreshButton, refreshParams);

        return side;
    }

    private LinearLayout buildEditorPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(makePanelGradient(C_BG, C_SURFACE, 0, C_BORDER, false));

        currentTab = new TextView(this);
        currentTab.setText(DEFAULT_FILE);
        currentTab.setTextColor(C_TEXT);
        currentTab.setBackground(makePanelGradient(C_TOOLBAR, C_SURFACE, 0, C_BORDER, false));
        currentTab.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        currentTab.setLetterSpacing(0.04f);
        currentTab.setPadding(dp(12), dp(9), dp(12), dp(9));
        currentTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        panel.addView(currentTab, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new LineNumberEditText(this);
        editor.setText(DEFAULT_CODE);
        editor.setTextColor(C_TEXT);
        editor.setHintTextColor(C_TEXT_2);
        editor.setBackground(makePanelGradient(C_SURFACE, C_BG_2, 0, C_BORDER, false));
        editor.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setHorizontallyScrolling(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        panel.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return panel;
    }

    private LinearLayout buildPreviewPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(makePanelGradient(C_BG, C_BG_2, 0, C_BORDER, false));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(makePanelGradient(C_TOOLBAR, C_SURFACE, 0, C_BORDER, false));
        header.setPadding(dp(12), dp(7), dp(12), dp(7));

        TextView title = new TextView(this);
        title.setText("3D VIEWER");
        title.setTextColor(C_TEXT_2);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.05f);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        header.addView(title);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        viewerModeButton = makeToolbarButton("Shaded", false);
        viewerModeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        viewerModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleViewerMode();
            }
        });
        header.addView(viewerModeButton);

        axisLinesButton = makeToolbarButton("Axes On", false);
        axisLinesButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        axisLinesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleAxisLines();
            }
        });
        header.addView(axisLinesButton);

        Button resetButton = makeToolbarButton("Reset", false);
        resetButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (previewSurface != null) {
                    previewSurface.resetCamera();
                }
            }
        });
        header.addView(resetButton);

        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout container = new FrameLayout(this);

        previewSurface = new StlGlSurfaceView(this);
        previewSurface.setBackgroundColor(C_BG_2);
        previewSurface.setWireframeMode(wireframeMode);
        previewSurface.setAxisLinesVisible(axisLinesVisible);
        container.addView(previewSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout axisPad = new FrameLayout(this);
        axisPad.setBackground(makePanelGradient(0xD9101A2A, 0xD9152437, 24, C_BORDER, true));

        int gizmoSize = dp(128);
        FrameLayout.LayoutParams gizmoParams = new FrameLayout.LayoutParams(
                gizmoSize,
                gizmoSize);
        axisPad.setLayoutParams(gizmoParams);
        axisPad.addView(buildGizmoGuides(gizmoSize), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int c = gizmoSize / 2;
        int outer = dp(42);
        int diag = dp(30);
        axisPad.addView(makeGizmoButton(
                "ISO",
                C_ACCENT,
                C_BG,
                C_ACCENT_2,
                StlGlSurfaceView.ViewPreset.ISO,
                c,
                c,
                30));
        axisPad.addView(makeGizmoButton(
                "X",
                0xFFFF6B7A,
                C_BG,
                0xFF3D1016,
                StlGlSurfaceView.ViewPreset.POS_X,
                c + outer,
                c,
                24));
        axisPad.addView(makeGizmoButton(
                "-X",
                0xFF5A313A,
                C_BG,
                0xFFFFD7DD,
                StlGlSurfaceView.ViewPreset.NEG_X,
                c - outer,
                c,
                24));
        axisPad.addView(makeGizmoButton(
                "Y",
                0xFF7DE4B2,
                C_BG,
                0xFF133424,
                StlGlSurfaceView.ViewPreset.POS_Y,
                c - diag,
                c - diag,
                24));
        axisPad.addView(makeGizmoButton(
                "-Y",
                0xFF315848,
                C_BG,
                0xFFD8FFEE,
                StlGlSurfaceView.ViewPreset.NEG_Y,
                c + diag,
                c + diag,
                24));
        axisPad.addView(makeGizmoButton(
                "Z",
                0xFF72A8FF,
                C_BG,
                0xFF112947,
                StlGlSurfaceView.ViewPreset.POS_Z,
                c,
                c - outer,
                24));
        axisPad.addView(makeGizmoButton(
                "-Z",
                0xFF2D476D,
                C_BG,
                0xFFDCEAFF,
                StlGlSurfaceView.ViewPreset.NEG_Z,
                c,
                c + outer,
                24));

        FrameLayout.LayoutParams axisPadParams = new FrameLayout.LayoutParams(
                gizmoSize,
                gizmoSize,
                Gravity.TOP | Gravity.END);
        axisPadParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        container.addView(axisPad, axisPadParams);

        previewHint = new TextView(this);
        previewHint.setText("Tap Render to load STL\n1-finger rotate, 2-finger pan, pinch to zoom");
        previewHint.setTextColor(C_TEXT_2);
        previewHint.setGravity(Gravity.CENTER);
        previewHint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        previewHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        container.addView(previewHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return panel;
    }

    private LinearLayout buildConsolePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(makePanelGradient(C_TOOLBAR, C_SURFACE, 0, C_BORDER, false));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(7), dp(10), dp(7));
        header.setBackground(makePanelGradient(C_TOOLBAR, C_SURFACE, 0, C_BORDER, true));

        TextView title = new TextView(this);
        title.setText("CONSOLE");
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.05f);
        header.addView(title);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button clear = makeToolbarButton("Clear", false);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearConsole();
            }
        });
        header.addView(clear);

        Button close = makeToolbarButton("X", false);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleConsole();
            }
        });
        header.addView(close);

        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        consoleScroll = new ScrollView(this);
        consoleOutput = new TextView(this);
        consoleOutput.setTextColor(C_TEXT_2);
        consoleOutput.setTypeface(Typeface.MONOSPACE);
        consoleOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        consoleOutput.setPadding(dp(10), dp(8), dp(10), dp(8));
        consoleScroll.addView(consoleOutput, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(consoleScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return panel;
    }

    private Button makeToolbarButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? C_BG : C_TEXT);

        GradientDrawable bg = makePanelGradient(
                primary ? C_ACCENT : C_SURFACE_2,
                primary ? C_ACCENT_2 : C_SURFACE,
                10,
                primary ? C_ACCENT_2 : C_BORDER,
                false);
        button.setBackground(bg);
        button.setPadding(dp(12), dp(5), dp(12), dp(5));
        button.setElevation(dp(1));
        button.setMinHeight(dp(34));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private View makeGizmoButton(
            String text,
            int fillColor,
            int strokeColor,
            int textColor,
            final StlGlSurfaceView.ViewPreset preset,
            int centerX,
            int centerY,
            int sizeDp) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        boolean iso = "ISO".equals(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, iso ? 8.5f : 8f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fillColor);
        bg.setStroke(dp(1), strokeColor);
        button.setBackground(bg);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (previewSurface != null) {
                    previewSurface.setViewPreset(preset);
                }
            }
        });

        int size = dp(sizeDp);
        int left = centerX - (size / 2);
        int top = centerY - (size / 2);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = left;
        params.topMargin = top;
        button.setLayoutParams(params);
        return button;
    }

    private View buildGizmoGuides(final int sizePx) {
        return new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);

                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));

                int c = sizePx / 2;
                int outer = dp(42);
                int diag = dp(30);

                paint.setColor(0x66FF6B7A);
                canvas.drawLine(c, c, c + outer, c, paint);
                canvas.drawLine(c, c, c - outer, c, paint);

                paint.setColor(0x6672A8FF);
                canvas.drawLine(c, c, c, c - outer, paint);
                canvas.drawLine(c, c, c, c + outer, paint);

                paint.setColor(0x667DE4B2);
                canvas.drawLine(c, c, c - diag, c - diag, paint);
                canvas.drawLine(c, c, c + diag, c + diag, paint);

                paint.setColor(0x335fd6ff);
                canvas.drawCircle(c, c, dp(46), paint);
            }
        };
    }

    private void ensureDefaultProject() {
        File example = new File(runtime.getProjectsDir(), DEFAULT_FILE);
        if (!example.exists()) {
            try {
                runtime.writeProject(DEFAULT_FILE, DEFAULT_CODE);
            } catch (IOException e) {
                appendLog("Could not create default project: " + e.getMessage(), C_RED);
            }
        }
    }

    private void warmUpRuntime() {
        setStatus("Preparing runtime...");
        appendLog("Extracting bundled OpenSCAD runtime", C_TEXT_2);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runtime.prepareRuntime();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Ready");
                            appendLog("Runtime ready", C_GREEN);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Runtime error");
                            appendLog("Runtime setup failed: " + e.getMessage(), C_RED);
                            showConsole();
                        }
                    });
                }
            }
        });
    }

    private void checkRuntimeUpdateOnBoot() {
        lastRuntimeStatus = runtimeUpdateManager.getCachedStatus();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final RuntimeUpdateManager.RuntimeStatus status = runtimeUpdateManager.checkForUpdates();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            lastRuntimeStatus = status;
                            if (status.updateAvailable) {
                                appendLog(
                                    "Runtime update available: " + safeLabel(status.latestVersion) +
                                        " (" + safeLabel(status.latestAssetName) + ")",
                                    C_YELLOW
                                );
                            } else {
                                appendLog("Runtime check: " + safeLabel(status.message), C_TEXT_2);
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("Runtime update check failed: " + e.getMessage(), C_YELLOW);
                        }
                    });
                }
            }
        });
    }

    private void showRuntimeDialog() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setPadding(dp(12), dp(6), dp(12), dp(6));

        final TextView info = new TextView(this);
        info.setTextColor(C_TEXT);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setLineSpacing(0f, 1.12f);
        scroll.addView(info, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RuntimeUpdateManager.RuntimeStatus cached =
            lastRuntimeStatus == null ? runtimeUpdateManager.getCachedStatus() : lastRuntimeStatus;
        info.setText(buildRuntimeStatusText(cached, null));

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Runtime Downloader")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setNeutralButton("Refresh", null)
            .setPositiveButton(runtimeActionLabel(cached), null)
            .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                final Button refreshButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                final Button downloadButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                RuntimeUpdateManager.RuntimeStatus status =
                    lastRuntimeStatus == null ? runtimeUpdateManager.getCachedStatus() : lastRuntimeStatus;
                downloadButton.setEnabled(canDownloadRuntime(status));
                downloadButton.setText(runtimeActionLabel(status));

                refreshButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        refreshRuntimeStatusForDialog(info, refreshButton, downloadButton);
                    }
                });

                downloadButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        downloadRuntimeForDialog(info, refreshButton, downloadButton);
                    }
                });

                refreshRuntimeStatusForDialog(info, refreshButton, downloadButton);
            }
        });

        dialog.show();
    }

    private void refreshRuntimeStatusForDialog(
        final TextView info,
        final Button refreshButton,
        final Button downloadButton
    ) {
        RuntimeUpdateManager.RuntimeStatus current =
            lastRuntimeStatus == null ? runtimeUpdateManager.getCachedStatus() : lastRuntimeStatus;
        refreshButton.setEnabled(false);
        downloadButton.setEnabled(false);
        info.setText(buildRuntimeStatusText(current, "Checking latest release..."));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                RuntimeUpdateManager.RuntimeStatus status;
                String error = null;
                try {
                    status = runtimeUpdateManager.checkForUpdates();
                } catch (Exception e) {
                    status = runtimeUpdateManager.getCachedStatus();
                    error = e.getMessage();
                }

                final RuntimeUpdateManager.RuntimeStatus finalStatus = status;
                final String finalError = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        lastRuntimeStatus = finalStatus;
                        info.setText(buildRuntimeStatusText(finalStatus, finalError == null ? null : "Check failed: " + finalError));
                        refreshButton.setEnabled(true);
                        downloadButton.setEnabled(canDownloadRuntime(finalStatus));
                        downloadButton.setText(runtimeActionLabel(finalStatus));

                        if (finalError != null) {
                            appendLog("Runtime update check failed: " + finalError, C_YELLOW);
                            return;
                        }
                        if (finalStatus.updateAvailable) {
                            appendLog(
                                "Runtime update available: " + safeLabel(finalStatus.latestVersion) +
                                    " (" + safeLabel(finalStatus.latestAssetName) + ")",
                                C_YELLOW
                            );
                        } else {
                            appendLog("Runtime check: " + safeLabel(finalStatus.message), C_TEXT_2);
                        }
                    }
                });
            }
        });
    }

    private void downloadRuntimeForDialog(
        final TextView info,
        final Button refreshButton,
        final Button downloadButton
    ) {
        refreshButton.setEnabled(false);
        downloadButton.setEnabled(false);

        RuntimeUpdateManager.RuntimeStatus current =
            lastRuntimeStatus == null ? runtimeUpdateManager.getCachedStatus() : lastRuntimeStatus;
        info.setText(buildRuntimeStatusText(current, "Starting download + install..."));
        setStatus("Downloading runtime...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                RuntimeUpdateManager.RuntimeStatus status = null;
                String error = null;

                try {
                    status = runtimeUpdateManager.downloadAndInstallLatest(new RuntimeUpdateManager.ProgressListener() {
                        @Override
                        public void onProgress(final String message) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    RuntimeUpdateManager.RuntimeStatus shown = lastRuntimeStatus == null
                                        ? runtimeUpdateManager.getCachedStatus()
                                        : lastRuntimeStatus;
                                    info.setText(buildRuntimeStatusText(shown, message));
                                    appendLog("Runtime: " + message, C_TEXT_2);
                                }
                            });
                        }
                    });
                    status = runtimeUpdateManager.checkForUpdates();
                } catch (Exception e) {
                    error = e.getMessage();
                }

                final RuntimeUpdateManager.RuntimeStatus finalStatus =
                    status == null ? runtimeUpdateManager.getCachedStatus() : status;
                final String finalError = error;

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        lastRuntimeStatus = finalStatus;
                        if (finalError == null) {
                            setStatus("Runtime updated");
                            appendLog(
                                "Runtime installed: " + safeLabel(finalStatus.installedVersion) +
                                    " (" + safeLabel(finalStatus.installedAssetName) + ")",
                                C_GREEN
                            );
                            info.setText(buildRuntimeStatusText(finalStatus, "Install complete"));
                        } else {
                            setStatus("Runtime update failed");
                            appendLog("Runtime install failed: " + finalError, C_RED);
                            info.setText(buildRuntimeStatusText(finalStatus, "Install failed: " + finalError));
                        }

                        refreshButton.setEnabled(true);
                        downloadButton.setEnabled(canDownloadRuntime(finalStatus));
                        downloadButton.setText(runtimeActionLabel(finalStatus));
                    }
                });
            }
        });
    }

    private boolean canDownloadRuntime(RuntimeUpdateManager.RuntimeStatus status) {
        return status != null
            && status.abiSupported
            && !TextUtils.isEmpty(status.latestVersion)
            && !TextUtils.isEmpty(status.latestAssetName);
    }

    private String runtimeActionLabel(RuntimeUpdateManager.RuntimeStatus status) {
        if (status == null || !status.abiSupported) {
            return "Download";
        }
        if (!status.downloaded) {
            return "Download";
        }
        if (status.updateAvailable) {
            return "Update";
        }
        return "Reinstall";
    }

    private String buildRuntimeStatusText(RuntimeUpdateManager.RuntimeStatus status, String action) {
        if (status == null) {
            status = runtimeUpdateManager.getCachedStatus();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Repo: ").append(safeLabel(status.repository)).append('\n');
        sb.append("Device ABI: ").append(safeLabel(status.deviceAbi)).append('\n');
        sb.append("Downloaded: ").append(status.downloaded ? "Yes" : "No").append('\n');
        sb.append("Installed Version: ").append(safeLabel(status.installedVersion)).append('\n');
        sb.append("Installed Asset: ").append(safeLabel(status.installedAssetName)).append('\n');
        sb.append("Latest Version: ").append(safeLabel(status.latestVersion)).append('\n');
        sb.append("Latest Asset: ").append(safeLabel(status.latestAssetName)).append('\n');
        sb.append("Update Available: ").append(status.updateAvailable ? "Yes" : "No").append('\n');
        if (status.checkedAtMs > 0) {
            sb.append("Last Check: ");
            sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(status.checkedAtMs)));
            sb.append('\n');
        }
        sb.append("Status: ").append(safeLabel(status.message));
        if (!TextUtils.isEmpty(action)) {
            sb.append('\n').append('\n').append("Action: ").append(action);
        }
        return sb.toString();
    }

    private String safeLabel(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        return value;
    }

    private void toggleFiles() {
        if (compactLayout) {
            showFilePickerDialog();
            return;
        }
        sidebar.setVisibility(sidebar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void showFilePickerDialog() {
        if (fileNames.isEmpty()) {
            Toast.makeText(this, "No files", Toast.LENGTH_SHORT).show();
            return;
        }

        final ListView pickerList = new ListView(this);
        final ArrayAdapter<String> pickerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>(fileNames));
        pickerList.setAdapter(pickerAdapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Open file (long-press to delete)")
                .setView(pickerList)
                .setNegativeButton("Close", null)
                .create();

        pickerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= pickerAdapter.getCount()) {
                    return;
                }
                openFile(pickerAdapter.getItem(position));
                dialog.dismiss();
            }
        });

        pickerList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= pickerAdapter.getCount()) {
                    return true;
                }
                String fileName = pickerAdapter.getItem(position);
                dialog.dismiss();
                promptDeleteFile(fileName);
                return true;
            }
        });

        dialog.show();
    }

    private void promptDeleteFile(final String fileName) {
        new AlertDialog.Builder(this)
                .setTitle("Delete file?")
                .setMessage(fileName)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        boolean deleted = runtime.deleteProject(fileName);
                        if (!deleted) {
                            setStatus("Delete failed");
                            appendLog("Could not delete " + fileName, C_RED);
                            return;
                        }

                        appendLog("Deleted " + fileName, C_YELLOW);
                        boolean removedCurrent = fileName.equals(currentFile);
                        refreshFiles();

                        if (!removedCurrent) {
                            return;
                        }

                        if (!fileNames.isEmpty()) {
                            openFile(fileNames.get(0));
                            return;
                        }

                        try {
                            runtime.writeProject(DEFAULT_FILE, DEFAULT_CODE);
                            refreshFiles();
                            openFile(DEFAULT_FILE);
                        } catch (IOException e) {
                            editor.setText(DEFAULT_CODE);
                            currentFile = DEFAULT_FILE;
                            currentTab.setText(DEFAULT_FILE);
                            setStatus("No files");
                        }
                    }
                })
                .show();
    }

    private void refreshFiles() {
        File[] files = runtime.getProjectsDir().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".scad");
            }
        });
        fileNames.clear();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File file : files) {
                fileNames.add(file.getName());
            }
        }
        fileAdapter.notifyDataSetChanged();
    }

    private void openFile(String fileName) {
        try {
            String content = runtime.readProject(fileName);
            editor.setText(content);
            currentFile = fileName;
            libraryPreviewMode = false;
            activeLibraryPath = null;
            currentTab.setText(fileName);
            refreshFiles();
            setStatus("Opened " + fileName);
            appendLog("Opened " + fileName, C_TEXT_2);
        } catch (IOException e) {
            setStatus("Open failed");
            appendLog("Open failed: " + e.getMessage(), C_RED);
        }
    }

    private void newFile() {
        promptForFileName("New file", "untitled.scad", new NameCallback() {
            @Override
            public void onName(String fileName) {
                editor.setText("// " + fileName + "\n\ncube(10);\n");
                currentFile = fileName;
                libraryPreviewMode = false;
                activeLibraryPath = null;
                currentTab.setText(fileName);
                saveCurrentFile(false);
            }
        });
    }

    private void saveCurrentFile(boolean silent) {
        if (libraryPreviewMode) {
            if (silent) {
                return;
            }
            String suggestion = "library_preview.scad";
            if (activeLibraryPath != null && !activeLibraryPath.trim().isEmpty()) {
                suggestion = new File(activeLibraryPath).getName();
            }
            promptForFileName("Save library view as project", suggestion, new NameCallback() {
                @Override
                public void onName(String fileName) {
                    currentFile = fileName;
                    libraryPreviewMode = false;
                    activeLibraryPath = null;
                    currentTab.setText(fileName);
                    saveCurrentFile(false);
                }
            });
            return;
        }

        if (currentFile == null || currentFile.trim().isEmpty()) {
            promptForFileName("Save file", "untitled.scad", new NameCallback() {
                @Override
                public void onName(String fileName) {
                    currentFile = fileName;
                    currentTab.setText(fileName);
                    saveCurrentFile(false);
                }
            });
            return;
        }

        try {
            runtime.writeProject(currentFile, editor.getText().toString());
            refreshFiles();
            if (!silent) {
                setStatus("Saved");
                appendLog("Saved " + currentFile, C_GREEN);
            }
        } catch (IOException e) {
            setStatus("Save failed");
            appendLog("Save failed: " + e.getMessage(), C_RED);
        }
    }

    private void doRender() {
        if (rendering) {
            appendLog("Render already running", C_YELLOW);
            return;
        }

        final String code = editor.getText().toString();
        if (code.trim().isEmpty()) {
            setStatus("Editor is empty");
            appendLog("Cannot render empty file", C_YELLOW);
            return;
        }

        saveCurrentFile(true);

        rendering = true;
        renderButton.setEnabled(false);
        renderButton.setText("Rendering...");
        setStatus("Rendering...");
        appendLog("Running OpenSCAD", C_ACCENT);

        String renderBase = currentFile;
        if (libraryPreviewMode && activeLibraryPath != null && !activeLibraryPath.trim().isEmpty()) {
            renderBase = new File(activeLibraryPath).getName();
        }
        final String baseName = renderBase == null ? "model.scad" : renderBase;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final OpenScadRuntime.RenderResult result = runtime.render(code, baseName);
                StlModel parsedModel = null;
                String parseError = null;

                if (result.success && result.stlFile != null && result.stlFile.exists()) {
                    try {
                        parsedModel = StlParser.parse(result.stlFile);
                    } catch (Exception e) {
                        parseError = e.getMessage();
                    }
                }

                final StlModel finalParsedModel = parsedModel;
                final String finalParseError = parseError;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        rendering = false;
                        renderButton.setEnabled(true);
                        renderButton.setText("Render");

                        if (result.success) {
                            lastRenderedStl = result.stlFile;
                            if (finalParsedModel != null) {
                                previewSurface.setModel(finalParsedModel);
                                previewHint.setVisibility(View.GONE);
                                appendLog(
                                        "Viewer mesh loaded: " + finalParsedModel.vertexCount + " vertices, radius " +
                                                String.format(Locale.US, "%.3f", finalParsedModel.radius) +
                                                ", center=(" +
                                                String.format(Locale.US, "%.3f", finalParsedModel.centerX) + "," +
                                                String.format(Locale.US, "%.3f", finalParsedModel.centerY) + "," +
                                                String.format(Locale.US, "%.3f", finalParsedModel.centerZ) + ")",
                                        C_TEXT_2);
                            } else {
                                previewHint.setText("Model generated but viewer could not load STL.");
                                previewHint.setVisibility(View.VISIBLE);
                                if (finalParseError != null && !finalParseError.trim().isEmpty()) {
                                    appendLog("Viewer parse error: " + finalParseError, C_YELLOW);
                                }
                            }

                            String duration = String.format(Locale.US, "%.2fs", result.durationMs / 1000f);
                            setStatus("Rendered in " + duration);
                            appendLog("Render success in " + duration, C_GREEN);
                            if (result.log != null && !result.log.trim().isEmpty()) {
                                appendLog(result.log.trim(), C_TEXT_2);
                            }
                        } else {
                            setStatus("Render failed");
                            String error = result.error == null ? "Unknown error" : result.error;
                            appendLog("Render failed: " + error, C_RED);
                            if (result.log != null && !result.log.trim().isEmpty()) {
                                appendLog(result.log.trim(), C_YELLOW);
                            }
                            showConsole();
                        }
                    }
                });
            }
        });
    }

    private void exportLastStl() {
        if (lastRenderedStl == null || !lastRenderedStl.exists()) {
            setStatus("Nothing to export");
            appendLog("No STL available. Render first.", C_YELLOW);
            return;
        }

        String stem = currentFile == null ? "model" : currentFile.replace(".scad", "");
        long ts = System.currentTimeMillis();
        final String suggestion = stem + "_" + ts + ".stl";
        final EditText input = new EditText(this);
        input.setText(suggestion);
        int extPos = suggestion.toLowerCase(Locale.US).lastIndexOf(".stl");
        input.setSelection(0, extPos > 0 ? extPos : suggestion.length());

        new AlertDialog.Builder(this)
                .setTitle("Export STL As")
                .setView(input)
                .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        String name = input.getText() == null ? "" : input.getText().toString().trim();
                        if (name.isEmpty()) {
                            setStatus("Export cancelled");
                            appendLog("Export cancelled: empty file name", C_YELLOW);
                            return;
                        }

                        String safeName = name.replace('\\', '_').replace('/', '_');
                        if (!safeName.toLowerCase(Locale.US).endsWith(".stl")) {
                            safeName = safeName + ".stl";
                        }
                        doExportWithFileName(safeName);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doExportWithFileName(String fileName) {
        try {
            Uri outUri = exportToPublicDocuments(lastRenderedStl, fileName);
            setStatus("Exported");
            appendLog("Exported STL to Documents/OpenSCAD: " + fileName, C_GREEN);
            Toast.makeText(this, "Saved to Documents/OpenSCAD/" + fileName, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            setStatus("Export failed");
            appendLog("Export failed: " + e.getMessage(), C_RED);
        }
    }

    private void showLibrariesDialog() {
        final List<String> libs = libraryManager.listLibraries();
        final List<String> options = new ArrayList<String>();
        options.add("Import .scad/.zip");
        options.add("Browse libraries");
        if (!libs.isEmpty()) {
            options.add("Insert use <...>;");
            options.add("Insert include <...>;");
        }
        options.add("Show library folder");
        options.add("Show source-copy folder");

        final String[] labels = options.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("OpenSCAD Libraries")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which == 0) {
                            launchLibraryPicker();
                            return;
                        }

                        int next = 1;
                        if (which == next) {
                            showLibraryBrowserDialog();
                            return;
                        }
                        next++;

                        if (!libs.isEmpty()) {
                            if (which == next) {
                                showInsertLibraryDialog(libs, false);
                                return;
                            }
                            next++;
                            if (which == next) {
                                showInsertLibraryDialog(libs, true);
                                return;
                            }
                            next++;
                        }

                        if (which == next) {
                            String path = libraryManager.getLibrariesDir().getAbsolutePath();
                            appendLog("Libraries folder: " + path, C_TEXT_2);
                            Toast.makeText(MainActivity.this, path, Toast.LENGTH_LONG).show();
                            return;
                        }

                        next++;
                        if (which == next) {
                            String path = libraryManager.getLibrarySourcesDir().getAbsolutePath();
                            appendLog("Library source-copy folder: " + path, C_TEXT_2);
                            Toast.makeText(MainActivity.this, path, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showLibraryBrowserDialog() {
        final List<String> libs = libraryManager.listLibraries();
        if (libs.isEmpty()) {
            Toast.makeText(this, "No imported .scad libraries yet", Toast.LENGTH_SHORT).show();
            return;
        }

        final ListView listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setBackgroundColor(Color.TRANSPARENT);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                libs);
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Imported Libraries (" + libs.size() + ")")
                .setView(listView)
                .setNegativeButton("Close", null)
                .create();

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= libs.size()) {
                    return;
                }
                final String selected = libs.get(position);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(selected)
                        .setItems(new String[] { "View code in editor", "Insert use <...>;", "Insert include <...>;" },
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int which) {
                                        if (which == 0) {
                                            viewLibraryCodeInEditor(selected);
                                        } else if (which == 1) {
                                            insertLibraryDirective(selected, false);
                                        } else if (which == 2) {
                                            insertLibraryDirective(selected, true);
                                        }
                                    }
                                })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        dialog.show();
    }

    private void viewLibraryCodeInEditor(String libraryPath) {
        if (libraryPath == null || libraryPath.trim().isEmpty()) {
            return;
        }

        try {
            String code = libraryManager.readLibrarySource(libraryPath);
            editor.setText(code);
            currentTab.setText("LIB: " + libraryPath);
            libraryPreviewMode = true;
            activeLibraryPath = libraryPath;
            setStatus("Viewing library");
            appendLog("Viewing library code: " + libraryPath, C_TEXT_2);
        } catch (IOException e) {
            setStatus("Library read failed");
            appendLog("Could not open library: " + e.getMessage(), C_RED);
        }
    }

    private void launchLibraryPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "text/*",
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
        });
        startActivityForResult(intent, RC_IMPORT_LIBRARY);
    }

    private void showInsertLibraryDialog(final List<String> libs, final boolean include) {
        if (libs.isEmpty()) {
            Toast.makeText(this, "No imported .scad libraries yet", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] items = libs.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(include ? "Insert include <...>;" : "Insert use <...>;")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which < 0 || which >= items.length) {
                            return;
                        }
                        insertLibraryDirective(items[which], include);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void insertLibraryDirective(String libraryPath, boolean include) {
        Editable editable = editor.getText();
        if (editable == null) {
            return;
        }
        int pos = Math.max(0, editor.getSelectionStart());
        String line = (include ? "include <" : "use <") + libraryPath + ">;\n";
        editable.insert(pos, line);
        setStatus(include ? "Inserted include" : "Inserted use");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_IMPORT_LIBRARY || resultCode != RESULT_OK || data == null) {
            return;
        }

        final Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }
        }

        setStatus("Importing library...");
        appendLog("Importing library from picker", C_TEXT_2);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String imported = libraryManager.importFromUri(uri);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Library imported");
                            appendLog("Imported library: " + imported, C_GREEN);
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Library import failed");
                            appendLog("Library import failed: " + e.getMessage(), C_RED);
                            showConsole();
                        }
                    });
                }
            }
        });
    }

    private Uri exportToPublicDocuments(File source, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "model/stl");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/OpenSCAD");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = MediaStore.Files.getContentUri("external");
            Uri item = getContentResolver().insert(collection, values);
            if (item == null) {
                throw new IOException("Could not create export entry");
            }

            try (OutputStream out = getContentResolver().openOutputStream(item, "w")) {
                if (out == null) {
                    getContentResolver().delete(item, null, null);
                    throw new IOException("Could not open output stream");
                }
                copyFileToStream(source, out);
            } catch (IOException e) {
                getContentResolver().delete(item, null, null);
                throw e;
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(item, done, null, null);
            return item;
        }

        File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File exportDir = new File(docs, "OpenSCAD");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("Could not create Documents/OpenSCAD");
        }
        File outFile = new File(exportDir, fileName);
        copyFile(source, outFile);
        return Uri.fromFile(outFile);
    }

    private void toggleViewerMode() {
        wireframeMode = !wireframeMode;
        if (previewSurface != null) {
            previewSurface.setWireframeMode(wireframeMode);
        }
        if (viewerModeButton != null) {
            viewerModeButton.setText(wireframeMode ? "Wireframe" : "Shaded");
        }
        appendLog("Viewer mode: " + (wireframeMode ? "wireframe" : "shaded"), C_TEXT_2);
    }

    private void toggleAxisLines() {
        axisLinesVisible = !axisLinesVisible;
        if (previewSurface != null) {
            previewSurface.setAxisLinesVisible(axisLinesVisible);
        }
        if (axisLinesButton != null) {
            axisLinesButton.setText(axisLinesVisible ? "Axes On" : "Axes Off");
        }
        appendLog("RGB axes: " + (axisLinesVisible ? "on" : "off"), C_TEXT_2);
    }

    private void toggleConsole() {
        if (consolePanel.getVisibility() == View.VISIBLE) {
            consolePanel.setVisibility(View.GONE);
        } else {
            consolePanel.setVisibility(View.VISIBLE);
        }
    }

    private void showConsole() {
        if (consolePanel.getVisibility() != View.VISIBLE) {
            consolePanel.setVisibility(View.VISIBLE);
        }
    }

    private void clearConsole() {
        logBuilder.clear();
        consoleOutput.setText("");
    }

    private void appendLog(String message, int color) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = "[" + ts + "] " + message + "\n";
        int start = logBuilder.length();
        logBuilder.append(line);
        logBuilder.setSpan(new ForegroundColorSpan(color), start, logBuilder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        consoleOutput.setText(logBuilder);
        consoleScroll.post(new Runnable() {
            @Override
            public void run() {
                consoleScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    private void promptForFileName(String title, String suggestion, final NameCallback callback) {
        final EditText input = new EditText(this);
        input.setText(suggestion);
        input.setSelection(suggestion.length());

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        String name = input.getText().toString().trim();
                        if (!name.endsWith(".scad")) {
                            name = name + ".scad";
                        }
                        if (name.isEmpty() || ".scad".equals(name)) {
                            return;
                        }
                        callback.onName(name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static void copyFileToStream(File source, OutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()));
    }

    private GradientDrawable makePanelGradient(int startColor, int endColor, int radiusDp, int strokeColor,
            boolean vertical) {
        GradientDrawable bg = new GradientDrawable(
                vertical ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { startColor, endColor });
        bg.setCornerRadius(dp(radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            bg.setStroke(dp(1), strokeColor);
        }
        return bg;
    }

    private interface NameCallback {
        void onName(String fileName);
    }
}
