package com.openscad.standalone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
    private static final String BUILD_MARKER = "2026-02-18_20:06_v4";

    private static final int C_BG = Color.parseColor("#1e1e2e");
    private static final int C_SURFACE = Color.parseColor("#282a36");
    private static final int C_SURFACE_2 = Color.parseColor("#313244");
    private static final int C_TOOLBAR = Color.parseColor("#181825");
    private static final int C_BORDER = Color.parseColor("#45475a");
    private static final int C_TEXT = Color.parseColor("#cdd6f4");
    private static final int C_TEXT_2 = Color.parseColor("#a6adc8");
    private static final int C_ACCENT = Color.parseColor("#89b4fa");
    private static final int C_GREEN = Color.parseColor("#a6e3a1");
    private static final int C_RED = Color.parseColor("#f38ba8");
    private static final int C_YELLOW = Color.parseColor("#f9e2af");

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
        root.setBackgroundColor(C_BG);

        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

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
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(C_TOOLBAR);

        TextView logo = new TextView(this);
        logo.setText("OpenSCAD");
        logo.setTextColor(C_ACCENT);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoParams.rightMargin = dp(8);
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
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(180),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.rightMargin = dp(8);
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
        side.setBackgroundColor(C_TOOLBAR);

        TextView header = new TextView(this);
        header.setText("Files");
        header.setTextColor(C_TEXT);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setPadding(dp(10), dp(10), dp(10), dp(6));
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
        fileList.setBackgroundColor(C_TOOLBAR);
        fileAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, fileNames) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                String name = getItem(position);
                boolean active = name != null && name.equals(currentFile);
                tv.setTextColor(active ? C_BG : C_TEXT);
                tv.setBackgroundColor(active ? C_ACCENT : Color.TRANSPARENT);
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
        panel.setBackgroundColor(C_BG);

        currentTab = new TextView(this);
        currentTab.setText(DEFAULT_FILE);
        currentTab.setTextColor(C_TEXT);
        currentTab.setBackgroundColor(C_TOOLBAR);
        currentTab.setTypeface(Typeface.DEFAULT_BOLD);
        currentTab.setPadding(dp(10), dp(8), dp(10), dp(8));
        currentTab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        panel.addView(currentTab, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new EditText(this);
        editor.setText(DEFAULT_CODE);
        editor.setTextColor(C_TEXT);
        editor.setHintTextColor(C_TEXT_2);
        editor.setBackgroundColor(C_SURFACE);
        editor.setTypeface(Typeface.MONOSPACE);
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
        panel.setBackgroundColor(Color.parseColor("#11111b"));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(C_TOOLBAR);
        header.setPadding(dp(10), dp(6), dp(10), dp(6));

        TextView title = new TextView(this);
        title.setText("3D Viewer");
        title.setTextColor(C_TEXT_2);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
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
        previewSurface.setBackgroundColor(Color.parseColor("#11111b"));
        previewSurface.setWireframeMode(wireframeMode);
        previewSurface.showDebugCube();
        container.addView(previewSurface, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewHint = new TextView(this);
        previewHint.setText("Debug cube should be visible now.\nTap Render to load STL.");
        previewHint.setTextColor(C_TEXT_2);
        previewHint.setGravity(Gravity.CENTER);
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
        panel.setBackgroundColor(C_TOOLBAR);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));

        TextView title = new TextView(this);
        title.setText("Console");
        title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setTextColor(primary ? C_BG : C_TEXT);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? C_ACCENT : C_SURFACE_2);
        bg.setCornerRadius(dp(7));
        bg.setStroke(dp(1), C_BORDER);
        button.setBackground(bg);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(6);
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
        final String[] items = fileNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Open file")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    openFile(items[i]);
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

        File downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) {
            downloads = new File(getFilesDir(), "exports");
        }
        File exportDir = new File(downloads, "OpenSCAD");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }

        String stem = currentFile == null ? "model" : currentFile.replace(".scad", "");
        File out = new File(exportDir, stem + "_" + System.currentTimeMillis() + ".stl");

        try {
            copyFile(lastRenderedStl, out);
            setStatus("Exported");
            appendLog("Exported STL: " + out.getAbsolutePath(), C_GREEN);
            Toast.makeText(this, "Saved to " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            setStatus("Export failed");
            appendLog("Export failed: " + e.getMessage(), C_RED);
        }
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

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            getResources().getDisplayMetrics()));
    }

    private interface NameCallback {
        void onName(String fileName);
    }
}
