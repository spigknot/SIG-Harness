package com.sig.harness;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows startup progress and the actions needed to recover the installation. */
public final class StartScreen {
    public interface Host {
        void onStart();
        void onRepair();
        void onOpenTermux();
        void onInstallTermux();
        void onOpenSettings();
    }

    private final ScrollView root;
    private final TextView status;
    private final TextView detail;
    private final TextView primary;
    private final TextView repair;
    private final LinearLayout setup;
    private final ProgressBar progress;
    private boolean termuxInstalled;
    private boolean busy;

    public StartScreen(Context c, Host host) {
        root = new ScrollView(c);
        root.setBackgroundColor(Ui.BG);
        root.setFillViewport(true);
        LinearLayout body = Ui.column(c);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24));
        root.addView(body);

        body.addView(Ui.text(c, "SIG Harness", 26f, Ui.TEXT, Ui.MEDIUM));
        status = Ui.text(c, "", 16f, Ui.TEXT_SOFT, Ui.MEDIUM);
        add(body, status, 16);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        detail = Ui.text(c, "", 14f, Ui.MUTED, Ui.REGULAR);
        add(body, detail, 8);
        detail.setVisibility(View.GONE);

        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        add(body, progress, 12);

        primary = Ui.primaryButton(c, "Abrir Harness", v -> {
            if (termuxInstalled) host.onStart(); else host.onInstallTermux();
        });
        add(body, primary, 20);
        repair = Ui.ghostButton(c, "Reparar instalação", v -> host.onRepair());
        add(body, repair, 8);

        setup = Ui.column(c);
        add(setup, Ui.ghostButton(c, "Abrir Termux", v -> host.onOpenTermux()), 8);
        add(setup, Ui.ghostButton(c, "Permissões do SIG Harness", v -> host.onOpenSettings()), 4);
        add(body, setup, 12);
        setBusy(false);
    }

    private static void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(parent.getContext(), gap);
        parent.addView(view, lp);
    }

    public View view() { return root; }

    public void setTermuxInstalled(boolean installed) {
        termuxInstalled = installed;
        updateActions();
    }

    public void setBusy(boolean value) {
        busy = value;
        if (value) detail.setVisibility(View.GONE);
        updateActions();
    }

    private void updateActions() {
        primary.setText(termuxInstalled ? "Abrir Harness" : "Instalar Termux");
        primary.setVisibility(busy ? View.GONE : View.VISIBLE);
        repair.setVisibility(!busy && termuxInstalled ? View.VISIBLE : View.GONE);
        setup.setVisibility(!busy && termuxInstalled ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    public void setStatus(String value, int color) {
        status.setText(value);
        status.setTextColor(color);
    }

    public void showError(String title, String message) {
        setBusy(false);
        setStatus(title, Ui.RED);
        detail.setText(message);
        detail.setVisibility(View.VISIBLE);
    }
}
