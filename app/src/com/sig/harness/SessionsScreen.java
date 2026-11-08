package com.sig.harness;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Conversation list with large cards and a prominent new-conversation button. */
public final class SessionsScreen {
    public interface Host {
        void onOpenSession(String id, String title, String cwd, boolean webOnly);
        void onNewChat();
        void onOpenSettings();
        void onRefresh();
        void onRenameSession(String id, String title);
        void onArchiveSession(String id, String title);
        void onDeleteSession(String id, String title);
        void onUnarchiveSession(String id, String title);
    }

    private final Context c;
    private final Host host;
    private final LinearLayout root;
    private final LinearLayout list;
    private final TextView subtitle;
    private final TextView dot;
    private final TextView newButton;
    private final FrameLayout listFrame;
    private final ScrollView scroll;
    private final LinearLayout emptyState;
    private final LinearLayout errorCard;
    private final TextView errorText;
    private final ProgressBar loading;
    private boolean online;
    private boolean newBusy;
    private JSONArray activeSessions;
    private JSONArray archivedSessions;
    private boolean archivedExpanded;

    public SessionsScreen(Context context, Host host) {
        this.c = context;
        this.host = host;
        root = Ui.column(c);
        root.setBackgroundColor(Ui.BG);

        // Header
        LinearLayout header = Ui.row(c);
        header.setPadding(Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 14), Ui.dp(c, 10));
        LinearLayout titles = Ui.column(c);
        TextView title = Ui.text(c, "Conversas", 27f, Ui.TEXT, Ui.BOLD);
        titles.addView(title);
        LinearLayout statusRow = Ui.row(c);
        statusRow.setPadding(0, Ui.dp(c, 5), 0, 0);
        dot = Ui.text(c, "●", 10f, Ui.FAINT, Ui.REGULAR);
        statusRow.addView(dot);
        subtitle = Ui.text(c, "Conectando…", 12.5f, Ui.MUTED, Ui.REGULAR);
        subtitle.setPadding(Ui.dp(c, 6), 0, 0, 0);
        statusRow.addView(subtitle);
        titles.addView(statusRow);
        header.addView(titles, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView settings = Ui.iconButton(c, "⚙", 21f, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onOpenSettings(); }
        });
        settings.setTextColor(Ui.MUTED);
        header.addView(settings);
        root.addView(header);

        listFrame = new FrameLayout(c);
        listFrame.setLayoutParams(Ui.lpWeight(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 8));
        list = Ui.column(c);
        scroll.addView(list, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        listFrame.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        loading = new ProgressBar(c);
        loading.setIndeterminate(true);
        FrameLayout.LayoutParams loadLp = new FrameLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42));
        loadLp.gravity = Gravity.CENTER;
        listFrame.addView(loading, loadLp);

        emptyState = Ui.column(c);
        emptyState.setGravity(Gravity.CENTER);
        LinearLayout emptyInner = Ui.column(c);
        emptyInner.setGravity(Gravity.CENTER);
        TextView plus = Ui.text(c, "＋", 40f, Ui.ACCENT, Ui.REGULAR);
        plus.setGravity(Gravity.CENTER);
        emptyInner.addView(plus);
        TextView emptyTitle = Ui.text(c, "Nenhuma conversa ainda", 17f, Ui.TEXT, Ui.MEDIUM);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, Ui.dp(c, 8), 0, 0);
        emptyInner.addView(emptyTitle);
        TextView emptySub = Ui.text(c, "Toque em Nova conversa para começar.", 13f, Ui.MUTED, Ui.REGULAR);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(0, Ui.dp(c, 6), 0, 0);
        emptyInner.addView(emptySub);
        emptyState.addView(emptyInner);
        emptyState.setVisibility(View.GONE);
        listFrame.addView(emptyState, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        errorCard = Ui.column(c);
        errorCard.setBackground(Ui.stroke(Ui.SURFACE, Color.rgb(120, 45, 45), 1, 16f, c));
        errorCard.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
        errorText = Ui.text(c, "", 13f, Ui.TEXT_SOFT, Ui.REGULAR);
        errorText.setLineSpacing(Ui.dp(c, 3), 1.0f);
        errorCard.addView(errorText);
        TextView retry = Ui.text(c, "Tentar novamente", 13f, Ui.ACCENT, Ui.MEDIUM);
        retry.setPadding(0, Ui.dp(c, 10), 0, 0);
        retry.setClickable(true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onRefresh(); }
        });
        errorCard.addView(retry);
        errorCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        errLp.gravity = Gravity.TOP;
        errLp.topMargin = Ui.dp(c, 10);
        errLp.leftMargin = Ui.dp(c, 18);
        errLp.rightMargin = Ui.dp(c, 18);
        listFrame.addView(errorCard, errLp);
        root.addView(listFrame);

        // Footer CTA
        LinearLayout footer = Ui.column(c);
        footer.setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 18), Ui.dp(c, 16));
        newButton = Ui.primaryButton(c, "＋   Nova conversa", new View.OnClickListener() {
            @Override public void onClick(View v) { if (!newBusy) host.onNewChat(); }
        });
        newButton.setTextSize(17f);
        footer.addView(newButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 64)));
        TextView hint = Ui.text(c, "O histórico fica neste aparelho.", 11.5f, Ui.FAINT, Ui.REGULAR);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(c, 10), 0, 0);
        footer.addView(hint);
        root.addView(footer);

        setOnline(false, "Conectando…");
    }

    public View view() { return root; }

    public void setOnline(boolean value, String detail) {
        online = value;
        dot.setTextColor(value ? Ui.GREEN : Ui.FAINT);
        subtitle.setText(detail == null ? (value ? "Harness online" : "Offline") : detail);
    }

    public void setNewChatBusy(boolean busy) {
        this.newBusy = busy;
        newButton.setText(busy ? "Criando conversa…" : "＋   Nova conversa");
        newButton.setAlpha(busy ? 0.6f : 1f);
    }

    public void setLoading(boolean value) {
        loading.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) { emptyState.setVisibility(View.GONE); errorCard.setVisibility(View.GONE); }
    }

    public void setError(String message) {
        loading.setVisibility(View.GONE);
        errorText.setText(message == null ? "Erro ao carregar conversas." : message);
        errorCard.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
    }

    public void setSessions(JSONArray sessions, JSONArray archived) {
        this.activeSessions = sessions;
        this.archivedSessions = archived;
        render();
    }

    private void render() {
        loading.setVisibility(View.GONE);
        errorCard.setVisibility(View.GONE);
        list.removeAllViews();
        int activeCount = activeSessions == null ? 0 : activeSessions.length();
        int archivedCount = archivedSessions == null ? 0 : archivedSessions.length();
        if (archivedCount == 0) archivedExpanded = false;
        if (activeCount == 0 && archivedCount == 0) {
            emptyState.setVisibility(View.VISIBLE);
            return;
        }
        emptyState.setVisibility(View.GONE);
        if (activeCount > 0) {
            TextView section = Ui.text(c, activeCount == 1 ? "1 conversa" : activeCount + " conversas", 11f, Ui.FAINT, Ui.MEDIUM);
            section.setAllCaps(true);
            section.setLetterSpacing(0.12f);
            section.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), 0, Ui.dp(c, 10));
            list.addView(section);
            int limit = Math.min(activeCount, 120);
            for (int i = 0; i < limit; i++) {
                JSONObject item = activeSessions.optJSONObject(i);
                if (item == null) continue;
                addCard(item, false);
            }
        }
        if (archivedCount > 0) {
            addArchivedHeader(archivedCount);
            if (archivedExpanded) {
                int limit = Math.min(archivedCount, 120);
                for (int i = 0; i < limit; i++) {
                    JSONObject item = archivedSessions.optJSONObject(i);
                    if (item == null) continue;
                    addCard(item, true);
                }
            }
        }
    }

    private void addArchivedHeader(final int count) {
        LinearLayout header = Ui.row(c);
        header.setPadding(Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 8));
        header.setClickable(true);
        header.setFocusable(true);
        TextView label = Ui.text(c, "Arquivadas · " + count, 11f, Ui.FAINT, Ui.MEDIUM);
        label.setAllCaps(true);
        label.setLetterSpacing(0.12f);
        header.addView(label, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = Ui.text(c, archivedExpanded ? "⌃" : "⌄", 14f, Ui.FAINT, Ui.REGULAR);
        header.addView(chevron);
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                archivedExpanded = !archivedExpanded;
                render();
            }
        });
        list.addView(header);
    }

    private void addCard(JSONObject item, boolean archived) {
        final String id = item.optString("id", "");
        String title = item.optString("title", "");
        if (title.isEmpty()) title = "Nova conversa";
        final String cwd = item.optString("cwd", "");
        final String finalTitle = title;
        String cwdName = item.optString("cwdName", "");
        if (cwdName.isEmpty()) cwdName = cwd;
        long updated = item.optLong("updatedAt", 0);
        boolean active = item.optBoolean("active", false);
        final boolean webOnly = "ptc".equals(item.optString("preset", ""));

        SwipeCard card = new SwipeCard(c);
        card.setBackground(Ui.ripple(Ui.SURFACE, 18f, c));
        card.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout texts = Ui.column(c);
        TextView titleView = Ui.text(c, title, 16.5f, Ui.TEXT, Ui.MEDIUM);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(titleView);

        LinearLayout meta = Ui.row(c);
        meta.setPadding(0, Ui.dp(c, 7), 0, 0);
        if (webOnly) {
            TextView badge = Ui.text(c, "WEB", 9f, Ui.ACCENT, Ui.MEDIUM);
            badge.setLetterSpacing(0.08f);
            badge.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
            badge.setBackground(Ui.round(Ui.ACCENT_SOFT, 6f, c));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = Ui.dp(c, 8);
            meta.addView(badge, blp);
        }
        if (archived) {
            TextView badge = Ui.text(c, "ARQUIVADA", 9f, Ui.AMBER, Ui.MEDIUM);
            badge.setLetterSpacing(0.08f);
            badge.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
            badge.setBackground(Ui.round(Ui.SURFACE_2, 6f, c));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.rightMargin = Ui.dp(c, 8);
            meta.addView(badge, blp);
        }
        if (active && !archived) {
            TextView pulse = Ui.text(c, "●", 9f, Ui.GREEN, Ui.REGULAR);
            meta.addView(pulse);
            TextView activeText = Ui.text(c, "respondendo", 11.5f, Ui.GREEN, Ui.MEDIUM);
            activeText.setPadding(Ui.dp(c, 5), 0, Ui.dp(c, 8), 0);
            meta.addView(activeText);
        }
        TextView path = Ui.text(c, cwdName, 11.5f, Ui.MUTED, Ui.MONO);
        path.setMaxLines(1);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        meta.addView(path, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView time = Ui.text(c, Ui.timeAgo(updated), 11.5f, Ui.FAINT, Ui.REGULAR);
        time.setPadding(Ui.dp(c, 8), 0, 0, 0);
        meta.addView(time);
        texts.addView(meta);

        if (archived) {
            LinearLayout actions = Ui.row(c);
            actions.setPadding(0, Ui.dp(c, 8), 0, 0);
            TextView unarchive = Ui.text(c, "Desarquivar", 11.5f, Ui.ACCENT, Ui.MEDIUM);
            unarchive.setPadding(0, Ui.dp(c, 3), Ui.dp(c, 18), Ui.dp(c, 3));
            unarchive.setClickable(true);
            unarchive.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { host.onUnarchiveSession(id, finalTitle); }
            });
            actions.addView(unarchive);
            TextView delete = Ui.text(c, "Apagar", 11.5f, Ui.RED, Ui.MEDIUM);
            delete.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
            delete.setClickable(true);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { host.onDeleteSession(id, finalTitle); }
            });
            actions.addView(delete);
            texts.addView(actions);
        }

        card.addView(texts, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!archived) {
            TextView more = Ui.text(c, "⋮", 19f, Ui.MUTED, Ui.REGULAR);
            more.setGravity(Gravity.CENTER);
            more.setMinWidth(Ui.dp(c, 44));
            more.setMinHeight(Ui.dp(c, 44));
            more.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
            more.setBackground(Ui.ripple(Color.TRANSPARENT, 10f, c));
            more.setClickable(true);
            more.setFocusable(true);
            more.setContentDescription("Mais opções da conversa");
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showSessionMenu(v, id, finalTitle); }
            });
            card.addView(more);

            TextView rename = Ui.text(c, "✎", 18f, Ui.MUTED, Ui.REGULAR);
            rename.setGravity(Gravity.CENTER);
            rename.setMinWidth(Ui.dp(c, 44));
            rename.setMinHeight(Ui.dp(c, 44));
            rename.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
            rename.setBackground(Ui.ripple(Color.TRANSPARENT, 10f, c));
            rename.setClickable(true);
            rename.setFocusable(true);
            rename.setContentDescription("Renomear conversa");
            rename.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { host.onRenameSession(id, finalTitle); }
            });
            card.addView(rename);

            card.setSwipeListener(new SwipeListener() {
                @Override public void onSwipeLeft() { host.onDeleteSession(id, finalTitle); }
                @Override public void onSwipeRight() { host.onArchiveSession(id, finalTitle); }
            });
        }

        TextView arrow = Ui.text(c, "›", 24f, Ui.FAINT, Ui.REGULAR);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(Ui.dp(c, 8), 0, 0, 0);
        card.addView(arrow);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.onOpenSession(id, finalTitle, cwd, webOnly);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                host.onRenameSession(id, finalTitle);
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 10);
        list.addView(card, lp);
    }

    private void showSessionMenu(View anchor, final String id, final String title) {
        PopupMenu popup = new PopupMenu(c, anchor);
        popup.getMenu().add(0, 1, 0, "Arquivar");
        popup.getMenu().add(0, 2, 1, "Apagar");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) host.onArchiveSession(id, title);
                else if (item.getItemId() == 2) host.onDeleteSession(id, title);
                return true;
            }
        });
        popup.show();
    }

    private interface SwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    /** Horizontal swipe, but lets vertical scrolls and child taps pass through. */
    private final class SwipeCard extends LinearLayout {
        private final int slop;
        private final int threshold;
        private float downX;
        private float downY;
        private boolean swiping;
        private SwipeListener swipeListener;

        SwipeCard(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setBaselineAligned(false);
            slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
            threshold = Ui.dp(context, 90);
        }

        void setSwipeListener(SwipeListener listener) { swipeListener = listener; }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    swiping = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        swiping = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    swiping = false;
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    setTranslationX(dx * 0.25f);
                    return true;
                case MotionEvent.ACTION_UP:
                    float endX = ev.getX() - downX;
                    setTranslationX(0);
                    if (Math.abs(endX) <= slop) {
                        performClick();
                    } else if (swipeListener != null) {
                        if (endX < -threshold) swipeListener.onSwipeLeft();
                        else if (endX > threshold) swipeListener.onSwipeRight();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setTranslationX(0);
                    return true;
                default:
                    return true;
            }
        }
    }

    public boolean isOnline() { return online; }
}
