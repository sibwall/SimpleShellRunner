package simple.shell.runner;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RunActivity extends Activity {

    private EditText input;
    private Button button;
    private TextView output;
    private ScrollView scroll;
    private LinearLayout root;

    private static final int INPUT_MAX_CHARS = 100;
    private static final int OUTPUT_MAX_CHARS = 10_000;

    private static volatile Process sProcess;
    private static final StringBuilder sOutputBuffer = new StringBuilder();
    private static volatile boolean sRunning = false;

    private static volatile RunActivity sActiveInstance;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private final String[] quickCommands = {
            "cmd package list packages -s -u | grep -m 1 com.android.settings",
            "cmd -l",
            "ls /product/app/"
    };

    private final String[] setupCommands = {
            "adb shell pm grant simple.shell.runner android.permission.INTERACT_ACROSS_USERS",
            "adb shell pm grant simple.shell.runner android.permission.WRITE_SECURE_SETTINGS",
            "adb shell pm create-user TestUser"
    };

    private boolean isEn() {
        return !java.util.Locale.getDefault().getLanguage().equals("ru");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        TextView welcomeText = new TextView(this);
        String welcomeMessage = isEn()
                ? "Hello! This is an application for testers. Here you can execute simple shell commands on behalf of this application."
                : "Привет! Это приложение для тестировщиков. Здесь вы можете выполнять простые shell команды от имени приложения.";
        
        welcomeText.setText(welcomeMessage);
        welcomeText.setTextSize(14f);
        welcomeText.setPadding(0, 0, 0, 16);
        root.addView(welcomeText);

        TextView hintHeader = new TextView(this);
        hintHeader.setText(isEn() ? "Command Examples:" : "Примеры команд:");
        hintHeader.setTextSize(14f);
        hintHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        hintHeader.setPadding(0, 0, 0, 8);
        root.addView(hintHeader);

        for (String cmd : quickCommands) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 2, 0, 2);

            TextView cmdText = new TextView(this);
            cmdText.setText(cmd);
            cmdText.setTextSize(12f);
            cmdText.setTextIsSelectable(true);
            row.addView(cmdText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button copyBtn = new Button(this);
            copyBtn.setText(isEn() ? "Copy" : "Копировать");
            copyBtn.setTextSize(10f);
            copyBtn.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip =
                        android.content.ClipData.newPlainText("command", cmd);
                clipboard.setPrimaryClip(clip);
            });
            row.addView(copyBtn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            root.addView(row);
        }

        input = new EditText(this);
        input.setHint(isEn() ? "Enter cmd command" : "Введите cmd команду");
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setHorizontallyScrolling(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(INPUT_MAX_CHARS)
        });

        input.setLongClickable(true);
        input.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }
            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }
            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                return false;
            }
            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
            }
        });
        root.addView(input);

        button = new Button(this);
        button.setOnClickListener(v -> onButtonClick());
        root.addView(button);

        output = new TextView(this);
        output.setPadding(0, 24, 0, 0);
        output.setTextIsSelectable(true);

        scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f));

        TextView setupInfoText = new TextView(this);
        String setupHtml = isEn()
                ? "<br>Before running some commands, please grant the app necessary permissions via ADB and create a test user (copy and run the commands below using any ADB environment, for example <a href=\"https://github.com/RikkaApps/Shizuku/releases/latest\">Shizuku</a> + <a href=\"https://f-droid.org/ru/packages/in.sunilpaulmathew.ashell/\">aShell</a>):"
                : "<br>Перед запуском некоторых команд предоставьте приложению нужные разрешения через ADB и создайте пользователя (скопируйте и выполните то, что ниже, через любую ADB среду, например <a href=\"https://github.com/RikkaApps/Shizuku/releases/latest\">Shizuku</a> + <a href=\"https://f-droid.org/ru/packages/in.sunilpaulmathew.ashell/\">aShell</a>):";

        setupInfoText.setText(Html.fromHtml(setupHtml, Html.FROM_HTML_MODE_LEGACY));
        setupInfoText.setTextSize(14f);
        setupInfoText.setMovementMethod(LinkMovementMethod.getInstance());
        setupInfoText.setPadding(0, 8, 0, 8);
        root.addView(setupInfoText);

        for (String cmd : setupCommands) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 2, 0, 2);

            TextView cmdText = new TextView(this);
            cmdText.setText(cmd);
            cmdText.setTextSize(12f);
            cmdText.setTextIsSelectable(true);
            row.addView(cmdText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button copyBtn = new Button(this);
            copyBtn.setText(isEn() ? "Copy" : "Копировать");
            copyBtn.setTextSize(10f);
            copyBtn.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip =
                        android.content.ClipData.newPlainText("setup_command", cmd);
                clipboard.setPrimaryClip(clip);
            });
            row.addView(copyBtn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            root.addView(row);
        }
        
        setContentView(root);

        sActiveInstance = this;
        output.setText(sOutputBuffer.toString());
        button.setText(sRunning ? "Stop" : "Start");
        updateOutputLayout();
    }

    private void onButtonClick() {
        if (sRunning) {
            stopCommand();
        } else {
            startCommand();
        }
    }

    private void startCommand() {
        String cmd = input.getText().toString();
        if (cmd.trim().isEmpty()) return;

        clearInput();

        sOutputBuffer.setLength(0);
        output.setText("");
        updateOutputLayout();
        sRunning = true;
        button.setText("Stop");

        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("sh", "-c", cmd)
                        .redirectErrorStream(true)
                        .start();
                sProcess = p;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    sOutputBuffer.append(line).append("\n");
                    trimToLastChars(sOutputBuffer, OUTPUT_MAX_CHARS);
                }
                p.waitFor();
            } catch (Exception e) {
                sOutputBuffer.append("Error: ").append(e.getMessage());
                trimToLastChars(sOutputBuffer, OUTPUT_MAX_CHARS);
            }

            sRunning = false;
            sProcess = null;

            sMainHandler.post(() -> {
                RunActivity active = sActiveInstance;
                if (active != null) {
                    active.output.setText(sOutputBuffer.toString());
                    active.button.setText("Start");
                    active.updateOutputLayout();
                }
            });
        }).start();
    }

    private void updateOutputLayout() {
        scroll.post(() -> {
            if (sOutputBuffer.length() == 0) {
                scroll.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 0f));
                return;
            }

            int lineCount = output.getLineCount();
            int lineHeight = output.getLineHeight();
            int contentHeight = (lineCount * lineHeight) + output.getPaddingTop() + output.getPaddingBottom();

            int totalRootHeight = root.getHeight();
            int ceilingHeight = totalRootHeight > 0 ? (int)(totalRootHeight * 0.22f) : 220;
            
            int targetHeight = Math.min(contentHeight, ceilingHeight);

            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, targetHeight, 0f));
        });
    }

    private static void trimToLastChars(StringBuilder sb, int maxChars) {
        if (sb.length() > maxChars) {
            sb.delete(0, sb.length() - maxChars);
        }
    }

    private void clearInput() {
        input.setText("");
    }

    private void stopCommand() {
        if (sProcess != null) {
            sProcess.destroy();
            sProcess = null;
        }
        sRunning = false;
        button.setText("Start");
        updateOutputLayout();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (sActiveInstance == this) {
            sActiveInstance = null;
        }
        super.onDestroy();
    }
}
