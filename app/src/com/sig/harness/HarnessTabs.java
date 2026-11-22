package com.sig.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Keeps one attached page per open tab and saves the list of remote addresses. */
final class HarnessTabs {
    private final Activity activity;
    private final WebFilePicker files;
    private final Consumer<String> openBrowser;
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final LinearLayout labels;
    private final FrameLayout pages;
    private final List<Tab> tabs = new ArrayList<>();
    private final Tab local = new Tab("Local", null);
    private Tab selected = local;

    HarnessTabs(Activity activity, View localPage, WebFilePicker files, Consumer<String> openBrowser) {
        this.activity = activity;
        this.files = files;
        this.openBrowser = openBrowser;
        prefs = activity.getSharedPreferences("harness_tabs", Context.MODE_PRIVATE);
        root = Ui.column(activity);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.row(activity);
        bar.setBackgroundColor(Ui.BG_ELEVATED);
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        labels = Ui.row(activity);
        labels.setPadding(dp(8), 0, dp(4), 0);
        scroll.addView(labels);
        bar.addView(scroll, Ui.lpWeight(0, dp(48), 1f));
        TextView add = Ui.iconButton(activity, "+", 25f, v -> edit(null));
        add.setContentDescription("Adicionar aba");
        bar.addView(add);
        root.addView(bar, Ui.lp(-1, dp(48)));
        View line = new View(activity);
        line.setBackgroundColor(Ui.SURFACE_3);
        root.addView(line, Ui.lp(-1, dp(1)));

        pages = new FrameLayout(activity);
        root.addView(pages, Ui.lpWeight(-1, 0, 1f));
        local.page = localPage;
        pages.addView(localPage, new FrameLayout.LayoutParams(-1, -1));
        addLabel(local);
        style(local);
        restore();
    }

    View view() { return root; }

    boolean handleBack() {
        if (selected == local) return false;
        if (!selected.remote.goBack()) select(local);
        return true;
    }

    void destroy() {
        for (Tab tab : tabs) closePage(tab);
    }

    private void addLabel(Tab tab) {
        tab.button = Ui.text(activity, tab.label, 14f, Ui.TEXT_SOFT, Ui.MEDIUM);
        tab.button.setGravity(Gravity.CENTER);
        tab.button.setSingleLine(true);
        tab.button.setEllipsize(TextUtils.TruncateAt.END);
        tab.button.setMinWidth(dp(68));
        tab.button.setMaxWidth(dp(180));
        tab.button.setPadding(dp(14), 0, dp(14), 0);
        tab.button.setFocusable(true);
        tab.button.setOnClickListener(v -> select(tab));
        if (tab != local) tab.button.setOnLongClickListener(v -> { edit(tab); return true; });
        LinearLayout.LayoutParams size = Ui.lp(-2, dp(44));
        size.rightMargin = dp(4);
        labels.addView(tab.button, size);
        style(tab);
    }

    private void style(Tab tab) {
        boolean active = selected == tab;
        tab.button.setSelected(active);
        tab.button.setTextColor(active ? Ui.TEXT : Ui.TEXT_SOFT);
        tab.button.setBackground(Ui.ripple(active ? Ui.ACCENT_SOFT : Ui.BG_ELEVATED, 10f, activity));
    }

    private void select(Tab tab) {
        Tab previous = selected;
        if (tab != previous && previous.page != null) {
            View focused = previous.page.findFocus();
            if (focused != null) {
                InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
            previous.page.setVisibility(View.GONE);
        }
        if (tab.page == null) {
            tab.remote = new RemoteWebPage(activity, tab.address, files, openBrowser);
            tab.page = tab.remote.view();
            pages.addView(tab.page, new FrameLayout.LayoutParams(-1, -1));
        }
        selected = tab;
        tab.page.setVisibility(View.VISIBLE);
        style(previous);
        style(tab);
        tab.button.post(() -> {
            if (selected == tab) {
                tab.button.requestRectangleOnScreen(new Rect(0, 0, tab.button.getWidth(), tab.button.getHeight()));
            }
        });
    }

    private void edit(Tab tab) {
        LinearLayout form = Ui.column(activity);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = new EditText(activity);
        name.setHint("Nome da aba");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(name, Ui.lp(-1, -2));
        EditText address = new EditText(activity);
        address.setHint("Endereço (ex.: taguai:3080)");
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(address, Ui.lp(-1, -2));
        if (tab != null) {
            name.setText(tab.label);
            address.setText(tab.address.toString());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(tab == null ? "Adicionar aba" : "Editar aba")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(tab == null ? "Adicionar" : "Salvar", null);
        if (tab != null) builder.setNeutralButton("Remover aba", (dialog, which) -> remove(tab));
        AlertDialog dialog = builder.create();
        name.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) {
                name.setError("Informe um nome para a aba.");
                return;
            }
            URI url = parseAddress(address.getText().toString());
            if (url == null) {
                address.setError("Use HTTP ou HTTPS e informe a porta (ex.: taguai:3080).");
                return;
            }
            Tab saved = tab;
            if (saved == null) {
                saved = new Tab(title, url);
                tabs.add(saved);
                addLabel(saved);
            } else {
                if (!url.equals(saved.address)) closePage(saved);
                saved.label = title;
                saved.address = url;
                saved.button.setText(title);
            }
            save();
            dialog.dismiss();
            select(saved);
        });
    }

    private void remove(Tab tab) {
        if (selected == tab) select(local);
        closePage(tab);
        labels.removeView(tab.button);
        tabs.remove(tab);
        save();
    }

    private void closePage(Tab tab) {
        if (tab.remote == null) return;
        pages.removeView(tab.page);
        tab.remote.destroy();
        tab.remote = null;
        tab.page = null;
    }

    private void restore() {
        try {
            JSONArray saved = new JSONArray(prefs.getString("remote", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.optJSONObject(i);
                if (entry == null) continue;
                String name = entry.optString("label").trim();
                URI address = parseAddress(entry.optString("url"));
                if (name.isEmpty() || address == null) continue;
                Tab tab = new Tab(name, address);
                tabs.add(tab);
                addLabel(tab);
            }
        } catch (JSONException ignored) { }
    }

    private void save() {
        JSONArray entries = new JSONArray();
        for (Tab tab : tabs) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("label", tab.label);
                entry.put("url", tab.address.toASCIIString());
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            entries.put(entry);
        }
        prefs.edit().putString("remote", entries.toString()).apply();
    }

    static URI parseAddress(String value) {
        String address = value.trim();
        int schemeEnd = address.indexOf("://");
        address = schemeEnd < 0 ? "http://" + address
                : address.substring(0, schemeEnd).toLowerCase(Locale.ROOT) + address.substring(schemeEnd);
        try {
            URI url = new URI(address);
            if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme()))) return null;
            if (url.getHost() == null || url.getRawUserInfo() != null || url.getPort() < 1 || url.getPort() > 65535) return null;
            return url;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private int dp(int value) { return Ui.dp(activity, value); }

    private static final class Tab {
        String label;
        URI address;
        TextView button;
        View page;
        RemoteWebPage remote;

        Tab(String label, URI address) {
            this.label = label;
            this.address = address;
        }
    }
}
