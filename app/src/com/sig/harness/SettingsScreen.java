package com.sig.harness;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Connection and workspace settings. */
public final class SettingsScreen {
    public interface Host {
        void onBack();
        void onSaveCwd(String value);
        void onStopHarness();
        void onRestartRuntime();
        void onRequestTermuxPermission();
        void onOpenAppSettings();
        void onOpenTermux();
        void onToggleBoot(boolean enabled);
        void onInstallBoot();
        void onOpenWeb();
        void onSaveStt(String provider, String museKey, String geminiKey);
    }

    private final Context c;
    private final Host host;
    private final LinearLayout root;
    private final TextView status;
    private final TextView details;
    private final TextView permission;
    private final TextView permissionButton;
    private final EditText cwd;
    private final TextView saveCwd;
    private final TextView stopButton;
    private final TextView restartButton;
    private final TextView bootButton;
    private final TextView bootStatus;
    private EditText museKey;
    private EditText geminiKey;
    private TextView museChoice;
    private TextView geminiChoice;
    private TextView sttSelected;
    private TextView saveStt;
    private String sttProvider = "muse";
    private boolean bootEnabled;

    public SettingsScreen(Context context, Host host) {
        this.c = context;
        this.host = host;
        root = Ui.column(c);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = Ui.row(c);
        header.setPadding(Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 10));
        TextView back = Ui.iconButton(c, "←", 24f, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onBack(); }
        });
        header.addView(back);
        TextView title = Ui.text(c, "Ajustes", 24f, Ui.TEXT, Ui.BOLD);
        title.setPadding(Ui.dp(c, 6), 0, 0, 0);
        header.addView(title, Ui.lpWeight(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(c);
        content.setPadding(Ui.dp(c, 20), Ui.dp(c, 6), Ui.dp(c, 20), Ui.dp(c, 32));
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, Ui.lpWeight(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Workspace
        content.addView(section("Pasta de trabalho"));
        LinearLayout work = card();
        TextView workHint = Ui.text(c, "O agente começa nesta pasta. Use o caminho absoluto no Termux.", 12f, Ui.MUTED, Ui.REGULAR);
        workHint.setLineSpacing(Ui.dp(c, 3), 1.0f);
        work.addView(workHint);
        cwd = new EditText(c);
        cwd.setTextSize(13f);
        cwd.setTypeface(Ui.MONO);
        cwd.setTextColor(Ui.TEXT);
        cwd.setHintTextColor(Ui.FAINT);
        cwd.setHint("/data/data/com.termux/files/home");
        cwd.setBackground(Ui.round(Ui.SURFACE_2, 12f, c));
        cwd.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        cwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cwd.setSingleLine(true);
        LinearLayout.LayoutParams cwdLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cwdLp.topMargin = Ui.dp(c, 12);
        work.addView(cwd, cwdLp);
        saveCwd = Ui.button(c, "Salvar pasta", Ui.SURFACE_3, Ui.TEXT, 14f, 14f);
        saveCwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onSaveCwd(cwd.getText().toString()); }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        saveLp.topMargin = Ui.dp(c, 12);
        work.addView(saveCwd, saveLp);
        content.addView(work);

        // Speech to text
        content.addView(section("Transcrição de voz"));
        LinearLayout stt = card();
        TextView sttHint = Ui.text(c, "O áudio vai direto do app para o provedor escolhido; não passa pelo bridge. Escolha o modelo e cole a API key.", 12f, Ui.MUTED, Ui.REGULAR);
        sttHint.setLineSpacing(Ui.dp(c, 3), 1.0f);
        stt.addView(sttHint);

        LinearLayout choices = Ui.row(c);
        LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f);
        choiceLp.topMargin = Ui.dp(c, 12);
        museChoice = sttChoice("Muse Voice");
        geminiChoice = sttChoice("Gemini 3.5 Transcribe");
        choices.addView(museChoice, new LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f));
        LinearLayout.LayoutParams gclp = new LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1f);
        gclp.leftMargin = Ui.dp(c, 8);
        choices.addView(geminiChoice, gclp);
        stt.addView(choices);

        sttSelected = Ui.text(c, "", 11.5f, Ui.MUTED, Ui.REGULAR);
        sttSelected.setPadding(0, Ui.dp(c, 8), 0, 0);
        stt.addView(sttSelected);

        TextView museLabel = Ui.text(c, "Chave Meta Muse", 11f, Ui.FAINT, Ui.MEDIUM);
        museLabel.setAllCaps(true);
        museLabel.setLetterSpacing(0.08f);
        museLabel.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 4));
        stt.addView(museLabel);
        museKey = keyField("Cole a chave da Meta (accessToken)");
        stt.addView(museKey);

        TextView geminiLabel = Ui.text(c, "Chave Google AI Studio", 11f, Ui.FAINT, Ui.MEDIUM);
        geminiLabel.setAllCaps(true);
        geminiLabel.setLetterSpacing(0.08f);
        geminiLabel.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 4));
        stt.addView(geminiLabel);
        geminiKey = keyField("Cole a chave do Gemini/AI Studio");
        stt.addView(geminiKey);

        saveStt = Ui.button(c, "Salvar transcrição", Ui.SURFACE_2, Ui.TEXT_SOFT, 13.5f, 14f);
        saveStt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.onSaveStt(sttProvider, museKey.getText().toString(), geminiKey.getText().toString());
            }
        });
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        stlp.topMargin = Ui.dp(c, 12);
        stt.addView(saveStt, stlp);
        content.addView(stt);
        selectStt("muse");

        // Harness
        content.addView(section("Harness"));
        LinearLayout harness = card();
        status = Ui.text(c, "●  Offline", 14f, Ui.MUTED, Ui.MEDIUM);
        harness.addView(status);
        details = Ui.text(c, "Pare o harness para encerrar o runtime no Termux.", 12f, Ui.MUTED, Ui.REGULAR);
        details.setLineSpacing(Ui.dp(c, 3), 1.0f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(c, 8);
        harness.addView(details, dlp);

        restartButton = Ui.button(c, "Reiniciar runtime", Ui.SURFACE_2, Ui.TEXT_SOFT, 13.5f, 14f);
        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onRestartRuntime(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        rlp.topMargin = Ui.dp(c, 14);
        harness.addView(restartButton, rlp);

        stopButton = Ui.button(c, "Parar harness", Color.TRANSPARENT, Ui.RED, 13.5f, 14f);
        stopButton.setBackground(Ui.rippleStroke(Color.TRANSPARENT, Color.rgb(110, 44, 44), 1, 14f, c));
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onStopHarness(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        slp.topMargin = Ui.dp(c, 10);
        harness.addView(stopButton, slp);
        content.addView(harness);

        // Boot
        content.addView(section("Inicialização"));
        LinearLayout boot = card();
        TextView bootTitle = Ui.text(c, "Iniciar o harness no boot", 14f, Ui.TEXT, Ui.MEDIUM);
        boot.addView(bootTitle);
        TextView bootHint = Ui.text(c, "Sobe o runtime sozinho quando o Android liga. Usa o app Termux:Boot.", 12f, Ui.MUTED, Ui.REGULAR);
        bootHint.setLineSpacing(Ui.dp(c, 3), 1.0f);
        LinearLayout.LayoutParams bhlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bhlp.topMargin = Ui.dp(c, 6);
        boot.addView(bootHint, bhlp);
        bootButton = Ui.button(c, "Desativado", Ui.SURFACE_2, Ui.TEXT_SOFT, 13.5f, 14f);
        bootButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bootEnabled = !bootEnabled;
                host.onToggleBoot(bootEnabled);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        blp.topMargin = Ui.dp(c, 12);
        boot.addView(bootButton, blp);
        bootStatus = Ui.text(c, "Verificando o Termux:Boot…", 11.5f, Ui.MUTED, Ui.REGULAR);
        bootStatus.setLineSpacing(Ui.dp(c, 3), 1.0f);
        bootStatus.setPadding(0, Ui.dp(c, 10), 0, 0);
        boot.addView(bootStatus);
        content.addView(boot);

        // Web mode
        content.addView(section("Interface web"));
        LinearLayout webCard = card();
        TextView webHint = Ui.text(c, "Abre a interface completa do DSH com autenticação automática. O modo nativo fica pausado enquanto você usa a web.", 12f, Ui.MUTED, Ui.REGULAR);
        webHint.setLineSpacing(Ui.dp(c, 3), 1.0f);
        webCard.addView(webHint);
        TextView openWeb = Ui.button(c, "Abrir modo web completo", Ui.SURFACE_2, Ui.TEXT_SOFT, 13.5f, 14f);
        openWeb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onOpenWeb(); }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        wlp.topMargin = Ui.dp(c, 12);
        webCard.addView(openWeb, wlp);
        content.addView(webCard);

        // Termux permission
        content.addView(section("Permissão do Termux"));
        LinearLayout perm = card();
        permission = Ui.text(c, "Verificando…", 12.5f, Ui.MUTED, Ui.REGULAR);
        permission.setLineSpacing(Ui.dp(c, 3), 1.0f);
        perm.addView(permission);
        permissionButton = Ui.button(c, "Solicitar permissão", Ui.SURFACE_2, Ui.TEXT_SOFT, 13.5f, 14f);
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onRequestTermuxPermission(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52));
        plp.topMargin = Ui.dp(c, 12);
        perm.addView(permissionButton, plp);
        TextView termuxLink = Ui.text(c, "Abrir Termux", 13f, Ui.ACCENT, Ui.MEDIUM);
        termuxLink.setGravity(Gravity.CENTER);
        termuxLink.setPadding(0, Ui.dp(c, 12), 0, 0);
        termuxLink.setClickable(true);
        termuxLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onOpenTermux(); }
        });
        perm.addView(termuxLink);
        content.addView(perm);

        // About
        content.addView(section("Sobre"));
        LinearLayout about = card();
        TextView name = Ui.text(c, "SIG Harness 1.3.2", 15f, Ui.TEXT, Ui.MEDIUM);
        about.addView(name);
        TextView desc = Ui.text(c, "Cliente nativo para o DeepSeek Harness no Termux. O app conversa via ACP com o runtime e mantém o histórico no aparelho.", 12f, Ui.MUTED, Ui.REGULAR);
        desc.setLineSpacing(Ui.dp(c, 3), 1.0f);
        LinearLayout.LayoutParams adlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        adlp.topMargin = Ui.dp(c, 8);
        about.addView(desc, adlp);
        TextView bridgePath = Ui.text(c, "Bridge: ~/.sig-harness/sig-bridge.mjs", 11f, Ui.FAINT, Ui.MONO);
        bridgePath.setPadding(0, Ui.dp(c, 10), 0, 0);
        about.addView(bridgePath);
        content.addView(about);
    }

    private TextView sttChoice(final String label) {
        final TextView view = Ui.text(c, label, 12.5f, Ui.TEXT_SOFT, Ui.MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private EditText keyField(String hint) {
        EditText field = new EditText(c);
        field.setTextSize(12.5f);
        field.setTypeface(Ui.MONO);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.FAINT);
        field.setHint(hint);
        field.setBackground(Ui.round(Ui.SURFACE_2, 10f, c));
        field.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 2);
        field.setLayoutParams(lp);
        return field;
    }

    private void selectStt(String provider) {
        sttProvider = "gemini".equals(provider) ? "gemini" : "muse";
        if (museChoice != null) {
            boolean muse = "muse".equals(sttProvider);
            museChoice.setBackground(Ui.ripple(muse ? Ui.ACCENT : Ui.SURFACE_2, 12f, c));
            museChoice.setTextColor(muse ? Color.WHITE : Ui.TEXT_SOFT);
            museChoice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectStt("muse"); }
            });
        }
        if (geminiChoice != null) {
            boolean gemini = "gemini".equals(sttProvider);
            geminiChoice.setBackground(Ui.ripple(gemini ? Ui.ACCENT : Ui.SURFACE_2, 12f, c));
            geminiChoice.setTextColor(gemini ? Color.WHITE : Ui.TEXT_SOFT);
            geminiChoice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectStt("gemini"); }
            });
        }
        if (sttSelected != null) {
            sttSelected.setText("Modelo: " + ("gemini".equals(sttProvider) ? "Gemini 3.5 Transcribe" : "Meta Muse Voice"));
        }
    }

    private TextView section(String label) {
        TextView tv = Ui.text(c, label, 11f, Ui.FAINT, Ui.MEDIUM);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 22);
        lp.bottomMargin = Ui.dp(c, 8);
        lp.leftMargin = Ui.dp(c, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.round(Ui.SURFACE, 16f, c));
        card.setPadding(Ui.dp(c, 16), Ui.dp(c, 15), Ui.dp(c, 16), Ui.dp(c, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 6);
        card.setLayoutParams(lp);
        return card;
    }

    public View view() { return root; }

    public void setConnection(String state, String detail, int port, int pid) {
        boolean online = "ready".equals(state);
        status.setText(online ? "●  Harness online" : "●  " + (detail == null || detail.isEmpty() ? "Offline" : detail));
        status.setTextColor(online ? Ui.GREEN : ("starting".equals(state) ? Ui.AMBER : Ui.MUTED));
        details.setText("Bridge na porta " + port + (pid > 0 ? " · bridge pid " + pid : "") + "\n" + (detail == null ? "" : detail));
        stopButton.setEnabled(true);
        stopButton.setAlpha(1f);
    }

    public void setPermission(boolean granted) {
        permission.setText(granted
            ? "Concedida. O app pode iniciar o bridge no Termux."
            : "Não concedida. Sem ela o app não consegue iniciar o harness.");
        permission.setTextColor(granted ? Ui.GREEN : Ui.AMBER);
        permissionButton.setText(granted ? "Abrir configurações do app" : "Solicitar permissão");
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (granted) host.onOpenAppSettings(); else host.onRequestTermuxPermission();
            }
        });
    }

    public void setStt(String provider, String muse, String gemini) {
        selectStt(provider);
        if (museKey != null) museKey.setText(muse == null ? "" : muse);
        if (geminiKey != null) geminiKey.setText(gemini == null ? "" : gemini);
    }

    public void setSttSaved() {
        if (saveStt == null) return;
        saveStt.setText("Transcrição salva ✓");
        saveStt.postDelayed(new Runnable() {
            @Override public void run() { saveStt.setText("Salvar transcrição"); }
        }, 1500);
    }

    public void setCwd(String value) {
        cwd.setText(value == null ? "" : value);
    }

    public void setCwdSaved() {
        saveCwd.setText("Pasta salva ✓");
        saveCwd.postDelayed(new Runnable() {
            @Override public void run() { saveCwd.setText("Salvar pasta"); }
        }, 1500);
    }

    public void setRestartLabel(String value) { restartButton.setText(value); }

    public void setBoot(boolean enabled, boolean installed) {
        bootEnabled = enabled;
        bootButton.setText(enabled ? "Ativado" : "Desativado");
        bootButton.setBackground(Ui.ripple(enabled ? Ui.ACCENT : Ui.SURFACE_2, 14f, c));
        bootButton.setTextColor(enabled ? Color.WHITE : Ui.TEXT_SOFT);
        if (installed) {
            bootStatus.setText("Termux:Boot instalado. O harness sobe junto com o Android.");
            bootStatus.setTextColor(Ui.MUTED);
            bootStatus.setOnClickListener(null);
            bootStatus.setClickable(false);
        } else {
            bootStatus.setText("Termux:Boot não instalado. O script é criado, mas só roda com o app Termux:Boot. Toque para instalar.");
            bootStatus.setTextColor(Ui.AMBER);
            bootStatus.setClickable(true);
            bootStatus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { host.onInstallBoot(); }
            });
        }
    }
}
