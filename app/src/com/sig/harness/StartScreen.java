package com.sig.harness;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** First-run / offline screen with the big "Iniciar Harness" button. */
public final class StartScreen {
    public interface Host {
        void onStart();
        void onConnectExisting();
        void onOpenTermux();
        void onInstallTermux();
        void onOpenSettings();
        void onCopyBootstrap();
        void onCopyTermuxSetup();
    }

    private static final String[] STEP_LABELS = {"Termux", "Pacotes", "Bridge", "Autenticação", "Runtime"};
    private final Context c;
    private final Host host;
    private final View root;
    private final TextView[] stepGlyph = new TextView[5];
    private final TextView[] stepDetail = new TextView[5];
    private final TextView statusText;
    private final TextView primary;
    private final TextView secondary;
    private final TextView logText;
    private final LinearLayout logBox;
    private final LinearLayout errorBox;
    private final TextView errorTitle;
    private final TextView errorDetail;
    private final TextView errorRetry;
    private boolean busy;
    private boolean termuxInstalled = true;

    public StartScreen(Context context, Host host) {
        this.c = context;
        this.host = host;

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout content = Ui.column(c);
        content.setPadding(Ui.dp(c, 24), Ui.dp(c, 20), Ui.dp(c, 24), Ui.dp(c, 40));
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // Brand
        LinearLayout brand = Ui.row(c);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = Ui.text(c, "SIG", 30f, Ui.ACCENT, Ui.BLACK);
        logo.setLetterSpacing(0.04f);
        brand.addView(logo);
        TextView brandWord = Ui.text(c, "HARNESS", 11f, Ui.MUTED, Ui.MEDIUM);
        brandWord.setLetterSpacing(0.3f);
        brandWord.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), 0, 0);
        brand.addView(brandWord);
        content.addView(brand);

        TextView headline = Ui.text(c, "Seu harness,\nno bolso.", 32f, Ui.TEXT, Ui.BOLD);
        headline.setLineSpacing(Ui.dp(c, 2), 1.0f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(c, 22);
        content.addView(headline, hlp);

        TextView sub = Ui.text(c, "Converse com o DeepSeek Harness sem abrir o Chrome nem copiar links de autenticação.", 15f, Ui.MUTED, Ui.REGULAR);
        sub.setLineSpacing(Ui.dp(c, 4), 1.0f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(c, 12);
        content.addView(sub, slp);

        // Hero status card with gradient
        LinearLayout card = Ui.column(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.rgb(22, 35, 58), Color.rgb(16, 22, 32)});
        bg.setCornerRadius(Ui.dp(c, 22));
        bg.setStroke(Ui.dp(c, 1), Color.rgb(36, 50, 74));
        card.setBackground(bg);
        card.setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(c, 26);
        content.addView(card, clp);

        LinearLayout statusRow = Ui.row(c);
        TextView dot = Ui.text(c, "●", 12f, Ui.FAINT, Ui.REGULAR);
        statusRow.addView(dot);
        statusText = Ui.text(c, "Harness parado", 14.5f, Ui.TEXT_SOFT, Ui.MEDIUM);
        statusText.setPadding(Ui.dp(c, 8), 0, 0, 0);
        statusRow.addView(statusText, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final ProgressBar spinner = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        statusRow.addView(spinner);
        card.addView(statusRow);
        LinearLayout steps = Ui.column(c);
        steps.setPadding(0, Ui.dp(c, 12), 0, 0);
        for (int i = 0; i < STEP_LABELS.length; i++) {
            LinearLayout row = Ui.row(c);
            row.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            stepGlyph[i] = Ui.text(c, "○", 18f, Ui.FAINT, Ui.REGULAR);
            stepGlyph[i].setGravity(Gravity.CENTER);
            stepGlyph[i].setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
            row.addView(stepGlyph[i]);
            TextView label = Ui.text(c, STEP_LABELS[i], 13.5f, Ui.TEXT_SOFT, Ui.MEDIUM);
            label.setPadding(Ui.dp(c, 10), 0, 0, 0);
            row.addView(label);
            stepDetail[i] = Ui.text(c, "", 11.5f, Ui.FAINT, Ui.REGULAR);
            stepDetail[i].setGravity(Gravity.END);
            row.addView(stepDetail[i], Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            steps.addView(row);
        }
        card.addView(steps);

        logBox = Ui.column(c);
        logBox.setBackground(Ui.round(Ui.CODE_BG, 10f, c));
        logBox.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        logText = Ui.text(c, "", 10f, Ui.MUTED, Ui.MONO);
        logText.setMaxLines(4);
        logText.setLineSpacing(Ui.dp(c, 1), 1.0f);
        logBox.addView(logText);
        logBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logLp.topMargin = Ui.dp(c, 12);
        card.addView(logBox, logLp);

        // Error box
        errorBox = Ui.column(c);
        errorBox.setBackground(Ui.stroke(Color.rgb(35, 22, 24), Color.rgb(120, 45, 45), 1, 14f, c));
        errorBox.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        errorTitle = Ui.text(c, "", 14f, Ui.RED, Ui.MEDIUM);
        errorBox.addView(errorTitle);
        errorDetail = Ui.text(c, "", 12f, Ui.MUTED, Ui.REGULAR);
        errorDetail.setLineSpacing(Ui.dp(c, 3), 1.0f);
        LinearLayout.LayoutParams edlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        edlp.topMargin = Ui.dp(c, 5);
        errorDetail.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        c.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SIG Harness", errorDetail.getText()));
                    android.widget.Toast.makeText(c, "Erro copiado", android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        errorBox.addView(errorDetail, edlp);

        TextView copyHint = Ui.text(c, "segure o texto acima para copiar", 10.5f, Ui.FAINT, Ui.REGULAR);
        copyHint.setPadding(0, Ui.dp(c, 4), 0, 0);
        errorBox.addView(copyHint);
        errorRetry = Ui.text(c, "Tentar novamente", 13f, Ui.RED, Ui.MEDIUM);
        errorRetry.setPadding(0, Ui.dp(c, 10), 0, 0);
        errorRetry.setClickable(true);
        errorRetry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideError(); host.onStart(); }
        });
        errorBox.addView(errorRetry);
        errorBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(c, 12);
        card.addView(errorBox, elp);

        // Primary CTA
        primary = Ui.primaryButton(c, "Iniciar Harness", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!termuxInstalled) { host.onInstallTermux(); return; }
                if (!busy) host.onStart();
            }
        });
        primary.setTextSize(18f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 68));
        plp.topMargin = Ui.dp(c, 18);
        content.addView(primary, plp);

        secondary = Ui.text(c, "Já está rodando? Conectar", 13.5f, Ui.ACCENT, Ui.MEDIUM);
        secondary.setGravity(Gravity.CENTER);
        secondary.setPadding(0, Ui.dp(c, 18), 0, Ui.dp(c, 6));
        secondary.setClickable(true);
        secondary.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!busy) host.onConnectExisting(); }
        });
        content.addView(secondary);

        TextView fallback = Ui.text(c, "Comando manual (copiar)", 12.5f, Ui.MUTED, Ui.MEDIUM);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(0, Ui.dp(c, 14), 0, 0);
        fallback.setClickable(true);
        fallback.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onCopyBootstrap(); }
        });
        content.addView(fallback);

        TextView liberar = Ui.text(c, "Liberar o Termux (copiar)", 12.5f, Ui.MUTED, Ui.MEDIUM);
        liberar.setGravity(Gravity.CENTER);
        liberar.setPadding(0, Ui.dp(c, 8), 0, 0);
        liberar.setClickable(true);
        liberar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onCopyTermuxSetup(); }
        });
        content.addView(liberar);

        TextView foot = Ui.text(c, "O SIG fala direto com o DSH pelo Termux. A autenticação é automática — nada de navegador.", 11.5f, Ui.FAINT, Ui.REGULAR);
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(Ui.dp(c, 2), 1.0f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(c, 20);
        content.addView(foot, flp);

        TextView settingsLink = Ui.text(c, "Ajustes", 12.5f, Ui.MUTED, Ui.MEDIUM);
        settingsLink.setGravity(Gravity.CENTER);
        settingsLink.setPadding(0, Ui.dp(c, 26), 0, 0);
        settingsLink.setClickable(true);
        settingsLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onOpenSettings(); }
        });
        content.addView(settingsLink);

        this.root = scroll;
        this.spinnerRef = spinner;
    }

    private final ProgressBar spinnerRef;
    private final TextView dotRef = null;

    public View view() { return root; }

    public void setTermuxInstalled(boolean installed) {
        this.termuxInstalled = installed;
        primary.setText(installed ? (busy ? "Iniciando…" : "Iniciar Harness") : "Instalar Termux");
    }

    public void setBusy(boolean value) {
        this.busy = value;
        primary.setText(termuxInstalled ? (value ? "Iniciando…" : "Iniciar Harness") : "Instalar Termux");
        primary.setAlpha(value ? 0.55f : 1f);
        secondary.setAlpha(value ? 0.4f : 1f);
        spinnerRef.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) hideError();
    }

    public void resetSteps() {
        for (int i = 0; i < 4; i++) setStep(i, 0, "");
        logText.setText("");
        logBox.setVisibility(View.GONE);
    }

    /** state: 0 pending, 1 active, 2 done, 3 error */
    public void setStep(int index, int state, String detail) {
        if (index < 0 || index > 3) return;
        if (state == 0) { stepGlyph[index].setText("○"); stepGlyph[index].setTextColor(Ui.FAINT); }
        else if (state == 1) { stepGlyph[index].setText("◔"); stepGlyph[index].setTextColor(Ui.ACCENT); }
        else if (state == 2) { stepGlyph[index].setText("✓"); stepGlyph[index].setTextColor(Ui.GREEN); }
        else { stepGlyph[index].setText("!"); stepGlyph[index].setTextColor(Ui.RED); }
        stepDetail[index].setText(detail == null ? "" : detail);
        stepDetail[index].setTextColor(state == 3 ? Ui.RED : (state == 2 ? Ui.GREEN : Ui.FAINT));
    }

    public void setStatus(String value, int color) {
        statusText.setText(value == null ? "" : value);
        statusText.setTextColor(color);
    }

    public void setLog(String line) {
        if (line == null || line.trim().isEmpty()) return;
        String clean = line.trim();
        if (clean.length() > 140) clean = clean.substring(0, 139) + "…";
        String existing = logText.getText().toString();
        String[] parts = existing.isEmpty() ? new String[0] : existing.split("\n");
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, parts.length - 3);
        for (int i = start; i < parts.length; i++) { if (sb.length() > 0) sb.append('\n'); sb.append(parts[i]); }
        if (sb.length() > 0) sb.append('\n');
        sb.append(clean);
        logText.setText(sb.toString());
        logBox.setVisibility(View.VISIBLE);
    }

    public void showError(String title, String detail) {
        errorTitle.setText(title);
        errorDetail.setText(detail);
        errorBox.setVisibility(View.VISIBLE);
        setBusy(false);
        setStatus("Não foi possível iniciar", Ui.RED);
    }

    public void hideError() { errorBox.setVisibility(View.GONE); }

    public void setErrorRetryLabel(String label) { errorRetry.setText(label); }
}
