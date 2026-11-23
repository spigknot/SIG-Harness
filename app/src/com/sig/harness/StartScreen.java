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

    private final LinearLayout root;
    private final TextView status;
    private final TextView detail;
    private final TextView primary;
    private final TextView repair;
    private final LinearLayout setup;
    private final ProgressBar progress;
    private boolean termuxInstalled;
    private boolean busy;

    public StartScreen(Context c, Host host) {
        root = Ui.column(c);
        root.setBackgroundColor(Ui.BG);

        repair = Ui.text(c, "Reparar instalação", 13f, Ui.ACCENT, Ui.MEDIUM);
        repair.setGravity(Gravity.CENTER);
        repair.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        repair.setBackground(Ui.ripple(android.graphics.Color.TRANSPARENT, 8f, c));
        repair.setOnClickListener(v -> host.onRepair());
        repair.setFocusable(true);
        LinearLayout.LayoutParams repairLayout = Ui.lp(-2, Ui.dp(c, 48));
        repairLayout.gravity = Gravity.END;
        repairLayout.setMarginEnd(Ui.dp(c, 12));
        root.addView(repair, repairLayout);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        root.addView(scroll, Ui.lpWeight(-1, 0, 1f));
        LinearLayout body = Ui.column(c);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24), Ui.dp(c, 24));
        scroll.addView(body);

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

        primary = Ui.primaryButton(c, "Iniciar Harness", v -> {
            if (termuxInstalled) host.onStart(); else host.onInstallTermux();
        });
        add(body, primary, 20);
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
        primary.setText(termuxInstalled ? "Iniciar Harness" : "Instalar Termux");
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

    public void showIdle() {
        setBusy(false);
        setStatus("Pronto para iniciar", Ui.TEXT_SOFT);
        detail.setVisibility(View.GONE);
    }
}
