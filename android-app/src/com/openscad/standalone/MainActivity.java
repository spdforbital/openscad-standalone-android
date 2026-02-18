package com.openscad.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
    private static final String BUILD_MARKER = "2026-02-18_20:42_v7";

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

    private static final String DEFAULT_CODE =
        "// OpenSCAD Example - Parametric Box\n" +
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
    private ExecutorService executor;
    private Handler mainHandler;

    private boolean compactLayout;
    private boolean rendering;
    private boolean wireframeMode;

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
    private TextView previewHint;
    private StlGlSurfaceView previewSurface;

    private String currentFile = DEFAULT_FILE;
    private File lastRenderedStl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        runtime = new OpenScadRuntime(this);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        compactLayout = getResources().getConfiguration().screenWidthDp < 700;

        buildUi();
        appendLog("Build " + BUILD_MARKER, C_ACCENT);
        ensureDefaultProject();
        refreshFiles();
        openFile(DEFAULT_FILE);
        warmUpRuntime();
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
                    : makePanelGradient(Color.TRANSPARENT, Color.TRANSPARENT, 10, C_BORDER, false)
                );
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

        editor = new EditText(this);
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
        previewSurface.showDebugCube();
        container.addView(previewSurface, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout axisPad = new FrameLayout(this);
        axisPad.setPadding(dp(10), dp(10), dp(10), dp(10));
        axisPad.setBackground(makePanelGradient(0xCC0f1724, 0xCC152131, 22, C_BORDER, true));

        int gizmoSize = dp(110);
        FrameLayout.LayoutParams gizmoParams = new FrameLayout.LayoutParams(
            gizmoSize,
            gizmoSize
        );
        axisPad.setLayoutParams(gizmoParams);

        axisPad.addView(makeGizmoButton("ISO", C_ACCENT, C_BG, C_ACCENT_2, StlGlSurfaceView.ViewPreset.ISO, 0, 0));
        axisPad.addView(makeGizmoButton("X", 0xFFFF6B7A, C_BG, 0xFF3D1016, StlGlSurfaceView.ViewPreset.POS_X, 1, 0));
        axisPad.addView(makeGizmoButton("x", 0xFF5D2A33, C_BG, 0xFFFFD3D9, StlGlSurfaceView.ViewPreset.NEG_X, -1, 0));
        axisPad.addView(makeGizmoButton("Y", 0xFF7DE4B2, C_BG, 0xFF123726, StlGlSurfaceView.ViewPreset.POS_Y, 0, -1));
        axisPad.addView(makeGizmoButton("y", 0xFF275543, C_BG, 0xFFC9F5E2, StlGlSurfaceView.ViewPreset.NEG_Y, 0, 1));
        axisPad.addView(makeGizmoButton("Z", 0xFF72A8FF, C_BG, 0xFF112947, StlGlSurfaceView.ViewPreset.POS_Z, 1, -1));
        axisPad.addView(makeGizmoButton("z", 0xFF2A4064, C_BG, 0xFFD3E4FF, StlGlSurfaceView.ViewPreset.NEG_Z, -1, 1));

        FrameLayout.LayoutParams axisPadParams = new FrameLayout.LayoutParams(
            gizmoSize,
            gizmoSize,
            Gravity.TOP | Gravity.END
        );
        axisPadParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        container.addView(axisPad, axisPadParams);

        previewHint = new TextView(this);
        previewHint.setText("Tap Render to load STL\n1-finger rotate, 2-finger pan/zoom");
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
            false
        );
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
        int gx,
        int gy
    ) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        boolean iso = "ISO".equals(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, iso ? 9 : 10);
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

        int size = iso ? dp(30) : dp(24);
        int center = dp(55);
        int step = dp(23);
        int left = center - (size / 2) + (gx * step);
        int top = center - (size / 2) + (gy * step);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = left;
        params.topMargin = top;
        button.setLayoutParams(params);
        return button;
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
        final ArrayAdapter<String> pickerAdapter =
            new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>(fileNames));
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
                currentTab.setText(fileName);
                saveCurrentFile(false);
            }
        });
    }

    private void saveCurrentFile(boolean silent) {
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

        final String baseName = currentFile == null ? "model.scad" : currentFile;

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
                                    C_TEXT_2
                                );
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
        String fileName = stem + "_" + System.currentTimeMillis() + ".stl";

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

            try {
                OutputStream out = getContentResolver().openOutputStream(item, "w");
                if (out == null) {
                    getContentResolver().delete(item, null, null);
                    throw new IOException("Could not open output stream");
                }
                copyFileToStream(source, out);
                out.close();
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
        logBuilder.setSpan(new ForegroundColorSpan(color), start, logBuilder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        FileInputStream in = new FileInputStream(source);
        FileOutputStream out = new FileOutputStream(target);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        out.close();
        in.close();
    }

    private static void copyFileToStream(File source, OutputStream out) throws IOException {
        FileInputStream in = new FileInputStream(source);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        in.close();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            getResources().getDisplayMetrics()));
    }

    private GradientDrawable makePanelGradient(int startColor, int endColor, int radiusDp, int strokeColor, boolean vertical) {
        GradientDrawable bg = new GradientDrawable(
            vertical ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {startColor, endColor}
        );
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
