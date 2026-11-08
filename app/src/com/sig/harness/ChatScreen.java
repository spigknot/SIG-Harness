package com.sig.harness;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native conversation view: streaming text, tool cards, big composer. */
public final class ChatScreen {
    public interface Host {
        void onBack();
        void onNewChat();
        void onOpenSessions();
        void onOpenSettings();
        void onStopHarness();
        void onSend(String sessionId, String text, JSONArray attachments);
        void onQueue(String sessionId, String text, JSONArray attachments);
        void onPickAttachments();
        void onClearQueue(String sessionId);
        void onPromoteQueued(String sessionId, String queueId);
        void onMic();
        void onCancel(String sessionId);
        void onModelSelected(String sessionId, String configId, String value);
        void onReconnect();
        void onPickModel(String sessionId, JSONArray configOptions);
        void onRestartRuntime();
        void onRename(String sessionId, String currentTitle);
    }

    private final Context c;
    private final Host host;
    private final String sessionId;
    private final LinearLayout root;
    private final ScrollView scroll;
    private final LinearLayout feed;
    private final LinearLayout emptyState;
    private final EditText input;
    private final TextView sendButton;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView banner;
    private final TextView menuButton;
    private final Map<String, ToolCard> tools = new HashMap<>();
    private final List<ToolCard> turnTools = new ArrayList<ToolCard>();
    private ToolSuperGroup toolSuper;
    private int turnSeq;
    private final List<Attachment> pendingFiles = new ArrayList<Attachment>();
    private final Map<String, QueuedItem> queued = new LinkedHashMap<String, QueuedItem>();
    private final Map<String, UserBubble> promotedBubbles = new HashMap<String, UserBubble>();
    private final Set<String> startedQueueIds = new HashSet<String>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private JSONArray configOptions;
    private long promptStartedAt;
    private Runnable timerRunnable;
    private String title;
    private String cwd;
    private String model;
    private String tokenUsage;
    private boolean generating;
    private boolean nearBottom = true;
    private boolean hadOutput;
    private boolean buildingHistory;
    private int scrollToken;
    private int lastUsageUsed;
    private int lastUsageSize;

    private LinearLayout streamingWrap;
    private LinearLayout streamingContent;
    private TextView streamingText;
    private final StringBuilder streamingBuffer = new StringBuilder();
    private TextView thinkingRow;
    private LinearLayout attachmentBar;
    private LinearLayout queueBar;
    private LinearLayout queueDetails;
    private TextView queueText;
    private TextView queueChevron;
    private boolean queueExpanded;
    private TextView attachButton;
    private TextView queueButton;
    private TextView micButton;
    private TextView dictationStatus;
    private boolean attaching;
    private boolean dictating;

