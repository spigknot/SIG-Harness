package com.sig.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/** Small dialog for the dictation provider and API keys used by the web mic. */
public final class VoiceSettings {
    private VoiceSettings() {}

    public static void show(final Activity activity, final Prefs prefs) {
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(activity, 22), Ui.dp(activity, 8), Ui.dp(activity, 22), Ui.dp(activity, 4));

        TextView hint = Ui.text(activity, "O áudio sai do app direto para o provedor escolhido; não passa pelo Termux nem pelo bridge.", 12f, Ui.MUTED, Ui.REGULAR);
        hint.setLineSpacing(Ui.dp(activity, 3), 1f);
        content.addView(hint);

        final RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 6));
        final RadioButton muse = new RadioButton(activity);
        muse.setText("Meta Muse Voice");
        muse.setTextColor(Ui.TEXT);
        muse.setId(1001);
        final RadioButton gemini = new RadioButton(activity);
        gemini.setText("Gemini 3.5 Transcribe");
        gemini.setTextColor(Ui.TEXT);
        gemini.setId(1002);
        group.addView(muse);
        group.addView(gemini);
        group.check("gemini".equals(prefs.sttProvider()) ? 1002 : 1001);
        content.addView(group);

        TextView museLabel = Ui.text(activity, "Chave Meta Muse", 11f, Ui.FAINT, Ui.MEDIUM);
        museLabel.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4));
        content.addView(museLabel);
        final EditText museKey = keyField(activity, "accessToken da Meta", prefs.museKey());
        content.addView(museKey, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView geminiLabel = Ui.text(activity, "Chave Gemini / AI Studio", 11f, Ui.FAINT, Ui.MEDIUM);
        geminiLabel.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));
        content.addView(geminiLabel);
        final EditText geminiKey = keyField(activity, "Chave do Google AI Studio", prefs.geminiKey());
        content.addView(geminiKey, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
                .setTitle("Ditar por voz")
                .setView(content)
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String provider = group.getCheckedRadioButtonId() == 1002 ? "gemini" : "muse";
                        prefs.setSttProvider(provider);
                        prefs.setMuseKey(museKey.getText().toString());
                        prefs.setGeminiKey(geminiKey.getText().toString());
                        boolean configured = "gemini".equals(provider)
                                ? !geminiKey.getText().toString().trim().isEmpty()
                                : !museKey.getText().toString().trim().isEmpty();
                        Toast.makeText(activity,
                                configured ? "Transcrição configurada." : "Chave vazia: o microfone pedirá a configuração.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static EditText keyField(Activity activity, String hint, String value) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setHintTextColor(Ui.FAINT);
        field.setTextColor(Ui.TEXT);
        field.setTextSize(13f);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setText(value == null ? "" : value);
        field.setBackground(Ui.ripple(Ui.SURFACE_2, 8f, activity));
        field.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 8));
        return field;
    }
}