    public ChatScreen(Context context, Host host, String sessionId, String title, String cwd) {
        this.c = context;
        this.host = host;
        this.sessionId = sessionId;
        this.title = title == null || title.isEmpty() ? "Nova conversa" : title;
        this.cwd = cwd == null ? "" : cwd;

        root = Ui.column(c);
        root.setBackgroundColor(Ui.BG);

        // Header
        LinearLayout header = Ui.row(c);
        header.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        TextView back = Ui.iconButton(c, "←", 24f, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onBack(); }
        });
        header.addView(back);
        LinearLayout titles = Ui.column(c);
        titles.setPadding(Ui.dp(c, 4), 0, 0, 0);
        titleView = Ui.text(c, this.title, 16.5f, Ui.TEXT, Ui.MEDIUM);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleView);
        subtitleView = Ui.text(c, "", 11.5f, Ui.MUTED, Ui.REGULAR);
        subtitleView.setMaxLines(1);
        subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleView.setPadding(0, Ui.dp(c, 2), 0, 0);
        titles.addView(subtitleView);
        header.addView(titles, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        menuButton = Ui.iconButton(c, "⋮", 22f, null);
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(); }
        });
        header.addView(menuButton);
        root.addView(header);
        root.addView(Ui.divider(c, Ui.SURFACE_3, 1));

        banner = Ui.text(c, "", 12.5f, Color.WHITE, Ui.MEDIUM);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(Ui.RED);
        banner.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onReconnect(); }
        });
        root.addView(banner);

        // Feed
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 12));
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                nearBottom = (scroll.getChildAt(0).getBottom() - (scrollY + scroll.getHeight())) < Ui.dp(c, 140);
            }
        });
        feed = Ui.column(c);
        scroll.addView(feed, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        emptyState = buildEmptyState();
        FrameLayout contentFrame = new FrameLayout(c);
        contentFrame.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        contentFrame.addView(emptyState, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentFrame, Ui.lpWeight(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Composer
        LinearLayout composer = Ui.column(c);
        composer.setBackgroundColor(Ui.BG_ELEVATED);
        composer.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 10));
        root.addView(composer);

        queueBar = Ui.row(c);
        queueBar.setBackground(Ui.rippleStroke(Ui.SURFACE, Ui.SURFACE_3, 1, 12f, c));
        queueBar.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
        queueBar.setClickable(true);
        queueBar.setFocusable(true);
        queueBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleQueue(); }
        });
        queueText = Ui.text(c, "", 11.5f, Ui.ACCENT, Ui.MEDIUM);
        queueBar.addView(queueText, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        queueChevron = Ui.text(c, "⌄", 13f, Ui.MUTED, Ui.MEDIUM);
        queueChevron.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 6), 0);
        queueBar.addView(queueChevron);
        TextView clearQueue = Ui.text(c, "Limpar", 11.5f, Ui.MUTED, Ui.MEDIUM);
        clearQueue.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        clearQueue.setClickable(true);
        clearQueue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onClearQueue(sessionId); }
        });
        queueBar.addView(clearQueue);
        queueBar.setVisibility(View.GONE);
        composer.addView(queueBar);

        queueDetails = Ui.column(c);
        queueDetails.setBackground(Ui.round(Ui.SURFACE, 12f, c));
        queueDetails.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 4));
        queueDetails.setVisibility(View.GONE);
        LinearLayout.LayoutParams qdlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qdlp.topMargin = Ui.dp(c, 4);
        composer.addView(queueDetails, qdlp);

        attachmentBar = Ui.column(c);
        attachmentBar.setVisibility(View.GONE);
        composer.addView(attachmentBar);

        dictationStatus = Ui.text(c, "", 11.5f, Ui.ACCENT, Ui.REGULAR);
        dictationStatus.setLineSpacing(Ui.dp(c, 2), 1.0f);
        dictationStatus.setMaxLines(4);
        dictationStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dictationStatus.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), 0);
        dictationStatus.setVisibility(View.GONE);
        composer.addView(dictationStatus);

        LinearLayout inputRow = Ui.row(c);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        attachButton = Ui.iconButton(c, "📎", 9f, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onPickAttachments(); }
        });
        attachButton.setTextColor(Ui.MUTED);
        attachButton.setBackground(Ui.ripple(Ui.SURFACE_2, 10f, c));
        attachButton.setContentDescription("Anexar arquivo");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(c, 23), Ui.dp(c, 23));
        alp.rightMargin = Ui.dp(c, 4);
        inputRow.addView(attachButton, alp);

        input = new EditText(c);
        input.setHint("Mensagem para o SIG…");
        input.setHintTextColor(Ui.FAINT);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(16f);
        input.setTypeface(Ui.REGULAR);
        input.setBackground(null);
        input.setPadding(Ui.dp(c, 14), Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(6);
        input.setMinLines(1);
        input.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout inputBox = Ui.column(c);
        inputBox.setBackground(Ui.stroke(Ui.SURFACE, Ui.SURFACE_3, 1, 22f, c));
        inputBox.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        inputRow.addView(inputBox, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout sideActions = Ui.column(c);
        sideActions.setGravity(Gravity.CENTER);
        sideActions.setClipChildren(false);

        micButton = Ui.text(c, "🎤", 10f, Ui.TEXT_SOFT, Ui.REGULAR);
        micButton.setGravity(Gravity.CENTER);
        micButton.setBackground(Ui.ripple(Ui.SURFACE_2, 10f, c));
        micButton.setContentDescription("Ditar mensagem");
        micButton.setClickable(true);
        micButton.setFocusable(true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(c, 21), Ui.dp(c, 21));
        sideActions.addView(micButton, mlp);
        micButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onMic(); }
        });

        queueButton = Ui.text(c, "⏳", 9f, Ui.ACCENT, Ui.MEDIUM);
        queueButton.setGravity(Gravity.CENTER);
        queueButton.setBackground(Ui.ripple(Ui.SURFACE_2, 10f, c));
        queueButton.setContentDescription("Enviar para a fila");
        queueButton.setClickable(true);
        queueButton.setFocusable(true);
        queueButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(Ui.dp(c, 21), Ui.dp(c, 21));
        qlp.topMargin = Ui.dp(c, 2);
        sideActions.addView(queueButton, qlp);
        queueButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { queueCurrent(); }
        });
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(Ui.dp(c, 24), LinearLayout.LayoutParams.WRAP_CONTENT);
        salp.leftMargin = Ui.dp(c, 2);
        inputRow.addView(sideActions, salp);

        sendButton = Ui.text(c, "↑", 18f, Color.WHITE, Ui.MEDIUM);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(Ui.ripple(Ui.ACCENT, 19f, c));
        sendButton.setTextColor(Color.WHITE);
        sendButton.setClickable(true);
        sendButton.setFocusable(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38));
        slp.leftMargin = Ui.dp(c, 6);
        inputRow.addView(sendButton, slp);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (generating) requestCancel(); else send();
            }
        });
        composer.addView(inputRow);

        updateSubtitle();
        showThinking(false);
        input.post(new Runnable() {
            @Override public void run() {
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public View view() { return root; }
    public String sessionId() { return sessionId; }
    public String title() { return title; }

    private LinearLayout buildEmptyState() {
        LinearLayout wrap = Ui.column(c);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(Ui.dp(c, 24), Ui.dp(c, 10), Ui.dp(c, 24), Ui.dp(c, 10));
        LinearLayout inner = Ui.column(c);
        inner.setGravity(Gravity.CENTER);
        TextView logo = Ui.text(c, "SIG", 30f, Ui.ACCENT, Ui.BLACK);
        logo.setLetterSpacing(0.04f);
        logo.setGravity(Gravity.CENTER);
        inner.addView(logo);
        TextView head = Ui.text(c, "Pergunte, programe, execute.", 19f, Ui.TEXT, Ui.BOLD);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, Ui.dp(c, 10), 0, 0);
        inner.addView(head);
        TextView sub = Ui.text(c, "O agente roda no Termux e pode usar o shell, editar arquivos e pesquisar.", 12.5f, Ui.MUTED, Ui.REGULAR);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(Ui.dp(c, 2), 1.0f);
        sub.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), 0);
        inner.addView(sub);
        String[] suggestions = {"O que tem neste projeto?", "Mostre a estrutura de arquivos", "Explique como o Harness funciona"};
        for (int i = 0; i < suggestions.length; i++) {
            final String suggestion = suggestions[i];
            TextView chip = Ui.text(c, suggestion, 13f, Ui.TEXT_SOFT, Ui.REGULAR);
            chip.setPadding(Ui.dp(c, 14), Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
            chip.setBackground(Ui.rippleStroke(Color.TRANSPARENT, Ui.SURFACE_3, 1, 14f, c));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    input.setText(suggestion);
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 10);
            inner.addView(chip, lp);
        }
        wrap.addView(inner);
        return wrap;
    }

    private void hideEmpty() {
        if (emptyState.getVisibility() == View.VISIBLE) emptyState.setVisibility(View.GONE);
    }

    private void showEmptyIfNeeded() {
        boolean empty = feed.getChildCount() == 0;
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void updateSubtitle() {
        String path = Ui.shortPath(cwd);
        StringBuilder sb = new StringBuilder();
        if (!path.isEmpty()) sb.append(path);
        if (model != null && !model.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(model);
        }
        if (lastUsageSize > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(formatTokens(lastUsageUsed)).append("/").append(formatTokens(lastUsageSize));
        }
        subtitleView.setText(sb.toString());
    }

    private static String formatTokens(int value) {
        if (value >= 1000000) return String.format("%.1fM", value / 1000000f);
        if (value >= 1000) return String.format("%.1fk", value / 1000f);
        return String.valueOf(value);
    }

    public void setTitle(String value) {
        if (value == null || value.isEmpty()) return;
        this.title = value;
        titleView.setText(value);
    }

    public void setCwd(String value) {
        this.cwd = value == null ? "" : value;
        updateSubtitle();
    }

    public void setModel(String value) {
        this.model = value;
        updateSubtitle();
    }

    public void setConfigOptions(JSONArray options) {
        this.configOptions = options;
        if (options == null) return;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null || !"model".equals(option.optString("id"))) continue;
            String current = option.optString("currentValue", "");
            try {
                JSONArray parsed = new JSONArray(current);
                if (parsed.length() >= 2) setModel(parsed.optString(1));
            } catch (Exception ignored) { }
        }
    }

    public void showStalled(long idleMs) {
        if (!generating) return;
        long minutes = Math.max(1, idleMs / 60000L);
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.stroke(Color.rgb(38, 31, 18), Ui.AMBER, 1, 14f, c));
        card.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        TextView title = Ui.text(c, "Sem novidades há " + minutes + " min", 13f, Ui.AMBER, Ui.MEDIUM);
        card.addView(title);
        TextView body = Ui.text(c, "O runtime pode estar travado. Você pode interromper ou reiniciar o runtime.", 12f, Ui.TEXT_SOFT, Ui.REGULAR);
        body.setLineSpacing(Ui.dp(c, 3), 1.0f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(c, 6);
        card.addView(body, blp);
        LinearLayout actions = Ui.row(c);
        actions.setPadding(0, Ui.dp(c, 10), 0, 0);
        TextView stop = Ui.text(c, "Interromper", 12.5f, Ui.RED, Ui.MEDIUM);
        stop.setPadding(0, Ui.dp(c, 4), Ui.dp(c, 18), Ui.dp(c, 4));
        stop.setClickable(true);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestCancel(); }
        });
        actions.addView(stop);
        TextView restart = Ui.text(c, "Reiniciar runtime", 12.5f, Ui.ACCENT, Ui.MEDIUM);
        restart.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        restart.setClickable(true);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onRestartRuntime(); }
        });
        actions.addView(restart);
        card.addView(actions);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 8);
        lp.bottomMargin = Ui.dp(c, 8);
        feed.addView(card, lp);
        scrollToBottom(true);
    }

    private String composeUserText(String text, JSONArray atts) {
        StringBuilder sb = new StringBuilder(text == null ? "" : text.trim());
        if (atts != null) {
            for (int i = 0; i < atts.length(); i++) {
                JSONObject a = atts.optJSONObject(i);
                if (a == null) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("📎 ").append(a.optString("name", "arquivo"));
            }
        }
        return sb.toString();
    }

    private JSONArray attachmentsJson() {
        JSONArray array = new JSONArray();
        for (Attachment a : pendingFiles) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", a.name);
                o.put("path", a.path);
                o.put("mimeType", a.mimeType);
                o.put("size", a.size);
            } catch (Exception ignored) { }
            array.put(o);
        }
        return array;
    }

    public void addAttachment(String name, String path, String mimeType, long size) {
        if (path == null || path.isEmpty()) return;
        pendingFiles.add(new Attachment(name == null ? "arquivo" : name, path,
                mimeType == null ? "application/octet-stream" : mimeType, size));
        rebuildAttachments();
    }

    public void setAttaching(boolean value) {
        attaching = value;
        if (attachButton != null) {
            attachButton.setAlpha(value ? 0.45f : 1f);
            attachButton.setEnabled(!value);
        }
        rebuildAttachments();
    }

    public void attachmentError(String message) {
        Toast.makeText(c, message == null ? "Não foi possível anexar o arquivo." : message, Toast.LENGTH_LONG).show();
    }

    private void clearAttachments() {
        pendingFiles.clear();
        rebuildAttachments();
    }

    private void rebuildAttachments() {
        if (attachmentBar == null) return;
        attachmentBar.removeAllViews();
        if (attaching) {
            TextView working = Ui.text(c, "Anexando…", 11.5f, Ui.MUTED, Ui.REGULAR);
            working.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            attachmentBar.addView(working);
        }
        for (int i = 0; i < pendingFiles.size(); i++) {
            final int index = i;
            Attachment a = pendingFiles.get(i);
            LinearLayout chip = Ui.row(c);
            chip.setBackground(Ui.round(Ui.SURFACE_2, 10f, c));
            chip.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
            TextView label = Ui.text(c, "📎 " + a.name, 12f, Ui.TEXT_SOFT, Ui.REGULAR);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            chip.addView(label, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (a.size > 0) {
                TextView size = Ui.text(c, formatSize(a.size), 10f, Ui.FAINT, Ui.REGULAR);
                size.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 4), 0);
                chip.addView(size);
            }
            TextView remove = Ui.text(c, "✕", 13f, Ui.MUTED, Ui.MEDIUM);
            remove.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4));
            remove.setClickable(true);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (index < pendingFiles.size()) {
                        pendingFiles.remove(index);
                        rebuildAttachments();
                    }
                }
            });
            chip.addView(remove);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 3);
            attachmentBar.addView(chip, lp);
        }
        attachmentBar.setVisibility(pendingFiles.isEmpty() && !attaching ? View.GONE : View.VISIBLE);
    }

    private static String formatSize(long size) {
        if (size <= 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.0f KB", size / 1024f);
        return String.format("%.1f MB", size / (1024f * 1024f));
    }

    public void addSentUserMessage(String text, JSONArray atts) {
        addUserBubble(composeUserText(text, atts), null);
    }

    public void addQueuedBubble(String queueId, String text, JSONArray atts, int position) {
        if (queueId == null || queueId.isEmpty() || queued.containsKey(queueId)) return;
        if (startedQueueIds.remove(queueId)) {
            final UserBubble sent = addUserBubble(composeUserText(text, atts), "✓ enviada");
            if (sent.status != null) {
                sent.status.setTextColor(Ui.GREEN);
                sent.status.postDelayed(new Runnable() {
                    @Override public void run() { sent.status.setVisibility(View.GONE); }
                }, 1500);
            }
            return;
        }
        UserBubble bubble = addUserBubble(composeUserText(text, atts), queueLabel(position));
        queued.put(queueId, new QueuedItem(queueId, text, atts, position, bubble));
        refreshQueueBar();
    }

    public void onQueuedStarted(final String queueId) {
        startedQueueIds.add(queueId);
        ui.postDelayed(new Runnable() {
            @Override public void run() { startedQueueIds.remove(queueId); }
        }, 60000);
        UserBubble promoted = promotedBubbles.remove(queueId);
        if (promoted != null && promoted.status != null) {
            promoted.status.setText("✓ enviada");
            promoted.status.setTextColor(Ui.GREEN);
            promoted.status.postDelayed(new Runnable() {
                @Override public void run() { promoted.status.setVisibility(View.GONE); }
            }, 1500);
        }
        QueuedItem item = queued.remove(queueId);
        if (item != null && item.bubble.status != null) {
            item.bubble.status.setText("✓ enviada");
            item.bubble.status.setTextColor(Ui.GREEN);
            item.bubble.status.postDelayed(new Runnable() {
                @Override public void run() { item.bubble.status.setVisibility(View.GONE); }
            }, 1500);
        }
        refreshQueueBar();
    }

    public void onQueueChanged(JSONArray items, String reason, String promotedQueueId) {
        if ("promoted".equals(reason) && promotedQueueId != null && !promotedQueueId.isEmpty()) {
            QueuedItem promoted = queued.remove(promotedQueueId);
            if (promoted != null) {
                if (promoted.bubble.status != null) {
                    promoted.bubble.status.setVisibility(View.VISIBLE);
                    promoted.bubble.status.setText("Enviando…");
                    promoted.bubble.status.setTextColor(Ui.ACCENT);
                }
                promotedBubbles.put(promotedQueueId, promoted.bubble);
            }
        }
        Set<String> ids = new HashSet<String>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("queueId", "");
                if (id.isEmpty()) continue;
                ids.add(id);
                QueuedItem current = queued.get(id);
                if (current != null) {
                    current.position = item.optInt("position", i + 1);
                    if (current.bubble.status != null) {
                        current.bubble.status.setVisibility(View.VISIBLE);
                        current.bubble.status.setTextColor(Ui.MUTED);
                        current.bubble.status.setText(queueLabel(current.position));
                    }
                }
            }
        }
        String why = reason == null ? "" : reason;
        Iterator<Map.Entry<String, QueuedItem>> it = queued.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, QueuedItem> entry = it.next();
            if (ids.contains(entry.getKey())) continue;
            QueuedItem current = entry.getValue();
            if ("started".equals(why) || "requeued".equals(why)) {
                if (current.bubble.status != null) {
                    current.bubble.status.setVisibility(View.VISIBLE);
                    current.bubble.status.setText("started".equals(why) ? "Enviando…" : "Na fila");
                    current.bubble.status.setTextColor("started".equals(why) ? Ui.ACCENT : Ui.MUTED);
                }
                continue;
            }
            if (current.bubble.row.getParent() != null) feed.removeView(current.bubble.row);
            it.remove();
        }
        refreshQueueBar();
    }

    private void toggleQueue() {
        if (queued.isEmpty()) return;
        queueExpanded = !queueExpanded;
        refreshQueueDetails();
    }

    private static String queueLabel(int position) {
        return position > 1 ? "Na fila · " + position + "º" : "Na fila";
    }

    private void refreshQueueBar() {
        if (queueBar == null) return;
        int count = queued.size();
        if (count == 0) queueExpanded = false;
        queueBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        if (queueText != null) {
            if (count == 1) queueText.setText("1 mensagem na fila");
            else if (count > 1) queueText.setText(count + " mensagens na fila");
        }
        if (queueChevron != null) queueChevron.setText(queueExpanded ? "⌃" : "⌄");
        refreshQueueDetails();
    }

    private void refreshQueueDetails() {
        if (queueDetails == null) return;
        queueDetails.removeAllViews();
        if (!queueExpanded || queued.isEmpty()) {
            queueDetails.setVisibility(View.GONE);
            return;
        }
        queueDetails.setVisibility(View.VISIBLE);
        int shown = 0;
        for (QueuedItem item : queued.values()) {
            if (shown >= 4) break;
            if (shown > 0) {
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 1));
                dlp.topMargin = Ui.dp(c, 4);
                dlp.bottomMargin = Ui.dp(c, 4);
                queueDetails.addView(Ui.divider(c, Ui.SURFACE_3, 1), dlp);
            }
            LinearLayout row = Ui.row(c);
            TextView text = Ui.text(c, composeUserText(item.text, item.attachments), 12.5f, Ui.TEXT_SOFT, Ui.REGULAR);
            text.setLineSpacing(Ui.dp(c, 3), 1.0f);
            text.setMaxLines(5);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setTextIsSelectable(true);
            text.setPadding(Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 6), Ui.dp(c, 5));
            row.addView(text, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView sendNow = Ui.text(c, "Enviar", 11f, Ui.ACCENT, Ui.MEDIUM);
            sendNow.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 2), Ui.dp(c, 4));
            sendNow.setClickable(true);
            sendNow.setFocusable(true);
            sendNow.setContentDescription("Enviar esta mensagem agora");
            sendNow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { host.onPromoteQueued(sessionId, item.queueId); }
            });
            row.addView(sendNow);
            queueDetails.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        int remaining = queued.size() - shown;
        if (remaining > 0) {
            TextView more = Ui.text(c, "… e mais " + remaining + " na fila", 11f, Ui.MUTED, Ui.MEDIUM);
            more.setPadding(Ui.dp(c, 2), Ui.dp(c, 4), 0, Ui.dp(c, 4));
            queueDetails.addView(more);
        }
    }

    public void setDictationState(String state, String detail) {
        boolean active = "connecting".equals(state) || "listening".equals(state) || "finishing".equals(state);
        dictating = active;
        if (micButton != null) {
            if ("listening".equals(state)) {
                micButton.setText("■");
                micButton.setTextColor(Color.WHITE);
                micButton.setBackground(Ui.ripple(Ui.RED, 21f, c));
                micButton.setContentDescription("Parar ditado");
            } else if (active) {
                micButton.setText("…");
                micButton.setTextColor(Ui.MUTED);
                micButton.setBackground(Ui.ripple(Ui.SURFACE_2, 21f, c));
                micButton.setContentDescription("Ditado em andamento");
            } else {
                micButton.setText("🎤");
                micButton.setTextColor(Ui.TEXT_SOFT);
                micButton.setBackground(Ui.ripple(Ui.SURFACE_2, 21f, c));
                micButton.setContentDescription("Ditar mensagem");
            }
        }
        if (dictationStatus != null) {
            if (active) {
                dictationStatus.setVisibility(View.VISIBLE);
                dictationStatus.setText(detail == null || detail.isEmpty() ? "🎤 …" : detail);
            } else {
                dictationStatus.setVisibility(View.GONE);
                dictationStatus.setText("");
            }
        }
    }

    public void setDictationPartial(String text) {
        if (dictationStatus == null) return;
        dictationStatus.setVisibility(View.VISIBLE);
        String value = text == null ? "" : text.trim();
        dictationStatus.setText(value.isEmpty() ? "🎤 Ouvindo…" : "🎤 " + value);
    }

    public void setDictationError(String message) {
        setDictationState("idle", "");
        Toast.makeText(c, message == null ? "Falha na transcrição." : message, Toast.LENGTH_LONG).show();
    }

    public void insertDictation(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            Toast.makeText(c, "Nada transcrito.", Toast.LENGTH_SHORT).show();
            return;
        }
        int start = input.getSelectionStart();
        String current = input.getText().toString();
        if (start < 0 || start > current.length()) start = current.length();
        String before = current.substring(0, start);
        String after = current.substring(start);
        boolean spaceBefore = before.length() > 0 && !Character.isWhitespace(before.charAt(before.length() - 1));
        boolean spaceAfter = after.length() > 0 && !Character.isWhitespace(after.charAt(0));
        String insertion = (spaceBefore ? " " : "") + value + (spaceAfter ? " " : "");
        input.setText(before + insertion + after);
        input.setSelection((before + insertion).length());
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    public void release() {
        stopTimer();
    }

    public void setConnection(boolean online, String detail) {
        if (online) {
            banner.setVisibility(View.GONE);
        } else {
            banner.setVisibility(View.VISIBLE);
            banner.setText(detail == null ? "Sem conexão · toque para reconectar" : detail);
        }
    }

    public void setHistory(JSONObject history) {
        if (history == null) return;
        String newTitle = history.optString("title", "");
        if (!newTitle.isEmpty() && !"Nova conversa".equals(newTitle)) setTitle(newTitle);
        String historyCwd = history.optString("cwd", "");
        if (!historyCwd.isEmpty()) { this.cwd = historyCwd; }
        JSONArray messages = history.optJSONArray("messages");
        nearBottom = true;
        feed.removeAllViews();
        queued.clear();
        queueExpanded = false;
        tools.clear();
        turnTools.clear();
        toolSuper = null;
        turnSeq = 0;
        streamingWrap = null;
        streamingContent = null;
        streamingText = null;
        streamingBuffer.setLength(0);
        buildingHistory = true;
        try {
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null) continue;
                    String role = message.optString("role", "");
                    String text = message.optString("text", "");
                    if (text.trim().isEmpty()) continue;
                    if ("user".equals(role)) addUserBubble(text, null);
                    else addAssistantMessage(text);
                }
            }
            JSONArray queue = history.optJSONArray("queue");
            if (queue != null) {
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject item = queue.optJSONObject(i);
                    if (item == null) continue;
                    addQueuedBubble(item.optString("queueId", ""), item.optString("text", ""),
                            item.optJSONArray("attachments"), item.optInt("position", i + 1));
                }
            }
        } finally {
            buildingHistory = false;
        }
        showEmptyIfNeeded();
        updateSubtitle();
        refreshQueueBar();
        if (history.optBoolean("active", false)) setGenerating(true);
        scrollToBottom(true);
    }

    public void onUpdate(JSONObject update) {
        if (update == null) return;
        String kind = update.optString("sessionUpdate", "");
        if ("agent_message_chunk".equals(kind)) {
            JSONObject content = update.optJSONObject("content");
            String text = content == null ? "" : content.optString("text", "");
            if (!text.isEmpty()) appendChunk(text);
        } else if ("tool_call".equals(kind) || "tool_call_update".equals(kind)) {
            finishStreaming();
            upsertTool(update);
            showThinking(false);
        } else if ("usage_update".equals(kind)) {
            lastUsageUsed = update.optInt("used", 0);
            lastUsageSize = update.optInt("size", 0);
            updateSubtitle();
        } else if ("config_option_update".equals(kind)) {
            JSONArray options = update.optJSONArray("configOptions");
            if (options != null) applyModelOption(options);
        }
    }

    private void applyModelOption(JSONArray options) {
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null || !"model".equals(option.optString("id"))) continue;
            String current = option.optString("currentValue", "");
            try {
                JSONArray parsed = new JSONArray(current);
                if (parsed.length() >= 2) setModel(parsed.optString(1));
            } catch (Exception ignored) { }
        }
    }

    public void setGenerating(boolean value) {
        if (value && !generating) {
            turnTools.clear();
            turnSeq++;
        }
        this.generating = value;
        if (!value) {
            stopTimer();
            showThinking(false);
        } else {
            hadOutput = false;
            promptStartedAt = System.currentTimeMillis();
            showThinking(true);
            startTimer();
        }
        updateSendButton();
    }

    private void startTimer() {
        stopTimer();
        timerRunnable = new Runnable() {
            @Override public void run() {
                if (!generating || thinkingRow == null) return;
                long seconds = Math.max(0, (System.currentTimeMillis() - promptStartedAt) / 1000L);
                String elapsed = seconds < 60 ? seconds + "s" : (seconds / 60) + "m" + (seconds % 60) + "s";
                thinkingRow.setText("◔  Pensando… " + elapsed + "   ·   Parar");
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(timerRunnable, 1000);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            ui.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
    }

    private void updateSendButton() {
        if (generating) {
            sendButton.setText("■");
            sendButton.setTextSize(15f);
            sendButton.setBackground(Ui.ripple(Ui.RED, 27f, c));
            sendButton.setContentDescription("Parar");
        } else {
            sendButton.setText("↑");
            sendButton.setTextSize(25f);
            sendButton.setBackground(Ui.ripple(Ui.ACCENT, 27f, c));
            sendButton.setContentDescription("Enviar mensagem");
        }
        if (queueButton != null) queueButton.setVisibility(generating ? View.VISIBLE : View.GONE);
    }

    private void send() {
        String text = input.getText().toString().trim();
        JSONArray atts = attachmentsJson();
        if (text.isEmpty() && atts.length() == 0) return;
        if (generating) {
            Toast.makeText(c, "O agente está respondendo. Use o botão ⏳ para enfileirar.", Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText("");
        hideKeyboard();
        hideEmpty();
        addUserBubble(composeUserText(text, atts), null);
        setGenerating(true);
        host.onSend(sessionId, text, atts);
        clearAttachments();
    }

    private void queueCurrent() {
        String text = input.getText().toString().trim();
        JSONArray atts = attachmentsJson();
        if (text.isEmpty() && atts.length() == 0) return;
        input.setText("");
        hideKeyboard();
        hideEmpty();
        host.onQueue(sessionId, text, atts);
        clearAttachments();
    }

    private void requestCancel() {
        host.onCancel(sessionId);
        sendButton.setText("…");
        sendButton.setTextSize(16f);
    }

    public void onPromptDone(JSONObject data) {
        finishStreaming();
        showThinking(false);
        groupTurnTools();
        setGenerating(false);
        String stop = data == null ? "" : data.optString("stopReason", "");
        String error = data == null ? "" : data.optString("error", "");
        if ("cancelled".equals(stop) || "cancelled".equals(error)) {
            addNotice("Resposta interrompida.");
        } else if ("error".equals(stop) || !error.isEmpty()) {
            addError(error.isEmpty() ? "O runtime não concluiu a resposta." : error);
        } else if (!hadOutput) {
            addNotice("O agente concluiu sem produzir texto.");
        }
        scrollToBottom(true);
    }

    public void showError(String message) {
        finishStreaming();
        showThinking(false);
        groupTurnTools();
        setGenerating(false);
        addError(message);
        scrollToBottom(true);
    }

    public void addNotice(String text) {
        hideEmpty();
        TextView notice = Ui.text(c, text, 12f, Ui.MUTED, Ui.REGULAR);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
        feed.addView(notice, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void addError(String message) {
        hideEmpty();
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.stroke(Color.rgb(34, 20, 22), Color.rgb(120, 45, 45), 1, 14f, c));
        card.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        TextView title = Ui.text(c, "Não foi possível concluir", 13f, Ui.RED, Ui.MEDIUM);
        card.addView(title);
        TextView body = Ui.text(c, message == null ? "Erro desconhecido." : message, 12f, Ui.TEXT_SOFT, Ui.REGULAR);
        body.setLineSpacing(Ui.dp(c, 3), 1.0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 6);
        card.addView(body, lp);
        TextView restart = Ui.text(c, "Reiniciar runtime", 12f, Ui.ACCENT, Ui.MEDIUM);
        restart.setPadding(0, Ui.dp(c, 10), 0, 0);
        restart.setClickable(true);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onRestartRuntime(); }
        });
        card.addView(restart);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrap.topMargin = Ui.dp(c, 10);
        wrap.bottomMargin = Ui.dp(c, 6);
        feed.addView(card, wrap);
    }

    private void addUserBubble(String text) { addUserBubble(text, null); }

    private UserBubble addUserBubble(String text, String statusText) {
        hideEmpty();
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.END);
        LinearLayout column = Ui.column(c);
        column.setGravity(Gravity.END);
        TextView bubble = Ui.text(c, text, 15.5f, Color.WHITE, Ui.REGULAR);
        bubble.setLineSpacing(Ui.dp(c, 3), 1.0f);
        bubble.setTextIsSelectable(true);
        bubble.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        bubble.setBackground(Ui.round(Ui.ACCENT, 18f, 18f, 6f, c));
        int max = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.82f);
        bubble.setMaxWidth(max);
        column.addView(bubble, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView status = null;
        if (statusText != null) {
            status = Ui.text(c, statusText, 10.5f, Ui.MUTED, Ui.MEDIUM);
            status.setGravity(Gravity.END);
            status.setPadding(0, Ui.dp(c, 4), Ui.dp(c, 2), 0);
            column.addView(status);
        }
        row.addView(column);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 4);
        rlp.bottomMargin = Ui.dp(c, 6);
        feed.addView(row, rlp);
        if (!buildingHistory) scrollToBottom(true);
        return new UserBubble(row, status);
    }

    private void addAssistantMessage(String text) {
        hideEmpty();
        feed.addView(assistantBlock(text));
        scrollToBottom(true);
    }

    private LinearLayout assistantBlock(String text) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.TOP);
        TextView avatar = Ui.text(c, "S", 11f, Ui.ACCENT, Ui.MEDIUM);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.round(Ui.ACCENT_SOFT, 12f, c));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
        alp.rightMargin = Ui.dp(c, 9);
        alp.topMargin = Ui.dp(c, 1);
        row.addView(avatar, alp);
        LinearLayout content = Ui.column(c);
        content.addView(copyBar(text));
        content.addView(Markdown.build(c, text));
        row.addView(content, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 10);
        row.setLayoutParams(lp);
        return row;
    }

    private void appendChunk(String text) {
        hideEmpty();
        hadOutput = true;
        showThinking(false);
        if (streamingWrap == null) {
            streamingWrap = Ui.row(c);
            streamingWrap.setGravity(Gravity.TOP);
            TextView avatar = Ui.text(c, "S", 11f, Ui.ACCENT, Ui.MEDIUM);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(Ui.round(Ui.ACCENT_SOFT, 12f, c));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
            alp.rightMargin = Ui.dp(c, 9);
            alp.topMargin = Ui.dp(c, 1);
            streamingWrap.addView(avatar, alp);
            streamingContent = Ui.column(c);
            streamingContent.addView(copyBarStreaming());
            streamingText = Ui.text(c, "", 16f, Ui.TEXT_SOFT, Ui.REGULAR);
            streamingText.setLineSpacing(Ui.dp(c, 4), 1.0f);
            streamingText.setTextIsSelectable(true);
            streamingContent.addView(streamingText);
            streamingWrap.addView(streamingContent, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(c, 10);
            streamingWrap.setLayoutParams(lp);
            feed.addView(streamingWrap);
            streamingBuffer.setLength(0);
        }
        streamingBuffer.append(text);
        streamingText.setText(streamingBuffer.toString());
        scrollToBottom(false);
    }

    private void finishStreaming() {
        if (streamingWrap == null) return;
        String text = streamingBuffer.toString();
        if (text.trim().isEmpty()) {
            feed.removeView(streamingWrap);
        } else {
            streamingContent.removeAllViews();
            streamingContent.addView(copyBar(text));
            streamingContent.addView(Markdown.build(c, text));
        }
        streamingWrap = null;
        streamingContent = null;
        streamingText = null;
        streamingBuffer.setLength(0);
    }

    private void showThinking(boolean show) {
        if (show) {
            if (thinkingRow != null) return;
            thinkingRow = Ui.text(c, "◔  Pensando…   ·   Parar", 12.5f, Ui.MUTED, Ui.REGULAR);
            thinkingRow.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 10));
            thinkingRow.setClickable(true);
            thinkingRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { requestCancel(); }
            });
            feed.addView(thinkingRow);
            scrollToBottom(true);
        } else {
            stopTimer();
            if (thinkingRow != null) {
                feed.removeView(thinkingRow);
                thinkingRow = null;
            }
        }
    }

    private void upsertTool(JSONObject update) {
        String id = update.optString("toolCallId", "");
        if (id.isEmpty()) id = "tool-" + tools.size();
        ToolCard card = tools.get(id);
        if (card == null) {
            card = new ToolCard(c);
            tools.put(id, card);
            feed.addView(card.root);
            turnTools.add(card);
        }
        card.apply(update);
        scrollToBottom(false);
    }

    private void scrollToBottom(boolean force) {
        if (buildingHistory) return;
        if (!force && !nearBottom) return;
        final int token = ++scrollToken;
        scroll.post(new Runnable() {
            @Override public void run() {
                if (token == scrollToken) jumpToBottom();
            }
        });
        if (!force) return;
        // A conversa é montada antes da primeira medição do ScrollView; repetir
        // depois do layout (e da abertura do teclado) garante o fim da conversa.
        for (int delay : new int[]{120, 300, 650}) {
            scroll.postDelayed(new Runnable() {
                @Override public void run() {
                    if (token == scrollToken) jumpToBottom();
                }
            }, delay);
        }
    }

    private void jumpToBottom() {
        if (scroll.getChildCount() == 0) return;
        // scrollTo não mexe no foco; fullScroll(View.FOCUS_DOWN) roubava o foco
        // do EditText a cada chunk de raciocínio.
        scroll.scrollTo(0, scroll.getChildAt(0).getBottom());
    }

    private LinearLayout copyBar(final String text) {
        LinearLayout bar = Ui.row(c);
        bar.setGravity(Gravity.END);
        TextView copy = Ui.text(c, "Copiar", 11f, Ui.MUTED, Ui.MEDIUM);
        copy.setPadding(Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 4), Ui.dp(c, 5));
        copy.setMinHeight(Ui.dp(c, 32));
        copy.setBackground(Ui.ripple(Color.TRANSPARENT, 8f, c));
        copy.setClickable(true);
        copy.setFocusable(true);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyText(text); }
        });
        bar.addView(copy);
        return bar;
    }

    private LinearLayout copyBarStreaming() {
        LinearLayout bar = Ui.row(c);
        bar.setGravity(Gravity.END);
        TextView copy = Ui.text(c, "Copiar", 11f, Ui.MUTED, Ui.MEDIUM);
        copy.setPadding(Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 4), Ui.dp(c, 5));
        copy.setMinHeight(Ui.dp(c, 32));
        copy.setBackground(Ui.ripple(Color.TRANSPARENT, 8f, c));
        copy.setClickable(true);
        copy.setFocusable(true);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyText(streamingBuffer.toString()); }
        });
        bar.addView(copy);
        return bar;
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SIG Harness", text == null ? "" : text));
            Toast.makeText(c, "Resposta copiada", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void showMenu() {
        PopupMenu popup = new PopupMenu(c, menuButton);
        popup.getMenu().add(0, 1, 0, "Nova conversa");
        popup.getMenu().add(0, 2, 1, "Conversas");
        popup.getMenu().add(0, 3, 2, "Ajustes");
        popup.getMenu().add(0, 6, 3, "Renomear conversa");
        popup.getMenu().add(0, 5, 4, "Modelo e raciocínio");
        popup.getMenu().add(0, 4, 5, "Parar harness");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                int id = item.getItemId();
                if (id == 1) host.onNewChat();
                else if (id == 2) host.onOpenSessions();
                else if (id == 3) host.onOpenSettings();
                else if (id == 6) host.onRename(sessionId, title);
                else if (id == 5) host.onPickModel(sessionId, configOptions);
                else if (id == 4) host.onStopHarness();
                return true;
            }
        });
        popup.show();
    }

    /** Expandable tool activity chip. */
    private void groupTurnTools() {
        if (turnTools.isEmpty()) return;
        ensureToolSuper();
        List<ToolCard> cards = new ArrayList<ToolCard>(turnTools);
        for (ToolCard card : cards) {
            if (card.root.getParent() instanceof ViewGroup) {
                ((ViewGroup) card.root.getParent()).removeView(card.root);
            }
            card.setExpanded(false);
        }
        if (cards.size() > 1) {
            ToolTurnGroup turn = new ToolTurnGroup(turnSeq, cards);
            toolSuper.body.addView(turn.root, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(c, 6);
            toolSuper.body.addView(cards.get(0).root, lp);
        }
        toolSuper.addCounts(cards.size());
        if (toolSuper.root.getParent() instanceof ViewGroup) {
            ((ViewGroup) toolSuper.root.getParent()).removeView(toolSuper.root);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 8);
        feed.addView(toolSuper.root, lp);
        toolSuper.setExpanded(false);
        turnTools.clear();
    }

    private void ensureToolSuper() {
        if (toolSuper == null) toolSuper = new ToolSuperGroup();
    }

    /** One compact entry for every tool call of the conversation. */
    private final class ToolSuperGroup {
        final LinearLayout root;
        final LinearLayout body;
        final TextView title;
        final TextView chevron;
        boolean expanded;
        int calls;
        int tasks;

        ToolSuperGroup() {
            root = Ui.column(c);
            root.setBackground(Ui.round(Ui.SURFACE, 12f, c));
            LinearLayout header = Ui.row(c);
            header.setBackground(Ui.ripple(Color.TRANSPARENT, 10f, c));
            header.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
            header.setClickable(true);
            header.setFocusable(true);
            TextView glyph = Ui.text(c, "⚙", 13f, Ui.ACCENT, Ui.REGULAR);
            header.addView(glyph);
            title = Ui.text(c, "Ferramentas · 0 chamadas", 12f, Ui.TEXT_SOFT, Ui.MEDIUM);
            title.setPadding(Ui.dp(c, 7), 0, 0, 0);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(title, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            chevron = Ui.text(c, "⌄", 15f, Ui.FAINT, Ui.REGULAR);
            header.addView(chevron);
            root.addView(header);
            body = Ui.column(c);
            body.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), Ui.dp(c, 4));
            body.setVisibility(View.GONE);
            root.addView(body);
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setExpanded(!expanded); }
            });
        }

        void addCounts(int count) {
            calls += count;
            tasks++;
            updateTitle();
        }

        void updateTitle() {
            String text = "Ferramentas · " + calls + (calls == 1 ? " chamada" : " chamadas");
            if (tasks > 1) text += " · " + tasks + " tarefas";
            title.setText(text);
        }

        void setExpanded(boolean value) {
            expanded = value;
            body.setVisibility(value ? View.VISIBLE : View.GONE);
            chevron.setText(value ? "⌃" : "⌄");
        }
    }

    /** One task's tool calls; expanded only when the user asks. */
    private final class ToolTurnGroup {
        final LinearLayout root;
        final LinearLayout body;
        final TextView chevron;
        boolean expanded;

        ToolTurnGroup(int sequence, List<ToolCard> cards) {
            root = Ui.column(c);
            root.setBackground(Ui.round(Ui.BG_ELEVATED, 10f, c));
            LinearLayout header = Ui.row(c);
            header.setBackground(Ui.ripple(Color.TRANSPARENT, 10f, c));
            header.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            header.setClickable(true);
            header.setFocusable(true);
            String count = cards.size() == 1 ? "1 ferramenta" : cards.size() + " ferramentas";
            TextView title = Ui.text(c, "Tarefa " + sequence + " · " + count, 11.5f, Ui.MUTED, Ui.MEDIUM);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(title, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            chevron = Ui.text(c, "⌄", 14f, Ui.FAINT, Ui.REGULAR);
            header.addView(chevron);
            root.addView(header);
            body = Ui.column(c);
            body.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            body.setVisibility(View.GONE);
            for (ToolCard card : cards) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(c, 6);
                body.addView(card.root, lp);
            }
            root.addView(body);
            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setExpanded(!expanded); }
            });
            setExpanded(false);
        }

        void setExpanded(boolean value) {
            expanded = value;
            body.setVisibility(value ? View.VISIBLE : View.GONE);
            chevron.setText(value ? "⌃" : "⌄");
        }
    }

    private final class ToolCard {
        final LinearLayout root;
        final TextView glyph;
        final TextView name;
        final TextView summary;
        final TextView chevron;
        final LinearLayout body;
        final TextView bodyText;
        boolean expanded;
        String lastInput = "";
        String lastOutput = "";

        ToolCard(Context context) {
            root = Ui.column(context);
            root.setBackground(Ui.round(Ui.SURFACE, 12f, context));
            root.setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10));
            LinearLayout row = Ui.row(context);
            glyph = Ui.text(context, "◔", 14f, Ui.AMBER, Ui.REGULAR);
            row.addView(glyph);
            name = Ui.text(context, "bash", 12.5f, Ui.TEXT, Ui.MONO);
            name.setPadding(Ui.dp(context, 8), 0, 0, 0);
            row.addView(name);
            summary = Ui.text(context, "", 11.5f, Ui.MUTED, Ui.REGULAR);
            summary.setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 6), 0);
            summary.setMaxLines(1);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(summary, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            chevron = Ui.text(context, "⌄", 15f, Ui.FAINT, Ui.REGULAR);
            row.addView(chevron);
            root.addView(row);
            body = Ui.column(context);
            bodyText = Ui.text(context, "", 10.5f, Ui.TEXT_SOFT, Ui.MONO);
            bodyText.setLineSpacing(Ui.dp(context, 2), 1.0f);
            bodyText.setTextIsSelectable(true);
            body.setBackground(Ui.round(Ui.CODE_BG, 8f, context));
            body.setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8));
            body.addView(bodyText);
            body.setVisibility(View.GONE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(context, 8);
            root.addView(body, blp);
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setExpanded(!expanded); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(context, 8);
            root.setLayoutParams(lp);
        }

        void setExpanded(boolean value) {
            expanded = value;
            body.setVisibility(value ? View.VISIBLE : View.GONE);
            chevron.setText(value ? "⌃" : "⌄");
        }

        void apply(JSONObject update) {
            String status = update.optString("status", "");
            String newTitle = update.optString("title", "");
            if (!newTitle.isEmpty()) name.setText(newTitle);
            if ("completed".equals(status)) { glyph.setText("✓"); glyph.setTextColor(Ui.GREEN); }
            else if ("failed".equals(status)) { glyph.setText("!"); glyph.setTextColor(Ui.RED); }
            else if ("in_progress".equals(status) || "pending".equals(status)) { glyph.setText("◔"); glyph.setTextColor(Ui.AMBER); }
            JSONObject raw = update.optJSONObject("rawInput");
            if (raw != null) {
                String command = raw.optString("command", "");
                if (command.isEmpty()) command = raw.optString("description", "");
                if (command.isEmpty()) command = raw.optString("path", "");
                if (!command.isEmpty()) {
                    lastInput = command;
                    summary.setText(oneLine(command));
                }
            }
            String output = extractContent(update.optJSONArray("content"));
            if (!output.isEmpty()) lastOutput = output;
            StringBuilder bodyBuilder = new StringBuilder();
            if (!lastInput.isEmpty()) bodyBuilder.append("$ ").append(lastInput);
            if (!lastOutput.isEmpty()) {
                if (bodyBuilder.length() > 0) bodyBuilder.append("\n\n");
                bodyBuilder.append(lastOutput);
            }
            bodyText.setText(bodyBuilder.toString().trim());
        }

        private String oneLine(String text) {
            String value = text.replace("\n", " ").trim();
            return value.length() > 60 ? value.substring(0, 59) + "…" : value;
        }
    }

    private static String extractContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            JSONObject inner = item.optJSONObject("content");
            if (inner != null) {
                String text = inner.optString("text", "");
                if (!text.isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(text); }
            } else if ("text".equals(item.optString("type"))) {
                String text = item.optString("text", "");
                if (!text.isEmpty()) { if (sb.length() > 0) sb.append('\n'); sb.append(text); }
            }
        }
        return sb.toString();
    }

    private static final class Attachment {
        final String name;
        final String path;
        final String mimeType;
        final long size;
        Attachment(String name, String path, String mimeType, long size) {
            this.name = name;
            this.path = path;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    private static final class UserBubble {
        final LinearLayout row;
        final TextView status;
        UserBubble(LinearLayout row, TextView status) {
            this.row = row;
            this.status = status;
        }
    }

    private static final class QueuedItem {
        final String queueId;
        final String text;
        final JSONArray attachments;
        int position;
        final UserBubble bubble;
        QueuedItem(String queueId, String text, JSONArray attachments, int position, UserBubble bubble) {
            this.queueId = queueId;
            this.text = text;
            this.attachments = attachments;
            this.position = position;
            this.bubble = bubble;
        }
    }

    public EditText input() { return input; }
}
